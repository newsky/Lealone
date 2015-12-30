/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.client.result;

import java.io.IOException;

import org.lealone.db.value.Transfer;

/**
 * A column of the client result.
 */
public class ClientResultColumn {

    /**
     * The column alias.
     */
    final String alias;

    /**
     * The schema name or null.
     */
    final String schemaName;

    /**
     * The table name or null.
     */
    final String tableName;

    /**
     * The column name or null.
     */
    final String columnName;

    /**
     * The value type of this column.
     */
    final int columnType;

    /**
     * The precision.
     */
    final long precision;

    /**
     * The scale.
     */
    final int scale;

    /**
     * The expected display size.
     */
    final int displaySize;

    /**
     * True if this is an auto-increment column.
     */
    final boolean autoIncrement;

    /**
     * True if this column is nullable.
     */
    final int nullable;

    /**
     * Read an object from the given transfer object.
     *
     * @param in the object from where to read the data
     */
    ClientResultColumn(Transfer in) throws IOException {
        alias = in.readString();
        schemaName = in.readString();
        tableName = in.readString();
        columnName = in.readString();
        columnType = in.readInt();
        precision = in.readLong();
        scale = in.readInt();
        displaySize = in.readInt();
        autoIncrement = in.readBoolean();
        nullable = in.readInt();
    }
}
