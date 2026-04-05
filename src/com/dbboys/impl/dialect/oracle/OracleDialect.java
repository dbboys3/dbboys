package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.ConnectionSupport;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

/**
 * Oracle 方言占位：建连参数与驱动占位，会话初始化暂不实现。
 */
public final class OracleDialect implements DatabasePlatform, ConnectionSupport {

    private static final String DB_TYPE = "ORACLE";
    private static final String DRIVER_CLASS = "oracle.jdbc.OracleDriver";
    private static final String DEFAULT_CONNECTION_PROPS =
            "[{\"propName\":\"oracle.net.CONNECT_TIMEOUT\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.jdbc.ReadTimeout\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.net.keepAlive\",\"propValue\":\"\"}," +
            "{\"propName\":\"defaultRowPrefetch\",\"propValue\":\"\"}," +
            "{\"propName\":\"defaultBatchValue\",\"propValue\":\"\"}," +
            "{\"propName\":\"remarksReporting\",\"propValue\":\"\"}," +
            "{\"propName\":\"includeSynonyms\",\"propValue\":\"\"}," +
            "{\"propName\":\"defaultNChar\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.jdbc.timezoneAsRegion\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.net.disableOob\",\"propValue\":\"\"}]";

    private final MetadataRepository metadataRepository = new OracleMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new OracleSqlexeRepository();
    private final DdlRepository ddlRepository = new OracleDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new OracleInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.ORACLE_LOGO, 0.55, 0.55);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        if (connect.getSessionDatabase() == null || connect.getSessionDatabase().isBlank()) {
            String username = connect.getUsername();
            if (username != null && !username.isBlank()) {
                connect.setSessionDatabase(username.toUpperCase());
            }
        }
        String host = connect.getIp() != null ? connect.getIp() : "localhost";
        String port = connect.getPort() != null && !connect.getPort().isEmpty() ? connect.getPort() : "1521";
        String database = connect.getDatabase() != null ? connect.getDatabase() : "ORCL";
        String url = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        String jarFilePath = "file:extlib/" + DB_TYPE + "/" + (connect.getDriver() != null && !connect.getDriver().isEmpty() ? connect.getDriver() : "ojdbc8.jar");
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        String sessionDatabase = getSessionDatabase(connect);
        if (sessionDatabase != null && !sessionDatabase.isBlank()) {
            metadataRepository.setDatabase(conn, sessionDatabase);
            connect.setSessionDatabase(sessionDatabase);
        }
    }

    @Override
    public boolean supportsSessionInit() {
        return true;
    }

    @Override
    public String defaultPort() {
        return "1521";
    }

    @Override
    public String defaultDatabase() {
        return "ORCL";
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
    public MetadataRepository metadata() {
        return metadataRepository;
    }

    @Override
    public List<String> getColumnTypes() {
        return List.of(
                "NUMBER", "INTEGER", "DECIMAL", "NUMERIC", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE",
                "CHAR", "VARCHAR2", "NCHAR", "NVARCHAR2",
                "DATE", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE",
                "INTERVAL YEAR TO MONTH", "INTERVAL DAY TO SECOND",
                "RAW", "LONG", "LONG RAW", "CLOB", "NCLOB", "BLOB", "JSON"
        );
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
    public boolean supportsPackages() {
        return true;
    }

    @Override
    public boolean supportsObjectTypesFolder() {
        return true;
    }

    @Override
    public boolean supportsObjectQueuesFolder() {
        return true;
    }

    @Override
    public boolean usesSchemaModel() {
        return true;
    }

    @Override
    public boolean prefersTableCountFromTableListQuery() {
        return true;
    }

    @Override
    public boolean canCreateDatabase() {
        return true;
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
    public String buildBootstrapSql(Database database) {
        if (database == null || database.getName() == null || database.getName().isBlank()) {
            return "";
        }
        String schema = database.getName().trim().toUpperCase();
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        StringBuilder sb = new StringBuilder();
        sb.append("-- ############################################################\n");
        sb.append("-- ### Oracle Schema DDL Export\n");
        sb.append("-- ### Schema   : ").append(schema).append("\n");
        sb.append("-- ### Datetime : ").append(dateStr).append("\n");
        sb.append("-- ############################################################\n\n");
        sb.append("CREATE USER ").append(schema).append(" IDENTIFIED BY ").append(schema).append(";\n\n");
        return sb.toString();
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
        return "alter table " + tableName.trim() + (logging ? " logging" : " nologging");
    }

    @Override
    public boolean supportsSetDefaultDatabase() {
        return false;
    }

    @Override
    public String renameObjectSql(String objectType, String oldName, String newName) {
        return "ALTER " + objectType.toUpperCase() + " " + oldName + " RENAME TO " + newName;
    }

    @Override
    public String dropObjectSql(String objectType, String objectName) {
        if ("user".equalsIgnoreCase(objectType)) {
            return "drop user " + objectName + " cascade";
        }
        return "drop " + objectType + " " + objectName;
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
    public List<TooltipField> databaseTooltipFields() {
        return List.of(
                new TooltipField("SCHEMA",  "name"),
                new TooltipField("OWNER",   "dbOwner"),
                new TooltipField("SERVICE", "dbUseGLU"),
                new TooltipField("SIZE",    "dbSize"),
                new TooltipField("CREATED", "dbCreated"),
                new TooltipField("CHARSET", "dbLocale")
        );
    }

    @Override
    public String getSessionDatabase(Connect connect) {
        if (connect == null) {
            return "";
        }
        String sessionDatabase = connect.getSessionDatabase();
        if (sessionDatabase != null && !sessionDatabase.isBlank()) {
            return sessionDatabase;
        }
        String username = connect.getUsername();
        return username == null ? "" : username;
    }

    @Override
    public void setSessionDatabase(Connect connect, String databaseName) {
        if (connect == null) {
            return;
        }
        connect.setSessionDatabase(databaseName);
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
}
