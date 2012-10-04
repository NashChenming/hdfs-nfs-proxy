/**
 * Copyright 2012 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.cloudera.hadoop.hdfs.nfs.nfs4;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
@SuppressWarnings({"rawtypes","unchecked"})
public class AsyncTaskExecutor<T> {
  protected static final Logger LOGGER = Logger.getLogger(AsyncTaskExecutor.class);

  private static final AtomicInteger instanceCounter = new AtomicInteger(0);
  private final BlockingQueue queue;
  private final ThreadPoolExecutor executor;
  
  public AsyncTaskExecutor() {
    queue = new DelayQueue();
    executor = new ThreadPoolExecutor(10, 500, 5L, TimeUnit.SECONDS, 
        queue, new ThreadFactoryBuilder().setDaemon(true).
        setNameFormat("AsyncTaskExecutor-" + instanceCounter.incrementAndGet() + "-%d")
        .build()) {
      protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        if(runnable instanceof DelayedRunnable) {
          return (FutureTask<T>)runnable;
        }
        return new FutureTask<T>(runnable, value);
      }
    };
  }
  
  public void schedule(final AsyncFuture<T> task) {
    executor.submit(new DelayedRunnable(new TaskRunnable(queue, task)));
  }
  private static class TaskRunnable implements Runnable {
    private AsyncFuture<?> task;
    private BlockingQueue queue;
    public TaskRunnable(BlockingQueue queue, AsyncFuture<?> task) {
      this.queue = queue;
      this.task = task;
    }
    @Override
    public void run() {
      try {
        if(LOGGER.isDebugEnabled()) {
          LOGGER.debug("Running " + task);
        }
        AsyncFuture.Complete status = task.makeProgress();
        if(status != AsyncFuture.Complete.COMPLETE) {
          if(LOGGER.isDebugEnabled()) {
            LOGGER.info("Status of " + task + " is " + status + ", queue.size = " + queue.size());
          }
          queue.add(new DelayedRunnable(this, 1000L));
        }
      } catch (Exception e) {
        LOGGER.error("Unabled exception while executing " + task, e);
      } catch (Error e) {
        LOGGER.error("Unabled error while executing " + task, e);
      }
    }
  }
  private static class DelayedRunnable extends FutureTask<Void> implements Delayed {
    private final long delayMS;
    private final long start;
    public DelayedRunnable(TaskRunnable delegate) {
      this(delegate, 0L);
    }
    public DelayedRunnable(TaskRunnable delegate, long delayMS) {
      super(delegate, null);
      this.delayMS = delayMS;
      this.start = System.currentTimeMillis();
    }
    @Override
    public int compareTo(Delayed other) {
      long d = (getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS));
      return (d == 0) ? 0 : ((d < 0) ? -1 : 1);
    }

    @Override
    public long getDelay(TimeUnit unit) {
      long timeRemaining = System.currentTimeMillis() - start;
      if(timeRemaining >= 0) {
        return TimeUnit.MILLISECONDS.convert(delayMS, unit);
      }
      return 0L;
    }
  }
}
