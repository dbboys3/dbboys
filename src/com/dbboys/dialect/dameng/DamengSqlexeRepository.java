package com.dbboys.dialect.dameng;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class DamengSqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {

    @Override
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateByMessage(sentSql, errorMessage);
    }


    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        String quotedSchema = "\"" + databaseName.trim().replace("\"", "\"\"") + "\"";
        try (var stmt = conn.createStatement()) {
            try {
                stmt.execute("alter session set current_schema = " + quotedSchema);
            } catch (SQLException first) {
                stmt.execute("set schema " + quotedSchema);
            }
        }
    }

    @Override
    public boolean autoCommitsDdl() {
        return true;
    }

    @Override
    public String explain(Connection conn, String sql) throws SQLException {
        if (conn == null || sql == null || sql.isBlank()) {
            return null;
        }
        String normalizedSql = normalizeExplainSql(sql);
        if (normalizedSql.isBlank()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("explain " + normalizedSql)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append('\t');
                    }
                    String value = rs.getString(i);
                    row.append(value == null ? "" : value);
                }
                lines.add(row.toString());
            }
        }
        return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
    }

    private static String normalizeExplainSql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        while (normalized.endsWith(";") || normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }
}
