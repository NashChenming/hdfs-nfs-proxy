/**
 * Copyright 2012 Cloudera Inc.
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

import static org.junit.Assert.*;

import java.util.Set;

import org.junit.Test;

import com.cloudera.hadoop.hdfs.nfs.nfs4.OperationFactory.Holder;

public class TestOperationFactory {

  @Test
  public void testIdentifiers() throws Exception{
    Set<Integer> ids = OperationFactory.operations.keySet();
    if(ids.isEmpty()) {
      throw new RuntimeException("No operations");
    }
    for(Integer id : ids) {
      Holder holder = OperationFactory.operations.get(id);
      Identifiable request = holder.requestClazz.newInstance();
      if(request.getID() != id) {
        fail(request.getClass().getName() + " has id " + request.getID() + " and not " + id);
      }
      Identifiable response = holder.responseClazz.newInstance();
      if(response.getID() != id) {
        fail(response.getClass().getName() + " has id " + response.getID() + " and not " + id);
      }
    }
  }

  @Test(expected=UnsupportedOperationException.class)
  public void testNotSupported() {
    OperationFactory.checkSupported(Integer.MIN_VALUE);
  }
}
