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
package com.cloudera.hadoop.hdfs.nfs.nfs4.handlers;

import static com.cloudera.hadoop.hdfs.nfs.nfs4.Constants.*;

import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

import com.cloudera.hadoop.hdfs.nfs.nfs4.NFS4Exception;
import com.cloudera.hadoop.hdfs.nfs.nfs4.Session;
import com.cloudera.hadoop.hdfs.nfs.nfs4.requests.PUTROOTFHRequest;
import com.cloudera.hadoop.hdfs.nfs.nfs4.responses.PUTROOTFHResponse;
import com.cloudera.hadoop.hdfs.nfs.nfs4.state.HDFSState;

public class PUTROOTFHHandler extends OperationRequestHandler<PUTROOTFHRequest, PUTROOTFHResponse> {

  protected static final Logger LOGGER = Logger.getLogger(PUTROOTFHHandler.class);

  @Override
  protected PUTROOTFHResponse createResponse() {
    return new PUTROOTFHResponse();
  }

  @Override
  protected PUTROOTFHResponse doHandle(HDFSState hdfsState, Session session,
      PUTROOTFHRequest request) throws NFS4Exception, IOException {
    session.setCurrentFileHandle(hdfsState.getOrCreateFileHandle(new Path("/")));
    PUTROOTFHResponse response = createResponse();
    response.setStatus(NFS4_OK);
    return response;
  }
}
