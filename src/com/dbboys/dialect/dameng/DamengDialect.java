package com.dbboys.dialect.dameng;

import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.model.Catalog;
import com.dbboys.model.Connect;
import com.dbboys.model.HealthCheck;
import com.dbboys.app.AppContext;
import com.dbboys.core.ConnectionService;
import com.dbboys.core.ConnectionServiceImpl;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
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

public final class DamengDialect implements DatabasePlatform, ConnectionSupport, InstanceTabCapability {
    private static final String DB_TYPE = "DAMENG";
    private static final String DRIVER_CLASS = "dm.jdbc.driver.DmDriver";
    private static final String DEFAULT_DRIVER = "DmJdbcDriver11.jar";
    private static final String DEFAULT_CONNECTION_PROPS = buildDefaultConnectionProps(
            // ── 基础属性 ──
            "schema", "",
            "loginMode", "",
            "connectTimeout", "",
            "socketTimeout", "",
            "appName", "",
            "osName", "",
            "localHost", "",
            // ── 加密与安全 ──
            "loginEncrypt", "",
            "encrypt", "",
            "encryptType", "",
            "certPath", "",
            "sslFiles", "",
            // ── 读写分离与集群 ──
            "doSwitch", "",
            "autoReconnect", "",
            "reconnectCount", "",
            "reconnectInterval", "",
            "rwSeparate", "",
            "rwPercent", "",
            "epSelector", "",
            // ── 压缩 ──
            "compress", "",
            "compressID", "",
            // ── 结果集与字段 ──
            "clobAsString", "",
            "columnNameCase", "",
            "columnNameUpperCase", "",
            "ignoreCase", "",
            "bufferFetchSize", "",
            "scrollResultSet", "",
            "lobPrefetchSize", "",
            // ── 性能与批处理 ──
            "batchType", "",
            "prepareOptimize", "",
            // ── 会话参数 ──
            "keyWords", "",
            "dbmdChkPrv", "",
            "exceedMaxRows", "",
            "sendBlobAsStream", "",
            // ── 兼容模式 ──
            "compatibleMode", "",
            "compatibleOjdbc", "",
            "checkExecCount", "",
            // ── 其他 ──
            "quoteReplace", "",
            "schemaAlias", "",
            "bindParamWithQuestionMark", "",
            "maxColumnNameLength", "",
            // ── 连接池相关 ──
            "connPoolInitSize", "",
            "connPoolMaxSize", "",
            "connPoolIdleTime", ""
    );

    private final MetadataRepository metadataRepository = new DamengMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new DamengSqlexeRepository();
    private final DdlRepository ddlRepository = new DamengDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new DamengInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.DAMENG_LOGO, 0.022, 0.022);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) {
        if (connect != null
                && (connect.getSessionCatalog() == null || connect.getSessionCatalog().isBlank())
                && connect.getUsername() != null
                && !connect.getUsername().isBlank()) {
            connect.setSessionCatalog(connect.getUsername().trim().toUpperCase(Locale.ROOT));
        }
        String host = connect == null || connect.getIp() == null || connect.getIp().isBlank()
                ? "localhost" : connect.getIp().trim();
        String port = connect == null || connect.getPort() == null || connect.getPort().isBlank()
                ? defaultPort() : connect.getPort().trim();
        String url = "jdbc:dm://" + host + ":" + port + "?compatibleMode=oracle";
        String driver = connect == null || connect.getDriver() == null || connect.getDriver().isBlank()
                ? DEFAULT_DRIVER : connect.getDriver().trim();
        String jarFilePath = Path.of("extlib", DB_TYPE, driver).toUri().toString();
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws SQLException {
        if (conn == null || connect == null) {
            return;
        }
        String schema = getSessionCatalog(connect);
        if (schema == null || schema.isBlank()) {
            schema = connect.getUsername();
        }
        if (schema != null && !schema.isBlank()) {
            String normalizedSchema = schema.trim().toUpperCase(Locale.ROOT);
            metadataRepository.setDatabase(conn, normalizedSchema);
            connect.setSessionCatalog(normalizedSchema);
        }
    }

    @Override
    public String defaultPort() {
        return "5236";
    }

    @Override
    public String defaultDatabase() {
        return "DAMENG";
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
    public String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        if (connection == null || connect == null) {
            return "";
        }
        StringBuilder info = new StringBuilder();
        String dbVersion = trimToEmpty(connection.getMetaData().getDatabaseProductVersion());
        connect.setDbversion("DM " + dbVersion);

        // ── 实例信息 ──
        appendSectionQuery(info, "Instance Information",
                connection,
                "select instance_name, host_name, status$, mode$, " +
                "to_char(start_time,'yyyy-mm-dd hh24:mi:ss') as start_time, " +
                "datediff(dd, start_time, sysdate) || ' 天' as uptime_days " +
                "from v$instance");

        // ── 数据库信息 ──
        appendSectionQuery(info, "Database Information",
                connection,
                "select name, global_name, arch_mode, " +
                "to_char(create_time,'yyyy-mm-dd hh24:mi:ss') as create_time " +
                "from v$database");

        // ── 内存与性能概况 ──
        appendSectionQuery(info, "Memory & Buffer Pool",
                connection,
                "select " +
                "nvl(sum(nvl(total_size,0))/1024/1024,0) || ' MB' as memory_pool_total_mb, " +
                "nvl(sum(nvl(n_logic_reads,0)),0) as buffer_logical_reads, " +
                "nvl(sum(nvl(n_phy_reads,0)),0) as buffer_physical_reads, " +
                "case when nvl(sum(nvl(n_logic_reads,0))+sum(nvl(n_phy_reads,0)),0) > 0 " +
                "then round(nvl(sum(nvl(n_logic_reads,0)),0)*100.0/" +
                "nvl(sum(nvl(n_logic_reads,0))+sum(nvl(n_phy_reads,0)),1),2) || '%' " +
                "else 'N/A' end as buffer_hit_ratio " +
                "from v$bufferpool");

        // ── 会话与连接 ──
        appendSectionQuery(info, "Sessions & Connections",
                connection,
                "select " +
                "(select count(*) from v$sessions) as total_sessions, " +
                "(select count(*) from v$sessions where state='ACTIVE') as active_sessions, " +
                "(select count(*) from v$lock where blocked=1) as blocked_locks, " +
                "(select max_sessions from v$dm_ini where para_name='MAX_SESSIONS') as max_sessions_limit");

        // ── 当前模式信息 ──
        String schema = currentSchema(connection);
        appendSectionQuery(info, "Current Schema",
                connection,
                "select user() as current_user, " +
                "sys_context('USERENV','CURRENT_SCHEMA') as current_schema, " +
                "sys_context('USERENV','LANGUAGE') as language, " +
                "sys_context('USERENV','IP_ADDRESS') as client_ip");

        // ── 数据库模式/兼容性参数 ──
        appendSectionQuery(info, "Database Configuration",
                connection,
                "select para_name, para_value from v$dm_ini " +
                "where para_name in (" +
                "'COMPATIBLE_MODE','BLANK_PAD_MODE'," +
                "'LENGTH_IN_CHAR','CASE_SENSITIVE','CHARSET','PAGE_SIZE'," +
                "'EXTENT_SIZE','PK_WITH_CLUSTER'" +
                ") order by para_name");

        connect.setInfo(info.toString());
        return "";
    }

    @Override
    public boolean usesSchemaModel() {
        return true;
    }

    @Override
    public boolean canCreateDatabase() {
        return true;
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
    public String buildBootstrapSql(Catalog database) {
        if (database == null || database.getName() == null || database.getName().isBlank()) {
            return "";
        }
        String schema = database.getName().trim().toUpperCase(Locale.ROOT);
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        StringBuilder sb = new StringBuilder();
        sb.append("-- ############################################################\n");
        sb.append("-- ### Dameng Schema DDL Export\n");
        sb.append("-- ### Schema   : ").append(schema).append("\n");
        sb.append("-- ### Datetime : ").append(dateStr).append("\n");
        sb.append("-- ############################################################\n\n");
        sb.append("CREATE USER ").append(schema).append(" IDENTIFIED BY ").append(schema).append(";\n\n");
        return sb.toString();
    }

    @Override
    public String metadataTooltipCatalogLabel() {
        return "SCHEMA";
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
    public boolean prefersTableCountFromTableListQuery() {
        return true;
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
        return "alter table " + tableName + (logging ? " logging" : " nologging");
    }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        String q = qualifiedTable == null ? "" : qualifiedTable.trim();
        return "SELECT ROWID, t.* FROM " + q + " t;";
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
        return "ALTER " + objectType.toUpperCase(Locale.ROOT) + " " + oldName + " RENAME TO " + newName;
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
        return "CREATE USER " + userName + " IDENTIFIED BY " + escapedPassword;
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
    public List<String> getColumnTypes() {
        return List.of(
                "CHAR", "VARCHAR", "VARCHAR2", "TEXT", "CLOB",
                "NUMBER", "NUMERIC", "DECIMAL", "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
                "FLOAT", "DOUBLE", "REAL",
                "DATE", "TIME", "TIMESTAMP", "DATETIME",
                "BINARY", "VARBINARY", "BLOB"
        );
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

    private static String currentSchema(Connection conn) {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema.trim();
            }
        } catch (Exception ignored) {
        }
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select sys_context('USERENV','CURRENT_SCHEMA') from dual")) {
            return rs.next() ? trimToEmpty(rs.getString(1)) : "";
        } catch (SQLException ignored) {
            return "";
        }
    }

    private static void appendInfoLine(StringBuilder info, String label, String value) {
        String normalizedValue = trimToEmpty(value);
        if (info == null || label == null || label.isBlank() || normalizedValue.isEmpty()) {
            return;
        }
        info.append(String.format("%-30s", label.trim())).append(normalizedValue).append("\n");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
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

    // ==================== InstanceTabCapability ====================

    private static final Map<String, String> DAMENG_CHECK_ENTRY_I18N_KEYS = Map.of(
            "数据库版本", "instance.check.dameng.item.db_version",
            "实例状态", "instance.check.dameng.item.instance_status",
            "启动时间", "instance.check.dameng.item.startup_time",
            "数据库模式", "instance.check.dameng.item.database_mode",
            "活动会话数", "instance.check.dameng.item.active_sessions",
            "阻塞锁数量", "instance.check.dameng.item.blocked_locks",
            "缓冲区命中率", "instance.check.dameng.item.buffer_hit_ratio",
            "内存池总大小(MB)", "instance.check.dameng.item.memory_pool_size"
    );

    private static final Map<String, String> DAMENG_CHECK_EXPECTED_I18N_KEYS = Map.of(
            "应可获取", "instance.check.dameng.expected.available",
            "应为 OPEN", "instance.check.dameng.expected.should_be_open",
            "应为 NORMAL", "instance.check.dameng.expected.should_be_normal",
            "应可用", "instance.check.dameng.expected.should_be_available",
            "建议持续关注", "instance.check.dameng.expected.monitor",
            "应为 0", "instance.check.dameng.expected.should_be_zero",
            ">=95%", "instance.check.dameng.expected.buffer_hit_ge_95",
            "应可统计", "instance.check.dameng.expected.should_be_measurable",
            "获取失败", "instance.check.dameng.expected.fetch_failed"
    );

    @Override
    public boolean supportsInfoTab(Connect connect) {
        return true;
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
        // 从建连时写入的 info 中提取真实实例名 (v$instance.instance_name)
        String infoName = InstanceTabCapability.extractInfoValue(
                connect.getInfo(), "instance_name");
        return infoName == null ? "" : infoName.trim();
    }

    @Override
    public SpaceLabels spaceLabels(Connect connect) {
        return new SpaceLabels(
                "",
                "",
                "instance.space.dameng.chart.tablespace",
                "表空间使用情况图(GB)",
                "instance.space.dameng.chart.datafile",
                "数据文件使用情况图(GB)",
                "instance.space.dameng.chart.schema",
                "模式使用空间情况(GB)",
                "instance.space.dameng.chart.table",
                "表/索引空间使用情况图TOP20(GB)",
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
            valueI18nKeys.put("entry", i18nEntryKey(check.getEntry()));
            valueI18nKeys.put("healthValue", i18nExpectedKey(check.getHealthValue()));
            rows.add(new CheckRow(values, valueI18nKeys, check.getCmd(), check.getCmdOutput(), false));
        }
        return new CheckTableModel(columns, rows);
    }

    private static String i18nEntryKey(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }
        String exact = DAMENG_CHECK_ENTRY_I18N_KEYS.get(entry);
        if (exact != null) {
            return exact;
        }
        // Handle "表空间 X" pattern — use the base entry text for lookup
        if (entry.startsWith("表空间 ")) {
            return "";
        }
        return "";
    }

    private static String i18nExpectedKey(String expected) {
        if (expected == null || expected.isBlank()) {
            return "";
        }
        String exact = DAMENG_CHECK_EXPECTED_I18N_KEYS.get(expected);
        if (exact != null) {
            return exact;
        }
        // Handle "获取失败: ..." pattern
        if (expected.startsWith("获取失败")) {
            return DAMENG_CHECK_EXPECTED_I18N_KEYS.get("获取失败");
        }
        return "";
    }

    @Override
    public List<HealthCheck> loadHealthChecks(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect))) {
            List<HealthCheck> checks = new ArrayList<>();

            // DM version
            runCheck(checks, "数据库版本", "select id_code()", "应可获取", () -> {
                String version = queryScalar(conn, "select id_code()");
                addCheck(checks, "数据库版本", "select id_code()", "应可获取", version, present(version));
            });

            // Instance status
            runCheck(checks, "实例状态", "select status$ from v$instance", "应为 OPEN", () -> {
                String instStatus = queryScalar(conn, "select status$ from v$instance");
                addCheck(checks, "实例状态", "select status$ from v$instance", "应为 OPEN",
                        instStatus, "OPEN".equalsIgnoreCase(instStatus));
            });

            // Uptime
            runCheck(checks, "启动时间", "select start_time from v$instance", "应可获取", () -> {
                String startTime = queryScalar(conn, "select to_char(start_time,'yyyy-mm-dd hh24:mi:ss') from v$instance");
                addCheck(checks, "启动时间", "select start_time from v$instance", "应可获取",
                        startTime, present(startTime));
            });

            // Database mode
            runCheck(checks, "数据库模式", "select mode$ from v$instance", "应为 NORMAL", () -> {
                String mode = queryScalar(conn, "select mode$ from v$instance");
                addCheck(checks, "数据库模式", "select mode$ from v$instance", "应为 NORMAL",
                        mode, "NORMAL".equalsIgnoreCase(mode));
            });

            // Tablespace usage (V$TABLESPACE sizes are in pages; PAGE() = page bytes)
            runCheck(checks, "表空间", "v$tablespace", "应可用", () -> {
                Map<String, String> usage = queryNameValue(conn,
                        "select name, round((total_size-used_size)*page()/1024/1024,2)||'/'||" +
                        "round(total_size*page()/1024/1024,2)||'MB' " +
                        "from v$tablespace where name not in ('TEMP','ROLL')");
                for (Map.Entry<String,String> e : usage.entrySet()) {
                    addCheck(checks, "表空间 " + e.getKey(), "v$tablespace", "应可用",
                            e.getValue(), true);
                }
            });

            // Active sessions
            runCheck(checks, "活动会话数", "select count(*) from v$sessions where state='ACTIVE'", "建议持续关注", () -> {
                long sessions = queryLong(conn, "select count(*) from v$sessions where state='ACTIVE'");
                addCheck(checks, "活动会话数", "select count(*) from v$sessions where state='ACTIVE'",
                        "建议持续关注", String.valueOf(sessions), true);
            });

            // Blocked locks
            runCheck(checks, "阻塞锁数量", "select count(*) from v$lock where blocked=1", "应为 0", () -> {
                long locks = queryLong(conn, "select count(*) from v$lock where blocked=1");
                addCheck(checks, "阻塞锁数量", "select count(*) from v$lock where blocked=1",
                        "应为 0", String.valueOf(locks), locks == 0);
            });

            // Buffer hit ratio (N_LOGIC_READS=hits, N_PHY_READS=misses per DM8 docs)
            runCheck(checks, "缓冲区命中率", "v$bufferpool", ">=95%", () -> {
                long bufGets = queryLong(conn, "select nvl(sum(nvl(n_logic_reads,0)),0) from v$bufferpool");
                long bufReads = queryLong(conn, "select nvl(sum(nvl(n_phy_reads,0)),0) from v$bufferpool");
                double bufHit = (bufGets + bufReads) <= 0 ? 100 : (double)bufGets * 100 / (bufGets + bufReads);
                addCheck(checks, "缓冲区命中率", "v$bufferpool", ">=95%",
                        String.format(Locale.ROOT, "%.2f%%", bufHit), bufHit >= 95);
            });

            // Memory usage
            runCheck(checks, "内存池总大小(MB)", "select sum(total_size) from v$mem_pool", "应可统计", () -> {
                long memTotal = queryLong(conn, "select nvl(sum(nvl(total_size,0)),0)/1024/1024 from v$mem_pool");
                addCheck(checks, "内存池总大小(MB)", "select sum(total_size) from v$mem_pool",
                        "应可统计", String.valueOf(memTotal), memTotal >= 0);
            });

            return checks;
        }
    }

    @Override
    public String loadRuntimeLog(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect))) {
            // Real instance message log (ELOG): V$INSTANCE_LOG_HISTORY keeps the
            // latest ~10k events of the current run; show the newest 1000 first.
            // Older history lives only in $DM_HOME/log/dm_<instance>_<yyyymm>.log.
            return safeQueryRows(conn,
                    "select to_char(log_time,'yyyy-mm-dd hh24:mi:ss') log_time, level$, thread_name, txt " +
                    "from v$instance_log_history order by log_time desc", 1000);
        }
    }

    @Override
    public List<ConfigEntry> loadConfigEntries(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect))) {
            List<ConfigEntry> rows = new ArrayList<>();
            try (var stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "select name, value, type, description from v$parameter " +
                         "where name not in ('SYSDBA','SYSAUDITOR','SYSSSO') order by name")) {
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
            return new ConfigUpdateResult(ConfigUpdateStatus.FILE_ONLY, "参数名称为空");
        }
        String value = (newValue == null ? "" : newValue).replace("'", "''");
        String escapedName = name.replace("'", "''");
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect));
             var stmt = conn.createStatement()) {
            try {
                // scope=1: dynamic params, applied to both memory and dm.ini
                stmt.execute("SP_SET_PARA_VALUE(1,'" + escapedName + "','" + value + "')");
                return new ConfigUpdateResult(ConfigUpdateStatus.APPLIED, "参数 " + name + " 已设置为 " + newValue);
            } catch (SQLException dynamicFailure) {
                // scope=1 is rejected for static ('IN FILE') params: fall back to
                // scope=2 (dm.ini only), which takes effect after a restart
                stmt.execute("SP_SET_PARA_VALUE(2,'" + escapedName + "','" + value + "')");
                return new ConfigUpdateResult(ConfigUpdateStatus.RESTART_REQUIRED,
                        "参数 " + name + " 已写入配置文件，重启后生效");
            }
        }
    }

    @Override
    public boolean isInstanceOnline(Connect connect) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(new Connect(connect));
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select 1 from dual")) {
            return rs.next();
        }
    }
    // ==================== End InstanceTabCapability ====================

    // ==================== Shared helpers ====================

    private static ConnectionService connectionService() {
        try {
            return AppContext.get(ConnectionService.class);
        } catch (IllegalStateException e) {
            return new ConnectionServiceImpl();
        }
    }

    private static void addCheck(List<HealthCheck> checks, String entry, String cmd, String expected, String current, boolean ok) {
        checks.add(new HealthCheck(entry, cmd, expected, current == null ? "" : current, ok ? "0" : "2", current));
    }

    @FunctionalInterface
    private interface CheckAction {
        void run() throws Exception;
    }

    /** Run one health check in isolation: a failing query degrades to an error
     *  row instead of aborting the whole health-check tab. */
    private static void runCheck(List<HealthCheck> checks, String entry, String cmd, String expected, CheckAction action) {
        try {
            action.run();
        } catch (Exception e) {
            String message = e.getMessage();
            addCheck(checks, entry, cmd, expected, "获取失败: " + (message == null ? e.toString() : message), false);
        }
    }

    /** Populates the info StringBuilder with a titled section from a SQL query.
     *  Each result set row is appended as "col_label     col_value". Silently
     *  skips the section if the query fails (view may be inaccessible). */
    private static void appendSectionQuery(StringBuilder info, String title, Connection connection, String sql) {
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                return;
            }
            info.append("\n");
            info.append("##########################################################################################\n");
            info.append(title).append("\n");
            info.append("##########################################################################################\n");
            java.sql.ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                appendInfoLine(info, md.getColumnLabel(i), rs.getString(i));
            }
        } catch (SQLException ignored) {
            // Optional DM views like v$dm_ini may be inaccessible to low-privilege accounts.
        }
    }

    /** queryRows variant that turns a failing section query into readable error
     *  text, so one unsupported view does not blank the whole runtime log. */
    private static String safeQueryRows(Connection conn, String sql, int maxRows) {
        try {
            return queryRows(conn, sql, maxRows);
        } catch (SQLException e) {
            return "查询失败: " + e.getMessage();
        }
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

    private static long queryLong(Connection conn, String sql) throws SQLException {
        try (var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
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
}
