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
package com.dremio.service.grpc;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.dremio.common.SuppressForbidden;

import io.netty.buffer.ByteBuf;

/**
 * Writer that enables copying a byte buffer to stream through grpc writable buffers without an
 * extra copy.
 *
 * Adapted from https://github.com/apache/arrow/tree/master/java/flight/flight-core/src/main/java/org/apache/arrow/flight/grpc
 */
@SuppressForbidden
public class ByteBufToStreamCopier {
  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.
          getLogger(ByteBufToStreamCopier.class);
  private static final Constructor<?> BUF_CONSTRUCTOR;
  private static final Field BUFFER_LIST;
  private static final Field CURRENT;
  private static final Method LIST_ADD;
  private static final Class<?> BUF_CHAIN_OUT;
  private static final AtomicInteger NON_OPTIMAL_WRITE = new AtomicInteger(0);

  /**
   * Copy the buffer into the stream and directly set it to the grpc
   * buffers.
   *
   * @param buf buffer to write to the stream
   * @param stream the grpc output stream
   * @return
   * @throws IOException
   */
  public static boolean add(ByteBuf buf, OutputStream stream) throws IOException {
    if (BUF_CHAIN_OUT == null || !stream.getClass().equals(BUF_CHAIN_OUT)) {
      LOGGER.warn("Entered non optimal write path {} number of times", NON_OPTIMAL_WRITE.incrementAndGet());
      return false;
    } else {
      try {
        if (CURRENT.get(stream) != null) {
          return false;
        } else {
          buf.retain();
          Object obj = BUF_CONSTRUCTOR.newInstance(buf);
          Object list = BUFFER_LIST.get(stream);
          LIST_ADD.invoke(list, obj);
          CURRENT.set(stream, obj);
          return true;
        }
      } catch (IllegalArgumentException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
        LOGGER.warn("Error adding byte buf to output stream", e);
        return false;
      }
    }
  }

  /**
   * Reflect and get internal methods that helps us directly
   * drain the arrow buffer into grpc writable buffer.
   *
   * All variables are initialized or none.
   */
  static {
    Constructor<?> tmpConstruct = null;
    Field tmpBufferList = null;
    Field tmpCurrent = null;
    Class<?> tmpBufChainOut = null;
    Method tmpListAdd = null;

    try {
      // For this to work, make sure to not have grpc-netty-shaded-XXXX.jar in classpath or mvn dependencies
      // otherwise we observe below error
      // Caused by: java.lang.ClassCastException: io.grpc.netty.NettyWritableBuffer cannot be
      // cast to io.grpc.netty.shaded.io.grpc.netty.NettyWritableBuffer
      Class<?> nwb = Class.forName("io.grpc.netty.NettyWritableBuffer");
      Constructor<?> tmpConstruct2 = nwb.getDeclaredConstructor(ByteBuf.class);
      tmpConstruct2.setAccessible(true);
      Class<?> tmpBufChainOut2 = Class.forName("io.grpc.internal.MessageFramer$BufferChainOutputStream");
      Field tmpBufferList2 = tmpBufChainOut2.getDeclaredField("bufferList");
      tmpBufferList2.setAccessible(true);
      Field tmpCurrent2 = tmpBufChainOut2.getDeclaredField("current");
      tmpCurrent2.setAccessible(true);
      Method tmpListAdd2 = List.class.getDeclaredMethod("add", Object.class);
      tmpConstruct = tmpConstruct2;
      tmpBufferList = tmpBufferList2;
      tmpCurrent = tmpCurrent2;
      tmpListAdd = tmpListAdd2;
      tmpBufChainOut = tmpBufChainOut2;
    } catch (Exception e) {
      LOGGER.warn("Unable to setup optimal write path.", e);
    }

    BUF_CONSTRUCTOR = tmpConstruct;
    BUFFER_LIST = tmpBufferList;
    CURRENT = tmpCurrent;
    LIST_ADD = tmpListAdd;
    BUF_CHAIN_OUT = tmpBufChainOut;
  }
}
