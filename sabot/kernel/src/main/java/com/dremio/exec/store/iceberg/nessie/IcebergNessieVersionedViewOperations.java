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
package com.dremio.exec.store.iceberg.nessie;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.viewdepoc.BaseMetastoreViewOperations;
import org.apache.iceberg.viewdepoc.ViewVersionMetadata;
import org.projectnessie.model.IcebergView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.exec.catalog.VersionedPlugin.EntityType;
import com.dremio.exec.store.iceberg.model.IcebergCommitOrigin;
import com.dremio.plugins.NessieClient;
import com.dremio.plugins.NessieContent;

/**
 * Versioned iceberg view operations.
 */
public class IcebergNessieVersionedViewOperations extends BaseMetastoreViewOperations {
  private static final Logger logger =
      LoggerFactory.getLogger(IcebergNessieVersionedViewOperations.class);
  private static final Predicate<Exception> RETRY_IF =
      exec -> !exec.getClass().getCanonicalName().contains("Unrecoverable");
  private static final int MAX_RETRIES = 2;

  private final FileIO fileIO;
  private final NessieClient nessieClient;
  private final List<String> viewKey;
  private final String dialect;
  private ResolvedVersionContext version;
  private final String userName;
  private final Function<String, ViewVersionMetadata> metadataLoader;
  private final IcebergCommitOrigin commitOrigin;
  private IcebergView icebergView;
  private String baseContentId;

  public IcebergNessieVersionedViewOperations(
      FileIO fileIO,
      NessieClient nessieClient,
      List<String> viewKey,
      IcebergCommitOrigin commitOrigin,
      String dialect,
      ResolvedVersionContext version,
      String userName,
      Function<String, ViewVersionMetadata> metadataLoader) {
    this.fileIO = fileIO;
    this.nessieClient = requireNonNull(nessieClient);
    this.viewKey = requireNonNull(viewKey);
    this.commitOrigin = commitOrigin;
    this.dialect = dialect;
    this.version = version;
    this.baseContentId = null;
    this.userName = userName;
    this.metadataLoader = metadataLoader;
  }

  @Override
  public ViewVersionMetadata refresh() {
    if (version.isBranch()) {
      version = nessieClient.resolveVersionContext(VersionContext.ofBranch(version.getRefName()));
    }
    baseContentId = null;
    String metadataLocation = null;
    Optional<NessieContent> maybeNessieContent = nessieClient.getContent(viewKey, version, null);
    if (maybeNessieContent.isPresent()) {
      NessieContent nessieContent = maybeNessieContent.get();
      baseContentId = nessieContent.getContentId();
      metadataLocation = nessieContent.getMetadataLocation().orElseThrow(
        () -> new IllegalStateException("No metadataLocation for iceberg view: " + viewKey + " ref: " + version));
    }
    refreshFromMetadataLocation(metadataLocation, RETRY_IF, MAX_RETRIES, this.metadataLoader);
    return current();
  }

  @Override
  public void drop(String viewIdentifier) {
    logger.debug("Deleting key for view {} at version {} from Nessie ", viewKey, version);
    nessieClient.deleteCatalogEntry(viewKey, EntityType.ICEBERG_VIEW, version, userName);
  }

  @Override
  public void commit(
      ViewVersionMetadata base,
      ViewVersionMetadata target,
      Map<String, String> properties) {
    final String newMetadataLocation = writeNewMetadata(target, currentVersion() + 1);

    boolean isFailedOperation = true;
    try {
      nessieClient.commitView(
        viewKey,
        newMetadataLocation,
        icebergView,
        target,
        dialect,
        version,
        baseContentId,
        commitOrigin,
        userName
      );
      isFailedOperation = false;
    } finally {
      if (isFailedOperation) {
        logger.debug("Deleting metadata file {} of view {}", newMetadataLocation, viewKey);
        io().deleteFile(newMetadataLocation);
      }
    }
  }

  @Override
  public FileIO io() {
    return fileIO;
  }
}
