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
package org.lealone.test.sql.index;

import java.sql.SQLException;
import java.sql.Savepoint;

import org.junit.Test;
import org.lealone.test.sql.SqlTestBase;

public class IndexTest extends SqlTestBase {
    @Test
    public void run() throws Exception {
        init();
        insert();
        select();
        testCommit();
        testRollback();
        //testSavepoint(); //TODO
    }

    void init() throws Exception {
        stmt.executeUpdate("DROP TABLE IF EXISTS IndexTest");
        stmt.executeUpdate("CREATE TABLE IF NOT EXISTS IndexTest (f1 int NOT NULL, f2 int, f3 varchar)");
        stmt.executeUpdate("CREATE PRIMARY KEY HASH IF NOT EXISTS IndexTest_idx0 ON IndexTest(f1)");
        stmt.executeUpdate("CREATE UNIQUE HASH INDEX IF NOT EXISTS IndexTest_idx1 ON IndexTest(f2)");
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS IndexTest_idx2 ON IndexTest(f3, f2)");
    }

    void delete() throws Exception {
        stmt.executeUpdate("DELETE FROM IndexTest");
    }

    void insert() throws Exception {
        stmt.executeUpdate("DELETE FROM IndexTest");
        sql = "SELECT f1, f2, f3 FROM IndexTest";
        printResultSet();

        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(100, 10, 'a')");
        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(200, 20, 'b')");
        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(300, 30, 'c')");
        try {
            stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(400, 20, 'd')");
            fail("insert duplicate key: 20");
        } catch (SQLException e) {
            //e.printStackTrace();
        }

        try {
            stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(200, 20, 'e')");
            fail("insert duplicate key: 20");
        } catch (SQLException e) {
            //e.printStackTrace();
        }

        try {
            stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(100, 20, 'f')");
            fail("insert duplicate key: 20");
        } catch (SQLException e) {
            //e.printStackTrace();
        }

        sql = "SELECT f1, f2, f3 FROM IndexTest";
        printResultSet();
    }

    void testCommit() throws Exception {
        stmt.executeUpdate("DELETE FROM IndexTest");

        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(100, 10, 'a1')");
        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(200, 20, 'b2')");
        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(300, 30, 'c3')");
        sql = "SELECT f1, f2, f3 FROM IndexTest";
        printResultSet();

        sql = "SELECT f3 FROM IndexTest where f1 = 300";
        assertEquals("c3", getStringValue(1, true));

        try {
            conn.setAutoCommit(false);
            insert();
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }

        sql = "SELECT f3 FROM IndexTest where f1 = 300";
        assertEquals("c", getStringValue(1, true));

        sql = "SELECT count(*) FROM IndexTest";
        assertEquals(3, getIntValue(1, true));

        sql = "DELETE FROM IndexTest";
        assertEquals(3, stmt.executeUpdate(sql));

        sql = "SELECT count(*) FROM IndexTest";
        assertEquals(0, getIntValue(1, true));
    }

    void testRollback() throws Exception {
        stmt.executeUpdate("DELETE FROM IndexTest");
        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(100, 10, 'a1')");
        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(200, 20, 'b2')");
        stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(300, 30, 'c3')");
        sql = "SELECT f1, f2, f3 FROM IndexTest";
        printResultSet();
        sql = "SELECT count(*) FROM IndexTest";
        assertEquals(3, getIntValue(1, true));

        sql = "SELECT f3 FROM IndexTest where f1 = 300";
        assertEquals("c3", getStringValue(1, true));

        try {
            conn.setAutoCommit(false);
            insert();
            conn.rollback();
        } finally {
            conn.setAutoCommit(true);
        }

        sql = "SELECT f3 FROM IndexTest where f1 = 300";
        assertEquals("c3", getStringValue(1, true));

        sql = "SELECT f1, f2, f3 FROM IndexTest";
        printResultSet();
        sql = "SELECT count(*) FROM IndexTest";
        assertEquals(3, getIntValue(1, true));

        stmt.executeUpdate("DELETE FROM IndexTest");
        assertEquals(0, getIntValue(1, true));

        try {
            conn.setAutoCommit(false);
            insert();
            conn.rollback();
        } finally {
            conn.setAutoCommit(true);
        }

        sql = "SELECT count(*) FROM IndexTest";
        assertEquals(0, getIntValue(1, true));
    }

    void select() throws Exception {
        sql = "SELECT f1, f2, f3 FROM IndexTest";
        printResultSet();

        sql = "SELECT count(*) FROM IndexTest";
        assertEquals(3, getIntValue(1, true));

        sql = "SELECT f1, f2, f3 FROM IndexTest WHERE f1 >= 200";
        printResultSet();

        sql = "SELECT count(*) FROM IndexTest WHERE f1 >= 200";
        assertEquals(2, getIntValue(1, true));

        sql = "SELECT f1, f2, f3 FROM IndexTest WHERE f2 >= 20";
        printResultSet();

        sql = "SELECT count(*) FROM IndexTest WHERE f2 >= 20";
        assertEquals(2, getIntValue(1, true));

        sql = "SELECT f1, f2, f3 FROM IndexTest WHERE f3 >= 'b' AND f3 <= 'c'";
        printResultSet();

        sql = "SELECT count(*) FROM IndexTest WHERE f3 >= 'b' AND f3 <= 'c'";
        assertEquals(2, getIntValue(1, true));

        sql = "DELETE FROM IndexTest WHERE f2 >= 20";
        assertEquals(2, stmt.executeUpdate(sql));
    }

    void testSavepoint() throws Exception {
        stmt.executeUpdate("DELETE FROM IndexTest");
        try {
            conn.setAutoCommit(false);
            stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(100, 10, 'a')");
            //stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(200, 20, 'b')");
            Savepoint savepoint = conn.setSavepoint();
            stmt.executeUpdate("INSERT INTO IndexTest(f1, f2, f3) VALUES(300, 30, 'c')");
            sql = "SELECT f1, f2, f3 FROM IndexTest";
            printResultSet();
            sql = "SELECT count(*) FROM IndexTest";
            assertEquals(3, getIntValue(1, true));
            conn.rollback(savepoint);
            //调用rollback(savepoint)后还是需要调用commit
            conn.commit();
            //或调用rollback也能撤消之前的操作
            //conn.rollback();
        } finally {
            //这个内部也会触发commit
            conn.setAutoCommit(true);
        }

        sql = "SELECT f1, f2, f3 FROM IndexTest";
        printResultSet();
        sql = "SELECT count(*) FROM IndexTest";
        assertEquals(2, getIntValue(1, true));
    }
}
