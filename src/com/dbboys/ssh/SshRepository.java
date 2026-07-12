package com.dbboys.ssh;

import com.dbboys.infra.db.LocalDbConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for SSH connection entries in the local SQLite database (t_ssh).
 * Follows the same pattern as {@link com.dbboys.infra.db.LocalDbRepository}.
 *
 * <pre>
 * t_ssh:
 *   c_id              INTEGER PRIMARY KEY AUTOINCREMENT
 *   c_parentid        INTEGER DEFAULT 0
 *   c_name            VARCHAR(100)
 *   c_host            VARCHAR(100)
 *   c_port            VARCHAR(10)
 *   c_username        VARCHAR(100)
 *   c_password        VARCHAR(100)
 *   c_auth_type       VARCHAR(10)   -- 'password' | 'key'
 *   c_key_path        VARCHAR(500)
 *   c_key_passphrase  VARCHAR(100)
 *   c_info            VARCHAR(3200)
 * </pre>
 */
public final class SshRepository {
    private static final Logger log = LogManager.getLogger(SshRepository.class);
    private static final Connection conn = LocalDbConnection.get();

    private SshRepository() {}

    // ---- table DDL ----

    public static void initTable() {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS t_ssh (" +
                "c_id              INTEGER PRIMARY KEY AUTOINCREMENT," +
                "c_parentid        INTEGER DEFAULT 0," +
                "c_name            VARCHAR(100)," +
                "c_host            VARCHAR(100)," +
                "c_port            VARCHAR(10)," +
                "c_username        VARCHAR(100)," +
                "c_password        VARCHAR(100)," +
                "c_auth_type       VARCHAR(10)," +
                "c_key_path        VARCHAR(500)," +
                "c_key_passphrase  VARCHAR(100)," +
                "c_info            VARCHAR(3200))"
            );
        } catch (Exception e) {
            log.error("Failed to create t_ssh table", e);
        }
    }

    private static final String ALL_COLUMNS =
        "c_id, c_parentid, c_name, c_host, c_port, c_username, c_password, " +
        "c_auth_type, c_key_path, c_key_passphrase, c_info";

    private static final String INSERT_SQL =
        "INSERT INTO t_ssh (c_parentid, c_name, c_host, c_port, c_username, c_password, " +
        "c_auth_type, c_key_path, c_key_passphrase, c_info) " +
        "VALUES (?,?,?,?,?,?,?,?,?,?)";

    private static final String UPDATE_SQL =
        "UPDATE t_ssh SET c_parentid=?, c_name=?, c_host=?, c_port=?, c_username=?, c_password=?, " +
        "c_auth_type=?, c_key_path=?, c_key_passphrase=?, c_info=? WHERE c_id=?";

    private static void bindColumnValues(PreparedStatement ps, SshConnect sc) throws Exception {
        ps.setInt(1, sc.getParentId());
        ps.setString(2, sc.getName());
        ps.setString(3, sc.getHost());
        ps.setString(4, sc.getPort());
        ps.setString(5, sc.getUsername());
        ps.setString(6, sc.getPassword());
        ps.setString(7, sc.getAuthType());
        ps.setString(8, sc.getKeyPath());
        ps.setString(9, sc.getKeyPassphrase());
        ps.setString(10, sc.getInfo());
    }

    private static SshConnect mapRow(ResultSet rs) throws Exception {
        SshConnect sc = new SshConnect();
        sc.setId(rs.getInt("c_id"));
        sc.setParentId(rs.getInt("c_parentid"));
        sc.setName(rs.getString("c_name"));
        sc.setHost(rs.getString("c_host"));
        sc.setPort(rs.getString("c_port"));
        sc.setUsername(rs.getString("c_username"));
        sc.setPassword(rs.getString("c_password"));
        sc.setAuthType(rs.getString("c_auth_type") != null ? rs.getString("c_auth_type") : SshConnect.AUTH_PASSWORD);
        sc.setKeyPath(rs.getString("c_key_path") != null ? rs.getString("c_key_path") : "");
        sc.setKeyPassphrase(rs.getString("c_key_passphrase") != null ? rs.getString("c_key_passphrase") : "");
        sc.setInfo(rs.getString("c_info") != null ? rs.getString("c_info") : "");
        return sc;
    }

    // ---- CRUD ----

    public static List<SshConnect> getAll() {
        List<SshConnect> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT " + ALL_COLUMNS + " FROM t_ssh ORDER BY c_name")) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (Exception e) {
            log.error("Failed to load SSH connections", e);
        }
        return list;
    }

    public static String create(SshConnect sc) {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            bindColumnValues(ps, sc);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    sc.setId(keys.getInt(1));
                }
            }
            return "";
        } catch (Exception e) {
            log.error("Failed to create SSH connection", e);
            return e.getMessage();
        }
    }

    public static String update(SshConnect sc) {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_SQL)) {
            bindColumnValues(ps, sc);
            ps.setInt(11, sc.getId());
            ps.executeUpdate();
            return "";
        } catch (Exception e) {
            log.error("Failed to update SSH connection", e);
            return e.getMessage();
        }
    }

    public static boolean delete(SshConnect sc) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM t_ssh WHERE c_id=?")) {
            ps.setInt(1, sc.getId());
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            log.error("Failed to delete SSH connection", e);
            return false;
        }
    }
}
