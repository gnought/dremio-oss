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
package com.dremio.exec.planner.sql.parser;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import com.dremio.common.utils.PathUtils;
import com.dremio.exec.catalog.Catalog;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * SqlPolicy
 */
public class SqlPolicy extends SqlCall {
  private static final SqlSpecialOperator OPERATOR = new SqlSpecialOperator("POLICY", SqlKind.OTHER)
  {
    @Override
    public SqlCall createCall(SqlLiteral functionQualifier, SqlParserPos pos, SqlNode... operands) {
      Preconditions.checkArgument(operands.length == 2, "SqlPolicy.createCall() has to get 2 operand!");
      return new SqlPolicy(pos, (SqlIdentifier) operands[0], (SqlNodeList) operands[1]);
    }
  };

  private final SqlIdentifier name;
  private final SqlNodeList columns;

  /**
   * Creates a SqlPolicy.
   */
  public SqlPolicy(SqlParserPos pos, SqlIdentifier name, SqlNodeList columns) {
    super(pos);
    this.name = name;
    this.columns = columns;
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  public String getName(Catalog catalog) {
    return getNamespaceKey(catalog).toString();
  }

  public NamespaceKey getNamespaceKey(Catalog catalog) {
    return catalog.resolveSingle(
      new NamespaceKey(PathUtils.parseFullPath(new NamespaceKey(name.names).toString())));
  }

  public SqlNodeList getColumns() {
    return columns;
  }

  @Override
  public List<SqlNode> getOperandList() {
    return ImmutableList.of(name, columns);
  }

  public List<String> getArgs() {
    return columns.getList().stream().map(c -> ((SqlIdentifier) c).getSimple()).collect(Collectors.toList());
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    name.unparse(writer, leftPrec, rightPrec);
    writer.keyword("(");
    columns.unparse(writer, leftPrec, rightPrec);
    writer.keyword(")");
  }
}
