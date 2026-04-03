package com.dbboys.api;

import com.dbboys.vo.Connect;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 一种数据库类型的完整平台适配器：与 {@link com.dbboys.vo.Connect#getDbtype()} 一一对应，
 * 内含建连、会话初始化、元数据访问、SQL 执行、DDL 导出、实例管理等能力。
 */
public interface DatabasePlatform {

    String getDbType();

    ConnectionParams getConnectionParams(Connect connect) throws Exception;

    void sessionInit(Connection conn, Connect connect) throws Exception;

    default boolean supportsSessionInit() {
        return true;
    }

    default String defaultPort() {
        return "";
    }

    default String defaultDatabase() {
        return "";
    }

    default String defaultUsername() {
        return "";
    }

    default String defaultConnectionProps() {
        return "[]";
    }

    default boolean supportsConnectionProperties() {
        String props = defaultConnectionProps();
        return props != null && !props.isBlank() && new JSONArray(props).length() > 0;
    }

    default String testConnectionSql() {
        return "SELECT 1";
    }

    default boolean testConnection(Connection conn) {
        if (conn == null) {
            return false;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(testConnectionSql())) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    default String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        if (connection == null || connect == null) {
            return "";
        }
        DatabaseMetaData metaData = connection.getMetaData();
        String product = metaData.getDatabaseProductName();
        String version = metaData.getDatabaseProductVersion();
        connect.setDbversion((product != null ? product : "") + " " + (version != null ? version : ""));
        connect.setInfo("");
        return "";
    }

    default ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        return ChangeDatabaseFailureKind.OTHER;
    }

    default boolean isSystemDatabase(String databaseName) {
        return false;
    }

    default String modifyProps(Connect connect, String propName, String propValue) {
        if (connect == null) {
            return null;
        }
        if (propName == null || propName.isBlank() || propValue == null) {
            return connect.getProps();
        }

        JSONArray jsonArray = new JSONArray(connect.getProps());
        boolean updated = false;
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            if (propName.equals(jsonObject.optString("propName"))) {
                jsonObject.put("propValue", propValue);
                updated = true;
            }
        }
        if (!updated) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("propName", propName);
            jsonObject.put("propValue", propValue);
            jsonArray.put(jsonObject);
        }
        return jsonArray.toString();
    }

    MetadataRepository getMetadataRepository();

    SqlexeRepository getSqlexeRepository();

    DdlRepository getDdlRepository();

    InstanceAdminRepository getInstanceAdminRepository();

    final class ConnectionParams {
        private final String url;
        private final String driverClassName;
        private final String jarFilePath;

        public ConnectionParams(String url, String driverClassName, String jarFilePath) {
            this.url = url;
            this.driverClassName = driverClassName;
            this.jarFilePath = jarFilePath;
        }

        public String getUrl() {
            return url;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public String getJarFilePath() {
            return jarFilePath;
        }
    }
}
