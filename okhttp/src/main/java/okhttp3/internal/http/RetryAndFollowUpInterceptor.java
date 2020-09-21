/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http2.ConnectionShutdownException;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * 第一层的拦截器<br>
 * 这个拦截器从失败中恢复 并且如果必要会跟随重定向。如果call被取消将会报IOException<br>
 * 作用就是处理了一些连接异常以及重定向<P></P>
 * 客户端发送一个请求到服务器端，服务器匹配Servlet，这都和请求转发一样。Servlet处理完之后调用了sendRedirect()
 * 这个方法，这个方法是response的方法。所以，当这个Servlet处理完后，即response.sendRedirect()方法执行完立即向客户端返回响应，响应行告诉客户端你必须再重新发送一个请求，去访问okhttp.jsp；紧接着客户端收到这个请求后，立刻发出一个新的请求，去请求okhttp.jsp，在这两个请求互不干扰、相互独立，在前面request里面setAttribute()的任何东西，
 * 在后面的request里面都获得不了。因此，在sendRedirect()里面是两个请求，两个响应<P></P>
 *
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 */
public final class RetryAndFollowUpInterceptor implements Interceptor {
  /**
   * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
   * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
   */
  private static final int MAX_FOLLOW_UPS = 20; //重定向的次数限制

  private final OkHttpClient client;
  private final boolean forWebSocket;
  private StreamAllocation streamAllocation;
  private Object callStackTrace;
  private volatile boolean canceled;

  public RetryAndFollowUpInterceptor(OkHttpClient client, boolean forWebSocket) {
    this.client = client;
    this.forWebSocket = forWebSocket;
  }

  /**
   * Immediately closes the socket connection if it's currently held. Use this to interrupt an
   * in-flight request from any thread. It's the caller's responsibility to close the request body
   * and response body streams; otherwise resources may be leaked.
   *
   * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
   * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
   * Otherwise if a socket connection is being established, that is terminated.
   */
  public void cancel() {
    canceled = true;
    StreamAllocation streamAllocation = this.streamAllocation;
    if (streamAllocation != null) streamAllocation.cancel();
  }

  public boolean isCanceled() {
    return canceled;
  }

  public void setCallStackTrace(Object callStackTrace) {
    this.callStackTrace = callStackTrace;
  }

  public StreamAllocation streamAllocation() {
    return streamAllocation;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Call call = realChain.call();
    EventListener eventListener = realChain.eventListener();
    //1.实例化一个StreamAllocation对象，是一个管理类，字面理解是分配流，分配与服务器数据传输的流
    //是用来建立HTTP请求所需的网络设施组件，比如说HttpCodec（跟服务端进行数据传输的流 HttpStream）、连接服务器的RealConnection等
    //它还提供了调用RealConnection的connect()方法与服务器建立连接的方法，提供了断开连接的方法release()，提供了对路由的判断等等
    //在这个拦截器里没有用到，真正使用的地方是在ConnectInterceptor
    streamAllocation = new StreamAllocation(client.connectionPool(), createAddress(request.url()),
        call, eventListener, callStackTrace);
    //记录重定向次数
    int followUpCount = 0;
    Response priorResponse = null; //上一个重试得到的响应
    while (true) {
      if (canceled) {
        //如果已取消，删除连接上的call请求
        streamAllocation.release();
        throw new IOException("Canceled");
      }

      Response response; // 定义请求的响应
      boolean releaseConnection = true; // 是否释放连接，默认为true
      try {
        //2. 继续执行下一个Interceptor，即BridgeInterceptor
        response = realChain.proceed(request, streamAllocation, null, null);
        releaseConnection = false; // 如果没有发送异常，修改标志 不需要重试
      } catch (RouteException e) { //后续拦截器抛出路由异常
        // 出现路由连接异常，通过recover方法判断能否恢复连接，如果不能将抛出异常不再重试
        // The attempt to connect via a route failed. The request will not have been sent.
        if (!recover(e.getLastConnectException(), false, request)) {
          //不能恢复，抛出异常
          throw e.getLastConnectException();
        }
        releaseConnection = false; //否则能恢复，不释放资源
        continue; //回到下一次循环 继续重试 除了finally代码外，下面的代码都不会执行
      } catch (IOException e) { //后续拦截器在与服务器通信中抛出IO异常
        // 判断该异常是否是连接关闭异常
        // An attempt to communicate with a server failed. The request may have been sent.
        boolean requestSendStarted = !(e instanceof ConnectionShutdownException);
        //不能恢复，抛出异常
        if (!recover(e, requestSendStarted, request)) throw e;
        releaseConnection = false; //否则能恢复，不释放资源
        continue;
      } finally {
        // We're throwing an unchecked exception. Release any resources.
        // 检测到其他未知异常，则释放连接和资源
        if (releaseConnection) {
          streamAllocation.streamFailed(null);
          streamAllocation.release();
        }
      }

      // 走到这里，说明网络请求已经完成了，但是响应码并不一定是200
      // 可能是其它异常的响应码或者重定向响应码

      // 如果priorResponse 不等于null，说明前面已经完成了一次请求
      // Attach the prior response if it exists. Such responses never have a body.
      if (priorResponse != null) {
        response = response.newBuilder()
            .priorResponse(priorResponse.newBuilder()
                    .body(null)
                    .build())
            .build();
      }
      // 重定向处理，根据响应码判断，返回Request不为空时则重定向
      Request followUp = followUpRequest(response);

      // 如果为null，那就没必要重新请求，说明已经有了合适的Response，直接返回
      if (followUp == null) {
        if (!forWebSocket) {
          streamAllocation.release();
        }
        return response;
      }
      //关闭，忽略任何已检查的异常，避免消耗客户端太多资源
      closeQuietly(response.body());

      //重定向的次数不能超过20次
      if (++followUpCount > MAX_FOLLOW_UPS) {
        streamAllocation.release();
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }

      //如果该请求体被UnrepeatableRequestBody标记，则不可重试
      if (followUp.body() instanceof UnrepeatableRequestBody) {
        streamAllocation.release();
        throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
      }

      // 判断重连前的Request与重新构建的Request是否有相同的连接，即host、port、scheme是否一致
      if (!sameConnection(response, followUp.url())) {
        // 如果不是相同的url连接，先释放之间的，再创建新的StreamAllocation
        streamAllocation.release();
        streamAllocation = new StreamAllocation(client.connectionPool(),
            createAddress(followUp.url()), call, eventListener, callStackTrace);
      } else if (streamAllocation.codec() != null) {
        // 如果相同，但是本次请求的流没有关闭，那就抛出异常
        throw new IllegalStateException("Closing the body of " + response
            + " didn't close its backing stream. Bad interceptor?");
      }
      // 将重定向的请求体赋值给request ，以便再次进入循环
      request = followUp;
      // 将重新构建的响应赋值给priorResponse，在下一次循环中使用
      priorResponse = response;

      // 本次循环结束，进入下一个循环，重新连接
    }
  }

  private Address createAddress(HttpUrl url) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    CertificatePinner certificatePinner = null;
    if (url.isHttps()) {
      sslSocketFactory = client.sslSocketFactory();
      hostnameVerifier = client.hostnameVerifier();
      certificatePinner = client.certificatePinner();
    }

    return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
        sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
        client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
  }

  /**
   * 判断连接能否恢复
   * Report and attempt to recover from a failure to communicate with a server. Returns true if
   * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
   * be recovered if the body is buffered or if the failure occurred before the request has been
   * sent.
   */
  private boolean recover(IOException e, boolean requestSendStarted, Request userRequest) {
    //根据抛出的异常，做出连接、连接路线的一些处理，并且释放连接，关闭连接
    streamAllocation.streamFailed(e);

    // 判断开发者是否禁用了失败重连
    // 在构建OKHttpClient的时候可以通过build进行配置
    // 如果禁用，那就返回false，不进行重连
    // The application layer has forbidden retries.
    if (!client.retryOnConnectionFailure()) return false;

    // 如果不是连接关闭异常，且请求体被UnrepeatableRequestBody标记，那不能恢复
    // We can't send the request body again.
    if (requestSendStarted && userRequest.body() instanceof UnrepeatableRequestBody) return false;

    // 根据异常判断是否可以重连
    // This exception is fatal.
    if (!isRecoverable(e, requestSendStarted)) return false;

    // 判断还有没有多余线路进行连接
    // 如果没有，返回false
    // No more routes to attempt.
    if (!streamAllocation.hasMoreRoutes()) return false;

    // 走到这里说明可以恢复连接，尝试重连
    // For failure recovery, use the same route selector with a new connection.
    return true;
  }

  /**
   * 根据异常类型判断能否恢复连接
   */
  private boolean isRecoverable(IOException e, boolean requestSendStarted) {

    // 出现协议异常，不能恢复
    // If there was a protocol problem, don't recover.
    if (e instanceof ProtocolException) {
      return false;
    }

    // 如果是中断异常，即IO连接中断
    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    if (e instanceof InterruptedIOException) {
      return e instanceof SocketTimeoutException && !requestSendStarted;
    }

    // 如果该异常是SSL握手异常
    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (e instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }
    // 如果该异常是SSL握手未授权异常  不能进行恢复
    if (e instanceof SSLPeerUnverifiedException) {
      // 比如证书校验失败
      // e.g. a certificate pinning error.
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true;
  }

  /**
   * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will
   * either add authentication headers, follow redirects or handle a client request timeout. If a
   * follow-up is either unnecessary or not applicable, this returns null.
   */
  private Request followUpRequest(Response userResponse) throws IOException {
    if (userResponse == null) throw new IllegalStateException();
    Connection connection = streamAllocation.connection();
    Route route = connection != null
        ? connection.route()
        : null;
    int responseCode = userResponse.code();

    final String method = userResponse.request().method();
    switch (responseCode) {
      case HTTP_PROXY_AUTH: //407未进行身份认证，需要对请求头进行处理后再发起新的请求
        Proxy selectedProxy = route != null
            ? route.proxy()
            : client.proxy();
        // 如果代理协议不是HTTP协议，那就抛出异常
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        return client.proxyAuthenticator().authenticate(route, userResponse);

      case HTTP_UNAUTHORIZED:  // 401 未认证 需要身份验证
        //在请求头中添加 “Authorization” 可以尝试重新连接
        return client.authenticator().authenticate(route, userResponse);
      // 308 永久重定向
      case HTTP_PERM_REDIRECT: //308/307/303/302/301/300 需要进行重定向，发起新的请求
        //  307 临时重定向
      case HTTP_TEMP_REDIRECT:
        // 如果接收到的状态码是307或者308去请求除GET或者HEAD以外的方法，用户代理不得自动重定向请求
        // "If the 307 or 308 status code is received in response to a request other than GET
        // or HEAD, the user agent MUST NOT automatically redirect the request"
        if (!method.equals("GET") && !method.equals("HEAD")) {
          return null;
        }
        // fall-through
      case HTTP_MULT_CHOICE: // 300  响应存在多种选择，需要客户端做出其中一种选择
      case HTTP_MOVED_PERM: // 301 请求的资源路径永久改变
      case HTTP_MOVED_TEMP: // 302 请求资源路径临时改变
      case HTTP_SEE_OTHER: // 303 服务端要求客户端使用GET访问另一个URI
        // 如果开发者不允许重定向，那就返回null
        // Does the client allow redirects?
        if (!client.followRedirects()) return null;
        // 从头部取出location
        String location = userResponse.header("Location");
        if (location == null) return null;
        // 从location 中取出HttpUrl
        HttpUrl url = userResponse.request().url().resolve(location);

        // 如果为null，说明协议有问题，取不出来HttpUrl，那就返回null，不进行重定向
        // Don't follow redirects to unsupported protocols.
        if (url == null) return null;

        // 检测是否存在http与https之间的重定向
        // If configured, don't follow redirects between SSL and non-SSL.
        boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
        if (!sameScheme && !client.followSslRedirects()) return null;

        // 大多数重定向不包含请求体
        // Most redirects don't include a request body.
        Request.Builder requestBuilder = userResponse.request().newBuilder();
        if (HttpMethod.permitsRequestBody(method)) {
          final boolean maintainBody = HttpMethod.redirectsWithBody(method);
          if (HttpMethod.redirectsToGet(method)) {
            requestBuilder.method("GET", null);
          } else {
            RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
            requestBuilder.method(method, requestBody);
          }
          if (!maintainBody) {
            requestBuilder.removeHeader("Transfer-Encoding");
            requestBuilder.removeHeader("Content-Length");
            requestBuilder.removeHeader("Content-Type");
          }
        }

        // 在跨主机重定向时，请删除所有身份验证标头。 这对应用程序层来说可能很烦人，因为他们无法保留它们
        // When redirecting across hosts, drop all authentication headers. This
        // is potentially annoying to the application layer since they have no
        // way to retain them.
        if (!sameConnection(userResponse, url)) {
          requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();

      // 408 客户端请求超时
      case HTTP_CLIENT_TIMEOUT:
        // 408's are rare in practice, but some servers like HAProxy use this response code. The
        // spec says that we may repeat the request without modifications. Modern browsers also
        // repeat the request (even non-idempotent ones.)
        if (!client.retryOnConnectionFailure()) {
          // The application layer has directed us not to retry the request.
          return null;
        }

        // 408在实际开发中很少见，但是像HAProxy这样的服务器使用这个响应代码
        // 请求体是否被UnrepeatableRequestBody标记，如果被标记，就不能进行重连
        if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
          return null;
        }

        if (userResponse.priorResponse() != null
            && userResponse.priorResponse().code() == HTTP_CLIENT_TIMEOUT) {
          // We attempted to retry and got another timeout. Give up.
          return null;
        }

        return userResponse.request();

      default:
        return null;
    }
  }

  /**
   * 比较已经完成的请求的Url和经过followUpRequest()构建的新Request的Url
   * 判断它们的host、port、scheme协议是否一致
   * 如果不一致则以followUpRequest()返回的Request为准，释放掉旧的StreamAllocation，创建新的StreamAllocation重新向服务器发送请求
   * <P></P>
   * Returns true if an HTTP request for {@code followUp} can reuse the connection used by this
   * engine.
   */
  private boolean sameConnection(Response response, HttpUrl followUp) {
    HttpUrl url = response.request().url();
    return url.host().equals(followUp.host())
        && url.port() == followUp.port()
        && url.scheme().equals(followUp.scheme());
  }
}
