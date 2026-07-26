package com.dbboys.dialect.dameng;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.ConnectionServiceImpl;
import com.dbboys.model.SpaceUsage;
import com.dbboys.model.Connect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class DamengInstanceAdminRepository implements InstanceAdminRepository {

    @Override
    public boolean supportsAdminFeatures(Connect connect) {
        return true;
    }

    @Override
    public boolean supportsHealthCheck(Connect connect) {
        return true;
    }

    @Override
    public boolean supportsOnlineLog(Connect connect) {
        return true;
    }

    @Override
    public boolean supportsSpaceManager(Connect connect) {
        return true;
    }

    @Override
    public boolean supportsConfigManagement(Connect connect) {
        return true;
    }

    @Override
    public boolean supportsStartStop(Connect connect) {
        return false;
    }

    @Override
    public boolean supportsSpaceMutation(Connect connect) {
        return true;
    }

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        // DM8: ALTER TABLESPACE <ts> DATAFILE '<path>' AUTOEXTEND ON|OFF (no ALTER DATABASE form)
        String sql = "select tablespace_name, file_name from dba_data_files where file_id = " + segmentId;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                String tsName = rs.getString(1);
                String filePath = rs.getString(2);
                String alterSql = "ALTER TABLESPACE \"" + tsName.replace("\"", "\"\"") + "\" DATAFILE '"
                        + filePath.replace("'", "''") + "' AUTOEXTEND " + (extendable ? "ON" : "OFF");
                try (Statement alterStmt = conn.createStatement()) {
                    alterStmt.execute(alterSql);
                }
            }
        }
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        String sql = "select file_name from dba_data_files where tablespace_name = '" + storageSpaceName.replace("'", "''") + "' order by file_id";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                String filePath = rs.getString(1);
                int targetSize = size1 > 0 ? size1 : size2;
                if (targetSize <= 0) { return; }
                // DM8 syntax: ALTER TABLESPACE ts RESIZE DATAFILE 'path' TO <MB> (bare number)
                String alterSql = "ALTER TABLESPACE \"" + storageSpaceName.replace("\"", "\"\"") + "\" RESIZE DATAFILE '"
                        + filePath.replace("'", "''") + "' TO " + targetSize;
                try (Statement alterStmt = conn.createStatement()) {
                    alterStmt.execute(alterSql);
                }
            }
        }
    }

    @Override
    public List<List<SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException {
        List<SpaceUsage> tablespaceUsage = new ArrayList<>();
        String tsSql = """
                SELECT
    a.tablespace_name tablespace_name,
    total::number / (1024 * 1024*1024) total_gb,
    free / (1024 * 1024*1024) free_gb,
    (total - free) / (1024 * 1024*1024) used_gb,
    round((total - free) / total, 4) * 100 as used_percent,
 file_count,
extendable
FROM
    (SELECT tablespace_name, SUM(bytes) free,count(*) file_count FROM dba_free_space GROUP BY tablespace_name) a,
    (SELECT tablespace_name, SUM(bytes) total,max(case when autoextensible='YES' then 1 else 0 end) extendable FROM dba_data_files GROUP BY tablespace_name) b
LEFT JOIN V$TABLESPACE ts ON b.tablespace_name=ts.name

WHERE
    a.tablespace_name = b.tablespace_name
order by ts.id;
                """;
        try (PreparedStatement ps = conn.prepareStatement(tsSql);
             ResultSet rs = ps.executeQuery()) {
            int no = 1;
            while (rs.next()) {
                double total = rs.getDouble("total_gb");
                double used = rs.getDouble("used_gb");
                int extendable = rs.getInt("extendable");
                tablespaceUsage.add(new SpaceUsage(
                        no++, rs.getString("tablespace_name"), rs.getString("tablespace_name"),
                        extendable, total, used, rs.getInt("file_count"), 0, 0, 0, total - used));
            }
        }

        List<SpaceUsage> datafileUsage = new ArrayList<>();
        String dfSql = """
                SELECT
df.group_id file_id,
    ts.NAME AS tablespace_name,
    df.PATH AS file_name,
((df.TOTAL_SIZE) * page_size::number / 1024 / 1024/1024)  AS total_gb,
    ((df.TOTAL_SIZE - df.FREE_SIZE) * page_size::number / 1024 / 1024/1024 )  AS used_gb,
df.auto_extend autoextend
FROM
    V$DATAFILE df
LEFT JOIN V$TABLESPACE ts ON ts.ID = df.GROUP_ID
order by ts.id
                """;
        try (PreparedStatement ps = conn.prepareStatement(dfSql);
             ResultSet rs = ps.executeQuery()) {
            int no = 1;
            while (rs.next()) {
                double total = rs.getDouble("total_gb");
                double used = rs.getDouble("used_gb");
                String fileName = rs.getString("file_name");
                String tsName = rs.getString("tablespace_name");
                // Label follows the Oracle convention (path [ TS ]) so the
                // space-mutation menus (add/drop/autoextend) work unchanged
                SpaceUsage su = new SpaceUsage(
                        rs.getInt("file_id"), fileName + " [ " + tsName + " ]", fileName,
                        rs.getInt("autoextend"),
                        total, used, 0, 0, 0, 0, total - used);
                datafileUsage.add(su);
            }
        }

        List<SpaceUsage> schemaUsage = new ArrayList<>();
        String schemaSql = """
                select owner, round(sum(bytes)/1024/1024/1024,2) total_gb,
                       count(distinct segment_name) seg_count
                from dba_segments
                group by owner order by sum(bytes) desc
                """;
        try (PreparedStatement ps = conn.prepareStatement(schemaSql);
             ResultSet rs = ps.executeQuery()) {
            int no = 1;
            while (rs.next()) {
                double total = rs.getDouble("total_gb");
                schemaUsage.add(new SpaceUsage(
                        no++, rs.getString("owner"), rs.getString("owner"),
                        0, total, total, rs.getInt("seg_count"), 0, 0, 0, 0));
            }
        }

        List<SpaceUsage> tableUsage = new ArrayList<>();
        String tableSql = """
                select owner, segment_name, segment_type,
                       round(sum(bytes)/1024/1024/1024,2) total_gb
                from dba_segments
                group by owner, segment_name, segment_type
                order by sum(bytes) desc
                fetch first 20 rows only
                """;
        try (PreparedStatement ps = conn.prepareStatement(tableSql);
             ResultSet rs = ps.executeQuery()) {
            int no = 1;
            while (rs.next()) {
                double total = rs.getDouble("total_gb");
                String label = rs.getString("owner") + "." + rs.getString("segment_name")
                        + " [" + rs.getString("segment_type") + "]";
                tableUsage.add(new SpaceUsage(
                        no++, label, rs.getString("owner"),
                        0, total, total, 0, 0, 0, 0, 0));
            }
        }

        return List.of(tablespaceUsage, datafileUsage, schemaUsage, tableUsage);
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) throws SQLException {
        String sql = "select round(max(sum(bytes))/1024/1024/1024,2) from dba_segments group by owner";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            double max = 0;
            while (rs.next()) {
                max = Math.max(max, rs.getDouble(1));
            }
            return max;
        }
    }

    @Override
    public boolean supportsLockSession(Connect connect) {
        return true;
    }

    @Override
    public boolean canKillLockSession(Connect connect) {
        return connect != null;
    }

    @Override
    public boolean canShowLockSessionDetail(Connect connect) {
        return connect != null;
    }

    @Override
    public String killLockSessionCommand(String owner) {
        validateOwner(owner);
        return "CALL SP_CLOSE_SESSION(" + owner + ")";
    }

    @Override
    public String lockSessionDetailCommand(String sid) {
        validateOwner(sid);
        return "SELECT * FROM V$SESSIONS WHERE SESS_ID = " + sid;
    }

    @Override
    public String getLockSessionDetail(Connect connect, String sid) throws Exception {
        validateOwner(sid);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(connect);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM V$SESSIONS WHERE SESS_ID = ?")) {
            ps.setString(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "Session not found: " + sid;
                }
                StringBuilder detail = new StringBuilder();
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                for (int i = 1; i <= count; i++) {
                    String label = meta.getColumnLabel(i);
                    String value = rs.getString(i);
                    if (value != null) {
                        detail.append(String.format("%-30s", label)).append(value).append('\n');
                    }
                }
                return detail.toString();
            }
        }
    }

    @Override
    public LockSessionResult getLockSessions(Connection conn, String databaseName, String tableName) throws SQLException {
        String sql;
        if (databaseName != null && !databaseName.isBlank() && tableName != null && !tableName.isBlank()) {
            sql = """
                    select distinct s.sess_id, s.user_name, s.state, s.clnt_host,
                           s.osname, l.blocked,
                           to_char(s.create_time,'yyyy-mm-dd hh24:mi:ss') create_time
                    from v$lock l
                    join v$sessions s on s.trx_id = l.trx_id
                    join sysobjects o on l.table_id = o.id
                    where l.blocked = 1
                      and o.name = '""" + tableName.replace("'", "''") + "'"
                    + " and o.subtype$ = 0";
        } else {
            sql = """
                    select distinct s.sess_id, s.user_name, s.state, s.clnt_host,
                           s.osname, l.blocked,
                           to_char(s.create_time,'yyyy-mm-dd hh24:mi:ss') create_time
                    from v$lock l
                    join v$sessions s on s.trx_id = l.trx_id
                    where l.blocked = 1
                    order by s.sess_id
                    """;
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnLabel(i));
            }
            List<List<String>> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    String val = rs.getString(i);
                    row.add(val == null ? null : val);
                }
                rows.add(row);
            }
            return new LockSessionResult(columns, rows);
        }
    }

    @Override
    public void killLockSession(Connect connect, String owner) throws Exception {
        validateOwner(owner);
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(connect);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CALL SP_CLOSE_SESSION(" + owner + ")");
        }
    }

    private static void validateOwner(String owner) {
        if (owner == null || !owner.trim().matches("\\d+")) {
            throw new IllegalArgumentException("Invalid Dameng session id: " + owner);
        }
    }

    // ==================== Space DDL (DM8 syntax) ====================

    @Override
    public boolean isProtectedTablespace(String tablespaceName) {
        if (tablespaceName == null || tablespaceName.isBlank()) {
            return true;
        }
        String u = tablespaceName.trim().toUpperCase(java.util.Locale.ROOT);
        return "SYSTEM".equals(u) || "RLOG".equals(u) || "ROLL".equals(u) || "TEMP".equals(u)
                || "MAIN".equals(u) || "HMAIN".equals(u);
    }

    private static String quotePath(String datafilePath) {
        return "'" + datafilePath.trim().replace("'", "''") + "'";
    }

    @Override
    public String createTablespaceSql(String tablespaceName, String datafilePath, long sizeMb, boolean autoextendUnlimited) {
        // DM8: SIZE is a bare MB number; MAXSIZE UNLIMITED is allowed
        StringBuilder sql = new StringBuilder("CREATE TABLESPACE ").append(tablespaceName);
        sql.append(" DATAFILE ").append(quotePath(datafilePath));
        sql.append(" SIZE ").append(sizeMb);
        if (autoextendUnlimited) {
            sql.append(" AUTOEXTEND ON NEXT 10 MAXSIZE UNLIMITED");
        }
        return sql.toString();
    }

    @Override
    public String addDatafileSql(String tablespaceName, String datafilePath, long sizeMb, boolean autoextendUnlimited) {
        StringBuilder sql = new StringBuilder("ALTER TABLESPACE ").append(tablespaceName);
        sql.append(" ADD DATAFILE ").append(quotePath(datafilePath));
        sql.append(" SIZE ").append(sizeMb);
        if (autoextendUnlimited) {
            sql.append(" AUTOEXTEND ON NEXT 10 MAXSIZE UNLIMITED");
        }
        return sql.toString();
    }

    @Override
    public String dropTablespaceSql(String tablespaceName, boolean includingContentsAndDatafiles) {
        // DM8 has no INCLUDING CONTENTS clause: only an empty user tablespace can
        // be dropped, and its datafiles are removed together with it
        return "DROP TABLESPACE " + tablespaceName;
    }

    @Override
    public String setDatafileAutoextendSql(String tablespaceName, String datafilePath, boolean enable) {
        // DM8: ALTER TABLESPACE <ts> DATAFILE '<path>' AUTOEXTEND ... (no ALTER DATABASE form)
        return enable
                ? "ALTER TABLESPACE " + tablespaceName + " DATAFILE " + quotePath(datafilePath) + " AUTOEXTEND ON NEXT 10 MAXSIZE UNLIMITED"
                : "ALTER TABLESPACE " + tablespaceName + " DATAFILE " + quotePath(datafilePath) + " AUTOEXTEND OFF";
    }

    @Override
    public String dropDatafileSql(String tablespaceName, String datafilePath) {
        return "ALTER TABLESPACE " + tablespaceName + " DROP DATAFILE " + quotePath(datafilePath);
    }
}