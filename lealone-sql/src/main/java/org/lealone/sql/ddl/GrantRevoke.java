/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import java.util.ArrayList;

import org.lealone.api.ErrorCode;
import org.lealone.common.message.DbException;
import org.lealone.common.util.New;
import org.lealone.db.Database;
import org.lealone.db.DbObject;
import org.lealone.db.LealoneDatabase;
import org.lealone.db.ServerSession;
import org.lealone.db.auth.Auth;
import org.lealone.db.auth.Right;
import org.lealone.db.auth.RightOwner;
import org.lealone.db.auth.Role;
import org.lealone.db.schema.Schema;
import org.lealone.db.table.Table;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statements
 * GRANT RIGHT,
 * GRANT ROLE,
 * REVOKE RIGHT,
 * REVOKE ROLE
 */
public class GrantRevoke extends DefineStatement {

    private ArrayList<String> roleNames;
    private int operationType;
    private int rightMask;
    private final ArrayList<Table> tables = New.arrayList();
    private Schema schema;
    private RightOwner grantee;

    public GrantRevoke(ServerSession session) {
        super(session);
    }

    public void setOperationType(int operationType) {
        this.operationType = operationType;
    }

    /**
     * Add the specified right bit to the rights bitmap.
     *
     * @param right the right bit
     */
    public void addRight(int right) {
        this.rightMask |= right;
    }

    /**
     * Add the specified role to the list of roles.
     *
     * @param roleName the role
     */
    public void addRoleName(String roleName) {
        if (roleNames == null) {
            roleNames = New.arrayList();
        }
        roleNames.add(roleName);
    }

    public void setGranteeName(String granteeName) {
        grantee = Auth.findUser(granteeName);
        if (grantee == null) {
            grantee = Auth.findRole(granteeName);
            if (grantee == null) {
                throw DbException.get(ErrorCode.USER_OR_ROLE_NOT_FOUND_1, granteeName);
            }
        }
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        session.commit(true);
        if (roleNames != null) {
            for (String name : roleNames) {
                Role grantedRole = Auth.findRole(name);
                if (grantedRole == null) {
                    throw DbException.get(ErrorCode.ROLE_NOT_FOUND_1, name);
                }
                if (operationType == SQLStatement.GRANT) {
                    grantRole(grantedRole);
                } else if (operationType == SQLStatement.REVOKE) {
                    revokeRole(grantedRole);
                } else {
                    DbException.throwInternalError("type=" + operationType);
                }
            }
        } else {
            if (operationType == SQLStatement.GRANT) {
                grantRight();
            } else if (operationType == SQLStatement.REVOKE) {
                revokeRight();
            } else {
                DbException.throwInternalError("type=" + operationType);
            }
        }
        return 0;
    }

    private void grantRight() {
        if (schema != null) {
            grantRight(schema);
        }
        for (Table table : tables) {
            grantRight(table);
        }
    }

    private void grantRight(DbObject object) {
        Database db = LealoneDatabase.getInstance();
        Right right = grantee.getRightForObject(object);
        if (right == null) {
            int id = getObjectId(db);
            right = new Right(db, id, grantee, rightMask, object);
            grantee.grantRight(object, right);
            db.addDatabaseObject(session, right);
        } else {
            right.setRightMask(right.getRightMask() | rightMask);
            db.updateMeta(session, right);
        }
    }

    private void grantRole(Role grantedRole) {
        if (grantedRole != grantee && grantee.isRoleGranted(grantedRole)) {
            return;
        }
        if (grantee instanceof Role) {
            Role granteeRole = (Role) grantee;
            if (grantedRole.isRoleGranted(granteeRole)) {
                // cyclic role grants are not allowed
                throw DbException.get(ErrorCode.ROLE_ALREADY_GRANTED_1, grantedRole.getSQL());
            }
        }
        Database db = LealoneDatabase.getInstance();
        int id = getObjectId(db);
        Right right = new Right(db, id, grantee, grantedRole);
        db.addDatabaseObject(session, right);
        grantee.grantRole(grantedRole, right);
    }

    private void revokeRight() {
        if (schema != null) {
            revokeRight(schema);
        }
        for (Table table : tables) {
            revokeRight(table);
        }
    }

    private void revokeRight(DbObject object) {
        Right right = grantee.getRightForObject(object);
        if (right == null) {
            return;
        }
        int mask = right.getRightMask();
        int newRight = mask & ~rightMask;
        Database db = LealoneDatabase.getInstance();
        if (newRight == 0) {
            db.removeDatabaseObject(session, right);
        } else {
            right.setRightMask(newRight);
            db.updateMeta(session, right);
        }
    }

    private void revokeRole(Role grantedRole) {
        Right right = grantee.getRightForRole(grantedRole);
        if (right == null) {
            return;
        }
        Database db = LealoneDatabase.getInstance();
        db.removeDatabaseObject(session, right);
    }

    @Override
    public boolean isTransactional() {
        return false;
    }

    /**
     * Add the specified table to the list of tables.
     *
     * @param table the table
     */
    public void addTable(Table table) {
        tables.add(table);
    }

    /**
     * Set the specified schema
     *
     * @param schema the schema
     */
    public void setSchema(Schema schema) {
        this.schema = schema;
    }

    @Override
    public int getType() {
        return operationType;
    }

    /**
     * @return true if this command is using Roles
     */
    public boolean isRoleMode() {
        return roleNames != null;
    }

    /**
     * @return true if this command is using Rights
     */
    public boolean isRightMode() {
        return rightMask != 0;
    }
}
