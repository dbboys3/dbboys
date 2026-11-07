package com.dbboys.dialect.db2;

import com.dbboys.core.SqlexeRepository;

import java.sql.Connection;
import java.sql.SQLException;

public final class Db2SqlexeRepository implements SqlexeRepository {

    private final Db2MetadataRepository metadataRepository;

    public Db2SqlexeRepository(Db2MetadataRepository metadataRepository) {
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
