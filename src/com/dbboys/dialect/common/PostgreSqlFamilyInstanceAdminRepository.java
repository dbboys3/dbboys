package com.dbboys.dialect.common;

import com.dbboys.core.ConnectionServiceImpl;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.model.SpaceUsage;
import com.dbboys.model.Connect;
import com.dbboys.infra.db.SqlRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared instance-admin implementation for the PostgreSQL family of dialects.
 * Uses {@code pg_locks} + {@code pg_stat_activity} for lock management,
 * and {@code pg_database_size} / {@code pg_total_relation_size} for space info.
 */
public abstract class PostgreSqlFamilyInstanceAdminRepository implements InstanceAdminRepository {

    protected static final int QUERY_TIMEOUT = 30;

    // ------------------------------------------------------------------
    // Dialect hooks
    // ------------------------------------------------------------------

    /** System owner name (default: "postgres"). */
    protected abstract String systemOwnerName();

    // ------------------------------------------------------------------
    // Lock sessions
    // ------------------------------------------------------------------

    private static final String SQL_LOCK_SESSIONS = """
            SELECT
                l.pid::text AS owner,
                d.datname AS dbsname,
                COALESCE(c.relname, '') AS tabname,
                l.locktype,
                l.mode,
                CASE WHEN l.granted THEN 'GRANTED' ELSE 'WAITING' END AS lock_status,
                a.usename AS username,
                COALESCE(a.client_addr::text, 'local') AS host,
                a.query AS sql_text
            FROM pg_catalog.pg_locks l
            LEFT JOIN pg_catalog.pg_database d ON d.oid = l.database
            LEFT JOIN pg_catalog.pg_class c ON c.oid = l.relation
            LEFT JOIN pg_catalog.pg_stat_activity a ON a.pid = l.pid
            WHERE l.locktype = 'relation'
              AND (? = '' OR c.relname = ?)
            ORDER BY l.pid
            """;

    private static final String SQL_SESSION_DETAIL = """
            SELECT
                pid::text AS id,
                usename AS "user",
                COALESCE(client_addr::text, 'local') AS host,
                datname AS db,
                state AS command,
                COALESCE(EXTRACT(EPOCH FROM (now() - query_start))::text, '0') AS time,
                wait_event_type || '/' || COALESCE(wait_event, '') AS state_col,
                query AS sql_text
            FROM pg_catalog.pg_stat_activity
            WHERE pid = ?
            """;

    @Override
    public boolean supportsAdminFeatures(Connect connect) {
        return connect != null;
    }

    @Override
    public boolean supportsHealthCheck(Connect connect) {
        return true;
    }

    @Override
    public boolean supportsLockSession(Connect connect) {
        return true;
    }

    @Override
    public boolean canKillLockSession(Connect connect) {
        return true;
    }

    @Override
    public boolean canShowLockSessionDetail(Connect connect) {
        return true;
    }

    @Override
    public LockSessionResult getLockSessions(Connection conn, String databaseName, String tableName) throws SQLException {
        List<String> columns = List.of("owner", "dbsname", "tabname", "locktype", "lock_mode",
                "lock_status", "username", "host", "sql_text");
        List<List<String>> rows = new ArrayList<>();
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        String lookupTable = tableName != null ? tableName : "";
        runner.query(SQL_LOCK_SESSIONS, List.of(lookupTable, lookupTable), rs -> {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                String val = rs.getString(i);
                row.add(val == null ? "" : val.trim());
            }
            rows.add(row);
            return null;
        });
        return new LockSessionResult(columns, rows);
    }

    @Override
    public void killLockSession(Connect connect, String owner) throws Exception {
        if (connect == null || owner == null || owner.isBlank()) {
            return;
        }
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(connect)) {
            conn.createStatement().execute("SELECT pg_terminate_backend(" + owner + ")");
        }
    }

    @Override
    public String getLockSessionDetail(Connect connect, String sid) throws Exception {
        if (connect == null || sid == null || sid.isBlank()) {
            return "";
        }
        int pid;
        try {
            pid = Integer.parseInt(sid.trim());
        } catch (NumberFormatException e) {
            return "-- Invalid PID: " + sid;
        }
        try (Connection conn = new ConnectionServiceImpl().getConnectionWithSessionInit(connect)) {
            SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
            return runner.queryOne(SQL_SESSION_DETAIL, List.of(pid), rs -> {
                StringBuilder sb = new StringBuilder();
                sb.append("PID: ").append(rs.getString("id")).append("\n");
                sb.append("User: ").append(blankToEmpty(rs.getString("user"))).append("\n");
                sb.append("Host: ").append(blankToEmpty(rs.getString("host"))).append("\n");
                sb.append("Database: ").append(blankToEmpty(rs.getString("db"))).append("\n");
                sb.append("Command: ").append(blankToEmpty(rs.getString("command"))).append("\n");
                sb.append("Time: ").append(blankToEmpty(rs.getString("time"))).append("s\n");
                sb.append("Wait: ").append(blankToEmpty(rs.getString("state_col"))).append("\n");
                sb.append("SQL: ").append(blankToEmpty(rs.getString("sql_text"))).append("\n");
                return sb.toString();
            });
        }
    }

    @Override
    public String lockSessionDetailCommand(String sid) {
        return "SELECT * FROM pg_stat_activity WHERE pid = " + sid;
    }

    @Override
    public String killLockSessionCommand(String owner) {
        return "SELECT pg_terminate_backend(" + owner + ")";
    }

    // ------------------------------------------------------------------
    // Space usage
    // ------------------------------------------------------------------

    @Override
    public void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException {
        throw new UnsupportedOperationException("Storage segment extendable is not supported for PostgreSQL");
    }

    @Override
    public void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException {
        throw new UnsupportedOperationException("Resize storage space is not supported for PostgreSQL");
    }

    @Override
    public List<List<SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException {
        List<List<SpaceUsage>> result = new ArrayList<>();

        // Level 1: Database sizes
        List<SpaceUsage> dbSpaceList = new ArrayList<>();
        String dbSql = """
                SELECT
                    ROW_NUMBER() OVER (ORDER BY datname)::int AS no,
                    datname AS label,
                    datname AS name,
                    pg_catalog.pg_database_size(datname::text) AS size_bytes
                FROM pg_catalog.pg_database
                WHERE NOT datistemplate
                ORDER BY datname
                """;
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        runner.query(dbSql, null, rs -> {
            double totalGb = rs.getDouble("size_bytes") / 1024.0 / 1024.0 / 1024.0;
            SpaceUsage space = new SpaceUsage(
                    rs.getInt("no"), rs.getString("label"), rs.getString("name"),
                    0, totalGb, totalGb, 0, 0, 0, 0, 0);
            dbSpaceList.add(space);
            return null;
        });
        result.add(dbSpaceList);

        // Level 2: Table/index sizes by schema (top 20)
        List<SpaceUsage> tableList = new ArrayList<>();
        String tableSql = """
                SELECT
                    ROW_NUMBER() OVER (ORDER BY SUM(pg_catalog.pg_total_relation_size(c.oid)) DESC)::int AS no,
                    n.nspname || '.' || c.relname AS label,
                    c.relname AS name,
                    pg_catalog.pg_total_relation_size(c.oid) AS size_bytes
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'i')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_toast%'
                GROUP BY n.nspname, c.relname, c.oid
                ORDER BY SUM(pg_catalog.pg_total_relation_size(c.oid)) DESC
                LIMIT 20
                """;
        runner.query(tableSql, null, rs -> {
            double totalGb = rs.getDouble("size_bytes") / 1024.0 / 1024.0 / 1024.0;
            SpaceUsage space = new SpaceUsage(
                    rs.getInt("no"), rs.getString("label"), rs.getString("name"),
                    0, totalGb, totalGb, 0, 0, 0, 0, 0);
            tableList.add(space);
            return null;
        });
        result.add(tableList);

        return result;
    }

    @Override
    public double getMaxStorageSpaceUsage(Connection conn) throws SQLException {
        String sql = """
                SELECT COALESCE(
                    MAX(pg_catalog.pg_database_size(datname::text)), 0
                ) / 1024.0 / 1024.0 / 1024.0 AS max_gb
                FROM pg_catalog.pg_database
                WHERE NOT datistemplate
                """;
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        Double maxGb = runner.queryOne(sql, null, rs -> rs.getDouble("max_gb"));
        return maxGb == null ? 0.0 : maxGb;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    protected static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
