/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.lealone.api.ErrorCode;
import org.lealone.common.message.DbException;
import org.lealone.common.util.JdbcUtils;
import org.lealone.common.util.NetUtils;
import org.lealone.common.util.New;
import org.lealone.db.Constants;

/**
 * The TCP server implements the native database server protocol.
 * It supports multiple client connections to multiple databases(many to many). 
 * The same database may be opened by multiple clients.
 * Also supported is the mixed mode: opening databases in embedded mode,
 * and at the same time start a TCP server to allow clients to connect to
 * the same database over the network.
 * 
 * @author H2 Group
 * @author zhh
 */
public class TcpServer implements ProtocolServer {

    private static final int SHUTDOWN_NORMAL = 0;
    private static final int SHUTDOWN_FORCE = 1;

    /**
     * The name of the in-memory management database used by the TCP server
     * to keep the active sessions.
     */
    private static final String MANAGEMENT_DB_PREFIX = "management_db_";

    private static final Map<Integer, TcpServer> SERVERS = Collections
            .synchronizedMap(new HashMap<Integer, TcpServer>());

    private ServerSocket serverSocket;
    private final Set<TcpServerThread> running = Collections.synchronizedSet(new HashSet<TcpServerThread>());

    private String listenAddress = Constants.DEFAULT_HOST;
    private int port = Constants.DEFAULT_TCP_PORT;

    private String baseDir;
    private String key;
    private String keyDatabase;

    private boolean trace;
    private boolean ssl;
    private boolean allowOthers;
    private boolean isDaemon;
    private boolean ifExists;

    private boolean stop;

    private Connection managementDb;
    private PreparedStatement managementDbAdd;
    private PreparedStatement managementDbRemove;
    private String managementPassword = "";
    private Thread listenerThread;
    private int nextThreadId;

    /**
     * Check if the argument matches the option.
     * If the argument starts with this option, but doesn't match,
     * then an exception is thrown.
     *
     * @param arg the argument
     * @param option the command line option
     * @return true if it matches
     */
    public static boolean isOption(String arg, String option) {
        if (arg.equals(option)) {
            return true;
        } else if (arg.startsWith(option)) {
            throw DbException.getUnsupportedException("expected: " + option + " got: " + arg);
        }
        return false;
    }

    private static String getManagementDbEmbeddedURL(int port) {
        StringBuilder buff = new StringBuilder();
        buff.append(Constants.URL_PREFIX).append(Constants.URL_MEM);
        buff.append(Constants.URL_EMBED);
        buff.append(MANAGEMENT_DB_PREFIX).append(port).append(";DISABLE_AUTHENTICATION=true");
        return buff.toString();
    }

    private static String getManagementDbTcpURL(String hostname, int port) {
        StringBuilder buff = new StringBuilder();
        buff.append(Constants.URL_PREFIX).append(Constants.URL_MEM);
        buff.append(Constants.URL_TCP).append("//").append(hostname).append(":").append(port).append('/');
        buff.append(MANAGEMENT_DB_PREFIX).append(port);
        return buff.toString();
    }

    private void initManagementDb() {
        Statement stat = null;
        try {
            // avoid using the driver manager
            Connection conn = DriverManager.getConnection(getManagementDbEmbeddedURL(port), "", managementPassword);
            stat = conn.createStatement();
            stat.execute("CREATE ALIAS IF NOT EXISTS STOP_SERVER FOR \"" + TcpServer.class.getName() + ".stopServer\"");
            stat.execute("CREATE TABLE IF NOT EXISTS SESSIONS"
                    + "(ID INT PRIMARY KEY, URL VARCHAR, USER VARCHAR, CONNECTED TIMESTAMP)");

            managementDbAdd = conn.prepareStatement("INSERT INTO SESSIONS VALUES(?, ?, ?, NOW())");
            managementDbRemove = conn.prepareStatement("DELETE FROM SESSIONS WHERE ID=?");
            managementDb = conn;
            SERVERS.put(port, this);
        } catch (SQLException e) {
            throw DbException.convert(e);
        } finally {
            JdbcUtils.closeSilently(stat);
        }

        SERVERS.put(port, this);
    }

    private synchronized void stopManagementDb() {
        if (managementDb != null) {
            try {
                managementDb.close();
            } catch (SQLException e) {
                DbException.traceThrowable(e);
            }
            managementDb = null;
        }
    }

    /**
     * Add a connection to the management database.
     *
     * @param id the connection id
     * @param url the database URL
     * @param user the user name
     */
    synchronized void addConnection(int id, String url, String user) {
        try {
            managementDbAdd.setInt(1, id);
            managementDbAdd.setString(2, url);
            managementDbAdd.setString(3, user);
            managementDbAdd.execute();
        } catch (SQLException e) {
            DbException.traceThrowable(e);
        }
    }

    /**
     * Remove a connection from the management database.
     *
     * @param id the connection id
     */
    synchronized void removeConnection(int id) {
        try {
            managementDbRemove.setInt(1, id);
            managementDbRemove.execute();
        } catch (SQLException e) {
            DbException.traceThrowable(e);
        }
    }

    @Override
    public void init(Map<String, String> config) { // TODO 对于不支持的参数直接报错
        if (config.containsKey("listen_address"))
            listenAddress = config.get("listen_address");
        if (config.containsKey("listen_port"))
            port = Integer.parseInt(config.get("listen_port"));

        baseDir = config.get("base_dir");
        if (config.containsKey("key")) {
            String[] keyAndDatabase = config.get("key").split(",");
            if (keyAndDatabase.length != 2)
                throw new IllegalArgumentException("Invalid key parameter, usage: 'key: v1,v2'.");
            key = keyAndDatabase[0];
            keyDatabase = keyAndDatabase[1];
        }

        trace = Boolean.parseBoolean(config.get("trace"));
        ssl = Boolean.parseBoolean(config.get("ssl"));
        allowOthers = Boolean.parseBoolean(config.get("allow_others"));
        isDaemon = Boolean.parseBoolean(config.get("daemon"));
        ifExists = Boolean.parseBoolean(config.get("if_exists"));

        if (config.containsKey("password"))
            managementPassword = config.get("password");

        initManagementDb();
    }

    @Override
    public synchronized void start() {
        serverSocket = NetUtils.createServerSocket(listenAddress, port, ssl);

        String name = getName() + " (" + getURL() + ")";
        Thread t = new Thread(this, name);
        t.setDaemon(isDaemon());
        t.start();
    }

    @Override
    public void run() {
        try {
            listen();
        } catch (Exception e) {
            DbException.traceThrowable(e);
        }
    }

    private void listen() {
        listenerThread = Thread.currentThread();
        String threadName = listenerThread.getName();
        try {
            while (!stop) {
                Socket s = serverSocket.accept();
                TcpServerThread c = new TcpServerThread(s, this, nextThreadId++);
                running.add(c);
                Thread thread = new Thread(c, threadName + " thread");
                thread.setDaemon(isDaemon);
                c.setThread(thread);
                thread.start();
            }
            serverSocket = NetUtils.closeSilently(serverSocket);
        } catch (Exception e) {
            if (!stop) {
                DbException.traceThrowable(e);
            }
        }
        stopManagementDb();
    }

    @Override
    public void stop() {
        // TODO server: share code between web and tcp servers
        // need to remove the server first, otherwise the connection is broken
        // while the server is still registered in this map
        SERVERS.remove(port);
        if (!stop) {
            stopManagementDb();
            stop = true;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    DbException.traceThrowable(e);
                } catch (NullPointerException e) {
                    // ignore
                }
                serverSocket = null;
            }
            if (listenerThread != null) {
                try {
                    listenerThread.join(1000);
                } catch (InterruptedException e) {
                    DbException.traceThrowable(e);
                }
            }
        }
        // TODO server: using a boolean 'now' argument? a timeout?
        for (TcpServerThread c : New.arrayList(running)) {
            if (c != null) {
                c.close();
                try {
                    c.getThread().join(100);
                } catch (Exception e) {
                    DbException.traceThrowable(e);
                }
            }
        }
    }

    @Override
    public synchronized boolean isRunning(boolean traceError) {
        if (serverSocket == null) {
            return false;
        }
        try {
            Socket s = NetUtils.createLoopbackSocket(port, ssl);
            s.close();
            return true;
        } catch (Exception e) {
            if (traceError) {
                traceError(e);
            }
            return false;
        }
    }

    @Override
    public String getURL() {
        return (ssl ? "ssl" : "tcp") + "://" + getListenAddress() + ":" + port;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getListenAddress() {
        return listenAddress;
    }

    @Override
    public String getName() {
        return "TCP Server";
    }

    @Override
    public String getType() {
        return "TCP";
    }

    @Override
    public boolean getAllowOthers() {
        return allowOthers;
    }

    @Override
    public boolean isDaemon() {
        return isDaemon;
    }

    /**
     * Check if this socket may connect to this server. Remote connections are
     * not allowed if the flag allowOthers is set.
     *
     * @param socket the socket
     * @return true if this client may connect
     */
    boolean allow(Socket socket) {
        if (allowOthers) {
            return true;
        }
        try {
            return NetUtils.isLocalAddress(socket);
        } catch (UnknownHostException e) {
            traceError(e);
            return false;
        }
    }

    /**
     * Remove a thread from the list.
     *
     * @param t the thread to remove
     */
    void remove(TcpServerThread t) {
        running.remove(t);
    }

    /**
     * Get the configured base directory.
     *
     * @return the base directory
     */
    String getBaseDir() {
        return baseDir;
    }

    /**
     * Print a message if the trace flag is enabled.
     *
     * @param s the message
     */
    void trace(String s) {
        if (trace) {
            System.out.println(s);
        }
    }

    boolean isTraceEnabled() {
        return trace;
    }

    /**
     * Print a stack trace if the trace flag is enabled.
     *
     * @param e the exception
     */
    void traceError(Throwable e) {
        if (trace) {
            e.printStackTrace();
        }
    }

    public boolean getIfExists() {
        return ifExists;
    }

    /**
     * Cancel a running statement.
     *
     * @param sessionId the session id
     * @param statementId the statement id
     */
    void cancelStatement(String sessionId, int statementId) {
        for (TcpServerThread c : New.arrayList(running)) {
            if (c != null) {
                c.cancelStatement(sessionId, statementId);
            }
        }
    }

    /**
     * If no key is set, return the original database name. If a key is set,
     * check if the key matches. If yes, return the correct database name. If
     * not, throw an exception.
     *
     * @param db the key to test (or database name if no key is used)
     * @return the database name
     * @throws DbException if a key is set but doesn't match
     */
    String checkKeyAndGetDatabaseName(String db) {
        if (key == null) {
            return db;
        }
        if (key.equals(db)) {
            return keyDatabase;
        }
        throw DbException.get(ErrorCode.WRONG_USER_OR_PASSWORD);
    }

    /**
     * Stop the TCP server with the given URL.
     *
     * @param hostname the database hostname
     * @param port the database port
     * @param password the password
     * @param force if the server should be stopped immediately
     * @param all whether all TCP servers that are running in the JVM should be
     *            stopped
     */
    public static synchronized void shutdown(String hostname, int port, String password, boolean force, boolean all)
            throws SQLException {
        try {
            for (int i = 0; i < 2; i++) {
                Connection conn = null;
                PreparedStatement prep = null;
                try {
                    conn = DriverManager.getConnection(getManagementDbTcpURL(hostname, port), "", password);
                    prep = conn.prepareStatement("CALL STOP_SERVER(?, ?, ?)");
                    prep.setInt(1, all ? 0 : port);
                    prep.setString(2, password);
                    prep.setInt(3, force ? SHUTDOWN_FORCE : SHUTDOWN_NORMAL);
                    try {
                        prep.execute();
                    } catch (SQLException e) {
                        if (force) {
                            // ignore
                        } else {
                            if (e.getErrorCode() != ErrorCode.CONNECTION_BROKEN_1) {
                                throw e;
                            }
                        }
                    }
                    break;
                } catch (SQLException e) {
                    if (i == 1) {
                        throw e;
                    }
                } finally {
                    JdbcUtils.closeSilently(prep);
                    JdbcUtils.closeSilently(conn);
                }
            }
        } catch (Exception e) {
            throw DbException.toSQLException(e);
        }
    }

    /**
     * Stop a running server. This method is called via reflection from the
     * STOP_SERVER function.
     *
     * @param port the port where the server runs, or 0 for all running servers
     * @param password the password (or null)
     * @param shutdownMode the shutdown mode, SHUTDOWN_NORMAL or SHUTDOWN_FORCE.
     */
    public static void stopServer(int port, String password, int shutdownMode) {
        if (port == 0) {
            for (int p : SERVERS.keySet().toArray(new Integer[0])) {
                if (p != 0) {
                    stopServer(p, password, shutdownMode);
                }
            }
            return;
        }
        TcpServer server = SERVERS.get(port);
        if (server == null) {
            return;
        }
        if (!server.managementPassword.equals(password)) {
            return;
        }
        if (shutdownMode == SHUTDOWN_NORMAL) {
            server.stopManagementDb();
            server.stop = true;
            try {
                Socket s = NetUtils.createLoopbackSocket(port, false);
                s.close();
            } catch (Exception e) {
                // try to connect - so that accept returns
            }
        } else if (shutdownMode == SHUTDOWN_FORCE) {
            server.stop();
        }
    }

}
