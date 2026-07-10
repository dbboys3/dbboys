package com.dbboys.dialect.oracle;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.ConnectionService;
import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.app.AppContext;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.model.Connect;
import com.dbboys.model.Catalog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle 方言占位：建连参数与驱动占位，会话初始化暂不实现。 */
public final class OracleDialect implements DatabasePlatform, ConnectionSupport, InstanceTabCapability {

    private static final String DB_TYPE = "ORACLE";
    private static final String DRIVER_CLASS = "oracle.jdbc.OracleDriver";
    private static final String DEFAULT_CONNECTION_PROPS = buildDefaultConnectionProps(
            "AccumulateBatchResult",
            "autoCommit",
            "com.sun.jndi.ldap.connect.timeout",
            "com.sun.jndi.ldap.read.timeout",
            "database",
            "defaultExecuteBatch",
            "defaultNChar",
            "defaultRowPrefetch",
            "disableDefineColumnType",
            "DMSName",
            "DMSType",
            "fixedString",
            "includeSynonyms",
            "internal_logon",
            "javax.net.ssl.keyStore",
            "javax.net.ssl.keyStorePassword",
            "javax.net.ssl.keyStoreType",
            "javax.net.ssl.trustStore",
            "javax.net.ssl.trustStorePassword",
            "javax.net.ssl.trustStoreType",
            "JDBCDriverCharSetId",
            "mapStringParameterToCHAR",
            "OCIEnvHandle",
            "OCIErrHandle",
            "OCISvcCtxHandle",
            "oracle.jdbc.accessToken",
            "oracle.jdbc.allowedLogonVersion",
            "oracle.jdbc.allowMixingJdbcAndNamedBinds",
            "oracle.jdbc.allowSingleShardTransactionSupport",
            "oracle.jdbc.applicationContext",
            "oracle.jdbc.autoCommitSpecCompliant",
            "oracle.jdbc.azureCredentials",
            "oracle.jdbc.azureDatabaseApplicationIdUri",
            "oracle.jdbc.backwardCompatibileUpdateableResultSet",
            "oracle.jdbc.checkAuthResponseOnError",
            "oracle.jdbc.clientCertificate",
            "oracle.jdbc.clientCertificatePassword",
            "oracle.jdbc.clientId",
            "oracle.jdbc.clientSecret",
            "oracle.jdbc.commitOption",
            "oracle.jdbc.commitSelectOnAutocommit",
            "oracle.jdbc.config.file",
            "oracle.jdbc.configurationProviders",
            "oracle.jdbc.continueBatchOnError",
            "oracle.jdbc.convertNcharLiterals",
            "oracle.jdbc.createDescriptorUseCurrentSchemaForSchemaName",
            "oracle.jdbc.databaseStateRequirement",
            "oracle.jdbc.dcnOptions",
            "oracle.jdbc.debugJDWP",
            "oracle.jdbc.defaultConnectionValidation",
            "oracle.jdbc.defaultLobPrefetchSize",
            "oracle.jdbc.diagnostic.bufferSize",
            "oracle.jdbc.diagnostic.debugSQL",
            "oracle.jdbc.diagnostic.debugTenant",
            "oracle.jdbc.diagnostic.enableDiagnoseFirstFailure",
            "oracle.jdbc.diagnostic.enableLogging",
            "oracle.jdbc.diagnostic.enableObservability",
            "oracle.jdbc.diagnostic.enableSensitiveDiagnostics",
            "oracle.jdbc.diagnostic.loggerName",
            "oracle.jdbc.diagnostic.permitSensitiveDiagnostics",
            "oracle.jdbc.diagnostic.writeLogsToDiagnoseFirstFailure",
            "oracle.jdbc.disabledBugFixes",
            "oracle.jdbc.DMSStatementCachingMetrics",
            "oracle.jdbc.DMSStatementMetrics",
            "oracle.jdbc.DRCPConnectionClass",
            "oracle.jdbc.DRCPConnectionPurity",
            "oracle.jdbc.DRCPMultiplexingInRequestAPIs",
            "oracle.jdbc.DRCPPLSQLCallback",
            "oracle.jdbc.DRCPTagName",
            "oracle.jdbc.driverNameAttribute",
            "oracle.jdbc.editionName",
            "oracle.jdbc.enableACSupport",
            "oracle.jdbc.enableDataInLocator",
            "oracle.jdbc.enableErrorUrl",
            "oracle.jdbc.enableImplicitRequests",
            "oracle.jdbc.enableProviderOverride",
            "oracle.jdbc.enableQueryResultCache",
            "oracle.jdbc.enableReadDataInLocator",
            "oracle.jdbc.enableResultSetCache",
            "oracle.jdbc.enableSSSCursor",
            "oracle.jdbc.enableTempLobRefCnt",
            "oracle.jdbc.enableTGSupport",
            "oracle.jdbc.fanEnabled",
            "oracle.jdbc.fetchSizeTuning",
            "oracle.jdbc.implicitStatementCacheSize",
            "oracle.jdbc.inbandNotification",
            "oracle.jdbc.J2EE13Compliant",
            "oracle.jdbc.JDBCStandardBehavior",
            "oracle.jdbc.jsonDefaultGetObjectType",
            "oracle.jdbc.LobStreamPosStandardCompliant",
            "oracle.jdbc.localhostName",
            "oracle.jdbc.loginTimeout",
            "oracle.jdbc.mapDateToTimestamp",
            "oracle.jdbc.maxBatchMemory",
            "oracle.jdbc.maxCachedBufferSize",
            "oracle.jdbc.newPassword",
            "oracle.jdbc.ociCompartment",
            "oracle.jdbc.ociConfigFile",
            "oracle.jdbc.ociDatabase",
            "oracle.jdbc.ociIamUrl",
            "oracle.jdbc.ociProfile",
            "oracle.jdbc.ociTenancy",
            "oracle.jdbc.ons.protocol",
            "oracle.jdbc.ons.walletfile",
            "oracle.jdbc.ons.walletpassword",
            "oracle.jdbc.parameterMetadataCacheIncludeParsing",
            "oracle.jdbc.parameterMetadataCacheSize",
            "oracle.jdbc.parameterMetadataPreprocess",
            "oracle.jdbc.passwordAuthentication",
            "oracle.jdbc.provider.accessToken",
            "oracle.jdbc.provider.connectionString",
            "oracle.jdbc.provider.json",
            "oracle.jdbc.provider.password",
            "oracle.jdbc.provider.tlsConfiguration",
            "oracle.jdbc.provider.traceEventListener",
            "oracle.jdbc.provider.username",
            "oracle.jdbc.proxyClientName",
            "oracle.jdbc.queryResultCacheMaxLag",
            "oracle.jdbc.queryResultCacheMaxSize",
            "oracle.jdbc.readOnlyInstanceAllowed",
            "oracle.jdbc.ReadTimeout",
            "oracle.jdbc.redirectUri",
            "oracle.jdbc.remoteConfigurationFiltering",
            "oracle.jdbc.replay.protectedRequestSizeLimit",
            "oracle.jdbc.retainLobPrefetchData",
            "oracle.jdbc.RetainV9LongBindBehavior",
            "oracle.jdbc.sendAllDataForValueLobs",
            "oracle.jdbc.sendBooleanAsNativeBoolean",
            "oracle.jdbc.sendBooleanInPLSQL",
            "oracle.jdbc.sessionFixUpParams",
            "oracle.jdbc.sqlErrorTranslationFile",
            "oracle.jdbc.sqlTranslationProfile",
            "oracle.jdbc.StreamChunkSize",
            "oracle.jdbc.strictASCIIConversion",
            "oracle.jdbc.TcpNoDelay",
            "oracle.jdbc.tenantId",
            "oracle.jdbc.thinForceDNSLoadBalancing",
            "oracle.jdbc.timestampTzInGmt",
            "oracle.jdbc.timezoneAsRegion",
            "oracle.jdbc.tokenAuthentication",
            "oracle.jdbc.tokenLocation",
            "oracle.jdbc.use1900AsYearForTime",
            "oracle.jdbc.UseDRCPMultipletag",
            "oracle.jdbc.useNio",
            "oracle.jdbc.useShardingDriverConnection",
            "oracle.jdbc.useThreadLocalBufferCache",
            "oracle.jdbc.useTrueCacheDriverConnection",
            "oracle.jdbc.vectorDefaultGetObjectType",
            "oracle.jdbc.XAThroughSessionlessTransactions",
            "oracle.net.allow_weak_crypto",
            "oracle.net.authentication_services",
            "oracle.net.CONNECT_TIMEOUT",
            "oracle.net.connectionIdPrefix",
            "oracle.net.crypto_checksum_client",
            "oracle.net.crypto_checksum_types_client",
            "oracle.net.crypto_seed",
            "oracle.net.disableOob",
            "oracle.net.DOWN_HOSTS_TIMEOUT",
            "oracle.net.encryption_client",
            "oracle.net.encryption_types_client",
            "oracle.net.external_authentication",
            "oracle.net.httpsProxyHost",
            "oracle.net.httpsProxyPort",
            "oracle.net.keepAlive",
            "oracle.net.kerberos5_cc_name",
            "oracle.net.kerberos5_mutual_authentication",
            "oracle.net.KerberosJaasLoginModule",
            "oracle.net.KerberosRealm",
            "oracle.net.ldap.security.authentication",
            "oracle.net.ldap.security.credentials",
            "oracle.net.ldap.security.principal",
            "oracle.net.ldap.ssl.keyManagerFactory.algorithm",
            "oracle.net.ldap.ssl.keyStore",
            "oracle.net.ldap.ssl.keyStorePassword",
            "oracle.net.ldap.ssl.keyStoreType",
            "oracle.net.ldap.ssl.ssl_context_protocol",
            "oracle.net.ldap.ssl.supportedCiphers",
            "oracle.net.ldap.ssl.supportedVersions",
            "oracle.net.ldap.ssl.trustManagerFactory.algorithm",
            "oracle.net.ldap.ssl.trustStore",
            "oracle.net.ldap.ssl.trustStorePassword",
            "oracle.net.ldap.ssl.trustStoreType",
            "oracle.net.ldap.ssl.walletLocation",
            "oracle.net.ldap.ssl.walletPassword",
            "oracle.net.networkCompression",
            "oracle.net.networkCompressionLevels",
            "oracle.net.networkCompressionThreshold",
            "oracle.net.oldSyntax",
            "oracle.net.OUTBOUND_CONNECT_TIMEOUT",
            "oracle.net.profile",
            "oracle.net.proxyRemoteDNS",
            "oracle.net.radius_challenge_response_handler",
            "oracle.net.seps_wallet_location",
            "oracle.net.seps_wallet_password",
            "oracle.net.setFIPSMode",
            "oracle.net.SNIIgnoreList",
            "oracle.net.socksProxyHost",
            "oracle.net.socksProxyPort",
            "oracle.net.socksRemoteDNS",
            "oracle.net.ssl_allow_weak_dn_match",
            "oracle.net.ssl_certificate_alias",
            "oracle.net.ssl_certificate_thumbprint",
            "oracle.net.ssl_cipher_suites",
            "oracle.net.ssl_context_protocol",
            "oracle.net.ssl_pem_private_key_index",
            "oracle.net.ssl_server_cert_dn",
            "oracle.net.ssl_server_dn_match",
            "oracle.net.ssl_version",
            "oracle.net.sslContextCacheSize",
            "oracle.net.TCP_KEEPCOUNT",
            "oracle.net.TCP_KEEPIDLE",
            "oracle.net.TCP_KEEPINTERVAL",
            "oracle.net.tns_admin",
            "oracle.net.useJCEAPI",
            "oracle.net.useSNI",
            "oracle.net.useTcpFastOpen",
            "oracle.net.useZeroCopyIO",
            "oracle.net.wallet_location",
            "oracle.net.wallet_password",
            "oracle.net.websocketPassword",
            "oracle.net.websocketUser",
            "password",
            "prelim_auth",
            "processEscapes",
            "protocol",
            "remarksReporting",
            "RessourceManagerId",
            "restrictGetTables",
            "server",
            "SetFloatAndDoubleUseBinary",
            "ssl.keyManagerFactory.algorithm",
            "ssl.trustManagerFactory.algorithm",
            "useFetchSizeWithLongColumn",
            "user",
            "v$session.ename",
            "v$session.iname",
            "v$session.machine",
            "v$session.osuser",
            "v$session.process",
            "v$session.program",
            "v$session.terminal"
    );
    private static final String SQL_INSTANCE_INFO = """
            select
                instance_name,
                host_name,
                version,
                status,
                parallel,
                archiver,
                to_char(startup_time, 'YYYY-MM-DD HH24:MI:SS') as startup_time
            from v$instance
            """;
    private static final String SQL_DATABASE_INFO = """
            select
                name,
                db_unique_name,
                open_mode,
                database_role,
                log_mode,
                flashback_on,
                platform_name,
                to_char(created, 'YYYY-MM-DD HH24:MI:SS') as created_time
            from v$database
            """;
    private static final String SQL_SESSION_ROLES = """
            select role
            from session_roles
            order by role
            """;
    private static final String SQL_SESSION_PRIVS = """
            select privilege
            from session_privs
            order by privilege
            """;

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
    public boolean supportsServiceName() {
        return true;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        if (connect.getSessionCatalog() == null || connect.getSessionCatalog().isBlank()) {
            String username = connect.getUsername();
            if (username != null && !username.isBlank()) {
                connect.setSessionCatalog(username.toUpperCase());
            }
        }
        String host = connect.getIp() != null ? connect.getIp() : "localhost";
        String port = connect.getPort() != null && !connect.getPort().isEmpty() ? connect.getPort() : "1521";
        String database = connect.getCatalog() != null ? connect.getCatalog() : "ORCL";
        String url = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        String jarFilePath = "file:extlib/" + DB_TYPE + "/" + (connect.getDriver() != null && !connect.getDriver().isEmpty() ? connect.getDriver() : "ojdbc8.jar");
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        String sessionCatalog = getSessionCatalog(connect);
        if (sessionCatalog != null && !sessionCatalog.isBlank()) {
            metadataRepository.setDatabase(conn, sessionCatalog);
            connect.setSessionCatalog(sessionCatalog);
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
    public boolean supportsHealthCheckTab(Connect connect) {
        return resolveOracleAdminPrivileges(connect).canViewInstance();
    }

    @Override
    public boolean supportsLogTab(Connect connect) {
        return resolveOracleAdminPrivileges(connect).canViewInstance();
    }

    @Override
    public boolean supportsConfigTab(Connect connect) {
        return resolveOracleAdminPrivileges(connect).canViewInstance();
    }

    @Override
    public boolean canEditConfig(Connect connect) {
        return resolveOracleAdminPrivileges(connect).canManageConfig();
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
        String catalog = connect.getCatalog();
        if (catalog != null && !catalog.isBlank()) {
            return catalog.trim();
        }
        return InstanceTabCapability.extractInfoValue(connect.getInfo(), "instance_name");
    }

    @Override
    public SpaceLabels spaceLabels(Connect connect) {
        return new SpaceLabels(
                "",
                "",
                "instance.space.oracle.chart.dbspace",
                "Tablespace Usage(GB)",
                "instance.space.oracle.chart.chunk",
                "Datafile Usage(GB)",
                "instance.space.oracle.chart.database",
                "Schema Usage(GB)",
                "instance.space.oracle.chart.table",
                "Segment Usage TOP20(GB)",
                "instance.space.oracle.unused_allocated",
                "已分配未使用"
        );
    }

    @Override
    public CheckTableModel buildCheckTable(Connect connect) {
        List<CheckColumn> columns = List.of(
                new CheckColumn("entry", "instance.check.oracle.column.item", "检查项", CheckColumnKind.TEXT, 220),
                new CheckColumn("currentValue", "instance.check.oracle.column.current", "当前值", CheckColumnKind.TEXT, 420),
                new CheckColumn("expectedValue", "instance.check.oracle.column.expected", "期望值", CheckColumnKind.TEXT, 260),
                new CheckColumn("status", "instance.check.oracle.column.result", "结果", CheckColumnKind.STATUS, 100)
        );

        Map<String, String> infoMap = new LinkedHashMap<>(parseOracleInfo(connect == null ? "" : connect.getInfo()));
        List<OracleWaitClassRow> waitClassRows = new ArrayList<>();
        if (connect != null) {
            try {
                ConnectionService connectionService = AppContext.get(ConnectionService.class);
                try (Connection jdbc = connectionService.getConnectionWithSessionInit(connect)) {
                    collectOracleInspectionMetrics(jdbc).forEach((k, v) -> {
                        if (v != null && !v.isBlank()) {
                            infoMap.put(k.toLowerCase(java.util.Locale.ROOT), v.trim());
                        }
                    });
                    waitClassRows.addAll(queryOracleWaitClassRows(jdbc));
                }
            } catch (Exception ignored) {
                // 未连接或连接失败时仅依赖已缓存的 connect.getInfo()
            }
        }
        String databaseRole = trimToEmpty(infoMap.get("database_role"));
        String expectedOpenMode = expectedOpenMode(databaseRole);
        String expectedInstanceStatus = expectedInstanceStatus(databaseRole);
        String expectedArchiveLog = expectedArchiveLog(databaseRole);
        String logMode = trimToEmpty(infoMap.get("log_mode"));
        String archDest = trimToEmpty(infoMap.get("arch_dest1"));
        String archState = trimToEmpty(infoMap.get("arch_dest_state1"));
        String archiveDestDisplay = formatArchiveDestDisplay(archDest, archState);

        List<CheckRow> rows = new ArrayList<>(List.of(
                buildOracleCheckRow("主机名",
                        "instance.check.oracle.item.host_name",
                        infoMap.get("host_name"),
                        "应可获取",
                        "instance.check.oracle.expected.available",
                        evaluatePresent(infoMap.get("host_name"))),
                buildOracleCheckRow("版本",
                        "instance.check.oracle.item.version",
                        infoMap.get("version"),
                        "应可获取",
                        "instance.check.oracle.expected.available",
                        evaluatePresent(infoMap.get("version"))),
                buildOracleCheckRow("数据库名",
                        "instance.check.oracle.item.name",
                        infoMap.get("name"),
                        "应可获取",
                        "instance.check.oracle.expected.available",
                        evaluatePresent(infoMap.get("name"))),
                buildOracleCheckRow("数据库唯一名",
                        "instance.check.oracle.item.db_unique_name",
                        infoMap.get("db_unique_name"),
                        "应可获取",
                        "instance.check.oracle.expected.available",
                        evaluatePresent(infoMap.get("db_unique_name"))),
                buildOracleCheckRow("实例状态",
                        "instance.check.item.instance_status",
                        infoMap.get("status"),
                        expectedInstanceStatus,
                        "",
                        evaluateInstanceStatus(infoMap.get("status"), databaseRole)),
                buildOracleCheckRow("数据库打开模式",
                        "instance.check.oracle.item.open_mode",
                        infoMap.get("open_mode"),
                        expectedOpenMode,
                        "",
                        evaluateOpenMode(infoMap.get("open_mode"), databaseRole)),
                buildOracleCheckRow("数据库角色",
                        "instance.check.oracle.item.database_role",
                        infoMap.get("database_role"),
                        "PRIMARY / PHYSICAL STANDBY / LOGICAL STANDBY",
                        "",
                        evaluatePresent(infoMap.get("database_role"))),
                buildOracleCheckRow("归档模式",
                        "instance.check.oracle.item.log_mode",
                        infoMap.get("log_mode"),
                        expectedArchiveLog,
                        "",
                        evaluateArchiveLog(infoMap.get("log_mode"), databaseRole)),
                buildOracleCheckRow("闪回状态",
                        "instance.check.oracle.item.flashback_on",
                        infoMap.get("flashback_on"),
                        "YES / RESTORE POINT ONLY",
                        "",
                        evaluateFlashback(infoMap.get("flashback_on"))),
                buildOracleCheckRow("归档进程",
                        "instance.check.oracle.item.archiver",
                        infoMap.get("archiver"),
                        "STARTED",
                        "",
                        evaluateEquals(infoMap.get("archiver"), "STARTED")),
                buildOracleCheckRow("并行模式",
                        "instance.check.oracle.item.parallel",
                        infoMap.get("parallel"),
                        "YES / NO",
                        "",
                        evaluatePresent(infoMap.get("parallel"))),
                buildOracleCheckRow("启动时间",
                        "instance.check.oracle.item.startup_time",
                        infoMap.get("startup_time"),
                        "应可获取",
                        "instance.check.oracle.expected.available",
                        evaluatePresent(infoMap.get("startup_time"))),
                buildOracleCheckRow("创建时间",
                        "instance.check.oracle.item.created_time",
                        infoMap.get("created_time"),
                        "应可获取",
                        "instance.check.oracle.expected.available",
                        evaluatePresent(infoMap.get("created_time"))),
                buildOracleCheckRow("平台",
                        "instance.check.oracle.item.platform_name",
                        infoMap.get("platform_name"),
                        "应可获取",
                        "instance.check.oracle.expected.available",
                        evaluatePresent(infoMap.get("platform_name")))
        ));
        rows.add(buildOracleCheckRow("表空间最大使用率(%)",
                "instance.check.oracle.item.ts_max_used_pct",
                infoMap.get("ts_max_used_pct"),
                "<90%",
                "instance.check.oracle.expected.ts_max_lt_90",
                evaluateOracleTsMaxUsedPct(infoMap.get("ts_max_used_pct"))));
        rows.add(buildOracleCheckRow("会话连接数(当前/上限)",
                "instance.check.oracle.item.sessions_usage",
                infoMap.get("sess_usage"),
                "<85%上限",
                "instance.check.oracle.expected.sessions_lt_85",
                evaluateOracleSessionsUsage(infoMap.get("sess_usage"))));
        rows.add(buildOracleCheckRow("事务提交数(累计)",
                "instance.check.oracle.item.user_commits",
                infoMap.get("usr_commit_cnt"),
                "应可读取统计",
                "instance.check.oracle.expected.stat_readable",
                evaluatePresent(infoMap.get("usr_commit_cnt"))));
        rows.add(buildOracleCheckRow("数据文件检查点时间",
                "instance.check.oracle.item.checkpoint",
                infoMap.get("datafile_ckpt_time"),
                "应可获取",
                "instance.check.oracle.expected.ckpt_available",
                evaluatePresent(infoMap.get("datafile_ckpt_time"))));
        rows.add(buildOracleCheckRow("最近RMAN备份完成时间",
                "instance.check.oracle.item.rman_backup",
                infoMap.get("rman_backup_end"),
                "建议7天内有备份",
                "instance.check.oracle.expected.backup_within_days",
                evaluateOracleBackupRecency(infoMap.get("rman_backup_end"))));
        rows.add(buildOracleCheckRow("自动归档路径与状态",
                "instance.check.oracle.item.archive_dest",
                archiveDestDisplay,
                "ARCHIVELOG时路径有效且启用",
                "instance.check.oracle.expected.archive_dest_ok",
                evaluateOracleArchiveDest(logMode, archDest, archState)));
        rows.add(buildOracleCheckRow("非空闲等待会话数",
                "instance.check.oracle.item.wait_sess",
                infoMap.get("wait_sess_cnt"),
                "建议<500（视负载）",
                "instance.check.oracle.expected.wait_sess_normal",
                evaluateOracleWaitSessionCount(infoMap.get("wait_sess_cnt"))));
        for (OracleWaitClassRow wc : waitClassRows) {
            String entryLabel = "等待类 · " + wc.className();
            String current = formatOracleWaitClassCurrent(wc);
            rows.add(buildOracleCheckRow(entryLabel,
                    "",
                    current,
                    "应可统计次数与时间",
                    "instance.check.oracle.expected.wait_class_line",
                    evaluatePresent(current)));
        }
        rows.add(buildOracleCheckRow("等待事件累计次数(Top)",
                "instance.check.oracle.item.wait_events_top",
                infoMap.get("wait_events_top"),
                "应可读取v$system_event",
                "instance.check.oracle.expected.wait_events_readable",
                evaluatePresent(infoMap.get("wait_events_top"))));
        return new CheckTableModel(columns, rows);
    }

    @Override
    public List<ConfigEntry> loadConfigEntries(Connect connect) throws Exception {
        return com.dbboys.infra.util.InstanceRuntimeUtil.loadOracleConfigEntries(connect);
    }

    @Override
    public String loadRuntimeLog(Connect connect) throws Exception {
        return com.dbboys.infra.util.InstanceRuntimeUtil.loadOracleRuntimeLog(connect);
    }

    @Override
    public boolean isInstanceOnline(Connect connect) throws Exception {
        return com.dbboys.infra.util.InstanceRuntimeUtil.isOracleInstanceOnline(connect);
    }

    @Override
    public ConfigUpdateResult updateConfig(Connect connect, String paramName, String newValue) throws Exception {
        return com.dbboys.infra.util.InstanceMutationUtil.updateOracleConfig(connect, paramName, newValue);
    }

    @Override
    public void startInstance(Connect connect) throws Exception {
        com.dbboys.infra.util.InstanceMutationUtil.startOracleInstance(connect);
    }

    @Override
    public void stopInstance(Connect connect) throws Exception {
        com.dbboys.infra.util.InstanceMutationUtil.stopOracleInstance(connect);
    }

    @Override
    public String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        if (connection == null || connect == null) {
            return "";
        }
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        connect.setDbversion((metaData.getDatabaseProductName() == null ? "" : metaData.getDatabaseProductName()) + " "
                + (metaData.getDatabaseProductVersion() == null ? "" : metaData.getDatabaseProductVersion()));

        StringBuilder info = new StringBuilder();
        String primaryInstance = "";

        appendOptionalOracleSection(info, "Instance Information", connection, SQL_INSTANCE_INFO);
        appendOptionalOracleSection(info, "Database Information", connection, SQL_DATABASE_INFO);
        appendOracleInspectionSnapshot(info, connection);
        appendOraclePrivilegeSection(info, detectOracleAdminPrivileges(connection, connect));

        connect.setInfo(info.toString());
        return primaryInstance;
    }

    @Override
    public ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        if (e == null) {
            return ChangeDatabaseFailureKind.OTHER;
        }
        int code = e.getErrorCode();
        if (code == 1012 || code == 3113 || code == 3114 || code == 17008) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        return ChangeDatabaseFailureKind.OTHER;
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
    public boolean supportsSchedulerJobsFolder() {
        return true;
    }

    @Override
    public boolean supportsRecycleBinFolder() {
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
        return "妯″紡宸插鍑哄埌锛?s";
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
        String schema = database.getName().trim().toUpperCase();
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        StringBuilder sb = new StringBuilder();
        sb.append("-- ############################################################\n");
        sb.append("-- ### Oracle Schema DDL Export\n");
        sb.append("-- ### Schema   : ").append(schema).append("\n");
        sb.append("-- ### Datetime : ").append(dateStr).append("\n");
        sb.append("-- ############################################################\n\n");
        sb.append("CREATE USER ").append(schema).append(" IDENTIFIED BY ").append(schema)
                .append(" QUOTA UNLIMITED ON USERS;\n\n");
        return sb.toString();
    }

    @Override
    public boolean supportsTableTypeModification() {
        return false;
    }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        String q = qualifiedTable == null ? "" : qualifiedTable.trim();
        return "SELECT ROWID, t.* FROM " + q + " t;";
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
    public boolean supportsRenameDatabaseNode() {
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
    public String createUserSql(String userName, String password) {
        String escapedPassword = "\"" + (password == null ? "" : password.trim()).replace("\"", "\"\"") + "\"";
        return "CREATE USER " + userName + " IDENTIFIED BY " + escapedPassword + " QUOTA UNLIMITED ON USERS";
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
    public String metadataTooltipCatalogLabel() {
        return "SCHEMA";
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

    /** 建连时写入巡检用动态指标；打开巡检页时 {@link #buildCheckTable} 会再次实时查询覆盖。 */
    private void appendOracleInspectionSnapshot(StringBuilder info, Connection connection) {
        collectOracleInspectionMetrics(connection).forEach((key, value) -> appendInfoLine(info, key, value));
    }

    /**
     * 巡检页与建连信息共用的指标采集；会话资源名在库中多为大写，故用 UPPER 比较。
     */
    private Map<String, String> collectOracleInspectionMetrics(Connection connection) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        String ts = firstNonBlank(
                queryScalar(connection,
                        "SELECT TO_CHAR(ROUND(MAX(used_percent), 2)) FROM dba_tablespace_usage_metrics"),
                queryScalar(connection,
                        "SELECT TO_CHAR(ROUND(MAX(used_percent), 2)) FROM cdb_tablespace_usage_metrics"));
        putIfNonBlank(m, "ts_max_used_pct", ts);

        putIfNonBlank(m, "sess_usage", queryScalar(connection,
                "SELECT current_utilization || '/' || limit_value FROM v$resource_limit "
                        + "WHERE UPPER(TRIM(resource_name)) = 'SESSIONS'"));

        putIfNonBlank(m, "usr_commit_cnt", queryScalar(connection,
                "SELECT value FROM v$sysstat WHERE LOWER(TRIM(name)) = 'user commits'"));

        putIfNonBlank(m, "datafile_ckpt_time", queryScalar(connection,
                "SELECT TO_CHAR(MAX(checkpoint_time), 'YYYY-MM-DD HH24:MI:SS') FROM v$datafile_header "
                        + "WHERE checkpoint_time IS NOT NULL"));

        putIfNonBlank(m, "rman_backup_end", queryScalar(connection,
                "SELECT TO_CHAR(MAX(end_time), 'YYYY-MM-DD HH24:MI:SS') FROM v$rman_backup_job_details "
                        + "WHERE UPPER(NVL(status, '')) LIKE 'COMPLETED%'"));

        putIfNonBlank(m, "arch_dest1", firstNonBlank(
                queryScalar(connection,
                        "SELECT value FROM v$parameter WHERE LOWER(TRIM(name)) = 'log_archive_dest_1'"),
                queryScalar(connection,
                        "SELECT value FROM gv$parameter WHERE LOWER(TRIM(name)) = 'log_archive_dest_1' "
                                + "AND inst_id = (SELECT instance_number FROM v$instance WHERE ROWNUM = 1)")));

        putIfNonBlank(m, "arch_dest_state1", firstNonBlank(
                queryScalar(connection,
                        "SELECT value FROM v$parameter WHERE LOWER(TRIM(name)) = 'log_archive_dest_state_1'"),
                queryScalar(connection,
                        "SELECT value FROM gv$parameter WHERE LOWER(TRIM(name)) = 'log_archive_dest_state_1' "
                                + "AND inst_id = (SELECT instance_number FROM v$instance WHERE ROWNUM = 1)")));

        putIfNonBlank(m, "wait_sess_cnt", queryScalar(connection,
                "SELECT TO_CHAR(COUNT(*)) FROM v$session WHERE status = 'WAITING' "
                        + "AND NVL(wait_class, 'x') <> 'Idle'"));
        putIfNonBlank(m, "wait_events_top", buildOracleWaitEventsTop(connection));
        return m;
    }

    private record OracleWaitClassRow(String className, long totalWaits, double waitSeconds) {
    }

    private List<OracleWaitClassRow> queryOracleWaitClassRows(Connection connection) {
        List<OracleWaitClassRow> out = new ArrayList<>();
        if (connection == null) {
            return out;
        }
        String sql = """
                SELECT wait_class,
                       SUM(total_waits) AS tw,
                       ROUND(SUM(NVL(time_waited_micro, NVL(time_waited, 0) * 10000)) / 1000000, 4) AS wsec
                FROM v$system_event
                WHERE NVL(wait_class, 'Other') <> 'Idle'
                GROUP BY wait_class
                ORDER BY SUM(NVL(time_waited_micro, NVL(time_waited, 0) * 10000)) DESC NULLS LAST,
                         SUM(total_waits) DESC
                """;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String wc = trimToEmptyStatic(rs.getString(1));
                if (wc.isEmpty()) {
                    continue;
                }
                long tw = rs.getLong(2);
                double sec = rs.getDouble(3);
                if (rs.wasNull()) {
                    sec = 0;
                }
                out.add(new OracleWaitClassRow(wc, tw, sec));
            }
        } catch (SQLException ignored) {
            // 无 wait_class / time_waited_micro 等列时跳过
        }
        return out;
    }

    private static String formatOracleWaitClassCurrent(OracleWaitClassRow wc) {
        return String.format("累计 %,d 次；等待时间约 %,.2f 秒", wc.totalWaits(), wc.waitSeconds());
    }

    /** 非 Idle 等待事件中 total_waits 最高的若干项：事件名=累计次数。 */
    private static String buildOracleWaitEventsTop(Connection connection) {
        if (connection == null) {
            return null;
        }
        String sql = """
                SELECT event, total_waits
                FROM (
                    SELECT event, total_waits,
                           ROW_NUMBER() OVER (ORDER BY total_waits DESC) rn
                    FROM v$system_event
                    WHERE NVL(wait_class, 'Other') <> 'Idle'
                ) sub
                WHERE sub.rn <= 18
                ORDER BY sub.total_waits DESC
                """;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                String ev = trimToEmptyStatic(rs.getString(1));
                if (ev.length() > 42) {
                    ev = ev.substring(0, 39) + "...";
                }
                sb.append(ev).append('=').append(rs.getString(2));
            }
            String out = sb.length() > 0 ? sb.toString() : null;
            return out == null ? null : truncateMetric(out, 900);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static String truncateMetric(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen - 3) + "...";
    }

    private static String queryScalar(Connection connection, String sql) {
        if (connection == null || sql == null || sql.isBlank()) {
            return null;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (SQLException ignored) {
            // 权限、PDB、版本差异
        }
        return null;
    }

    private static void putIfNonBlank(Map<String, String> m, String key, String value) {
        if (value == null) {
            return;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return;
        }
        m.put(key, t);
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return null;
    }

    private static String formatArchiveDestDisplay(String dest, String state) {
        String d = trimToEmptyStatic(dest);
        String s = trimToEmptyStatic(state);
        if (d.isEmpty() && s.isEmpty()) {
            return "";
        }
        if (s.isEmpty()) {
            return d;
        }
        if (d.isEmpty()) {
            return s;
        }
        return d + " | " + s;
    }

    private String evaluateOracleTsMaxUsedPct(String raw) {
        String v = trimToEmpty(raw);
        if (v.isEmpty()) {
            return "2";
        }
        try {
            double d = Double.parseDouble(v.replace(',', '.'));
            return d >= 90.0 ? "1" : "0";
        } catch (NumberFormatException e) {
            return "2";
        }
    }

    private String evaluateOracleSessionsUsage(String raw) {
        String s = trimToEmpty(raw);
        if (s.isEmpty()) {
            return "2";
        }
        int slash = s.indexOf('/');
        if (slash <= 0 || slash >= s.length() - 1) {
            return "2";
        }
        try {
            long cur = Long.parseLong(s.substring(0, slash).trim());
            String maxPart = s.substring(slash + 1).trim();
            if (maxPart.isEmpty()) {
                return "2";
            }
            if ("UNLIMITED".equalsIgnoreCase(maxPart)) {
                return "0";
            }
            long lim = Long.parseLong(maxPart);
            if (lim <= 0) {
                return "0";
            }
            return (cur * 100.0 / lim) >= 85.0 ? "1" : "0";
        } catch (NumberFormatException e) {
            return "2";
        }
    }

    private String evaluateOracleBackupRecency(String raw) {
        String s = trimToEmpty(raw);
        if (s.isEmpty()) {
            return "1";
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date d = fmt.parse(s);
            long days = (System.currentTimeMillis() - d.getTime()) / 86400000L;
            return days > 7 ? "1" : "0";
        } catch (ParseException e) {
            return "0";
        }
    }

    private String evaluateOracleWaitSessionCount(String raw) {
        String v = trimToEmpty(raw);
        if (v.isEmpty()) {
            return "2";
        }
        try {
            long n = Long.parseLong(v.replace(",", ""));
            return n > 500 ? "1" : "0";
        } catch (NumberFormatException e) {
            return "2";
        }
    }

    private String evaluateOracleArchiveDest(String logMode, String dest, String state) {
        String lm = trimToEmpty(logMode).toUpperCase(java.util.Locale.ROOT);
        if (lm.isEmpty()) {
            return "2";
        }
        if ("NOARCHIVELOG".equals(lm)) {
            return "0";
        }
        if (!"ARCHIVELOG".equals(lm)) {
            return "1";
        }
        String st = trimToEmpty(state).toUpperCase(java.util.Locale.ROOT);
        if (st.contains("DEFER")) {
            return "1";
        }
        String d = trimToEmpty(dest);
        if (d.isEmpty() && (st.isEmpty() || st.contains("RESET"))) {
            return "1";
        }
        return "0";
    }

    private void appendOptionalOracleSection(StringBuilder info,
                                             String title,
                                             Connection connection,
                                             String sql) {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            if (!rs.next()) {
                return;
            }
            info.append("\n##########################################################################################\n");
            info.append(title).append("\n");
            info.append("##########################################################################################\n");
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                appendInfoLine(info, rs.getMetaData().getColumnLabel(i), rs.getString(i));
            }
        } catch (SQLException ignored) {
            // Optional Oracle views like v$instance / v$database may be inaccessible to low-privilege accounts.
        }
    }

    private void appendInfoLine(StringBuilder info, String label, String value) {
        String normalizedValue = trimToEmpty(value);
        if (label == null || label.isBlank() || normalizedValue.isEmpty()) {
            return;
        }
        info.append(String.format("%-30s", label.trim())).append(normalizedValue).append("\n");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    static OracleAdminPrivileges resolveOracleAdminPrivileges(Connect connect) {
        String info = connect == null ? "" : connect.getInfo();
        boolean canViewInstance = parseBooleanFlag(info, "can_view_instance");
        boolean canViewSpace = parseBooleanFlag(info, "can_view_space_manager");
        boolean canMutateSpace = parseBooleanFlag(info, "can_mutate_space");
        boolean canManageConfig = parseBooleanFlag(info, "can_manage_config");
        boolean canStartStopInstance = parseBooleanFlag(info, "can_start_stop_instance");
        boolean hasDetectedFlags = containsPrivilegeFlags(info);

        if (hasDetectedFlags) {
            return new OracleAdminPrivileges(
                    parseBooleanFlag(info, "oracle_admin_user"),
                    parseBooleanFlag(info, "has_dba_role"),
                    parseBooleanFlag(info, "has_select_catalog_role"),
                    parseBooleanFlag(info, "has_alter_tablespace"),
                    parseBooleanFlag(info, "has_create_tablespace"),
                    parseBooleanFlag(info, "has_drop_tablespace"),
                    parseBooleanFlag(info, "has_alter_database"),
                    parseBooleanFlag(info, "has_alter_system"),
                    canViewInstance,
                    canViewSpace,
                    canMutateSpace,
                    canManageConfig,
                    canStartStopInstance,
                    Set.of(),
                    Set.of()
            );
        }

        String username = connect == null ? "" : trimToEmptyStatic(connect.getUsername());
        boolean privilegedUser = "sys".equalsIgnoreCase(username) || "system".equalsIgnoreCase(username);
        return new OracleAdminPrivileges(
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                privilegedUser,
                Set.of(),
                Set.of()
        );
    }

    private OracleAdminPrivileges detectOracleAdminPrivileges(Connection connection, Connect connect) {
        Set<String> roles = loadOracleAuthoritySet(connection, SQL_SESSION_ROLES);
        Set<String> privileges = loadOracleAuthoritySet(connection, SQL_SESSION_PRIVS);
        String username = connect == null ? "" : trimToEmpty(connect.getUsername());
        boolean privilegedUser = "sys".equalsIgnoreCase(username) || "system".equalsIgnoreCase(username);
        boolean hasDbaRole = privilegedUser || roles.contains("DBA");
        boolean hasSelectCatalogRole = privilegedUser || roles.contains("SELECT_CATALOG_ROLE");
        boolean hasAlterTablespace = privilegedUser || privileges.contains("ALTER TABLESPACE");
        boolean hasCreateTablespace = privilegedUser || privileges.contains("CREATE TABLESPACE");
        boolean hasDropTablespace = privilegedUser || privileges.contains("DROP TABLESPACE");
        boolean hasAlterDatabase = privilegedUser || privileges.contains("ALTER DATABASE");
        boolean hasAlterSystem = privilegedUser || privileges.contains("ALTER SYSTEM");
        boolean canViewInstance = privilegedUser || hasDbaRole || hasSelectCatalogRole || hasAlterDatabase;
        boolean canViewSpace = privilegedUser || hasDbaRole || hasSelectCatalogRole || hasAlterTablespace || hasCreateTablespace || hasDropTablespace;
        boolean canMutateSpace = privilegedUser || hasDbaRole || hasAlterTablespace || hasCreateTablespace || hasDropTablespace;
        boolean canManageConfig = privilegedUser || hasDbaRole || hasAlterSystem;
        boolean canStartStopInstance = privilegedUser || hasDbaRole || hasAlterDatabase;
        return new OracleAdminPrivileges(
                privilegedUser,
                hasDbaRole,
                hasSelectCatalogRole,
                hasAlterTablespace,
                hasCreateTablespace,
                hasDropTablespace,
                hasAlterDatabase,
                hasAlterSystem,
                canViewInstance,
                canViewSpace,
                canMutateSpace,
                canManageConfig,
                canStartStopInstance,
                roles,
                privileges
        );
    }

    private void appendOraclePrivilegeSection(StringBuilder info, OracleAdminPrivileges privileges) {
        if (info == null || privileges == null) {
            return;
        }
        info.append("\n##########################################################################################\n");
        info.append("Privilege Information\n");
        info.append("##########################################################################################\n");
        appendInfoLine(info, "oracle_admin_user", toFlag(privileges.adminUser()));
        appendInfoLine(info, "has_dba_role", toFlag(privileges.hasDbaRole()));
        appendInfoLine(info, "has_select_catalog_role", toFlag(privileges.hasSelectCatalogRole()));
        appendInfoLine(info, "has_alter_tablespace", toFlag(privileges.hasAlterTablespace()));
        appendInfoLine(info, "has_create_tablespace", toFlag(privileges.hasCreateTablespace()));
        appendInfoLine(info, "has_drop_tablespace", toFlag(privileges.hasDropTablespace()));
        appendInfoLine(info, "has_alter_database", toFlag(privileges.hasAlterDatabase()));
        appendInfoLine(info, "has_alter_system", toFlag(privileges.hasAlterSystem()));
        appendInfoLine(info, "can_view_instance", toFlag(privileges.canViewInstance()));
        appendInfoLine(info, "can_view_space_manager", toFlag(privileges.canViewSpaceManager()));
        appendInfoLine(info, "can_mutate_space", toFlag(privileges.canMutateSpace()));
        appendInfoLine(info, "can_manage_config", toFlag(privileges.canManageConfig()));
        appendInfoLine(info, "can_start_stop_instance", toFlag(privileges.canStartStopInstance()));
        appendInfoLine(info, "session_roles", joinAuthorities(privileges.roles()));
        appendInfoLine(info, "session_privs", joinAuthorities(privileges.privileges()));
    }

    private Set<String> loadOracleAuthoritySet(Connection connection, String sql) {
        Set<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (connection == null || sql == null || sql.isBlank()) {
            return values;
        }
        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String value = trimToEmpty(rs.getString(1)).toUpperCase(java.util.Locale.ROOT);
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        } catch (SQLException ignored) {
            // Low-privilege Oracle users may not see all role/privilege views.
        }
        return values;
    }

    private void appendInfoLine(StringBuilder info, String label, boolean value) {
        appendInfoLine(info, label, toFlag(value));
    }

    private static boolean parseBooleanFlag(String info, String key) {
        return "YES".equalsIgnoreCase(InstanceTabCapability.extractInfoValue(info, key));
    }

    private static boolean containsPrivilegeFlags(String info) {
        return !InstanceTabCapability.extractInfoValue(info, "can_view_instance").isEmpty()
                || !InstanceTabCapability.extractInfoValue(info, "can_view_space_manager").isEmpty()
                || !InstanceTabCapability.extractInfoValue(info, "can_mutate_space").isEmpty()
                || !InstanceTabCapability.extractInfoValue(info, "can_manage_config").isEmpty()
                || !InstanceTabCapability.extractInfoValue(info, "can_start_stop_instance").isEmpty();
    }

    private static String toFlag(boolean value) {
        return value ? "YES" : "NO";
    }

    private static String joinAuthorities(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
    }

    private static String trimToEmptyStatic(String value) {
        return value == null ? "" : value.trim();
    }

    private CheckRow buildOracleCheckRow(String entry,
                                         String entryI18nKey,
                                         String currentValue,
                                         String expectedValue,
                                         String expectedValueI18nKey,
                                         String status) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> valueI18nKeys = new LinkedHashMap<>();
        values.put("entry", trimToEmpty(entry));
        values.put("currentValue", trimToEmpty(currentValue));
        values.put("expectedValue", trimToEmpty(expectedValue));
        values.put("status", status);
        valueI18nKeys.put("entry", trimToEmpty(entryI18nKey));
        valueI18nKeys.put("expectedValue", trimToEmpty(expectedValueI18nKey));
        return new CheckRow(values, valueI18nKeys, "", "", false);
    }

    private String evaluateEquals(String currentValue, String expectedValue) {
        String current = trimToEmpty(currentValue);
        if (current.isEmpty()) {
            return "2";
        }
        return current.equalsIgnoreCase(trimToEmpty(expectedValue)) ? "0" : "1";
    }

    private String evaluatePresent(String currentValue) {
        return trimToEmpty(currentValue).isEmpty() ? "2" : "0";
    }

    private String evaluateFlashback(String currentValue) {
        String current = trimToEmpty(currentValue);
        if (current.isEmpty()) {
            return "2";
        }
        if ("YES".equalsIgnoreCase(current) || "RESTORE POINT ONLY".equalsIgnoreCase(current)) {
            return "0";
        }
        return "1";
    }

    private String evaluateOpenMode(String currentValue, String databaseRole) {
        String current = trimToEmpty(currentValue).toUpperCase(java.util.Locale.ROOT);
        if (current.isEmpty()) {
            return "2";
        }
        String role = trimToEmpty(databaseRole).toUpperCase(java.util.Locale.ROOT);
        if ("PRIMARY".equals(role)) {
            return "READ WRITE".equals(current) ? "0" : "2";
        }
        if ("PHYSICAL STANDBY".equals(role)) {
            return ("READ ONLY WITH APPLY".equals(current) || "MOUNTED".equals(current) || "READ ONLY".equals(current)) ? "0" : "1";
        }
        if ("LOGICAL STANDBY".equals(role)) {
            return ("READ WRITE".equals(current) || "READ ONLY".equals(current)) ? "0" : "1";
        }
        return "READ WRITE".equals(current) ? "0" : "1";
    }

    private String evaluateInstanceStatus(String currentValue, String databaseRole) {
        String current = trimToEmpty(currentValue).toUpperCase(java.util.Locale.ROOT);
        if (current.isEmpty()) {
            return "2";
        }
        String role = trimToEmpty(databaseRole).toUpperCase(java.util.Locale.ROOT);
        if ("PHYSICAL STANDBY".equals(role)) {
            return ("MOUNTED".equals(current) || "OPEN".equals(current)) ? "0" : "1";
        }
        return "OPEN".equals(current) ? "0" : "1";
    }

    private String evaluateArchiveLog(String currentValue, String databaseRole) {
        String current = trimToEmpty(currentValue).toUpperCase(java.util.Locale.ROOT);
        if (current.isEmpty()) {
            return "2";
        }
        String role = trimToEmpty(databaseRole).toUpperCase(java.util.Locale.ROOT);
        if ("PRIMARY".equals(role) || "PHYSICAL STANDBY".equals(role) || "LOGICAL STANDBY".equals(role)) {
            return "ARCHIVELOG".equals(current) ? "0" : "2";
        }
        return "ARCHIVELOG".equals(current) ? "0" : "1";
    }

    private String expectedOpenMode(String databaseRole) {
        String role = trimToEmpty(databaseRole).toUpperCase(java.util.Locale.ROOT);
        if ("PHYSICAL STANDBY".equals(role)) {
            return "READ ONLY WITH APPLY / MOUNTED / READ ONLY";
        }
        if ("LOGICAL STANDBY".equals(role)) {
            return "READ WRITE / READ ONLY";
        }
        return "READ WRITE";
    }

    private String expectedInstanceStatus(String databaseRole) {
        String role = trimToEmpty(databaseRole).toUpperCase(java.util.Locale.ROOT);
        if ("PHYSICAL STANDBY".equals(role)) {
            return "OPEN / MOUNTED";
        }
        return "OPEN";
    }

    private String expectedArchiveLog(String databaseRole) {
        String role = trimToEmpty(databaseRole).toUpperCase(java.util.Locale.ROOT);
        if ("PRIMARY".equals(role) || "PHYSICAL STANDBY".equals(role) || "LOGICAL STANDBY".equals(role)) {
            return "ARCHIVELOG";
        }
        return "寤鸿 ARCHIVELOG";
    }

    private Map<String, String> parseOracleInfo(String info) {
        Map<String, String> values = new LinkedHashMap<>();
        if (info == null || info.isBlank()) {
            return values;
        }
        Pattern pattern = Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*)\\s+(.+?)\\s*$");
        Matcher matcher = pattern.matcher(info);
        while (matcher.find()) {
            values.put(matcher.group(1).trim().toLowerCase(java.util.Locale.ROOT), matcher.group(2).trim());
        }
        return values;
    }

    private static String buildDefaultConnectionProps(String... propNames) {
        JSONArray array = new JSONArray();
        if (propNames == null) {
            return array.toString();
        }
        for (String propName : propNames) {
            if (propName == null || propName.isBlank()) {
                continue;
            }
            JSONObject object = new JSONObject();
            object.put("propName", propName);
            object.put("propValue", defaultConnectionPropValue(propName));
            array.put(object);
        }
        return array.toString();
    }

    private static String defaultConnectionPropValue(String propName) {
        return "";
    }

    record OracleAdminPrivileges(boolean adminUser,
                                 boolean hasDbaRole,
                                 boolean hasSelectCatalogRole,
                                 boolean hasAlterTablespace,
                                 boolean hasCreateTablespace,
                                 boolean hasDropTablespace,
                                 boolean hasAlterDatabase,
                                 boolean hasAlterSystem,
                                 boolean canViewInstance,
                                 boolean canViewSpaceManager,
                                 boolean canMutateSpace,
                                 boolean canManageConfig,
                                 boolean canStartStopInstance,
                                 Set<String> roles,
                                 Set<String> privileges) {
    }
}
