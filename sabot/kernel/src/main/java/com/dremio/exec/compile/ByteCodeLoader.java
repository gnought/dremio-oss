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
package com.dremio.exec.compile;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.dremio.exec.exception.ClassTransformationException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.io.Resources;

class ByteCodeLoader {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ByteCodeLoader.class);


  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final LoadingCache<String, byte[]> byteCode = CacheBuilder.newBuilder().maximumSize(10000)
      .expireAfterWrite(10, TimeUnit.MINUTES).build(new ClassBytesCacheLoader());

  private class ClassBytesCacheLoader extends CacheLoader<String, byte[]> {
    @Override
    public byte[] load(String path) throws ClassTransformationException, IOException {
      URL u = this.getClass().getResource(path);
      if (u == null) {
        throw new ClassTransformationException(String.format("Unable to find TemplateClass at path %s", path));
      }
      return Resources.toByteArray(u);
    }
  };

  public byte[] getClassByteCodeFromPath(String path) throws ClassTransformationException, IOException {
    try {
      return byteCode.get(path);
    } catch (ExecutionException e) {
      Throwable c = e.getCause();
      if (c instanceof ClassTransformationException) {
        throw (ClassTransformationException) c;
      }
      if (c instanceof IOException) {
        throw (IOException) c;
      }
      throw new ClassTransformationException(c);
    }
  }

}
