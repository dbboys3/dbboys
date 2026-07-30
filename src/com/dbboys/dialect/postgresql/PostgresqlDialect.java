package com.dbboys.dialect.postgresql;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.ReconnectFallbackCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.model.Connect;
import com.dbboys.model.Database;
import com.dbboys.model.HealthCheck;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL database platform dialect.
 * Implements the main {@link DatabasePlatform} contract along with
 * {@link ConnectionSupport} and {@link InstanceTabCapability}.
 */
public final class PostgresqlDialect implements DatabasePlatform, ConnectionSupport, InstanceTabCapability,
        ReconnectFallbackCapability {

    private static final String DB_TYPE = "POSTGRESQL";
    private static final String DRIVER_CLASS = "org.postgresql.Driver";

    private static final String DEFAULT_CONNECTION_PROPS = """
            [
                {"propName":"ApplicationName","propValue":""},
                {"propName":"connectTimeout","propValue":""},
                {"propName":"socketTimeout","propValue":""},
                {"propName":"sslmode","propValue":""},
                {"propName":"ssl","propValue":""},
                {"propName":"sslcert","propValue":""},
                {"propName":"sslkey","propValue":""},
                {"propName":"sslrootcert","propValue":""},
                {"propName":"currentSchema","propValue":""},
                {"propName":"prepareThreshold","propValue":""},
                {"propName":"preparedStatementCacheSizeMiB","propValue":""},
                {"propName":"cancelSignalTimeout","propValue":""},
                {"propName":"loginTimeout","propValue":""},
                {"propName":"reWriteBatchedInserts","propValue":""}
            ]""";

    private static final Set<String> SYS_DBS = Set.of("template0", "template1", "postgres");

    private final MetadataRepository metadataRepository = new PostgresqlMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new PostgresqlSqlexeRepository();
    private final DdlRepository ddlRepository = new PostgresqlDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new PostgresqlInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.POSTGRESQL_LOGO, 0.55, 0.55);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    // ==================================================================
    // ConnectionSupport
    // ==================================================================

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        // catalog = 数据库（决定 JDBC URL），sessionCatalog = 模式（连接后 SET search_path）
        String database = connect.getCatalog();
        if (database == null || database.isBlank()) {
            database = getSessionCatalog(connect);
        }
        if (database == null || database.isBlank()) {
            database = defaultDatabase();
        }
        String host = connect.getIp() == null || connect.getIp().isBlank() ? "127.0.0.1" : connect.getIp();
        String port = connect.getPort() == null || connect.getPort().isBlank() ? defaultPort() : connect.getPort();
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        String jarFilePath = "file:extlib/" + DB_TYPE + "/" + connect.getDriver();
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        String sessionCatalog = getSessionCatalog(connect);
        if (sessionCatalog != null && !sessionCatalog.isBlank()) {
            metadataRepository.setDatabase(conn, sessionCatalog);
        }
    }

    @Override
    public boolean supportsSessionInit() {
        return true;
    }

    @Override
    public String defaultPort() {
        return "5432";
    }

    @Override
    public String defaultDatabase() {
        return "postgres";
    }

    @Override
    public String defaultUsername() {
        return "postgres";
    }

    @Override
    public String defaultConnectionProps() {
        return DEFAULT_CONNECTION_PROPS;
    }

    @Override
    public void setSessionCatalog(Connect connect, String catalogName) {
        if (connect == null) {
            return;
        }
        connect.setSessionCatalog(catalogName);
        connect.setCatalog(catalogName);
    }

    @Override
    public String testConnectionSql() {
        return "SELECT 1";
    }

    @Override
    public ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        if (e == null) {
            return ChangeDatabaseFailureKind.OTHER;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("08")) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        if ("25P01".equals(state)) {
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
    // DatabasePlatform overrides
    // ==================================================================

    @Override
    public CatalogModel catalogModel() {
        return CatalogModel.DATABASE_SCHEMA; // 库-模式-表：实例有多个库，库下再挂模式
    }

    @Override
    public boolean supportsPackages() {
        return false;
    }

    @Override
    public boolean supportsObjectTypesFolder() {
        return false;
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
    public boolean supportsSynonymsFolder() {
        return false;
    }

    @Override
    public boolean supportsFunctionsFolder() {
        return true;
    }

    @Override
    public boolean supportsProceduresFolder() {
        return true;
    }

    @Override
    public boolean supportsSequencesFolder() {
        return true;
    }

    @Override
    public boolean supportsSystemTablesFolder() {
        return true;
    }

    @Override
    public boolean supportsEditableAutoIncrement() {
        return true;
    }

    @Override
    public boolean canCreateDatabase() {
        return true;
    }

    @Override
    public boolean canDropDatabase() {
        return true;
    }

    @Override
    public boolean supportsCreateDatabaseStorageSpace() {
        return false;
    }

    @Override
    public boolean supportsCreateDatabaseCharset() {
        return false;
    }

    @Override
    public List<String> createDatabaseCharsetOptions() {
        return List.of();
    }

    @Override
    public String createDatabaseSql(String databaseName, String charsetOption, String storageSpace) {
        String name = databaseName == null ? "" : databaseName.trim().replace("\"", "\"\"");
        String sql = "CREATE DATABASE \"" + name + "\"";
        if (charsetOption != null && !charsetOption.isBlank()) {
            sql += " WITH ENCODING '" + charsetOption.trim().replace("'", "''") + "'";
        }
        return sql;
    }

    @Override
    public boolean createSchemaWithPassword() {
        return false; // CREATE SCHEMA 没有密码概念
    }

    @Override
    public String createSchemaSql(String schemaName) {
        String name = schemaName == null ? "" : schemaName.trim().replace("\"", "\"\"");
        return "CREATE SCHEMA \"" + name + "\"";
    }

    @Override
    public boolean supportsDatabaseExport() {
        return true;
    }

    @Override
    public boolean supportsDatabaseImport() {
        return false;
    }

    @Override
    public boolean supportsTableTypeModification() {
        return false;
    }

    @Override
    public boolean prefersTableCountFromTableListQuery() {
        return true;
    }

    @Override
    public Set<String> systemDatabaseNames() {
        return SYS_DBS;
    }

    @Override
    public boolean isSystemDatabase(String databaseName) {
        if (databaseName == null) {
            return false;
        }
        String lower = databaseName.toLowerCase();
        return SYS_DBS.contains(lower)
                || lower.startsWith("pg_")
                || "information_schema".equals(lower);
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
    public String metadataTooltipCatalogLabel() {
        return "SCHEMA";
    }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        return "select * from " + qualifiedTable + ";";
    }

    @Override
    public String renameObjectSql(String objectType, String oldName, String newName) {
        // PostgreSQL uses ALTER ... RENAME TO
        String type = objectType.toUpperCase();
        if ("TABLE".equals(type)) {
            return "ALTER TABLE " + oldName + " RENAME TO " + newName;
        }
        if ("INDEX".equals(type)) {
            return "ALTER INDEX " + oldName + " RENAME TO " + newName;
        }
        if ("VIEW".equals(type)) {
            return "ALTER VIEW " + oldName + " RENAME TO " + newName;
        }
        if ("SEQUENCE".equals(type)) {
            return "ALTER SEQUENCE " + oldName + " RENAME TO " + newName;
        }
        if ("USER".equals(type) || "SCHEMA".equals(type)) {
            // 节点是模式（schema），不是角色：ALTER SCHEMA 而非 ALTER USER
            return "ALTER SCHEMA " + oldName + " RENAME TO " + newName;
        }
        return "ALTER " + type + " " + oldName + " RENAME TO " + newName;
    }

    @Override
    public String dropObjectSql(String objectType, String objectName) {
        String type = objectType == null ? "" : objectType.toUpperCase();
        // PostgreSQL：DROP DATABASE 没有 CASCADE；删模式用 DROP SCHEMA
        if ("DATABASE".equals(type)) {
            return "DROP DATABASE IF EXISTS " + objectName;
        }
        if ("USER".equals(type) || "SCHEMA".equals(type)) {
            return "DROP SCHEMA IF EXISTS " + objectName + " CASCADE";
        }
        return "DROP " + type + " IF EXISTS " + objectName + " CASCADE";
    }

    @Override
    public String gatherSchemaSql(String schemaName) {
        return "ANALYZE";
    }

    @Override
    public String gatherTableFolderSql(String schemaName) {
        return "ANALYZE";
    }

    @Override
    public String gatherTableSql(String schemaName, String tableName) {
        return "ANALYZE " + tableName;
    }

    @Override
    public String gatherTableHighSql(String schemaName, String tableName, String indexColumns) {
        return "ANALYZE " + tableName;
    }

    @Override
    public String truncateTableSql(String tableName) {
        return "TRUNCATE TABLE " + tableName + " CASCADE";
    }

    @Override
    public String toggleIndexSql(String indexName, boolean enabled) {
        return null; // PostgreSQL does not support enable/disable index
    }

    @Override
    public String toggleTriggerSql(String triggerName, boolean enabled) {
        if (triggerName == null || triggerName.isBlank()) {
            return "";
        }
        // PostgreSQL needs ALTER TABLE <table> ENABLE/DISABLE TRIGGER, but the SPI only
        // passes the trigger name: resolve the table at runtime inside a DO block.
        String action = enabled ? "ENABLE" : "DISABLE";
        String name = triggerName.replace("'", "''");
        return "DO $$ BEGIN EXECUTE format('ALTER TABLE %I.%I " + action + " TRIGGER %I', "
                + "(SELECT event_object_schema FROM information_schema.triggers WHERE trigger_name = '" + name + "' LIMIT 1), "
                + "(SELECT event_object_table FROM information_schema.triggers WHERE trigger_name = '" + name + "' LIMIT 1), "
                + "'" + name + "'); END $$;";
    }

    @Override
    public List<String> getColumnTypes() {
        return List.of(
                "SMALLINT", "INTEGER", "BIGINT", "SMALLSERIAL", "SERIAL", "BIGSERIAL",
                "DECIMAL", "NUMERIC", "REAL", "DOUBLE PRECISION", "MONEY",
                "CHARACTER", "CHARACTER VARYING", "VARCHAR", "CHAR", "TEXT",
                "BYTEA", "BOOLEAN",
                "DATE", "TIME", "TIME WITH TIME ZONE",
                "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "INTERVAL",
                "JSON", "JSONB", "XML",
                "UUID", "CIDR", "INET", "MACADDR",
                "POINT", "LINE", "LSEG", "BOX", "PATH", "POLYGON", "CIRCLE",
                "INT4RANGE", "INT8RANGE", "NUMRANGE", "TSRANGE", "TSTZRANGE", "DATERANGE",
                "ARRAY", "BIT", "BIT VARYING", "TSVECTOR", "TSQUERY"
        );
    }

    // ==================================================================
    // InstanceTabCapability
    // ==================================================================

    @Override
    public boolean supportsHealthCheckTab(Connect connect) {
        return connect != null;
    }

    @Override
    public boolean supportsLogTab(Connect connect) {
        return false; // PostgreSQL pg_log files are not accessible via SQL
    }

    @Override
    public boolean supportsConfigTab(Connect connect) {
        return true;
    }

    @Override
    public boolean canEditConfig(Connect connect) {
        return false; // listing pg_settings read-only; ALTER SYSTEM editing not implemented
    }

    @Override
    public String instanceName(Connect connect) {
        if (connect == null) {
            return "";
        }
        String host = connect.getIp() == null ? "" : connect.getIp();
        String port = connect.getPort() == null ? "" : connect.getPort();
        String db = connect.getCatalog() == null ? "" : connect.getCatalog();
        return host + (port.isEmpty() ? "" : ":" + port) + "/" + db;
    }

    @Override
    public String loadRuntimeLog(Connect connect) throws Exception {
        return ""; // PostgreSQL uses pg_log files, not accessible via SQL
    }

    @Override
    public List<ConfigEntry> loadConfigEntries(Connect connect) throws Exception {
        List<ConfigEntry> entries = new ArrayList<>();
        try (Connection conn = new com.dbboys.core.ConnectionServiceImpl().getConnectionWithSessionInit(connect);
             java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, setting FROM pg_catalog.pg_settings ORDER BY name")) {
            while (rs.next()) {
                entries.add(new ConfigEntry(rs.getString(1), rs.getString(2)));
            }
        }
        return entries;
    }

    @Override
    public List<HealthCheck> loadHealthChecks(Connect connect) throws Exception {
        List<HealthCheck> checks = new ArrayList<>();

        checks.add(new HealthCheck("Version", "SELECT version()",
                "Should be available", "", "", ""));
        checks.add(new HealthCheck("Uptime",
                "SELECT pg_postmaster_start_time()",
                "Should be available", "", "", ""));
        checks.add(new HealthCheck("Active Connections",
                "SELECT count(*) FROM pg_stat_activity",
                "Below 85% of max_connections", "", "", ""));
        checks.add(new HealthCheck("Database Size",
                "SELECT pg_size_pretty(pg_database_size(current_database()))",
                "Should be measurable", "", "", ""));
        checks.add(new HealthCheck("Cache Hit Ratio",
                "SELECT CASE WHEN sum(blks_hit) + sum(blks_read) > 0 THEN round(sum(blks_hit) * 100.0 / (sum(blks_hit) + sum(blks_read)), 2) ELSE 0 END FROM pg_stat_database",
                "Should be >= 99%", "", "", ""));
        checks.add(new HealthCheck("Transaction Rate",
                "SELECT xact_commit, xact_rollback FROM pg_stat_database WHERE datname = current_database()",
                "Monitor continuously", "", "", ""));

        return checks;
    }

    @Override
    public CheckTableModel buildCheckTable(Connect connect) throws Exception {
        List<CheckColumn> columns = List.of(
                new CheckColumn("entry", "instance.check.column.item", "巡检项", CheckColumnKind.TEXT, 200),
                new CheckColumn("cmd", "instance.check.column.cmd", "巡检命令", CheckColumnKind.TEXT, 100),
                new CheckColumn("healthValue", "instance.check.column.expected", "正常值", CheckColumnKind.TEXT, 300),
                new CheckColumn("currentValue", "instance.check.column.current", "当前值", CheckColumnKind.TEXT, 300),
                new CheckColumn("status", "instance.check.column.result", "巡检结论", CheckColumnKind.STATUS, 100)
        );
        List<CheckRow> rows = loadHealthChecks(connect).stream().map(check -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("entry", check.getEntry());
            values.put("cmd", check.getCmd());
            values.put("healthValue", check.getHealthValue());
            values.put("currentValue", check.getCurrentValue());
            values.put("status", check.getStatus());
            return new CheckRow(values, Map.of(), check.getCmd(), check.getCmdOutput(), false);
        }).toList();
        return new CheckTableModel(columns, rows);
    }

    @Override
    public String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        if (connection == null || connect == null) {
            return "";
        }

        DatabaseMetaData metaData = connection.getMetaData();
        connect.setDbversion((metaData.getDatabaseProductName() == null ? "" : metaData.getDatabaseProductName())
                + " " + (metaData.getDatabaseProductVersion() == null ? "" : metaData.getDatabaseProductVersion()));

        StringBuilder info = new StringBuilder();
        info.append("##########################################################################################\n");
        info.append("PostgreSQL Connection Information\n");
        info.append("##########################################################################################\n");

        info.append("##########################################################################################\n");
        info.append("System Information\n");
        info.append("##########################################################################################\n");

        try (ResultSet rs = connection.createStatement().executeQuery("SELECT version()")) {
            if (rs.next()) {
                info.append(String.format("%-30s", "Version")).append(rs.getString(1).trim()).append("\n");
            }
        }

        try (java.sql.Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                "SELECT name, setting FROM pg_settings WHERE name IN ('server_version', 'server_encoding', 'lc_collate', 'lc_ctype', 'max_connections') ORDER BY name")) {
            while (rs.next()) {
                info.append(String.format("%-30s", rs.getString(1))).append(rs.getString(2).trim()).append("\n");
            }
        } catch (SQLException ignored) {
        }

        connect.setInfo(info.toString());
        return "";
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
}
