package com.dbboys.dialect.informix;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.model.Catalog;
import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.InstanceManagerCapability;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.NamedServerConnectionCapability;
import com.dbboys.core.ReconnectFallbackCapability;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.InstanceHealthCheckUtil;
import com.dbboys.infra.util.InstanceMutationUtil;
import com.dbboys.infra.util.InstanceRuntimeUtil;
import com.dbboys.model.Connect;
import com.dbboys.model.HealthCheck;

import java.util.Set;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class InformixDialect implements DatabasePlatform, ConnectionSupport,
        NamedServerConnectionCapability, ReconnectFallbackCapability, InstanceManagerCapability, InstanceTabCapability {

    private static final String DB_TYPE = "INFORMIX";
    private static final String DRIVER_CLASS = "com.informix.jdbc.IfxDriver";
    private static final String NAMED_SERVER_PROP = "INFORMIXSERVER";
    private static final String DEFAULT_CONNECTION_PROPS =
            "[{\"propName\":\"APPENDISAM\",\"propValue\":\"\"},{\"propName\":\"CLIENT_LOCALE\",\"propValue\":\"\"},{\"propName\":\"CSM\",\"propValue\":\"\"},{\"propName\":\"DBANSIWARN\",\"propValue\":\"\"},{\"propName\":\"DBDATE\",\"propValue\":\"Y4MD-\"},{\"propName\":\"DBSPACETEMP\",\"propValue\":\"\"},{\"propName\":\"DBTEMP\",\"propValue\":\"\"},{\"propName\":\"DBUPSPACE\",\"propValue\":\"\"},{\"propName\":\"DB_LOCALE\",\"propValue\":\"\"},{\"propName\":\"DELIMIDENT\",\"propValue\":\"\"},{\"propName\":\"ENABLE_TYPE_CACHE\",\"propValue\":\"\"},{\"propName\":\"ENABLE_HDRSWITCH\",\"propValue\":\"\"},{\"propName\":\"FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"INFORMIXCONRETRY\",\"propValue\":\"\"},{\"propName\":\"INFORMIXCONTIME\",\"propValue\":\"\"},{\"propName\":\"INFORMIXOPCACHE\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSERVER\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSERVER_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSTACKSIZE\",\"propValue\":\"\"},{\"propName\":\"IFX_AUTOFREE\",\"propValue\":\"\"},{\"propName\":\"IFX_BATCHUPDATE_PER_SPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_CODESETLOB\",\"propValue\":\"\"},{\"propName\":\"IFX_DIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_EXTDIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_GET_SMFLOAT_AS_FLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_ISOLATION_LEVEL\",\"propValue\":\"5\"},{\"propName\":\"IFX_FLAT_UCSQ\",\"propValue\":\"\"},{\"propName\":\"IFX_LOCK_MODE_WAIT\",\"propValue\":\"10\"},{\"propName\":\"IFX_PAD_VARCHAR\",\"propValue\":\"\"},{\"propName\":\"IFX_SET_FLOAT_AS_SMFLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_SOC_TIMEOUT\",\"propValue\":\"\"},{\"propName\":\"IFX_TRIMTRAILINGSPACES\",\"propValue\":\"1\"},{\"propName\":\"IFX_USEPUT\",\"propValue\":\"\"},{\"propName\":\"IFX_USE_STRENC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASTDCOMPLIANCE_XAEND\",\"propValue\":\"\"},{\"propName\":\"IFXHOST\",\"propValue\":\"\"},{\"propName\":\"IFXHOST_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"JDBCTEMP\",\"propValue\":\"\"},{\"propName\":\"LOBCACHE\",\"propValue\":\"\"},{\"propName\":\"LOGINTIMEOUT\",\"propValue\":\"1000\"},{\"propName\":\"NEWCODESET\",\"propValue\":\"\"},{\"propName\":\"NEWNLSMAP\",\"propValue\":\"\"},{\"propName\":\"NODEFDAC\",\"propValue\":\"\"},{\"propName\":\"OPT_GOAL\",\"propValue\":\"\"},{\"propName\":\"OPTCOMPIND\",\"propValue\":\"\"},{\"propName\":\"OPTOFC\",\"propValue\":\"\"},{\"propName\":\"PATH\",\"propValue\":\"\"},{\"propName\":\"PDQPRIORITY\",\"propValue\":\"\"},{\"propName\":\"PORTNO_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"PROXY\",\"propValue\":\"\"},{\"propName\":\"PSORT_DBTEMP\",\"propValue\":\"\"},{\"propName\":\"PSORT_NPROCS\",\"propValue\":\"\"},{\"propName\":\"SECURITY\",\"propValue\":\"\"},{\"propName\":\"SQLIDEBUG\",\"propValue\":\"\"},{\"propName\":\"SRV_FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"STMT_CACHE\",\"propValue\":\"\"},{\"propName\":\"TRUSTED_CONTEXT\",\"propValue\":\"\"}]";
    private static final Map<String, String> CHECK_ENTRY_I18N_KEYS = Map.ofEntries(
            Map.entry("系统架构", "instance.check.item.system_arch"),
            Map.entry("CPU数量", "instance.check.item.cpu_count"),
            Map.entry("内存大小", "instance.check.item.memory_size"),
            Map.entry("数据库版本", "instance.check.item.db_version"),
            Map.entry("软件授权有效期", "instance.check.item.license_expiry"),
            Map.entry("实例状态", "instance.check.item.instance_status"),
            Map.entry("实例是否BLOCKED", "instance.check.item.instance_blocked"),
            Map.entry("实例运行天数", "instance.check.item.instance_uptime_days"),
            Map.entry("实例内存总量", "instance.check.item.instance_memory_total"),
            Map.entry("实例内存段数量", "instance.check.item.instance_memory_segments"),
            Map.entry("实例集群状态", "instance.check.item.instance_cluster_status"),
            Map.entry("实例物理日志", "instance.check.item.instance_physical_log"),
            Map.entry("实例逻辑日志", "instance.check.item.instance_logical_log"),
            Map.entry("实例空间状态", "instance.check.item.instance_space_status"),
            Map.entry("实例空间使用率", "instance.check.item.instance_space_usage"),
            Map.entry("实例空间备份", "instance.check.item.instance_space_backup"),
            Map.entry("实例运行日志", "instance.check.item.instance_runtime_log"),
            Map.entry("实例总连接数", "instance.check.item.instance_total_connections"),
            Map.entry("实例活动连接数", "instance.check.item.instance_active_connections"),
            Map.entry("实例队列数量", "instance.check.item.instance_queue_count"),
            Map.entry("实例逻辑日志等待logio cond", "instance.check.item.instance_wait_logio"),
            Map.entry("实例锁等待yield lockwait", "instance.check.item.instance_wait_lock"),
            Map.entry("实例buf等待yield bufwait", "instance.check.item.instance_wait_buf"),
            Map.entry("实例IO等待IO Wait", "instance.check.item.instance_wait_io"),
            Map.entry("实例打开未提交事务数", "instance.check.item.instance_open_txn"),
            Map.entry("实例已提交事务数", "instance.check.item.instance_committed_txn"),
            Map.entry("实例回滚事务数", "instance.check.item.instance_rollback_txn"),
            Map.entry("实例插入数量", "instance.check.item.instance_insert_count"),
            Map.entry("实例更新数量", "instance.check.item.instance_update_count"),
            Map.entry("实例删除数量", "instance.check.item.instance_delete_count"),
            Map.entry("实例死锁数量", "instance.check.item.instance_deadlock_count")
    );
    private static final Map<String, String> CHECK_EXPECTED_I18N_KEYS = Map.ofEntries(
            Map.entry("1核心以上", "instance.check.expected.cpu_above_1"),
            Map.entry("2GB以上", "instance.check.expected.memory_above_2g"),
            Map.entry("永久", "instance.check.expected.license_permanent"),
            Map.entry("主节点或单机On-Line，集群备机Read-Only", "instance.check.expected.instance_online_or_readonly"),
            Map.entry("正常无Blocked:显示", "instance.check.expected.no_blocked"),
            Map.entry("V段不超过3个", "instance.check.expected.memory_segments_le_3"),
            Map.entry("无集群或Connected", "instance.check.expected.cluster_connected"),
            Map.entry("physize不小于1G", "instance.check.expected.physize_ge_1g"),
            Map.entry("U------状态日志为0", "instance.check.expected.logical_log_zero"),
            Map.entry("无PD状态", "instance.check.expected.no_pd"),
            Map.entry("使用率小于80%", "instance.check.expected.usage_lt_80"),
            Map.entry("最后一次备份时间在24小时内", "instance.check.expected.backup_within_24h"),
            Map.entry("err、failed关键字数量为0", "instance.check.expected.log_error_zero"),
            Map.entry("0或少量", "instance.check.expected.zero_or_few"),
            Map.entry("少量", "instance.check.expected.few"),
            Map.entry("业务繁忙度决定", "instance.check.expected.depends_on_business_load"),
            Map.entry("业务逻辑决定", "instance.check.expected.depends_on_business_logic")
    );

    private final MetadataRepository metadataRepository = new InformixMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new InformixSqlexeRepository();
    private final DdlRepository ddlRepository = new InformixDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new InformixInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.INFORMIX_LOGO, 0.15, 0.12);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) {
        String database = getSessionCatalog(connect);
        if (database == null || database.isBlank()) {
            database = connect.getCatalog();
        }
        if (database == null || database.isBlank()) {
            database = defaultDatabase();
        }
        connect.setSessionCatalog(database);
        String url;
        if (connect.getPropByName(NAMED_SERVER_PROP).isEmpty()) {
            String host = connect.getIp() == null || connect.getIp().isBlank() ? "127.0.0.1" : connect.getIp();
            String port = connect.getPort() == null || connect.getPort().isBlank() ? defaultPort() : connect.getPort();
            url = "jdbc:informix-sqli://" + host + ":" + port + "/" + database;
        } else {
            url = "jdbc:informix-sqli:/" + database + ":SQLH_TYPE=FILE;SQLH_FILE=extlib/" + connect.getDbtype() + "/sqlhosts;";
        }
        String jarFilePath = "file:extlib/" + connect.getDbtype() + "/" + connect.getDriver();
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) {
        // Informix does not require extra session initialization for the current workflow.
    }

    @Override
    public boolean supportsSessionInit() {
        return false;
    }

    @Override
    public String defaultPort() {
        return "9088";
    }

    @Override
    public String defaultDatabase() {
        return "sysmaster";
    }

    @Override
    public String defaultUsername() {
        return "informix";
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
    }

    @Override
    public boolean supportsInstanceManager(Connect connect) {
        return connect != null && "informix".equalsIgnoreCase(connect.getUsername());
    }

    @Override
    public String installDirEnvName() {
        return "INFORMIXDIR";
    }

    @Override
    public String adminOsUser(Connect connect) {
        return "informix";
    }

    @Override
    public String versionExpectation() {
        return "Informix 12.1+";
    }

    @Override
    public boolean supportsHealthCheckTab(Connect connect) {
        return supportsInstanceManager(connect);
    }

    @Override
    public boolean supportsLogTab(Connect connect) {
        return supportsInstanceManager(connect);
    }

    @Override
    public boolean supportsConfigTab(Connect connect) {
        return supportsInstanceManager(connect);
    }

    @Override
    public boolean supportsStartStopTab(Connect connect) {
        return supportsInstanceManager(connect);
    }

    @Override
    public String instanceName(Connect connect) {
        if (connect == null) {
            return "";
        }
        String instanceName = InstanceTabCapability.extractInfoValue(connect.getInfo(), NAMED_SERVER_PROP);
        if (!instanceName.isEmpty()) {
            return instanceName;
        }
        String prop = connect.getPropByName(NAMED_SERVER_PROP);
        return prop == null ? "" : prop.trim();
    }

    @Override
    public String runtimeLogCommand(Connect connect) {
        return "onstat -m";
    }

    @Override
    public String installDirEnvName(Connect connect) {
        return installDirEnvName();
    }

    @Override
    public String versionExpectation(Connect connect) {
        return versionExpectation();
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
        String logCommand = runtimeLogCommand(connect);
        List<CheckRow> rows = loadHealthChecks(connect).stream().map(check -> {
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> valueI18nKeys = new java.util.LinkedHashMap<>();
            values.put("entry", check.getEntry());
            values.put("cmd", check.getCmd());
            values.put("healthValue", check.getHealthValue());
            values.put("currentValue", check.getCurrentValue());
            values.put("status", check.getStatus());
            valueI18nKeys.put("entry", CHECK_ENTRY_I18N_KEYS.getOrDefault(check.getEntry(), ""));
            valueI18nKeys.put("healthValue", CHECK_EXPECTED_I18N_KEYS.getOrDefault(check.getHealthValue(), ""));
            return new CheckRow(
                    values,
                    valueI18nKeys,
                    check.getCmd(),
                    check.getCmdOutput(),
                    logCommand != null && !logCommand.isBlank() && logCommand.equals(check.getCmd())
            );
        }).toList();
        return new CheckTableModel(columns, rows);
    }

    @Override
    public List<HealthCheck> loadHealthChecks(Connect connect) throws Exception {
        return InstanceHealthCheckUtil.loadInformixStyleHealthChecks(
                connect,
                versionExpectation(connect),
                runtimeLogCommand(connect)
        );
    }

    @Override
    public List<ConfigEntry> loadConfigEntries(Connect connect) throws Exception {
        return InstanceRuntimeUtil.loadInformixStyleConfigEntries(connect);
    }

    @Override
    public String loadRuntimeLog(Connect connect) throws Exception {
        return InstanceRuntimeUtil.loadInformixStyleRuntimeLog(connect);
    }

    @Override
    public boolean isInstanceOnline(Connect connect) throws Exception {
        return InstanceRuntimeUtil.isInformixStyleInstanceOnline(connect);
    }

    @Override
    public ConfigUpdateResult updateConfig(Connect connect, String paramName, String newValue) throws Exception {
        return InstanceMutationUtil.updateInformixStyleConfig(connect, installDirEnvName(connect), paramName, newValue);
    }

    @Override
    public void startInstance(Connect connect) throws Exception {
        InstanceMutationUtil.startInformixStyleInstance(connect);
    }

    @Override
    public void stopInstance(Connect connect) throws Exception {
        InstanceMutationUtil.stopInformixStyleInstance(connect);
    }

    @Override
    public void createOrAddSpace(Connect connect, SpaceMutationRequest request) throws Exception {
        InstanceMutationUtil.createOrAddInformixStyleSpace(connect, request);
    }

    @Override
    public void abortCreateOrAddSpace(Connect connect) throws Exception {
        InstanceMutationUtil.abortCreateOrAddInformixStyleSpace(connect);
    }

    @Override
    public void dropSpace(Connect connect, String spaceName, List<String> datafilePaths) throws Exception {
        InstanceMutationUtil.dropInformixStyleSpace(connect, spaceName, datafilePaths);
    }

    @Override
    public void dropDatafile(Connect connect, String spaceName, String datafilePath) throws Exception {
        InstanceMutationUtil.dropInformixStyleDatafile(connect, spaceName, datafilePath);
    }

    @Override
    public String namedServerPropertyName() {
        return NAMED_SERVER_PROP;
    }

    @Override
    public String testConnectionSql() {
        return "select first 1 tabid from systables";
    }

    @Override
    public String metadataTreeDragTableSelectSql(String qualifiedTable) {
        return "select ifx_row_id,* from " + stripOwnerForDragSql(qualifiedTable) + ";";
    }

    @Override
    public String metadataTreeDragFragmentTableSelectSql(String qualifiedTable) {
        return DatabasePlatform.defaultMetadataTreeDragStarFromSql(stripOwnerForDragSql(qualifiedTable));
    }

    @Override
    public ChangeDatabaseFailureKind classifyChangeDatabaseFailure(SQLException e) {
        int code = e.getErrorCode();
        if (code == -79716 || code == -79730) {
            return ChangeDatabaseFailureKind.DISCONNECTED;
        }
        if (code == -23197 || code == -349) {
            return ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION;
        }
        return ChangeDatabaseFailureKind.OTHER;
    }

    @Override
    public String reconnectFallbackDatabaseName() {
        return "sysmaster";
    }

    private String stripOwnerForDragSql(String qualifiedTable) {
        if (qualifiedTable == null || qualifiedTable.isBlank()) {
            return qualifiedTable;
        }
        int dotIndex = qualifiedTable.indexOf('.');
        if (dotIndex < 0 || dotIndex >= qualifiedTable.length() - 1) {
            return qualifiedTable;
        }
        return qualifiedTable.substring(dotIndex + 1).trim();
    }

    private static final Set<String> SYS_DBS = Set.of(
            "sysmaster", "sysadmin", "sysutils", "syscdcv1", "sys",
            "sysuser", "syscdr", "sysha", "informix"
    );

    @Override
    public Set<String> systemDatabaseNames() {
        return SYS_DBS;
    }

    @Override
    public String buildBootstrapSql(Catalog database) {
        if (database == null || database.getName() == null || database.getName().isBlank()) {
            return "";
        }
        String name = database.getName().trim();
        String dbspace = database.getDbSpace() == null ? "" : database.getDbSpace().trim();
        String dbLog = database.getDbLog() == null ? "" : database.getDbLog().trim().toLowerCase(java.util.Locale.ROOT);
        String dbLocale = database.getDbLocale() == null ? "" : database.getDbLocale().trim();
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        StringBuilder sb = new StringBuilder();
        sb.append("-- ############################################################\n");
        sb.append("-- ### Informix Database DDL Export\n");
        sb.append("-- ### Database : ").append(name).append("\n");
        sb.append("-- ### Datetime : ").append(dateStr).append("\n");
        sb.append("-- ############################################################\n\n");
        if (!dbLocale.isEmpty()) {
            sb.append("-- DB_LOCALE=").append(dbLocale).append("\n");
        }
        sb.append("create database ").append(name);
        if (!dbspace.isEmpty()) {
            sb.append(" in ").append(dbspace);
        }
        if ("buffered".equals(dbLog)) {
            sb.append(" with buffered log");
        } else if ("unbuffered".equals(dbLog)) {
            sb.append(" with log");
        }
        sb.append(";\n\n");
        return sb.toString();
    }

    @Override
    public boolean isSystemDatabase(String databaseName) {
        return databaseName != null && SYS_DBS.contains(databaseName);
    }

    @Override
    public String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        if (connection == null || connect == null) {
            return "";
        }

        DatabaseMetaData metaData = connection.getMetaData();
        String primaryInstance = "";
        connect.setDbversion((metaData.getDatabaseProductName() == null ? "" : metaData.getDatabaseProductName()) + " "
                + (metaData.getDatabaseProductVersion() == null ? "" : metaData.getDatabaseProductVersion()));

        StringBuilder info = new StringBuilder();
        info.append("##########################################################################################\n");
        info.append("Instance Boot Information\n");
        info.append("##########################################################################################\n");

        try (ResultSet envRs = connection.createStatement().executeQuery("select trim(env_name),trim(env_value) from sysmaster:sysenv")) {
            while (envRs.next()) {
                String envName = envRs.getString(1);
                String envValue = envRs.getString(2);
                info.append(String.format("%-30s", envName)).append(envValue).append("\n");
                if ("DB_LOCALE".equals(envName)) {
                    connect.setProps(modifyProps(connect, "DB_LOCALE", envValue.toUpperCase().trim()));
                } else if (!connect.getPropByName(NAMED_SERVER_PROP).isEmpty()
                        && envValue != null
                        && !envValue.isBlank()
                        && (NAMED_SERVER_PROP.equalsIgnoreCase(envName)
                        || "DBSERVERNAME".equalsIgnoreCase(envName))) {
                    primaryInstance = envValue.trim();
                }
            }
        }

        info.append("\n##########################################################################################\n");
        info.append("System Information\n");
        info.append("##########################################################################################\n");

        try (ResultSet rs = connection.createStatement().executeQuery("SELECT * from sysmaster:sysmachineinfo ")) {
            if (rs.next()) {
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    info.append(String.format("%-30s", rs.getMetaData().getColumnName(i).trim()));
                    info.append(rs.getString(i) == null ? "" : rs.getString(i).trim()).append("\n");
                }
            }
        } catch (SQLException e) {
            // Some editions may not expose sysmachineinfo.
        }

        connect.setInfo(info.toString());
        if (connect.getDbversion() == null || connect.getDbversion().isBlank()) {
            connect.setDbversion(I18n.t("metadata.dbversion.no_permission", "褰撳墠鐢ㄦ埛鏃犳潈闄愯幏鍙栫増鏈俊鎭紝璇蜂娇鐢╥nformix鐢ㄦ埛杩炴帴鑾峰彇\n"));
        }
        return primaryInstance;
    }

    @Override
    public String modifyProps(Connect connect, String propName, String propValue) {
        if (connect == null) {
            return null;
        }
        if (!"DB_LOCALE".equals(propName)) {
            return ConnectionSupport.super.modifyProps(connect, propName, propValue);
        }
        if (propValue == null || propValue.trim().isEmpty()) {
            return connect.getProps();
        }
        String normalized = propValue
                .replaceAll("(?i)" + "UTF8", "57372")
                .replaceAll("(?i)" + "GB18030-2000", "5488")
                .trim();
        return ConnectionSupport.super.modifyProps(connect, propName, normalized);
    }

    @Override
    public MetadataRepository metadata() {
        return metadataRepository;
    }

    @Override
    public List<String> getColumnTypes() {
        return List.of(
                "SMALLINT", "INTEGER", "BIGINT", "SERIAL", "SERIAL8", "BIGSERIAL",
                "DECIMAL", "NUMERIC", "FLOAT", "MONEY",
                "CHAR", "VARCHAR", "LVARCHAR", "NCHAR", "NVARCHAR",
                "DATE", "DATETIME YEAR TO SECOND", "DATETIME YEAR TO FRACTION(5)", "INTERVAL",
                "TEXT", "BYTE", "BLOB", "CLOB",
                "BOOLEAN", "JSON", "BSON"
        );
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


