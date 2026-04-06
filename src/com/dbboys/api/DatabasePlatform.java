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

    /** When true, schema/database object tree shows an object types folder (Oracle / GBase 8S). */
    default boolean supportsObjectTypesFolder() {
        return false;
    }

    /** When true, schema/database object tree shows a queues folder (e.g. Oracle Advanced Queuing). */
    default boolean supportsObjectQueuesFolder() {
        return false;
    }

    /** When true, schema tree shows a scheduler jobs folder (e.g. Oracle {@code USER_SCHEDULER_JOBS}). */
    default boolean supportsSchedulerJobsFolder() {
        return false;
    }

    /** When true, schema tree shows a recycle bin folder (e.g. Oracle {@code USER_RECYCLEBIN}). */
    default boolean supportsRecycleBinFolder() {
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

    default String getExportNoticeI18nKey() {
        return "metadata.export.ddl_data.notice.completed";
    }

    default String getExportNoticeDefaultText() {
        return "数据库已导出到：%s";
    }

    default String getExportTaskNameI18nKey() {
        return "metadata.export.ddl_data.task_name";
    }

    default String getExportTaskNameDefaultText() {
        return "导出数据库\"%s\"";
    }

    default String buildBootstrapSql(Database database) {
        return "";
    }

    default String getSystemTableFolderI18nKey() {
        return "metadata.folder.system_table_view";
    }

    default String getSystemTableFolderDefaultText() {
        return "系统表/视图";
    }

    default boolean supportsTableTypeModification() {
        return true;
    }

    default boolean supportsSetDefaultDatabase() {
        return true;
    }

    /**
     * When true, {@link com.dbboys.service.TableService#loadObjects} loads tables via
     * {@link MetadataRepository#getUserTables} first and uses the list size as the folder row count,
     * instead of calling {@link MetadataRepository#getUserTablesCount} (saves one round trip when
     * the list query is always required).
     */
    default boolean prefersTableCountFromTableListQuery() {
        return false;
    }

    /**
     * Oracle-style {@code LOGGING} / {@code NOLOGGING} on heap tables. Unrelated to
     * {@link #supportsTableTypeModification()} (Informix raw/standard).
     */
    default boolean supportsTableLoggingToggle() {
        return false;
    }

    /**
     * @param tableName table identifier for DDL (often schema-qualified)
     * @param logging   {@code true} → {@code LOGGING}, {@code false} → {@code NOLOGGING}
     */
    default String alterTableLoggingSql(String tableName, boolean logging) {
        return null;
    }

    default String renameObjectSql(String objectType, String oldName, String newName) {
        return "rename " + objectType + " " + oldName + " to " + newName;
    }

    default String dropObjectSql(String objectType, String objectName) {
        return "drop " + objectType + " " + objectName;
    }

    default String truncateTableSql(String tableName) {
        return "truncate table " + tableName;
    }

    /**
     * SQL inserted when dragging a non-fragment table from the metadata tree into the SQL editor.
     *
     * @param qualifiedTable identifier as used in FROM (e.g. {@code owner.table} or {@code table})
     */
    default String metadataTreeDragTableSelectSql(String qualifiedTable) {
        return defaultMetadataTreeDragTableSelectSql(qualifiedTable);
    }

    static String defaultMetadataTreeDragTableSelectSql(String qualifiedTable) {
        return "select rowid,* from " + qualifiedTable + ";";
    }

    /**
     * SQL inserted when dragging a fragment table from the metadata tree into the SQL editor.
     */
    default String metadataTreeDragFragmentTableSelectSql(String qualifiedTable) {
        return defaultMetadataTreeDragStarFromSql(qualifiedTable);
    }

    /**
     * SQL inserted when dragging a view from the metadata tree into the SQL editor.
     */
    default String metadataTreeDragViewSelectSql(String qualifiedView) {
        return defaultMetadataTreeDragStarFromSql(qualifiedView);
    }

    static String defaultMetadataTreeDragStarFromSql(String qualified) {
        return "select * from " + qualified + ";";
    }

    default String toggleIndexSql(String indexName, boolean enabled) {
        return "set indexes " + indexName + (enabled ? " enabled" : " disabled");
    }

    default String toggleTriggerSql(String triggerName, boolean enabled) {
        return "set triggers " + triggerName + (enabled ? " enabled" : " disabled");
    }

    default String gatherSchemaSql(String schemaName) {
        return "update statistics";
    }

    default String gatherTableFolderSql(String schemaName) {
        return "update statistics high for table force";
    }

    default String gatherTableSql(String schemaName, String tableName) {
        return "update statistics for table " + tableName;
    }

    default String gatherTableHighSql(String schemaName, String tableName, String indexColumns) {
        return "update statistics high for table " + tableName + "(" + indexColumns + ")";
    }

    default String gatherProcedureFolderSql(String schemaName) {
        return "update statistics for procedure";
    }

    default String gatherProcedureSql(String schemaName, String procedureName) {
        return "update statistics for procedure " + procedureName;
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

    /**
     * English label for schema/catalog in metadata tree object tooltips (table, index, …).
     * Oracle overrides to {@code SCHEMA}; Informix-style platforms keep {@code DATABASE}.
     */
    default String metadataTooltipCatalogLabel() {
        return "DATABASE";
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
