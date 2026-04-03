package com.dbboys.api;

import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Optional;

/**
 * 一种数据库类型的完整平台适配器：与 {@link com.dbboys.vo.Connect#getDbtype()} 一一对应，
 * 内含建连、会话初始化、元数据访问、SQL 执行、DDL 导出、实例管理等能力。
 */
public interface DatabasePlatform {

    String getDbType();

    ConnectionSupport connection();

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

    default boolean isSystemDatabase(String databaseName) {
        return false;
    }

    default boolean supportsPackages() {
        return false;
    }

    default List<String> getColumnTypes() {
        return List.of();
    }

    default String getDatabaseFolderI18nKey() {
        return "metadata.folder.databases";
    }

    default String getDatabaseFolderDefaultText() {
        return "数据库";
    }

    default <T> Optional<T> capability(Class<T> type) {
        if (type == null || !type.isInstance(this)) {
            return Optional.empty();
        }
        return Optional.of(type.cast(this));
    }

    MetadataRepository metadata();

    SqlexeRepository sql();

    DdlRepository ddl();

    InstanceAdminRepository admin();
}
