/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.command.ddl;

import org.lealone.api.ErrorCode;
import org.lealone.command.CommandInterface;
import org.lealone.dbobject.Schema;
import org.lealone.dbobject.Sequence;
import org.lealone.engine.Database;
import org.lealone.engine.Session;
import org.lealone.expression.Expression;
import org.lealone.message.DbException;

/**
 * This class represents the statement
 * CREATE SEQUENCE
 */
public class CreateSequence extends SchemaCommand {

    private String sequenceName;
    private boolean ifNotExists;
    private Expression start;
    private Expression increment;
    private Expression cacheSize;
    private boolean belongsToTable;

    public CreateSequence(Session session, Schema schema) {
        super(session, schema);
    }

    public void setSequenceName(String sequenceName) {
        this.sequenceName = sequenceName;
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public int update() {
        session.commit(true);
        Database db = session.getDatabase();
        if (getSchema().findSequence(sequenceName) != null) {
            if (ifNotExists) {
                return 0;
            }
            throw DbException.get(ErrorCode.SEQUENCE_ALREADY_EXISTS_1, sequenceName);
        }
        int id = getObjectId();
        Sequence sequence = session.createSequence(getSchema(), id, sequenceName, belongsToTable);
        sequence.setStartValue(getLong(start, 1));
        sequence.setIncrement(getLong(increment, 1));
        sequence.setCacheSize(getLong(cacheSize, Sequence.DEFAULT_CACHE_SIZE));
        db.addSchemaObject(session, sequence);
        return 0;
    }

    private long getLong(Expression expr, long defaultValue) {
        if (expr == null) {
            return defaultValue;
        }
        return expr.optimize(session).getValue(session).getLong();
    }

    public void setStartWith(Expression start) {
        this.start = start;
    }

    public void setIncrement(Expression increment) {
        this.increment = increment;
    }

    public void setBelongsToTable(boolean belongsToTable) {
        this.belongsToTable = belongsToTable;
    }

    public void setCacheSize(Expression cacheSize) {
        this.cacheSize = cacheSize;
    }

    public int getType() {
        return CommandInterface.CREATE_SEQUENCE;
    }

}
