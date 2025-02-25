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

package com.dremio.exec.store.dfs.implicit;

import java.util.List;
import java.util.Map;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.types.pojo.Field;

import com.dremio.exec.store.SplitAndPartitionInfo;
import com.dremio.options.OptionResolver;

public interface ImplicitColumnValuesProvider {
  List<NameValuePair<?>> getImplicitColumnValues(BufferAllocator allocator, SplitAndPartitionInfo splitInfo,
      Map<String, Field> implicitColumns, OptionResolver options);
}
