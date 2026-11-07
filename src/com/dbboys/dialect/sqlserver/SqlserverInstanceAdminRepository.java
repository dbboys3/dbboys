package com.dbboys.dialect.sqlserver;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.model.SpaceUsage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Instance management is intentionally not implemented for SQL Server. */
public final class SqlserverInstanceAdminRepository implements InstanceAdminRepository {

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        throw new UnsupportedOperationException("Storage management is not supported for SQL Server");
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        throw new UnsupportedOperationException("Storage management is not supported for SQL Server");
    }

    @Override
    public List<List<SpaceUsage>> getStorageSpaceUsage(Connection conn) {
        return List.of();
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) {
        return 0;
    }
}
