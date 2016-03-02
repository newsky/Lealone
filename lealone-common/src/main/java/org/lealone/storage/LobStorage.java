/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

import org.lealone.db.value.Value;
import org.lealone.db.value.ValueLob;

/**
 * A mechanism to store and retrieve lob data.
 * 
 * @author H2 Group
 * @author zhh
 */
public interface LobStorage {

    /**
     * The table id for session variables (LOBs not assigned to a table).
     */
    int TABLE_ID_SESSION_VARIABLE = -1;

    /**
     * The table id for temporary objects (not assigned to any object).
     */
    int TABLE_TEMP = -2;

    /**
     * The table id for result sets.
     */
    int TABLE_RESULT = -3;

    /**
     * Initialize the lob storage.
     */
    void init();

    /**
     * Whether the storage is read-only
     *
     * @return true if yes
     */
    boolean isReadOnly();

    /**
     * Create a BLOB object.
     *
     * @param in the input stream
     * @param maxLength the maximum length (-1 if not known)
     * @return the LOB
     */
    Value createBlob(InputStream in, long maxLength);

    /**
     * Create a CLOB object.
     *
     * @param reader the reader
     * @param maxLength the maximum length (-1 if not known)
     * @return the LOB
     */
    Value createClob(Reader reader, long maxLength);

    /**
     * Copy a lob.
     *
     * @param old the old lob
     * @param tableId the new table id
     * @param length the length
     * @return the new lob
     */
    ValueLob copyLob(ValueLob old, int tableId, long length);

    /**
     * Get the input stream for the given lob.
     *
     * @param lob the lob id
     * @param hmac the message authentication code (for remote input streams)
     * @param byteCount the number of bytes to read, or -1 if not known
     * @return the stream
     */
    InputStream getInputStream(ValueLob lob, byte[] hmac, long byteCount) throws IOException;

    /**
     * Set the table reference of this lob.
     *
     * @param lob the lob
     * @param table the table
     */
    void setTable(ValueLob lob, int table);

    /**
     * Remove all LOBs for this table.
     *
     * @param tableId the table id
     */
    void removeAllForTable(int tableId);

    /**
     * Delete a LOB (from the database, if it is stored there).
     *
     * @param lob the lob
     */
    void removeLob(ValueLob lob);

}
