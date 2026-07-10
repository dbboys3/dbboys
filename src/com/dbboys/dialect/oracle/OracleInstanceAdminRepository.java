package com.dbboys.dialect.oracle;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.model.SpaceUsage;
import com.dbboys.core.ConnectionServiceImpl;
import com.dbboys.model.Connect;

import java.sql.Connection;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
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
    private static final String SQL_LOCK_SESSIONS = """
            select
                to_char(s.sid) as owner,
                to_char(s.serial#) as serial_no,
                s.username,
                s.machine,
                s.program,
                s.status,
                o.owner as object_owner,
                o.object_name as table_name,
                to_char(lo.locked_mode) as locked_mode,
                s.sql_id
            from v$locked_object lo
            join dba_objects o
              on o.object_id = lo.object_id
            join v$session s
              on s.sid = lo.session_id
            where o.owner = ?
              and o.object_name = ?
            order by s.sid, s.serial#
            """;
    private static final String SQL_SESSION_DETAIL = """
            select to_char(s.sid) as sid,
                   to_char(s.serial#) as serial_no,
                   s.username,
                   s.machine,
                   s.program,
                   s.status,
                   s.event,
                   s.wait_class,
                   s.sql_id,
                   q.sql_fulltext as sql_text,
                   s.prev_sql_id,
                   pq.sql_fulltext as prev_sql_text
            from v$session s
            left join v$sql q
              on q.sql_id = s.sql_id
            left join v$sql pq
              on pq.sql_id = s.prev_sql_id
            where s.sid = ?
            """;
    private static final String SQL_SESSION_SERIAL = "select serial# from v$session where sid = ?";

    @Override
    public boolean supportsAdminFeatures(Connect connect) {
        return supportsSpaceManager(connect);
    }
    @Override
    public boolean supportsStartStop(Connect connect) { return false; }


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
    public List<List<SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException {
        List<List<SpaceUsage>> result = new ArrayList<>();
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

    @Override
    public boolean supportsLockSession(Connect connect) {
        return true;
    }

    @Override
    public LockSessionResult getLockSessions(Connection conn, String databaseName, String tableName) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_LOCK_SESSIONS)) {
            pstmt.setString(1, normalizeOracleName(databaseName));
            pstmt.setString(2, normalizeOracleName(tableName));
            try (ResultSet rs = pstmt.executeQuery()) {
                return toLockSessionResult(rs);
            }
        }
    }

    @Override
    public void killLockSession(Connect connect, String owner) throws Exception {
        validateSid(owner);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(connect);
             PreparedStatement serialStmt = conn.prepareStatement(SQL_SESSION_SERIAL)) {
            serialStmt.setLong(1, Long.parseLong(owner));
            try (ResultSet rs = serialStmt.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Oracle session not found: " + owner);
                }
                String serial = rs.getString(1);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER SYSTEM KILL SESSION '" + owner + "," + serial + "'");
                }
            }
        }
    }

    @Override
    public boolean canKillLockSession(Connect connect) {
        return connect != null;
    }

    @Override
    public String killLockSessionCommand(String owner) {
        validateSid(owner);
        return "ALTER SYSTEM KILL SESSION '" + owner + ",<serial#>'";
    }

    @Override
    public String getLockSessionDetail(Connect connect, String sid) throws Exception {
        validateSid(sid);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(connect);
             PreparedStatement pstmt = conn.prepareStatement(SQL_SESSION_DETAIL)) {
            pstmt.setLong(1, Long.parseLong(sid));
            try (ResultSet rs = pstmt.executeQuery()) {
                return formatResult(toLockSessionResult(rs));
            }
        }
    }

    @Override
    public boolean canShowLockSessionDetail(Connect connect) {
        return connect != null;
    }

    @Override
    public String lockSessionDetailCommand(String sid) {
        validateSid(sid);
        return "SELECT * FROM v$session WHERE sid = " + sid;
    }

    private List<SpaceUsage> loadTablespaces(Connection conn) throws SQLException {
        List<SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_TABLESPACE_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            int index = 1;
            while (rs.next()) {
                SpaceUsage usage = new SpaceUsage(
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

    private List<SpaceUsage> loadDatafiles(Connection conn) throws SQLException {
        List<SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_DATAFILE_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                SpaceUsage usage = new SpaceUsage(
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

    private List<SpaceUsage> loadSchemas(Connection conn) throws SQLException {
        List<SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_SCHEMA_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            int index = 1;
            while (rs.next()) {
                result.add(new SpaceUsage(
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

    private List<SpaceUsage> loadSegments(Connection conn) throws SQLException {
        List<SpaceUsage> result = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(SQL_SEGMENT_USAGE);
             ResultSet rs = pstmt.executeQuery()) {
            int index = 1;
            while (rs.next()) {
                String owner = rs.getString("owner");
                String segmentName = rs.getString("segment_name");
                String segmentType = rs.getString("segment_type");
                result.add(new SpaceUsage(
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

    private static String normalizeOracleName(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static void validateSid(String sid) {
        if (sid == null || !sid.trim().matches("\\d+")) {
            throw new IllegalArgumentException("Invalid Oracle SID: " + sid);
        }
    }

    private static String formatResult(LockSessionResult result) {
        if (result.rows().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> columns = result.columns();
        for (List<String> row : result.rows()) {
            for (int i = 0; i < columns.size(); i++) {
                sb.append(columns.get(i)).append(": ");
                if (i < row.size() && row.get(i) != null) {
                    sb.append(row.get(i));
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static LockSessionResult toLockSessionResult(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            String name = metaData.getColumnLabel(i);
            if (name == null || name.isBlank()) {
                name = metaData.getColumnName(i);
            }
            columns.add(name == null || name.isBlank() ? "COL" + i : name);
        }
        List<List<String>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = resultSet.getObject(i);
                row.add(value == null || resultSet.wasNull() ? null : toDisplayValue(value));
            }
            rows.add(row);
        }
        return new LockSessionResult(columns, rows);
    }

    private static String toDisplayValue(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            long length = clob.length();
            if (length <= 0) {
                return "";
            }
            int readLength = (int) Math.min(length, Integer.MAX_VALUE);
            return clob.getSubString(1, readLength);
        }
        return String.valueOf(value);
    }
}
