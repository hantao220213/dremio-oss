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
package com.dremio.common.logical.data;

import com.dremio.common.logical.data.visitors.LogicalVisitor;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.Lists;
import java.util.Iterator;
import java.util.List;

@JsonTypeName("union")
public class Union extends LogicalOperatorBase {
  private final List<LogicalOperator> inputs;
  private final boolean distinct;

  @JsonCreator
  public Union(
      @JsonProperty("inputs") List<LogicalOperator> inputs,
      @JsonProperty("distinct") Boolean distinct) {
    this.inputs = inputs;
    for (LogicalOperator o : inputs) {
      o.registerAsSubscriber(this);
    }
    this.distinct = distinct == null ? false : distinct;
  }

  public List<LogicalOperator> getInputs() {
    return inputs;
  }

  public boolean isDistinct() {
    return distinct;
  }

  @Override
  public <T, X, E extends Throwable> T accept(LogicalVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitUnion(this, value);
  }

  @Override
  public Iterator<LogicalOperator> iterator() {
    return inputs.iterator();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuilder<Union> {
    private List<LogicalOperator> inputs = Lists.newArrayList();
    private boolean distinct;

    public Builder addInput(LogicalOperator o) {
      inputs.add(o);
      return this;
    }

    public Builder setDistinct(boolean distinct) {
      this.distinct = distinct;
      return this;
    }

    @Override
    public Union build() {
      return new Union(inputs, distinct);
    }
  }
}
