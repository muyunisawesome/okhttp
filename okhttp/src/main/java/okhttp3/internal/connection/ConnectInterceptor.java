/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.RealInterceptorChain;

/**
 * 负责与服务器建立连接<P></P>
 * 在RetryAndFollowUpInterceptor里初始化了一个StreamAllocation对象，
 * 我们说在这个StreamAllocation对象里初始化了一个Socket对象用来做连接，但是并没有
 * 真正的连接，等到处理完hader和缓存信息之后，才调用ConnectInterceptor来进行真正的连接<P></P>
 *
 * Opens a connection to the target server and proceeds to the next interceptor.
 */
public final class ConnectInterceptor implements Interceptor {
  public final OkHttpClient client;

  public ConnectInterceptor(OkHttpClient client) {
    this.client = client;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Request request = realChain.request();
    StreamAllocation streamAllocation = realChain.streamAllocation();

    //我们需要网络来满足这个要求。 可能用于验证条件GET
    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    boolean doExtensiveHealthChecks = !request.method().equals("GET");
    //创建输出流
    HttpCodec httpCodec = streamAllocation.newStream(client, chain, doExtensiveHealthChecks);
    //建立连接
    RealConnection connection = streamAllocation.connection();

    return realChain.proceed(request, streamAllocation, httpCodec, connection);
  }
}
