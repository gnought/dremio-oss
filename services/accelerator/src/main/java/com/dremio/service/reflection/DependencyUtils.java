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
package com.dremio.service.reflection;

import static com.dremio.service.reflection.ReflectionServiceImpl.ACCELERATOR_STORAGEPLUGIN_NAME;
import static com.dremio.service.reflection.materialization.AccelerationStoragePlugin.TABLE_SCHEMA_PATH_REFLECTION_ID_COMPONENT;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.utils.PathUtils;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.EntityExplorer;
import com.dremio.exec.store.CatalogService;
import com.dremio.service.job.proto.JobInfo;
import com.dremio.service.job.proto.ScanPath;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.reflection.DependencyEntry.DatasetDependency;
import com.dremio.service.reflection.DependencyEntry.ReflectionDependency;
import com.dremio.service.reflection.DependencyEntry.TableFunctionDependency;
import com.dremio.service.reflection.proto.DependencyType;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.RefreshDecision;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Sets;

/**
 * Helper functions to deal with reflection dependencies
 */
public class DependencyUtils {

  private static final Predicate<DependencyEntry> IS_REFLECTION = new Predicate<DependencyEntry>() {
    @Override
    public boolean apply(DependencyEntry entry) {
      return entry.getType() == DependencyType.REFLECTION;
    }
  };

  private static final Predicate<DependencyEntry> IS_DATASET = new Predicate<DependencyEntry>() {
    @Override
    public boolean apply(DependencyEntry entry) {
      return entry.getType() == DependencyType.DATASET;
    }
  };

  private static final Predicate<DependencyEntry> IS_TABLEFUNCTION = new Predicate<DependencyEntry>() {
    @Override
    public boolean apply(DependencyEntry entry) {
      return entry.getType() == DependencyType.TABLEFUNCTION;
    }
  };

  public static String describeDependencies(ReflectionId reflectionId, Iterable<DependencyEntry> dependencyEntries) {
    final StringBuilder builder = new StringBuilder();
    builder.append("reflection ").append(reflectionId.getId()).append(" depends on : {\n");
    for (DependencyEntry dependency : dependencyEntries) {
      if (dependency.getType() == DependencyType.REFLECTION) {
        final ReflectionId rId = ((ReflectionDependency) dependency).getReflectionId();
        builder.append("  reflection ").append(rId.getId()).append("\n");
      } else if (dependency.getType() == DependencyType.DATASET){
        final List<String> path = ((DatasetDependency) dependency).getPath();
        builder.append("  dataset ").append(PathUtils.constructFullPath(path)).append("\n");
      } else {
        final String souceName = ((TableFunctionDependency) dependency).getSourceName();
        builder.append(" table function ").append(souceName);
      }
    }
    builder.append("}\n");
    return builder.toString();
  }

  static FluentIterable<ReflectionDependency> filterReflectionDependencies(Iterable<DependencyEntry> dependencyEntries) {
    return FluentIterable.from(dependencyEntries)
      .filter(IS_REFLECTION)
      .transform(new Function<DependencyEntry, ReflectionDependency>() {
        @Override
        public ReflectionDependency apply(DependencyEntry entry) {
          return (ReflectionDependency) entry;
        }
      });
  }

  static FluentIterable<DatasetDependency> filterDatasetDependencies(Iterable<DependencyEntry> dependencyEntries) {
    return FluentIterable.from(dependencyEntries)
      .filter(IS_DATASET)
      .transform(new Function<DependencyEntry, DatasetDependency>() {
        @Override
        public DatasetDependency apply(DependencyEntry entry) {
          return (DatasetDependency) entry;
        }
      });
  }

  static FluentIterable<TableFunctionDependency> filterTableFunctionDependencies(Iterable<DependencyEntry> dependencyEntries) {
    return FluentIterable.from(dependencyEntries)
      .filter(IS_TABLEFUNCTION)
      .transform(new Function<DependencyEntry, TableFunctionDependency>() {
        @Override
        public TableFunctionDependency apply(DependencyEntry entry) {
          return (TableFunctionDependency) entry;
        }
      });
  }

  /**
   * Extract reflection dependencies from the materialization job infos
   *
   * @throws NamespaceException if can't access a dataset dependency in the Namespace
   */
  public static ExtractedDependencies extractDependencies(final JobInfo jobInfo,
                                                          final RefreshDecision decision, CatalogService catalogService) throws NamespaceException {
    final Set<DependencyEntry> plandDependencies = Sets.newHashSet();
    EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService);

    // add all physical datasets and substitutions
    final List<ScanPath> jobScanPaths = jobInfo.getScanPathsList();
    if (jobScanPaths != null) {
      for (ScanPath scanPath : jobScanPaths) {
        TableVersionContext versionContext = null;
        if (scanPath.getVersionContext() != null) {
          versionContext = TableVersionContext.deserialize(scanPath.getVersionContext());
        }
        DremioTable table = catalog.getTable(CatalogEntityKey.newBuilder().
          keyComponents(scanPath.getPathList()).tableVersionContext(versionContext).build());
        if (table == null) {
          throw new NamespaceNotFoundException(new NamespaceKey(scanPath.getPathList()), "Dataset not found in catalog " + scanPath.getVersionContext());
        }
        long snapshotId = 0L;
        if (scanPath.getSnapshotId() != null) {
          snapshotId = scanPath.getSnapshotId();
        }
        if (scanPath.getPathList().get(0).equals(ACCELERATOR_STORAGEPLUGIN_NAME)) {
          plandDependencies.add(DependencyEntry.of(new ReflectionId(scanPath.getPathList().get(TABLE_SCHEMA_PATH_REFLECTION_ID_COMPONENT)), snapshotId));
        } else {
          plandDependencies.add(DependencyEntry.of(table.getDatasetConfig().getId().getId(), scanPath.getPathList(), snapshotId));
        }
      }
    }

    //add all table function dependencies
    List<String> externalQuerySourceInfo = jobInfo.getSourceNamesList();
    if (externalQuerySourceInfo != null) {
      for(int i = 0; i < externalQuerySourceInfo.size(); i+=2) {
        plandDependencies.add(DependencyEntry.of(UUID.randomUUID().toString(), externalQuerySourceInfo.get(i), externalQuerySourceInfo.get(i+1)));
      }
    }

    final Set<DependencyEntry> decisionDependencies = Sets.newHashSet();
    final List<ScanPath> scanPaths = decision.getScanPathsList();
    if (scanPaths != null) {
      for (ScanPath scanPath : scanPaths) {
        TableVersionContext versionContext = null;
        if (scanPath.getVersionContext() != null) {
          versionContext = TableVersionContext.deserialize(scanPath.getVersionContext());
        }
        DremioTable table = catalog.getTable(CatalogEntityKey.newBuilder().
          keyComponents(scanPath.getPathList()).tableVersionContext(versionContext).build());
        if (table == null) {
          throw new NamespaceNotFoundException(new NamespaceKey(scanPath.getPathList()), "Dataset not found in catalog " + scanPath.getVersionContext());
        }
        long snapshotId = 0L;
        if (scanPath.getSnapshotId() != null) {
          snapshotId = scanPath.getSnapshotId();
        }
        decisionDependencies.add(DependencyEntry.of(table.getDatasetConfig().getId().getId(), scanPath.getPathList(), snapshotId));
      }
    }

    return new ExtractedDependencies(plandDependencies, decisionDependencies);
  }

}
