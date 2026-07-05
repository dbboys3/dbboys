package com.dbboys.core;

import com.dbboys.model.Connect;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库平台的连接能力集合：建连参数、默认连接信息、连接属性处理、会话初始化与错误分类。
 */
public interface ConnectionSupport {

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

    // ── 连接模型 / UI 提示 ──

    default ConnectionAddressType connectionAddressType() {
        return ConnectionAddressType.HOST_PORT;
    }

    default boolean supportsCredentials() {
        return true;
    }

    default java.util.List<String> excludedDriverJars() {
        return java.util.List.of();
    }

    default java.util.List<String> fileBrowserExtensions() {
        return java.util.List.of();
    }

    default String fileBrowserExtensionDescription() {
        return "";
    }

    default String fileBrowserDialogTitle() {
        return "";
    }

    default boolean supportsServiceName() {
        return false;
    }

    default String getServiceNameLabelI18nKey() {
        return "createconnect.label.service_name";
    }

    default String getServiceNameLabelDefault() {
        return "服务名";
    }

    default String getServiceNamePromptI18nKey() {
        return "createconnect.prompt.service_name";
    }

    default String getServiceNamePromptDefault() {
        return "数据库服务名，例如 ORCLPDB";
    }

    default String getSessionCatalog(Connect connect) {
        if (connect == null) {
            return "";
        }
        String sessionCatalog = connect.getSessionCatalog();
        if (sessionCatalog != null && !sessionCatalog.isBlank()) {
            return sessionCatalog;
        }
        String catalog = connect.getCatalog();
        return catalog == null ? "" : catalog;
    }

    default void setSessionCatalog(Connect connect, String catalogName) {
        if (connect == null) {
            return;
        }
        connect.setSessionCatalog(catalogName);
        connect.setCatalog(catalogName);
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

    default ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        return ChangeDatabaseFailureKind.OTHER;
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

    final class ConnectionParams {
        private final String url;
        private final String driverClassName;
        private final String jarFilePath;
        private final java.util.List<String> extraJarPaths;

        public ConnectionParams(String url, String driverClassName, String jarFilePath) {
            this(url, driverClassName, jarFilePath, java.util.List.of());
        }

        public ConnectionParams(String url, String driverClassName, String jarFilePath, java.util.List<String> extraJarPaths) {
            this.url = url;
            this.driverClassName = driverClassName;
            this.jarFilePath = jarFilePath;
            this.extraJarPaths = extraJarPaths != null ? extraJarPaths : java.util.List.of();
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

        public java.util.List<String> getExtraJarPaths() {
            return extraJarPaths;
        }
    }
}
