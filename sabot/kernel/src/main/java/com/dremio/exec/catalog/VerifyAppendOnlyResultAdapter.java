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
package com.dremio.exec.catalog;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;

import com.dremio.connector.metadata.DatasetVerifyAppendOnlyResult;

/**
 * Adapter adapts {@link DatasetVerifyAppendOnlyResult} to {@link TableMetadataVerifyAppendOnlyResult}
 */
final class VerifyAppendOnlyResultAdapter implements TableMetadataVerifyAppendOnlyResult{
  private final DatasetVerifyAppendOnlyResult datasetVerifyAppendOnlyResult;

  VerifyAppendOnlyResultAdapter(DatasetVerifyAppendOnlyResult datasetVerifyAppendOnlyResult) {
    this.datasetVerifyAppendOnlyResult = datasetVerifyAppendOnlyResult;
  }

  @Override
  public ResultCode getResultCode() {
    switch (datasetVerifyAppendOnlyResult.getResultCode()) {
      case APPEND_ONLY:
        return ResultCode.APPEND_ONLY;
      case NOT_APPEND_ONLY:
        return ResultCode.NOT_APPEND_ONLY;
      case NOT_ANCESTOR:
        return ResultCode.NOT_ANCESTOR;
      case INVALID_BEGIN_SNAPSHOT:
        return ResultCode.INVALID_BEGIN_SNAPSHOT;
      case INVALID_END_SNAPSHOT:
        return ResultCode.INVALID_END_SNAPSHOT;
      default:
        throw new UnsupportedOperationException("Unsupported metadata verify result code: " + datasetVerifyAppendOnlyResult.getResultCode());
    }
  }

  @Override
  public List<Pair<String, String>> getSnapshotRanges() {
    return datasetVerifyAppendOnlyResult.getSnapshotRanges();
  }
}
