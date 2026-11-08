package com.dbboys.core;

/**
 * Where a SQL execution error points, as reported by a dialect.
 *
 * @param offset       0-based char index of the error within the reference SQL
 * @param referenceSql the SQL text the offset is relative to; {@code null} means
 *                     the statement sent to the driver (some drivers, e.g. Oracle,
 *                     report positions against their own copy of the SQL)
 */
public record SqlErrorPosition(int offset, String referenceSql) {

    public static SqlErrorPosition inStatement(int offset) {
        return new SqlErrorPosition(offset, null);
    }
}
