package com.dbboys.impl.dialect.gbase;

import com.dbboys.api.SqlModeCapability;
import com.dbboys.api.SqlexeRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GbaseSqlexeRepository implements SqlexeRepository, SqlModeCapability {
    public static final String SQL_SYS_DUAL = "select  * from sysmaster:sysdual";
    public static final String SQLMODE_GBASE = "set environment sqlmode 'gbase'";
    public static final String SQLMODE_MYSQL = "set environment sqlmode 'mysql'";
    public static final String SQLMODE_ORACLE = "set environment sqlmode 'oracle'";
    public static final String SQL_TABNAMES_GROUP = "select tabid,tabname from systables group by 1";
    public static final String SQL_EXPLAIN = "execute function ifx_explain(?)";

    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        conn.createStatement().executeUpdate("database " + databaseName);
    }


    public List<String> getSqlModes(Connection conn) throws SQLException {
        List<String> sqlmodes = new ArrayList<>();
        ResultSet rs = null;
        java.sql.Statement stmt = null;
        try {
            rs = conn.createStatement().executeQuery(SQL_SYS_DUAL);
            stmt = conn.createStatement();
            stmt.execute(SQLMODE_GBASE);
            sqlmodes.add("sqlmode=gbase");
            try {
                conn.createStatement().executeUpdate(SQLMODE_MYSQL);
                rs = conn.createStatement().executeQuery(SQL_TABNAMES_GROUP);
                conn.createStatement().executeUpdate(SQLMODE_GBASE);
                sqlmodes.add("sqlmode=oracle");
                sqlmodes.add("sqlmode=mysql");
            } catch (SQLException e1) {
                sqlmodes.add("sqlmode=oracle");
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == -201) {
                try {
                    rs = conn.createStatement().executeQuery(SQL_TABNAMES_GROUP);
                    sqlmodes.add("sqlmode=mysql");
                    sqlmodes.add("sqlmode=oracle");
                    sqlmodes.add("sqlmode=mysql");
                } catch (SQLException e1) {
                    sqlmodes.add("sqlmode=oracle");
                    sqlmodes.add("sqlmode=oracle");
                    try {
                        conn.createStatement().executeUpdate(SQLMODE_MYSQL);
                        rs = conn.createStatement().executeQuery(SQL_TABNAMES_GROUP);
                        sqlmodes.add("sqlmode=mysql");
                        conn.createStatement().executeUpdate(SQLMODE_ORACLE);
                    } catch (SQLException e2) {
                        // ignore
                    }
                }
            } else if (e.getErrorCode() == -19840) {
                sqlmodes.add("sqlmode=none");
            } else {
                throw e;
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e1) {
                    // ignore
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e1) {
                    // ignore
                }
            }
        }
        if (!sqlmodes.isEmpty()
                && !"sqlmode=none".equals(sqlmodes.get(0))
                && !sqlmodes.contains("sqlmode=gbase")) {
            sqlmodes.add("sqlmode=gbase");
        }
        return sqlmodes;
    }

    @Override
    public void changeSqlMode(Connection conn, String sqlMode) throws SQLException {
        String sql = switch (sqlMode) {
            case "sqlmode=gbase" -> SQLMODE_GBASE;
            case "sqlmode=oracle" -> SQLMODE_ORACLE;
            case "sqlmode=mysql" -> SQLMODE_MYSQL;
            default -> throw new IllegalArgumentException("Unsupported sql mode: " + sqlMode);
        };
        conn.createStatement().executeUpdate(sql);
    }

    @Override
    public String detectSqlMode(String sql) {
        if (sql == null) {
            return null;
        }
        String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("[ \\t\\r\\n]+", "");
        if (normalized.startsWith("setenvironmentsqlmode'gbase'")) {
            return "sqlmode=gbase";
        }
        if (normalized.startsWith("setenvironmentsqlmode'oracle'")) {
            return "sqlmode=oracle";
        }
        if (normalized.startsWith("setenvironmentsqlmode'mysql'")) {
            return "sqlmode=mysql";
        }
        return null;
    }

    @Override
    public boolean autoCommitsDdl(String sqlMode) {
        return "sqlmode=oracle".equals(sqlMode) || "sqlmode=mysql".equals(sqlMode);
    }

    @Override
    public String explain(Connection conn, String sql) throws SQLException {
        try (java.sql.PreparedStatement stmt = conn.prepareStatement(SQL_EXPLAIN)) {
            stmt.setObject(1, sql);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        }
    }

    @Override
    public boolean requiresSessionRecovery(SQLException e) {
        if (e == null) {
            return false;
        }
        int code = e.getErrorCode();
        return code == -329 || code == -23197 || code == -349;
    }

    @Override
    public void recoverSession(Connection conn, String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isBlank()) {
            return;
        }
        setDatabase(conn, databaseName);
    }
}
