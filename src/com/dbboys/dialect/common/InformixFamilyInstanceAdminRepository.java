package com.dbboys.dialect.common;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.model.SpaceUsage;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.ssh.SshUtil;
import com.dbboys.remote.RemoteSessionClient;
import com.dbboys.model.Connect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared instance-admin implementation for the Informix family of dialects (Informix, GBase 8s).
 * Dialect differences are isolated behind the hook methods below; everything else is verbatim shared.
 */
public abstract class InformixFamilyInstanceAdminRepository implements InstanceAdminRepository {
    private static final String LOCK_SQL = "select first 100 * from sysmaster:syslocks where dbsname = ? and tabname = ?";

    // ------------------------------------------------------------------
    // Dialect hooks
    // ------------------------------------------------------------------

    /** Built-in system owner name ({@code "gbasedbt"} / {@code "informix"}); gates admin features by login user. */
    protected abstract String systemOwnerName();

    /**
     * Dbspace-usage and chunk-usage queries (first and second lists in {@link #getStorageSpaceUsage}),
     * returned as {@code [dbspaceSql, chunkSql]}; built in one call so version detection runs once.
     */
    protected abstract String[] sqlStorageSpaceUsage(Connection conn) throws SQLException;

    /** Query behind {@link #getMaxStorageSpaceUsage}. */
    protected abstract String sqlMaxSpaceUsage(Connection conn) throws SQLException;

    @Override
    public boolean supportsAdminFeatures(Connect connect) {
        return connect != null && systemOwnerName().equalsIgnoreCase(connect.getUsername());
    }

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        String sql = extendable
                ? "EXECUTE FUNCTION sysadmin:task (\"modify chunk extendable on\"," + segmentId + ")"
                : "EXECUTE FUNCTION sysadmin:task (\"modify chunk extendable off\"," + segmentId + ")";
        conn.createStatement().execute(sql);
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        String sql = "EXECUTE FUNCTION sysadmin:task (\"modify space sp_sizes\",\"" + storageSpaceName + "\",\"" + size1 + "\",\"" + size2 + "\",\"" + size3 + "\")";
        conn.createStatement().execute(sql);
    }

    @Override
    public List<List<SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException {
        List<List<SpaceUsage>> result = new ArrayList<>();

        List<SpaceUsage> dbspaceList = new ArrayList<>();
        List<SpaceUsage> chunkList = new ArrayList<>();
        List<SpaceUsage> databaseList = new ArrayList<>();
        List<SpaceUsage> tabList = new ArrayList<>();

        String[] usageSql = sqlStorageSpaceUsage(conn);
        String sql = usageSql[0];
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                Double total = rs.getDouble(5);
                Double metaSize = rs.getDouble(8);
                if (metaSize > 0) {
                    total = metaSize + total;
                }
                SpaceUsage spaceUsage =
                        new SpaceUsage(
                                rs.getInt(1),
                                rs.getString(2),
                                rs.getString(3),   // name
                                rs.getInt(4),
                                total,   //total
                                rs.getDouble(6), //used
                                rs.getInt(7),
                                0, 0, metaSize, rs.getDouble(9)   // total
                        );
                spaceUsage.setLimitSize(rs.getDouble(10));
                dbspaceList.add(spaceUsage);
            }
        }
        result.add(dbspaceList);

        sql = usageSql[1];
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                Double total = rs.getDouble(6);
                Double metaSize = rs.getDouble(11);
                if (metaSize > 0) {
                    total = metaSize + total;
                }
                SpaceUsage spaceUsage =
                        new SpaceUsage(
                                rs.getInt(2),
                                rs.getString(3),
                                rs.getString(4),   // name
                                rs.getInt(5),
                                total,
                                rs.getDouble(7),
                                rs.getInt(8),
                                rs.getInt(9), rs.getInt(10), metaSize, rs.getDouble(12)  // total
                        );
                chunkList.add(spaceUsage);
            }
        }
        result.add(chunkList);

        sql = """
                select trim(dbsname),round(sum(sin.ti_nptotal*sd.pagesize/1024/1024/1024),2) total_size,
                 round(sum(sin.ti_npused*sd.pagesize/1024/1024/1024),2) used_size
                from
                sysmaster:systabnames st JOIN sysmaster:systabinfo sin ON  st.partnum=sin.ti_partnum
                JOIN sysmaster:sysdbspaces sd ON sd.dbsnum = trunc(st.partnum/1048576) and sd.name!=st.dbsname
                where dbsname!='system'
                group by dbsname
                order by total_size desc
                """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                SpaceUsage spaceUsage =
                        new SpaceUsage(0,
                                rs.getString(1),
                                rs.getString(1),   // name
                                0,
                                rs.getDouble(2),   // total
                                rs.getDouble(3),  //used
                                0,
                                0,
                                0, 0, 0  // total
                        );
                databaseList.add(spaceUsage);
            }
        }
        result.add(databaseList);

        sql = """
                select first 20
                sin.ti_nptotal nptotal,trim(st.dbsname)||':'||
                case when trim(st.tabname)=='LO_hdr_partn' or trim(st.tabname)=='LO_ud_free' then
                trim(st.tabname)||'['||st.partnum||']' else trim(st.tabname) end
                ,
                 round(sin.ti_nptotal*sd.pagesize/1024/1024/1024,2) total_size,
                 round(sin.ti_npused*sd.pagesize/1024/1024/1024,2) used_size,
                    sin.ti_nptotal,
                    sin.ti_npdata
                from
                sysmaster:systabnames st JOIN sysmaster:systabinfo sin ON  st.partnum=sin.ti_partnum
                JOIN sysmaster:sysdbspaces sd ON sd.dbsnum = trunc(st.partnum/1048576)
                where sin.ti_nptotal>0
                order by ti_nptotal desc
                """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                SpaceUsage spaceUsage =
                        new SpaceUsage(0,
                                rs.getString(2),
                                rs.getString(2),   // name
                                0,
                                rs.getDouble(3),   // used
                                rs.getDouble(4), 1,
                                rs.getInt(5),
                                rs.getInt(6), 0, 0  // total
                        );
                tabList.add(spaceUsage);
            }
        }
        result.add(tabList);

        return result;
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) throws SQLException {
        String sql = sqlMaxSpaceUsage(conn);
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getDouble(3);
            }
            return 0;
        }
    }

    @Override
    public LockSessionResult getLockSessions(Connection conn, String databaseName, String tableName) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(LOCK_SQL)) {
            statement.setString(1, databaseName);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
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
                        row.add(value == null || resultSet.wasNull() ? null : String.valueOf(value));
                    }
                    rows.add(row);
                }
                return new LockSessionResult(columns, rows);
            }
        }
    }

    @Override
    public void killLockSession(Connect connect, String owner) throws Exception {
        RemoteSessionClient remoteClient = new RemoteSessionClient();
        try {
            remoteClient.connect(connect.getUsername(), connect.getIp(), 22, connect.getPassword(), 5000);
            int status = remoteClient.executeCommandWithExitStatus("source ~/.bash_profile && onmode -z " + owner);
            if (status != 0) {
                throw new Exception(I18n.t("locksession.error.kill_failed", "KILL会话执行失败，退出码：%d").formatted(status));
            }
        } finally {
            remoteClient.disconnect();
        }
    }

    @Override
    public boolean canKillLockSession(Connect connect) {
        return connect != null && systemOwnerName().equalsIgnoreCase(connect.getUsername());
    }

    @Override
    public boolean supportsLockSession(Connect connect) {
        return true;
    }

    @Override
    public String getLockSessionDetail(Connect connect, String sid) throws Exception {
        RemoteSessionClient remoteClient = new RemoteSessionClient();
        try {
            remoteClient.connect(connect.getUsername(), connect.getIp(), 22, connect.getPassword(), 5000);
            String command = remoteCommandPrefix(connect) + "onstat -g ses " + sid;
            return remoteClient.executeCommand(command);
        } finally {
            remoteClient.disconnect();
        }
    }

    @Override
    public boolean canShowLockSessionDetail(Connect connect) {
        return connect != null;
    }

    private String remoteCommandPrefix(Connect connect) {
        String env = connect == null || connect.getInfo() == null ? "" : SshUtil.extractEnvValue(connect.getInfo());
        return env == null || env.isBlank() ? "source ~/.bash_profile && " : env;
    }
}
