package com.dbboys.impl.dialect.mysql;

import com.dbboys.api.SqlexeRepository;

import java.sql.Connection;
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
        return "EXPLAIN " + sql.trim();
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
}
