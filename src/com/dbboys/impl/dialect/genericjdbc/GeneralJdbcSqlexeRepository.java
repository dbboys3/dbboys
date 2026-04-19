package com.dbboys.impl.dialect.genericjdbc;

import com.dbboys.api.SqlexeRepository;

import java.sql.Connection;
import java.sql.SQLException;

public final class GeneralJdbcSqlexeRepository implements SqlexeRepository {

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
