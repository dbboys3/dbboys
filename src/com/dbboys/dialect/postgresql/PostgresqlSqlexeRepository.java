package com.dbboys.dialect.postgresql;

import com.dbboys.core.SqlexeRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL SQL execution repository.
 * Handles schema switching via {@code SET search_path} and {@code EXPLAIN ANALYZE}.
 */
public final class PostgresqlSqlexeRepository implements SqlexeRepository {

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        String schema = databaseName.replace("\"", "\"\"");
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"" + schema + "\"");
        }
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
        String normalized = sql.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        // Plain EXPLAIN only: EXPLAIN ANALYZE would actually execute DML
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN (FORMAT TEXT) " + normalized)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append("\n");
            }
            return sb.toString();
        }
    }

    @Override
    public boolean requiresSessionRecovery(SQLException e) {
        if (e == null) {
            return false;
        }
        String state = e.getSQLState();
        return state != null && (state.startsWith("08") || "25P01".equals(state));
    }

    @Override
    public void recoverSession(Connection conn, String databaseName) throws SQLException {
        setDatabase(conn, databaseName);
    }
}
