/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.client;

import java.io.IOException;
import java.net.Socket;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Random;

import org.lealone.api.ErrorCode;
import org.lealone.common.message.DbException;
import org.lealone.common.message.JdbcSQLException;
import org.lealone.common.message.Trace;
import org.lealone.common.message.TraceSystem;
import org.lealone.common.util.MathUtils;
import org.lealone.common.util.NetUtils;
import org.lealone.common.util.SmallLRUCache;
import org.lealone.common.util.StringUtils;
import org.lealone.common.util.TempFileDeleter;
import org.lealone.db.Command;
import org.lealone.db.ConnectionInfo;
import org.lealone.db.Constants;
import org.lealone.db.DataHandler;
import org.lealone.db.Session;
import org.lealone.db.SessionBase;
import org.lealone.db.SessionFactory;
import org.lealone.db.SetTypes;
import org.lealone.db.SysProperties;
import org.lealone.db.value.Transfer;
import org.lealone.db.value.Value;
import org.lealone.sql.BatchStatement;
import org.lealone.sql.ParsedStatement;
import org.lealone.sql.PreparedStatement;
import org.lealone.storage.LobStorage;
import org.lealone.storage.fs.FileStorage;
import org.lealone.storage.fs.FileUtils;
import org.lealone.transaction.Transaction;

/**
 * The client side part of a session when using the server mode. 
 * This object communicates with a Session on the server side.
 */
public class ClientSession extends SessionBase implements DataHandler, Transaction.Participant {

    private static final Random random = new Random(System.currentTimeMillis());

    private SessionFactory sessionFactory;

    private TraceSystem traceSystem;
    private Trace trace;
    private Transfer transfer;
    private int nextId;
    private boolean autoCommit = true;
    private final ConnectionInfo connectionInfo;
    private String cipher;
    private byte[] fileEncryptionKey;
    private final Object lobSyncObject = new Object();
    private String sessionId;
    private int clientVersion;
    private int lastReconnect;
    private Session embedded;
    private LobStorage lobStorage;
    private Transaction transaction;

    public ClientSession(ConnectionInfo ci) {
        this.connectionInfo = ci;
    }

    private Transfer initTransfer(ConnectionInfo ci, String server) throws IOException {
        Socket socket = NetUtils.createSocket(server, Constants.DEFAULT_TCP_PORT, ci.isSSL());
        Transfer trans = new Transfer(this, socket);
        trans.setSSL(ci.isSSL());
        trans.init();
        trans.writeInt(Constants.TCP_PROTOCOL_VERSION_1); // minClientVersion
        trans.writeInt(Constants.TCP_PROTOCOL_VERSION_1); // maxClientVersion
        trans.writeString(ci.getDatabaseName());
        trans.writeString(ci.getURL()); // 不带参数的URL
        trans.writeString(ci.getUserName());
        trans.writeBytes(ci.getUserPasswordHash());
        trans.writeBytes(ci.getFilePasswordHash());
        trans.writeBytes(ci.getFileEncryptionKey());
        String[] keys = ci.getKeys();
        trans.writeInt(keys.length);
        for (String key : keys) {
            trans.writeString(key).writeString(ci.getProperty(key));
        }
        try {
            done(trans);
            clientVersion = trans.readInt();
            trans.setVersion(clientVersion);
            trans.writeInt(ClientSession.SESSION_SET_ID);
            trans.writeString(sessionId);
            done(trans);
            autoCommit = trans.readBoolean();
        } catch (DbException e) {
            trans.close();
            throw e;
        }
        return trans;
    }

    @Override
    public void cancel() {
        // this method is called when closing the connection
        // the statement that is currently running is not canceled in this case
        // however Statement.cancel is supported
    }

    /**
     * Cancel the statement with the given id.
     *
     * @param id the statement id
     */
    public void cancelStatement(int id) {
        try {
            Transfer trans = transfer.openNewConnection();
            trans.init();
            trans.writeInt(clientVersion);
            trans.writeInt(clientVersion);
            trans.writeString(null);
            trans.writeString(null);
            trans.writeString(sessionId);
            trans.writeInt(ClientSession.SESSION_CANCEL_STATEMENT);
            trans.writeInt(id);
            trans.close();
        } catch (IOException e) {
            trace.debug(e, "could not cancel statement");
        }
    }

    @Override
    public boolean isAutoCommit() {
        return autoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        setAutoCommitSend(autoCommit);
        this.autoCommit = autoCommit;
    }

    public void setAutoCommitFromServer(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    private void setAutoCommitSend(boolean autoCommit) {
        try {
            traceOperation("SESSION_SET_AUTOCOMMIT", autoCommit ? 1 : 0);
            transfer.writeInt(ClientSession.SESSION_SET_AUTOCOMMIT).writeBoolean(autoCommit);
            done(transfer);
        } catch (IOException e) {
            handleException(e);
        }
    }

    private String getFilePrefix(String dir, String dbName) {
        StringBuilder buff = new StringBuilder(dir);
        buff.append('/');
        for (int i = 0; i < dbName.length(); i++) {
            char ch = dbName.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                buff.append(ch);
            } else {
                buff.append('_');
            }
        }
        return buff.toString();
    }

    /**
     * Open a new (remote or embedded) session.
     *
     * @return the session
     */
    @Override
    public Session connectEmbeddedOrServer() {
        ConnectionInfo ci = connectionInfo;
        if (ci.isRemote()) {
            connectServer(ci);
            return this;
        }
        // create the session using reflection,
        // so that the JDBC layer can be compiled without it
        try {
            if (sessionFactory == null) {
                sessionFactory = ci.getSessionFactory();
            }
            return sessionFactory.createSession(ci);
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    private void connectServer(ConnectionInfo ci) {
        traceSystem = new TraceSystem(null);
        String traceLevelFile = ci.getProperty(SetTypes.TRACE_LEVEL_FILE, null);
        if (traceLevelFile != null) {
            int level = Integer.parseInt(traceLevelFile);
            String prefix = getFilePrefix(SysProperties.CLIENT_TRACE_DIRECTORY, ci.getDatabaseName());
            try {
                traceSystem.setLevelFile(level);
                if (level > 0) {
                    String file = FileUtils.createTempFile(prefix, Constants.SUFFIX_TRACE_FILE, false, false);
                    traceSystem.setFileName(file);
                }
            } catch (IOException e) {
                throw DbException.convertIOException(e, prefix);
            }
        }
        String traceLevelSystemOut = ci.getProperty(SetTypes.TRACE_LEVEL_SYSTEM_OUT, null);
        if (traceLevelSystemOut != null) {
            int level = Integer.parseInt(traceLevelSystemOut);
            traceSystem.setLevelSystemOut(level);
        }
        trace = traceSystem.getTrace(Trace.JDBC);
        cipher = ci.getProperty("CIPHER");
        if (cipher != null) {
            fileEncryptionKey = MathUtils.secureRandomBytes(32);
        }

        String[] servers = StringUtils.arraySplit(ci.getServers(), ',', true);
        int len = servers.length;
        transfer = null;
        sessionId = StringUtils.convertBytesToHex(MathUtils.secureRandomBytes(32));

        try {
            for (int i = 0; i < len; i++) {
                String s = servers[random.nextInt(len)];
                try {
                    transfer = initTransfer(ci, s);
                    break;
                } catch (IOException e) {
                    if (i == len - 1) {
                        throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, e, e + ": " + s);
                    }
                    int index = 0;
                    String[] newServers = new String[len - 1];
                    for (int j = 0; j < len; j++) {
                        if (j != i)
                            newServers[index++] = servers[j];
                    }
                    servers = newServers;
                    len--;
                    i = -1;
                }
            }
            checkClosed();
        } catch (DbException e) {
            traceSystem.close();
            throw e;
        }
    }

    // TODO
    public void handleException(Exception e) {
        checkClosed();
    }

    @Override
    public synchronized Command prepareCommand(String sql, int fetchSize) {
        checkClosed();
        return new ClientCommand(this, transfer, sql, fetchSize);
    }

    /**
     * Check if this session is closed and throws an exception if so.
     *
     * @throws DbException if the session is closed
     */
    public void checkClosed() {
        if (isClosed()) {
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "session closed");
        }
    }

    @Override
    public void close() {
        RuntimeException closeError = null;
        if (transfer != null) {
            synchronized (this) {
                try {
                    traceOperation("SESSION_CLOSE", 0);
                    transfer.writeInt(ClientSession.SESSION_CLOSE);
                    done(transfer);
                    transfer.close();
                } catch (RuntimeException e) {
                    trace.error(e, "close");
                    closeError = e;
                } catch (Exception e) {
                    trace.error(e, "close");
                }
            }
            transfer = null;
        }
        traceSystem.close();
        if (embedded != null) {
            embedded.close();
            embedded = null;
        }
        if (closeError != null) {
            throw closeError;
        }
    }

    @Override
    public Trace getTrace() {
        return traceSystem.getTrace(Trace.JDBC);
    }

    public int getNextId() {
        return nextId++;
    }

    public int getCurrentId() {
        return nextId;
    }

    /**
     * Called to flush the output after data has been sent to the server and
     * just before receiving data. This method also reads the status code from
     * the server and throws any exception the server sent.
     *
     * @param transfer the transfer object
     * @throws DbException if the server sent an exception
     * @throws IOException if there is a communication problem between client
     *             and server
     */
    public void done(Transfer transfer) throws IOException {
        // 正常来讲不会出现这种情况，如果出现了，说明存在bug，找出为什么transfer的输入流没正常读完的原因
        if (transfer.available() > 0) {
            throw DbException.throwInternalError("before transfer flush, the available bytes was "
                    + transfer.available());
        }

        transfer.flush();
        int status = transfer.readInt();
        if (status == STATUS_ERROR) {
            parseError(transfer);
        } else if (status == STATUS_CLOSED) {
            transfer = null;
        } else if (status == STATUS_OK_STATE_CHANGED) {
            sessionStateChanged = true;
        } else if (status == STATUS_OK) {
            // ok
        } else {
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "unexpected status " + status);
        }
    }

    public void parseError(Transfer transfer) throws IOException {
        String sqlstate = transfer.readString();
        String message = transfer.readString();
        String sql = transfer.readString();
        int errorCode = transfer.readInt();
        String stackTrace = transfer.readString();
        JdbcSQLException s = new JdbcSQLException(message, sql, sqlstate, errorCode, null, stackTrace);
        if (errorCode == ErrorCode.CONNECTION_BROKEN_1) {
            // allow re-connect
            IOException e = new IOException(s.toString());
            e.initCause(s);
            throw e;
        }
        throw DbException.convert(s);
    }

    @Override
    public boolean isClosed() {
        return transfer == null;
    }

    /**
     * Write the operation to the trace system if debug trace is enabled.
     *
     * @param operation the operation performed
     * @param id the id of the operation
     */
    public void traceOperation(String operation, int id) {
        if (trace.isDebugEnabled()) {
            trace.debug("{0} {1}", operation, id);
        }
    }

    @Override
    public void checkPowerOff() {
        // ok
    }

    @Override
    public void checkWritingAllowed() {
        // ok
    }

    @Override
    public String getDatabasePath() {
        return "";
    }

    @Override
    public String getLobCompressionAlgorithm(int type) {
        return null;
    }

    @Override
    public int getMaxLengthInplaceLob() {
        return SysProperties.LOB_CLIENT_MAX_SIZE_MEMORY;
    }

    @Override
    public FileStorage openFile(String name, String mode, boolean mustExist) {
        if (mustExist && !FileUtils.exists(name)) {
            throw DbException.get(ErrorCode.FILE_NOT_FOUND_1, name);
        }
        FileStorage fileStorage;
        if (cipher == null) {
            fileStorage = FileStorage.open(this, name, mode);
        } else {
            fileStorage = FileStorage.open(this, name, mode, cipher, fileEncryptionKey, 0);
        }
        fileStorage.setCheckedWriting(false);
        try {
            fileStorage.init();
        } catch (DbException e) {
            fileStorage.closeSilently();
            throw e;
        }
        return fileStorage;
    }

    @Override
    public DataHandler getDataHandler() {
        return this;
    }

    @Override
    public Object getLobSyncObject() {
        return lobSyncObject;
    }

    @Override
    public SmallLRUCache<String, String[]> getLobFileListCache() {
        return null;
    }

    public int getLastReconnect() {
        return lastReconnect;
    }

    @Override
    public TempFileDeleter getTempFileDeleter() {
        return TempFileDeleter.getInstance();
    }

    @Override
    public LobStorage getLobStorage() {
        if (lobStorage == null) {
            lobStorage = new ClientLobStorage(this);
        }
        return lobStorage;
    }

    @Override
    public Connection getLobConnection() {
        return null;
    }

    @Override
    public synchronized int readLob(long lobId, byte[] hmac, long offset, byte[] buff, int off, int length) {

        try {
            traceOperation("LOB_READ", (int) lobId);
            transfer.writeInt(ClientSession.LOB_READ);
            transfer.writeLong(lobId);
            transfer.writeBytes(hmac);
            transfer.writeLong(offset);
            transfer.writeInt(length);
            done(transfer);
            length = transfer.readInt();
            if (length <= 0) {
                return length;
            }
            transfer.readBytes(buff, off, length);
            return length;
        } catch (IOException e) {
            handleException(e);
        }
        return 1;
    }

    @Override
    public synchronized void commitTransaction(String allLocalTransactionNames) {
        checkClosed();
        try {
            transfer.writeInt(ClientSession.COMMAND_EXECUTE_DISTRIBUTED_COMMIT).writeString(allLocalTransactionNames);
            done(transfer);
        } catch (IOException e) {
            handleException(e);
        }
    }

    @Override
    public synchronized void rollbackTransaction() {
        checkClosed();
        try {
            transfer.writeInt(ClientSession.COMMAND_EXECUTE_DISTRIBUTED_ROLLBACK);
            done(transfer);
        } catch (IOException e) {
            handleException(e);
        }
    }

    @Override
    public synchronized void addSavepoint(String name) {
        checkClosed();
        try {
            transfer.writeInt(ClientSession.COMMAND_EXECUTE_DISTRIBUTED_SAVEPOINT_ADD).writeString(name);
            done(transfer);
        } catch (IOException e) {
            handleException(e);
        }
    }

    @Override
    public synchronized void rollbackToSavepoint(String name) {
        checkClosed();
        try {
            transfer.writeInt(ClientSession.COMMAND_EXECUTE_DISTRIBUTED_SAVEPOINT_ROLLBACK).writeString(name);
            done(transfer);
        } catch (IOException e) {
            handleException(e);
        }
    }

    @Override
    public synchronized boolean validateTransaction(String localTransactionName) {
        checkClosed();
        try {
            transfer.writeInt(ClientSession.COMMAND_EXECUTE_TRANSACTION_VALIDATE).writeString(localTransactionName);
            done(transfer);
            return transfer.readBoolean();
        } catch (Exception e) {
            handleException(e);
            return false;
        }
    }

    // 要加synchronized，避免ClientCommand在执行更新和查询时其他线程把transaction置null
    @Override
    public synchronized void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    @Override
    public Transaction getTransaction() {
        return transaction;
    }

    public synchronized ClientBatchCommand getClientBatchCommand(ArrayList<String> batchCommands) {
        checkClosed();
        return new ClientBatchCommand(this, transfer, batchCommands);
    }

    public synchronized ClientBatchCommand getClientBatchCommand(Command preparedCommand,
            ArrayList<Value[]> batchParameters) {
        checkClosed();
        return new ClientBatchCommand(this, transfer, preparedCommand, batchParameters);
    }

    @Override
    public String getURL() {
        return connectionInfo.getURL();
    }

    @Override
    public synchronized void checkTransfers() {
        if (transfer != null) {
            try {
                if (transfer.available() > 0)
                    throw DbException.throwInternalError("the transfer available bytes was " + transfer.available());
            } catch (IOException e) {
                throw DbException.convert(e);
            }
        }
    }

    @Override
    public ParsedStatement parseStatement(String sql) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int fetchSize) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public BatchStatement getBatchStatement(PreparedStatement ps, ArrayList<Value[]> batchParameters) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public BatchStatement getBatchStatement(ArrayList<String> batchCommands) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getModificationId() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void rollback() {
        // TODO Auto-generated method stub

    }

    @Override
    public void setRoot(boolean isRoot) {
        // TODO Auto-generated method stub

    }

    @Override
    public void commit(boolean ddl, String allLocalTransactionNames) {
        // TODO Auto-generated method stub

    }
}
