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
package okhttp3;

import java.lang.ref.Reference;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.Util.closeQuietly;

/**
 * 管理HTTP和HTTP/2的重用，以减少网络延迟，相同的Address的HTTP请求将共享同一个Connection<br>
 *
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that
 * share the same {@link Address} may share a {@link Connection}. This class implements the policy
 * of which connections to keep open for future use.
 */
public final class ConnectionPool {
  /**
   * Background threads are used to cleanup expired connections. There will be at most a single
   * thread running per connection pool. The thread pool executor permits the pool itself to be
   * garbage collected.
   */
  private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
      Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
      new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

  /** The maximum number of idle connections for each address. */
  private final int maxIdleConnections;   //每个address的最大空闲连接数
  private final long keepAliveDurationNs;  //每个连接的最大保活时间
  private final Runnable cleanupRunnable = new Runnable() {
    @Override public void run() {
      while (true) {
        //对连接池进行清理，返回下次清理等待时间
        long waitNanos = cleanup(System.nanoTime());
        if (waitNanos == -1) return;
        if (waitNanos > 0) {
          long waitMillis = waitNanos / 1000000L;
          waitNanos -= (waitMillis * 1000000L);
          synchronized (ConnectionPool.this) {
            try {
              //暂停当前线程
              ConnectionPool.this.wait(waitMillis, (int) waitNanos);
            } catch (InterruptedException ignored) {
            }
          }
        }
      }
    }
  };

  private final Deque<RealConnection> connections = new ArrayDeque<>(); //用来保存连接
  final RouteDatabase routeDatabase = new RouteDatabase();  //路由黑名单，记录不可用的route
  boolean cleanupRunning;   //清理任务正在执行的标志

  /**
   * //OkHttpClient内部调用该方法实例化
   * Create a new connection pool with tuning parameters appropriate for a single-user application.
   * The tuning parameters in this pool are subject to change in future OkHttp releases. Currently
   * this pool holds up to 5 idle connections which will be evicted after 5 minutes of inactivity.
   */
  public ConnectionPool() {
    this(5, 5, TimeUnit.MINUTES);
  }

  public ConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

    // 需要给keep alive duration设置下限
    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    if (keepAliveDuration <= 0) {
      throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
    }
  }

  /** Returns the number of idle connections in the pool. */
  public synchronized int idleConnectionCount() {
    int total = 0;
    for (RealConnection connection : connections) {
      if (connection.allocations.isEmpty()) total++;
    }
    return total;
  }

  /**
   * Returns total number of connections in the pool. Note that prior to OkHttp 2.7 this included
   * only idle connections and HTTP/2 connections. Since OkHttp 2.7 this includes all connections,
   * both active and inactive. Use {@link #idleConnectionCount()} to count connections not currently
   * in use.
   */
  public synchronized int connectionCount() {
    return connections.size();
  }

  /**
   * Returns a recycled connection to {@code address}, or null if no such connection exists. The
   * route is null if the address has not yet been routed.
   */
  @Nullable RealConnection get(Address address, StreamAllocation streamAllocation, Route route) {
    //断言，判断线程是不是被自己锁住了
    assert (Thread.holdsLock(this));
    for (RealConnection connection : connections) {
      if (connection.isEligible(address, route)) {
        //复用这个连接
        streamAllocation.acquire(connection, true);
        return connection;
      }
    }
    return null;
  }

  /**
   * 看名称是去重的意思，主要是判断如果当前请求是HTTP/2，那么所有指向该地址的请求都应该共享同一个TCP连接，也就是多路复用，
   * 那么就遍历连接池中所有连接，找到一个可以复用的连接<br>
   *
   * Replaces the connection held by {@code streamAllocation} with a shared connection if possible.
   * This recovers when multiple multiplexed connections are created concurrently.
   */
  @Nullable Socket deduplicate(Address address, StreamAllocation streamAllocation) {
    assert (Thread.holdsLock(this));
    for (RealConnection connection : connections) {
      if (connection.isEligible(address, null)
          && connection.isMultiplexed()
          && connection != streamAllocation.connection()) {
        return streamAllocation.releaseAndAcquire(connection);
      }
    }
    return null;
  }

  void put(RealConnection connection) {
    //断言，判断线程是不是被自己锁住了
    assert (Thread.holdsLock(this));
    if (!cleanupRunning) { //是否需要执行清除空闲连接操作
      cleanupRunning = true;
      executor.execute(cleanupRunnable);
    }
    connections.add(connection);
  }

  /**
   * Notify this pool that {@code connection} has become idle. Returns true if the connection has
   * been removed from the pool and should be closed.
   */
  boolean connectionBecameIdle(RealConnection connection) {
    //判断一个连接是否是空闲连接，如果是，就需要从集合中清除；否则唤醒清除线程
    assert (Thread.holdsLock(this));
    if (connection.noNewStreams || maxIdleConnections == 0) {
      connections.remove(connection);
      return true;
    } else {
      // 唤醒清理线程,我们可能已超过空闲连接限制
      notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
      return false;
    }
  }

  //清除所有空闲连接，释放Socket资源
  /** Close and remove all idle connections in the pool. */
  public void evictAll() {
    List<RealConnection> evictedConnections = new ArrayList<>();
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();
        if (connection.allocations.isEmpty()) {
          connection.noNewStreams = true;
          evictedConnections.add(connection);
          i.remove();
        }
      }
    }

    for (RealConnection connection : evictedConnections) {
      closeQuietly(connection.socket());
    }
  }

  /**
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
   * -1 if no further cleanups are required.
   */
  long cleanup(long now) {
    //正在使用的连接数
    int inUseConnectionCount = 0;
    //空闲的连接数
    int idleConnectionCount = 0;
    //空闲时间最长的连接
    RealConnection longestIdleConnection = null;
    //最长的空闲时间
    long longestIdleDurationNs = Long.MIN_VALUE;

    // 通过循环找出一个需要清除的空闲时间最长的连接，或者下次清理的时间
    // Find either a connection to evict, or the time that the next eviction is due.
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();

        // 判断当前连接是否正在使用，主要逻辑是判断RealConnection里的StreamAllocation集合是否为空
        // If the connection is in use, keep searching.
        if (pruneAndGetAllocationCount(connection, now) > 0) {
          //正在使用的连接数+1
          inUseConnectionCount++;
          continue;
        }
        //空闲的连接数+1
        idleConnectionCount++;

        // 如果当前空闲连接的空闲时间大于最大空闲时间，就给longestIdleDurationNs、longestIdleConnection 重新赋值
        // If the connection is ready to be evicted, we're done.
        long idleDurationNs = now - connection.idleAtNanos;
        if (idleDurationNs > longestIdleDurationNs) {
          longestIdleDurationNs = idleDurationNs;
          longestIdleConnection = connection;
        }
      }

      if (longestIdleDurationNs >= this.keepAliveDurationNs
          || idleConnectionCount > this.maxIdleConnections) {
        // 如果空闲时间最长的连接的空闲时间等于或大于保活时间
        // 或者空闲连接数等于或者大于最大空闲连接数
        //那么将这个空闲时间最长的连接从集合中清除
        // We've found a connection to evict. Remove it from the list, then close it below (outside
        // of the synchronized block).
        connections.remove(longestIdleConnection);
      } else if (idleConnectionCount > 0) {
        // 如果只是存在空闲连接，就计算出下次清理的时间
        //比如保活时间是5分钟，有一个空闲连接已经空闲了3分钟，那么还有2分钟就达到最长空闲时间，所以5-2=3分钟后要进行清理
        // A connection will be ready to evict soon.
        return keepAliveDurationNs - longestIdleDurationNs;
      } else if (inUseConnectionCount > 0) {
        // 如果没有空闲连接，那就在5分钟后再去清理
        // All connections are in use. It'll be at least the keep alive duration 'til we run again.
        return keepAliveDurationNs;
      } else {
        // 连接池没有连接，返回-1，结束清理线程
        // No connections, idle or in use.
        cleanupRunning = false;
        return -1;
      }
    }

    //关闭这个最长空闲时间的连接的socket资源
    closeQuietly(longestIdleConnection.socket());

    //清理一个空闲时间最长的连接以后，需要立即再次执行清理
    // Cleanup again immediately.
    return 0;
  }

  /**
   * Prunes any leaked allocations and then returns the number of remaining live allocations on
   * {@code connection}. Allocations are leaked if the connection is tracking them but the
   * application code has abandoned them. Leak detection is imprecise and relies on garbage
   * collection.
   */
  private int pruneAndGetAllocationCount(RealConnection connection, long now) {
    List<Reference<StreamAllocation>> references = connection.allocations;
    for (int i = 0; i < references.size(); ) {
      Reference<StreamAllocation> reference = references.get(i);
      //如果StreamAllocation被使用，则遍历下一个
      if (reference.get() != null) {
        i++;
        continue;
      }
      // 到这里说明StreamAllocation未被使用
      // We've discovered a leaked allocation. This is an application bug.
      StreamAllocation.StreamAllocationReference streamAllocRef =
          (StreamAllocation.StreamAllocationReference) reference;
      String message = "A connection to " + connection.route().address().url()
          + " was leaked. Did you forget to close a response body?";
      Platform.get().logCloseableLeak(message, streamAllocRef.callStackTrace);
      //那就删除它
      references.remove(i);
      //修改noNewStreams值，表明这个连接不能再分配新的流了
      connection.noNewStreams = true;
      // 如果集合为null，说明这个连接没有被引用了，是一个空闲连接
      // If this was the last allocation, the connection is eligible for immediate eviction.
      if (references.isEmpty()) {
        connection.idleAtNanos = now - keepAliveDurationNs;
        return 0;
      }
    }
    //返回集合实际大小
    return references.size();
  }
}
