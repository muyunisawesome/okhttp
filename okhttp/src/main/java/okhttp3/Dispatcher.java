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
package okhttp3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;

/**
 * 在OKHttp中Dispatcher就是用来保存并管理用户产生的同步请求（RealCall）和异步请求（AsyncCall），
 * 并负责执行AsyncCall<P></P>
 * 这里说明下单个host同时执行的最大请求数量，host在这里指的是hostname，即主机名（代表一个主机，每个主机都有一个唯一标识，即ip地址，但是每个主机的主机名并不一定是唯一的），
 * 你可以理解为同时往同一个服务器上发送的请求数量不能超过5个；不过OKHttp是通过URL的主机名判断的，所以对同一个ip地址的并发请求仍然可能会超过5个，因为多个主机名可能共享同一个ip地址或者路由（相同的HTTP代理
 * <P></P>
 * 有的人可能说了，用这些队列有什么用呢？好处在哪呢？
 *
 * 要知道通过这些队列，OKHttp可以轻松的实现并发请求，更方便的维护请求数以及后续对这些请求的操作（比如取消请求），大大提高网络请求效率；同时可以更好的管理请求数，防止同时运行的线程过多，导致OOM
 * ，同时限制了同一hostname下的请求数，防止一个应用占用的网络资源过多，优化用户体验<
 * P></P>
 * 政策：什么时候异步请求被执行。
 * 每个派发器的内部使用一个ExecutorService来运行调用。如果你提供了自己的executor，他应该能并发的执行
 * getMaxRequests配置的最大数量的调用。<P></P>
 *
 * Policy on when async requests are executed.
 *
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 */
public final class Dispatcher {
  //这个要求只针对异步请求，对于同步请求数量不做限制
  private int maxRequests = 64; //总最大请求数
  private int maxRequestsPerHost = 5; //每个请求的最大请求数

  private @Nullable Runnable idleCallback; //空闲回调任务

  /** Executes calls. Created lazily. */
  private @Nullable ExecutorService executorService; //执行AsyncCall的线程池，懒加载方式创建。

  /** Ready async calls in the order they'll be run. */
  // 准备执行异步的调用，以他们将被执行的顺序。
  // 一个新的异步请求首先会被加入该队列中
  private final Deque<AsyncCall> readyAsyncCalls = new ArrayDeque<>();

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
  // 当前正在运行中的异步请求
  private final Deque<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

  /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
  // 当前正在运行的同步请求（包含了已取消但未完成的请求）
  private final Deque<RealCall> runningSyncCalls = new ArrayDeque<>();

  public Dispatcher(ExecutorService executorService) {
    this.executorService = executorService;
  }

  public Dispatcher() {
  }

  /** 这个线程池没有核心线程，线程数量没有限制，空闲60s就会回收*/
  // 那是不是对手机性能消耗是不是特别大；其实不会，因为OKHttp对正在进行的异步请求数有限制，最大是64个
  public synchronized ExecutorService executorService() {
    if (executorService == null) {
      executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
    }
    return executorService;
  }

  /**
   * Set the maximum number of requests to execute concurrently. Above this requests queue in
   * memory, waiting for the running calls to complete.
   *
   * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
   * will remain in flight.
   */
  public synchronized void setMaxRequests(int maxRequests) {
    if (maxRequests < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequests);
    }
    this.maxRequests = maxRequests;
    promoteCalls();
  }

  public synchronized int getMaxRequests() {
    return maxRequests;
  }

  /**
   * Set the maximum number of requests for each host to execute concurrently. This limits requests
   * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
   * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
   * proxy.
   *
   * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
   * requests will remain in flight.
   */
  public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
    if (maxRequestsPerHost < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
    }
    this.maxRequestsPerHost = maxRequestsPerHost;
    promoteCalls();
  }

  public synchronized int getMaxRequestsPerHost() {
    return maxRequestsPerHost;
  }

  /**
   * Set a callback to be invoked each time the dispatcher becomes idle (when the number of running
   * calls returns to zero).
   *
   * <p>Note: The time at which a {@linkplain Call call} is considered idle is different depending
   * on whether it was run {@linkplain Call#enqueue(Callback) asynchronously} or
   * {@linkplain Call#execute() synchronously}. Asynchronous calls become idle after the
   * {@link Callback#onResponse onResponse} or {@link Callback#onFailure onFailure} callback has
   * returned. Synchronous calls become idle once {@link Call#execute() execute()} returns. This
   * means that if you are doing synchronous calls the network layer will not truly be idle until
   * every returned {@link Response} has been closed.
   */
  public synchronized void setIdleCallback(@Nullable Runnable idleCallback) {
    this.idleCallback = idleCallback;
  }

  /**
   * 执行异步请求
   */
  synchronized void enqueue(AsyncCall call) {
    //正在执行的任务数量小于最大值（64），并且此任务所属主机的正在执行任务小于最大值（5）
    if (runningAsyncCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
      //加入立刻执行队列
      runningAsyncCalls.add(call);
      //投入线程池执行
      executorService().execute(call);
    } else {
      //否则加入准备队列
      readyAsyncCalls.add(call);
    }
  }

  /**
   * 取消所有请求<P></P>
   *
   * Cancel all calls currently enqueued or executing. Includes calls executed both {@linkplain
   * Call#execute() synchronously} and {@linkplain Call#enqueue asynchronously}.
   */
  public synchronized void cancelAll() {
    for (AsyncCall call : readyAsyncCalls) {
      call.get().cancel();
    }

    for (AsyncCall call : runningAsyncCalls) {
      call.get().cancel();
    }

    for (RealCall call : runningSyncCalls) {
      call.cancel();
    }
  }

  //调整请求队列，将等待队列中的请求放入正在请求的队列
  private void promoteCalls() {

    //如果正在进行请求的异步队列大小大于等于64，或者正在等待执行的异步请求队列是空的，那就直接返回
    if (runningAsyncCalls.size() >= maxRequests) return; // Already running max capacity.
    if (readyAsyncCalls.isEmpty()) return; // No ready calls to promote.

    //对readyAsyncCalls队列进行迭代
    for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
      AsyncCall call = i.next();
      //如果同一个host上的请求数小于5个，那就将这个请求添加到runningAsyncCalls中并执行它，同时从readyAsyncCalls移除
      if (runningCallsForHost(call) < maxRequestsPerHost) {
        i.remove();
        runningAsyncCalls.add(call);
        executorService().execute(call);
      }

      //当runningAsyncCalls满了(大小大于等于64)，直接退出迭代
      if (runningAsyncCalls.size() >= maxRequests) return; // Reached max capacity.
    }
  }

  /** 返回单个host的请求数 */
  /** Returns the number of running calls that share a host with {@code call}. <br>
   * 返回 那些用call共享一个主机的运行中call的数量 */
  private int runningCallsForHost(AsyncCall call) {
    int result = 0;
    for (AsyncCall c : runningAsyncCalls) {
      if (c.host().equals(call.host())) result++;
    }
    return result;
  }

  /** 执行同步请求，只是将其添加到队列中 */
  /** Used by {@code Call#execute} to signal it is in-flight.<br>
   * {@code Call#execute}用来标记它在飞行中*/
  synchronized void executed(RealCall call) {
    runningSyncCalls.add(call);
  }

  /** 异步请求执行完成调用. */
  /** Used by {@code AsyncCall#run} to signal completion. */
  void finished(AsyncCall call) {
    finished(runningAsyncCalls, call, true);
  }

  /** 同步请求执行完成调用. */
  /** Used by {@code Call#execute} to signal completion. */
  void finished(RealCall call) {
    finished(runningSyncCalls, call, false);
  }

  //泛型函数，同时接受 同步、异步Call
  private <T> void finished(Deque<T> calls, T call, boolean promoteCalls) {
    int runningCallsCount;
    Runnable idleCallback;
    synchronized (this) {
      //从指定队列（即正在执行队列）中，移出此call
      if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
      //如果是异步，则推动下一个任务的执行。promote促进
      if (promoteCalls) promoteCalls();
      //获取正在执行的任务数量 = 同步+异步的
      runningCallsCount = runningCallsCount();
      idleCallback = this.idleCallback;
    }
    //如果没有正在执行的任务，且idleCallback不为null，则回调通知空闲了
    if (runningCallsCount == 0 && idleCallback != null) {
      idleCallback.run();
    }
  }

  /** 返回当前正在等待执行的异步请求的快照. */
  /** Returns a snapshot of the calls currently awaiting execution. */
  public synchronized List<Call> queuedCalls() {
    List<Call> result = new ArrayList<>();
    for (AsyncCall asyncCall : readyAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  /** 返回当前正在执行的异步请求的快照. */
  /** Returns a snapshot of the calls currently being executed. */
  public synchronized List<Call> runningCalls() {
    List<Call> result = new ArrayList<>();
    result.addAll(runningSyncCalls);
    for (AsyncCall asyncCall : runningAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  //返回等待执行的异步请求数量
  public synchronized int queuedCallsCount() {
    return readyAsyncCalls.size();
  }

  //计算正在执行的请求数量
  public synchronized int runningCallsCount() {
    return runningAsyncCalls.size() + runningSyncCalls.size();
  }
}
