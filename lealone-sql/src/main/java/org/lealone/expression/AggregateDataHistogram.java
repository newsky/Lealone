/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.expression;

import java.util.Arrays;
import java.util.Comparator;

import org.lealone.engine.Constants;
import org.lealone.engine.Database;
import org.lealone.util.ValueHashMap;
import org.lealone.value.CompareMode;
import org.lealone.value.Value;
import org.lealone.value.ValueArray;
import org.lealone.value.ValueLong;
import org.lealone.value.ValueNull;

/**
 * Data stored while calculating a HISTOGRAM aggregate.
 */
class AggregateDataHistogram extends AggregateData {
    private long count;
    private ValueHashMap<AggregateDataHistogram> distinctValues;

    @Override
    void add(Database database, int dataType, boolean distinct, Value v) {
        if (distinctValues == null) {
            distinctValues = ValueHashMap.newInstance();
        }
        AggregateDataHistogram a = distinctValues.get(v);
        if (a == null) {
            if (distinctValues.size() < Constants.SELECTIVITY_DISTINCT_COUNT) {
                a = new AggregateDataHistogram();
                distinctValues.put(v, a);
            }
        }
        if (a != null) {
            a.count++;
        }
    }

    @Override
    Value getValue(Database database, int dataType, boolean distinct) {
        if (distinct) {
            count = 0;
            groupDistinct(database, dataType);
        }
        ValueArray[] values = new ValueArray[distinctValues.size()];
        int i = 0;
        for (Value dv : distinctValues.keys()) {
            AggregateDataHistogram d = distinctValues.get(dv);
            values[i] = ValueArray.get(new Value[] { dv, ValueLong.get(d.count) });
            i++;
        }
        final CompareMode compareMode = database.getCompareMode();
        Arrays.sort(values, new Comparator<ValueArray>() {
            @Override
            public int compare(ValueArray v1, ValueArray v2) {
                Value a1 = v1.getList()[0];
                Value a2 = v2.getList()[0];
                return a1.compareTo(a2, compareMode);
            }
        });
        Value v = ValueArray.get(values);
        return v.convertTo(dataType);
    }

    private void groupDistinct(Database database, int dataType) {
        if (distinctValues == null) {
            return;
        }
        count = 0;
        for (Value v : distinctValues.keys()) {
            add(database, dataType, false, v);
        }
    }

    @Override
    void merge(Database database, int dataType, boolean distinct, Value v) {
    }

    @Override
    Value getMergedValue(Database database, int dataType, boolean distinct) {
        Value v = null;
        ValueArray[] values = new ValueArray[distinctValues.size()];
        int i = 0;
        for (Value dv : distinctValues.keys()) {
            AggregateDataHistogram d = distinctValues.get(dv);
            values[i] = ValueArray.get(new Value[] { dv, ValueLong.get(d.count) });
            i++;
        }
        final CompareMode compareMode = database.getCompareMode();
        Arrays.sort(values, new Comparator<ValueArray>() {
            @Override
            public int compare(ValueArray v1, ValueArray v2) {
                Value a1 = v1.getList()[0];
                Value a2 = v2.getList()[0];
                return a1.compareTo(a2, compareMode);
            }
        });
        v = ValueArray.get(values);

        return v == null ? ValueNull.INSTANCE : v.convertTo(dataType);
    }

}