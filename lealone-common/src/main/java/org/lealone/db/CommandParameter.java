/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.db;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.value.Value;

/**
 * The interface for client side and server side parameters.
 * 
 * @author H2 Group
 * @author zhh
 */
public interface CommandParameter {

    /**
     * Get the parameter index.
     *
     * @return the parameter index.
     */
    int getIndex();

    /**
     * Set the value of the parameter.
     *
     * @param value the new value
     * @param closeOld if the old value (if one is set) should be closed
     */
    void setValue(Value value, boolean closeOld);

    void setValue(Value value);

    /**
     * Get the value of the parameter if set.
     *
     * @return the value or null
     */
    Value getValue();

    /**
     * Check if the value is set.
     *
     * @throws DbException if not set.
     */
    void checkSet() throws DbException;

    /**
     * Is the value of a parameter set.
     *
     * @return true if set
     */
    boolean isValueSet();

    /**
     * Get the expected data type of the parameter if no value is set, or the
     * data type of the value if one is set.
     *
     * @return the data type
     */
    int getType();

    /**
     * Get the expected precision of this parameter.
     *
     * @return the expected precision
     */
    long getPrecision();

    /**
     * Get the expected scale of this parameter.
     *
     * @return the expected scale
     */
    int getScale();

    /**
     * Check if this column is nullable.
     *
     * @return Column.NULLABLE_*
     */
    int getNullable();

}
