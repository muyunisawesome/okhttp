/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.util.List;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;

import static okhttp3.internal.Util.closeQuietly;

/**
 * 封装了一次请求所需的网络组件<br>
 * 这个类协调3个实体：Connections、Streams、Calls<P></P>
 *
 * This class coordinates the relationship between three entities:
 *
 * <ul>
 *     <li><strong>Connections:</strong> physical socket connections to remote servers. These are
 *         potentially slow to establish so it is necessary to be able to cancel a connection
 *         currently being connected.
 *     <li><strong>Streams:</strong> logical HTTP request/response pairs that are layered on
 *         connections. Each connection has its own allocation limit, which defines how many
 *         concurrent streams that connection can carry. HTTP/1.x connections can carry 1 stream
 *         at a time, HTTP/2 typically carry multiple.
 *     <li><strong>Calls:</strong> a logical sequence of streams, typically an initial request and
 *         its follow up requests. We prefer to keep all streams of a single call on the same
 *         connection for better behavior and locality.
 * </ul>
 *
 * <p>Instances of this class act on behalf of the call, using one or more streams over one or more
 * connections. This class has APIs to release each of the above resources:
 *
 * <ul>
 *     <li>{@link #noNewStreams()} prevents the connection from being used for new streams in the
 *         future. Use this after a {@code Connection: close} header, or when the connection may be
 *         inconsistent.
 *     <li>{@link #streamFinished streamFinished()} releases the active stream from this allocation.
 *         Note that only one stream may be active at a given time, so it is necessary to call
 *         {@link #streamFinished streamFinished()} before creating a subsequent stream with {@link
 *         #newStream newStream()}.
 *     <li>{@link #release()} removes the call's hold on the connection. Note that this won't
 *         immediately free the connection if there is a stream still lingering. That happens when a
 *         call is complete but its response body has yet to be fully consumed.
 * </ul>
 *
 * <p>This class supports {@linkplain #cancel asynchronous canceling}. This is intended to have the
 * smallest blast radius possible. If an HTTP/2 stream is active, canceling will cancel that stream
 * but not the other streams sharing its connection. But if the TLS handshake is still in progress
 * then canceling may break the entire connection.
 */
public final class StreamAllocation {
  public final Address address; //地址
  private RouteSelector.Selection routeSelection;
  private Route route;//路由
  private final ConnectionPool connectionPool;  //连接池
  public final Call call;
  public final EventListener eventListener;
  private final Object callStackTrace;//日志

  // State guarded by connectionPool.
  private final RouteSelector routeSelector;  // 路由选择器
  private int refusedStreamCount;  //拒绝次数
  private RealConnection connection; //连接,记录当前这个Stream是被分配到哪个RealConnection上
  private boolean reportedAcquired;
  private boolean released;  //是否被释放
  private boolean canceled;  //是否被取消
  private HttpCodec codec; //用来进行网络IO操作

  public StreamAllocation(ConnectionPool connectionPool, Address address, Call call,
      EventListener eventListener, Object callStackTrace) {
    this.connectionPool = connectionPool;
    this.address = address;
    this.call = call;
    this.eventListener = eventListener;
    this.routeSelector = new RouteSelector(address, routeDatabase(), call, eventListener);
    this.callStackTrace = callStackTrace;
  }

  public HttpCodec newStream(
      OkHttpClient client, Interceptor.Chain chain, boolean doExtensiveHealthChecks) {
    //连接超时
    int connectTimeout = chain.connectTimeoutMillis();
    //读超时
    int readTimeout = chain.readTimeoutMillis();
    //写超时
    int writeTimeout = chain.writeTimeoutMillis();
    //连接失败是否重试
    boolean connectionRetryEnabled = client.retryOnConnectionFailure();

    try {
      //完成 连接，即找到一个健康的连接
      RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout,
          writeTimeout, connectionRetryEnabled, doExtensiveHealthChecks);
      //创建HttpCodec。
      //利用连接实例化流HttpCodec对象，如果是HTTP/2返回Http2Codec，否则返回Http1Codec
      HttpCodec resultCodec = resultConnection.newCodec(client, chain, this);

      synchronized (connectionPool) {
        //让当前的StreamAllocation持有这个流对象，然后返回它
        codec = resultCodec;
        return resultCodec;
      }
    } catch (IOException e) {
      throw new RouteException(e);
    }
  }

  /**
   * 返回一个连接来托管一个新的流。 这更喜欢现有的连接（如果存在的话），然后是池，最后建立一个新的连接。<P></P>
   *
   * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
   * until a healthy connection is found.
   */
  private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
      int writeTimeout, boolean connectionRetryEnabled, boolean doExtensiveHealthChecks)
      throws IOException {
    while (true) {
      //找到一个连接
      RealConnection candidate = findConnection(connectTimeout, readTimeout, writeTimeout,
          connectionRetryEnabled);

      // 如果这个连接是新建立的，那肯定是健康的，直接返回
      // If this is a brand new connection, we can skip the extensive health checks.
      synchronized (connectionPool) {
        if (candidate.successCount == 0) {
          return candidate;
        }
      }

      // 如果不是新创建的，需要检查是否健康
      // Do a (potentially slow) check to confirm that the pooled connection is still good. If it
      // isn't, take it out of the pool and start again.
      if (!candidate.isHealthy(doExtensiveHealthChecks)) {
        //不健康 关闭连接，释放Socket资源
        //如果这个连接不会再创建新的stream，从连接池移除
        //继续下次寻找连接操作
        noNewStreams();
        continue;
      }
      //如果是健康的就重用，返回
      return candidate;
    }
  }

  /**
   * Returns a connection to host a new stream. This prefers the existing connection if it exists,
   * then the pool, finally building a new connection.
   */
  private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout,
      boolean connectionRetryEnabled) throws IOException {
    boolean foundPooledConnection = false;
    RealConnection result = null;
    Route selectedRoute = null;
    Connection releasedConnection;
    Socket toClose;
    synchronized (connectionPool) {
      if (released) throw new IllegalStateException("released");
      if (codec != null) throw new IllegalStateException("codec != null");
      if (canceled) throw new IOException("Canceled");

      ///尝试使用已分配的连接。 我们在这里需要小心，因为我们已经分配的连接可能已经被限制在创建新的流中。
      // Attempt to use an already-allocated connection. We need to be careful here because our
      // already-allocated connection may have been restricted from creating new streams.
      releasedConnection = this.connection;
      toClose = releaseIfNoNewStreams();
      //1 查看是否有完好的连接
      if (this.connection != null) {
        // We had an already-allocated connection and it's good.
        result = this.connection;
        releasedConnection = null;
      }
      if (!reportedAcquired) {
        // If the connection was never reported acquired, don't report it as released!
        releasedConnection = null;
      }
      //2 连接池中是否用可用的连接，有则使用
      if (result == null) {
        // Attempt to get a connection from the pool.
        Internal.instance.get(connectionPool, address, this, null);
        if (connection != null) {
          foundPooledConnection = true;
          result = connection;
        } else {
          selectedRoute = route;
        }
      }
    }
    closeQuietly(toClose);

    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }
    if (foundPooledConnection) {
      eventListener.connectionAcquired(call, result);
    }
    if (result != null) {
      //如果我们找到了已经分配或者连接的连接，我们就完成了。
      // If we found an already-allocated or pooled connection, we're done.
      return result;
    }

    //如果我们需要路线选择，请选择一个。 这是一项阻塞操作。
    //线程的选择，多IP操作
    // If we need a route selection, make one. This is a blocking operation.
    boolean newRouteSelection = false;
    if (selectedRoute == null && (routeSelection == null || !routeSelection.hasNext())) {
      newRouteSelection = true;
      routeSelection = routeSelector.next();
    }

    //3 如果没有可用连接，则自己创建一个
    synchronized (connectionPool) {
      //请求被取消了 抛出异常
      if (canceled) throw new IOException("Canceled");

      if (newRouteSelection) {
        // 现在我们有一组IP地址，再次尝试从池中获取连接。 这可能由于连接合并而匹配
        // Now that we have a set of IP addresses, make another attempt at getting a connection from
        // the pool. This could match due to connection coalescing.
        List<Route> routes = routeSelection.getAll();
        for (int i = 0, size = routes.size(); i < size; i++) {
          Route route = routes.get(i);
          Internal.instance.get(connectionPool, address, this, route);
          if (connection != null) {
            foundPooledConnection = true;
            result = connection;
            this.route = route;
            break;
          }
        }
      }

      if (!foundPooledConnection) {
        if (selectedRoute == null) {
          selectedRoute = routeSelection.next();
        }

        // 到这里说明实在找不到 必须要实例化一个连接了
        // Create a connection and assign it to this allocation immediately. This makes it possible
        // for an asynchronous cancel() to interrupt the handshake we're about to do.
        route = selectedRoute;
        refusedStreamCount = 0;
        result = new RealConnection(connectionPool, selectedRoute);
        //将这个连接分配给流
        //同时将流添加到这个连接的集合里
        acquire(result, false);
      }
    }

    //如果我们第二次发现一个连接池，我们就完成了。
    // If we found a pooled connection on the 2nd time around, we're done.
    if (foundPooledConnection) {
      eventListener.connectionAcquired(call, result);
      return result;
    }

    //4 开始TCP以及TLS握手操作,这是阻塞操作
    // Do TCP + TLS handshakes. This is a blocking operation.
    result.connect(
        connectTimeout, readTimeout, writeTimeout, connectionRetryEnabled, call, eventListener);
    routeDatabase().connected(result.route());

    //5 将新创建的连接，放在连接池中
    Socket socket = null;
    synchronized (connectionPool) {
      reportedAcquired = true;
      // 将新创建的连接放到连接池中
      // Pool the connection.
      Internal.instance.put(connectionPool, result);

      //如果同时创建了到同一地址的另一个多路复用连接，（只要是HTTP/2连接，就可以同时用于多个HTTP请求，所有指向该地址的请求都应该基于同一个TCP连接），
      //同时连接池里存在一个同样的连接，那就释放掉当前这个连接，复用连接池里的连接
      // If another multiplexed connection to the same address was created concurrently, then
      // release this connection and acquire that one.
      if (result.isMultiplexed()) {
        socket = Internal.instance.deduplicate(connectionPool, address, this);
        result = connection;
      }
    }
    //关闭新建连接的Socket
    closeQuietly(socket);

    eventListener.connectionAcquired(call, result);
    //返回复用的连接
    return result;
  }

  /**
   * Releases the currently held connection and returns a socket to close if the held connection
   * restricts new streams from being created. With HTTP/2 multiple requests share the same
   * connection so it's possible that our connection is restricted from creating new streams during
   * a follow-up request.
   */
  private Socket releaseIfNoNewStreams() {
    assert (Thread.holdsLock(connectionPool));
    RealConnection allocatedConnection = this.connection;
    if (allocatedConnection != null && allocatedConnection.noNewStreams) {
      return deallocate(false, false, true);
    }
    return null;
  }

  public void streamFinished(boolean noNewStreams, HttpCodec codec, long bytesRead, IOException e) {
    eventListener.responseBodyEnd(call, bytesRead);

    Socket socket;
    Connection releasedConnection;
    boolean callEnd;
    synchronized (connectionPool) {
      if (codec == null || codec != this.codec) {
        throw new IllegalStateException("expected " + this.codec + " but was " + codec);
      }
      if (!noNewStreams) {
        connection.successCount++;
      }
      releasedConnection = connection;
      socket = deallocate(noNewStreams, false, true);
      if (connection != null) releasedConnection = null;
      callEnd = this.released;
    }
    closeQuietly(socket);
    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }

    if (e != null) {
      eventListener.callFailed(call, e);
    } else if (callEnd) {
      eventListener.callEnd(call);
    }
  }

  public HttpCodec codec() {
    synchronized (connectionPool) {
      return codec;
    }
  }

  private RouteDatabase routeDatabase() {
    return Internal.instance.routeDatabase(connectionPool);
  }

  public synchronized RealConnection connection() {
    return connection;
  }

  //释放连接，关闭Socket
  public void release() {
    Socket socket;
    Connection releasedConnection;
    synchronized (connectionPool) {
      releasedConnection = connection;
      socket = deallocate(false, true, false);
      if (connection != null) releasedConnection = null;
    }
    closeQuietly(socket);
    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }
  }

  //设置不能在此连接上创建新的流，同时关闭Socket
  /** Forbid new streams from being created on the connection that hosts this allocation. */
  public void noNewStreams() {
    Socket socket;
    Connection releasedConnection;
    synchronized (connectionPool) {
      releasedConnection = connection;
      socket = deallocate(true, false, false);
      if (connection != null) releasedConnection = null;
    }
    closeQuietly(socket);
    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }
  }

  /**
   * Releases resources held by this allocation. If sufficient resources are allocated, the
   * connection will be detached or closed. Callers must be synchronized on the connection pool.
   *
   * <p>Returns a closeable that the caller should pass to {@link Util#closeQuietly} upon completion
   * of the synchronized block. (We don't do I/O while synchronized on the connection pool.)
   */
  private Socket deallocate(boolean noNewStreams, boolean released, boolean streamFinished) {
    assert (Thread.holdsLock(connectionPool));
    //关闭流
    if (streamFinished) {
      this.codec = null;
    }
    //关闭流
    if (released) {
      this.released = true;
    }
    Socket socket = null;
    if (connection != null) {
      if (noNewStreams) {
        //重置标志 不能在该连接上分配流
        connection.noNewStreams = true;
      }
      if (this.codec == null && (this.released || connection.noNewStreams)) {
        //从连接的StreamAllocation链表中删除当前StreamAllocation
        release(connection);
        //如果链表为空，说明是个空闲连接
        if (connection.allocations.isEmpty()) {
          connection.idleAtNanos = System.nanoTime();
          //从连接池中清除该连接
          if (Internal.instance.connectionBecameIdle(connectionPool, connection)) {
            socket = connection.socket();
          }
        }
        connection = null;
      }
    }
    //返回该连接持有的Socket
    return socket;
  }

  //这个方法就是取消流或者连接
  public void cancel() {
    HttpCodec codecToCancel;
    RealConnection connectionToCancel;
    synchronized (connectionPool) {
      canceled = true;
      codecToCancel = codec;
      connectionToCancel = connection;
    }
    if (codecToCancel != null) {
      codecToCancel.cancel();
    } else if (connectionToCancel != null) {
      connectionToCancel.cancel();
    }
  }

  public void streamFailed(IOException e) {
    Socket socket;
    Connection releasedConnection;
    boolean noNewStreams = false;

    synchronized (connectionPool) {
      if (e instanceof StreamResetException) {
        StreamResetException streamResetException = (StreamResetException) e;
        if (streamResetException.errorCode == ErrorCode.REFUSED_STREAM) {
          refusedStreamCount++;
        }
        // On HTTP/2 stream errors, retry REFUSED_STREAM errors once on the same connection. All
        // other errors must be retried on a new connection.
        if (streamResetException.errorCode != ErrorCode.REFUSED_STREAM || refusedStreamCount > 1) {
          noNewStreams = true;
          route = null;
        }
      } else if (connection != null
          && (!connection.isMultiplexed() || e instanceof ConnectionShutdownException)) {
        noNewStreams = true;

        // If this route hasn't completed a call, avoid it for new connections.
        if (connection.successCount == 0) {
          if (route != null && e != null) {
            routeSelector.connectFailed(route, e);
          }
          route = null;
        }
      }
      releasedConnection = connection;
      socket = deallocate(noNewStreams, false, true);
      if (connection != null || !reportedAcquired) releasedConnection = null;
    }

    closeQuietly(socket);
    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }
  }

  /**
   * Use this allocation to hold {@code connection}. Each call to this must be paired with a call to
   * {@link #release} on the same connection.
   */
  public void acquire(RealConnection connection, boolean reportedAcquired) {
    assert (Thread.holdsLock(connectionPool));
    if (this.connection != null) throw new IllegalStateException();
    //将传进来的连接赋值给全局变量，同时将分配的流添加到这个连接的allocations集合里
    this.connection = connection;
    this.reportedAcquired = reportedAcquired;
    connection.allocations.add(new StreamAllocationReference(this, callStackTrace));
  }

  /** Remove this allocation from the connection's list of allocations. */
  private void release(RealConnection connection) {
    //作用是从连接的StreamAllocation链表中删除当前StreamAllocation，解除连接和StreamAllocation的引用关系
    for (int i = 0, size = connection.allocations.size(); i < size; i++) {
      Reference<StreamAllocation> reference = connection.allocations.get(i);
      if (reference.get() == this) {
        connection.allocations.remove(i);
        return;
      }
    }
    throw new IllegalStateException();
  }

  /**
   * Release the connection held by this connection and acquire {@code newConnection} instead. It is
   * only safe to call this if the held connection is newly connected but duplicated by {@code
   * newConnection}. Typically this occurs when concurrently connecting to an HTTP/2 webserver.
   *
   * <p>Returns a closeable that the caller should pass to {@link Util#closeQuietly} upon completion
   * of the synchronized block. (We don't do I/O while synchronized on the connection pool.)
   */
  public Socket releaseAndAcquire(RealConnection newConnection) {
    assert (Thread.holdsLock(connectionPool));
    if (codec != null || connection.allocations.size() != 1) throw new IllegalStateException();

    // Release the old connection.
    Reference<StreamAllocation> onlyAllocation = connection.allocations.get(0);
    Socket socket = deallocate(true, false, false);

    // Acquire the new connection.
    this.connection = newConnection;
    newConnection.allocations.add(onlyAllocation);

    return socket;
  }

  //判断是否有可用的路由
  public boolean hasMoreRoutes() {
    //没有可选择的路由意味着
    //没有下一个 IP
    //没有下一个代理
    //没有下一个延迟使用的 Route（之前有失败过的路由，会在这个列表中延迟使用）
    return route != null
        || (routeSelection != null && routeSelection.hasNext())
        || routeSelector.hasNext();
  }

  @Override public String toString() {
    RealConnection connection = connection();
    return connection != null ? connection.toString() : address.toString();
  }

  public static final class StreamAllocationReference extends WeakReference<StreamAllocation> {
    /**
     * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
     * identifying the origin of connection leaks.
     */
    public final Object callStackTrace;

    StreamAllocationReference(StreamAllocation referent, Object callStackTrace) {
      super(referent);
      this.callStackTrace = callStackTrace;
    }
  }
}
