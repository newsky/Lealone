/*
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
package org.lealone.transaction.log;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.lealone.storage.type.WriteBuffer;

public class WriteBufferPool {
    // 不要求精确
    private static int poolSize;
    private static final int capacity = 4 * 1024 * 1024;
    private static final ConcurrentLinkedQueue<WriteBuffer> writeBufferPool = new ConcurrentLinkedQueue<>();

    public static WriteBuffer poll() {
        WriteBuffer writeBuffer = writeBufferPool.poll();
        if (writeBuffer == null)
            writeBuffer = new WriteBuffer();
        else {
            writeBuffer.clear();
            poolSize--;
        }

        return writeBuffer;
    }

    public static void offer(WriteBuffer writeBuffer) {
        if (poolSize < 5 && writeBuffer.capacity() <= capacity) {
            poolSize++;
            writeBufferPool.offer(writeBuffer);
        }
    }
}
