package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.SqlexeRepository;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Oracle SQL 执行占位实现，所有方法暂抛 UnsupportedOperationException。
 */
public final class OracleSqlexeRepository implements SqlexeRepository {

    private static final String MSG = "Oracle SqlexeRepository not implemented";

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public boolean autoCommitsDdl() {
        return true;
    }
}
