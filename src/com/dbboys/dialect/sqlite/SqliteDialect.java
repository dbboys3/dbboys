package com.dbboys.dialect.sqlite;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.ConnectionAddressType;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.model.Connect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.nio.file.Path;

public final class SqliteDialect implements DatabasePlatform, ConnectionSupport {
    private static final String DB_TYPE = "SQLITE";
    private static final String DRIVER_CLASS = "org.sqlite.JDBC";
    private static final String DEFAULT_DRIVER = "sqlite-jdbc-3.46.0.0.jar";

    private final MetadataRepository metadataRepository = new SqliteMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new SqliteSqlexeRepository();
    private final DdlRepository ddlRepository = new SqliteDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new SqliteInstanceAdminRepository();

    @Override
    public String getDbType() { return DB_TYPE; }

    @Override
    public boolean supportsSystemTablesFolder() { return false; }
    @Override
    public boolean supportsSequencesFolder() { return false; }
    @Override
    public boolean supportsSynonymsFolder() { return false; }
    @Override
    public boolean supportsEditableAutoIncrement() { return true; }
    @Override
    public boolean supportsSetDefaultDatabase() { return false; }
    @Override
    public boolean supportsRenameDatabaseNode() { return false; }
    @Override
    public boolean canCreateDatabase() { return false; }
    @Override
    public boolean canDropDatabase() { return false; }
    @Override
    public boolean supportsFunctionsFolder() { return false; }
    @Override
    public boolean supportsProceduresFolder() { return false; }
    @Override
    public boolean supportsDatabaseExport() { return true; }
    @Override
    public boolean supportsDatabaseImport() { return true; }
    @Override
    public boolean supportsTableTypeModification() { return false; }
    @Override
    public boolean showMetadataDescriptions() { return true; }
    @Override
    public boolean showMetadataWarnings() { return false; }
    @Override
    public boolean showMetadataTooltips() { return false; }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.CONNECTION_LINK, 0.55, 0.55);
    }

    @Override
    public ConnectionSupport connection() { return this; }

    @Override
    public ConnectionAddressType connectionAddressType() {
        return ConnectionAddressType.FILE_PATH;
    }

    @Override
    public boolean supportsCredentials() {
        return false;
    }

    @Override
    public java.util.List<String> excludedDriverJars() {
        return java.util.List.of("slf4j-api-2.0.13.jar");
    }

    @Override
    public java.util.List<String> fileBrowserExtensions() {
        return java.util.List.of("*.db", "*.dat");
    }

    @Override
    public String fileBrowserExtensionDescription() {
        return "SQLite Database (*.db, *.dat)";
    }

    @Override
    public String fileBrowserDialogTitle() {
        return "选择SQLite数据库文件";
    }

    @Override
    public boolean supportsModifyColumn() { return false; }

    @Override
    public boolean supportsCommentOnTable() { return false; }

    @Override
    public String renameColumnSql(String tableName, String oldName, String newName) {
        return "ALTER TABLE " + tableName + " RENAME COLUMN " + oldName + " TO " + newName;
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        return "ALTER TABLE " + oldName + " RENAME TO " + newName;
    }

    @Override
   public String dropColumnSql(String tableName, String columnName) {
       return "ALTER TABLE " + tableName + " DROP COLUMN " + columnName;
   }
    @Override
   public String addColumnsSql(String tableName, java.util.List<String> columnDefs) {
       if (columnDefs == null || columnDefs.isEmpty()) {
           return "";
       }
       java.util.List<String> stmts = new java.util.ArrayList<>();
       for (String def : columnDefs) {
           stmts.add("ALTER TABLE " + tableName + " ADD " + def);
       }
       return String.join(";\n", stmts);
   }
    @Override
   public String dropColumnsSql(String tableName, java.util.List<String> columnNames) {
       if (columnNames == null || columnNames.isEmpty()) {
           return "";
       }
       java.util.List<String> stmts = new java.util.ArrayList<>();
       for (String col : columnNames) {
           stmts.add("ALTER TABLE " + tableName + " DROP COLUMN " + col);
       }
       return String.join(";\n", stmts);
   }
    @Override
    public String modifyColumnsSql(String tableName, java.util.List<String> columnDefs) {
        return ""; // SQLite does not support ALTER TABLE MODIFY
    }



    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        String filePath = connect.getIp() == null || connect.getIp().isBlank()
                ? "test.db" : connect.getIp().trim();
        String url = "jdbc:sqlite:" + filePath;
        String driver = connect.getDriver() == null || connect.getDriver().isBlank()
                ? DEFAULT_DRIVER : connect.getDriver().trim();
        String jarFilePath = Path.of("extlib", DB_TYPE, driver).toUri().toString();
        String slf4jPath = Path.of("extlib", DB_TYPE, "slf4j-api-2.0.13.jar").toUri().toString();
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath, java.util.List.of(slf4jPath));
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws SQLException {}

    @Override
    public boolean supportsSessionInit() { return false; }

    @Override
    public String defaultPort() { return ""; }

    @Override
    public String defaultDatabase() { return ""; }

    @Override
    public String defaultConnectionProps() { return "[]"; }

    @Override
    public String testConnectionSql() { return "SELECT 1"; }

    @Override
    public ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        if (e == null) return ChangeDatabaseFailureKind.OTHER;
        String state = e.getSQLState();
        if (state != null && state.startsWith("08")) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        return ChangeDatabaseFailureKind.OTHER;
    }

    @Override
   public List<String> getColumnTypes() {
      return List.of(
               "INTEGER",
               "REAL",
                "VARCHAR",
                "CHAR",
               "TEXT",
               "BLOB",
               "NUMERIC"
      );
   }

    @Override public String buildBootstrapSql(com.dbboys.model.Catalog database) { return ""; }
    @Override public String createDatabaseSql(String name, String charset, String dbspace) { return null; }

    @Override
    public MetadataRepository metadata() { return metadataRepository; }
    @Override
    public SqlexeRepository sql() { return sqlexeRepository; }
    @Override
    public DdlRepository ddl() { return ddlRepository; }
    @Override
    public InstanceAdminRepository admin() { return instanceAdminRepository; }

    // === SQL generation ===

    @Override
    public String truncateTableSql(String tableName) {
        return "DELETE FROM \"" + tableName.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String renameObjectSql(String objectType, String oldName, String newName) {
        if ("table".equalsIgnoreCase(objectType)) {
            return "ALTER TABLE \"" + oldName.replace("\"", "\"\"") + "\" RENAME TO \"" + newName.replace("\"", "\"\"") + "\"";
        }
        return DatabasePlatform.super.renameObjectSql(objectType, oldName, newName);
    }

    @Override
    public String renameIndexSql(String indexName, String tableName, String newName) { return null; }

    @Override
    public String dropObjectSql(String objectType, String objectName) {
        return "DROP " + objectType.toUpperCase() + " IF EXISTS \"" + objectName.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String toggleIndexSql(String indexName, boolean enabled) { return null; }
    @Override
    public String toggleTriggerSql(String triggerName, boolean enabled) { return null; }

    @Override
    public String gatherSchemaSql(String schemaName) { return "ANALYZE"; }
    @Override
    public String gatherTableFolderSql(String schemaName) { return "ANALYZE"; }
    @Override
    public String gatherTableSql(String schemaName, String tableName) { return "ANALYZE " + tableName; }
    @Override
    public String gatherTableHighSql(String schemaName, String tableName, String indexColumns) { return "ANALYZE " + tableName; }

    private static String quoteIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
