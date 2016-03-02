/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.db.table;

import java.util.ArrayList;

import org.lealone.api.ErrorCode;
import org.lealone.common.exceptions.DbException;
import org.lealone.db.ServerSession;
import org.lealone.db.expression.Expression;
import org.lealone.db.index.Index;
import org.lealone.db.index.IndexType;
import org.lealone.db.index.RangeIndex;
import org.lealone.db.result.Row;
import org.lealone.db.schema.Schema;
import org.lealone.db.value.Value;

/**
 * The table SYSTEM_RANGE is a virtual table that generates incrementing numbers
 * with a given start end end point.
 */
public class RangeTable extends Table {

    /**
     * The name of the range table.
     */
    public static final String NAME = "SYSTEM_RANGE";

    /**
     * The PostgreSQL alias for the range table.
     */
    public static final String ALIAS = "GENERATE_SERIES";

    private Expression min, max, step;
    private boolean optimized;

    /**
     * Create a new range with the given start and end expressions.
     *
     * @param schema the schema (always the main schema)
     * @param min the start expression
     * @param max the end expression
     * @param noColumns whether this table has no columns
     */
    public RangeTable(Schema schema, Expression min, Expression max, boolean noColumns) {
        super(schema, 0, NAME, true, true);
        Column[] cols = noColumns ? new Column[0] : new Column[] { new Column("X", Value.LONG) };
        this.min = min;
        this.max = max;
        setColumns(cols);
    }

    public RangeTable(Schema schema, Expression min, Expression max, Expression step, boolean noColumns) {
        this(schema, min, max, noColumns);
        this.step = step;
    }

    @Override
    public String getCreateSQL() {
        return null;
    }

    @Override
    public String getSQL() {
        String sql = NAME + "(" + min.getSQL() + ", " + max.getSQL();
        if (step != null) {
            sql += ", " + step.getSQL();
        }
        return sql + ")";
    }

    @Override
    public boolean lock(ServerSession session, boolean exclusive, boolean forceLockEvenInMvcc) {
        // nothing to do
        return false;
    }

    @Override
    public void close(ServerSession session) {
        // nothing to do
    }

    @Override
    public void unlock(ServerSession s) {
        // nothing to do
    }

    @Override
    public boolean isLockedExclusively() {
        return false;
    }

    @Override
    public Index addIndex(ServerSession session, String indexName, int indexId, IndexColumn[] cols,
            IndexType indexType, boolean create, String indexComment) {
        throw DbException.getUnsupportedException("SYSTEM_RANGE");
    }

    @Override
    public void removeRow(ServerSession session, Row row) {
        throw DbException.getUnsupportedException("SYSTEM_RANGE");
    }

    @Override
    public void addRow(ServerSession session, Row row) {
        throw DbException.getUnsupportedException("SYSTEM_RANGE");
    }

    @Override
    public void checkSupportAlter() {
        throw DbException.getUnsupportedException("SYSTEM_RANGE");
    }

    @Override
    public void checkRename() {
        throw DbException.getUnsupportedException("SYSTEM_RANGE");
    }

    @Override
    public boolean canGetRowCount() {
        return true;
    }

    @Override
    public boolean canDrop() {
        return false;
    }

    @Override
    public long getRowCount(ServerSession session) {
        return Math.max(0, getMax(session) - getMin(session) + 1);
    }

    @Override
    public String getTableType() {
        throw DbException.throwInternalError();
    }

    @Override
    public Index getScanIndex(ServerSession session) {
        if (getStep(session) == 0) {
            throw DbException.get(ErrorCode.STEP_SIZE_MUST_NOT_BE_ZERO);
        }
        return new RangeIndex(this, IndexColumn.wrap(columns));
    }

    /**
     * Calculate and get the start value of this range.
     *
     * @param session the session
     * @return the start value
     */
    public long getMin(ServerSession session) {
        optimize(session);
        return min.getValue(session).getLong();
    }

    /**
     * Calculate and get the end value of this range.
     *
     * @param session the session
     * @return the end value
     */
    public long getMax(ServerSession session) {
        optimize(session);
        return max.getValue(session).getLong();
    }

    /**
     * Get the increment.
     *
     * @param session the session
     * @return the increment (1 by default)
     */
    public long getStep(ServerSession session) {
        optimize(session);
        if (step == null) {
            return 1;
        }
        return step.getValue(session).getLong();
    }

    private void optimize(ServerSession s) {
        if (!optimized) {
            min = min.optimize(s);
            max = max.optimize(s);
            if (step != null) {
                step = step.optimize(s);
            }
            optimized = true;
        }
    }

    @Override
    public ArrayList<Index> getIndexes() {
        return null;
    }

    @Override
    public void truncate(ServerSession session) {
        throw DbException.getUnsupportedException("SYSTEM_RANGE");
    }

    @Override
    public long getMaxDataModificationId() {
        return 0;
    }

    @Override
    public Index getUniqueIndex() {
        return null;
    }

    @Override
    public long getRowCountApproximation() {
        return 100;
    }

    @Override
    public long getDiskSpaceUsed() {
        return 0;
    }

    @Override
    public boolean isDeterministic() {
        return true;
    }

    @Override
    public boolean canReference() {
        return false;
    }

}
