package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.api.ConnectionSupport;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.InstanceTabCapability;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private static final String DEFAULT_CONNECTION_PROPS =
            "[{\"propName\":\"oracle.net.CONNECT_TIMEOUT\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.jdbc.ReadTimeout\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.net.keepAlive\",\"propValue\":\"\"}," +
            "{\"propName\":\"defaultRowPrefetch\",\"propValue\":\"\"}," +
            "{\"propName\":\"defaultBatchValue\",\"propValue\":\"\"}," +
            "{\"propName\":\"remarksReporting\",\"propValue\":\"\"}," +
            "{\"propName\":\"includeSynonyms\",\"propValue\":\"\"}," +
            "{\"propName\":\"defaultNChar\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.jdbc.timezoneAsRegion\",\"propValue\":\"\"}," +
            "{\"propName\":\"oracle.net.disableOob\",\"propValue\":\"true\"}]";
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
        return resolveOracleAdminPrivileges(connect).canStartStopInstance();
    }

    @Override
    public String instanceName(Connect connect) {
        if (connect == null) {
            return "";
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
                "Segment Usage TOP20(GB)"
        );
    }

    @Override
    public CheckTableModel buildCheckTable(Connect connect) {
        List<CheckColumn> columns = List.of(
                new CheckColumn("entry", "instance.check.oracle.column.item", "检查项", CheckColumnKind.TEXT, 220),
                new CheckColumn("currentValue", "instance.check.oracle.column.current", "当前值", CheckColumnKind.TEXT, 260),
                new CheckColumn("expectedValue", "instance.check.oracle.column.expected", "期望值", CheckColumnKind.TEXT, 260),
                new CheckColumn("status", "instance.check.oracle.column.result", "结果", CheckColumnKind.STATUS, 100)
        );

        Map<String, String> infoMap = parseOracleInfo(connect == null ? "" : connect.getInfo());
        String databaseRole = trimToEmpty(infoMap.get("database_role"));
        String expectedOpenMode = expectedOpenMode(databaseRole);
        String expectedInstanceStatus = expectedInstanceStatus(databaseRole);
        String expectedArchiveLog = expectedArchiveLog(databaseRole);
        List<CheckRow> rows = List.of(
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
        );
        return new CheckTableModel(columns, rows);
    }

    @Override
    public List<ConfigEntry> loadConfigEntries(Connect connect) throws Exception {
        return com.dbboys.util.InstanceRuntimeUtil.loadOracleConfigEntries(connect);
    }

    @Override
    public String loadRuntimeLog(Connect connect) throws Exception {
        return com.dbboys.util.InstanceRuntimeUtil.loadOracleRuntimeLog(connect);
    }

    @Override
    public boolean isInstanceOnline(Connect connect) throws Exception {
        return com.dbboys.util.InstanceRuntimeUtil.isOracleInstanceOnline(connect);
    }

    @Override
    public ConfigUpdateResult updateConfig(Connect connect, String paramName, String newValue) throws Exception {
        return com.dbboys.util.InstanceMutationUtil.updateOracleConfig(connect, paramName, newValue);
    }

    @Override
    public void startInstance(Connect connect) throws Exception {
        com.dbboys.util.InstanceMutationUtil.startOracleInstance(connect);
    }

    @Override
    public void stopInstance(Connect connect) throws Exception {
        com.dbboys.util.InstanceMutationUtil.stopOracleInstance(connect);
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
        return "妯″紡";
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
        return "鏂板缓妯″紡";
    }

    @Override
    public String getImportDdlDataMenuI18nKey() {
        return "metadata.menu.import_ddl_schema";
    }

    @Override
    public String getImportDdlDataMenuDefaultText() {
        return "瀵煎叆妯″紡";
    }

    @Override
    public String getExportDdlDataMenuI18nKey() {
        return "metadata.menu.export_ddl_schema";
    }

    @Override
    public String getExportDdlDataMenuDefaultText() {
        return "瀵煎嚭妯″紡";
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
        return "瀵煎嚭妯″紡\"%s\"";
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

