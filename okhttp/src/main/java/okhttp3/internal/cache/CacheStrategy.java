/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.cache;

import java.util.Date;
import javax.annotation.Nullable;
import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.http.HttpDate;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.StatusLine;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 缓存策略, 缓存拦截器CacheInterceptor决定使用缓存还是使用网络请求<br>
 * Given a request and cached response, this figures out whether to use the network, the cache, or
 * both.
 *
 * <p>Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since"
 * header for conditional GETs) or warnings to the cached response (if the cached data is
 * potentially stale).
 */
public final class CacheStrategy {
  /** The request to send on the network, or null if this call doesn't use the network. */
  /** 如果最终这个变量为null，那就不能使用网络；反之就通过网络发送请求. */
  public final @Nullable Request networkRequest;

  /** The cached response to return or validate; or null if this call doesn't use a cache. */
  /** 如果最终这个变量为null，那就不能使用缓存；反之就返回缓存的响应. */
  public final @Nullable Response cacheResponse;

  CacheStrategy(Request networkRequest, Response cacheResponse) {
    this.networkRequest = networkRequest;
    this.cacheResponse = cacheResponse;
  }

  /** Returns true if {@code response} can be stored to later serve another request. */
  public static boolean isCacheable(Response response, Request request) {
    // Always go to network for uncacheable response codes (RFC 7231 section 6.1),
    // This implementation doesn't support caching partial content.
    switch (response.code()) {
      case HTTP_OK:
      case HTTP_NOT_AUTHORITATIVE:
      case HTTP_NO_CONTENT:
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_NOT_FOUND:
      case HTTP_BAD_METHOD:
      case HTTP_GONE:
      case HTTP_REQ_TOO_LONG:
      case HTTP_NOT_IMPLEMENTED:
      case StatusLine.HTTP_PERM_REDIRECT:
        // These codes can be cached unless headers forbid it.
        break;

      case HTTP_MOVED_TEMP:
      case StatusLine.HTTP_TEMP_REDIRECT:
        // These codes can only be cached with the right response headers.
        // http://tools.ietf.org/html/rfc7234#section-3
        // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
        if (response.header("Expires") != null
            || response.cacheControl().maxAgeSeconds() != -1
            || response.cacheControl().isPublic()
            || response.cacheControl().isPrivate()) {
          break;
        }
        // Fall-through.

      default:
        // All other codes cannot be cached.
        return false;
    }

    // A 'no-store' directive on request or response prevents the response from being cached.
    return !response.cacheControl().noStore() && !request.cacheControl().noStore();
  }

  public static class Factory {
    final long nowMillis;
    final Request request;
    final Response cacheResponse;

    /** The server's time when the cached response was served, if known. */
    private Date servedDate;
    private String servedDateString;

    /** The last modified date of the cached response, if known. */
    private Date lastModified;
    private String lastModifiedString;

    /**
     * The expiration date of the cached response, if known. If both this field and the max age are
     * set, the max age is preferred.
     */
    private Date expires;

    /**
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
     * first initiated.
     */
    private long sentRequestMillis;

    /**
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
     * first received.
     */
    private long receivedResponseMillis;

    /** Etag of the cached response. */
    private String etag;

    /** Age of the cached response. */
    private int ageSeconds = -1;

    //Factory是CacheStrategy的一个静态内部类，用来获取缓存的响应信息，然后根据这些信息生成缓存策略
    public Factory(long nowMillis, Request request, Response cacheResponse) {
      this.nowMillis = nowMillis;
      this.request = request;
      this.cacheResponse = cacheResponse;
      // 若有缓存
      if (cacheResponse != null) {
        // 当时发出请求获取该响应的时间
        this.sentRequestMillis = cacheResponse.sentRequestAtMillis();
        // 收到该响应的时间
        this.receivedResponseMillis = cacheResponse.receivedResponseAtMillis();
        // 取出缓存的响应头
        Headers headers = cacheResponse.headers();
        // 遍历header，保存Date、Expires、Last-Modified、ETag、Age等缓存机制相关字段的值
        for (int i = 0, size = headers.size(); i < size; i++) {
          String fieldName = headers.name(i);
          String value = headers.value(i);
          if ("Date".equalsIgnoreCase(fieldName)) {
            servedDate = HttpDate.parse(value);
            servedDateString = value;
          } else if ("Expires".equalsIgnoreCase(fieldName)) {
            expires = HttpDate.parse(value);
          } else if ("Last-Modified".equalsIgnoreCase(fieldName)) {
            lastModified = HttpDate.parse(value);
            lastModifiedString = value;
          } else if ("ETag".equalsIgnoreCase(fieldName)) {
            etag = value;
          } else if ("Age".equalsIgnoreCase(fieldName)) {
            ageSeconds = HttpHeaders.parseSeconds(value, -1);
          }
        }
      }
    }

    /**
     * Returns a strategy to satisfy {@code request} using the a cached response {@code response}.
     */
    public CacheStrategy get() {
      // 获取CacheStrategy 对象
      CacheStrategy candidate = getCandidate();
      // getCandidate方法告诉客户端需要进行网络请求，但是我们设置的请求头有only-if-cached标识（只要有缓存就使用缓存），
      // 那就发送矛盾，所以就两者都为null，返回一个504给用户
      if (candidate.networkRequest != null && request.cacheControl().onlyIfCached()) {
        // We're forbidden from using the network and the cache is insufficient.
        return new CacheStrategy(null, null);
      }

      return candidate;
    }

    /** Returns a strategy to use assuming the request can use the network. */
    private CacheStrategy getCandidate() {
      //1. 如果缓存没有命中，就直接进行网络请求。
      // No cached response.
      if (cacheResponse == null) {
        return new CacheStrategy(request, null);
      }
      //2. 如果TLS握手信息丢失，则返回直接进行连接。
      // Drop the cached response if it's missing a required handshake.
      if (request.isHttps() && cacheResponse.handshake() == null) {
        return new CacheStrategy(request, null);
      }
      //3. 根据response状态码，Expired时间和是否有no-cache标签判断响应能不能缓存。如果不能缓存那就进行网络请求
      // If this response shouldn't have been stored, it should never be used
      // as a response source. This check should be redundant as long as the
      // persistence store is well-behaved and the rules are constant.
      if (!isCacheable(cacheResponse, request)) {
        return new CacheStrategy(request, null);
      }
      //4. 如果请求header里有"no-cache"或者右条件GET请求（header里带有ETag/Since标签），则直接连接。
      CacheControl requestCaching = request.cacheControl();
      if (requestCaching.noCache() || hasConditions(request)) {
        return new CacheStrategy(request, null);
      }

      CacheControl responseCaching = cacheResponse.cacheControl();
      if (responseCaching.immutable()) {
        return new CacheStrategy(null, cacheResponse);
      }
      //该响应已缓存的时长：now - sent + age
      long ageMillis = cacheResponseAge();
      //该响应可以缓存的时长，一般服务器设置为max-age
      long freshMillis = computeFreshnessLifetime();

      if (requestCaching.maxAgeSeconds() != -1) {
        // 取出两者最小值
        // 走到这里 ，从computeFreshnessLifetime方法可以知道就是
        // 拿Request和Response的CacheControl头中max-age值作比较
        freshMillis = Math.min(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds()));
      }

      long minFreshMillis = 0;
      if (requestCaching.minFreshSeconds() != -1) {
        // 这里是取出min_fresh值，即缓存过期后还能继续使用的时长. 一般取0
        minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
      }

      long maxStaleMillis = 0;
      // 第一个判断：是否要求必须去服务器验证资源状态
      // 第二个判断：获取max-stale值，如果不等于-1，说明缓存过期后还能使用指定的时长
      if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
        // 如果不用去服务器验证状态且max-stale值不等于-1，那就取出来
        maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
      }
      //5. 如果缓存在过期时间内则可以直接使用，则直接返回上次缓存。
      // 如果响应头没有要求忽略本地缓存
      // 已缓存时长+最小新鲜度时长 < 最大新鲜度时长 + 过期后继续使用时长
      // 通过不等式转换：最大新鲜度时长减去最小新鲜度时长就是缓存的有效期，再加上过期后继续使用时长，那就是缓存极限有效时长
      // 如果已缓存的时长小于极限时长，说明还没到极限，对吧，那就继续使用
      if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
        Response.Builder builder = cacheResponse.newBuilder();
        // 如果已过期，但未超过 过期后继续使用时长，那还可以继续使用，只用添加相应的头部字段
        if (ageMillis + minFreshMillis >= freshMillis) {
          builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
        }
        long oneDayMillis = 24 * 60 * 60 * 1000L;
        //如果缓存已超过一天并且响应中没有设置过期时间也需要添加警告
        if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
          builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
        }
        //缓存继续使用，不进行网络请求
        return new CacheStrategy(null, builder.build());
      }
      // 走到这里说明缓存真的过期了
      //6. 如果缓存过期，且有ETag等信息，则发送If-None-Match、If-Modified-Since、If-Modified-Since等条件请求
      //交给服务端判断处理
      // Find a condition to add to the request. If the condition is satisfied, the response body
      // will not be transmitted.
      String conditionName;
      String conditionValue;
      if (etag != null) {//判断缓存的响应头是否设置了Etag
        conditionName = "If-None-Match";
        conditionValue = etag;
      } else if (lastModified != null) {//判断缓存的响应头是否设置了lastModified
        conditionName = "If-Modified-Since";
        conditionValue = lastModifiedString;
      } else if (servedDate != null) {//判断缓存的响应头是否设置了Date
        conditionName = "If-Modified-Since";
        conditionValue = servedDateString;
      } else { //如果都没有设置就使用网络请求
        return new CacheStrategy(request, null); // No condition! Make a regular request.
      }
      //复制一份和当前请求一样的头部
      Headers.Builder conditionalRequestHeaders = request.headers().newBuilder();
      //将上面判断的字段添加到头部
      Internal.instance.addLenient(conditionalRequestHeaders, conditionName, conditionValue);
      //使用新的头部
      Request conditionalRequest = request.newBuilder()
          .headers(conditionalRequestHeaders.build())
          .build();
      //返回策略类
      return new CacheStrategy(conditionalRequest, cacheResponse);
    }

    /**
     * 获取缓存的新鲜度，或者说可以缓存的时长<P></P>
     *
     * Returns the number of milliseconds that the response was fresh for, starting from the served
     * date.
     */
    private long computeFreshnessLifetime() {
      // 取出响应头部
      CacheControl responseCaching = cacheResponse.cacheControl();
      //如果设置了max-age，那就返回它的值
      if (responseCaching.maxAgeSeconds() != -1) {
        return SECONDS.toMillis(responseCaching.maxAgeSeconds());
      } else if (expires != null) {//如果设置了过期时间
        long servedMillis = servedDate != null
            ? servedDate.getTime()
            : receivedResponseMillis;
        // 计算过期时间与产生时间的差就是可以缓存的时长
        long delta = expires.getTime() - servedMillis;
        return delta > 0 ? delta : 0;
      } else if (lastModified != null
          && cacheResponse.request().url().query() == null) {
        //当上述2个字段都不存在时 进行试探性过期计算
        // As recommended by the HTTP RFC and implemented in Firefox, the
        // max age of a document should be defaulted to 10% of the
        // document's age at the time it was served. Default expiration
        // dates aren't used for URIs containing a query.
        long servedMillis = servedDate != null
            ? servedDate.getTime()
            : sentRequestMillis;
        long delta = servedMillis - lastModified.getTime();
        return delta > 0 ? (delta / 10) : 0;
      }
      //走到这里，说明缓存不能继续使用了，需要进行网络请求
      return 0;
    }

    /**
     * 返回响应的已缓存时间<P></P>
     * Returns the current age of the response, in milliseconds. The calculation is specified by RFC
     * 7234, 4.2.3 Calculating Age.
     */
    private long cacheResponseAge() {
      //计算收到响应的时间与服务器创建该响应时间的差值apparentReceivedAge
      long apparentReceivedAge = servedDate != null
          ? Math.max(0, receivedResponseMillis - servedDate.getTime())
          : 0;
      //取出apparentReceivedAge 与ageSeconds最大值并赋予receivedAge
      long receivedAge = ageSeconds != -1
          ? Math.max(apparentReceivedAge, SECONDS.toMillis(ageSeconds))
          : apparentReceivedAge;
      //计算从发起请求到收到响应的时间差responseDuration
      long responseDuration = receivedResponseMillis - sentRequestMillis;
      //计算现在与收到响应的时间差residentDuration
      long residentDuration = nowMillis - receivedResponseMillis;
      //三者加起来就是响应已存在的总时长
      return receivedAge + responseDuration + residentDuration;
    }

    /**
     * Returns true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a
     * cached response older than 24 hours, we are required to attach a warning.
     */
    private boolean isFreshnessLifetimeHeuristic() {
      return cacheResponse.cacheControl().maxAgeSeconds() == -1 && expires == null;
    }

    /**
     * Returns true if the request contains conditions that save the server from sending a response
     * that the client has locally. When a request is enqueued with its own conditions, the built-in
     * response cache won't be used.
     */
    private static boolean hasConditions(Request request) {
      return request.header("If-Modified-Since") != null || request.header("If-None-Match") != null;
    }
  }
}
