package com.dbboys.dialect.db2;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.model.SpaceUsage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Instance management is intentionally not implemented for Db2. */
public final class Db2InstanceAdminRepository implements InstanceAdminRepository {

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        throw new UnsupportedOperationException("Storage management is not supported for Db2");
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        throw new UnsupportedOperationException("Storage management is not supported for Db2");
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
