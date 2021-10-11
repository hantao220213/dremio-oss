/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.logical.partition;

import static com.dremio.exec.planner.logical.partition.PartitionStatsBasedPruner.TestCounters.incrementEvaluatedWithEvalCounter;
import static com.dremio.exec.planner.logical.partition.PartitionStatsBasedPruner.TestCounters.incrementEvaluatedWithSargCounter;
import static org.apache.calcite.sql.SqlKind.AND;
import static org.apache.calcite.sql.SqlKind.EQUALS;
import static org.apache.calcite.sql.SqlKind.GREATER_THAN;
import static org.apache.calcite.sql.SqlKind.GREATER_THAN_OR_EQUAL;
import static org.apache.calcite.sql.SqlKind.LESS_THAN;
import static org.apache.calcite.sql.SqlKind.LESS_THAN_OR_EQUAL;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatsEntry;
import org.apache.iceberg.PartitionStatsReader;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.io.CloseableIterator;

import com.dremio.common.AutoCloseables;
import com.dremio.common.VM;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.types.TypeProtos;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.logical.partition.FindSimpleFilters.StateHolder;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.TableMetadata;
import com.dremio.service.Pointer;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;

/**
 * Implementation of {@link RecordPruner} which prunes based on Iceberg partition stats
 */
public class PartitionStatsBasedPruner extends RecordPruner {
  private static final List<SqlKind> SARG_PRUNEABLE_OPERATORS = Arrays.asList(EQUALS, AND, LESS_THAN,
    LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL);

  private final PartitionSpec partitionSpec;
  private final CloseableIterator<PartitionStatsEntry> statsEntryIterator;
  private Map<Integer, Integer> partitionColIdToSpecColIdMap; // partition col ID to col ID in Iceberg partition spec
  private Map<String, Integer> partitionColNameToSpecColIdMap; // col name to col ID in Iceberg partition spec

  public PartitionStatsBasedPruner(PartitionStatsReader partitionStatsReader, OptimizerRulesContext rulesContext,
                                   PartitionSpec partitionSpec) {
    super(rulesContext);
    this.statsEntryIterator = partitionStatsReader.iterator();
    this.partitionSpec = partitionSpec;
  }

  @Override
  public long prune(
    Map<Integer, String> inUseColIdToNameMap,
    Map<String, Integer> partitionColToIdMap,
    Function<RexNode, List<Integer>> usedIndexes,
    List<SchemaPath> projectedColumns,
    TableMetadata tableMetadata,
    RexNode pruneCondition,
    BatchSchema batchSchema,
    RelDataType rowType,
    RelOptCluster cluster) {

    setup(inUseColIdToNameMap, batchSchema);
    populateMaps(inUseColIdToNameMap);

    FindSimpleFilters rexVisitor = new FindSimpleFilters(cluster.getRexBuilder(), true, false, SARG_PRUNEABLE_OPERATORS);
    ImmutableList<RexCall> rexConditions = ImmutableList.of();
    StateHolder holder = pruneCondition.accept(rexVisitor);
    if (!hasDecimalCols(holder)) {
      rexConditions = holder.getConditions();
    }

    long qualifiedCount = 0;
    ConditionsByColumn conditionsByColumn = buildSargPrunableConditions(usedIndexes, projectedColumns, rexVisitor, rexConditions);
    if (canPruneWhollyWithSarg(holder, conditionsByColumn)) {
      qualifiedCount = evaluateWithSarg(conditionsByColumn);
    } else {
      int batchIndex = 0;
      long[] recordCounts = null;
      LogicalExpression materializedExpr = null;

      while (statsEntryIterator.hasNext()) {
        List<PartitionStatsEntry> entriesInBatch = createRecordBatch(conditionsByColumn, batchIndex);
        int batchSize = entriesInBatch.size();
        if (batchSize == 0) {
          logger.debug("batch: {} is empty as sarg pruned all entries", batchIndex);
          break;
        }

        if (batchIndex == 0) {
          setupVectors(inUseColIdToNameMap, partitionColToIdMap, batchSize);
          materializedExpr = materializePruneExpr(pruneCondition, rowType, cluster);
          recordCounts = new long[batchSize];
        }

        populateVectors(batchIndex, recordCounts, entriesInBatch);
        evaluateExpr(materializedExpr, batchSize);

        // Count the number of records in the splits that survived the expression evaluation
        for (int i = 0; i < batchSize; ++i) {
          if (!outputVector.isNull(i) && outputVector.get(i) == 1) {
            qualifiedCount += recordCounts[i];
          }
        }

        logger.debug("Within batch: {}, qualified records: {}", batchIndex, qualifiedCount);
        batchIndex++;
      }
    }

    updateCounters(holder);
    return qualifiedCount;
  }

  @Override
  public void close() {
    AutoCloseables.close(RuntimeException.class, statsEntryIterator, super::close);
  }

  private void updateCounters(StateHolder holder) {
    // Update test counters only when assertions are enabled, which is the case for test flow
    if (VM.areAssertsEnabled()) {
      boolean hasDecimalCols = hasDecimalCols(holder);
      if (holder.hasConditions() && !hasDecimalCols) {
        incrementEvaluatedWithSargCounter(); // expression was partly or wholly evaluated with sarg
      }

      if (holder.hasRemainingExpression() || hasDecimalCols) {
        incrementEvaluatedWithEvalCounter();
      }
    }
  }

  private long evaluateWithSarg(ConditionsByColumn conditionsByColumn) {
    timer.start();
    long qualifiedCount = 0;
    while (statsEntryIterator.hasNext()) {
      PartitionStatsEntry statsEntry = statsEntryIterator.next();
      StructLike partitionData = statsEntry.getPartition();
      if (isRecordMatch(conditionsByColumn, partitionData)) {
        qualifiedCount += statsEntry.getRecordCount();
      }
    }
    logger.debug("Elapsed time to find surviving records: {}ms", timer.elapsed(TimeUnit.MILLISECONDS));
    timer.reset();
    return qualifiedCount;
  }

  private List<PartitionStatsEntry> createRecordBatch(ConditionsByColumn conditionsByColumn, int batchIndex) {
    timer.start();
    List<PartitionStatsEntry> entriesInBatch = new ArrayList<>();
    while (entriesInBatch.size() < PARTITION_BATCH_SIZE && statsEntryIterator.hasNext()) {
      PartitionStatsEntry statsEntry = statsEntryIterator.next();
      StructLike partitionData = statsEntry.getPartition();
      if (!conditionsByColumn.hasConditions() || isRecordMatch(conditionsByColumn, partitionData)) {
        entriesInBatch.add(statsEntry);
      }
    }

    logger.debug("Elapsed time to get list of partition stats entries for the current batch: {}ms within batchIndex: {}",
      timer.elapsed(TimeUnit.MILLISECONDS), batchIndex);
    timer.reset();
    return entriesInBatch;
  }

  private void populateVectors(int batchIndex, long[] recordCounts, List<PartitionStatsEntry> entriesInBatch) {
    timer.start();
    // Loop over partition stats and populate record count, vectors etc.
    for (int i = 0; i < entriesInBatch.size(); i++) {
      PartitionStatsEntry statsEntry = entriesInBatch.get(i);
      StructLike partitionData = statsEntry.getPartition();
      recordCounts[i] = statsEntry.getRecordCount();
      writePartitionValues(i, partitionData);
    }
    logger.debug("Elapsed time to populate partition column vectors: {}ms within batchIndex: {}",
      timer.elapsed(TimeUnit.MILLISECONDS), batchIndex);
    timer.reset();
  }

  private boolean canPruneWhollyWithSarg(StateHolder holder, ConditionsByColumn conditionsByColumn) {
    return !holder.hasRemainingExpression() && conditionsByColumn.hasConditions();
  }

  private ConditionsByColumn buildSargPrunableConditions(Function<RexNode, List<Integer>> usedIndexes,
                                                         List<SchemaPath> projectedColumns,
                                                         FindSimpleFilters rexVisitor,
                                                         ImmutableList<RexCall> rexConditions) {
    ConditionsByColumn conditionsByColumn = new ConditionsByColumn();
    for (RexCall condition : rexConditions) {
      ImmutableList<RexNode> ops = condition.operands;
      if (ops.size() != 2) {
        continue; // We're only interested in conditions with two operands
      }

      StateHolder a = ops.get(0).accept(rexVisitor);
      StateHolder b = ops.get(1).accept(rexVisitor);
      if (a.getType() == FindSimpleFilters.Type.LITERAL && b.getType() == FindSimpleFilters.Type.INPUT) {
        // e.g. '2020' = year
        addCondition(usedIndexes, projectedColumns, condition, conditionsByColumn, b, a);
      } else if (a.getType() == FindSimpleFilters.Type.INPUT && b.getType() == FindSimpleFilters.Type.LITERAL) {
        // e.g. year = '2020'
        addCondition(usedIndexes, projectedColumns, condition, conditionsByColumn, a, b);
      }
    }
    return conditionsByColumn;
  }

  private void addCondition(Function<RexNode, List<Integer>> usedIndexes, List<SchemaPath> projectedColumns,
                            RexCall condition, ConditionsByColumn conditionsByColumn,
                            StateHolder input, StateHolder literal) {
    int colIndex = usedIndexes.apply(input.getNode()).stream().findFirst().get();
    String colName = projectedColumns.get(colIndex).getAsUnescapedPath();
    Comparable<?> value = getValueFromFilter(colName, literal.getNode());
    conditionsByColumn.addCondition(colName, new Condition(condition.getKind(), value));
  }

  private boolean isRecordMatch(ConditionsByColumn conditionsByColumn, StructLike partitionData) {
    for (String colName : conditionsByColumn.getColumnNames()) {
      Integer indexInPartitionSpec = partitionColNameToSpecColIdMap.get(colName);
      Comparable<?> valueFromPartitionData = getValueFromPartitionData(indexInPartitionSpec, colName, partitionData);
      if (!conditionsByColumn.satisfiesComparison(colName, valueFromPartitionData)) {
        return false;
      }
    }
    return true;
  }

  private boolean hasDecimalCols(StateHolder holder) {
    Pointer<Boolean> hasDecimalCols = new Pointer<>(false);
    holder.getConditions().forEach(condition -> condition.getOperands().forEach(
      operand -> {
        if (operand.getKind().equals(SqlKind.INPUT_REF) && operand.getType().getSqlTypeName().equals(SqlTypeName.DECIMAL)) {
          hasDecimalCols.value = true;
        }
      }));
    return hasDecimalCols.value;
  }

  private Comparable getValueFromFilter(String colName, RexNode node) {
    TypeProtos.MinorType minorType = partitionColNameToTypeMap.get(colName).getMinorType();
    RexLiteral literal = (RexLiteral) node;
    switch (minorType) {
      case BIT:
        return literal.getValueAs(Boolean.class);
      case INT:
      case DATE:
      case TIME:
        return literal.getValueAs(Integer.class);
      case BIGINT:
      case TIMESTAMP:
        return literal.getValueAs(Long.class);
      case FLOAT4:
        return literal.getValueAs(Float.class);
      case FLOAT8:
        return literal.getValueAs(Double.class);
      case VARCHAR:
        return literal.getValueAs(String.class);
      default:
        throw new UnsupportedOperationException("Unsupported type: " + minorType);
    }
  }

  private Comparable getValueFromPartitionData(int indexInPartitionSpec, String colName, StructLike partitionData) {
    MajorType majorType = partitionColNameToTypeMap.get(colName);
    switch (majorType.getMinorType()) {
      case BIT:
        return partitionData.get(indexInPartitionSpec, Boolean.class);
      case INT:
      case DATE:
        return partitionData.get(indexInPartitionSpec, Integer.class);
      case BIGINT:
        return partitionData.get(indexInPartitionSpec, Long.class);
      case TIME:
      case TIMESTAMP:
        // Divide by 1000 to convert microseconds to millis
        return partitionData.get(indexInPartitionSpec, Long.class) / 1000;
      case FLOAT4:
        return partitionData.get(indexInPartitionSpec, Float.class);
      case FLOAT8:
        return partitionData.get(indexInPartitionSpec, Double.class);
      case VARBINARY:
        return partitionData.get(indexInPartitionSpec, ByteBuffer.class);
      case FIXEDSIZEBINARY:
      case VARCHAR:
        return partitionData.get(indexInPartitionSpec, String.class);
      default:
        throw new UnsupportedOperationException("Unsupported type: " + majorType);
    }
  }

  private void populateMaps(Map<Integer, String> fieldNameMap) {
    Set<Integer> partitionColIds = partitionColIdToTypeMap.keySet();
    partitionColIdToSpecColIdMap = new HashMap<>(partitionColIds.size());
    partitionColNameToSpecColIdMap = new HashMap<>(partitionColIds.size());
    for (Integer partitionColIndex : partitionColIds) {
      String colName = fieldNameMap.get(partitionColIndex);
      List<PartitionField> fields = partitionSpec.fields();
      for (int i = 0; i < fields.size(); i++) {
        if (fields.get(i).name().equalsIgnoreCase(colName)) {
          partitionColIdToSpecColIdMap.put(partitionColIndex, i);
          partitionColNameToSpecColIdMap.put(colName, partitionColIndex);
          break;
        }
      }
    }
  }

  /**
   * Sets all the vectors for a given entry in the batch
   */
  private void writePartitionValues(int row, StructLike partitionData) {
    for (int partitionColIndex : partitionColIdToTypeMap.keySet()) {
      int indexInPartitionSpec = partitionColIdToSpecColIdMap.get(partitionColIndex);
      MajorType majorType = partitionColIdToTypeMap.get(partitionColIndex);
      ValueVector vv = vectors[partitionColIndex];

      switch (majorType.getMinorType()) {
        case BIT:
          BitVector bitVector = (BitVector) vv;
          Boolean boolVal = partitionData.get(indexInPartitionSpec, Boolean.class);
          if (boolVal != null) {
            bitVector.set(row, boolVal.equals(Boolean.TRUE) ? 1 : 0);
          }
          break;
        case INT:
          IntVector intVector = (IntVector) vv;
          Integer intVal = partitionData.get(indexInPartitionSpec, Integer.class);
          if (intVal != null) {
            intVector.set(row, intVal);
          }
          break;
        case BIGINT:
          BigIntVector bigIntVector = (BigIntVector) vv;
          Long longVal = partitionData.get(indexInPartitionSpec, Long.class);
          if (longVal != null) {
            bigIntVector.set(row, longVal);
          }
          break;
        case FLOAT4:
          Float4Vector float4Vector = (Float4Vector) vv;
          Float floatVal = partitionData.get(indexInPartitionSpec, Float.class);
          if (floatVal != null) {
            float4Vector.set(row, floatVal);
          }
          break;
        case FLOAT8:
          Float8Vector float8Vector = (Float8Vector) vv;
          Double doubleVal = partitionData.get(indexInPartitionSpec, Double.class);
          if (doubleVal != null) {
            float8Vector.set(row, doubleVal);
          }
          break;
        case VARBINARY:
          VarBinaryVector varBinaryVector = (VarBinaryVector) vv;
          ByteBuffer byteBuf = partitionData.get(indexInPartitionSpec, ByteBuffer.class);
          if (byteBuf != null) {
            byte[] bytes = byteBuf.array();
            varBinaryVector.setSafe(row, bytes, 0, bytes.length);
          }
          break;
        case FIXEDSIZEBINARY:
        case VARCHAR:
          VarCharVector varCharVector = (VarCharVector) vv;
          String stringVal = partitionData.get(indexInPartitionSpec, String.class);
          if (stringVal != null) {
            byte[] stringBytes = stringVal.getBytes();
            varCharVector.setSafe(row, stringBytes, 0, stringBytes.length);
          }
          break;
        case DATE:
          DateMilliVector dateVector = (DateMilliVector) vv;
          Integer dateVal = partitionData.get(indexInPartitionSpec, Integer.class);
          if (dateVal != null) {
            dateVector.set(row, TimeUnit.DAYS.toMillis(dateVal));
          }
          break;
        case TIME:
          TimeMilliVector timeVector = (TimeMilliVector) vv;
          Long timeVal = partitionData.get(indexInPartitionSpec, Long.class);
          if (timeVal != null) {
            // Divide by 1000 to convert microseconds to millis
            timeVector.set(row, Math.toIntExact(timeVal / 1000));
          }
          break;
        case TIMESTAMP:
          TimeStampMilliVector timeStampVector = (TimeStampMilliVector) vv;
          Long timestampVal = partitionData.get(indexInPartitionSpec, Long.class);
          if (timestampVal != null) {
            // Divide by 1000 to convert microseconds to millis
            timeStampVector.set(row, timestampVal / 1000);
          }
          break;
        case DECIMAL:
          DecimalVector decimal = (DecimalVector) vv;
          BigDecimal decimalVal = partitionData.get(indexInPartitionSpec, BigDecimal.class);
          if (decimalVal != null) {
            byte[] bytes = decimalVal.unscaledValue().toByteArray();
            /* set the bytes in LE format in the buffer of decimal vector, we will swap
             * the bytes while writing into the vector.
             */
            decimal.setBigEndian(row, bytes);
          }
          break;
        default:
          throw new UnsupportedOperationException("Unsupported type: " + majorType);
      }
    }
  }

  /**
   * Maintains counters which tests can use to verify the behavior
   */
  @com.google.common.annotations.VisibleForTesting
  public static class TestCounters {
    private static final AtomicLong evaluatedWithSarg = new AtomicLong(0);
    private static final AtomicLong evaluatedWithEval = new AtomicLong(0);

    public static void incrementEvaluatedWithSargCounter() {
      evaluatedWithSarg.incrementAndGet();
    }

    public static void incrementEvaluatedWithEvalCounter() {
      evaluatedWithEval.incrementAndGet();
    }

    public static long getEvaluatedWithSargCounter() {
      return evaluatedWithSarg.get();
    }

    public static long getEvaluatedWithEvalCounter() {
      return evaluatedWithEval.get();
    }

    public static void resetCounters() {
      evaluatedWithSarg.set(0);
      evaluatedWithEval.set(0);
    }
  }

  private static class ConditionsByColumn {
    Multimap<String, Condition> queryConditions = HashMultimap.create();

    void addCondition(String colName, Condition condition) {
      queryConditions.put(colName, condition);
    }

    boolean hasConditions() {
      return !queryConditions.isEmpty();
    }

    boolean satisfiesComparison(String colName, Comparable valueFromPartitionData) {
      for (Condition condition : queryConditions.get(colName)) {
        if (valueFromPartitionData == null || !condition.matches(valueFromPartitionData)) {
          return false;
        }
      }
      return true;
    }

    public Set<String> getColumnNames() {
      return queryConditions.keySet();
    }
  }

  private static class Condition {
    final Predicate<Comparable> matcher;

    Condition(SqlKind sqlKind, Comparable<?> valueFromCondition) {
      switch (sqlKind) {
        case EQUALS:
          matcher = valueFromPartitionData -> valueFromPartitionData.compareTo(valueFromCondition) == 0;
          break;
        case LESS_THAN:
          matcher = valueFromPartitionData -> valueFromPartitionData.compareTo(valueFromCondition) < 0;
          break;
        case LESS_THAN_OR_EQUAL:
          matcher = valueFromPartitionData -> valueFromPartitionData.compareTo(valueFromCondition) <= 0;
          break;
        case GREATER_THAN:
          matcher = valueFromPartitionData -> valueFromPartitionData.compareTo(valueFromCondition) > 0;
          break;
        case GREATER_THAN_OR_EQUAL:
          matcher = valueFromPartitionData -> valueFromPartitionData.compareTo(valueFromCondition) >= 0;
          break;
        default:
          throw new IllegalStateException("Unsupported SQL operator type: " + sqlKind);
      }
    }

    boolean matches(Comparable valueFromPartitionData) {
      return matcher.test(valueFromPartitionData);
    }
  }
}