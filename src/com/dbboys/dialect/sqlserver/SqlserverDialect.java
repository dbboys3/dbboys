package com.dbboys.dialect.sqlserver;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.SqlParser;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.model.Connect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Microsoft SQL Server platform adapter. Connection + metadata + SQL execution
 * only; instance management (install/start/stop) is intentionally not implemented.
 */
public final class SqlserverDialect implements DatabasePlatform, ConnectionSupport,
        com.dbboys.core.ReconnectFallbackCapability {
    private static final String DB_TYPE = "SQLSERVER";
    private static final String DRIVER_CLASS = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    private static final String DEFAULT_DRIVER = "mssql-jdbc-12.8.1.jre11.jar";

    private static final String DEFAULT_CONNECTION_PROPS = """
            [
              {"propName":"encrypt","propValue":"false"},
              {"propName":"trustServerCertificate","propValue":"true"}
            ]
            """;

    private final SqlserverMetadataRepository metadataRepository = new SqlserverMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new SqlserverSqlexeRepository(metadataRepository);
    private final DdlRepository ddlRepository = new SqlserverDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new SqlserverInstanceAdminRepository();

    @Override
    public String getDbType() { return DB_TYPE; }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(com.dbboys.ui.icon.IconPaths.SQLSERVER_LOGO, 0.6, 0.6);
    }

    @Override
    public ConnectionSupport connection() { return this; }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        String database = connect.getCatalog();
        if (database == null || database.isBlank()) {
            database = defaultDatabase();
        }
        String host = connect.getIp() == null || connect.getIp().isBlank() ? "127.0.0.1" : connect.getIp().trim();
        String port = connect.getPort() == null || connect.getPort().isBlank() ? defaultPort() : connect.getPort().trim();
        String url = "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database;
        String driver = connect.getDriver() == null || connect.getDriver().isBlank()
                ? DEFAULT_DRIVER : connect.getDriver().trim();
        String jarFilePath = "file:extlib/" + DB_TYPE + "/" + driver;
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) {
        // Schema selection is implicit (default schema of the login); nothing to do.
    }

    @Override
    public boolean supportsSessionInit() { return false; }

    @Override
    public String defaultPort() { return "1433"; }

    @Override
    public String defaultDatabase() { return "master"; }

    @Override
    public String defaultUsername() { return "sa"; }

    @Override
    public String defaultConnectionProps() { return DEFAULT_CONNECTION_PROPS; }

    @Override
    public String testConnectionSql() { return "SELECT 1"; }

    @Override
    public ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        if (e == null) {
            return ChangeDatabaseFailureKind.OTHER;
        }
        String state = e.getSQLState();
        if ("08S01".equals(state) || (state != null && state.startsWith("08"))) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        if (SqlserverMetadataRepository.RECONNECT_REQUIRED_STATE.equals(state)) {
            return ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION;
        }
        return ChangeDatabaseFailureKind.OTHER;
    }

    // ==================================================================
    // ReconnectFallbackCapability
    // ==================================================================

    @Override
    public String reconnectFallbackDatabaseName() {
        return defaultDatabase();
    }

    @Override
    public String reconnectFallbackDatabaseName(Connect connect) {
        String catalog = connect == null ? null : connect.getCatalog();
        return catalog == null || catalog.isBlank() ? reconnectFallbackDatabaseName() : catalog;
    }

    // ==================================================================
    // DatabasePlatform
    // ==================================================================

    /** 库-模式-表：实例有多个库，库下再挂模式（dbo 等）。 */
    @Override
    public CatalogModel catalogModel() { return CatalogModel.DATABASE_SCHEMA; }

    /** T-SQL 无会话级模式切换：面板下拉框列库，拖模式节点等价于拖所属库。 */
    @Override
    public boolean sqlPanelComboUsesDatabases() { return true; }

    @Override
    public boolean supportsPackages() { return false; }

    @Override
    public boolean supportsSynonymsFolder() { return true; }

    @Override
    public boolean supportsSequencesFolder() { return true; }

    @Override
    public boolean supportsFunctionsFolder() { return true; }

    @Override
    public boolean supportsProceduresFolder() { return true; }

    @Override
    public boolean supportsSystemTablesFolder() { return true; }

    @Override
    public boolean supportsEditableAutoIncrement() { return true; }

    /** T-SQL 无会话级模式切换：DDL 表名必须显式带模式名，否则落到登录默认模式（dbo）。 */
    @Override
    public boolean ddlQualifiesTableWithSchema() { return true; }

    @Override
    public String autoIncrementClause() { return "IDENTITY(1,1)"; }

    @Override
    public boolean createSchemaWithPassword() { return false; }

    @Override
    public boolean canCreateDatabase() { return true; }

    @Override
    public boolean canDropDatabase() { return true; }

    @Override
    public boolean supportsCreateDatabaseStorageSpace() { return false; }

    @Override
    public boolean supportsCreateDatabaseCharset() { return false; }

    @Override
    public String createDatabaseSql(String databaseName, String charsetOption, String storageSpace) {
        String name = databaseName == null ? "" : databaseName.trim();
        if (name.isEmpty()) {
            return null;
        }
        return "CREATE DATABASE [" + name.replace("]", "]]" ) + "]";
    }

    @Override
    public boolean supportsTableTypeModification() { return false; }

    @Override
    public String metadataTooltipCatalogLabel() { return "SCHEMA"; }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        return DatabasePlatform.defaultMetadataTreeDragStarFromSql(qualifiedTable);
    }

    /**
     * 内置模式名（含固定数据库角色对应的同名模式）：导出库时随系统库一并跳过，
     * dbo 不在其中，始终导出。
     */
    private static final Set<String> SYSTEM_SCHEMA_NAMES = Set.of(
            "db_accessadmin", "db_backupoperator", "db_datareader", "db_datawriter",
            "db_ddladmin", "db_denydatareader", "db_denydatawriter", "db_owner",
            "db_securityadmin", "guest", "information_schema", "sys");

    @Override
    public Set<String> systemDatabaseNames() {
        return Set.of("master", "model", "msdb", "tempdb");
    }

    @Override
    public boolean isSystemDatabase(String databaseName) {
        if (databaseName == null) {
            return false;
        }
        String lower = databaseName.trim().toLowerCase();
        return systemDatabaseNames().contains(lower) || SYSTEM_SCHEMA_NAMES.contains(lower);
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        return "EXEC sp_rename N'" + oldName.replace("'", "''") + "', N'" + newName.replace("'", "''") + "', 'OBJECT'";
    }

    @Override
    public String renameColumnSql(String tableName, String oldName, String newName) {
        return "EXEC sp_rename N'" + (tableName + "." + oldName).replace("'", "''")
                + "', N'" + newName.replace("'", "''") + "', 'COLUMN'";
    }

    @Override
    public String renameObjectSql(String objectType, String oldName, String newName) {
        // T-SQL 不支持重命名模式（只能新建后迁移对象），返回 null 由上层提示
        if ("schema".equalsIgnoreCase(objectType)) {
            return null;
        }
        String objectClass = "index".equalsIgnoreCase(objectType) ? "INDEX" : "OBJECT";
        return "EXEC sp_rename N'" + oldName.replace("'", "''") + "', N'" + newName.replace("'", "''")
                + "', '" + objectClass + "'";
    }

    /** 模式是独立对象（非用户=模式模型）。 */
    @Override
    public String schemaNodeObjectType() { return "schema"; }

    /** T-SQL 不支持重命名模式/库（只能新建后迁移），右键不显示重命名。 */
    @Override
    public boolean supportsRenameDatabaseNode() { return false; }

    @Override
    public boolean supportsSchemaImportScript() { return false; }

    @Override
    public boolean supportsDatabaseImportScript() { return true; }

    @Override
    public String getExportDdlDataMenuI18nKey() { return "metadata.menu.export_ddl_schema"; }

    @Override
    public String getExportDdlDataMenuDefaultText() { return "导出模式"; }

    @Override
    public String getExportNoticeI18nKey() { return "metadata.export.ddl_schema.notice.completed"; }

    @Override
    public String getExportNoticeDefaultText() { return "模式已导出到：%s"; }

    @Override
    public String getExportTaskNameI18nKey() { return "metadata.export.ddl_schema.task_name"; }

    @Override
    public String getExportTaskNameDefaultText() { return "导出模式\"%s\""; }

    @Override
    public String dropObjectSql(String objectType, String objectName) {
        String type = objectType == null ? "" : objectType.toUpperCase(java.util.Locale.ROOT);
        return switch (type) {
            case "SCHEMA" -> "DROP SCHEMA " + quoteBracket(objectName);
            case "USER" -> "DROP USER " + quoteBracket(objectName);
            case "DATABASE" -> "DROP DATABASE " + quoteBracket(objectName);
            default -> "DROP " + type + " " + quoteBracket(objectName);
        };
    }

    @Override
    public String renameIndexSql(String indexName, String tableName, String newName) {
        return "EXEC sp_rename N'" + (tableName + "." + indexName).replace("'", "''")
                + "', N'" + newName.replace("'", "''") + "', 'INDEX'";
    }

    /** SQL Server：先建服务器级登录名，再在库内建同名数据库用户（一个批次执行）。 */
    @Override
    public String createUserSql(String userName, String password) {
        String login = quoteBracket(userName);
        return "CREATE LOGIN " + login + " WITH PASSWORD = '" + DatabasePlatform.escapeSqlString(password) + "';\n"
                + "CREATE USER " + login + " FOR LOGIN " + login + ";";
    }

    @Override
    public String resetUserPasswordSql(String userName, String password) {
        return "ALTER LOGIN " + quoteBracket(userName) + " WITH PASSWORD = '" + DatabasePlatform.escapeSqlString(password) + "'";
    }

    /** T-SQL 的 DROP INDEX 必须带 ON <table>。 */
    @Override
    public String dropIndexSql(String indexName, String tableName) {
        return "DROP INDEX " + quoteBracket(indexName) + " ON " + quoteBracket(tableName);
    }

    /** 限定名逐段加括号：dbo.t -> [dbo].[t]；已加括号的原样返回。 */
    private static String quoteBracket(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : value.split("\\.", -1)) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append('[').append(part.replace("]", "]]" )).append(']');
        }
        return sb.toString();
    }

    /** schema 非空且对象名未限定时，拼成 [schema].[object]；否则只加括号。 */
    private static String quoteQualified(String schemaName, String objectName) {
        String object = objectName == null ? "" : objectName.trim();
        if (object.contains(".") || schemaName == null || schemaName.isBlank()) {
            return quoteBracket(object);
        }
        return quoteBracket(schemaName) + "." + quoteBracket(object);
    }

    @Override
    public String dropColumnSql(String tableName, String columnName) {
        return "ALTER TABLE " + tableName + " DROP COLUMN " + columnName;
    }

    @Override
    public String addColumnsSql(String tableName, List<String> columnDefs) {
        if (columnDefs == null || columnDefs.isEmpty()) {
            return "";
        }
        return "ALTER TABLE " + tableName + " ADD " + String.join(", ", columnDefs);
    }

    @Override
    public String dropColumnsSql(String tableName, List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return "";
        }
        return "ALTER TABLE " + tableName + " DROP COLUMN " + String.join(", ", columnNames);
    }

    @Override
    public String modifyColumnsSql(String tableName, List<String> columnDefs) {
        if (columnDefs == null || columnDefs.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        for (String def : columnDefs) {
            if (sql.length() > 0) {
                sql.append(";\n");
            }
            sql.append("ALTER TABLE ").append(tableName).append(" ALTER COLUMN ").append(def);
        }
        return sql.toString();
    }

    @Override
    public String toggleIndexSql(String indexName, boolean enabled) { return null; }

    @Override
    public String toggleTriggerSql(String triggerName, boolean enabled) { return null; }

    @Override
    public String gatherSchemaSql(String schemaName) {
        return "EXEC sp_updatestats";
    }

    @Override
    public String gatherTableFolderSql(String schemaName) {
        return "EXEC sp_updatestats";
    }

    @Override
    public String gatherTableSql(String schemaName, String tableName) {
        return "UPDATE STATISTICS " + quoteQualified(schemaName, tableName);
    }

    @Override
    public String gatherTableHighSql(String schemaName, String tableName, String indexColumns) {
        return "UPDATE STATISTICS " + quoteQualified(schemaName, tableName) + " WITH FULLSCAN";
    }

    @Override
    public String gatherProcedureFolderSql(String schemaName) {
        return "EXEC sp_updatestats";
    }

    @Override
    public String gatherProcedureSql(String schemaName, String procedureName) {
        return "EXEC sp_updatestats";
    }

    @Override
    public List<String> getColumnTypes() {
        return List.of(
                "bigint", "int", "smallint", "tinyint",
                "decimal", "numeric", "money", "smallmoney", "float", "real",
                "char", "varchar", "text",
                "nchar", "nvarchar", "ntext",
                "binary", "varbinary", "image",
                "date", "time", "datetime", "datetime2", "smalldatetime", "datetimeoffset",
                "bit", "uniqueidentifier", "xml", "sql_variant",
                "varchar(max)", "nvarchar(max)", "varbinary(max)"
        );
    }

    @Override
    public MetadataRepository metadata() { return metadataRepository; }

    @Override
    public SqlexeRepository sql() { return sqlexeRepository; }

    @Override
    public DdlRepository ddl() { return ddlRepository; }

    @Override
    public InstanceAdminRepository admin() { return instanceAdminRepository; }

    @Override
    public SqlParser parser() { return new SqlserverSqlParser(); }
}
