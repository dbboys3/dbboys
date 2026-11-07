package com.dbboys.dialect.sqlserver;

import com.dbboys.core.SqlexeRepository;

import java.sql.Connection;
import java.sql.SQLException;

public final class SqlserverSqlexeRepository implements SqlexeRepository {

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
}
