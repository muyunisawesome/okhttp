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

/**
 * 一个call是一个已准备好执行的请求。可以被取消，当这个对象表示单个的请求/响应对（流）时，不能被执行两次。<P></P>
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 */
public interface Call extends Cloneable {

  /** Returns the original request that initiated this call. */
  Request request();

  /**
   * Invokes the request immediately, and blocks until the response can be processed or is in
   * error.
   *
   * <p>To avoid leaking resources callers should close the {@link Response} which in turn will
   * close the underlying {@link ResponseBody}.
   *
   * <pre>@{code
   *
   *   // ensure the response (and underlying response body) is closed
   *   try (Response response = client.newCall(request).execute()) {
   *     ...
   *   }
   *
   * }</pre>
   *
   * <p>The caller may read the response body with the response's {@link Response#body} method. To
   * avoid leaking resources callers must {@linkplain ResponseBody close the response body} or the
   * Response.
   *
   * <p>Note that transport-layer success (receiving a HTTP response code, headers and body) does
   * not necessarily indicate application-layer success: {@code response} may still indicate an
   * unhappy HTTP response code like 404 or 500.
   *
   * @throws IOException if the request could not be executed due to cancellation, a connectivity
   * problem or timeout. Because networks can fail during an exchange, it is possible that the
   * remote server accepted the request before the failure.
   * @throws IllegalStateException when the call has already been executed.
   */
  Response execute() throws IOException;

  /**
   * 进行异步请求<br>
   * 至于何时执行这个请求由分发器决定<br>
   * 通常是立即执行，除非当前有任务在执行或不符合限制条件<br>
   * 如果不能立即执行，会被保存到等待执行的异步请求队列<br>
   * 请求结束后，会通过回调接口将结果返回<P></P>
   *
   * Schedules the request to be executed at some point in the future.
   *
   * <p>The {@link OkHttpClient#dispatcher dispatcher} defines when the request will run: usually
   * immediately unless there are several other requests currently being executed.
   *
   * <p>This client will later call back {@code responseCallback} with either an HTTP response or a
   * failure exception.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  void enqueue(Callback responseCallback);

  /**
   * 取消这个RealCall 如果请求已经有返回了，那么就不能被取消了<P></P>
   * Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  void cancel();

  /**
   * 判断是否执行过<P></P>
   * Returns true if this call has been either {@linkplain #execute() executed} or {@linkplain
   * #enqueue(Callback) enqueued}. It is an error to execute a call more than once.
   */
  boolean isExecuted();

  /**
   * 请求是否被取消
   */
  boolean isCanceled();

  /**
   * Create a new, identical call to this one which can be enqueued or executed even if this call
   * has already been.
   */
  Call clone();

  //创建call工厂
  interface Factory {
    Call newCall(Request request);
  }
}
