package com.dbboys.api;

import com.dbboys.customnode.CustomSpaceChart;
import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 实例级管理能力，如空间信息与扩容策略。
 */
public interface InstanceAdminRepository {

    default boolean supportsAdminFeatures(Connect connect) {
        return false;
    }

    void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException;

    void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException;

    List<List<CustomSpaceChart.SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException;

    double getMaxStorageSpaceUsage(Connection conn) throws SQLException;
}
