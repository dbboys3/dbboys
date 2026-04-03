package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.customnode.CustomSpaceChart;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Oracle 实例管理占位实现。
 */
public final class OracleInstanceAdminRepository implements InstanceAdminRepository {

    private static final String MSG = "Oracle instance admin repository not implemented";

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<List<CustomSpaceChart.SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }
}
