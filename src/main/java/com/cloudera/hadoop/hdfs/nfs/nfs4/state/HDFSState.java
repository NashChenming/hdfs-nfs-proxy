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
package com.cloudera.hadoop.hdfs.nfs.nfs4.state;

import static com.cloudera.hadoop.hdfs.nfs.PathUtils.*;
import static com.cloudera.hadoop.hdfs.nfs.nfs4.Constants.*;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.log4j.Logger;

import com.cloudera.hadoop.hdfs.nfs.PathUtils;
import com.cloudera.hadoop.hdfs.nfs.nfs4.FileHandle;
import com.cloudera.hadoop.hdfs.nfs.nfs4.FileHandleStore;
import com.cloudera.hadoop.hdfs.nfs.nfs4.FileHandleStoreEntry;
import com.cloudera.hadoop.hdfs.nfs.nfs4.Metrics;
import com.cloudera.hadoop.hdfs.nfs.nfs4.NFS4Exception;
import com.cloudera.hadoop.hdfs.nfs.nfs4.StateID;
import com.cloudera.hadoop.hdfs.nfs.nfs4.WriteOrderHandler;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;

public class HDFSState {
  protected static final Logger LOGGER = Logger.getLogger(HDFSState.class);
  private static final AtomicLong FILEID = new AtomicLong(0L);
  private static final Random RANDOM = new Random();
  /**
   * getFileHandle is not synchronized on this as such a concurrent map is used
   */
  private final ConcurrentMap<String, HDFSFile> mPathMap = Maps.newConcurrentMap();
  /**
   * getPath is not synchronized on this as such a concurrent map is used
   */
  private final ConcurrentMap<FileHandle, HDFSFile> mFileHandleMap;
  private final long mStartTime = System.currentTimeMillis();
  private final ClientFactory mClientFactory = new ClientFactory();
  /**
   * Synchronized on the map itself
   */
  private final Map<FileHandle, WriteOrderHandler> mWriteOrderHandlerMap;
  private final FileHandleStore mFileHandleStore;
  private final Metrics mMetrics;
  private final File[] mTempDirs;
  
  public HDFSState(FileHandleStore fileHandleStore, String[] tempDirs, Metrics metrics, 
      Map<FileHandle, WriteOrderHandler> writeOrderHandlerMap,
      ConcurrentMap<FileHandle, HDFSFile> fileHandleMap) throws IOException {
    mFileHandleStore = fileHandleStore;
    mTempDirs = new File[tempDirs.length];
    for (int i = 0; i < tempDirs.length; i++) {
      mTempDirs[i] = new File(tempDirs[i]);
      Preconditions.checkState(mTempDirs[i].isDirectory(), "Path " + mTempDirs[i] + 
          " is not a directory");
    }
    mMetrics = metrics;
    mFileHandleMap = fileHandleMap;
    mWriteOrderHandlerMap = writeOrderHandlerMap;
    for(File tempDir : mTempDirs) {
      try {
        if(tempDir.isDirectory()) {
          PathUtils.fullyDeleteContents(tempDir);          
        } else if(tempDir.isFile()) {
          Preconditions.checkState(tempDir.delete(), "Cannot delete " + tempDir);
        }
      } catch (IOException e) {
        LOGGER.error("Error deleting " + tempDir, e);
        Throwables.propagate(e);
      }
      if(!(tempDir.isDirectory() || tempDir.mkdirs())) {
        throw new IOException("Directory " + tempDir +
            " does not exist or could not be created.");
      }
      File testFile = new File(tempDir, "test");
      try {
        if(testFile.isFile() && !testFile.delete()) {
          throw new IOException("Test file " + testFile + " exists but cannot be deleted");
        }
        if(!testFile.createNewFile()) {
          throw new IOException("Unable to create test file " + testFile);
        }        
      } finally {
        if(!testFile.delete()) {
          LOGGER.warn("Unable to delete " + testFile);
        }
      }
      
    }
    
    LOGGER.info("Writing temp files to " + Arrays.toString(mTempDirs));
    
    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        for(File tempDir : mTempDirs) {
          try {
            PathUtils.fullyDelete(tempDir);
          } catch (IOException e) {
            LOGGER.error("Error deleting " + tempDir, e);
          }
        }
      }
    });
    
    for (FileHandleStoreEntry entry : mFileHandleStore.getAll()) {
      FileHandle fileHandle = new FileHandle(entry.getFileHandle());
      HDFSFile holder = new HDFSFile(fileHandle, entry.getPath(), entry.getFileID());
      putFileHandle(fileHandle, entry.getPath(), holder);
    }
    
  }
  /**
   * Deletes a file from fs. If the file is open for writing,
   * the file will not be deleted.
   * @param fs
   * @param path
   * @return
   * @throws IOException
   */
  public synchronized boolean delete(FileSystem fs, Path path) 
      throws IOException {
    HDFSFile fileHolder = mPathMap.get(realPath(path));
    if (fileHolder != null && fileHolder.isOpenForWrite()) {
      return false;
    }    
    return fs.delete(path, false);
  }
  /**
   * Files which are in the process of being created need to exist from a NFS
   * perspective even if they do not exist from an HDFS perspective. This
   * method intercepts requests for files that are open and calls
   * FileSystem.exists for other files.
   *
   * @param fs
   * @param path
   * @return true if the file exists or is open for write
   * @throws IOException
   */
  public synchronized boolean fileExists(FileSystem fs, Path path)
      throws IOException {
    HDFSFile fileHolder = mPathMap.get(realPath(path));
    if (fileHolder != null && fileHolder.isOpenForWrite()) {
      return true;
    }
    return fs.exists(path);
  }

  /**
   * Return a FileHandle if it exists or create a new one.
   *
   * @param path
   * @return a FileHandle
   * @throws IOException
   */
  public synchronized FileHandle createFileHandle(Path path) throws IOException {
    String realPath = realPath(path);
    HDFSFile file = mPathMap.get(realPath);
    if (file != null) {
      return file.getFileHandle();
    }
    String s = UUID.randomUUID().toString().replace("-", "");
    byte[] bytes = s.getBytes(Charsets.UTF_8);
    FileHandle fileHandle = new FileHandle(bytes);
    HDFSFile holder = new HDFSFile(fileHandle, realPath, getNextFileID());
    FileHandleStoreEntry storeEntry = new FileHandleStoreEntry(bytes, realPath, holder.getFileID());
    mFileHandleStore.storeFileHandle(storeEntry);
    putFileHandle(fileHandle, realPath, holder);
    return fileHandle;
  }

  protected synchronized void putFileHandle(FileHandle fileHandle, String path, HDFSFile holder) {
    mPathMap.put(path, holder);
    mFileHandleMap.put(fileHandle, holder);
    mMetrics.incrementMetric("FILE_HANDLES_CREATED", 1);
  }

  /**
   * The NFS fileid is translated to an inode on the host. As such we need to
   * have a unique fileid for each path. Return the file id for a given path
   * or throw a NFSException(STALE).
   *
   * @param path
   * @return fileid
   * @throws NFS4Exception if the file does not have a FileHandle/
   */
  public synchronized long getFileID(Path path) throws NFS4Exception {
    HDFSFile file = mPathMap.get(realPath(path));
    if (file != null) {
      return file.getFileID();
    }
    throw new NFS4Exception(NFS4ERR_STALE, "Path " + realPath(path));
  }

  /**
   * Close the resources allocated in the server, block until the writes for
   * this file have been processed and close the underlying stream. No lock is
   * held while we wait for the writes to be processed.
   *
   * @param sessionID
   * @param stateID
   * @param seqID
   * @param fileHandle
   * @return and updated StateID
   * @throws NFS4Exception if the file is open or the stateid is old
   * @throws IOException if the underlying streams throw an excpetion
   */
  public StateID close(String sessionID, StateID stateID, int seqID,
      FileHandle fileHandle) throws NFS4Exception, IOException {
    HDFSFile hdfsFile = null;
    WriteOrderHandler writeOrderHandler = null;
    OpenResource<?> file = null;
    synchronized (this) {
      hdfsFile = mFileHandleMap.get(fileHandle);
      if (hdfsFile == null) {
        throw new NFS4Exception(NFS4ERR_STALE);
      }
      if (hdfsFile.isOpenForWrite()) {
        LOGGER.info(sessionID + " Closing " + hdfsFile.getPath()
            + " for write");
        file = hdfsFile.getHDFSOutputStream();
      } else {
        LOGGER.info(sessionID + " Closing " + hdfsFile.getPath()
            + " for read");
        file = hdfsFile.getInputStream(stateID);
      }
      if (file == null) {
        throw new NFS4Exception(NFS4ERR_OLD_STATEID);
      }
      if (!file.isOwnedBy(stateID)) {
        throw new NFS4Exception(NFS4ERR_FILE_OPEN);
      }
      file.setSequenceID(seqID);
      synchronized (mWriteOrderHandlerMap) {
        writeOrderHandler = mWriteOrderHandlerMap.remove(fileHandle);
      }
    }
    if (writeOrderHandler != null) {
      writeOrderHandler.close(); // blocks
      LOGGER.info(sessionID + " Closed WriteOrderHandler for "
          + hdfsFile.getPath());
    }
    synchronized (this) {
      if(hdfsFile.isOpenForRead()) {
        hdfsFile.closeInputStream(stateID);        
      } else if(hdfsFile.isOpenForWrite()) {
        hdfsFile.closeOutputStream(stateID);
      } else {
        LOGGER.warn("File " + hdfsFile + " is not open for read or write.");
      }
      mMetrics.incrementMetric("FILES_CLOSED", 1);
      return file.getStateID();
    }
  }

  /**
   * Confirm a file in accordance with NFS OPEN_CONFIRM.
   *
   * @param stateID
   * @param seqID
   * @param fileHandle
   * @return the stateid associated with the open file
   * @throws NFS4Exception if the stateid is old or open file is owned by
   * another stateid
   */
  public synchronized StateID confirm(StateID stateID, int seqID,
      FileHandle fileHandle) throws NFS4Exception {
    HDFSFile fileHolder = mFileHandleMap.get(fileHandle);
    OpenResource<?> file = null;
    if (fileHolder != null) {
      if (fileHolder.isOpenForWrite()) {
        file = fileHolder.getHDFSOutputStreamForWrite();
      } else {
        file = fileHolder.getInputStream(stateID);
      }
      if (file == null) {
        throw new NFS4Exception(NFS4ERR_OLD_STATEID);
      }
      if (!file.isOwnedBy(stateID)) {
        throw new NFS4Exception(NFS4ERR_FILE_OPEN); // TODO lock unavailable
        // should be _LOCK?
      }
      file.setConfirmed(true);
      file.setSequenceID(seqID);
      return file.getStateID();
    }
    throw new NFS4Exception(NFS4ERR_STALE);
  }

  /**
   * Returns true if the file is open.
   *
   * @param path
   * @return true if the file is open for read or write
   */
  public synchronized boolean isFileOpen(Path path) {
    HDFSFile fileHolder = mPathMap.get(realPath(path));
    if (fileHolder != null) {
      return fileHolder.isOpen();
    }
    return false;
  }

  /**
   * Open if not open or obtain the input stream opened by the StateID.
   *
   * @param stateID
   * @param fs
   * @param fileHandle
   * @return FSDataInputStream for reading
   * @throws NFS4Exception if the file is already open for write, the open is
   * not confirmed or the file handle is stale.
   * @throws IOException if the file open throws an IOException
   */
  public synchronized HDFSInputStream forRead(StateID stateID, FileSystem fs,
      FileHandle fileHandle) throws NFS4Exception, IOException {
    HDFSFile fileHolder = mFileHandleMap.get(fileHandle);
    if (fileHolder != null) {
      if (fileHolder.isOpenForWrite()) {
        throw new NFS4Exception(NFS4ERR_FILE_OPEN); // TODO lock unavailable
        // should be _LOCK?
      }
      Path path = new Path(fileHolder.getPath());
      OpenResource<HDFSInputStream> file = fileHolder.getInputStream(stateID);
      if (file != null) {
        if (!file.isConfirmed()) {
          throw new NFS4Exception(NFS4ERR_DENIED);
        }
        return file.get();
      }
      FileStatus status = fs.getFileStatus(path);
      if (status.isDir()) {
        throw new NFS4Exception(NFS4ERR_ISDIR);
      }
      HDFSInputStream in = new HDFSInputStream(fs.open(path));
      mMetrics.incrementMetric("FILES_OPENED_READ", 1);
      fileHolder.putInputStream(stateID, in);
      return in;
    }
    throw new NFS4Exception(NFS4ERR_STALE);
  }

  /**
   * Create or return the a WriteOrderHandler for a given FSDataOutputStream.
   *
   * @param name
   * @param out
   * @return the WriteOrderHandler
   * @throws IOException of the output stream throws an IO Exception while
   * creating the WriteOrderHandler.
   */
  public WriteOrderHandler getOrCreateWriteOrderHandler(FileHandle fileHandle) 
      throws IOException, NFS4Exception {
    WriteOrderHandler writeOrderHandler;
    synchronized (mWriteOrderHandlerMap) {
      writeOrderHandler = getWriteOrderHandler(fileHandle);
      if (writeOrderHandler == null) {
        HDFSFile hdfsFile = mFileHandleMap.get(fileHandle);
        if (hdfsFile == null) {
          throw new NFS4Exception(NFS4ERR_STALE);
        }
        OpenResource<HDFSOutputStream> file = hdfsFile.getHDFSOutputStream();
        Preconditions.checkState(file != null);
        writeOrderHandler = new WriteOrderHandler(mTempDirs, file.get());
        writeOrderHandler.setDaemon(true);
        writeOrderHandler.setName("WriteOrderHandler-" + getPath(fileHandle));
        writeOrderHandler.start();
        mWriteOrderHandlerMap.put(fileHandle, writeOrderHandler);
      }
    }
    return writeOrderHandler;
  }

  public WriteOrderHandler getWriteOrderHandler(FileHandle fileHandle) {
    synchronized (mWriteOrderHandlerMap) {
      if (mWriteOrderHandlerMap.containsKey(fileHandle)) {
        return mWriteOrderHandlerMap.get(fileHandle);
      }
    }
    return null;
  }
  
  /**
   *
   * @param stateID
   * @param fs
   * @param fileHandle
   * @param overwrite
   * @return
   * @throws NFS4Exception
   * @throws IOException
   */
  public synchronized HDFSOutputStream forWrite(StateID stateID,
      FileSystem fs, FileHandle fileHandle, boolean overwrite)
          throws NFS4Exception, IOException {
    HDFSFile fileHolder = mFileHandleMap.get(fileHandle);
    if (fileHolder != null) {
      OpenResource<HDFSOutputStream> file = fileHolder.getHDFSOutputStreamForWrite();
      if (file != null) {
        if (file.isOwnedBy(stateID)) {
          return file.get();
        }
        throw new NFS4Exception(NFS4ERR_FILE_OPEN);
      }
      Path path = new Path(fileHolder.getPath());
      boolean exists = fs.exists(path);
      // If overwrite = false, fs.create throws IOException which
          // is useless. In case of IOE do we always return EXIST?
      // doesn't seem to make sense. As such, I am mitigating the issue
      // even if there is a known race between the exists and create
      if (!overwrite && exists) {
        // append to a file
        // We used to be NFS4ERR_EXIST here but the linux client behaved 
        // rather oddly. It would open the file with overwrite=true but 
        // then send the data which was to be appended at offset 0
        throw new NFS4Exception(NFS4ERR_PERM,
            "File Exists and overwrite = false", true);
      }
      if (path.getParent() != null) {
        // TODO bad perms will fail with IOException, perhaps we should check
        // that file can be created before trying to so we can return the
        // correct error perm denied
        // check(user, groups, status, access);
      }
      if (exists && fs.getFileStatus(path).isDir()) {
        throw new NFS4Exception(NFS4ERR_ISDIR);
      }
      HDFSOutputStream out = new HDFSOutputStream(fs.create(path, overwrite), path.toString(), fileHandle);
      mMetrics.incrementMetric("FILES_OPENED_WRITE", 1);
      fileHolder.setHDFSOutputStream(stateID, out);
      return out;
    }
    throw new NFS4Exception(NFS4ERR_STALE);
  }

  protected boolean check(String user, List<String> groups, FileStatus
      status, FsAction access) {
    FsPermission mode = status.getPermission();
    if (user.equals(status.getOwner())) { // user class
      if (mode.getUserAction().implies(access)) {
        return true;
      }
    } else if (groups.contains(status.getGroup())) { // group class
      if (mode.getGroupAction().implies(access)) {
        return true;
      }
    } else { // other class
      if (mode.getOtherAction().implies(access)) {
        return true;
      }
    }
    return false;
  }
  /**
   * Get the Path for a FileHandle
   *
   * @param fileHandle
   * @return Path for FileHandler
   * @throws NFS4Exception if FileHandle is stale
   */
  public Path getPath(FileHandle fileHandle) throws NFS4Exception {
    HDFSFile file = mFileHandleMap.get(fileHandle);
    if (file != null) {
      return new Path(file.getPath());
    }
    throw new NFS4Exception(NFS4ERR_STALE);
  }

  /**
   * Get the FileHandle for a Path
   *
   * @param path
   * @return FileHandle for path
   * @throws NFS4Exception if the FileHandle for the Path is stale
   */
  public FileHandle getFileHandle(Path path) throws NFS4Exception {
    HDFSFile file = mPathMap.get(realPath(path));
    if (file != null) {
      return file.getFileHandle();
    }
    throw new NFS4Exception(NFS4ERR_STALE);
  }

  /**
   * Files open for write will have an unreliable length according to the name
   * node. As such, this call intercepts calls for open files and returns the
   * length of the as reported by the output stream.
   *
   * @param status
   * @return the current file length including data written to the output
   * stream
   * @throws NFS4Exception if the getPos() call of the output stream throws
   * IOException
   */
  public long getFileSize(FileStatus status) throws NFS4Exception {
    HDFSFile fileHolder = mPathMap.get(realPath(status.getPath()));
    if (fileHolder != null) {
      OpenResource<HDFSOutputStream> file = fileHolder.getHDFSOutputStreamForWrite();
      if (file != null) {
        HDFSOutputStream out = file.get();
        return out.getPos();
      }
    }
    return status.getLen();
  }

  /**
   * @return the ClientFactory in use by the NFS4 Handler
   */
  public ClientFactory getClientFactory() {
    return mClientFactory;
  }
  /**
   * Close the state store
   * @throws IOException
   */
  public void close() throws IOException {
    mFileHandleStore.close();
  }
  /**
   * Increment name by count
   * @param name
   * @param count
   */
  public void incrementMetric(String name, long count) {
    mMetrics.incrementMetric(name, count);
  }
  /**
   * @return next fileID
   */
  private long getNextFileID() {
    synchronized (RANDOM) {
      return FILEID.addAndGet(RANDOM.nextInt(20) + 1);
    }
  }
  /**
   * Get the start time of the NFS server in milliseconds
   * @return start time in ms
   */
  public long getStartTime() {
    return mStartTime;
  }
}
