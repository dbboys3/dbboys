package com.dbboys.dialect.sqlserver;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqlserverSqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {

    private final SqlserverMetadataRepository metadataRepository;

    public SqlserverSqlexeRepository(SqlserverMetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        metadataRepository.setDatabase(conn, databaseName);
    }

    @Override
    public boolean autoCommitsDdl() {
        return false;
    }

    /**
     * Estimated plan via {@code SET SHOWPLAN_XML ON}: the statement is compiled
     * but not executed, and SQL Server returns the plan as a single-column XML
     * rowset. SHOWPLAN_XML is session-scoped, so it must be switched back off
     * on the same connection even when the statement fails.
     */
    @Override
    public String explain(Connection conn, String sql) throws SQLException {
        if (conn == null || sql == null || sql.isBlank()) {
            return "";
        }
        String normalizedSql = normalizeExplainSql(sql);
        if (normalizedSql.isBlank()) {
            return "";
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET SHOWPLAN_XML ON");
            String plan = null;
            SQLException planError = null;
            try {
                plan = readShowplan(stmt, normalizedSql);
            } catch (SQLException e) {
                planError = e;
            } finally {
                try {
                    stmt.execute("SET SHOWPLAN_XML OFF");
                } catch (SQLException offError) {
                    if (planError == null) {
                        throw offError;
                    }
                    planError.addSuppressed(offError);
                }
            }
            if (planError != null) {
                throw planError;
            }
            return SqlserverShowplanFormatter.format(plan);
        }
    }

    @Override
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateSqlServer(error, sentSql, errorMessage);
    }

    private static String readShowplan(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            StringBuilder plan = new StringBuilder();
            while (rs.next()) {
                String row = rs.getString(1);
                if (row != null) {
                    if (plan.length() > 0) {
                        plan.append(System.lineSeparator());
                    }
                    plan.append(row);
                }
            }
            return plan.toString();
        }
    }

    private static String normalizeExplainSql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }
}
