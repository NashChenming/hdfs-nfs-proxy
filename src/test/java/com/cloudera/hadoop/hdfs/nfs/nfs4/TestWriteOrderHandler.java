/**
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.hadoop.hdfs.nfs.nfs4;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.cloudera.hadoop.hdfs.nfs.nfs4.state.HDFSOutputStream;
import com.google.common.collect.Lists;

public class TestWriteOrderHandler {

  HDFSOutputStream mOutputStream;
  WriteOrderHandler mWriteOrderHandler;
  final AtomicInteger xid = new AtomicInteger(0);
  final byte[] buffer = new byte[1000];

  @Before
  public void setup() throws IOException {
    mOutputStream = mock(HDFSOutputStream.class);
    mWriteOrderHandler = new WriteOrderHandler(mOutputStream);
    mWriteOrderHandler.setDaemon(true);
    mWriteOrderHandler.setName("WriteOrderHandler");
    mWriteOrderHandler.start();
  }

  @After
  public void teardown() throws IOException, NFS4Exception {
    mWriteOrderHandler.close();
  }

  @Test
  public void testSimple() throws Exception {
    final AtomicLong count = new AtomicLong(0);
    doAnswer(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) {
        Object[] args = checkNotNull(invocation.getArguments());
        checkArgument(args.length == 3);
        count.addAndGet((Integer)args[2]);
        return null;
      }
    }).when(mOutputStream).write((byte[]) any(), anyInt(), anyInt());
    when(mOutputStream.getPos()).thenAnswer(new Answer<Long>() {
      @Override
      public Long answer(InvocationOnMock invocation) {
        return count.get();
      }
    });
    final WriteOrderHandler writeOrderHandler = mWriteOrderHandler;
    final List<Object> errors = Collections.synchronizedList(Lists.newArrayList());
    int numThreads = 50;
    for (int i = numThreads; i > 0; i--) {
      final long offset = (i-1) * buffer.length;
      new Thread() {
        @Override
        public void run() {
          try {
            int count = writeOrderHandler.write(new MemoryBackedWrite("a file", xid.incrementAndGet(), offset, false, buffer, 0, buffer.length));
            if(count != buffer.length) {
              errors.add(new Exception("Expected to write " + buffer.length + " but wrote " + count));
            }
          } catch (Exception e) {
            errors.add(e);
          }
        }
      }.start();
    }
    {
      long start = System.currentTimeMillis();
      long expectedPos = buffer.length * numThreads;
      while(expectedPos != mOutputStream.getPos()) {
        Thread.sleep(1);
        if(!errors.isEmpty()) {
          String msg = "";
          for(Object o : errors) {
            Exception e = ((Exception)o);
            e.printStackTrace();
            msg += e.getClass().getName() + " => " + e.getMessage() + "\n";
          }
          fail(msg);
        }
        if(System.currentTimeMillis() - start > 20000) {
          fail("Timed out waiting for writes: expectedPos = " + expectedPos + ", pos = " + mOutputStream.getPos());
        }
      }
    }
  }

  @Test(expected=IOException.class)
  public void testCannotWriteToWhenClosed() throws IOException, NFS4Exception {
    mWriteOrderHandler.close();
    mWriteOrderHandler.write(new MemoryBackedWrite("a file", xid.incrementAndGet(), 0, false, buffer, 0, buffer.length));
  }

  @Test(expected=NFS4Exception.class)
  public void testCannotWriteToWhileDead() throws IOException, NFS4Exception, InterruptedException {
    mWriteOrderHandler.close();
    while(mWriteOrderHandler.isAlive()) {
      Thread.sleep(1);
    }
    mWriteOrderHandler.write(new MemoryBackedWrite("a file", xid.incrementAndGet(), 0, false, buffer, 0, buffer.length));
  }

  @Test(expected=IOException.class)
  public void testErrorOnWriteAfterError() throws IOException, NFS4Exception, InterruptedException {
    doThrow(new IOException()).when(mOutputStream).write(any(byte[].class), anyInt(), anyInt());
    mWriteOrderHandler.write(new MemoryBackedWrite("a file", xid.incrementAndGet(), 0, false, buffer, 0, buffer.length));
    Thread.sleep(10);
    mWriteOrderHandler.write(new MemoryBackedWrite("a file", xid.incrementAndGet(), 0, false, buffer, 0, buffer.length));
  }

  @Test
  public void testSyncIsCalledForSyncWrite() throws IOException, NFS4Exception {
    mWriteOrderHandler.write(new MemoryBackedWrite("a file", xid.incrementAndGet(), 0, true, buffer, 0, buffer.length));
    verify(mOutputStream, atLeastOnce()).sync();
  }

  @Test(expected=NFS4Exception.class)
  public void testRandomWrite() throws IOException, NFS4Exception {
    when(mOutputStream.getPos()).thenReturn(1L);
    mWriteOrderHandler.write(new MemoryBackedWrite("a file", xid.incrementAndGet(), 0, true, buffer, 0, buffer.length));
  }
  @Test
  public void testRetransmit() throws IOException, NFS4Exception {
    int id = xid.incrementAndGet();
    int length = mWriteOrderHandler.write(new MemoryBackedWrite("a file", id, 0, true, buffer, 0, buffer.length));
    assertEquals(length, mWriteOrderHandler.write(new MemoryBackedWrite("a file", id, 0, true, buffer, 0, buffer.length)));
  }
}