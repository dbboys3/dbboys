package com.dbboys.impl;

import com.dbboys.api.MetadataRepository;
import com.dbboys.api.ConnectionService;
import com.dbboys.api.ConnectionService.ChangeDefaultDatabaseResult;
import com.dbboys.api.ConnectionService.SqlWork;
import com.dbboys.i18n.I18n;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.util.MD5Util;
import com.dbboys.util.tree.TreeViewUtil;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.vo.*;
import javafx.scene.control.TreeItem;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConnectionServiceImpl implements ConnectionService {
    private static final Logger log = LogManager.getLogger(ConnectionServiceImpl.class);
    private static final Map<String, Driver> DRIVER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, URLClassLoader> LOADER_CACHE = new ConcurrentHashMap<>();
    private final MetadataRepository metadataRepository;

    public ConnectionServiceImpl() {
        this(new MetadataRepositoryImpl());
    }

    public ConnectionServiceImpl(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    public Connection createConnection(Connect connect) throws Exception {
        String urlString = null;
        String className = null;
        String jarFilePath = null;

        switch (connect.getDbtype()) {
            case "GBASE 8S":
                if (connect.getPropByName("GBASEDBTSERVER").isEmpty()) {
                    urlString = "jdbc:gbasedbt-sqli://" + connect.getIp() + ":" + connect.getPort() + "/" + connect.getDatabase();
                } else {
                    urlString = "jdbc:gbasedbt-sqli:/" + connect.getDatabase() + ":SQLH_TYPE=FILE;SQLH_FILE=extlib/GBASE 8S/sqlhosts;";
                }
                className = "com.gbasedbt.jdbc.Driver";
                jarFilePath = "file:extlib/" + connect.getDbtype() + "/" + connect.getDriver();
                break;
            case "oracle":
                break;
            default:
                break;
        }

        Driver driver = getOrLoadDriver(className, jarFilePath);
        Properties info = new Properties();
        info.setProperty("user", connect.getUsername());
        info.setProperty("password", connect.getPassword());

        JSONArray jsonArray = new JSONArray(connect.getProps());
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            if (jsonObject.getString("propValue") != null && (!jsonObject.getString("propValue").equals(""))) {
                info.setProperty(jsonObject.getString("propName"), jsonObject.getString("propValue"));
            }
        }

        Connection connection = driver.connect(urlString, info);
        return connection;
    }

    private Driver getOrLoadDriver(String className, String jarFilePath) throws Exception {
        if (className == null || jarFilePath == null) {
            throw new IllegalArgumentException("className/jarFilePath is null");
        }
        String key = className + "|" + jarFilePath;
        Driver cached = DRIVER_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (DRIVER_CACHE) {
            cached = DRIVER_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            URLClassLoader loader = LOADER_CACHE.get(jarFilePath);
            if (loader == null) {
                loader = new URLClassLoader(new URL[]{new URL(jarFilePath)}, ClassLoader.getPlatformClassLoader());
                LOADER_CACHE.put(jarFilePath, loader);
            }
            Driver driver = (Driver) Class.forName(className, true, loader).getDeclaredConstructor().newInstance();
            DRIVER_CACHE.put(key, driver);
            return driver;
        }
    }

    public Connection getGbaseModeConnection(Connect connect) throws Exception {
        Connection conn = createConnection(connect);
        sessionChangeToGbaseMode(conn);
        return conn;
    }

    public Connection getConnection(Connect connect) throws Exception {
        return createConnection(connect);
    }

    public void changeCommitMode(Connection conn, int commitChoiceBoxIndex) throws SQLException {
        if (commitChoiceBoxIndex == 0) {
            conn.setAutoCommit(true);
        } else if (commitChoiceBoxIndex == 1) {
            conn.setAutoCommit(false);
        }
    }

    public void sessionChangeToGbaseMode(Connection conn) {
        try {
            conn.createStatement().execute("set environment sqlmode 'gbase'");
        } catch (SQLException e) {
            // ignore
        }
    }

    public ChangeDefaultDatabaseResult changeDefaultDatabase(Connect connect, Database database) {
        ChangeDefaultDatabaseResult result = new ChangeDefaultDatabaseResult();
        if (connect == null || database == null) {
            result.setSuccess(false);
            return result;
        }
        try {
            metadataRepository.changeDatabase(connect.getConn(), database.getName());
            connect.setDatabase(database.getName());
            if(database.getName().equals("sysmaster")||database.getName().equals("sysadmin")||database.getName().equals("sysutils")||database.getName().equals("syscdcv1")||database.getName().equals("sys")||database.getName().equals("gbasedbt")){
            }else{
                connect.setProps(modifyProps(connect, database.getDbLocale()));

            }
            connect.setProps(modifyProps(connect, database.getDbLocale()));
            LocalDbRepository.updateConnect(connect);
            result.setSuccess(true);
        } catch (SQLException e) {
            if (e.getErrorCode() == -79716 || e.getErrorCode() == -79730) {
                result.setDisconnected(true);
            } else if (e.getErrorCode() == -23197 || e.getErrorCode() == -349) {
                try {
                    connect.getConn().close();
                    connect.setDatabase(database.getName());
                    connect.setProps(modifyProps(connect, database.getDbLocale()));
                    connect.setConn(getConnection(connect));
                    sessionChangeToGbaseMode(connect.getConn());
                    LocalDbRepository.updateConnect(connect);
                    result.setSuccess(true);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            } else {
                result.setErrorCode(e.getErrorCode());
                result.setErrorMessage(e.getMessage());
            }
        }
        return result;
    }

    public String modifyProps(Connect connect, String DBlocale) {
        if (connect == null) {
            return null;
        }
        if (DBlocale == null || DBlocale.trim().isEmpty()) {
            return connect.getProps();
        }
        String dbLocale = DBlocale.replaceAll("(?i)" + "UTF8", "57372")
                .replaceAll("(?i)" + "GB18030-2000", "5488").trim();
        JSONArray jsonArray = new JSONArray(connect.getProps());
        JSONArray jsonArraynew = new JSONArray();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            if (!jsonObject.getString("propName").equals("DB_LOCALE")) {
                jsonArraynew.put(jsonObject);
            }
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("propName", "DB_LOCALE");
        jsonObject.put("propValue", dbLocale);
        jsonArraynew.put(jsonObject);
        return jsonArraynew.toString();
    }

    public String setConnectInfo(Connect connect) throws Exception {
        String primaryInstance = "";
        Connection connection = getConnection(connect);
        sessionChangeToGbaseMode(connection);

        ResultSet rs = null;
        String dbversion = null;
        if (connect.getUsername().equals("gbasedbt")) {
            rs = connection.createStatement().executeQuery("EXECUTE FUNCTION sysadmin:task('onstat','-V');");
            rs.next();
            dbversion = rs.getString(1).replace("GBase Database Server Version 12.10.FC4G1", "")
                    .replace(" Software Serial Number AAA#B000000", "")
                    .replace("\n", "");
            if (!dbversion.contains("GBase8s")) {
                DatabaseMetaData metaData = connection.getMetaData();
                String databaseProductVersion = metaData.getDatabaseProductVersion();
                dbversion = "GBase8sV" + databaseProductVersion + "_" + dbversion;
            }
        } else {
            dbversion = I18n.t("metadata.dbversion.no_permission",
                    "当前用户无权限获取版本信息，请使用gbasedbt用户连接获取\n");
        }
        connect.setDbversion(dbversion);
        String info = "##########################################################################################\n";
        info += "Instance Boot Information\n";
        info += "##########################################################################################\n";
        rs = connection.createStatement().executeQuery("select env_name,trim(env_value) from sysmaster:sysenv");

        while (rs.next()) {
            info += String.format("%-30s", rs.getString(1)) + rs.getString(2) + "\n";
            if (rs.getString(1).equals("DB_LOCALE")) {
                connect.setProps(connect.getProps().replace("{\"propValue\":\"\",\"propName\":\"DB_LOCALE\"}",
                        "{\"propValue\":\"" + rs.getString(2).toUpperCase().trim()
                                .replace("ZH_CN.GB18030-2000", "zh_CN.5488")
                                .replace("ZH_CN.UTF8", "zh_CN.57372")
                                + "\",\"propName\":\"DB_LOCALE\"}"));
                connect.setProps(connect.getProps().replace("{\"propName\":\"DB_LOCALE\",\"propValue\":\"\"}",
                        "{\"propName\":\"DB_LOCALE\",\"propValue\":\"" + rs.getString(2).toUpperCase().trim()
                                .replace("ZH_CN.GB18030-2000", "zh_CN.5488")
                                .replace("ZH_CN.UTF8", "zh_CN.57372")
                                + "\"}"));
            }
        }
        rs.close();
        info += "\n##########################################################################################\n";
        info += "System Information\n";
        info += "##########################################################################################\n";
        rs = connection.createStatement().executeQuery("SELECT * from sysmaster:sysmachineinfo ");
        rs.next();
        for (int i = 1; i <= 24; i++) {
            info += String.format("%-30s", rs.getMetaData().getColumnName(i));
            info += rs.getString(i) + "\n";
        }
        rs.close();

        if (!connect.getPropByName("GBASEDBTSERVER").isEmpty()) {
            rs = connection.createStatement().executeQuery("select dbservername from dual");
            if (rs.next()) {
                primaryInstance = rs.getString(1);
            }
        }
        rs.close();
        connection.close();
        connect.setInfo(info);

        connect.setDrivermd5(MD5Util.getMD5Checksum(Paths.get("extlib/" + connect.getDbtype() + "/" + connect.getDriver()).toFile().getAbsolutePath()));
        return primaryInstance;
    }

    public Boolean testConn(Connect connect) {
        Boolean result = false;
        ResultSet rs = null;
        if (connect.getConn() != null) {
            try {
                rs = connect.getConn().createStatement().executeQuery("select first 1 tabid from systables");
                result = true;
            } catch (SQLException e) {
                log.error("Operation failed", e);
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (SQLException e) {
                        rs = null;
                    }
                }
            }
        }
        return result;
    }

    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T value) throws Exception;
    }

    private static class ConnectionLease {
        private final Connection conn;
        private final boolean shouldClose;

        private ConnectionLease(Connection conn, boolean shouldClose) {
            this.conn = conn;
            this.shouldClose = shouldClose;
        }
    }

    private ConnectionLease acquireConnection(Connect connect, Database database) throws Exception {
        Connection conn = connect.getConn();
        try {
            metadataRepository.setDatabase(conn, database.getName());
            return new ConnectionLease(conn, false);
        } catch (SQLException e) {
            if (e.getErrorCode() == -23197) {
                metadataRepository.setDatabase(connect.getConn(), "sysmaster");
                Connect connect1 = new Connect(connect);
                connect1.setDatabase(database.getName());
                connect1.setProps(modifyProps(connect1, database.getDbLocale()));
                Connection newConn = getConnection(connect1);
                sessionChangeToGbaseMode(newConn);
                metadataRepository.setDatabase(newConn, database.getName());
                return new ConnectionLease(newConn, true);
            }
            throw e;
        }
    }

    public <T> T withMetaSession(Connect connect, Database database, SqlWork<T> action) throws Exception {
        if (connect == null) {
            return null;
        }
        if (database == null) {
            return action.apply(connect.getConn());
        }
        ConnectionLease lease = acquireConnection(connect, database);
        try {
            return action.apply(lease.conn);
        } finally {
            if (lease.shouldClose) {
                lease.conn.close();
            }
        }
    }
}
