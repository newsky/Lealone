/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.command.ddl;

import java.util.ArrayList;

import org.lealone.api.ErrorCode;
import org.lealone.command.CommandInterface;
import org.lealone.command.dml.Insert;
import org.lealone.command.dml.Query;
import org.lealone.dbobject.Schema;
import org.lealone.dbobject.Sequence;
import org.lealone.dbobject.table.Column;
import org.lealone.dbobject.table.IndexColumn;
import org.lealone.dbobject.table.Table;
import org.lealone.engine.Database;
import org.lealone.engine.Session;
import org.lealone.expression.Expression;
import org.lealone.message.DbException;
import org.lealone.util.New;
import org.lealone.value.DataType;

/**
 * This class represents the statement
 * CREATE TABLE
 */
public class CreateTable extends SchemaCommand {

    protected final CreateTableData data = new CreateTableData();
    protected IndexColumn[] pkColumns;
    protected boolean ifNotExists;
    protected boolean dynamicTable;

    private final ArrayList<DefineCommand> constraintCommands = New.arrayList();
    private boolean onCommitDrop;
    private boolean onCommitTruncate;
    private Query asQuery;
    private String comment;
    private boolean sortedInsertMode;

    public CreateTable(Session session, Schema schema) {
        super(session, schema);
        data.persistIndexes = true;
        data.persistData = true;
    }

    public void setQuery(Query query) {
        this.asQuery = query;
    }

    public void setTemporary(boolean temporary) {
        data.temporary = temporary;
    }

    public void setTableName(String tableName) {
        data.tableName = tableName;
    }

    /**
     * Add a column to this table.
     *
     * @param column the column to add
     */
    public void addColumn(Column column) {
        data.columns.add(column);
    }

    /**
     * Add a constraint statement to this statement.
     * The primary key definition is one possible constraint statement.
     *
     * @param command the statement to add
     */
    public void addConstraintCommand(DefineCommand command) {
        if (command instanceof CreateIndex) {
            constraintCommands.add(command);
        } else {
            AlterTableAddConstraint con = (AlterTableAddConstraint) command;
            boolean alreadySet;
            if (con.getType() == CommandInterface.ALTER_TABLE_ADD_CONSTRAINT_PRIMARY_KEY) {
                alreadySet = setPrimaryKeyColumns(con.getIndexColumns());
            } else {
                alreadySet = false;
            }
            if (!alreadySet) {
                constraintCommands.add(command);
            }
        }
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public boolean isDynamicTable() {
        return dynamicTable;
    }

    public void setDynamicTable(boolean dynamicTable) {
        this.dynamicTable = dynamicTable;
    }

    @Override
    public int update() {
        if (!transactional) {
            session.commit(true);
        }
        Database db = session.getDatabase();
        if (!db.isPersistent()) {
            data.persistIndexes = false;
        }
        if (getSchema().findTableOrView(session, data.tableName) != null) {
            if (ifNotExists) {
                return 0;
            }
            throw DbException.get(ErrorCode.TABLE_OR_VIEW_ALREADY_EXISTS_1, data.tableName);
        }
        if (asQuery != null) {
            asQuery.prepare();
            if (data.columns.isEmpty()) {
                generateColumnsFromQuery();
            } else if (data.columns.size() != asQuery.getColumnCount()) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
        if (pkColumns != null) {
            for (Column c : data.columns) {
                for (IndexColumn idxCol : pkColumns) {
                    if (c.getName().equals(idxCol.columnName)) {
                        c.setNullable(false);
                    }
                }
            }
        }
        data.id = getObjectId();
        data.create = create;
        data.session = session;
        boolean isSessionTemporary = data.temporary && !data.globalTemporary;
        if (!isSessionTemporary) {
            db.lockMeta(session);
        }
        Table table = createTable(data);
        ArrayList<Sequence> sequences = New.arrayList();
        for (Column c : data.columns) {
            if (c.isAutoIncrement()) {
                int objId = getObjectId();
                c.convertAutoIncrementToSequence(session, getSchema(), objId, data.temporary);
            }
            Sequence seq = c.getSequence();
            if (seq != null) {
                sequences.add(seq);
            }
        }
        table.setComment(comment);
        if (isSessionTemporary) {
            if (onCommitDrop) {
                table.setOnCommitDrop(true);
            }
            if (onCommitTruncate) {
                table.setOnCommitTruncate(true);
            }
            session.addLocalTempTable(table);
        } else {
            db.lockMeta(session);
            db.addSchemaObject(session, table);
        }
        try {
            for (Column c : data.columns) {
                c.prepareExpression(session);
            }
            for (Sequence sequence : sequences) {
                table.addSequence(sequence);
            }
            for (DefineCommand command : constraintCommands) {
                command.setTransactional(transactional);
                command.update();
            }
            if (asQuery != null) {
                Insert insert = null;
                insert = session.createInsert();
                insert.setSortedInsertMode(sortedInsertMode);
                insert.setQuery(asQuery);
                insert.setTable(table);
                insert.setInsertFromSelect(true);
                insert.prepare();
                insert.update();
            }
        } catch (DbException e) {
            db.checkPowerOff();
            db.removeSchemaObject(session, table);
            if (!transactional) {
                session.commit(true);
            }
            throw e;
        }
        return 0;
    }

    protected Table createTable(CreateTableData data) {
        return getSchema().createTable(data);
    }

    private void generateColumnsFromQuery() {
        int columnCount = asQuery.getColumnCount();
        ArrayList<Expression> expressions = asQuery.getExpressions();
        for (int i = 0; i < columnCount; i++) {
            Expression expr = expressions.get(i);
            int type = expr.getType();
            String name = expr.getAlias();
            long precision = expr.getPrecision();
            int displaySize = expr.getDisplaySize();
            DataType dt = DataType.getDataType(type);
            if (precision > 0
                    && (dt.defaultPrecision == 0 || (dt.defaultPrecision > precision && dt.defaultPrecision < Byte.MAX_VALUE))) {
                // dont' set precision to MAX_VALUE if this is the default
                precision = dt.defaultPrecision;
            }
            int scale = expr.getScale();
            if (scale > 0 && (dt.defaultScale == 0 || (dt.defaultScale > scale && dt.defaultScale < precision))) {
                scale = dt.defaultScale;
            }
            if (scale > precision) {
                precision = scale;
            }
            Column col = new Column(name, type, precision, scale, displaySize);
            addColumn(col);
        }
    }

    /**
     * Sets the primary key columns, but also check if a primary key
     * with different columns is already defined.
     *
     * @param columns the primary key columns
     * @return true if the same primary key columns where already set
     */
    private boolean setPrimaryKeyColumns(IndexColumn[] columns) {
        if (pkColumns != null) {
            int len = columns.length;
            if (len != pkColumns.length) {
                throw DbException.get(ErrorCode.SECOND_PRIMARY_KEY);
            }
            for (int i = 0; i < len; i++) {
                if (!columns[i].columnName.equals(pkColumns[i].columnName)) {
                    throw DbException.get(ErrorCode.SECOND_PRIMARY_KEY);
                }
            }
            return true;
        }
        this.pkColumns = columns;
        return false;
    }

    public void setPersistIndexes(boolean persistIndexes) {
        data.persistIndexes = persistIndexes;
    }

    public void setGlobalTemporary(boolean globalTemporary) {
        data.globalTemporary = globalTemporary;
    }

    /**
     * This temporary table is dropped on commit.
     */
    public void setOnCommitDrop() {
        this.onCommitDrop = true;
    }

    /**
     * This temporary table is truncated on commit.
     */
    public void setOnCommitTruncate() {
        this.onCommitTruncate = true;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public void setPersistData(boolean persistData) {
        data.persistData = persistData;
        if (!persistData) {
            data.persistIndexes = false;
        }
    }

    public void setSortedInsertMode(boolean sortedInsertMode) {
        this.sortedInsertMode = sortedInsertMode;
    }

    public void setStorageEngine(String storageEngine) {
        data.storageEngine = storageEngine;
    }

    public void setHidden(boolean isHidden) {
        data.isHidden = isHidden;
    }

    @Override
    public int getType() {
        return CommandInterface.CREATE_TABLE;
    }

}
