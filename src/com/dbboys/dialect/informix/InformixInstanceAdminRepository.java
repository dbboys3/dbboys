package com.dbboys.dialect.informix;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.model.SpaceUsage;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.SshUtil;
import com.dbboys.remote.RemoteSessionClient;
import com.dbboys.model.Connect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class InformixInstanceAdminRepository implements InstanceAdminRepository {
    private static final String LOCK_SQL = "select first 100 * from sysmaster:syslocks where dbsname = ? and tabname = ?";

    @Override
    public boolean supportsAdminFeatures(Connect connect) {
        return connect != null && "informix".equalsIgnoreCase(connect.getUsername());
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

        String sql = """
                SELECT
                  A.dbsnum as No,
                  case when is_temp==1 then '[T]' else '' end
                  ||
                  case when is_sbspace==1 then '[S]'
                  when is_blobspace==1 then '[B]'
                  else '' end||
                  case when max_size>0 then '[L]' else '' end||       
                  trim(B.name)||'['||round(A.pagesize/1024)||'k]' as label,trim(B.name) as name,
                  sum(is_extendable),
                  round(sum(case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024,2)  as data_SIZE ,
                  round(sum(case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024,2) as dataused_size,
                  sum(e.extents),
                  round(sum(decode(mdsize,-1,0,mdsize))*2/1024/1024,2)  as Meta_SIZE ,
                  round(sum(decode(mdsize,-1,0,mdsize))*2/1024/1024-sum(decode(mdsize,-1,0,nfree))*2/1024/1024,2) as metaused_size,
                  max(max_size)/1024
                  FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                  left join (select chunk,count(*) as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                  group by 1,2,3
                  order by 1
                """;
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

        sql = """
                SELECT A.dbsnum as No,A.chknum,
                trim(fname)||' [ '||trim(B.name)||' ] ' as label,trim(fname) as filename,
                is_extendable,
                round((case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024,2)  as data_SIZE ,
                round((case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024,2) as dataused_size,
                e.extents,chksize,
                chksize -nfree,
                round((decode(mdsize,-1,0,mdsize))*2/1024/1024,2)  as Meta_SIZE ,
                round((decode(mdsize,-1,0,mdsize))*2/1024/1024-(decode(mdsize,-1,0,nfree))*2/1024/1024,2) as metaused_size
                FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                left join (select chunk,count(*)-1 as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                order by 1,2
                """;
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
        String sql = """
                SELECT first 1
                  trim(B.name) as name,
                  sum(is_extendable),
                  case when
                  round(
                  (sum(case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024)
                           / (sum(case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024)*100,2)
                  >
                  round(sum(decode(mdsize,-1,0,mdsize-nfree))/sum(decode(mdsize,-1,1,mdsize))*100,2)
                  then
                  round(
                  (sum(case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024)
                           / (sum(case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024)*100,2)
                  else round(sum(decode(mdsize,-1,0,mdsize-nfree))/sum(decode(mdsize,-1,1,mdsize))*100,2)
                  end
                  as percent
                  ,sum(e.extents)
                  FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                  left join (select chunk,count(*) as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                  group by 1
                  having sum(is_extendable) =0
                  and sum(e.extents)>0
                  order by percent desc;
                """;
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
        return connect != null && "informix".equalsIgnoreCase(connect.getUsername());
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


