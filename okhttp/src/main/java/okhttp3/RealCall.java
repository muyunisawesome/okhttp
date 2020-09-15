/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.INFO;

//真正的call？
final class RealCall implements Call {

  //客户端，内部包涵dispatcher，构成循环引用不停迭代执行
  final OkHttpClient client;
  final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;

  /**
   * 在Call和EventListener之间有个环，这使它很尴尬。这个将在我们创建call实例然后创建event listener实例后被设置。<P></P>
   *
   * There is a cycle between the {@link Call} and {@link EventListener} that makes this awkward.
   * This will be set after we create the call instance then create the event listener instance.
   */
  private EventListener eventListener; //事件监听器

  /** The application's original request unadulterated by redirects or auth headers.<br>
   *  应用程序原始的请求，不被重定向或者auth头影响 */
  final Request originalRequest;
  final boolean forWebSocket; //是否是websocket

  // Guarded by this.
  private boolean executed; //标志是否已执行,避免2次执行

  private RealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    this.client = client;
    this.originalRequest = originalRequest;
    this.forWebSocket = forWebSocket;
    //重试和重定向拦截器，默认也是第一个拦截器
    this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
  }

  static RealCall newRealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    // Safely publish the Call instance to the EventListener.
    RealCall call = new RealCall(client, originalRequest, forWebSocket);
    call.eventListener = client.eventListenerFactory().create(call);
    return call;
  }

  @Override public Request request() {
    return originalRequest;
  }

  @Override public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    captureCallStackTrace();
    eventListener.callStart(this);
    try {
      client.dispatcher().executed(this);
      //通过拦截器链获取响应：即执行
      Response result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } catch (IOException e) {
      eventListener.callFailed(this, e);
      throw e;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  //捕获调用栈
  private void captureCallStackTrace() {
    Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
    retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
  }

  @Override public void enqueue(Callback responseCallback) {
    synchronized (this) {
      //每个请求只能被执行一次
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    captureCallStackTrace();
    eventListener.callStart(this);
    //创建一个AsyncCall，投入队列
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
  }

  @Override public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public RealCall clone() {
    return RealCall.newRealCall(client, originalRequest, forWebSocket);
  }

  StreamAllocation streamAllocation() {
    return retryAndFollowUpInterceptor.streamAllocation();
  }

  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;

    AsyncCall(Callback responseCallback) {
      super("OkHttp %s", redactedUrl());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    RealCall get() {
      return RealCall.this;
    }

    //模板方法
    @Override protected void execute() {
      boolean signalledCallback = false; //标记是否已回调，避免2次回调
      try {
        //通过拦截器链获取响应：即执行
        Response response = getResponseWithInterceptorChain();
        if (retryAndFollowUpInterceptor.isCanceled()) { //如果取消
          signalledCallback = true; //已回调，告知失败
          responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true; //已回调，告知成功
          responseCallback.onResponse(RealCall.this, response);
        }
      } catch (IOException e) {
        //如果产生异常
        if (signalledCallback) { //如果已回调，不用再次回调
          // Do not signal the callback twice!
          Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          eventListener.callFailed(RealCall.this, e);
          responseCallback.onFailure(RealCall.this, e);
        }
      } finally {
        //不管请求成功与否，都进行finished()操作
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  String toLoggableString() {
    return (isCanceled() ? "canceled " : "")
        + (forWebSocket ? "web socket" : "call")
        + " to " + redactedUrl();
  }

  String redactedUrl() {
    return originalRequest.url().redact();
  }

  Response getResponseWithInterceptorChain() throws IOException {
    //构建一个完整的拦截器栈
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();//这是一个List，是有序的
    interceptors.addAll(client.interceptors()); //首先添加的是用户添加的全局拦截器
    interceptors.add(retryAndFollowUpInterceptor); //错误重试、重定向拦截器
    //桥接拦截器，桥接应用层与网络层，添加必要的头、
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    //缓存处理，Last-Modified、ETag、DiskLruCache等
    interceptors.add(new CacheInterceptor(client.internalCache()));
    //连接拦截器
    interceptors.add(new ConnectInterceptor(client));
    //从这就知道，通过okHttpClient.Builder#addNetworkInterceptor()传进来的拦截器只对非网页的请求生效
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }
    //真正访问服务器的拦截器
    interceptors.add(new CallServerInterceptor(forWebSocket));

    Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0,
        originalRequest, this, eventListener, client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis());

    return chain.proceed(originalRequest);
  }
}
