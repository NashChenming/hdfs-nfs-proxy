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

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import com.cloudera.hadoop.hdfs.nfs.Bytes;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

public class FileBackedWrite extends AbstractPendingWrite {

  private final File backingFile;
  private final int length;
  private final int hashCode;
  private final int size;
  
  public FileBackedWrite(File backingFile, String name, int xid, long offset, boolean sync,
      byte[] data, int start, int length) {
    super(name, xid, offset, sync);
    this.backingFile = backingFile;
    this.length = length;
    this.hashCode = getHashCode(offset, data, start, length);
    this.size = getSize(name, length);
    try {
      writeBytes(data, start, length);      
    } catch (IOException e) {
      try {
        writeBytes(data, start, length);
      } catch (IOException ex) {
        Throwables.propagate(ex);
      }
    }
  }
  @Override
  public int getSize() {
    return size;
  }
  @Override
  public int hashCode() {
    return hashCode;
  }
  @Override
  public byte[] getData() {
    try {
      return readBytes();
    } catch (Exception e) {
      try {
        return readBytes();
      } catch (Exception ex) {
        throw Throwables.propagate(ex);
      }
    }
  }
  @Override
  public int getStart() {
    return 0;
  }
  @Override
  public int getLength() {
    return length;
  }
  @Override
  public void close() {
   backingFile.delete(); 
  }
  private void writeBytes(byte[] buffer, int start, int length) 
      throws IOException {
    FileOutputStream out = new FileOutputStream(backingFile);
    try {
      out.write(buffer, start, length);
    } finally {
      out.close();
    }
  }
  private byte[] readBytes() 
      throws IOException {
    Preconditions.checkArgument(this.length == (int)backingFile.length());
    byte[] buffer = new byte[length];
    DataInputStream in = new DataInputStream(new FileInputStream(backingFile));
    try {
      in.readFully(buffer);
      return buffer;
    } finally {
      in.close();
    }
  }
  private static int getSize(String name, int dataLength) {
    int size = 4; // obj header     
    size += name.length() + 4; // string, 4 byte length?
    size += 4; // xid
    size += 8; // offset
    size += 1; // sync
    size += dataLength; // data
    size += 4; // start
    size += 4; // length
    size += 4; // hashcode
    size += 4; // size
    return size;
  }
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    FileBackedWrite other = (FileBackedWrite) obj;
    if (getOffset() != other.getOffset()) {
      return false;
    }
    // this is very expensive but should be called only in extremely rare cases
    return Bytes.compareTo(getData(), 0, length, other.getData(), other.getStart(), other.length) == 0;
  }
  @Override
  public String toString() {
    return "FileBackedWrite [backingFile=" + backingFile + ", length=" + length
        + ", hashCode=" + hashCode + ", size=" + size + "]";
  }
}
