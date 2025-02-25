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

import java.util.Map;
import java.util.Set;

import javax.inject.Provider;

import com.dremio.context.RequestContext;
import com.google.common.collect.Sets;

import io.grpc.ClientInterceptor;
import io.opentracing.Tracer;

/**
 *  Channel builder factory where the client is a single-tenant service.
 */
public class SingleTenantGrpcChannelBuilderFactory extends BaseGrpcChannelBuilderFactory {
  public SingleTenantGrpcChannelBuilderFactory(
    Tracer tracer,
    Provider<RequestContext> defaultContext,
    Provider<Map<String, Object>> defaultServiceConfigProvider)
  {
    super(tracer,
      Sets.newHashSet(ContextualizedClientInterceptor.buildSingleTenantClientInterceptorWithDefaults(defaultContext)),
      defaultServiceConfigProvider);
  }

  public SingleTenantGrpcChannelBuilderFactory(
    Tracer tracer,
    Provider<Map<String, Object>> defaultServiceConfigProvider,
    Set<ClientInterceptor> interceptors)
  {
    super(tracer, interceptors, defaultServiceConfigProvider);
  }
}
