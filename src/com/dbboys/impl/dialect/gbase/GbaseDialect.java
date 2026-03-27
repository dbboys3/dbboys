package com.dbboys.impl.dialect.gbase;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.api.DatabaseDialect;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.i18n.I18n;
import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GBase 8S 方言：建连 URL/驱动、会话 sqlmode 初始化。
 */
public final class GbaseDialect implements DatabaseDialect {

    private static final String DB_TYPE = "GBASE 8S";
    private static final String DRIVER_CLASS = "com.gbasedbt.jdbc.Driver";

    private final MetadataRepository metadataRepository = new GbaseMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new GbaseSqlexeRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        String url;
        if (connect.getPropByName("GBASEDBTSERVER").isEmpty()) {
            url = "jdbc:gbasedbt-sqli://" + connect.getIp() + ":" + connect.getPort() + "/" + connect.getDatabase();
        } else {
            url = "jdbc:gbasedbt-sqli:/" + connect.getDatabase() + ":SQLH_TYPE=FILE;SQLH_FILE=extlib/GBASE 8S/sqlhosts;";
        }
        String jarFilePath = "file:extlib/" + connect.getDbtype() + "/" + connect.getDriver();
        return new com.dbboys.api.DatabaseDialect.ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        try {
            conn.createStatement().execute("set environment sqlmode 'gbase'");
        } catch (SQLException e) {
            // ignore
        }
    }

    @Override
    public boolean supportsSessionInit() {
        return true;
    }

    @Override
    public String testConnectionSql() {
        return "select first 1 tabid from systables";
    }

    @Override
    public ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        int code = e.getErrorCode();
        if (code == -79716 || code == -79730) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        if (code == -23197 || code == -349) {
            return ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION;
        }
        return ChangeDatabaseFailureKind.OTHER;
    }

    @Override
    public String changeDatabaseFallbackCatalogName() {
        return "sysmaster";
    }

    @Override
    public boolean isSystemDatabase(String databaseName) {
        if (databaseName == null) {
            return false;
        }
        switch (databaseName) {
            case "sysmaster":
            case "sysadmin":
            case "sysutils":
            case "syscdcv1":
            case "sys":
            case "gbasedbt":
                return true;
            default:
                return false;
        }
    }

    @Override
    public String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        String primaryInstance = "";
        ResultSet rs = null;
        String dbversion;
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
            rs.close();
            rs = null;
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
        rs = null;

        if (!connect.getPropByName("GBASEDBTSERVER").isEmpty()) {
            rs = connection.createStatement().executeQuery("select dbservername from dual");
            if (rs.next()) {
                primaryInstance = rs.getString(1);
            }
        }
        if (rs != null) {
            rs.close();
        }
        connect.setInfo(info);
        return primaryInstance;
    }

    /**
     * GBase：按库 locale 调整连接属性 JSON（如 DB_LOCALE 编码映射）。仅在此处实现；
     * {@link com.dbboys.api.ConnectionService#modifyProps} 对非 GBase 直接返回原 props。
     */
    @Override
    public String modifyProps(Connect connect, String dbLocale) {
        if (connect == null) {
            return null;
        }
        if (dbLocale == null || dbLocale.trim().isEmpty()) {
            return connect.getProps();
        }
        String normalized = dbLocale
                .replaceAll("(?i)" + "UTF8", "57372")
                .replaceAll("(?i)" + "GB18030-2000", "5488")
                .trim();
        JSONArray jsonArray = new JSONArray(connect.getProps());
        JSONArray jsonArrayNew = new JSONArray();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            if (!"DB_LOCALE".equals(jsonObject.getString("propName"))) {
                jsonArrayNew.put(jsonObject);
            }
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("propName", "DB_LOCALE");
        jsonObject.put("propValue", normalized);
        jsonArrayNew.put(jsonObject);
        return jsonArrayNew.toString();
    }

    @Override
    public MetadataRepository getMetadataRepository() {
        return metadataRepository;
    }

    @Override
    public SqlexeRepository getSqlexeRepository() {
        return sqlexeRepository;
    }
}
