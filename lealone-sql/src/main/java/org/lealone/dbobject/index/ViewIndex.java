/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.dbobject.index;

import java.util.ArrayList;

import org.lealone.api.ErrorCode;
import org.lealone.command.dml.Query;
import org.lealone.command.dml.SelectUnion;
import org.lealone.dbobject.table.Column;
import org.lealone.dbobject.table.IndexColumn;
import org.lealone.dbobject.table.Table;
import org.lealone.dbobject.table.TableFilter;
import org.lealone.dbobject.table.TableView;
import org.lealone.engine.Constants;
import org.lealone.engine.Session;
import org.lealone.expression.Comparison;
import org.lealone.expression.Parameter;
import org.lealone.message.DbException;
import org.lealone.result.LocalResult;
import org.lealone.result.ResultInterface;
import org.lealone.result.Row;
import org.lealone.result.SearchRow;
import org.lealone.result.SortOrder;
import org.lealone.util.IntArray;
import org.lealone.util.New;
import org.lealone.util.SmallLRUCache;
import org.lealone.util.SynchronizedVerifier;
import org.lealone.util.Utils;
import org.lealone.value.Value;
import org.lealone.value.ValueNull;

/**
 * This object represents a virtual index for a query.
 * Actually it only represents a prepared SELECT statement.
 */
public class ViewIndex extends IndexBase {

    private final TableView view;
    private final String querySQL;
    private final ArrayList<Parameter> originalParameters;
    private final SmallLRUCache<IntArray, CostElement> costCache = SmallLRUCache
            .newInstance(Constants.VIEW_INDEX_CACHE_SIZE);
    private boolean recursive;
    private final int[] indexMasks;
    private String planSQL;
    private Query query;
    private final Session createSession;

    public ViewIndex(TableView view, String querySQL, ArrayList<Parameter> originalParameters, boolean recursive) {
        initIndexBase(view, 0, null, null, IndexType.createNonUnique(false));
        this.view = view;
        this.querySQL = querySQL;
        this.originalParameters = originalParameters;
        this.recursive = recursive;
        columns = new Column[0];
        this.createSession = null;
        this.indexMasks = null;
    }

    public ViewIndex(TableView view, ViewIndex index, Session session, int[] masks) {
        initIndexBase(view, 0, null, null, IndexType.createNonUnique(false));
        this.view = view;
        this.querySQL = index.querySQL;
        this.originalParameters = index.originalParameters;
        this.recursive = index.recursive;
        this.indexMasks = masks;
        this.createSession = session;
        columns = new Column[0];
        if (!recursive) {
            query = getQuery(session, masks);
            planSQL = query.getPlanSQL();
        }
    }

    public Session getSession() {
        return createSession;
    }

    @Override
    public String getPlanSQL() {
        return planSQL;
    }

    @Override
    public void close(Session session) {
        // nothing to do
    }

    @Override
    public void add(Session session, Row row) {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public void remove(Session session, Row row) {
        throw DbException.getUnsupportedException("VIEW");
    }

    /**
     * A calculated cost value.
     */
    static class CostElement {

        /**
         * The time in milliseconds when this cost was calculated.
         */
        long evaluatedAt;

        /**
         * The cost.
         */
        double cost;
    }

    @Override
    public synchronized double getCost(Session session, int[] masks, SortOrder sortOrder) {
        if (recursive) {
            return 1000;
        }
        IntArray masksArray = new IntArray(masks == null ? Utils.EMPTY_INT_ARRAY : masks);
        SynchronizedVerifier.check(costCache);
        CostElement cachedCost = costCache.get(masksArray);
        if (cachedCost != null) {
            long time = System.currentTimeMillis();
            if (time < cachedCost.evaluatedAt + Constants.VIEW_COST_CACHE_MAX_AGE) {
                return cachedCost.cost;
            }
        }
        Query q = (Query) session.prepare(querySQL, true);
        if (masks != null) {
            IntArray paramIndex = new IntArray();
            for (int i = 0; i < masks.length; i++) {
                int mask = masks[i];
                if (mask == 0) {
                    continue;
                }
                paramIndex.add(i);
            }
            int len = paramIndex.size();
            for (int i = 0; i < len; i++) {
                int idx = paramIndex.get(i);
                int mask = masks[idx];
                int nextParamIndex = q.getParameters().size() + view.getParameterOffset();
                if ((mask & IndexCondition.EQUALITY) != 0) {
                    Parameter param = new Parameter(nextParamIndex);
                    q.addGlobalCondition(param, idx, Comparison.EQUAL_NULL_SAFE);
                } else {
                    if ((mask & IndexCondition.START) != 0) {
                        Parameter param = new Parameter(nextParamIndex);
                        q.addGlobalCondition(param, idx, Comparison.BIGGER_EQUAL);
                    }
                    if ((mask & IndexCondition.END) != 0) {
                        Parameter param = new Parameter(nextParamIndex);
                        q.addGlobalCondition(param, idx, Comparison.SMALLER_EQUAL);
                    }
                }
            }
            String sql = q.getPlanSQL();
            q = (Query) session.prepare(sql, true);
        }
        double cost = q.getCost();
        cachedCost = new CostElement();
        cachedCost.evaluatedAt = System.currentTimeMillis();
        cachedCost.cost = cost;
        costCache.put(masksArray, cachedCost);
        return cost;
    }

    @Override
    public Cursor find(TableFilter filter, SearchRow first, SearchRow last) {
        if (query != null) {
            for (TableFilter f : query.getTopFilters())
                f.setPrepared(filter.getPrepared());
        }
        return find(filter.getSession(), first, last);
    }

    @Override
    public Cursor find(Session session, SearchRow first, SearchRow last) {
        if (recursive) {
            ResultInterface recResult = view.getRecursiveResult();
            if (recResult != null) {
                recResult.reset();
                return new ViewCursor(this, recResult, first, last);
            }
            if (query == null) {
                query = (Query) createSession.prepare(querySQL, true);
                planSQL = query.getPlanSQL();
            }
            if (!(query instanceof SelectUnion)) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_2, "recursive queries without UNION ALL");
            }
            SelectUnion union = (SelectUnion) query;
            if (union.getUnionType() != SelectUnion.UNION_ALL) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_2, "recursive queries without UNION ALL");
            }
            Query left = union.getLeft();
            // to ensure the last result is not closed
            left.disableCache();
            LocalResult r = (LocalResult) left.query(0);
            LocalResult result = union.getEmptyResult();
            while (r.next()) {
                result.addRow(r.currentRow());
            }
            Query right = union.getRight();
            r.reset();
            view.setRecursiveResult(r);
            // to ensure the last result is not closed
            right.disableCache();
            while (true) {
                r = (LocalResult) right.query(0);
                if (r.getRowCount() == 0) {
                    break;
                }
                while (r.next()) {
                    result.addRow(r.currentRow());
                }
                r.reset();
                view.setRecursiveResult(r);
            }
            view.setRecursiveResult(null);
            result.done();
            return new ViewCursor(this, result, first, last);
        }
        ArrayList<Parameter> paramList = query.getParameters();
        if (originalParameters != null) {
            for (int i = 0, size = originalParameters.size(); i < size; i++) {
                Parameter orig = originalParameters.get(i);
                int idx = orig.getIndex();
                Value value = orig.getValue(session);
                setParameter(paramList, idx, value);
            }
        }
        int len;
        if (first != null) {
            len = first.getColumnCount();
        } else if (last != null) {
            len = last.getColumnCount();
        } else {
            len = 0;
        }
        int idx = originalParameters == null ? 0 : originalParameters.size();
        idx += view.getParameterOffset();
        for (int i = 0; i < len; i++) {
            if (first != null) {
                Value v = first.getValue(i);
                if (v != null) {
                    int x = idx++;
                    setParameter(paramList, x, v);
                }
            }
            // for equality, only one parameter is used (first == last)
            if (last != null && indexMasks[i] != IndexCondition.EQUALITY) {
                Value v = last.getValue(i);
                if (v != null) {
                    int x = idx++;
                    setParameter(paramList, x, v);
                }
            }
        }
        ResultInterface result = query.query(0);
        return new ViewCursor(this, result, first, last);
    }

    private static void setParameter(ArrayList<Parameter> paramList, int x, Value v) {
        if (x >= paramList.size()) {
            // the parameter may be optimized away as in
            // select * from (select null as x) where x=1;
            return;
        }
        Parameter param = paramList.get(x);
        param.setValue(v);
    }

    private Query getQuery(Session session, int[] masks) {
        Query q = (Query) session.prepare(querySQL, true);
        if (masks == null) {
            return q;
        }
        if (!q.allowGlobalConditions()) {
            return q;
        }
        int firstIndexParam = originalParameters == null ? 0 : originalParameters.size();
        firstIndexParam += view.getParameterOffset();
        IntArray paramIndex = new IntArray();
        int indexColumnCount = 0;
        for (int i = 0; i < masks.length; i++) {
            int mask = masks[i];
            if (mask == 0) {
                continue;
            }
            indexColumnCount++;
            paramIndex.add(i);
            if (Integer.bitCount(mask) > 1) {
                // two parameters for range queries: >= x AND <= y
                paramIndex.add(i);
            }
        }
        int len = paramIndex.size();
        ArrayList<Column> columnList = New.arrayList();
        for (int i = 0; i < len;) {
            int idx = paramIndex.get(i);
            columnList.add(table.getColumn(idx));
            int mask = masks[idx];
            if ((mask & IndexCondition.EQUALITY) == IndexCondition.EQUALITY) {
                Parameter param = new Parameter(firstIndexParam + i);
                q.addGlobalCondition(param, idx, Comparison.EQUAL_NULL_SAFE);
                i++;
            }
            if ((mask & IndexCondition.START) == IndexCondition.START) {
                Parameter param = new Parameter(firstIndexParam + i);
                q.addGlobalCondition(param, idx, Comparison.BIGGER_EQUAL);
                i++;
            }
            if ((mask & IndexCondition.END) == IndexCondition.END) {
                Parameter param = new Parameter(firstIndexParam + i);
                q.addGlobalCondition(param, idx, Comparison.SMALLER_EQUAL);
                i++;
            }
        }
        columns = new Column[columnList.size()];
        columnList.toArray(columns);

        // reconstruct the index columns from the masks
        this.indexColumns = new IndexColumn[indexColumnCount];
        this.columnIds = new int[indexColumnCount];
        for (int type = 0, indexColumnId = 0; type < 2; type++) {
            for (int i = 0; i < masks.length; i++) {
                int mask = masks[i];
                if (mask == 0) {
                    continue;
                }
                if (type == 0) {
                    if ((mask & IndexCondition.EQUALITY) != IndexCondition.EQUALITY) {
                        // the first columns need to be equality conditions
                        continue;
                    }
                } else {
                    if ((mask & IndexCondition.EQUALITY) == IndexCondition.EQUALITY) {
                        // then only range conditions
                        continue;
                    }
                }
                IndexColumn c = new IndexColumn();
                c.column = table.getColumn(i);
                indexColumns[indexColumnId] = c;
                columnIds[indexColumnId] = c.column.getColumnId();
                indexColumnId++;
            }
        }

        String sql = q.getPlanSQL();
        q = (Query) session.prepare(sql, true);
        return q;
    }

    @Override
    public void remove(Session session) {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public void truncate(Session session) {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public void checkRename() {
        throw DbException.getUnsupportedException("VIEW");
    }

    @Override
    public boolean needRebuild() {
        return false;
    }

    @Override
    public boolean canGetFirstOrLast() {
        return false;
    }

    @Override
    public Cursor findFirstOrLast(Session session, boolean first) {
        throw DbException.getUnsupportedException("VIEW");
    }

    public void setRecursive(boolean value) {
        this.recursive = value;
    }

    @Override
    public long getRowCount(Session session) {
        return 0;
    }

    @Override
    public long getRowCountApproximation() {
        return 0;
    }

    @Override
    public long getDiskSpaceUsed() {
        return 0;
    }

    public boolean isRecursive() {
        return recursive;
    }

    /**
     * The cursor implementation of a view index.
     */
    private static class ViewCursor implements Cursor {

        private final Table table;
        private final Index index;
        private final ResultInterface result;
        private final SearchRow first, last;
        private Row current;

        ViewCursor(Index index, ResultInterface result, SearchRow first, SearchRow last) {
            this.table = index.getTable();
            this.index = index;
            this.result = result;
            this.first = first;
            this.last = last;
        }

        @Override
        public Row get() {
            return current;
        }

        @Override
        public SearchRow getSearchRow() {
            return current;
        }

        @Override
        public boolean next() {
            while (true) {
                boolean res = result.next();
                if (!res) {
                    result.close();
                    current = null;
                    return false;
                }
                current = table.getTemplateRow();
                Value[] values = result.currentRow();
                for (int i = 0, len = current.getColumnCount(); i < len; i++) {
                    Value v = i < values.length ? values[i] : ValueNull.INSTANCE;
                    current.setValue(i, v);
                }
                int comp;
                if (first != null) {
                    comp = index.compareRows(current, first);
                    if (comp < 0) {
                        continue;
                    }
                }
                if (last != null) {
                    comp = index.compareRows(current, last);
                    if (comp > 0) {
                        continue;
                    }
                }
                return true;
            }
        }

        @Override
        public boolean previous() {
            throw DbException.throwInternalError();
        }
    }
}
