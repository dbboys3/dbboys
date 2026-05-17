package com.dbboys.impl.dialect.mysql;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.api.ConnectionSupport;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.InstanceTabCapability;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.app.AppContext;
import com.dbboys.api.ConnectionService;
import com.dbboys.impl.ConnectionServiceImpl;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Catalog;
import com.dbboys.vo.Connect;
import com.dbboys.vo.HealthCheck;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MysqlDialect implements DatabasePlatform, ConnectionSupport, InstanceTabCapability {
    private static final String DB_TYPE = "MYSQL";
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final String DEFAULT_DRIVER = "mysql-connector-j-8.0.32.jar";
    private static final Map<String, String> MYSQL_CHECK_ENTRY_I18N_KEYS = Map.of(
            "Version", "instance.check.mysql.item.version",
            "Uptime", "instance.check.mysql.item.uptime",
            "Connections", "instance.check.mysql.item.connections",
            "Aborted Connects", "instance.check.mysql.item.aborted_connects",
            "Slow Queries", "instance.check.mysql.item.slow_queries",
            "InnoDB Status", "instance.check.mysql.item.innodb_status",
            "Max Database Space", "instance.check.mysql.item.max_database_space"
    );
    private static final Map<String, String> MYSQL_CHECK_EXPECTED_I18N_KEYS = Map.of(
            "Should be available", "instance.check.mysql.expected.available",
            "Should be greater than 0", "instance.check.mysql.expected.gt_zero",
            "Below 85%", "instance.check.mysql.expected.lt_85",
            "Monitor continuously", "instance.check.mysql.expected.monitor",
            "InnoDB should be available", "instance.check.mysql.expected.innodb_available",
            "Should be measurable", "instance.check.mysql.expected.measurable"
    );
    private static final Map<String, String> MYSQL_CHECK_ENTRY_I18N_KEYS_BY_CMD = Map.ofEntries(
            Map.entry("select version()", "instance.check.mysql.item.version"),
            Map.entry("show global status like 'Uptime'", "instance.check.mysql.item.uptime"),
            Map.entry("Threads_connected / max_connections", "instance.check.mysql.item.connections"),
            Map.entry("Max_used_connections / max_connections", "instance.check.mysql.item.max_used_connections"),
            Map.entry("show global status like 'Threads_running'", "instance.check.mysql.item.threads_running"),
            Map.entry("Thread cache hit ratio", "instance.check.mysql.item.thread_cache_hit"),
            Map.entry("Temporary disk table ratio", "instance.check.mysql.item.tmp_disk_table_ratio"),
            Map.entry("InnoDB buffer pool hit ratio", "instance.check.mysql.item.buffer_pool_hit"),
            Map.entry("show global status like 'Innodb_log_waits'", "instance.check.mysql.item.innodb_log_waits"),
            Map.entry("show global status like 'Table_locks_waited'", "instance.check.mysql.item.table_locks_waited"),
            Map.entry("show global variables like 'log_bin'", "instance.check.mysql.item.log_bin"),
            Map.entry("read_only / super_read_only", "instance.check.mysql.item.read_only"),
            Map.entry("Connection_errors_*", "instance.check.mysql.item.connection_errors"),
            Map.entry("show global status like 'Aborted_connects'", "instance.check.mysql.item.aborted_connects"),
            Map.entry("show global status like 'Slow_queries'", "instance.check.mysql.item.slow_queries"),
            Map.entry("show engines", "instance.check.mysql.item.innodb_status"),
            Map.entry("information_schema.tables", "instance.check.mysql.item.max_database_space")
    );
    private static final Map<String, String> MYSQL_CHECK_EXPECTED_I18N_KEYS_BY_CMD = Map.ofEntries(
            Map.entry("select version()", "instance.check.mysql.expected.available"),
            Map.entry("show global status like 'Uptime'", "instance.check.mysql.expected.gt_zero"),
            Map.entry("Threads_connected / max_connections", "instance.check.mysql.expected.lt_85"),
            Map.entry("Max_used_connections / max_connections", "instance.check.mysql.expected.lt_85"),
            Map.entry("show global status like 'Threads_running'", "instance.check.mysql.expected.monitor"),
            Map.entry("Thread cache hit ratio", "instance.check.mysql.expected.hit_ge_90"),
            Map.entry("Temporary disk table ratio", "instance.check.mysql.expected.tmp_disk_lt_25"),
            Map.entry("InnoDB buffer pool hit ratio", "instance.check.mysql.expected.buffer_pool_ge_99"),
            Map.entry("show global status like 'Innodb_log_waits'", "instance.check.mysql.expected.zero_or_few"),
            Map.entry("show global status like 'Table_locks_waited'", "instance.check.mysql.expected.zero_or_few"),
            Map.entry("show global variables like 'log_bin'", "instance.check.mysql.expected.monitor"),
            Map.entry("read_only / super_read_only", "instance.check.mysql.expected.monitor"),
            Map.entry("Connection_errors_*", "instance.check.mysql.expected.zero_or_few"),
            Map.entry("show global status like 'Aborted_connects'", "instance.check.mysql.expected.monitor"),
            Map.entry("show global status like 'Slow_queries'", "instance.check.mysql.expected.monitor"),
            Map.entry("show engines", "instance.check.mysql.expected.innodb_available"),
            Map.entry("information_schema.tables", "instance.check.mysql.expected.measurable")
    );
    private static final String DEFAULT_CONNECTION_PROPS = buildDefaultConnectionProps(
            "user", "",
            "password", "",
            "password1", "",
            "password2", "",
            "password3", "",
            "authenticationPlugins", "",
            "disabledAuthenticationPlugins", "",
            "defaultAuthenticationPlugin", "",
            "ldapServerHostname", "",
            "ociConfigFile", "",
            "ociConfigProfile", "",
            "connectionAttributes", "",
            "connectionLifecycleInterceptors", "",
            "useConfigs", "",
            "clientInfoProvider", "",
            "createDatabaseIfNotExist", "",
            "databaseTerm", "",
            "detectCustomCollations", "",
            "disconnectOnExpiredPasswords", "",
            "interactiveClient", "",
            "passwordCharacterEncoding", "",
            "propertiesTransform", "",
            "rollbackOnPooledClose", "",
            "useAffectedRows", "",
            "sessionVariables", "",
            "useUnicode", "true",
            "characterEncoding", "UTF-8",
            "characterSetResults", "",
            "connectionCollation", "",
            "customCharsetMapping", "",
            "trackSessionState", "",
            "socksProxyHost", "",
            "socksProxyPort", "",
            "socketFactory", "",
            "connectTimeout", "",
            "socketTimeout", "",
            "dnsSrv", "",
            "localSocketAddress", "",
            "maxAllowedPacket", "",
            "socksProxyRemoteDns", "",
            "tcpKeepAlive", "",
            "tcpNoDelay", "",
            "tcpRcvBuf", "",
            "tcpSndBuf", "",
            "tcpTrafficClass", "",
            "useCompression", "",
            "useUnbufferedInput", "",
            "paranoid", "",
            "serverRSAPublicKeyFile", "",
            "allowPublicKeyRetrieval", "true",
            "sslMode", "",
            "trustCertificateKeyStoreUrl", "",
            "trustCertificateKeyStoreType", "",
            "trustCertificateKeyStorePassword", "",
            "fallbackToSystemTrustStore", "",
            "clientCertificateKeyStoreUrl", "",
            "clientCertificateKeyStoreType", "",
            "clientCertificateKeyStorePassword", "",
            "fallbackToSystemKeyStore", "",
            "tlsCiphersuites", "",
            "tlsVersions", "",
            "allowLoadLocalInfile", "",
            "allowLoadLocalInfileInPath", "",
            "allowMultiQueries", "",
            "allowUrlInLocalInfile", "",
            "requireSSL", "",
            "useSSL", "false",
            "verifyServerCertificate", "",
            "cacheDefaultTimeZone", "",
            "continueBatchOnError", "",
            "dontTrackOpenResources", "",
            "queryInterceptors", "",
            "queryTimeoutKillsConnection", "",
            "allowNanAndInf", "",
            "autoClosePStmtStreams", "",
            "compensateOnDuplicateKeyUpdateCounts", "",
            "emulateUnsupportedPstmts", "",
            "generateSimpleParameterMetadata", "",
            "processEscapeCodesForPrepStmts", "",
            "useServerPrepStmts", "",
            "useStreamLengthsInPrepStmts", "",
            "clobberStreamingResults", "",
            "emptyStringsConvertToZero", "",
            "holdResultsOpenOverStatementClose", "",
            "jdbcCompliantTruncation", "",
            "maxRows", "",
            "netTimeoutForStreamingResults", "",
            "padCharsWithSpace", "",
            "populateInsertRowWithDefaultValues", "",
            "scrollTolerantForwardOnly", "",
            "strictUpdates", "",
            "tinyInt1isBit", "",
            "transformedBitIsBoolean", "",
            "getProceduresReturnsFunctions", "",
            "noAccessToProcedureBodies", "",
            "nullDatabaseMeansCurrent", "",
            "useHostsInPrivileges", "",
            "useInformationSchema", "",
            "blobSendChunkSize", "",
            "blobsAreStrings", "",
            "clobCharacterEncoding", "",
            "emulateLocators", "",
            "functionsNeverReturnBlobs", "",
            "locatorFetchBufferSize", "",
            "connectionTimeZone", "",
            "serverTimezone", "Asia/Shanghai",
            "forceConnectionTimeZoneToSession", "",
            "noDatetimeStringSync", "",
            "preserveInstants", "",
            "sendFractionalSeconds", "",
            "sendFractionalSecondsForTime", "",
            "treatUtilDateAsTimestamp", "",
            "yearIsDateType", "",
            "zeroDateTimeBehavior", "",
            "autoReconnect", "",
            "autoReconnectForPools", "",
            "failOverReadOnly", "",
            "maxReconnects", "",
            "reconnectAtTxEnd", "",
            "retriesAllDown", "",
            "initialTimeout", "",
            "queriesBeforeRetrySource", "",
            "secondsBeforeRetrySource", "",
            "allowReplicaDownConnections", "",
            "allowSourceDownConnections", "",
            "ha.enableJMX", "",
            "loadBalanceHostRemovalGracePeriod", "",
            "readFromSourceWhenNoReplicas", "",
            "selfDestructOnPingMaxOperations", "",
            "selfDestructOnPingSecondsLifetime", "",
            "ha.loadBalanceStrategy", "",
            "loadBalanceAutoCommitStatementRegex", "",
            "loadBalanceAutoCommitStatementThreshold", "",
            "loadBalanceBlocklistTimeout", "",
            "loadBalanceConnectionGroup", "",
            "loadBalanceExceptionChecker", "",
            "loadBalancePingTimeout", "",
            "loadBalanceSQLExceptionSubclassFailover", "",
            "loadBalanceSQLStateFailover", "",
            "loadBalanceValidateConnectionOnSwapServer", "",
            "pinGlobalTxToPhysicalConnection", "",
            "replicationConnectionGroup", "",
            "resourceId", "",
            "serverAffinityOrder", "",
            "callableStmtCacheSize", "",
            "metadataCacheSize", "",
            "useLocalSessionState", "",
            "useLocalTransactionState", "",
            "prepStmtCacheSize", "",
            "prepStmtCacheSqlLimit", "",
            "queryInfoCacheFactory", "",
            "serverConfigCacheFactory", "",
            "alwaysSendSetIsolation", "",
            "maintainTimeStats", "",
            "useCursorFetch", "",
            "cacheCallableStmts", "",
            "cachePrepStmts", "",
            "cacheResultSetMetadata", "",
            "cacheServerConfiguration", "",
            "defaultFetchSize", "",
            "dontCheckOnDuplicateKeyUpdateInSQL", "",
            "elideSetAutoCommits", "",
            "enableEscapeProcessing", "",
            "enableQueryTimeouts", "",
            "largeRowSizeThreshold", "",
            "readOnlyPropagatesToServer", "",
            "rewriteBatchedStatements", "",
            "useReadAheadInput", "",
            "logger", "",
            "profilerEventHandler", "",
            "useNanosForElapsedTime", "",
            "maxQuerySizeToLog", "",
            "maxByteArrayAsHex", "",
            "profileSQL", "",
            "logSlowQueries", "",
            "slowQueryThresholdMillis", "",
            "slowQueryThresholdNanos", "",
            "autoSlowLog", "",
            "explainSlowQueries", "",
            "gatherPerfMetrics", "",
            "reportMetricsIntervalMillis", "",
            "logXaCommands", "",
            "traceProtocol", "",
            "enablePacketDebug", "",
            "packetDebugBufferSize", "",
            "useUsageAdvisor", "",
            "resultSetSizeThreshold", "",
            "autoGenerateTestcaseScript", "",
            "dumpQueriesOnException", "",
            "exceptionInterceptors", "",
            "ignoreNonTxTables", "",
            "includeInnodbStatusInDeadlockExceptions", "",
            "includeThreadDumpInDeadlockExceptions", "",
            "includeThreadNamesAsStatementComment", "",
            "useOnlyServerErrorMessages", "",
            "overrideSupportsIntegrityEnhancementFacility", "",
            "ultraDevHack", "",
            "useColumnNamesInFindColumn", "",
            "pedantic", "",
            "useOldAliasMetadataBehavior", "",
            "xdevapi.auth", "",
            "xdevapi.compression", "",
            "xdevapi.compression-algorithms", "",
            "xdevapi.compression-extensions", "",
            "xdevapi.connect-timeout", "",
            "xdevapi.connection-attributes", "",
            "xdevapi.dns-srv", "",
            "xdevapi.fallback-to-system-keystore", "",
            "xdevapi.fallback-to-system-truststore", "",
            "xdevapi.ssl-keystore", "",
            "xdevapi.ssl-keystore-password", "",
            "xdevapi.ssl-keystore-type", "",
            "xdevapi.ssl-mode", "",
            "xdevapi.ssl-truststore", "",
            "xdevapi.ssl-truststore-password", "",
            "xdevapi.ssl-truststore-type", "",
            "xdevapi.tls-ciphersuites", "",
            "xdevapi.tls-versions", ""
    );

    private final MetadataRepository metadataRepository = new MysqlMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new MysqlSqlexeRepository();
    private final DdlRepository ddlRepository = new MysqlDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new MysqlInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean supportsSystemTablesFolder() {
        return false;
    }

    @Override
    public boolean supportsSequencesFolder() {
        return false;
    }

    @Override
    public boolean supportsSynonymsFolder() {
        return false;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.MYSQL_LOGO, 0.6, 0.6);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) {
        String host = connect.getIp() == null || connect.getIp().isBlank() ? "localhost" : connect.getIp().trim();
        String port = connect.getPort() == null || connect.getPort().isBlank() ? defaultPort() : connect.getPort().trim();
        String database = connect.getCatalog() == null ? "" : connect.getCatalog().trim();
        if (!database.isBlank() && (connect.getSessionCatalog() == null || connect.getSessionCatalog().isBlank())) {
            connect.setSessionCatalog(database);
        }
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database;
        String driver = connect.getDriver() == null || connect.getDriver().isBlank() ? DEFAULT_DRIVER : connect.getDriver().trim();
        String jarFilePath = Path.of("extlib", DB_TYPE, driver).toUri().toString();
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws SQLException {
        String sessionCatalog = getSessionCatalog(connect);
        if (conn != null && sessionCatalog != null && !sessionCatalog.isBlank()) {
            String catalog = sessionCatalog.trim();
            conn.setCatalog(catalog);
            connect.setSessionCatalog(catalog);
            return;
        }
        if (conn != null && connect != null) {
            String catalog = conn.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                connect.setSessionCatalog(catalog.trim());
            }
        }
    }

    @Override
    public boolean supportsSessionInit() {
        return true;
    }

    @Override
    public String defaultPort() {
        return "3306";
    }

    @Override
    public String defaultDatabase() {
        return "";
    }

    @Override
    public String defaultConnectionProps() {
        return DEFAULT_CONNECTION_PROPS;
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
        int code = e.getErrorCode();
        if (code == 0 || code == 2006 || code == 2013 || code == 2055) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        return ChangeDatabaseFailureKind.OTHER;
    }

    @Override
    public List<String> getColumnTypes() {
        return List.of(
                "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT",
                "TINYINT UNSIGNED", "SMALLINT UNSIGNED", "MEDIUMINT UNSIGNED", "INT UNSIGNED", "BIGINT UNSIGNED",
                "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "BIT", "BOOLEAN",
                "CHAR", "VARCHAR", "BINARY", "VARBINARY",
                "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
                "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB",
                "DATE", "TIME", "DATETIME", "TIMESTAMP", "YEAR",
                "JSON", "ENUM", "SET", "SERIAL"
        );
    }

    @Override
    public String buildBootstrapSql(Catalog database) {
        if (database == null || database.getName() == null || database.getName().isBlank()) {
            return "";
        }
        String name = quoteIdentifier(database.getName());
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        return "-- ############################################################\n"
                + "-- ### MySQL Database DDL Export\n"
                + "-- ### Database : " + database.getName().trim() + "\n"
                + "-- ### Datetime : " + dateStr + "\n"
                + "-- ############################################################\n\n"
                + "CREATE DATABASE IF NOT EXISTS " + name + ";\n"
                + "USE " + name + ";\n\n";
    }

    @Override
    public List<String> createDatabaseCharsetOptions() {
        return List.of(
                "utf8mb4(Recommended)",
                "utf8mb4",
                "utf8",
                "gbk",
                "latin1"
        );
    }

    @Override
    public boolean supportsCreateDatabaseStorageSpace() {
        return false;
    }

    @Override
    public String createDatabaseSql(String databaseName, String charsetOption, String storageSpace) {
        String name = quoteIdentifier(databaseName);
        String charset = normalizeCreateDatabaseCharset(charsetOption);
        if (charset.isEmpty()) {
            return "CREATE DATABASE " + name;
        }
        return "CREATE DATABASE " + name + " DEFAULT CHARACTER SET " + charset;
    }

    @Override
    public String getUserMetadataDatabaseName(Connect connect) {
        String sessionCatalog = connect == null ? "" : connect.getSessionCatalog();
        if (sessionCatalog != null && !sessionCatalog.isBlank()) {
            return sessionCatalog.trim();
        }
        String catalog = connect == null ? "" : connect.getCatalog();
        if (catalog != null && !catalog.isBlank()) {
            return catalog.trim();
        }
        return "mysql";
    }

    @Override
    public boolean supportsTableTypeModification() {
        return false;
    }

    @Override
    public boolean supportsTableLoggingToggle() {
        return false;
    }

    @Override
    public boolean supportsRenameDatabaseNode() {
        return false;
    }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        return DatabasePlatform.defaultMetadataTreeDragStarFromSql(qualifiedTable);
    }

    @Override
    public String renameObjectSql(String objectType, String oldName, String newName) {
        String type = normalizeObjectType(objectType);
        String oldIdentifier = qualify(null, oldName);
        String newIdentifier = qualify(null, newName);
        return switch (type) {
            case "table" -> "RENAME TABLE " + oldIdentifier + " TO " + newIdentifier;
            case "view" -> "RENAME TABLE " + oldIdentifier + " TO " + newIdentifier;
            case "database" -> null;
            default -> "RENAME " + type.toUpperCase(Locale.ROOT) + " " + oldIdentifier + " TO " + newIdentifier;
        };
    }

    @Override
    public String renameIndexSql(String indexName, String tableName, String newName) {
        return "ALTER TABLE " + qualify(null, tableName)
                + " RENAME INDEX " + quoteIdentifier(indexName)
                + " TO " + quoteIdentifier(newName);
    }

    @Override
    public String dropObjectSql(String objectType, String objectName) {
        String type = normalizeObjectType(objectType);
        if ("user".equals(type)) {
            return "DROP USER " + quoteAccount(objectName);
        }
        String identifier = "database".equals(type) ? quoteIdentifier(objectName) : qualify(null, objectName);
        return switch (type) {
            case "database" -> "DROP DATABASE " + identifier;
            case "view" -> "DROP VIEW " + identifier;
            case "index" -> "DROP INDEX " + identifier;
            case "trigger" -> "DROP TRIGGER " + identifier;
            case "function" -> "DROP FUNCTION " + identifier;
            case "procedure" -> "DROP PROCEDURE " + identifier;
            default -> "DROP " + type.toUpperCase(Locale.ROOT) + " " + identifier;
        };
    }

    @Override
    public String createUserSql(String userName, String password) {
        return "CREATE USER " + quoteAccount(userName)
                + " IDENTIFIED BY '" + DatabasePlatform.escapeSqlString(password) + "'";
    }

    @Override
    public String resetUserPasswordSql(String userName, String password) {
        return "ALTER USER " + quoteAccount(userName)
                + " IDENTIFIED BY '" + DatabasePlatform.escapeSqlString(password) + "'";
    }

    @Override
    public String dropIndexSql(String indexName, String tableName) {
        return "DROP INDEX " + quoteIdentifier(indexName) + " ON " + qualify(null, tableName);
    }

    @Override
    public String truncateTableSql(String tableName) {
        String qualified = qualify(null, tableName);
        return qualified.isEmpty() ? null : "TRUNCATE TABLE " + qualified;
    }

    @Override
    public String toggleIndexSql(String indexName, boolean enabled) {
        return null;
    }

    @Override
    public boolean supportsToggleIndex() {
        return false;
    }

    @Override
    public String toggleTriggerSql(String triggerName, boolean enabled) {
        return null;
    }

    @Override
    public boolean supportsToggleTrigger() {
        return false;
    }

    @Override
    public String gatherSchemaSql(String schemaName) {
        return null;
    }

    @Override
    public String gatherTableFolderSql(String schemaName) {
        return null;
    }

    @Override
    public String gatherTableSql(String schemaName, String tableName) {
        String qualified = qualify(schemaName, tableName);
        return qualified.isEmpty() ? null : "ANALYZE TABLE " + qualified;
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
    public Set<String> systemDatabaseNames() {
        return Set.of("information_schema", "mysql", "performance_schema", "sys");
    }

    @Override
    public boolean isSystemDatabase(String databaseName) {
        return databaseName != null && systemDatabaseNames().contains(databaseName.trim().toLowerCase());
    }

    @Override
    public String supportedVersionLabel() {
        return "8.0";
    }

    @Override
    public String metadataTooltipCatalogLabel() {
        return "DATABASE";
    }

    @Override
    public boolean supportsHealthCheckTab(Connect connect) {
        return true;
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
    public boolean supportsStartStopTab(Connect connect) {
        return false;
    }

    @Override
    public String instanceName(Connect connect) {
        if (connect == null) {
            return "";
        }
        String host = connect.getIp() == null ? "" : connect.getIp().trim();
        String port = connect.getPort() == null ? "" : connect.getPort().trim();
        return host + (port.isEmpty() ? "" : ":" + port);
    }

    @Override
    public SpaceLabels spaceLabels(Connect connect) {
        return new SpaceLabels(
                "",
                "",
                "instance.space.mysql.chart.engine",
                "Storage Engine Usage(GB)",
                "instance.space.mysql.chart.database_parts",
                "Database Data/Index Usage(GB)",
                "instance.space.mysql.chart.database",
                "Database Usage(GB)",
                "instance.space.mysql.chart.table",
                "Table Usage TOP20(GB)",
                "",
                ""
        );
    }

    @Override
    public CheckTableModel buildCheckTable(Connect connect) throws Exception {
        List<CheckColumn> columns = List.of(
                new CheckColumn("entry", "instance.check.column.item", "巡检项", CheckColumnKind.TEXT, 220),
                new CheckColumn("currentValue", "instance.check.column.current", "当前值", CheckColumnKind.TEXT, 360),
                new CheckColumn("healthValue", "instance.check.column.expected", "正常值", CheckColumnKind.TEXT, 260),
                new CheckColumn("status", "instance.check.column.result", "巡检结论", CheckColumnKind.STATUS, 100)
        );
        List<CheckRow> rows = new ArrayList<>();
        for (HealthCheck check : loadHealthChecks(connect)) {
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, String> valueI18nKeys = new LinkedHashMap<>();
            values.put("entry", check.getEntry());
            values.put("currentValue", check.getCurrentValue());
            values.put("healthValue", check.getHealthValue());
            values.put("status", check.getStatus());
            valueI18nKeys.put("entry", MYSQL_CHECK_ENTRY_I18N_KEYS_BY_CMD.getOrDefault(
                    check.getCmd(),
                    MYSQL_CHECK_ENTRY_I18N_KEYS.getOrDefault(check.getEntry(), "")
            ));
            valueI18nKeys.put("healthValue", MYSQL_CHECK_EXPECTED_I18N_KEYS_BY_CMD.getOrDefault(
                    check.getCmd(),
                    MYSQL_CHECK_EXPECTED_I18N_KEYS.getOrDefault(check.getHealthValue(), "")
            ));
            rows.add(new CheckRow(values, valueI18nKeys, check.getCmd(), check.getCmdOutput(), false));
        }
        return new CheckTableModel(columns, rows);
    }

    @Override
    public List<HealthCheck> loadHealthChecks(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect))) {
            Map<String, String> variables = queryNameValue(conn, "show global variables");
            Map<String, String> status = queryNameValue(conn, "show global status");
            List<HealthCheck> checks = new ArrayList<>();
            addCheck(checks, "版本", "select version()", "应可获取",
                    queryScalar(conn, "select version()"), present(queryScalar(conn, "select version()")));
            addCheck(checks, "运行时间", "show global status like 'Uptime'", "应大于 0",
                    secondsText(status.get("Uptime")), parseLong(status.get("Uptime")) > 0);
            long maxConnections = parseLong(variables.get("max_connections"));
            long threadsConnected = parseLong(status.get("Threads_connected"));
            addCheck(checks, "连接数", "Threads_connected / max_connections", "<85%",
                    threadsConnected + " / " + maxConnections, maxConnections <= 0 || threadsConnected * 100d / maxConnections < 85d);
            long maxUsedConnections = parseLong(status.get("Max_used_connections"));
            addCheck(checks, "Max Used Connections", "Max_used_connections / max_connections", "Below 85%",
                    percentText(maxUsedConnections, maxConnections) + " (" + maxUsedConnections + " / " + maxConnections + ")",
                    maxConnections <= 0 || maxUsedConnections * 100d / maxConnections < 85d);
            long threadsRunning = parseLong(status.get("Threads_running"));
            addCheck(checks, "Threads Running", "show global status like 'Threads_running'", "Monitor continuously",
                    String.valueOf(threadsRunning), true);
            long connections = parseLong(status.get("Connections"));
            long threadsCreated = parseLong(status.get("Threads_created"));
            double threadCacheHit = connections <= 0 ? 100d : (1d - (double) threadsCreated / connections) * 100d;
            addCheck(checks, "Thread Cache Hit Ratio", "Thread cache hit ratio", ">=90%",
                    percentText(threadCacheHit), threadCacheHit >= 90d);
            long tmpTables = parseLong(status.get("Created_tmp_tables"));
            long tmpDiskTables = parseLong(status.get("Created_tmp_disk_tables"));
            double tmpDiskRatio = tmpTables <= 0 ? 0d : (double) tmpDiskTables * 100d / tmpTables;
            addCheck(checks, "Temporary Disk Table Ratio", "Temporary disk table ratio", "<25%",
                    percentText(tmpDiskRatio) + " (" + tmpDiskTables + " / " + tmpTables + ")", tmpDiskRatio < 25d);
            long bpRequests = parseLong(status.get("Innodb_buffer_pool_read_requests"));
            long bpReads = parseLong(status.get("Innodb_buffer_pool_reads"));
            double bpHit = bpRequests <= 0 ? 100d : (1d - (double) bpReads / bpRequests) * 100d;
            addCheck(checks, "InnoDB Buffer Pool Hit Ratio", "InnoDB buffer pool hit ratio", ">=99%",
                    percentText(bpHit), bpHit >= 99d);
            long innodbLogWaits = parseLong(status.get("Innodb_log_waits"));
            addCheck(checks, "InnoDB Log Waits", "show global status like 'Innodb_log_waits'", "0 or few",
                    String.valueOf(innodbLogWaits), innodbLogWaits == 0);
            long tableLocksWaited = parseLong(status.get("Table_locks_waited"));
            addCheck(checks, "Table Locks Waited", "show global status like 'Table_locks_waited'", "0 or few",
                    String.valueOf(tableLocksWaited), tableLocksWaited == 0);
            addCheck(checks, "Binary Log", "show global variables like 'log_bin'", "Monitor continuously",
                    blankToUnknown(variables.get("log_bin")), true);
            addCheck(checks, "Read Only", "read_only / super_read_only", "Monitor continuously",
                    blankToUnknown(variables.get("read_only")) + " / " + blankToUnknown(variables.get("super_read_only")), true);
            long connectionErrors = connectionErrorCount(status);
            addCheck(checks, "Connection Errors", "Connection_errors_*", "0 or few",
                    String.valueOf(connectionErrors), connectionErrors == 0);
            long abortedConnects = parseLong(status.get("Aborted_connects"));
            addCheck(checks, "失败连接数", "show global status like 'Aborted_connects'", "建议持续关注",
                    String.valueOf(abortedConnects), true);
            long slowQueries = parseLong(status.get("Slow_queries"));
            addCheck(checks, "慢查询数", "show global status like 'Slow_queries'", "建议持续关注",
                    String.valueOf(slowQueries), true);
            addCheck(checks, "InnoDB 状态", "show engines", "InnoDB 应可用",
                    queryInnoDbSupport(conn), queryInnoDbSupport(conn).toUpperCase(Locale.ROOT).contains("YES")
                            || queryInnoDbSupport(conn).toUpperCase(Locale.ROOT).contains("DEFAULT"));
            double maxDbUsage = adminRepositoryMaxUsage(conn);
            addCheck(checks, "最大库空间", "information_schema.tables", "应可统计",
                    String.format(Locale.ROOT, "%.2f GB", maxDbUsage), maxDbUsage >= 0);
            return checks;
        }
    }

    @Override
    public String loadRuntimeLog(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect))) {
            StringBuilder text = new StringBuilder();
            appendSection(text, "PROCESSLIST", queryRows(conn, "show full processlist", 200));
            appendSection(text, "GLOBAL STATUS", queryRows(conn, "show global status", 300));
            try {
                appendSection(text, "INNODB STATUS", queryRows(conn, "show engine innodb status", 20));
            } catch (SQLException e) {
                appendSection(text, "INNODB STATUS", e.getMessage());
            }
            return text.toString();
        }
    }

    @Override
    public List<ConfigEntry> loadConfigEntries(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect))) {
            List<ConfigEntry> rows = new ArrayList<>();
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("show global variables")) {
                while (rs.next()) {
                    rows.add(new ConfigEntry(rs.getString(1), rs.getString(2)));
                }
            }
            return rows;
        }
    }

    @Override
    public ConfigUpdateResult updateConfig(Connect connect, String paramName, String newValue) throws Exception {
        String name = paramName == null ? "" : paramName.trim();
        if (name.isEmpty()) {
            return new ConfigUpdateResult(ConfigUpdateStatus.FILE_ONLY, "Empty parameter name");
        }
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect));
             var stmt = conn.createStatement()) {
            stmt.execute("SET GLOBAL " + quoteSystemVariable(name) + " = " + mysqlValueLiteral(newValue));
            return new ConfigUpdateResult(ConfigUpdateStatus.APPLIED, "SET GLOBAL " + name + " applied");
        }
    }

    @Override
    public boolean isInstanceOnline(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect));
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select 1")) {
            return rs.next();
        }
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

    private static String qualify(String schemaName, String tableName) {
        String table = tableName == null ? "" : tableName.trim();
        if (table.isEmpty()) {
            return "";
        }
        if (table.contains(".")) {
            String[] parts = table.split("\\.");
            StringBuilder qualified = new StringBuilder();
            for (String part : parts) {
                String normalized = part.trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                if (!qualified.isEmpty()) {
                    qualified.append(".");
                }
                qualified.append(quoteIdentifier(normalized));
            }
            return qualified.toString();
        }
        String schema = schemaName == null ? "" : schemaName.trim();
        if (schema.isEmpty()) {
            return quoteIdentifier(table);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    private static String quoteIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.startsWith("`") && value.endsWith("`")) {
            return value;
        }
        return "`" + value.replace("`", "``") + "`";
    }

    private static String normalizeCreateDatabaseCharset(String charsetOption) {
        String charset = charsetOption == null ? "" : charsetOption.trim();
        int paren = charset.indexOf('(');
        if (paren >= 0) {
            charset = charset.substring(0, paren).trim();
        }
        return charset.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static String quoteAccount(String account) {
        String value = account == null ? "" : account.trim();
        int at = findAccountSeparator(value);
        if (at < 0) {
            return quoteAccountPart(value) + "@'%'";
        }
        return quoteAccountPart(value.substring(0, at)) + "@" + quoteAccountPart(value.substring(at + 1));
    }

    private static int findAccountSeparator(String value) {
        boolean inSingleQuote = false;
        boolean inBacktick = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'' && !inBacktick) {
                boolean escaped = i + 1 < value.length() && value.charAt(i + 1) == '\'';
                if (escaped) {
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (ch == '`' && !inSingleQuote) {
                inBacktick = !inBacktick;
            } else if (ch == '@' && !inSingleQuote && !inBacktick) {
                return i;
            }
        }
        return -1;
    }

    private static String quoteAccountPart(String part) {
        String value = part == null ? "" : part.trim();
        if ((value.startsWith("'") && value.endsWith("'") && value.length() >= 2)
                || (value.startsWith("`") && value.endsWith("`") && value.length() >= 2)) {
            value = value.substring(1, value.length() - 1);
        }
        return "'" + DatabasePlatform.escapeSqlString(value) + "'";
    }

    private static String normalizeObjectType(String objectType) {
        String type = objectType == null ? "" : objectType.trim().toLowerCase(Locale.ROOT);
        if (type.endsWith("s")) {
            return type.substring(0, type.length() - 1);
        }
        return type.isEmpty() ? "object" : type;
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

    private static ConnectionService connectionService() {
        try {
            return AppContext.get(ConnectionService.class);
        } catch (IllegalStateException e) {
            return new ConnectionServiceImpl();
        }
    }

    private double adminRepositoryMaxUsage(Connection conn) throws SQLException {
        return instanceAdminRepository.getMaxStorageSpaceUsage(conn);
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

    private static String percentText(long value, long total) {
        if (total <= 0) {
            return "0.00%";
        }
        return percentText((double) value * 100d / total);
    }

    private static String percentText(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }

    private static long connectionErrorCount(Map<String, String> status) {
        long total = 0;
        if (status == null) {
            return 0;
        }
        for (Map.Entry<String, String> entry : status.entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith("Connection_errors_")) {
                total += parseLong(entry.getValue());
            }
        }
        return total;
    }

    private static String secondsText(String seconds) {
        long value = parseLong(seconds);
        return value + " sec";
    }

    private static Map<String, String> queryNameValue(Connection conn, String sql) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                values.put(rs.getString(1), rs.getString(2));
            }
        }
        return values;
    }

    private static String queryScalar(Connection conn, String sql) throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private static String queryInnoDbSupport(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("show engines")) {
            while (rs.next()) {
                if ("InnoDB".equalsIgnoreCase(rs.getString("Engine"))) {
                    return rs.getString("Support");
                }
            }
        }
        return "";
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

    private static String quoteSystemVariable(String name) {
        String value = name == null ? "" : name.trim();
        if (!value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid MySQL system variable: " + name);
        }
        return value;
    }

    private static String mysqlValueLiteral(String value) {
        String text = value == null ? "" : value.trim();
        if (text.matches("(?i)^(ON|OFF|TRUE|FALSE|DEFAULT|NULL)$") || text.matches("[-+]?\\d+(\\.\\d+)?")) {
            return text;
        }
        return "'" + DatabasePlatform.escapeSqlString(text) + "'";
    }
}
