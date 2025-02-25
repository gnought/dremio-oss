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
package com.dremio.datastore.api;

import static java.lang.reflect.Modifier.isPublic;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;

import javax.inject.Provider;

import org.apache.arrow.memory.BufferAllocator;

import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.datastore.format.Format;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.service.Service;
import com.dremio.services.fabric.api.FabricService;

/**
 * Key-value store abstraction
 */
public interface KVStoreProvider extends Service {

  /**
   * Get stores created.
   *
   * @return a set of all the KVStores
   */
  Set<KVStore<?, ?>> stores();

  /**
   * Get the store associated with the provided creator class.
   *
   * @param creator The creator function.
   * @return The associated kvstore, already initialized.
   */
  <K, V, T extends KVStore<K, V>> T getStore(Class<? extends StoreCreationFunction<K, V, T>> creator);


  /**
   * Get method to retrieve the StoreBuilder of this KVStoreProvider.
   *
   * @return the StoreBuilder of this KVStoreProvider.
   */
  <K, V> StoreBuilder<K, V> newStore();

  /**
   * Interface to configure and construct different store types.
   *
   * @param <K> key type K.
   * @param <V> value type V.
   */
  interface StoreBuilder<K, V> {
    /**
     * Sets the name of the KVStore to be built.
     *
     * @param name the name of the KVStore to be built.
     * @return a StoreBuilder implementation with name configured.
     */
    StoreBuilder<K, V> name(String name);

    /**
     * Sets the key format.
     *
     * @param format the key format.
     * @return a StoreBuilder implementation with key format configured.
     */
    StoreBuilder<K, V> keyFormat(Format<K> format);

    /**
     * Sets the value format.
     *
     * @param format the value Format.
     * @return a StoreBuilder implementation with value format configured.
     */
    StoreBuilder<K, V> valueFormat(Format<V> format);

    /**
     * Indicates that this StoreBuilder permits CompoundKeys. By default CompoundKeys are not
     * permitted. This gets evaluated at build time.
     *
     * @param allowCompund Set to true to allow CompoundKeys to be used as keys.
     * @return a StoreBuilder implementation with the CompoundKey permission configured.
     */
    StoreBuilder<K, V> permitCompoundKeys(boolean permitCompoundKeys);

    /**
     * Builds a KVStore implementation.
     *
     * @return a KVStore implementation.
     */
    KVStore<K, V> build();

    /**
     * Builds an IndexedStore implementation.
     *
     * @param documentConverter the DocumentConverter.
     * @return an IndexedStore implementation.
     */
    IndexedStore<K, V> buildIndexed(DocumentConverter<K, V> documentConverter);
  }

  /**
   * Method that allows decorators of kv store to be peeled off.
   * If unwrap cannot succeed, null is returned.
   */
  default <T> T unwrap(Class<T> clazz) {
    if (clazz.isInstance(this)) {
      return (T) this;
    }
    return null;
  }

  /**
   * This method retrieves the expected constructor from {@link KVStoreProvider} implementations. It can be used to
   * write tests to ensure the correct constructor is maintained.
   */
  static Constructor<? extends KVStoreProvider> getConstructor(Class<? extends KVStoreProvider> cls) {
    Constructor<? extends KVStoreProvider> constructor;
    try {
      constructor =  cls.getDeclaredConstructor(
        ScanResult.class,
        Provider.class,
        Provider.class,
        BufferAllocator.class,
        Map.class
      );
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(cls + " must have the expected constructor", e);
    }
    if (!isPublic(constructor.getModifiers())) {
      throw new RuntimeException("constructor " + constructor + " must be public");
    }
    return constructor;
  }

  /**
   * This provides a visible method which makes clear what constructor is expected to be present on
   * implementations of {@link KVStoreProvider}.
   */
  static KVStoreProvider newInstance(
      Class<? extends KVStoreProvider> cls,
      ScanResult scan,
      Provider<FabricService> fabricService,
      Provider<CoordinationProtos.NodeEndpoint> endpoint,
      BufferAllocator allocator,
      Map<String, Object> config) {
    Constructor<? extends KVStoreProvider> constructor = getConstructor(cls);
    try {
      return constructor.newInstance(scan, fabricService, endpoint, allocator, config);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("cannot create new instance of " + cls, e);
    }
  }
}
