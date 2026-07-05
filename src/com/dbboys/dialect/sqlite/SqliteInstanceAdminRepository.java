package com.dbboys.dialect.sqlite;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.model.Connect;
import com.dbboys.model.SpaceUsage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class SqliteInstanceAdminRepository implements InstanceAdminRepository {

    @Override public boolean supportsAdminFeatures(Connect connect) { return false; }

    @Override public boolean supportsHealthCheck(Connect connect) { return false; }
    @Override public boolean supportsOnlineLog(Connect connect) { return false; }
    @Override public boolean supportsSpaceManager(Connect connect) { return false; }
    @Override public boolean supportsConfigManagement(Connect connect) { return false; }
    @Override public boolean supportsStartStop(Connect connect) { return false; }
    @Override public boolean supportsSpaceMutation(Connect connect) { return false; }

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        throw new UnsupportedOperationException("Storage management is not supported for SQLite");
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        throw new UnsupportedOperationException("Storage management is not supported for SQLite");
    }

    @Override
    public List<List<SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException {
        return List.of();
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) throws SQLException {
        return 0;
    }
}
