package com.cloudera.hadoop.hdfs.nfs.nfs4;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
@SuppressWarnings({"rawtypes","unchecked"})
public class AsyncTaskExecutor<T> {
  protected static final Logger LOGGER = Logger.getLogger(AsyncTaskExecutor.class);

  private final BlockingQueue queue;
  private final ThreadPoolExecutor executor;
  
  public AsyncTaskExecutor() {
    queue = new DelayQueue();
    executor = new ThreadPoolExecutor(10, 500, 5L, TimeUnit.SECONDS, 
        queue, 
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("-%d").build());
    
  }
  
  public void schedule(final AsyncFuture<T> task) {
    executor.submit(new Runnable() {
      @Override
      public void run() {
        try {
          if(task.makeProgress() != AsyncFuture.Complete.COMPLETE) {
            queue.add(new DelayedRunnable(this, 1, TimeUnit.SECONDS));
          }
        } catch (Exception e) {
          LOGGER.error("Unabled exception while executing " + task, e);
        } catch (Error e) {
          LOGGER.error("Unabled error while executing " + task, e);
        }
      }
    });
  }
  
  private static class DelayedRunnable implements Runnable, Delayed {
    private final Runnable delegate;
    private final long delay;
    private final TimeUnit unit;
    public DelayedRunnable(Runnable delegate, long delay, TimeUnit unit) {
      this.delegate = delegate;
      this.delay = delay;
      this.unit = unit;
    }
    public void run() {
      delegate.run();
    }

    @Override
    public int compareTo(Delayed other) {
      long d = (getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS));
      return (d == 0) ? 0 : ((d < 0) ? -1 : 1);
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(delay, this.unit);
    }
  }
}
