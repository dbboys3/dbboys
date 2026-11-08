package com.dbboys.dialect.sqlite;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public final class SqliteSqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {

    @Override
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateSqlite(sentSql, errorMessage);
    }


    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        // SQLite is single-file, no-op
    }

    @Override
    public String explain(Connection conn, String sql) throws SQLException {
        if (conn == null || sql == null || sql.isBlank()) {
            return "";
        }
        String normalizedSql = normalizeExplainSql(sql);
        if (normalizedSql.isBlank()) {
            return "";
        }
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN QUERY PLAN " + normalizedSql)) {
            return readResultRows(rs);
        }
    }

    private static String normalizeExplainSql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        while (normalized.endsWith(";") || normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static String readResultRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) {
                text.append('\t');
            }
            text.append(meta.getColumnLabel(i));
        }
        while (rs.next()) {
            text.append(System.lineSeparator());
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    text.append('\t');
                }
                String value = rs.getString(i);
                text.append(value == null ? "NULL" : value);
            }
        }
        return text.toString();
    }
}
