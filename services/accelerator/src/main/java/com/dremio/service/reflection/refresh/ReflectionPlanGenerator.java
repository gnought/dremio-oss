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
package com.dremio.service.reflection.refresh;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.TimestampString;

import com.dremio.catalog.model.dataset.TableVersionType;
import com.dremio.common.config.SabotConfig;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.EntityExplorer;
import com.dremio.exec.catalog.VersionedDatasetId;
import com.dremio.exec.ops.SnapshotDiffContext;
import com.dremio.exec.planner.sql.SqlExceptionHelper;
import com.dremio.exec.planner.sql.handlers.ConvertedRelNode;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.SqlToRelTransformer;
import com.dremio.exec.planner.sql.parser.SqlUnresolvedVersionedTableMacro;
import com.dremio.exec.planner.sql.parser.SqlVersionedTableCollectionCall;
import com.dremio.exec.planner.sql.parser.SqlVersionedTableMacroCall;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.tablefunctions.TableMacroNames;
import com.dremio.exec.work.foreman.ForemanSetupException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.reflection.ReflectionSettings;
import com.dremio.service.reflection.ReflectionUtils;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.RefreshDecision;
import com.dremio.service.reflection.store.MaterializationStore;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

/**
 * Encapsulates all the logic needed to generate a reflection's plan
 */
public class ReflectionPlanGenerator {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ReflectionPlanGenerator.class);

  private final CatalogService catalogService;
  private final SabotConfig config;
  private final SqlHandlerConfig sqlHandlerConfig;
  private final ReflectionGoal goal;
  private final ReflectionEntry entry;
  private final Materialization materialization;
  private final ReflectionSettings reflectionSettings;
  private final MaterializationStore materializationStore;
  private final boolean forceFullUpdate;
  private final int stripVersion;
  private final boolean isRebuildPlan;

  private RefreshDecision refreshDecision;

  private SnapshotDiffContext snapshotDiffContext = SnapshotDiffContext.NO_SNAPSHOT_DIFF;

  public ReflectionPlanGenerator(
    SqlHandlerConfig sqlHandlerConfig,
    CatalogService catalogService,
    SabotConfig config,
    ReflectionGoal goal,
    ReflectionEntry entry,
    Materialization materialization,
    ReflectionSettings reflectionSettings,
    MaterializationStore materializationStore,
    boolean forceFullUpdate,
    int stripVersion,
    boolean isRebuildPlan) {
    this.catalogService = Preconditions.checkNotNull(catalogService, "Catalog service required");
    this.config = Preconditions.checkNotNull(config, "sabot config required");
    this.sqlHandlerConfig = Preconditions.checkNotNull(sqlHandlerConfig, "SqlHandlerConfig required.");
    this.entry = entry;
    this.goal = goal;
    this.materialization = materialization;
    this.reflectionSettings = reflectionSettings;
    this.materializationStore = materializationStore;
    this.forceFullUpdate = forceFullUpdate;
    this.stripVersion = stripVersion;
    this.isRebuildPlan = isRebuildPlan;
  }

  public RefreshDecision getRefreshDecision() {
    return refreshDecision;
  }

  public RelNode generateNormalizedPlan() {

    ReflectionPlanNormalizer planNormalizer = new ReflectionPlanNormalizer(
      sqlHandlerConfig,
      goal,
      entry,
      materialization,
      catalogService,
      config,
      reflectionSettings,
      materializationStore,
      forceFullUpdate,
      stripVersion,
      isRebuildPlan
    );
    // retrieve reflection's dataset
    final EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService);
    DatasetConfig datasetConfig = CatalogUtil.getDatasetConfig(catalog, goal.getDatasetId());
    if (datasetConfig == null) {
      throw new IllegalStateException(String.format("Dataset %s not found for %s", goal.getDatasetId(), ReflectionUtils.getId(goal)));
    }
    final SqlSelect select = generateSelectStarFromDataset(datasetConfig);
    try {
      ConvertedRelNode converted = SqlToRelTransformer.validateAndConvert(sqlHandlerConfig, select, planNormalizer);
      this.refreshDecision = planNormalizer.getRefreshDecision();
      this.snapshotDiffContext = planNormalizer.getSnapshotDiffContext();
      return converted.getConvertedNode();
    } catch (ForemanSetupException | RelConversionException | ValidationException e) {
      throw Throwables.propagate(SqlExceptionHelper.coerceException(logger, select.toString(), e, false));
    }
  }

  /**
   * Given a DatasetConfig, generate a SqlSelect that does
   * Select * from Dataset
   * In addition we take special care to make sure we resolve the
   * correct dataset version to use
   */
  public static SqlSelect generateSelectStarFromDataset(DatasetConfig datasetConfig) {
    // generate dataset's plan and viewFieldTypes
    final NamespaceKey path = new NamespaceKey(datasetConfig.getFullPathList());
    final SqlNode from;
    final VersionedDatasetId versionedDatasetId = ReflectionUtils.getVersionDatasetId(datasetConfig.getId().getId());
    if (versionedDatasetId != null) {
      // For reflections on versioned datasets, call UDF to resolve to the correct dataset version
      final TableVersionType tableVersionType = versionedDatasetId.getVersionContext().getType();
      SqlNode versionSpecifier = SqlLiteral.createCharString(versionedDatasetId.getVersionContext().getValue().toString(), SqlParserPos.ZERO);
      if (tableVersionType == TableVersionType.TIMESTAMP) {
        versionSpecifier = SqlLiteral.createTimestamp(TimestampString.fromMillisSinceEpoch(
          Long.valueOf(versionedDatasetId.getVersionContext().getValue().toString())), 0, SqlParserPos.ZERO);
      }
      from = new SqlVersionedTableCollectionCall(SqlParserPos.ZERO,
        new SqlVersionedTableMacroCall(
          new SqlUnresolvedVersionedTableMacro(
              new SqlIdentifier(TableMacroNames.TIME_TRAVEL, SqlParserPos.ZERO), tableVersionType, versionSpecifier, null),
          new SqlNode[]{SqlLiteral.createCharString(path.getSchemaPath(), SqlParserPos.ZERO)},
          SqlParserPos.ZERO)
        );
    } else {
      from = new SqlIdentifier(path.getPathComponents(), SqlParserPos.ZERO);
    }

    return new SqlSelect(
        SqlParserPos.ZERO,
        new SqlNodeList(SqlParserPos.ZERO),
        new SqlNodeList(ImmutableList.<SqlNode>of(SqlIdentifier.star(SqlParserPos.ZERO)), SqlParserPos.ZERO),
        from,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
        );
  }

  public SnapshotDiffContext getSnapshotDiffContext() {
    return snapshotDiffContext;
  }
}
