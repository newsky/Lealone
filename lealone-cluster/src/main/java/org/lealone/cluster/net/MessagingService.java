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
package org.lealone.cluster.net;

import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOError;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.lealone.cluster.concurrent.ScheduledExecutors;
import org.lealone.cluster.concurrent.Stage;
import org.lealone.cluster.concurrent.StageManager;
import org.lealone.cluster.config.DatabaseDescriptor;
import org.lealone.cluster.config.EncryptionOptions.ServerEncryptionOptions;
import org.lealone.cluster.db.PullSchema;
import org.lealone.cluster.db.PullSchemaAck;
import org.lealone.cluster.db.PullSchemaAckVerbHandler;
import org.lealone.cluster.db.PullSchemaVerbHandler;
import org.lealone.cluster.exceptions.ConfigurationException;
import org.lealone.cluster.gms.EchoMessage;
import org.lealone.cluster.gms.EchoVerbHandler;
import org.lealone.cluster.gms.GossipDigestAck;
import org.lealone.cluster.gms.GossipDigestAck2;
import org.lealone.cluster.gms.GossipDigestAck2VerbHandler;
import org.lealone.cluster.gms.GossipDigestAckVerbHandler;
import org.lealone.cluster.gms.GossipDigestSyn;
import org.lealone.cluster.gms.GossipDigestSynVerbHandler;
import org.lealone.cluster.gms.GossipShutdownVerbHandler;
import org.lealone.cluster.io.DataOutputPlus;
import org.lealone.cluster.io.IVersionedSerializer;
import org.lealone.cluster.locator.ILatencySubscriber;
import org.lealone.cluster.metrics.ConnectionMetrics;
import org.lealone.cluster.metrics.DroppedMessageMetrics;
import org.lealone.cluster.security.SSLFactory;
import org.lealone.cluster.utils.ExpiringMap;
import org.lealone.cluster.utils.FileUtils;
import org.lealone.cluster.utils.Pair;
import org.lealone.cluster.utils.Utils;
import org.lealone.cluster.utils.concurrent.SimpleCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@SuppressWarnings({ "rawtypes" })
public final class MessagingService implements MessagingServiceMBean {

    /* All verb handler identifiers */
    public static enum Verb {
        REQUEST_RESPONSE, // client-initiated reads and writes
        GOSSIP_DIGEST_SYN,
        GOSSIP_DIGEST_ACK,
        GOSSIP_DIGEST_ACK2,
        GOSSIP_SHUTDOWN,
        INTERNAL_RESPONSE, // responses to internal calls
        ECHO,
        PULL_SCHEMA,
        PULL_SCHEMA_ACK,
        // remember to add new verbs at the end, since we serialize by ordinal
        UNUSED_1,
        UNUSED_2,
        UNUSED_3;
    }

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);

    public static final int VERSION_10 = 1;
    public static final int CURRENT_VERSION = VERSION_10;

    public static final String FAILURE_CALLBACK_PARAM = "CAL_BAC";
    public static final String FAILURE_RESPONSE_PARAM = "FAIL";
    public static final byte[] ONE_BYTE = new byte[1];

    /**
     * we preface every message with this number so the recipient can validate the sender is sane
     */
    public static final int PROTOCOL_MAGIC = 0xCA552DFA;

    public static void validateMagic(int magic) throws IOException {
        if (magic != PROTOCOL_MAGIC)
            throw new IOException("invalid protocol header");
    }

    /**
     * Verbs it's okay to drop if the request has been queued longer than the request timeout.
     * These all correspond to client requests or something triggered by them; 
     * we don't want to drop internal messages like bootstrap.
     */
    public static final EnumSet<Verb> DROPPABLE_VERBS = EnumSet.of(Verb.REQUEST_RESPONSE);

    private static final int LOG_DROPPED_INTERVAL_IN_MS = 5000;

    public static final EnumMap<MessagingService.Verb, Stage> verbStages = new EnumMap<MessagingService.Verb, Stage>(
            MessagingService.Verb.class) {
        {
            put(Verb.REQUEST_RESPONSE, Stage.REQUEST_RESPONSE);
            put(Verb.INTERNAL_RESPONSE, Stage.INTERNAL_RESPONSE);

            put(Verb.GOSSIP_DIGEST_ACK, Stage.GOSSIP);
            put(Verb.GOSSIP_DIGEST_ACK2, Stage.GOSSIP);
            put(Verb.GOSSIP_DIGEST_SYN, Stage.GOSSIP);
            put(Verb.GOSSIP_SHUTDOWN, Stage.GOSSIP);
            put(Verb.ECHO, Stage.GOSSIP);

            put(Verb.PULL_SCHEMA, Stage.REQUEST_RESPONSE);
            put(Verb.PULL_SCHEMA_ACK, Stage.REQUEST_RESPONSE);

            put(Verb.UNUSED_1, Stage.INTERNAL_RESPONSE);
            put(Verb.UNUSED_2, Stage.INTERNAL_RESPONSE);
            put(Verb.UNUSED_3, Stage.INTERNAL_RESPONSE);
        }
    };

    /**
     * Messages we receive in IncomingTcpConnection have a Verb that tells us what kind of message it is.
     * Most of the time, this is enough to determine how to deserialize the message payload.
     * The exception is the REQUEST_RESPONSE verb, which just means "a reply to something you told me to do."
     * Traditionally, this was fine since each VerbHandler knew what type of payload it expected, and
     * handled the deserialization itself.  Now that we do that in ITC, to avoid the extra copy to an
     * intermediary byte[] (See lealone-3716), we need to wire that up to the CallbackInfo object
     * (see below).
     */
    public static final EnumMap<Verb, IVersionedSerializer<?>> verbSerializers = new EnumMap<Verb, IVersionedSerializer<?>>(
            Verb.class) {
        {
            put(Verb.REQUEST_RESPONSE, CallbackDeterminedSerializer.instance);
            put(Verb.INTERNAL_RESPONSE, CallbackDeterminedSerializer.instance);
            put(Verb.GOSSIP_DIGEST_ACK, GossipDigestAck.serializer);
            put(Verb.GOSSIP_DIGEST_ACK2, GossipDigestAck2.serializer);
            put(Verb.GOSSIP_DIGEST_SYN, GossipDigestSyn.serializer);
            put(Verb.ECHO, EchoMessage.serializer);
            put(Verb.PULL_SCHEMA, PullSchema.serializer);
            put(Verb.PULL_SCHEMA_ACK, PullSchemaAck.serializer);
        }
    };

    /**
     * A Map of what kind of serializer to wire up to a REQUEST_RESPONSE callback, based on outbound Verb.
     */
    public static final EnumMap<Verb, IVersionedSerializer<?>> callbackDeserializers = new EnumMap<>(Verb.class);

    private static final AtomicInteger idGen = new AtomicInteger(0);

    private static int nextId() {
        return idGen.incrementAndGet();
    }

    /**
     * a placeholder class that means "deserialize using the callback." We can't implement this without
     * special-case code in InboundTcpConnection because there is no way to pass the message id to IVersionedSerializer.
     */
    static class CallbackDeterminedSerializer implements IVersionedSerializer<Object> {
        public static final CallbackDeterminedSerializer instance = new CallbackDeterminedSerializer();

        @Override
        public Object deserialize(DataInput in, int version) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void serialize(Object o, DataOutputPlus out, int version) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long serializedSize(Object o, int version) {
            throw new UnsupportedOperationException();
        }
    }

    /* This records all the results mapped by message Id */
    private final ExpiringMap<Integer, CallbackInfo> callbacks;

    /* Lookup table for registering message handlers based on the verb. */
    private final Map<Verb, IVerbHandler> verbHandlers = new EnumMap<>(Verb.class);;

    private final ConcurrentMap<InetAddress, OutboundTcpConnection> connectionManagers = new NonBlockingHashMap<>();

    private final List<SocketThread> socketThreads = Lists.newArrayList();
    private final SimpleCondition listenGate = new SimpleCondition();

    // total dropped message counts for server lifetime
    private final Map<Verb, DroppedMessageMetrics> droppedMessages = new EnumMap<>(Verb.class);
    // dropped count when last requested for the Recent api. high concurrency isn't necessary here.
    private final Map<Verb, Integer> lastDroppedInternal = new EnumMap<>(Verb.class);

    private final List<ILatencySubscriber> subscribers = new ArrayList<>();

    // protocol versions of the other nodes in the cluster
    private final ConcurrentMap<InetAddress, Integer> versions = new NonBlockingHashMap<>();

    private static class MSHandle {
        public static final MessagingService instance = new MessagingService();
    }

    public static MessagingService instance() {
        return MSHandle.instance;
    }

    private void registerDefaultVerbHandlers() {
        registerVerbHandler(Verb.REQUEST_RESPONSE, new ResponseVerbHandler());
        registerVerbHandler(Verb.INTERNAL_RESPONSE, new ResponseVerbHandler());
        registerVerbHandler(Verb.GOSSIP_SHUTDOWN, new GossipShutdownVerbHandler());
        registerVerbHandler(Verb.GOSSIP_DIGEST_SYN, new GossipDigestSynVerbHandler());
        registerVerbHandler(Verb.GOSSIP_DIGEST_ACK, new GossipDigestAckVerbHandler());
        registerVerbHandler(Verb.GOSSIP_DIGEST_ACK2, new GossipDigestAck2VerbHandler());
        registerVerbHandler(Verb.ECHO, new EchoVerbHandler());
        registerVerbHandler(Verb.PULL_SCHEMA, new PullSchemaVerbHandler());
        registerVerbHandler(Verb.PULL_SCHEMA_ACK, new PullSchemaAckVerbHandler());
    }

    private MessagingService() {
        registerDefaultVerbHandlers();

        for (Verb verb : DROPPABLE_VERBS) {
            droppedMessages.put(verb, new DroppedMessageMetrics(verb));
            lastDroppedInternal.put(verb, 0);
        }

        Runnable logDropped = new Runnable() {
            @Override
            public void run() {
                logDroppedMessages();
            }
        };
        ScheduledExecutors.scheduledTasks.scheduleWithFixedDelay(logDropped, LOG_DROPPED_INTERVAL_IN_MS,
                LOG_DROPPED_INTERVAL_IN_MS, TimeUnit.MILLISECONDS);

        Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, ?> timeoutReporter = //
        new Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, Object>() {
            @Override
            public Object apply(Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>> pair) {
                final CallbackInfo expiredCallbackInfo = pair.right.value;
                maybeAddLatency(expiredCallbackInfo.callback, expiredCallbackInfo.target, pair.right.timeout);
                ConnectionMetrics.totalTimeouts.mark();
                getConnection(expiredCallbackInfo.target).incrementTimeout();
                if (expiredCallbackInfo.isFailureCallback()) {
                    StageManager.getStage(Stage.INTERNAL_RESPONSE).submit(new Runnable() {
                        @Override
                        public void run() {
                            ((IAsyncCallbackWithFailure) expiredCallbackInfo.callback)
                                    .onFailure(expiredCallbackInfo.target);
                        }
                    });
                }
                return null;
            }
        };

        callbacks = new ExpiringMap<Integer, CallbackInfo>(DatabaseDescriptor.getRpcTimeout(), timeoutReporter);

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.registerMBean(this, new ObjectName(Utils.getJmxObjectName("MessagingService")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void incrementDroppedMessages(Verb verb) {
        assert DROPPABLE_VERBS.contains(verb) : "Verb " + verb + " should not legally be dropped";
        droppedMessages.get(verb).dropped.mark();
    }

    private void logDroppedMessages() {
        for (Map.Entry<Verb, DroppedMessageMetrics> entry : droppedMessages.entrySet()) {
            int dropped = (int) entry.getValue().dropped.count();
            Verb verb = entry.getKey();
            int recent = dropped - lastDroppedInternal.get(verb);
            if (recent > 0) {
                logger.info("{} {} messages dropped in last {}ms", new Object[] { recent, verb,
                        LOG_DROPPED_INTERVAL_IN_MS });
                lastDroppedInternal.put(verb, dropped);
            }
        }
    }

    /**
     * Track latency information for the dynamic snitch
     *
     * @param cb      the callback associated with this message -- this lets us know if it's a message type we're interested in
     * @param address the host that replied to the message
     * @param latency
     */
    public void maybeAddLatency(IAsyncCallback cb, InetAddress address, long latency) {
        if (cb.isLatencyForSnitch())
            addLatency(address, latency);
    }

    public void addLatency(InetAddress address, long latency) {
        for (ILatencySubscriber subscriber : subscribers)
            subscriber.receiveTiming(address, latency);
    }

    public void waitUntilListening() {
        try {
            listenGate.await();
        } catch (InterruptedException ie) {
            if (logger.isDebugEnabled())
                logger.debug("await interrupted");
        }
    }

    public boolean isListening() {
        return listenGate.isSignaled();
    }

    /**
     * Listen on the specified port.
     *
     * @param localEp InetAddress whose port to listen on.
     */
    public void listen(InetAddress localEp) throws ConfigurationException {
        callbacks.reset(); // hack to allow tests to stop/restart MS
        for (ServerSocket ss : getServerSockets(localEp)) {
            SocketThread th = new SocketThread(ss, "ACCEPT-" + localEp);
            th.start();
            socketThreads.add(th);
        }
        listenGate.signalAll();
    }

    private List<ServerSocket> getServerSockets(InetAddress localEp) throws ConfigurationException {
        final List<ServerSocket> ss = new ArrayList<>(2);
        ServerEncryptionOptions options = DatabaseDescriptor.getServerEncryptionOptions();
        if (options.internode_encryption != ServerEncryptionOptions.InternodeEncryption.none) {
            try {
                ss.add(SSLFactory.getServerSocket(options, localEp, DatabaseDescriptor.getSSLStoragePort()));
            } catch (IOException e) {
                throw new ConfigurationException("Unable to create ssl socket", e);
            }
            // setReuseAddress happens in the factory.
            logger.info("Starting Encrypted Messaging Service on SSL port {}", DatabaseDescriptor.getSSLStoragePort());
        }

        if (options.internode_encryption != ServerEncryptionOptions.InternodeEncryption.all) {
            ServerSocketChannel serverChannel = null;
            try {
                serverChannel = ServerSocketChannel.open();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            ServerSocket socket = serverChannel.socket();
            try {
                socket.setReuseAddress(true);
            } catch (SocketException e) {
                throw new ConfigurationException("Insufficient permissions to setReuseAddress", e);
            }
            InetSocketAddress address = new InetSocketAddress(localEp, DatabaseDescriptor.getStoragePort());
            try {
                socket.bind(address, 500);
            } catch (BindException e) {
                if (e.getMessage().contains("in use"))
                    throw new ConfigurationException(address + " is in use by another process.  "
                            + "Change listen_address:storage_port in lealone.yaml "
                            + "to values that do not conflict with other services");
                else if (e.getMessage().contains("Cannot assign requested address"))
                    throw new ConfigurationException("Unable to bind to address " + address
                            + ". Set listen_address in lealone.yaml to an interface you can bind to, e.g.,"
                            + " your private IP address on EC2");
                else
                    throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            logger.info("Starting Messaging Service on port {}", DatabaseDescriptor.getStoragePort());
            ss.add(socket);
        }
        return ss;
    }

    private static class SocketThread extends Thread {
        private final ServerSocket server;
        private final Set<Closeable> connections = Sets.newConcurrentHashSet();

        SocketThread(ServerSocket server, String name) {
            super(name);
            this.server = server;
        }

        @Override
        public void run() {
            while (!server.isClosed()) {
                Socket socket = null;
                try {
                    socket = server.accept();
                    if (!authenticate(socket)) {
                        logger.trace("remote failed to authenticate");
                        socket.close();
                        continue;
                    }
                    // new IncomingTcpConnection(socket).start();

                    socket.setKeepAlive(true);
                    // socket.setSoTimeout(2 * OutboundTcpConnection.WAIT_FOR_VERSION_MAX_TIME);
                    socket.setSoTimeout(2 * 1000000); // 我加上的，不让超时，方便调试
                    // determine the connection type to decide whether to buffer
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    int magic = in.readInt();
                    Thread thread;
                    if (magic != PROTOCOL_MAGIC) {
                        thread = new IncomingAdminConnection(socket, connections);
                    } else {
                        // MessagingService.validateMagic(in.readInt());
                        // 写入的格式见:OutboundTcpConnection.connect()
                        int header = in.readInt();
                        boolean isStream = MessagingService.getBits(header, 3, 1) == 1;
                        int version = MessagingService.getBits(header, 15, 8);
                        logger.trace("Connection version {} from {}", version, socket.getInetAddress());
                        socket.setSoTimeout(0);

                        thread = isStream ? new IncomingStreamingConnection(version, socket, connections)
                                : new IncomingTcpConnection(version, MessagingService.getBits(header, 2, 1) == 1,
                                        socket, connections);
                    }
                    thread.start();
                    connections.add((Closeable) thread);

                } catch (AsynchronousCloseException e) {
                    if (logger.isDebugEnabled())
                        // this happens when another thread calls close().
                        logger.debug("Asynchronous close seen by server thread");
                    break;
                } catch (ClosedChannelException e) {
                    if (logger.isDebugEnabled())
                        logger.debug("MessagingService server thread already closed");
                    break;
                } catch (IOException e) {
                    if (logger.isDebugEnabled())
                        logger.debug("Error reading the socket " + socket, e);
                    FileUtils.closeQuietly(socket);
                }
            }
            logger.info("MessagingService has terminated the accept() thread");
        }

        void close() throws IOException {
            logger.trace("Closing accept() thread");

            try {
                server.close();
            } catch (IOException e) {
                // dirty hack for clean shutdown on OSX w/ Java >= 1.8.0_20
                // see https://issues.apache.org/jira/browse/CASSANDRA-8220
                // see https://bugs.openjdk.java.net/browse/JDK-8050499
                if (!"Unknown error: 316".equals(e.getMessage()) || !"Mac OS X".equals(System.getProperty("os.name")))
                    throw e;
            }

            for (Closeable connection : connections) {
                connection.close();
            }
        }

        private boolean authenticate(Socket socket) {
            return DatabaseDescriptor.getInternodeAuthenticator().authenticate(socket.getInetAddress(),
                    socket.getPort());
        }
    }

    public static int getBits(int packed, int start, int count) {
        return packed >>> (start + 1) - count & ~(-1 << count);
    }

    /**
     * Register a verb and the corresponding verb handler with the
     * Messaging Service.
     *
     * @param verb
     * @param verbHandler handler for the specified verb
     */
    public void registerVerbHandler(Verb verb, IVerbHandler verbHandler) {
        assert !verbHandlers.containsKey(verb);
        verbHandlers.put(verb, verbHandler);
    }

    /**
     * This method returns the verb handler associated with the registered
     * verb. If no handler has been registered then null is returned.
     *
     * @param type for which the verb handler is sought
     * @return a reference to IVerbHandler which is the handler for the specified verb
     */
    public IVerbHandler getVerbHandler(Verb type) {
        return verbHandlers.get(type);
    }

    public int sendRR(MessageOut message, InetAddress to, IAsyncCallback cb) {
        return sendRR(message, to, cb, message.getTimeout(), false);
    }

    public int sendRRWithFailure(MessageOut message, InetAddress to, IAsyncCallbackWithFailure cb) {
        return sendRR(message, to, cb, message.getTimeout(), true);
    }

    /**
     * Send a non-mutation message to a given endpoint. This method specifies a callback
     * which is invoked with the actual response.
     *
     * @param message message to be sent.
     * @param to      endpoint to which the message needs to be sent
     * @param cb      callback interface which is used to pass the responses or
     *                suggest that a timeout occurred to the invoker of the send().
     * @param timeout the timeout used for expiration
     * @return an reference to message id used to match with the result
     */
    public int sendRR(MessageOut message, InetAddress to, IAsyncCallback cb, long timeout, boolean failureCallback) {
        int id = addCallback(message, to, cb, timeout, failureCallback);
        sendOneWay(failureCallback ? message.withParameter(FAILURE_CALLBACK_PARAM, ONE_BYTE) : message, id, to);
        return id;
    }

    private int addCallback(MessageOut message, InetAddress to, IAsyncCallback cb, long timeout, boolean failureCallback) {
        int messageId = nextId();
        CallbackInfo previous = callbacks.put(messageId,
                new CallbackInfo(to, cb, callbackDeserializers.get(message.verb), failureCallback), timeout);
        assert previous == null : String.format("Callback already exists for id %d! (%s)", messageId, previous);
        return messageId;
    }

    public void sendOneWay(MessageOut message, InetAddress to) {
        sendOneWay(message, nextId(), to);
    }

    public void sendReply(MessageOut message, int id, InetAddress to) {
        sendOneWay(message, id, to);
    }

    /**
     * Send a message to a given endpoint. This method adheres to the fire and forget
     * style messaging.
     *
     * @param message messages to be sent.
     * @param to      endpoint to which the message needs to be sent
     */
    public void sendOneWay(MessageOut message, int id, InetAddress to) {
        if (logger.isTraceEnabled()) {
            if (to.equals(Utils.getBroadcastAddress()))
                logger.trace("Message-to-self {} going over MessagingService", message);
            else
                logger.trace("{} sending {} to {}@{}", Utils.getBroadcastAddress(), message.verb, id, to);
        }

        // get pooled connection (really, connection queue)
        OutboundTcpConnection connection = getConnection(to);

        // write it
        connection.enqueue(message, id);
    }

    public OutboundTcpConnection getConnection(InetAddress to) {
        OutboundTcpConnection conn = connectionManagers.get(to);
        if (conn == null) {
            conn = new OutboundTcpConnection(to);
            OutboundTcpConnection existingConn = connectionManagers.putIfAbsent(to, conn);
            if (existingConn != null)
                conn = existingConn;
            else
                conn.start();
        }
        return conn;
    }

    public void destroyConnection(InetAddress to) {
        OutboundTcpConnection conn = connectionManagers.get(to);
        if (conn == null)
            return;
        conn.close();
        connectionManagers.remove(to);
    }

    public InetAddress getConnectionEndpoint(InetAddress to) {
        return getConnection(to).endpoint();
    }

    public void reconnect(InetAddress old, InetAddress to) {
        getConnection(old).reset(to);
    }

    /**
     * called from gossiper when it notices a node is not responding.
     */
    public void convict(InetAddress ep) {
        if (logger.isDebugEnabled())
            logger.debug("Resetting pool for {}", ep);
        getConnection(ep).reset();
    }

    public void register(ILatencySubscriber subcriber) {
        subscribers.add(subcriber);
    }

    /**
     * Wait for callbacks and don't allow any more to be created (since they could require writing hints)
     */
    public void shutdown() {
        logger.info("Waiting for messaging service to quiesce");

        // the important part
        callbacks.shutdownBlocking();

        // attempt to humor tests that try to stop and restart MS
        try {
            for (SocketThread th : socketThreads)
                th.close();
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public CallbackInfo getRegisteredCallback(int messageId) {
        return callbacks.get(messageId);
    }

    public CallbackInfo removeRegisteredCallback(int messageId) {
        return callbacks.remove(messageId);
    }

    /**
     * @return System.nanoTime() when callback was created.
     */
    public long getRegisteredCallbackAge(int messageId) {
        return callbacks.getAge(messageId);
    }

    public void setVersion(InetAddress endpoint, int version) {
        if (logger.isDebugEnabled())
            logger.debug("Setting version {} for {}", version, endpoint);

        versions.put(endpoint, version);
    }

    public void removeVersion(InetAddress endpoint) {
        if (logger.isDebugEnabled())
            logger.debug("Removing version for {}", endpoint);
        versions.remove(endpoint);
    }

    public int getVersion(InetAddress endpoint) {
        Integer v = versions.get(endpoint);
        if (v == null) {
            // we don't know the version. assume current. we'll know soon enough if that was incorrect.
            if (logger.isTraceEnabled())
                logger.trace("Assuming current protocol version for {}", endpoint);
            return MessagingService.CURRENT_VERSION;
        } else
            return Math.min(v, MessagingService.CURRENT_VERSION);
    }

    public boolean knowsVersion(InetAddress endpoint) {
        return versions.containsKey(endpoint);
    }

    // --------------以下是MessagingServiceMBean的API实现-------------

    @Override
    public int getVersion(String endpoint) throws UnknownHostException {
        return getVersion(InetAddress.getByName(endpoint));
    }

    @Override
    public Map<String, Integer> getResponsePendingTasks() {
        Map<String, Integer> pendingTasks = new HashMap<>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnection> entry : connectionManagers.entrySet())
            pendingTasks.put(entry.getKey().getHostAddress(), entry.getValue().getPendingMessages());
        return pendingTasks;
    }

    @Override
    public Map<String, Long> getResponseCompletedTasks() {
        Map<String, Long> completedTasks = new HashMap<>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnection> entry : connectionManagers.entrySet())
            completedTasks.put(entry.getKey().getHostAddress(), entry.getValue().getCompletedMesssages());
        return completedTasks;
    }

    @Override
    public Map<String, Integer> getDroppedMessages() {
        Map<String, Integer> map = new HashMap<>(droppedMessages.size());
        for (Map.Entry<Verb, DroppedMessageMetrics> entry : droppedMessages.entrySet())
            map.put(entry.getKey().toString(), (int) entry.getValue().dropped.count());
        return map;
    }

    @Override
    public long getTotalTimeouts() {
        return ConnectionMetrics.totalTimeouts.count();
    }

    @Override
    public Map<String, Long> getTimeoutsPerHost() {
        Map<String, Long> result = new HashMap<>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnection> entry : connectionManagers.entrySet()) {
            String ip = entry.getKey().getHostAddress();
            long recent = entry.getValue().getTimeouts();
            result.put(ip, recent);
        }
        return result;
    }
}
