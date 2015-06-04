/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.test.dbobject;

import org.junit.Test;
import org.lealone.dbobject.UserDataType;
import org.lealone.dbobject.table.Column;

public class UserDataTypeTest extends DbObjectTestBase {

    @Test
    public void run() {
        int id = db.allocateObjectId();
        String udtName = "EMAIL";
        UserDataType udt = new UserDataType(db, id, udtName);
        assertEquals(id, udt.getId());

        Column column = new Column("c", 0);
        udt.setColumn(column);

        db.addDatabaseObject(session, udt);
        assertNotNull(db.findUserDataType(udtName));

        udt.removeChildrenAndResources(session);
        assertNotNull(db.findUserDataType(udtName)); //并不会删除UserDataType

        db.removeDatabaseObject(session, udt);
        assertNull(db.findUserDataType(udtName));

        //测试SQL
        //CREATE DOMAIN/TYPE/DATATYPE都是一样的
        //DROP DOMAIN/TYPE/DATATYPE也是一样的
        //-----------------------------------------------
        //VALUE是CREATE DOMAIN语句的默认临时列名
        String sql = "CREATE DOMAIN IF NOT EXISTS " + udtName + " AS VARCHAR(255) CHECK (POSITION('@', VALUE) > 1)";
        executeUpdate(sql);
        assertNotNull(db.findUserDataType(udtName));
        sql = "DROP DOMAIN " + udtName;
        executeUpdate(sql);
        assertNull(db.findUserDataType(udtName));

        sql = "CREATE TYPE IF NOT EXISTS " + udtName + " AS VARCHAR(255) CHECK (POSITION('@', VALUE) > 1)";
        executeUpdate(sql);
        assertNotNull(db.findUserDataType(udtName));
        sql = "DROP TYPE " + udtName;
        executeUpdate(sql);
        assertNull(db.findUserDataType(udtName));

        sql = "CREATE DATATYPE IF NOT EXISTS " + udtName + " AS VARCHAR(255) CHECK (POSITION('@', VALUE) > 1)";
        executeUpdate(sql);
        assertNotNull(db.findUserDataType(udtName));
        sql = "DROP DATATYPE " + udtName;
        executeUpdate(sql);
        assertNull(db.findUserDataType(udtName));

        //从第二个名称开始的都是隐藏类型的，如下面的int
        //new String[]{"INTEGER", "INT", "MEDIUMINT", "INT4", "SIGNED"}
        //隐藏类型当用户在数据库中没有建表时可以覆盖
        //如CREATE DATATYPE IF NOT EXISTS int AS VARCHAR(255)
        //但是非隐藏类型就不能覆盖
        //如CREATE DATATYPE IF NOT EXISTS integer AS VARCHAR(255)
        sql = "CREATE DATATYPE IF NOT EXISTS int AS VARCHAR(255) CHECK (POSITION('@', VALUE) > 1)";
        executeUpdate(sql);
        udtName = "int";
        if (db.getSettings().databaseToUpper)
            udtName = udtName.toUpperCase();
        assertNotNull(db.findUserDataType(udtName));
        sql = "DROP DATATYPE int";
        executeUpdate(sql);
        assertNull(db.findUserDataType(udtName));

        try {
            udtName = "integer";
            //如果DATABASE_TO_UPPER是false就用大写INTEGER
            if (!db.getSettings().databaseToUpper)
                udtName = udtName.toUpperCase();
            sql = "CREATE DATATYPE IF NOT EXISTS " + udtName + " AS VARCHAR(255) CHECK (POSITION('@', VALUE) > 1)";
            executeUpdate(sql);
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().toLowerCase().contains("user data type"));
        }
    }
}
