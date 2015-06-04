/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.dbobject;

import org.lealone.api.AggregateFunction;
import org.lealone.command.Parser;
import org.lealone.dbobject.table.Table;
import org.lealone.engine.Database;
import org.lealone.engine.Session;
import org.lealone.message.DbException;
import org.lealone.message.Trace;
import org.lealone.util.Utils;

/**
 * Represents a user-defined aggregate function.
 */
public class UserAggregate extends DbObjectBase {

    private String className;
    private Class<?> javaClass;

    public UserAggregate(Database db, int id, String name, String className, boolean force) {
        initDbObjectBase(db, id, name, Trace.FUNCTION);
        this.className = className;
        if (!force) {
            getInstance();
        }
    }

    public AggregateFunction getInstance() {
        if (javaClass == null) {
            javaClass = Utils.loadUserClass(className);
        }
        Object obj;
        try {
            obj = javaClass.newInstance();
            AggregateFunction agg = (AggregateFunction) obj;
            return agg;
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    public String getCreateSQLForCopy(Table table, String quotedName) {
        throw DbException.throwInternalError();
    }

    public String getDropSQL() {
        return "DROP AGGREGATE IF EXISTS " + getSQL();
    }

    public String getCreateSQL() {
        return "CREATE FORCE AGGREGATE " + getSQL() + " FOR " + Parser.quoteIdentifier(className);
    }

    public int getType() {
        return DbObject.AGGREGATE;
    }

    public synchronized void removeChildrenAndResources(Session session) {
        database.removeMeta(session, getId());
        className = null;
        javaClass = null;
        invalidate();
    }

    public void checkRename() {
        throw DbException.getUnsupportedException("AGGREGATE");
    }

    public String getJavaClassName() {
        return this.className;
    }

}
