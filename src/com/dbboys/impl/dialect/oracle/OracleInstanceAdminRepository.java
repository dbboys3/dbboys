package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.customnode.CustomSpaceChart;
import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 实例管理占位实现。
 */
public final class OracleInstanceAdminRepository implements InstanceAdminRepository {

    private static final String MSG = "Oracle instance admin mutation is not implemented";
    /** 表空间「总大小」= 数据文件当前已分配 {@code sum(df.bytes)}，与数据文件图一致；已用 = 已分配 − 空闲 extent。 */
    private static final String SQL_TABLESPACE_USAGE = """
            select
                t.tablespace_name,
                max(case when nvl(df.autoextensible, 'NO') = 'YES' then 1 else 0 end) as autoextendable,
                round(nvl(sum(df.bytes), 0) / power(1024, 3), 2) as total_gb,
                round((nvl(sum(df.bytes), 0) - nvl(max(fs.free_bytes), 0)) / power(1024, 3), 2) as used_gb,
                count(df.file_id) as file_count,
                round(sum(case when nvl(df.autoextensible, 'NO') = 'YES' and df.maxbytes > 0 then df.maxbytes else 0 end)
                      / power(1024, 3), 2) as limit_gb
            from dba_tablespaces t
            left join dba_data_files df
              on df.tablespace_name = t.tablespace_name
            left join (
                select tablespace_name, sum(bytes) as free_bytes
                from dba_free_space
                group by tablespace_name
            ) fs
              on fs.tablespace_name = t.tablespace_name
            where t.contents <> 'UNDO'
            group by t.tablespace_name, t.block_size
            order by 4 desc, 3 desc, 1
            """;
    /**
     * 数据文件图「总容量」用当前已分配 {@code df.bytes}，与表空间图（metrics/sum(bytes)）口径一致。
     * 自动扩展上限放在 {@code limit_gb}（tooltip「限制大小」）。
     */
    private static final String SQL_DATAFILE_USAGE = """
            select
                df.file_id,
                df.file_name,
                df.tablespace_name,
                case when nvl(df.autoextensible, 'NO') = 'YES' then 1 else 0 end as autoextendable,
                round(df.bytes / power(1024, 3), 2) as total_gb,
                round((df.bytes - nvl(fs.free_bytes, 0)) / power(1024, 3), 2) as used_gb,
                nvl(df.blocks, 0) as total_blocks,
                nvl(df.blocks, 0) - nvl(fs.free_blocks, 0) as used_blocks,
                round(case when df.maxbytes > df.bytes then df.maxbytes / power(1024, 3) else 0 end, 2) as limit_gb
            from dba_data_files df
            left join (
                select file_id, sum(bytes) as free_bytes, sum(blocks) as free_blocks
                from dba_free_space
                group by file_id
            ) fs
              on fs.file_id = df.file_id
            order by used_gb desc, df.tablespace_name, df.file_id
            """;
    private static final String SQL_SCHEMA_USAGE = """
            select
                u.username,
                round(nvl(sum(s.bytes), 0) / power(1024, 3), 2) as used_gb
            from dba_users u
            left join dba_segments s
              on s.owner = u.username
            group by u.username
            having nvl(sum(s.bytes), 0) > 0
            order by used_gb desc, u.username
            fetch first 20 rows only
            """;
    private static final String SQL_SEGMENT_USAGE = """
            select
                owner,
                segment_name,
                segment_type,
                round(sum(bytes) / power(1024, 3), 2) as used_gb,
                nvl(sum(blocks), 0) as used_blocks,
                count(*) as extents
            from dba_segments
            where segment_type not in ('ROLLBACK', 'TYPE2 UNDO')
            group by owner, segment_name, segment_type
            having sum(bytes) > 0
            order by used_gb desc, owner, segment_name
            fetch first 20 rows only
            """;
    private static final String SQL_MAX_TABLESPACE_USAGE = """
            select nvl(max(used_percent), 0)
            from dba_tablespace_usage_metrics
            """;

    @Override
    public boolean supportsAdminFeatures(Connect connect) {
        return supportsSpaceManager(connect);
    }

    @Override
    public boolean supportsSpaceManager(Connect connect) {
        return OracleDialect.resolveOracleAdminPrivileges(connect).canViewSpaceManager();
    }

    @Override
    public boolean supportsSpaceMutation(Connect connect) {
        return OracleDialect.resolveOracleAdminPrivileges(connect).canMutateSpace();
    }

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
        List<List<CustomSpaceChart.SpaceUsage>> result = new ArrayList<>();
        result.add(loadTablespaces(conn));
        result.add(loadDatafiles(conn));
        result.add(loadSchemas(conn));
        result.add(loadSegments(conn));
        return result;
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_MAX_TABLESPACE_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getDouble(1);
            }
        }
        return 0;
    }

    private List<CustomSpaceChart.SpaceUsage> loadTablespaces(Connection conn) throws SQLException {
        List<CustomSpaceChart.SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_TABLESPACE_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            int index = 1;
            while (rs.next()) {
                CustomSpaceChart.SpaceUsage usage = new CustomSpaceChart.SpaceUsage(
                        index++,
                        rs.getString("tablespace_name"),
                        rs.getString("tablespace_name"),
                        rs.getInt("autoextendable"),
                        rs.getDouble("total_gb"),
                        rs.getDouble("used_gb"),
                        rs.getInt("file_count"),
                        0,
                        0,
                        0,
                        0
                );
                usage.setLimitSize(rs.getDouble("limit_gb"));
                result.add(usage);
            }
        }
        return result;
    }

    private List<CustomSpaceChart.SpaceUsage> loadDatafiles(Connection conn) throws SQLException {
        List<CustomSpaceChart.SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_DATAFILE_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                CustomSpaceChart.SpaceUsage usage = new CustomSpaceChart.SpaceUsage(
                        rs.getInt("file_id"),
                        rs.getString("file_name") + " [ " + rs.getString("tablespace_name") + " ]",
                        rs.getString("file_name"),
                        rs.getInt("autoextendable"),
                        rs.getDouble("total_gb"),
                        rs.getDouble("used_gb"),
                        0,
                        rs.getInt("total_blocks"),
                        rs.getInt("used_blocks"),
                        0,
                        0
                );
                usage.setLimitSize(rs.getDouble("limit_gb"));
                result.add(usage);
            }
        }
        return result;
    }

    private List<CustomSpaceChart.SpaceUsage> loadSchemas(Connection conn) throws SQLException {
        List<CustomSpaceChart.SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_SCHEMA_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            int index = 1;
            while (rs.next()) {
                result.add(new CustomSpaceChart.SpaceUsage(
                        index++,
                        rs.getString("username"),
                        rs.getString("username"),
                        0,
                        rs.getDouble("used_gb"),
                        rs.getDouble("used_gb"),
                        0,
                        0,
                        0,
                        0,
                        0
                ));
            }
        }
        return result;
    }

    private List<CustomSpaceChart.SpaceUsage> loadSegments(Connection conn) throws SQLException {
        List<CustomSpaceChart.SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_SEGMENT_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            int index = 1;
            while (rs.next()) {
                String owner = rs.getString("owner");
                String segmentName = rs.getString("segment_name");
                String segmentType = rs.getString("segment_type");
                result.add(new CustomSpaceChart.SpaceUsage(
                        index++,
                        owner + "." + segmentName + " [ " + segmentType + " ]",
                        owner + "." + segmentName,
                        0,
                        rs.getDouble("used_gb"),
                        rs.getDouble("used_gb"),
                        rs.getInt("extents"),
                        rs.getInt("used_blocks"),
                        rs.getInt("used_blocks"),
                        0,
                        0
                ));
            }
        }
        return result;
    }
}
