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

    @Override
    public Set<String> systemDatabaseNames() {
        return Set.of("master", "model", "msdb", "tempdb");
    }

    @Override
    public boolean isSystemDatabase(String databaseName) {
        return databaseName != null && systemDatabaseNames().contains(databaseName.trim().toLowerCase());
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
        String objectClass = "index".equalsIgnoreCase(objectType) ? "INDEX" : "OBJECT";
        return "EXEC sp_rename N'" + oldName.replace("'", "''") + "', N'" + newName.replace("'", "''")
                + "', '" + objectClass + "'";
    }

    @Override
    public String renameIndexSql(String indexName, String tableName, String newName) {
        return "EXEC sp_rename N'" + (tableName + "." + indexName).replace("'", "''")
                + "', N'" + newName.replace("'", "''") + "', 'INDEX'";
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
        return "UPDATE STATISTICS " + tableName;
    }

    @Override
    public String gatherTableHighSql(String schemaName, String tableName, String indexColumns) {
        return "UPDATE STATISTICS " + tableName + " WITH FULLSCAN";
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
