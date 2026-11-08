package com.dbboys.dialect.db2;

import com.dbboys.core.SqlErrorPosition;
import com.dbboys.core.SqlErrorPositionCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.dialect.common.ErrorPositionSupport;

import java.sql.Connection;
import java.sql.SQLException;

public final class Db2SqlexeRepository implements SqlexeRepository, SqlErrorPositionCapability {

    private final Db2MetadataRepository metadataRepository;

    public Db2SqlexeRepository(Db2MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    @Override
    public SqlErrorPosition locateErrorPosition(Connection conn, SQLException error, String sentSql, String errorMessage) {
        return ErrorPositionSupport.locateByMessage(sentSql, errorMessage);
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
