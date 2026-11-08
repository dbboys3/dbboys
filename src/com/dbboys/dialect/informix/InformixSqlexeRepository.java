package com.dbboys.dialect.informix;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

public class InformixSqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {
    public static final String SQL_EXPLAIN = "execute function ifx_explain(?)";

    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        conn.createStatement().executeUpdate("database " + databaseName);
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
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateInformixFamily(conn, error, sentSql, errorMessage);
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


