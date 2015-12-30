/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.expression;

import org.lealone.common.message.DbException;
import org.lealone.db.Database;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueLong;
import org.lealone.db.value.ValueNull;

/**
 * Data stored while calculating a COUNT(*) aggregate.
 */
class AggregateDataCountAll extends AggregateData {
    private long count;

    @Override
    void add(Database database, int dataType, boolean distinct, Value v) {
        // 在Parser.readAggregate那里确保使用COUNT_ALL时distinct是false
        if (distinct) {
            throw DbException.throwInternalError();
        }
        count++;
    }

    @Override
    Value getValue(Database database, int dataType, boolean distinct) {
        if (distinct) {
            throw DbException.throwInternalError();
        }
        Value v = ValueLong.get(count);
        return v == null ? ValueNull.INSTANCE : v.convertTo(dataType);
    }

    @Override
    void merge(Database database, int dataType, boolean distinct, Value v) {
        count += v.getLong();
    }

    @Override
    Value getMergedValue(Database database, int dataType, boolean distinct) {
        return ValueLong.get(count);
    }
}