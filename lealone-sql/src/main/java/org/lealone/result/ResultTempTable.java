/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.result;

import java.util.ArrayList;

import org.lealone.command.ddl.CreateTableData;
import org.lealone.dbobject.Schema;
import org.lealone.dbobject.index.Cursor;
import org.lealone.dbobject.index.Index;
import org.lealone.dbobject.index.IndexType;
import org.lealone.dbobject.table.Column;
import org.lealone.dbobject.table.IndexColumn;
import org.lealone.dbobject.table.TableBase;
import org.lealone.engine.Constants;
import org.lealone.engine.Database;
import org.lealone.engine.Session;
import org.lealone.value.Value;
import org.lealone.value.ValueArray;

/**
 * This class implements the temp table buffer for the LocalResult class.
 */
public class ResultTempTable implements ResultExternal {

    private static final String COLUMN_NAME = "DATA";
    private final SortOrder sort;
    private final Session session;
    private final Index index;
    private TableBase table;
    private Cursor resultCursor;

    private final ResultTempTable parent;
    private boolean closed;
    private int childCount;

    ResultTempTable(Session session, SortOrder sort) {
        this.session = session;
        this.sort = sort;
        Database db = session.getDatabase();
        Schema schema = db.getSchema(Constants.SCHEMA_MAIN);
        Column column = new Column(COLUMN_NAME, Value.ARRAY);
        column.setNullable(false);
        CreateTableData data = new CreateTableData();
        data.columns.add(column);
        data.id = db.allocateObjectId();
        data.tableName = "TEMP_RESULT_SET_" + data.id;
        data.temporary = true;
        data.persistIndexes = false;
        data.persistData = true;
        data.create = true;
        data.session = session;
        table = (TableBase) schema.createTable(data);
        int indexId = db.allocateObjectId();
        IndexColumn indexColumn = new IndexColumn();
        indexColumn.column = column;
        indexColumn.columnName = COLUMN_NAME;
        IndexType indexType;
        indexType = IndexType.createPrimaryKey(true, false);
        IndexColumn[] indexCols = { indexColumn };
        index = db.createIndex(table, indexId, data.tableName, indexCols, indexType, true, session);
        index.setTemporary(true);
        table.getIndexes().add(index);
        parent = null;
    }

    private ResultTempTable(ResultTempTable parent) {
        this.parent = parent;
        this.session = parent.session;
        this.table = parent.table;
        this.index = parent.index;
        // sort is only used when adding rows
        this.sort = null;
        reset();
    }

    @Override
    public synchronized ResultExternal createShallowCopy() {
        if (parent != null) {
            return parent.createShallowCopy();
        }
        if (closed) {
            return null;
        }
        childCount++;
        return new ResultTempTable(this);
    }

    @Override
    public int removeRow(Value[] values) {
        Row row = convertToRow(values);
        Cursor cursor = find(row);
        if (cursor != null) {
            row = cursor.get();
            table.removeRow(session, row);
        }
        return (int) table.getRowCount(session);
    }

    @Override
    public boolean contains(Value[] values) {
        return find(convertToRow(values)) != null;
    }

    @Override
    public int addRow(Value[] values) {
        Row row = convertToRow(values);
        Cursor cursor = find(row);
        if (cursor == null) {
            table.addRow(session, row);
        }
        return (int) table.getRowCount(session);
    }

    @Override
    public int addRows(ArrayList<Value[]> rows) {
        if (sort != null) {
            sort.sort(rows);
        }
        for (Value[] values : rows) {
            addRow(values);
        }
        return (int) table.getRowCount(session);
    }

    private synchronized void closeChild() {
        if (--childCount == 0 && closed) {
            dropTable();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (parent != null) {
            parent.closeChild();
        } else {
            if (childCount == 0) {
                dropTable();
            }
        }
    }

    private void dropTable() {
        if (table == null) {
            return;
        }
        try {
            table.truncate(session);
            Database database = session.getDatabase();
            synchronized (database) {
                Session sysSession = database.getSystemSession();
                if (!database.isSysTableLocked()) {
                    // this session may not lock the sys table (except if it already has locked it)
                    // because it must be committed immediately
                    // otherwise other threads can not access the sys table.
                    // if the table is not removed now, it will be when the database
                    // is opened the next time
                    // (the table is truncated, so this is just one record)
                    synchronized (sysSession) {
                        index.removeChildrenAndResources(sysSession);
                        table.removeChildrenAndResources(sysSession);
                        // the transaction must be committed immediately
                        sysSession.commit(false);
                    }
                }
            }
        } finally {
            table = null;
        }
    }

    @Override
    public void done() {
        // nothing to do
    }

    @Override
    public Value[] next() {
        if (!resultCursor.next()) {
            return null;
        }
        Row row = resultCursor.get();
        ValueArray data = (ValueArray) row.getValue(0);
        return data.getList();
    }

    @Override
    public void reset() {
        resultCursor = index.find(session, null, null);
    }

    private static Row convertToRow(Value[] values) {
        ValueArray data = ValueArray.get(values);
        return new Row(new Value[] { data }, Row.MEMORY_CALCULATE);
    }

    private Cursor find(Row row) {
        Cursor cursor = index.find(session, row, row);
        Value a = row.getValue(0);
        while (cursor.next()) {
            SearchRow found;
            found = cursor.getSearchRow();
            Value b = found.getValue(0);
            if (session.getDatabase().areEqual(a, b)) {
                return cursor;
            }
        }
        return null;
    }

}
