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
package com.dremio.exec.planner.sql;

import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;

import com.google.common.base.Preconditions;

public final class Checker implements SqlOperandTypeChecker {
  private final SqlOperandCountRange range;

  private Checker(SqlOperandCountRange range) {
    Preconditions.checkNotNull(range);
    this.range = range;
  }

  public static Checker of(int size) {
    return new Checker(SqlOperandCountRanges.of(size));
  }

  public static Checker between(int min, int max) {
    return new Checker(SqlOperandCountRanges.between(min, max));
  }

  public static Checker any() {
    return new Checker(SqlOperandCountRanges.any());
  }

  @Override
  public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
    return true;
  }

  @Override
  public SqlOperandCountRange getOperandCountRange() {
    return range;
  }

  @Override
  public String getAllowedSignatures(SqlOperator op, String opName) {
    return opName + "(Dremio - Opaque)";
  }

  @Override
  public Consistency getConsistency() {
    return Consistency.NONE;
  }

  @Override
  public boolean isOptional(int i) {
    return false;
  }
}
