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
package org.lealone.cluster.gms;

import java.io.DataInput;
import java.io.IOException;
import java.util.Map;

import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.lealone.cluster.db.TypeSizes;
import org.lealone.cluster.io.DataOutputPlus;
import org.lealone.cluster.io.IVersionedSerializer;

/**
 * This abstraction represents both the HeartBeatState and the ApplicationState in an EndpointState
 * instance. Any state for a given endpoint can be retrieved from this instance.
 */
public class EndpointState {
    private static final ApplicationState[] STATES = ApplicationState.values();

    public final static IVersionedSerializer<EndpointState> serializer = new EndpointStateSerializer();

    private volatile HeartBeatState hbState;
    final Map<ApplicationState, VersionedValue> applicationState = new NonBlockingHashMap<>();

    /* fields below do not get serialized */
    private volatile long updateTimestamp;
    private volatile boolean isAlive;

    EndpointState(HeartBeatState initialHbState) {
        hbState = initialHbState;
        updateTimestamp = System.nanoTime();
        isAlive = true;
    }

    HeartBeatState getHeartBeatState() {
        return hbState;
    }

    void setHeartBeatState(HeartBeatState newHbState) {
        updateTimestamp();
        hbState = newHbState;
    }

    public VersionedValue getApplicationState(ApplicationState key) {
        return applicationState.get(key);
    }

    /**
     * TODO replace this with operations that don't expose private state
     */
    //@Deprecated
    public Map<ApplicationState, VersionedValue> getApplicationStateMap() {
        return applicationState;
    }

    void addApplicationState(ApplicationState key, VersionedValue value) {
        applicationState.put(key, value);
    }

    /* getters and setters */
    /**
     * @return System.nanoTime() when state was updated last time.
     */
    public long getUpdateTimestamp() {
        return updateTimestamp;
    }

    void updateTimestamp() {
        updateTimestamp = System.nanoTime();
    }

    public boolean isAlive() {
        return isAlive;
    }

    void markAlive() {
        isAlive = true;
    }

    void markDead() {
        isAlive = false;
    }

    @Override
    public String toString() {
        return "EndpointState: HeartBeatState = " + hbState + ", AppStateMap = " + applicationState;
    }

    private static class EndpointStateSerializer implements IVersionedSerializer<EndpointState> {
        @Override
        public void serialize(EndpointState epState, DataOutputPlus out, int version) throws IOException {
            /* serialize the HeartBeatState */
            HeartBeatState hbState = epState.getHeartBeatState();
            HeartBeatState.serializer.serialize(hbState, out, version);

            /* serialize the map of ApplicationState objects */
            int size = epState.applicationState.size();
            out.writeInt(size);
            for (Map.Entry<ApplicationState, VersionedValue> entry : epState.applicationState.entrySet()) {
                VersionedValue value = entry.getValue();
                out.writeInt(entry.getKey().ordinal());
                VersionedValue.serializer.serialize(value, out, version);
            }
        }

        @Override
        public EndpointState deserialize(DataInput in, int version) throws IOException {
            HeartBeatState hbState = HeartBeatState.serializer.deserialize(in, version);
            EndpointState epState = new EndpointState(hbState);

            int appStateSize = in.readInt();
            for (int i = 0; i < appStateSize; ++i) {
                int key = in.readInt();
                VersionedValue value = VersionedValue.serializer.deserialize(in, version);
                epState.addApplicationState(EndpointState.STATES[key], value);
            }
            return epState;
        }

        @Override
        public long serializedSize(EndpointState epState, int version) {
            long size = HeartBeatState.serializer.serializedSize(epState.getHeartBeatState(), version);
            size += TypeSizes.NATIVE.sizeof(epState.applicationState.size());
            for (Map.Entry<ApplicationState, VersionedValue> entry : epState.applicationState.entrySet()) {
                VersionedValue value = entry.getValue();
                size += TypeSizes.NATIVE.sizeof(entry.getKey().ordinal());
                size += VersionedValue.serializer.serializedSize(value, version);
            }
            return size;
        }
    }
}
