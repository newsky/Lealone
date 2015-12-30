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

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract class LogSyncService extends Thread {

    protected final Semaphore haveWork = new Semaphore(1);
    protected final WaitQueue syncComplete = new WaitQueue();

    protected long syncIntervalMillis;
    protected volatile long lastSyncedAt = System.currentTimeMillis();
    protected boolean running = true;

    public LogSyncService(String name) {
        super(name);
        setDaemon(true);
    }

    public abstract void maybeWaitForSync(LogMap<Long, RedoLogValue> redoLog, Long lastOperationId);

    void close() {
        running = false;
        haveWork.release(1);
    }

    @Override
    public void run() {
        while (running) {
            long syncStarted = System.currentTimeMillis();
            sync();
            lastSyncedAt = syncStarted;
            syncComplete.signalAll();
            long now = System.currentTimeMillis();
            long sleep = syncStarted + syncIntervalMillis - now;
            if (sleep < 0)
                continue;

            try {
                haveWork.tryAcquire(sleep, TimeUnit.MILLISECONDS);
                haveWork.drainPermits();
            } catch (InterruptedException e) {
                throw new AssertionError();
            }
        }
    }

    private void sync() {
        if (LogStorage.redoLog != null)
            LogStorage.redoLog.save();
        // TODO 是否要保存其他map?
        // for (LogMap<?, ?> map : LogStorage.logMaps) {
        // map.save();
        // }
    }
}
