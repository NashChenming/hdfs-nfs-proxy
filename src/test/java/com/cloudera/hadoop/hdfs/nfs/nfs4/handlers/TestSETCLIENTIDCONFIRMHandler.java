/**
 * Copyright 2012 The Apache Software Foundation
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
package com.cloudera.hadoop.hdfs.nfs.nfs4.handlers;

import static com.cloudera.hadoop.hdfs.nfs.nfs4.Constants.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;

import com.cloudera.hadoop.hdfs.nfs.nfs4.OpaqueData8;
import com.cloudera.hadoop.hdfs.nfs.nfs4.Status;
import com.cloudera.hadoop.hdfs.nfs.nfs4.requests.SETCLIENTIDCONFIRMRequest;

public class TestSETCLIENTIDCONFIRMHandler extends TestBaseHandler {

  private SETCLIENTIDCONFIRMHandler handler;
  private SETCLIENTIDCONFIRMRequest request;

  @Before
  public void setup() throws Exception {
    super.setup();
    handler = new SETCLIENTIDCONFIRMHandler();
    request = new SETCLIENTIDCONFIRMRequest();
    request.setVerifer(clientVerifier);
  }
  @Test
  public void testOldClient() throws Exception {
    when(clientFactory.getByShortHand(any(Long.class))).thenReturn(null);
    Status response = handler.handle(hdfsState, session, request);
    assertEquals(NFS4ERR_STALE_CLIENTID, response.getStatus());
  }
  @Test
  public void testBadVerifer() throws Exception {
    request.setVerifer(new OpaqueData8(1L));
    Status response = handler.handle(hdfsState, session, request);
    assertEquals(NFS4ERR_CLID_INUSE, response.getStatus());
  }
  @Test
  public void testSuccess() throws Exception {
    Status response = handler.handle(hdfsState, session, request);
    assertEquals(NFS4_OK, response.getStatus());
  }
}
