/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.command;

import java.util.ArrayList;

import org.lealone.api.DatabaseEventListener;
import org.lealone.api.ParameterInterface;
import org.lealone.expression.Parameter;
import org.lealone.result.ResultInterface;
import org.lealone.value.Value;
import org.lealone.value.ValueNull;

/**
 * Represents a single SQL statements.
 * It wraps a prepared statement.
 */
public class CommandContainer extends Command {

    protected Prepared prepared;
    private boolean readOnlyKnown;
    private boolean readOnly;

    protected CommandContainer(Parser parser, String sql, Prepared prepared) {
        super(parser, sql);
        prepared.setCommand(this);
        this.prepared = prepared;
    }

    public ArrayList<? extends ParameterInterface> getParameters() {
        return prepared.getParameters();
    }

    public boolean isTransactional() {
        return prepared.isTransactional();
    }

    public boolean isQuery() {
        return prepared.isQuery();
    }

    private void recompileIfRequired() {
        if (prepared.needRecompile()) {
            // TODO test with 'always recompile'
            prepared.setModificationMetaId(0);
            String sql = prepared.getSQL();
            ArrayList<Parameter> oldParams = prepared.getParameters();
            Parser parser = session.createParser();
            prepared = parser.parse(sql);
            long mod = prepared.getModificationMetaId();
            prepared.setModificationMetaId(0);
            ArrayList<Parameter> newParams = prepared.getParameters();
            for (int i = 0, size = newParams.size(); i < size; i++) {
                Parameter old = oldParams.get(i);
                if (old.isValueSet()) {
                    Value v = old.getValue(session);
                    Parameter p = newParams.get(i);
                    p.setValue(v);
                }
            }
            prepared.prepare();
            prepared.setModificationMetaId(mod);
        }
    }

    public int update() {
        recompileIfRequired();
        setProgress(DatabaseEventListener.STATE_STATEMENT_START);
        start();
        session.setLastScopeIdentity(ValueNull.INSTANCE);
        prepared.checkParameters();
        int updateCount = updateInternal();
        prepared.trace(startTime, updateCount);
        setProgress(DatabaseEventListener.STATE_STATEMENT_END);
        return updateCount;
    }

    protected int updateInternal() {
        return prepared.update();
    }

    public ResultInterface query(int maxrows) {
        recompileIfRequired();
        setProgress(DatabaseEventListener.STATE_STATEMENT_START);
        start();
        prepared.checkParameters();
        ResultInterface result = queryInternal(maxrows);
        prepared.trace(startTime, result.getRowCount());
        setProgress(DatabaseEventListener.STATE_STATEMENT_END);
        return result;
    }

    protected ResultInterface queryInternal(int maxrows) {
        return prepared.query(maxrows);
    }

    public boolean isReadOnly() {
        if (!readOnlyKnown) {
            readOnly = prepared.isReadOnly();
            readOnlyKnown = true;
        }
        return readOnly;
    }

    public ResultInterface queryMeta() {
        return prepared.queryMeta();
    }

    public boolean isCacheable() {
        return prepared.isCacheable();
    }

    public int getCommandType() {
        return prepared.getType();
    }

    @Override
    public Prepared getPrepared() {
        return prepared;
    }

}
