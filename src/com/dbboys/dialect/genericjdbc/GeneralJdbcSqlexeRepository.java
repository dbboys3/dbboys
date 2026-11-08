package com.dbboys.dialect.genericjdbc;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;

import java.sql.Connection;
import java.sql.SQLException;

public final class GeneralJdbcSqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {

    @Override
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateGeneralJdbc(conn, error, sentSql, errorMessage);
    }


    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        try {
            conn.setCatalog(databaseName);
            return;
        } catch (SQLException ignored) {
            // fall through
        }
        try {
            conn.setSchema(databaseName);
        } catch (Throwable ignored) {
            // best effort only
        }
    }
}
