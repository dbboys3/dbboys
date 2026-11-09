package com.dbboys.dialect.db2;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.ConnectionAddressType;
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

/**
 * IBM Db2 (LUW) platform adapter. Connection + metadata + SQL execution only;
 * instance management (install/start/stop) is intentionally not implemented.
 */
public final class Db2Dialect implements DatabasePlatform, ConnectionSupport {
    private static final String DB_TYPE = "DB2";
    private static final String DRIVER_CLASS = "com.ibm.db2.jcc.DB2Driver";
    private static final String DEFAULT_DRIVER = "jcc-11.5.9.0.jar";

    private final Db2MetadataRepository metadataRepository = new Db2MetadataRepository();
    private final SqlexeRepository sqlexeRepository = new Db2SqlexeRepository(metadataRepository);
    private final DdlRepository ddlRepository = new Db2DdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new Db2InstanceAdminRepository();

    @Override
    public String getDbType() { return DB_TYPE; }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(com.dbboys.ui.icon.IconPaths.DATABASE_CONNECT, 0.48, 0.48);
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
        String url = "jdbc:db2://" + host + ":" + port + "/" + database;
        String driver = connect.getDriver() == null || connect.getDriver().isBlank()
                ? DEFAULT_DRIVER : connect.getDriver().trim();
        String jarFilePath = "file:extlib/" + DB_TYPE + "/" + driver;
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        String sessionCatalog = getSessionCatalog(connect);
        if (sessionCatalog != null && !sessionCatalog.isBlank()) {
            metadataRepository.setDatabase(conn, sessionCatalog);
        }
    }

    /** 会话目标是模式（默认与用户名相同）；绝不写 catalog——catalog 是 JDBC URL 里的库名。 */
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

    /** 只写 sessionCatalog（模式）；默认实现会连 catalog 一起改，导致把模式名当库名拼连接地址。 */
    @Override
    public void setSessionCatalog(Connect connect, String catalogName) {
        if (connect == null) {
            return;
        }
        connect.setSessionCatalog(catalogName);
    }

    @Override
    public boolean supportsSessionInit() { return true; }

    @Override
    public String defaultPort() { return "50000"; }

    @Override
    public String defaultDatabase() { return "sample"; }

    @Override
    public boolean requiresDatabaseName() { return true; }

    @Override
    public String defaultUsername() { return "db2inst1"; }

    @Override
    public String defaultConnectionProps() { return "[]"; }

    @Override
    public String testConnectionSql() { return "SELECT 1 FROM SYSIBM.SYSDUMMY1"; }

    @Override
    public ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        if (e == null) {
            return ChangeDatabaseFailureKind.OTHER;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("08")) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        if (Db2MetadataRepository.RECONNECT_REQUIRED_STATE.equals(state)) {
            return ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION;
        }
        return ChangeDatabaseFailureKind.OTHER;
    }

    // ==================================================================
    // DatabasePlatform
    // ==================================================================

    /** Db2 connections bind to one database; the tree's first level lists schemas. */
    @Override
    public CatalogModel catalogModel() { return CatalogModel.SCHEMA; }

    @Override
    public String getDatabaseFolderI18nKey() {
        return "metadata.folder.schemas";
    }

    @Override
    public String getDatabaseFolderDefaultText() {
        return "模式";
    }

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

    /** 同 Oracle/Dameng：两层模型的节点是会话级模式，不能持久化为连接的"默认数据库"。 */
    @Override
    public boolean supportsSetDefaultDatabase() { return false; }

    @Override
    public boolean supportsEditableAutoIncrement() { return true; }

    @Override
    public String autoIncrementClause() { return "GENERATED BY DEFAULT AS IDENTITY"; }

    @Override
    public boolean createSchemaWithPassword() { return false; }

    @Override
    public boolean canCreateDatabase() { return false; }

    @Override
    public boolean canDropDatabase() { return false; }

    @Override
    public boolean supportsCreateDatabaseStorageSpace() { return false; }

    @Override
    public boolean supportsCreateDatabaseCharset() { return false; }

    @Override
    public String createDatabaseSql(String databaseName, String charsetOption, String storageSpace) {
        return null;
    }

    @Override
    public boolean supportsTableTypeModification() { return false; }

    @Override
    public String metadataTooltipCatalogLabel() { return "SCHEMA"; }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        return DatabasePlatform.defaultMetadataTreeDragStarFromSql(qualifiedTable);
    }

    /** DB2 模式是独立对象（非用户=模式模型）；不支持重命名模式。 */
    @Override
    public String schemaNodeObjectType() { return "schema"; }

    @Override
    public boolean supportsRenameDatabaseNode() { return false; }

    /** DB2 不支持重命名模式；删除模式必须带 RESTRICT。 */
    @Override
    public String renameObjectSql(String objectType, String oldName, String newName) {
        if ("schema".equalsIgnoreCase(objectType)) {
            return null;
        }
        return DatabasePlatform.super.renameObjectSql(objectType, oldName, newName);
    }

    @Override
    public String dropObjectSql(String objectType, String objectName) {
        if ("schema".equalsIgnoreCase(objectType)) {
            return "DROP SCHEMA " + objectName + " RESTRICT";
        }
        return DatabasePlatform.super.dropObjectSql(objectType, objectName);
    }

    @Override
    public String truncateTableSql(String tableName) {
        return "TRUNCATE TABLE " + tableName + " IMMEDIATE";
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        return "RENAME TABLE " + oldName + " TO " + newName;
    }

    @Override
    public String renameColumnSql(String tableName, String oldName, String newName) {
        return "ALTER TABLE " + tableName + " RENAME COLUMN " + oldName + " TO " + newName;
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
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName);
        for (String def : columnDefs) {
            sql.append(" ADD COLUMN ").append(def);
        }
        return sql.toString();
    }

    @Override
    public String dropColumnsSql(String tableName, List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName);
        for (String col : columnNames) {
            sql.append(" DROP COLUMN ").append(col);
        }
        return sql.toString();
    }

    @Override
    public String modifyColumnsSql(String tableName, List<String> columnDefs) {
        if (columnDefs == null || columnDefs.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName);
        for (String def : columnDefs) {
            sql.append(" ALTER COLUMN ").append(def);
        }
        return sql.toString();
    }

    @Override
    public String toggleIndexSql(String indexName, boolean enabled) { return null; }

    @Override
    public String toggleTriggerSql(String triggerName, boolean enabled) { return null; }

    @Override
    public String gatherSchemaSql(String schemaName) {
        return schemaName == null || schemaName.isBlank()
                ? "RUNSTATS ON TABLE SYSIBM.SYSTABLES"
                : "CALL SYSPROC.ADMIN_CMD('RUNSTATS ON SCHEMA " + schemaName.replace("'", "''") + "')";
    }

    @Override
    public String gatherTableFolderSql(String schemaName) {
        return gatherSchemaSql(schemaName);
    }

    @Override
    public String gatherTableSql(String schemaName, String tableName) {
        return "RUNSTATS ON TABLE " + tableName;
    }

    @Override
    public String gatherTableHighSql(String schemaName, String tableName, String indexColumns) {
        return "RUNSTATS ON TABLE " + tableName + " WITH DISTRIBUTION AND INDEXES ALL";
    }

    @Override
    public String gatherProcedureFolderSql(String schemaName) {
        return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
    }

    @Override
    public String gatherProcedureSql(String schemaName, String procedureName) {
        return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
    }

    @Override
    public List<String> getColumnTypes() {
        return List.of(
                "SMALLINT", "INTEGER", "BIGINT",
                "DECIMAL", "NUMERIC", "REAL", "DOUBLE", "DECFLOAT",
                "CHAR", "VARCHAR", "LONG VARCHAR", "CLOB",
                "GRAPHIC", "VARGRAPHIC", "DBCLOB",
                "BINARY", "VARBINARY", "BLOB",
                "DATE", "TIME", "TIMESTAMP",
                "BOOLEAN", "XML"
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
    public SqlParser parser() { return new Db2SqlParser(); }
}
