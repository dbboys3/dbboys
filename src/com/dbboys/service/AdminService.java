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

    public void modifyChunkExtendable(Connect connect, int chunkId, boolean toExtendAble) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        modifyChunkExtendable(platformResolver.admin(connect), conn, chunkId, toExtendAble);
        conn.close();
    }

    public void unLimitedSpaceSize(Connect connect, String dbspace) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        modifySpaceSize(platformResolver.admin(connect), conn, dbspace, 10, 10000, 0);
        conn.close();
    }

    public List<List<CustomSpaceChart.SpaceUsage>> getInstanceDbspaceInfo(Connect connect) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        List<List<CustomSpaceChart.SpaceUsage>> result = getInstanceDbspaceInfo(platformResolver.admin(connect), conn);
        conn.close();
        return result;
    }

    public double getMaxDbspaceUsed(Connect connect) throws Exception {
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        double result = getMaxDbspaceUsed(platformResolver.admin(connect), conn);
        conn.close();
        return result;
    }

    private void modifyChunkExtendable(InstanceAdminRepository adminRepository, Connection conn, int chunkId, boolean toExtendable) throws SQLException {
        adminRepository.modifyChunkExtendable(conn, chunkId, toExtendable);
    }

    private void modifySpaceSize(InstanceAdminRepository adminRepository, Connection conn, String dbspace, int size1, int size2, int size3) throws SQLException {
        adminRepository.modifySpaceSize(conn, dbspace, size1, size2, size3);
    }

    private List<List<CustomSpaceChart.SpaceUsage>> getInstanceDbspaceInfo(InstanceAdminRepository adminRepository, Connection conn) throws SQLException {
        return adminRepository.getInstanceDbspaceInfo(conn);
    }

    private double getMaxDbspaceUsed(InstanceAdminRepository adminRepository, Connection conn) throws SQLException {
        return adminRepository.getMaxDbspaceUsed(conn);
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
