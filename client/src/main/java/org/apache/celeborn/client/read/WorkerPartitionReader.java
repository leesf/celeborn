/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.client.read;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.celeborn.common.CelebornConf;
import org.apache.celeborn.common.network.buffer.ManagedBuffer;
import org.apache.celeborn.common.network.buffer.NettyManagedBuffer;
import org.apache.celeborn.common.network.client.ChunkReceivedCallback;
import org.apache.celeborn.common.network.client.TransportClientFactory;
import org.apache.celeborn.common.protocol.PartitionLocation;

public class WorkerPartitionReader implements PartitionReader {
  private final Logger logger = LoggerFactory.getLogger(WorkerPartitionReader.class);
  private final RetryingChunkClient client;
  private final int numChunks;

  private int returnedChunks;
  private int chunkIndex;

  private final LinkedBlockingQueue<ByteBuf> results;

  private final AtomicReference<IOException> exception = new AtomicReference<>();
  private final int fetchMaxReqsInFlight;
  private boolean closed = false;

  WorkerPartitionReader(
      CelebornConf conf,
      String shuffleKey,
      PartitionLocation location,
      TransportClientFactory clientFactory,
      int startMapIndex,
      int endMapIndex)
      throws IOException {
    fetchMaxReqsInFlight = conf.fetchMaxReqsInFlight();
    results = new LinkedBlockingQueue<>();
    // only add the buffer to results queue if this reader is not closed.
    ChunkReceivedCallback callback =
        new ChunkReceivedCallback() {
          @Override
          public void onSuccess(int chunkIndex, ManagedBuffer buffer) {
            // only add the buffer to results queue if this reader is not closed.
            synchronized (this) {
              ByteBuf buf = ((NettyManagedBuffer) buffer).getBuf();
              if (!closed) {
                buf.retain();
                results.add(buf);
              }
            }
          }

          @Override
          public void onFailure(int chunkIndex, Throwable e) {
            String errorMsg = "Fetch chunk " + chunkIndex + " failed.";
            logger.error(errorMsg, e);
            exception.set(new IOException(errorMsg, e));
          }
        };
    client =
        new RetryingChunkClient(
            conf, shuffleKey, location, callback, clientFactory, startMapIndex, endMapIndex);
    numChunks = client.openChunks();
  }

  public boolean hasNext() {
    return returnedChunks < numChunks;
  }

  public ByteBuf next() throws IOException {
    checkException();
    if (chunkIndex < numChunks) {
      fetchChunks();
    }
    ByteBuf chunk = null;
    try {
      while (chunk == null) {
        checkException();
        chunk = results.poll(500, TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      IOException ioe = new IOException(e);
      exception.set(ioe);
      throw ioe;
    }
    returnedChunks++;
    return chunk;
  }

  public void close() {
    synchronized (this) {
      closed = true;
    }
    if (results.size() > 0) {
      results.forEach(ReferenceCounted::release);
    }
    results.clear();
  }

  private void fetchChunks() {
    final int inFlight = chunkIndex - returnedChunks;
    if (inFlight < fetchMaxReqsInFlight) {
      final int toFetch = Math.min(fetchMaxReqsInFlight - inFlight + 1, numChunks - chunkIndex);
      for (int i = 0; i < toFetch; i++) {
        client.fetchChunk(chunkIndex++);
      }
    }
  }

  private void checkException() throws IOException {
    IOException e = exception.get();
    if (e != null) {
      throw e;
    }
  }
}
