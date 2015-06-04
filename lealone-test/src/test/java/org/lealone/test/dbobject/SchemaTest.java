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
import org.lealone.dbobject.Schema;

public class SchemaTest extends DbObjectTestBase {
    @Test
    public void run() {
        String userName = "sa1";
        executeUpdate("CREATE USER IF NOT EXISTS " + userName + " PASSWORD 'abc' ADMIN");

        int id = db.allocateObjectId();
        String schemaName = "test";
        Schema schema = new Schema(db, id, schemaName, db.getUser(userName), false);
        assertEquals(id, schema.getId());

        db.addDatabaseObject(session, schema);
        assertNotNull(db.findSchema(schemaName));

        db.removeDatabaseObject(session, schema);
        assertNull(db.findSchema(schemaName));

        //测试SQL
        //-----------------------------------------------
        executeUpdate("CREATE SCHEMA IF NOT EXISTS " + schemaName + " AUTHORIZATION " + userName);
        assertNotNull(db.findSchema(schemaName));
        executeUpdate("DROP SCHEMA IF EXISTS " + schemaName);
        assertNull(db.findSchema(schemaName));

        executeUpdate("CREATE SCHEMA IF NOT EXISTS test2 AUTHORIZATION " + userName);
        executeUpdate("ALTER SCHEMA test2 RENAME TO " + schemaName);
        assertNotNull(db.findSchema(schemaName));
        executeUpdate("DROP SCHEMA IF EXISTS " + schemaName);

        executeUpdate("CREATE SCHEMA IF NOT EXISTS " + schemaName + " AUTHORIZATION " + userName //
                + " WITH REPLICATION = ('class': 'SimpleStrategy', 'replication_factor':1)");
        schema = db.findSchema(schemaName);
        assertNotNull(schema);
        assertNotNull(schema.getReplicationProperties());
        assertTrue(schema.getReplicationProperties().containsKey("class"));

        executeUpdate("ALTER SCHEMA " + schemaName //
                + " WITH REPLICATION = ('class': 'SimpleStrategy', 'replication_factor':2)");

        schema = db.findSchema(schemaName);
        assertNotNull(schema);
        assertNotNull(schema.getReplicationProperties());
        assertEquals("2", schema.getReplicationProperties().get("replication_factor"));

        executeUpdate("DROP SCHEMA IF EXISTS " + schemaName);
        executeUpdate("DROP USER " + userName);
    }
}
