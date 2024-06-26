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
package com.dremio.exec.store.parquet;

import com.dremio.common.expression.CompleteType;

/** Class to override creation of filters for reading Parquet files */
public class ParquetFilterCreator {
  public static final ParquetFilterCreator DEFAULT = new ParquetFilterCreator();

  public ParquetFilterIface getParquetFilter(
      ParquetFilterCondition filterCondition,
      CompleteType tableColumnType,
      String filteredColumn,
      CompleteType fileColumnType) {
    return filterCondition.getFilter();
  }

  public ParquetFilterIface rewriteIfNecessary(
      ParquetFilterIface filter,
      CompleteType tableColumnType,
      String filteredColumn,
      CompleteType fileColumnType) {
    return filter;
  }

  public boolean filterMayChange() {
    return false;
  }
}
