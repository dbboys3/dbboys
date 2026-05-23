package com.dbboys.impl.dialect.dameng;

import com.dbboys.api.ConnectionSupport;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Catalog;
import com.dbboys.vo.Connect;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DamengDialect implements DatabasePlatform, ConnectionSupport {
    private static final String DB_TYPE = "DAMENG";
    private static final String DRIVER_CLASS = "dm.jdbc.driver.DmDriver";
    private static final String DEFAULT_DRIVER = "DmJdbcDriver11.jar";
    private static final String DEFAULT_CONNECTION_PROPS = buildDefaultConnectionProps(
            "schema", "",
            "connectTimeout", "",
            "socketTimeout", ""
    );

    private final MetadataRepository metadataRepository = new DamengMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new DamengSqlexeRepository();
    private final DdlRepository ddlRepository = new DamengDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new DamengInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.DAMENG_LOGO, 0.022, 0.022);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) {
        if (connect != null
                && (connect.getSessionCatalog() == null || connect.getSessionCatalog().isBlank())
                && connect.getUsername() != null
                && !connect.getUsername().isBlank()) {
            connect.setSessionCatalog(connect.getUsername().trim().toUpperCase(Locale.ROOT));
        }
        String host = connect == null || connect.getIp() == null || connect.getIp().isBlank()
                ? "localhost" : connect.getIp().trim();
        String port = connect == null || connect.getPort() == null || connect.getPort().isBlank()
                ? defaultPort() : connect.getPort().trim();
        String url = "jdbc:dm://" + host + ":" + port + "?compatibleMode=oracle";
        String driver = connect == null || connect.getDriver() == null || connect.getDriver().isBlank()
                ? DEFAULT_DRIVER : connect.getDriver().trim();
        String jarFilePath = Path.of("extlib", DB_TYPE, driver).toUri().toString();
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws SQLException {
        if (conn == null || connect == null) {
            return;
        }
        String schema = getSessionCatalog(connect);
        if (schema == null || schema.isBlank()) {
            schema = connect.getUsername();
        }
        if (schema != null && !schema.isBlank()) {
            String normalizedSchema = schema.trim().toUpperCase(Locale.ROOT);
            metadataRepository.setDatabase(conn, normalizedSchema);
            connect.setSessionCatalog(normalizedSchema);
        }
    }

    @Override
    public String defaultPort() {
        return "5236";
    }

    @Override
    public String defaultDatabase() {
        return "DAMENG";
    }

    @Override
    public String defaultConnectionProps() {
        return DEFAULT_CONNECTION_PROPS;
    }

    @Override
    public String testConnectionSql() {
        return "SELECT 1 FROM DUAL";
    }

    @Override
    public String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        if (connection == null || connect == null) {
            return "";
        }
        DatabaseMetaData metaData = connection.getMetaData();
        StringBuilder info = new StringBuilder();
        appendInfoLine(info, "product_name", metaData.getDatabaseProductName());
        appendInfoLine(info, "product_version", metaData.getDatabaseProductVersion());
        appendInfoLine(info, "driver_name", metaData.getDriverName());
        appendInfoLine(info, "driver_version", metaData.getDriverVersion());
        appendInfoLine(info, "current_schema", currentSchema(connection));
        connect.setDbversion(trimToEmpty(metaData.getDatabaseProductName()) + " " + trimToEmpty(metaData.getDatabaseProductVersion()));
        connect.setInfo(info.toString());
        return "";
    }

    @Override
    public boolean usesSchemaModel() {
        return true;
    }

    @Override
    public boolean canCreateDatabase() {
        return true;
    }

    @Override
    public String getDatabaseFolderI18nKey() {
        return "metadata.folder.schemas";
    }

    @Override
    public String getDatabaseFolderDefaultText() {
        return "模式";
    }

    @Override
    public String getCreateDatabaseMenuI18nKey() {
        return "metadata.menu.create_schema";
    }

    @Override
    public String getCreateDatabaseMenuDefaultText() {
        return "新建模式";
    }

    @Override
    public String getImportDdlDataMenuI18nKey() {
        return "metadata.menu.import_ddl_schema";
    }

    @Override
    public String getImportDdlDataMenuDefaultText() {
        return "导入模式";
    }

    @Override
    public String getExportDdlDataMenuI18nKey() {
        return "metadata.menu.export_ddl_schema";
    }

    @Override
    public String getExportDdlDataMenuDefaultText() {
        return "导出模式";
    }

    @Override
    public String getExportNoticeI18nKey() {
        return "metadata.export.ddl_schema.notice.completed";
    }

    @Override
    public String getExportNoticeDefaultText() {
        return "模式已导出到：%s";
    }

    @Override
    public String getExportTaskNameI18nKey() {
        return "metadata.export.ddl_schema.task_name";
    }

    @Override
    public String getExportTaskNameDefaultText() {
        return "导出模式\"%s\"";
    }

    @Override
    public boolean canDropDatabase() {
        return true;
    }

    @Override
    public String buildBootstrapSql(Catalog database) {
        if (database == null || database.getName() == null || database.getName().isBlank()) {
            return "";
        }
        String schema = database.getName().trim().toUpperCase(Locale.ROOT);
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        StringBuilder sb = new StringBuilder();
        sb.append("-- ############################################################\n");
        sb.append("-- ### Dameng Schema DDL Export\n");
        sb.append("-- ### Schema   : ").append(schema).append("\n");
        sb.append("-- ### Datetime : ").append(dateStr).append("\n");
        sb.append("-- ############################################################\n\n");
        sb.append("CREATE USER ").append(schema).append(" IDENTIFIED BY ").append(schema).append(";\n\n");
        return sb.toString();
    }

    @Override
    public String metadataTooltipCatalogLabel() {
        return "SCHEMA";
    }

    @Override
    public boolean supportsPackages() {
        return true;
    }

    @Override
    public boolean supportsObjectTypesFolder() {
        return true;
    }

    @Override
    public boolean supportsObjectQueuesFolder() {
        return false;
    }

    @Override
    public boolean supportsSchedulerJobsFolder() {
        return false;
    }

    @Override
    public boolean supportsRecycleBinFolder() {
        return false;
    }

    @Override
    public boolean prefersTableCountFromTableListQuery() {
        return true;
    }

    @Override
    public boolean supportsTableTypeModification() {
        return false;
    }

    @Override
    public boolean supportsTableLoggingToggle() {
        return true;
    }

    @Override
    public String alterTableLoggingSql(String tableName, boolean logging) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }
        return "alter table " + tableName + (logging ? " logging" : " nologging");
    }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        String q = qualifiedTable == null ? "" : qualifiedTable.trim();
        return "SELECT ROWID, t.* FROM " + q + " t;";
    }

    @Override
    public boolean supportsSetDefaultDatabase() {
        return false;
    }

    @Override
    public boolean supportsRenameDatabaseNode() {
        return false;
    }

    @Override
    public String renameObjectSql(String objectType, String oldName, String newName) {
        return "ALTER " + objectType.toUpperCase(Locale.ROOT) + " " + oldName + " RENAME TO " + newName;
    }

    @Override
    public String dropObjectSql(String objectType, String objectName) {
        if ("user".equalsIgnoreCase(objectType)) {
            return "drop user " + objectName + " cascade";
        }
        return "drop " + objectType + " " + objectName;
    }

    @Override
    public String createUserSql(String userName, String password) {
        String escapedPassword = "\"" + (password == null ? "" : password.trim()).replace("\"", "\"\"") + "\"";
        return "CREATE USER " + userName + " IDENTIFIED BY " + escapedPassword;
    }

    @Override
    public String toggleIndexSql(String indexName, boolean enabled) {
        return enabled ? "ALTER INDEX " + indexName + " REBUILD" : "ALTER INDEX " + indexName + " UNUSABLE";
    }

    @Override
    public String toggleTriggerSql(String triggerName, boolean enabled) {
        return "ALTER TRIGGER " + triggerName + (enabled ? " ENABLE" : " DISABLE");
    }

    @Override
    public String gatherSchemaSql(String schemaName) {
        return "BEGIN DBMS_STATS.GATHER_SCHEMA_STATS(ownname => '" + schemaName + "'); END;";
    }

    @Override
    public String gatherTableFolderSql(String schemaName) {
        return "BEGIN DBMS_STATS.GATHER_SCHEMA_STATS(ownname => '" + schemaName + "'); END;";
    }

    @Override
    public String gatherTableSql(String schemaName, String tableName) {
        return "BEGIN DBMS_STATS.GATHER_TABLE_STATS(ownname => '" + schemaName + "', tabname => '" + tableName + "'); END;";
    }

    @Override
    public String gatherTableHighSql(String schemaName, String tableName, String indexColumns) {
        return gatherTableSql(schemaName, tableName);
    }

    @Override
    public String gatherProcedureFolderSql(String schemaName) {
        return null;
    }

    @Override
    public String gatherProcedureSql(String schemaName, String procedureName) {
        return null;
    }

    @Override
    public String getSystemTableFolderI18nKey() {
        return "metadata.folder.dictionary";
    }

    @Override
    public String getSystemTableFolderDefaultText() {
        return "字典表";
    }

    @Override
    public Set<String> systemDatabaseNames() {
        return Set.of("sys");
    }

    @Override
    public List<String> getColumnTypes() {
        return List.of(
                "CHAR", "VARCHAR", "VARCHAR2", "TEXT", "CLOB",
                "NUMBER", "NUMERIC", "DECIMAL", "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
                "FLOAT", "DOUBLE", "REAL",
                "DATE", "TIME", "TIMESTAMP", "DATETIME",
                "BINARY", "VARBINARY", "BLOB"
        );
    }

    @Override
    public List<TooltipFieldDef> tooltipFields(MetadataObjectType type) {
        if (type == null) {
            return List.of();
        }
        return switch (type) {
            case DATABASE -> List.of(
                    new TooltipFieldDef("SCHEMA", "name"),
                    new TooltipFieldDef("SIZE", "dbSize"),
                    new TooltipFieldDef("CREATED", "dbCreated"),
                    new TooltipFieldDef("CHARSET", "dbLocale")
            );
            case SYS_TABLE, TABLE -> List.of(
                    new TooltipFieldDef("SCHEMA", "tableCatalog"),
                    new TooltipFieldDef("TABLENAME", "name"),
                    new TooltipFieldDef("CREATED", "createTime"),
                    new TooltipFieldDef("TYPE", "tableTypeCode"),
                    new TooltipFieldDef("FRAGMENTED", "isfragment"),
                    new TooltipFieldDef("EXTENTS", "extents"),
                    new TooltipFieldDef("NROWS", "nrows"),
                    new TooltipFieldDef("PAGESIZE", "pagesize"),
                    new TooltipFieldDef("TOTALPAGES", "nptotal"),
                    new TooltipFieldDef("TOTALSIZE", "totalsize"),
                    new TooltipFieldDef("DATAPAGES", "npdata"),
                    new TooltipFieldDef("DATASIZE", "usedsize")
            );
            case VIEW -> List.of(
                    new TooltipFieldDef("SCHEMA", "dbname"),
                    new TooltipFieldDef("VIEWNAME", "name"),
                    new TooltipFieldDef("CREATED", "createTime")
            );
            case TYPE -> List.of(
                    new TooltipFieldDef("SCHEMA", "database"),
                    new TooltipFieldDef("TYPE", "name"),
                    new TooltipFieldDef("KIND", "typeKind")
            );
            case QUEUE -> List.of(
                    new TooltipFieldDef("SCHEMA", "database"),
                    new TooltipFieldDef("QUEUE", "name")
            );
            case FUNCTION -> List.of(
                    new TooltipFieldDef("SCHEMA", "database"),
                    new TooltipFieldDef("FUNCNAME", "name")
            );
            case PROCEDURE -> List.of(
                    new TooltipFieldDef("SCHEMA", "database"),
                    new TooltipFieldDef("PROCNAME", "name")
            );
            case PACKAGE -> List.of(
                    new TooltipFieldDef("SCHEMA", "database"),
                    new TooltipFieldDef("PKGNAME", "name")
            );
            default -> DatabasePlatform.super.tooltipFields(type);
        };
    }

    @Override
    public String getSessionCatalog(Connect connect) {
        if (connect == null) {
            return "";
        }
        String sessionCatalog = connect.getSessionCatalog();
        if (sessionCatalog != null && !sessionCatalog.isBlank()) {
            return sessionCatalog;
        }
        String username = connect.getUsername();
        return username == null ? "" : username;
    }

    @Override
    public void setSessionCatalog(Connect connect, String catalogName) {
        if (connect == null) {
            return;
        }
        connect.setSessionCatalog(catalogName);
    }

    @Override
    public MetadataRepository metadata() {
        return metadataRepository;
    }

    @Override
    public SqlexeRepository sql() {
        return sqlexeRepository;
    }

    @Override
    public DdlRepository ddl() {
        return ddlRepository;
    }

    @Override
    public InstanceAdminRepository admin() {
        return instanceAdminRepository;
    }

    private static String currentSchema(Connection conn) {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema.trim();
            }
        } catch (Exception ignored) {
        }
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select sys_context('USERENV','CURRENT_SCHEMA') from dual")) {
            return rs.next() ? trimToEmpty(rs.getString(1)) : "";
        } catch (SQLException ignored) {
            return "";
        }
    }

    private static void appendInfoLine(StringBuilder info, String label, String value) {
        String normalizedValue = trimToEmpty(value);
        if (info == null || label == null || label.isBlank() || normalizedValue.isEmpty()) {
            return;
        }
        info.append(String.format("%-30s", label.trim())).append(normalizedValue).append("\n");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String buildDefaultConnectionProps(String... keyValues) {
        JSONArray array = new JSONArray();
        if (keyValues == null) {
            return array.toString();
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            JSONObject object = new JSONObject();
            object.put("propName", keyValues[i]);
            object.put("propValue", keyValues[i + 1]);
            array.put(object);
        }
        return array.toString();
    }
}
