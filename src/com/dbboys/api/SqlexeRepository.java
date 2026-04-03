package com.dbboys.api;

import java.sql.Connection;
import java.sql.SQLException;

public interface SqlexeRepository {

    void setDatabase(Connection conn, String databaseName) throws SQLException;

    default boolean autoCommitsDdl() {
        return false;
    }

    default String explain(Connection conn, String sql) throws SQLException {
        throw new UnsupportedOperationException("Explain is not supported");
    }

    default boolean requiresSessionRecovery(SQLException e) {
        return false;
    }

    default void recoverSession(Connection conn, String databaseName) throws SQLException {
        // no-op by default
    }
}
