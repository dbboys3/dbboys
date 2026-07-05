package com.dbboys.dialect.sqlite;

import com.dbboys.core.SqlexeRepository;
import java.sql.Connection;
import java.sql.SQLException;

public final class SqliteSqlexeRepository implements SqlexeRepository {

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        // SQLite is single-file, no-op
    }
}
