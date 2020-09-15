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
package okhttp3.internal;

/**
 * 总是这是他自己的线程名的Runnable实现<P></P>
 *
 * Runnable implementation which always sets its thread name.
 */
public abstract class NamedRunnable implements Runnable {
  protected final String name;

  public NamedRunnable(String format, Object... args) {
    this.name = Util.format(format, args);
  }

  @Override public final void run() {
    //这个不错，线程池里线程是共享的，可以在使用的时候短暂改原线程名字，用完还原回去
    String oldName = Thread.currentThread().getName();
    Thread.currentThread().setName(name);
    try {
      execute();
    } finally {
      //这就很妙了
      Thread.currentThread().setName(oldName);
    }
  }

  //模板方法设计模式，让子类将具体的操作放入此方法
  protected abstract void execute();
}
