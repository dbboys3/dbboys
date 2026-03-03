package com.dbboys.service;

import com.dbboys.customnode.CustomSpaceChart;
import com.dbboys.db.AdminRepository;
import com.dbboys.api.ConnectionService;
import com.dbboys.impl.ConnectionServiceImpl;
import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class AdminService {
    private final ConnectionService connectionService;
    private final AdminRepository adminRepository;

    public AdminService() {
        this(new ConnectionServiceImpl(), new AdminRepository());
    }

    public AdminService(ConnectionService connectionService, AdminRepository adminRepository) {
        this.connectionService = connectionService;
        this.adminRepository = adminRepository;
    }

    public void modifyChunkExtendable(Connect connect, int chunkId, boolean toExtendAble) throws Exception {
        Connection conn = connectionService.getConnection(connect);
        connectionService.sessionChangeToGbaseMode(conn);
        modifyChunkExtendable(conn, chunkId, toExtendAble);
        conn.close();
    }

    public void unLimitedSpaceSize(Connect connect, String dbspace) throws Exception {
        Connection conn = connectionService.getConnection(connect);
        connectionService.sessionChangeToGbaseMode(conn);
        modifySpaceSize(conn, dbspace, 10, 10000, 0);
        conn.close();
    }

    public List<List<CustomSpaceChart.SpaceUsage>> getInstanceDbspaceInfo(Connect connect) throws Exception {
        Connection conn = connectionService.getConnection(connect);
        connectionService.sessionChangeToGbaseMode(conn);
        List<List<CustomSpaceChart.SpaceUsage>> result = getInstanceDbspaceInfo(conn);
        conn.close();
        return result;
    }

    public double getMaxDbspaceUsed(Connect connect) throws Exception {
        Connection conn = connectionService.getConnection(connect);
        connectionService.sessionChangeToGbaseMode(conn);
        double result = getMaxDbspaceUsed(conn);
        conn.close();
        return result;
    }

    public void modifyChunkExtendable(Connection conn, int chunkId, boolean toExtendable) throws SQLException {
        adminRepository.modifyChunkExtendable(conn, chunkId, toExtendable);
    }

    public void modifySpaceSize(Connection conn, String dbspace, int size1, int size2, int size3) throws SQLException {
        adminRepository.modifySpaceSize(conn, dbspace, size1, size2, size3);
    }

    public List<List<CustomSpaceChart.SpaceUsage>> getInstanceDbspaceInfo(Connection conn) throws SQLException {
        return adminRepository.getInstanceDbspaceInfo(conn);
    }

    public double getMaxDbspaceUsed(Connection conn) throws SQLException {
        return adminRepository.getMaxDbspaceUsed(conn);
    }
}
