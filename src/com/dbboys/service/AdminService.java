package com.dbboys.service;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.app.AppContext;
import com.dbboys.customnode.CustomSpaceChart;
import com.dbboys.api.ConnectionService;
import com.dbboys.impl.ConnectionServiceImpl;
import com.dbboys.impl.DialectServices;
import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class AdminService {
    private final ConnectionService connectionService;
    private final DatabasePlatformResolver platformResolver;

    public AdminService() {
        this(resolveConnectionService(), resolvePlatformResolver());
    }

    public AdminService(ConnectionService connectionService, DatabasePlatformResolver platformResolver) {
        this.connectionService = connectionService;
        this.platformResolver = platformResolver;
    }

    public void setStorageSegmentExtendable(Connect connect, int segmentId, boolean extendable) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        setStorageSegmentExtendable(platformResolver.admin(connect), conn, segmentId, extendable);
        conn.close();
    }

    public void removeStorageSpaceLimit(Connect connect, String storageSpaceName) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        resizeStorageSpace(platformResolver.admin(connect), conn, storageSpaceName, 10, 10000, 0);
        conn.close();
    }

    public List<List<CustomSpaceChart.SpaceUsage>> getStorageSpaceUsage(Connect connect) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        List<List<CustomSpaceChart.SpaceUsage>> result = getStorageSpaceUsage(platformResolver.admin(connect), conn);
        conn.close();
        return result;
    }

    public double getMaxStorageSpaceUsage(Connect connect) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        double result = getMaxStorageSpaceUsage(platformResolver.admin(connect), conn);
        conn.close();
        return result;
    }

    private void setStorageSegmentExtendable(InstanceAdminRepository adminRepository, Connection conn, int segmentId, boolean extendable) throws SQLException {
        adminRepository.setStorageSegmentExtendable(conn, segmentId, extendable);
    }

    private void resizeStorageSpace(InstanceAdminRepository adminRepository, Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        adminRepository.resizeStorageSpace(conn, storageSpaceName, size1, size2, size3);
    }

    private List<List<CustomSpaceChart.SpaceUsage>> getStorageSpaceUsage(InstanceAdminRepository adminRepository, Connection conn) throws SQLException {
        return adminRepository.getStorageSpaceUsage(conn);
    }

    private double getMaxStorageSpaceUsage(InstanceAdminRepository adminRepository, Connection conn) throws SQLException {
        return adminRepository.getMaxStorageSpaceUsage(conn);
    }

    private static ConnectionService resolveConnectionService() {
        try {
            return AppContext.get(ConnectionService.class);
        } catch (IllegalStateException e) {
            return new ConnectionServiceImpl();
        }
    }

    private static DatabasePlatformResolver resolvePlatformResolver() {
        try {
            return AppContext.get(DatabasePlatformResolver.class);
        } catch (IllegalStateException e) {
            return DialectServices.createDefault();
        }
    }
}
