/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.command.dml;

import java.util.ArrayList;

import org.lealone.dbobject.table.Column;
import org.lealone.dbobject.table.ColumnResolver;
import org.lealone.dbobject.table.TableFilter;
import org.lealone.expression.Expression;
import org.lealone.expression.ExpressionColumn;
import org.lealone.value.Value;

/**
 * This class represents a column resolver for the column list of a SELECT
 * statement. It is used to resolve select column aliases in the HAVING clause.
 * Example:
 * <p>
 * SELECT X/3 AS A, COUNT(*) FROM SYSTEM_RANGE(1, 10) GROUP BY A HAVING A>2;
 * </p>
 *
 * @author Thomas Mueller
 */
public class SelectListColumnResolver implements ColumnResolver {

    private final Select select;
    private final Expression[] expressions;
    private final Column[] columns;

    SelectListColumnResolver(Select select) {
        this.select = select;
        int columnCount = select.getColumnCount();
        columns = new Column[columnCount];
        expressions = new Expression[columnCount];
        ArrayList<Expression> columnList = select.getExpressions();
        for (int i = 0; i < columnCount; i++) {
            Expression expr = columnList.get(i);
            Column column = new Column(expr.getAlias(), Value.NULL);
            column.setTable(null, i);
            columns[i] = column;
            expressions[i] = expr.getNonAliasExpression();
        }
    }

    public Column[] getColumns() {
        return columns;
    }

    public String getSchemaName() {
        return null;
    }

    public Select getSelect() {
        return select;
    }

    public Column[] getSystemColumns() {
        return null;
    }

    public Column getRowIdColumn() {
        return null;
    }

    public String getTableAlias() {
        return null;
    }

    public TableFilter getTableFilter() {
        return null;
    }

    public Value getValue(Column column) {
        return null;
    }

    public Expression optimize(ExpressionColumn expressionColumn, Column column) {
        return expressions[column.getColumnId()];
    }

}
