/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.api.ErrorCode;
import org.lealone.common.exceptions.DbException;
import org.lealone.db.Database;
import org.lealone.db.ServerSession;
import org.lealone.db.auth.Right;
import org.lealone.db.schema.Schema;
import org.lealone.db.table.Table;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * ALTER TABLE RENAME
 */
public class AlterTableRename extends SchemaStatement {

    private Table oldTable;
    private String newTableName;
    private boolean hidden;

    public AlterTableRename(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_TABLE_RENAME;
    }

    public void setOldTable(Table table) {
        oldTable = table;
    }

    public void setNewTableName(String name) {
        newTableName = name;
    }

    @Override
    public int update() {
        session.commit(true);
        Database db = session.getDatabase();
        session.getUser().checkRight(oldTable, Right.ALL);
        Table t = getSchema().findTableOrView(session, newTableName);
        if (t != null && hidden && newTableName.equals(oldTable.getName())) {
            if (!t.isHidden()) {
                t.setHidden(hidden);
                oldTable.setHidden(true);
                db.updateMeta(session, oldTable);
            }
            return 0;
        }
        if (t != null || newTableName.equals(oldTable.getName())) {
            throw DbException.get(ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1, newTableName);
        }
        if (oldTable.isTemporary()) {
            throw DbException.getUnsupportedException("temp table");
        }
        db.renameSchemaObject(session, oldTable, newTableName);
        return 0;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

}
