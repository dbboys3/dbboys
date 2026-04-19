package com.dbboys.impl.dialect.genericjdbc;

import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.customnode.CustomSpaceChart;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public final class GeneralJdbcInstanceAdminRepository implements InstanceAdminRepository {

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        throw new UnsupportedOperationException("General JDBC does not support instance storage operations");
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        throw new UnsupportedOperationException("General JDBC does not support instance storage operations");
    }

    @Override
    public List<List<CustomSpaceChart.SpaceUsage>> getStorageSpaceUsage(Connection conn) {
        return List.of();
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) {
        return 0;
    }
}
