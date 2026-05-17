package com.dbboys.impl.dialect.mysql;

import com.dbboys.api.SqlexeRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public final class MysqlSqlexeRepository implements SqlexeRepository {

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        conn.setCatalog(databaseName.trim());
    }

    @Override
    public boolean autoCommitsDdl() {
        return true;
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
             ResultSet rs = stmt.executeQuery("EXPLAIN FORMAT=JSON " + normalizedSql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("EXPLAIN " + normalizedSql)) {
                return readResultRows(rs);
            }
        }
    }

    @Override
    public boolean requiresSessionRecovery(SQLException e) {
        if (e == null) {
            return false;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("08");
    }

    @Override
    public void recoverSession(Connection conn, String databaseName) throws SQLException {
        setDatabase(conn, databaseName);
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
