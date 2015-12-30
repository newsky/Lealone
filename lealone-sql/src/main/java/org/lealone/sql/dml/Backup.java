/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.dml;

import org.lealone.db.ServerSession;
import org.lealone.db.result.Result;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.StatementBase;
import org.lealone.sql.expression.Expression;

/**
 * This class represents the statement
 * BACKUP
 */
public class Backup extends StatementBase {

    private Expression fileNameExpr;

    public Backup(ServerSession session) {
        super(session);
    }

    public void setFileName(Expression fileName) {
        this.fileNameExpr = fileName;
    }

    @Override
    public int update() {
        String fileName = fileNameExpr.getValue(session).getString();
        session.getUser().checkAdmin();
        session.getDatabase().backupTo(fileName);
        return 0;
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public boolean needRecompile() {
        return false;
    }

    @Override
    public Result queryMeta() {
        return null;
    }

    @Override
    public int getType() {
        return SQLStatement.BACKUP;
    }

}
