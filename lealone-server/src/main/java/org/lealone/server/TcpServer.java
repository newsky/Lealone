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
import org.lealone.engine.Constants;
import org.lealone.jdbc.Driver;
import org.lealone.message.DbException;
import org.lealone.util.JdbcUtils;
import org.lealone.util.NetUtils;
import org.lealone.util.New;

/**
 * The TCP server implements the native H2 database server protocol.
 * It supports multiple client connections to multiple databases
 * (many to many). The same database may be opened by multiple clients.
 * Also supported is the mixed mode: opening databases in embedded mode,
 * and at the same time start a TCP server to allow clients to connect to
 * the same database over the network.
 */
public class TcpServer implements Server {

    private static final int SHUTDOWN_NORMAL = 0;
    private static final int SHUTDOWN_FORCE = 1;

    /**
     * The name of the in-memory management database used by the TCP server
     * to keep the active sessions.
     */
    private static final String MANAGEMENT_DB_PREFIX = "management_db_";

    private static final Map<Integer, TcpServer> SERVERS = Collections
            .synchronizedMap(new HashMap<Integer, TcpServer>());

    private String listenAddress;
    private int port;
    private boolean portIsSet;
    private boolean trace;
    private boolean ssl;
    private boolean stop;
    private ServerSocket serverSocket;
    private final Set<TcpServerThread> running = Collections.synchronizedSet(new HashSet<TcpServerThread>());
    private String baseDir;
    private boolean allowOthers;
    private boolean isDaemon;
    private boolean ifExists;
    private Connection managementDb;
    private PreparedStatement managementDbAdd;
    private PreparedStatement managementDbRemove;
    private String managementPassword = "";
    private Thread listenerThread;
    private int nextThreadId;
    private String key;
    private String keyDatabase;

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
        buff.append(MANAGEMENT_DB_PREFIX).append(port);
        return buff.toString();
    }

    private static String getManagementDbTcpURL(String hostname, int port) {
        StringBuilder buff = new StringBuilder();
        buff.append(Constants.URL_PREFIX).append(Constants.URL_MEM);
        buff.append(Constants.URL_TCP).append("//").append(hostname).append(":").append(port).append('/');
        buff.append(MANAGEMENT_DB_PREFIX).append(port);
        return buff.toString();
    }

    protected void initManagementDb() throws SQLException {
        // avoid using the driver manager
        Connection conn = Driver.getConnection(getManagementDbEmbeddedURL(port), "", managementPassword);
        managementDb = conn;
        Statement stat = null;
        try {
            stat = conn.createStatement();
            stat.execute("CREATE ALIAS IF NOT EXISTS STOP_SERVER FOR \"" + TcpServer.class.getName() + ".stopServer\"");
            stat.execute("CREATE TABLE IF NOT EXISTS SESSIONS"
                    + "(ID INT PRIMARY KEY, URL VARCHAR, USER VARCHAR, CONNECTED TIMESTAMP)");
            managementDbAdd = conn.prepareStatement("INSERT INTO SESSIONS VALUES(?, ?, ?, NOW())");
            managementDbRemove = conn.prepareStatement("DELETE FROM SESSIONS WHERE ID=?");
        } finally {
            JdbcUtils.closeSilently(stat);
        }
        SERVERS.put(port, this);
    }

    /**
     * Add a connection to the management database.
     *
     * @param id the connection id
     * @param url the database URL
     * @param user the user name
     */
    protected synchronized void addConnection(int id, String url, String user) {
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
    protected synchronized void removeConnection(int id) {
        try {
            managementDbRemove.setInt(1, id);
            managementDbRemove.execute();
        } catch (SQLException e) {
            DbException.traceThrowable(e);
        }
    }

    protected synchronized void stopManagementDb() {
        if (managementDb != null) {
            try {
                managementDb.close();
            } catch (SQLException e) {
                DbException.traceThrowable(e);
            }
            managementDb = null;
        }
    }

    @Override
    public void init(String... args) {
        port = Constants.DEFAULT_TCP_PORT;
        listenAddress = Constants.DEFAULT_HOST;
        for (int i = 0; args != null && i < args.length; i++) {
            String a = args[i];
            if (isOption(a, "-tcpListenAddress")) {
                listenAddress = args[++i];
            } else if (isOption(a, "-trace")) {
                trace = true;
            } else if (isOption(a, "-tcpSSL")) {
                ssl = true;
            } else if (isOption(a, "-tcpPort")) {
                port = Integer.decode(args[++i]);
                portIsSet = true;
            } else if (isOption(a, "-tcpPassword")) {
                managementPassword = args[++i];
            } else if (isOption(a, "-baseDir")) {
                baseDir = args[++i];
            } else if (isOption(a, "-key")) {
                key = args[++i];
                keyDatabase = args[++i];
            } else if (isOption(a, "-tcpAllowOthers")) {
                allowOthers = true;
            } else if (isOption(a, "-tcpDaemon")) {
                isDaemon = true;
            } else if (isOption(a, "-ifExists")) {
                ifExists = true;
            }
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

    @Override
    public synchronized void start() throws SQLException {
        stop = false;
        try {
            serverSocket = NetUtils.createServerSocket(listenAddress, port, ssl);
        } catch (DbException e) {
            if (!portIsSet) {
                serverSocket = NetUtils.createServerSocket(0, ssl);
            } else {
                throw e;
            }
        }
        port = serverSocket.getLocalPort();
        initManagementDb();

        String name = getName() + " (" + getURL() + ")";
        Thread t = new Thread(this, name);
        t.setDaemon(isDaemon());
        t.start();
    }

    private void listen() {
        listenerThread = Thread.currentThread();
        String threadName = listenerThread.getName();
        try {
            while (!stop) {
                Socket s = serverSocket.accept();
                TcpServerThread c = createTcpServerThread(s, nextThreadId++);
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

    protected TcpServerThread createTcpServerThread(Socket socket, int threadId) {
        return new TcpServerThread(socket, this, threadId);
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
    public String getBaseDir() {
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

    @Override
    public boolean getAllowOthers() {
        return allowOthers;
    }

    @Override
    public String getType() {
        return "TCP";
    }

    @Override
    public String getName() {
        return "TCP Server";
    }

    public boolean getIfExists() {
        return ifExists;
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
            try {
                Driver.load();
            } catch (Throwable e) {
                throw DbException.convert(e);
            }
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
    public String checkKeyAndGetDatabaseName(String db) {
        if (key == null) {
            return db;
        }
        if (key.equals(db)) {
            return keyDatabase;
        }
        throw DbException.get(ErrorCode.WRONG_USER_OR_PASSWORD);
    }

    @Override
    public boolean isDaemon() {
        return isDaemon;
    }

    @Override
    public void run() {
        try {
            listen();
        } catch (Exception e) {
            DbException.traceThrowable(e);
        }
    }
}
