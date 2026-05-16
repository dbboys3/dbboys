package com.dbboys.impl.dialect.mysql;

import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.customnode.CustomSpaceChart;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MysqlInstanceAdminRepository implements InstanceAdminRepository {

    @Override
    public boolean supportsSpaceManager(com.dbboys.vo.Connect connect) {
        return true;
    }

    @Override
    public boolean supportsSpaceMutation(com.dbboys.vo.Connect connect) {
        return false;
    }

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) {
        throw new UnsupportedOperationException("MySQL does not support storage segment extend operations");
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) {
        throw new UnsupportedOperationException("MySQL does not support storage space resize operations");
    }

    @Override
    public List<List<CustomSpaceChart.SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException {
        List<CustomSpaceChart.SpaceUsage> databaseUsage = new ArrayList<>();
        String sql = """
                select table_schema,
                       coalesce(sum(data_length), 0) as data_bytes,
                       coalesce(sum(index_length), 0) as index_bytes
                from information_schema.tables
                where table_schema not in ('information_schema', 'mysql', 'performance_schema', 'sys')
                group by table_schema
                order by data_bytes + index_bytes desc
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                double dataMb = bytesToMb(rs.getLong("data_bytes"));
                double indexMb = bytesToMb(rs.getLong("index_bytes"));
                String name = rs.getString("table_schema");
                double used = dataMb + indexMb;
                databaseUsage.add(new CustomSpaceChart.SpaceUsage(
                        0, name, name, 0, used, used, 0, 0, 0, 0, 0));
            }
        }
        return List.of(databaseUsage, List.of(), List.of(), List.of());
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) throws SQLException {
        double max = 0;
        for (CustomSpaceChart.SpaceUsage usage : getStorageSpaceUsage(conn).get(0)) {
            max = Math.max(max, usage.getUsed());
        }
        return max;
    }

    private static double bytesToMb(long bytes) {
        return Math.round(bytes / 1024d / 1024d * 100d) / 100d;
    }
}
