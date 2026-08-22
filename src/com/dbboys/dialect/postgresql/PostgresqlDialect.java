package com.dbboys.dialect.postgresql;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.ReconnectFallbackCapability;
import com.dbboys.core.SqlParser;
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
import java.util.Locale;
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
    public String autoIncrementClause() {
        return "GENERATED BY DEFAULT AS IDENTITY";
    }

    @Override
    public String addColumnsSql(String tableName, java.util.List<String> columnDefs) {
        if (columnDefs == null || columnDefs.isEmpty()) {
            return "";
        }
        List<String> clauses = new ArrayList<>();
        for (String def : columnDefs) {
            clauses.add("ADD COLUMN " + def);
        }
        return "ALTER TABLE " + tableName + " " + String.join(", ", clauses);
    }

    @Override
    public String dropColumnsSql(String tableName, java.util.List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return "";
        }
        List<String> clauses = new ArrayList<>();
        for (String name : columnNames) {
            clauses.add("DROP COLUMN " + name);
        }
        return "ALTER TABLE " + tableName + " " + String.join(", ", clauses);
    }

    @Override
    public String dropColumnSql(String tableName, String columnName) {
        return "ALTER TABLE " + tableName + " DROP COLUMN " + columnName;
    }

    @Override
    public String renameColumnSql(String tableName, String oldName, String newName) {
        return "ALTER TABLE " + tableName + " RENAME COLUMN " + oldName + " TO " + newName;
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        return "ALTER TABLE " + oldName + " RENAME TO " + newName;
    }

    @Override
    public String modifyColumnsSql(String tableName, java.util.List<String> columnDefs) {
        if (columnDefs == null || columnDefs.isEmpty()) {
            return "";
        }
        List<String> actions = new ArrayList<>();
        for (String def : columnDefs) {
            if (def == null || def.isBlank()) {
                continue;
            }
            int firstSpace = def.indexOf(' ');
            if (firstSpace <= 0) {
                continue;
            }
            String columnName = def.substring(0, firstSpace).trim();
            String rest = def.substring(firstSpace).trim();
            if (rest.isEmpty()) {
                continue;
            }

            boolean notNull = rest.contains(" NOT NULL");
            if (notNull) {
                rest = rest.replace(" NOT NULL", " ");
            }
            String identityClause = " GENERATED BY DEFAULT AS IDENTITY";
            boolean identity = rest.endsWith(identityClause);
            if (identity) {
                rest = rest.substring(0, rest.length() - identityClause.length()).trim();
            }
            String defaultValue = null;
            int defaultIndex = rest.indexOf(" DEFAULT ");
            if (defaultIndex >= 0) {
                defaultValue = rest.substring(defaultIndex + " DEFAULT ".length()).trim();
                rest = rest.substring(0, defaultIndex).trim();
            }
            String type = normalizeSerialType(rest.trim());
            if (type.isEmpty()) {
                continue;
            }

            actions.add("ALTER COLUMN " + columnName + " TYPE " + type);
            actions.add("ALTER COLUMN " + columnName + (notNull ? " SET NOT NULL" : " DROP NOT NULL"));
            actions.add("ALTER COLUMN " + columnName
                    + (defaultValue != null ? " SET DEFAULT " + defaultValue : " DROP DEFAULT"));
            actions.add("ALTER COLUMN " + columnName
                    + (identity ? " ADD GENERATED BY DEFAULT AS IDENTITY" : " DROP IDENTITY IF EXISTS"));
        }
        return actions.isEmpty() ? "" : "ALTER TABLE " + tableName + " " + String.join(", ", actions);
    }

    private static String normalizeSerialType(String type) {
        return switch (type.toUpperCase(Locale.ROOT).trim()) {
            case "SERIAL" -> "INTEGER";
            case "BIGSERIAL" -> "BIGINT";
            case "SMALLSERIAL" -> "SMALLINT";
            default -> type;
        };
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
        return true;
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
    public String dropTriggerSql(String triggerName, String tableName) {
        String onTable = tableName == null || tableName.isBlank() ? "" : " ON " + tableName;
        return "DROP TRIGGER IF EXISTS " + triggerName + onTable + " CASCADE";
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
    public String gatherProcedureFolderSql(String schemaName) {
        return null; // PostgreSQL has no procedure statistics
    }

    @Override
    public String gatherProcedureSql(String schemaName, String procedureName) {
        return null; // PostgreSQL has no procedure statistics
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
        return true;
    }

    @Override
    public boolean supportsConfigTab(Connect connect) {
        return true;
    }

    @Override
    public boolean canEditConfig(Connect connect) {
        return connect != null && !Boolean.TRUE.equals(connect.getReadonly());
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
    public SpaceLabels spaceLabels(Connect connect) {
        return new SpaceLabels(
                "",
                "",
                "instance.space.postgresql.chart.tablespace",
                "Tablespace Usage(GB)",
                "instance.space.chart.chunk",
                "数据文件使用情况图(GB)",
                "instance.space.postgresql.chart.database",
                "库空间使用情况图(GB)",
                "instance.space.chart.table",
                "表/索引空间使用情况图TOP20(GB)",
                "",
                ""
        );
    }

    @Override
    public String loadRuntimeLog(Connect connect) throws Exception {
        try (Connection conn = new com.dbboys.core.ConnectionServiceImpl().getConnectionWithSessionInit(connect)) {
            StringBuilder text = new StringBuilder();
            appendSection(text, "ACTIVITY (pg_stat_activity)", queryRows(conn, """
                    SELECT pid, usename, datname, client_addr, state, wait_event_type, backend_start, left(query, 200) AS query
                    FROM pg_catalog.pg_stat_activity ORDER BY pid""", 200));
            appendSection(text, "DATABASE STATS (pg_stat_database)", queryRows(conn, """
                    SELECT datname, numbackends, xact_commit, xact_rollback, blks_read, blks_hit,
                           tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted
                    FROM pg_catalog.pg_stat_database ORDER BY datname""", 100));
            try {
                // Tail of the current server log file; needs logging_collector=on and
                // superuser or pg_read_server_files, otherwise the error text is shown
                appendSection(text, "SERVER LOG (pg_current_logfile, tail 300)", queryRows(conn, """
                        WITH lines AS (
                            SELECT row_number() OVER () AS rn, u.line
                            FROM unnest(string_to_array(pg_catalog.pg_read_file(pg_catalog.pg_current_logfile()), E'\\n')) AS u(line)
                        )
                        SELECT string_agg(line, E'\\n' ORDER BY rn) FROM lines
                        WHERE rn > (SELECT max(rn) FROM lines) - 300""", 1));
            } catch (SQLException e) {
                appendSection(text, "SERVER LOG (pg_current_logfile)", e.getMessage());
            }
            return text.toString();
        }
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
    public ConfigUpdateResult updateConfig(Connect connect, String paramName, String newValue) throws Exception {
        String name = paramName == null ? "" : paramName.trim();
        if (name.isEmpty()) {
            return new ConfigUpdateResult(ConfigUpdateStatus.FILE_ONLY, "Empty parameter name");
        }
        // GUC names are dotted identifiers; reject anything else before issuing ALTER SYSTEM
        if (!name.matches("[A-Za-z0-9_.]+")) {
            return new ConfigUpdateResult(ConfigUpdateStatus.FILE_ONLY, "Invalid parameter name: " + paramName);
        }
        try (Connection conn = new com.dbboys.core.ConnectionServiceImpl().getConnectionWithSessionInit(new Connect(connect));
             var stmt = conn.createStatement()) {
            stmt.execute("ALTER SYSTEM SET " + name + " = " + pgValueLiteral(newValue));
            String context = null;
            try (ResultSet rs = stmt.executeQuery("SELECT context FROM pg_catalog.pg_settings WHERE name = '" + name + "'")) {
                if (rs.next()) {
                    context = rs.getString(1);
                }
            }
            // postmaster-context GUCs only take effect after a restart; everything else reloads
            if ("postmaster".equals(context)) {
                return new ConfigUpdateResult(ConfigUpdateStatus.RESTART_REQUIRED,
                        "ALTER SYSTEM SET " + name + " written to postgresql.auto.conf; restart required to take effect");
            }
            stmt.execute("SELECT pg_catalog.pg_reload_conf()");
            return new ConfigUpdateResult(ConfigUpdateStatus.APPLIED, "ALTER SYSTEM SET " + name + " applied");
        }
    }

    @Override
    public boolean isInstanceOnline(Connect connect) throws Exception {
        try (Connection conn = new com.dbboys.core.ConnectionServiceImpl().getConnectionWithSessionInit(new Connect(connect));
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            return rs.next();
        }
    }

    @Override
    public List<HealthCheck> loadHealthChecks(Connect connect) throws Exception {
        try (Connection conn = new com.dbboys.core.ConnectionServiceImpl().getConnectionWithSessionInit(connect)) {
            List<HealthCheck> checks = new ArrayList<>();

            String version = queryScalar(conn, "SELECT version()");
            addCheck(checks, "Version", "SELECT version()", "Should be available", version, present(version));

            String uptime = queryScalar(conn, "SELECT now() - pg_catalog.pg_postmaster_start_time()");
            addCheck(checks, "Uptime", "SELECT pg_postmaster_start_time()", "Should be available", uptime, present(uptime));

            long activeConnections = parseLong(queryScalar(conn, "SELECT count(*) FROM pg_catalog.pg_stat_activity"));
            long maxConnections = parseLong(queryScalar(conn, "SHOW max_connections"));
            addCheck(checks, "Active Connections", "SELECT count(*) FROM pg_stat_activity", "Below 85% of max_connections",
                    activeConnections + " / " + maxConnections,
                    maxConnections <= 0 || activeConnections * 100d / maxConnections < 85d);

            String dbSize = queryScalar(conn, "SELECT pg_catalog.pg_size_pretty(pg_catalog.pg_database_size(current_database()))");
            addCheck(checks, "Database Size", "SELECT pg_size_pretty(pg_database_size(current_database()))",
                    "Should be measurable", dbSize, present(dbSize));

            double cacheHit = parseDouble(queryScalar(conn, """
                    SELECT CASE WHEN sum(blks_hit) + sum(blks_read) > 0
                           THEN round(sum(blks_hit) * 100.0 / (sum(blks_hit) + sum(blks_read)), 2)
                           ELSE 100 END FROM pg_catalog.pg_stat_database"""));
            addCheck(checks, "Cache Hit Ratio", "blks_hit / (blks_hit + blks_read)", ">= 99%",
                    cacheHit + "%", cacheHit >= 99d);

            String txn = queryScalar(conn, "SELECT xact_commit || ' / ' || xact_rollback FROM pg_catalog.pg_stat_database WHERE datname = current_database()");
            addCheck(checks, "Transaction Rate", "SELECT xact_commit, xact_rollback FROM pg_stat_database WHERE datname = current_database()",
                    "Monitor continuously", txn, true);

            long deadlocks = parseLong(queryScalar(conn, "SELECT COALESCE(sum(deadlocks), 0) FROM pg_catalog.pg_stat_database"));
            addCheck(checks, "Deadlocks", "SELECT sum(deadlocks) FROM pg_stat_database", "0",
                    String.valueOf(deadlocks), deadlocks == 0);

            long idleInTx = parseLong(queryScalar(conn, "SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE state = 'idle in transaction'"));
            addCheck(checks, "Idle In Transaction", "SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction'", "0 or few",
                    String.valueOf(idleInTx), idleInTx == 0);

            return checks;
        }
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

    @Override
    public SqlParser parser() {
        return new PostgresqlSqlParser();
    }

    private static void addCheck(List<HealthCheck> checks, String entry, String cmd, String expected, String current, boolean ok) {
        checks.add(new HealthCheck(entry, cmd, expected, current == null ? "" : current, ok ? "0" : "2", current));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String queryScalar(Connection conn, String sql) throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private static String queryRows(Connection conn, String sql, int maxRows) throws SQLException {
        StringBuilder text = new StringBuilder();
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int columnCount = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    text.append('\t');
                }
                text.append(rs.getMetaData().getColumnLabel(i));
            }
            int rows = 0;
            while (rs.next() && rows++ < maxRows) {
                text.append('\n');
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        text.append('\t');
                    }
                    String value = rs.getString(i);
                    text.append(value == null ? "NULL" : value);
                }
            }
        }
        return text.toString();
    }

    private static void appendSection(StringBuilder text, String title, String body) {
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append("##########################################################################################\n");
        text.append(title).append('\n');
        text.append("##########################################################################################\n");
        text.append(body == null ? "" : body);
    }

    private static String pgValueLiteral(String value) {
        String text = value == null ? "" : value.trim();
        if (text.matches("(?i)^(ON|OFF|TRUE|FALSE|DEFAULT|NULL)$") || text.matches("[-+]?\\d+(\\.\\d+)?")) {
            return text;
        }
        return "'" + DatabasePlatform.escapeSqlString(text) + "'";
    }
}
