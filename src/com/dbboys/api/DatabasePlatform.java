package com.dbboys.api;

import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    record IconInfo(String svgPath, double scaleX, double scaleY) {}

    default IconInfo iconInfo() {
        return null;
    }

    default boolean usesSchemaModel() {
        return false;
    }

    default boolean canCreateDatabase() {
        return true;
    }

    default String getCreateDatabaseMenuI18nKey() {
        return "metadata.menu.create_database";
    }

    default String getCreateDatabaseMenuDefaultText() {
        return "新建数据库";
    }

    default String getImportDdlDataMenuI18nKey() {
        return "metadata.menu.import_ddl_data";
    }

    default String getImportDdlDataMenuDefaultText() {
        return "导入数据库";
    }

    default String getExportDdlDataMenuI18nKey() {
        return "metadata.menu.export_ddl_data";
    }

    default String getExportDdlDataMenuDefaultText() {
        return "导出数据库";
    }

    default String buildBootstrapSql(Database database) {
        return "";
    }

    default boolean canDropDatabase() {
        return true;
    }

    default Set<String> systemDatabaseNames() {
        return Set.of();
    }

    record TooltipField(String label, String propertyName) {}

    List<TooltipField> DEFAULT_DATABASE_TOOLTIP_FIELDS = List.of(
            new TooltipField("DATABASE", "name"),
            new TooltipField("OWNER",    "dbOwner"),
            new TooltipField("LOG TYPE", "dbLog"),
            new TooltipField("DBSPACE",  "dbSpace"),
            new TooltipField("DBSIZE",   "dbSize"),
            new TooltipField("CREATED",  "dbCreated"),
            new TooltipField("CHARSET",  "dbLocale"),
            new TooltipField("USEGLU",   "dbUseGLU")
    );

    default List<TooltipField> databaseTooltipFields() {
        return DEFAULT_DATABASE_TOOLTIP_FIELDS;
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
