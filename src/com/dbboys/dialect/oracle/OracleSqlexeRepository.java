package com.dbboys.dialect.oracle;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class OracleSqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {
    private static final String SQL_EXPLAIN_PLAN_PREFIX = "EXPLAIN PLAN SET STATEMENT_ID = '";
    private static final String SQL_EXPLAIN_PLAN_MIDDLE = "' FOR ";
    private static final String SQL_DISPLAY_PLAN_PREFIX =
            "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, '";
    private static final String SQL_DISPLAY_PLAN_SUFFIX = "', 'TYPICAL'))";

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        String quotedSchema = "\"" + databaseName.replace("\"", "\"\"") + "\"";
        try (var stmt = conn.createStatement()) {
            stmt.execute("alter session set current_schema = " + quotedSchema);
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

        String statementId = "DBBOYS_" + Long.toHexString(System.nanoTime()).toUpperCase();
        String escapedStatementId = statementId.replace("'", "''");
        String explainSql = SQL_EXPLAIN_PLAN_PREFIX + escapedStatementId + SQL_EXPLAIN_PLAN_MIDDLE + normalizedSql;
        String displaySql = SQL_DISPLAY_PLAN_PREFIX + escapedStatementId + SQL_DISPLAY_PLAN_SUFFIX;

        try (var explainStmt = conn.createStatement()) {
            explainStmt.execute(explainSql);
        }

        List<String> planLines = new ArrayList<>();
        try (var displayStmt = conn.createStatement();
             ResultSet rs = displayStmt.executeQuery(displaySql)) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line != null) {
                    planLines.add(line);
                }
            }
        }

        if (planLines.isEmpty()) {
            return null;
        }
        return String.join(System.lineSeparator(), planLines);
    }

    @Override
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateOracle(error, sentSql, errorMessage);
    }

    private static String normalizeExplainSql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        while (normalized.endsWith(";") || normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }
}
