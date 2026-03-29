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
 * 一种数据库类型的「全家桶」策略：与 {@link com.dbboys.vo.Connect#getDbtype()} 一一对应，
 * 内含建连、（可选）会话初始化、元数据访问、SQL/切库执行等；注册到 {@link com.dbboys.impl.dialect.DatabaseDialectRegistry} 后即参与多库路由。
 */
public interface DatabaseDialect {

    /**
     * 本方言对应的数据库类型标识，与 {@link Connect#getDbtype()} 一致，如 "GBASE 8S"、"oracle"。
     */
    String getDbType();

    /**
     * 返回该库的 JDBC 连接参数，由 {@link ConnectionService} 实现类用于加载驱动并建连。
     */
    ConnectionParams getConnectionParams(Connect connect) throws Exception;

    /**
     * 在已建立的连接上做会话级初始化（如 GBase 的 sqlmode、Oracle 的 schema）。
     * 不支持时可空实现。
     */
    void sessionInit(Connection conn, Connect connect) throws Exception;

    /**
     * 是否支持会话初始化。若为 false，{@link ConnectionService#getConnectionWithSessionInit} 等价于普通建连。
     */
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

    default boolean supportsNamedServerConnection() {
        return false;
    }

    /**
     * 用于检测连接是否可用的简单查询（单行结果即可）。
     */
    default String testConnectionSql() {
        return "SELECT 1";
    }

    /**
     * 执行 {@link #testConnectionSql()} 判断连接是否可用。
     */
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

    /**
     * 登录成功后填充版本、实例信息等；返回主实例名等方言相关字段（无则空串）。
     * 默认使用 {@link DatabaseMetaData}；GBase 等可覆盖。
     */
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

    /**
     * 切换当前库失败时，对 JDBC 异常分类（断连/需重建连接/其它）。
     */
    default ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        return ChangeDatabaseFailureKind.OTHER;
    }

    /**
     * 在 {@link #classifyChangeDatabaseFailure} 为 {@link ChangeDatabaseFailureKind#RETRY_WITH_NEW_CONNECTION} 时，
     * 元数据层可先切到该库再重连，如 GBase 的 {@code sysmaster}；不需要则返回 null。
     */
    default String changeDatabaseFallbackCatalogName() {
        return null;
    }

    /**
     * 是否为系统库（切换时通常不调整 DB_LOCALE 等）。
     */
    default boolean isSystemDatabase(String databaseName) {
        return false;
    }

    /**
     * 按属性名和值调整连接属性；默认实现会更新现有属性，若不存在则追加。
     */
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

    /**
     * 该库的元数据访问实现。阶段 2 按 Connect 的 dbtype 通过 Provider 获取。
     */
    com.dbboys.api.MetadataRepository getMetadataRepository();

    /**
     * 该库的 SQL 执行/模式实现（如 setDatabase、getSqlMode）。阶段 2 按 Connect 的 dbtype 通过 Provider 获取。
     */
    com.dbboys.api.SqlexeRepository getSqlexeRepository();

    /**
     * 该库的 DDL 导出实现。
     */
    com.dbboys.api.DdlRepository getDdlRepository();

    /**
     * 该库的实例级管理实现；不支持的数据库可返回抛 UnsupportedOperationException 的实现。
     */
    com.dbboys.api.InstanceAdminRepository getInstanceAdminRepository();

    /**
     * JDBC 连接参数：URL、驱动类名、驱动 jar 路径。
     */
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
