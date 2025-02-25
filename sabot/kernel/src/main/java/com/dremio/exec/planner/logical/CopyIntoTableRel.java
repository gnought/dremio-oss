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
package com.dremio.exec.planner.logical;

import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;

import com.dremio.exec.planner.common.CopyIntoTableRelBase;
import com.dremio.exec.planner.sql.handlers.query.CopyIntoTableContext;

/**
 * Drel for 'COPY INTO'
 */
public class CopyIntoTableRel extends CopyIntoTableRelBase implements Rel {

  public CopyIntoTableRel(RelOptCluster cluster,
                          RelTraitSet traitSet,
                          RelOptTable table,
                          RelDataType rowType,
                          CopyIntoTableContext config) {
    super(LOGICAL, cluster, traitSet, table, rowType, config);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new CopyIntoTableRel(getCluster(), traitSet, getTable(), getRowType(), getContext());
  }
}
