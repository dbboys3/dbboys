package com.dbboys.impl.dialect.mysql;

import com.dbboys.api.ChangeDatabaseFailureKind;
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
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MysqlDialect implements DatabasePlatform, ConnectionSupport {
    private static final String DB_TYPE = "MYSQL";
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final String DEFAULT_DRIVER = "mysql-connector-j-8.0.32.jar";
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
        return "5.7 / 8.0";
    }

    @Override
    public String metadataTooltipCatalogLabel() {
        return "DATABASE";
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
}
