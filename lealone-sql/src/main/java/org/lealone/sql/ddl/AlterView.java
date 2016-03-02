/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.ServerSession;
import org.lealone.db.auth.Right;
import org.lealone.db.table.TableView;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * ALTER VIEW
 */
public class AlterView extends DefineStatement {

    private TableView view;

    public AlterView(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_VIEW;
    }

    public void setView(TableView view) {
        this.view = view;
    }

    @Override
    public int update() {
        session.commit(true);
        session.getUser().checkRight(view, Right.ALL);
        DbException e = view.recompile(session, false);
        if (e != null) {
            throw e;
        }
        return 0;
    }

}
