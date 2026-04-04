package com.dbboys.impl.dialect.gbase;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.api.ConnectionSupport;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceManagerCapability;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.NamedServerConnectionCapability;
import com.dbboys.api.ReconnectFallbackCapability;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Connect;

import java.util.Set;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * GBase 8S 方言：建连 URL/驱动、会话 sqlmode 初始化。
 */
public final class GbaseDialect implements DatabasePlatform, ConnectionSupport,
        NamedServerConnectionCapability, ReconnectFallbackCapability, InstanceManagerCapability {

    private static final String DB_TYPE = "GBASE 8S";
    private static final String DRIVER_CLASS = "com.gbasedbt.jdbc.Driver";
    private static final String NAMED_SERVER_PROP = "GBASEDBTSERVER";
    private static final String DEFAULT_CONNECTION_PROPS =
            "[{\"propName\":\"APPENDISAM\",\"propValue\":\"\"},{\"propName\":\"CLIENT_LOCALE\",\"propValue\":\"\"},{\"propName\":\"CSM\",\"propValue\":\"\"},{\"propName\":\"DBANSIWARN\",\"propValue\":\"\"},{\"propName\":\"DBDATE\",\"propValue\":\"Y4MD-\"},{\"propName\":\"DBSPACETEMP\",\"propValue\":\"\"},{\"propName\":\"DBTEMP\",\"propValue\":\"\"},{\"propName\":\"DBUPSPACE\",\"propValue\":\"\"},{\"propName\":\"DB_LOCALE\",\"propValue\":\"\"},{\"propName\":\"DELIMIDENT\",\"propValue\":\"\"},{\"propName\":\"ENABLE_TYPE_CACHE\",\"propValue\":\"\"},{\"propName\":\"ENABLE_HDRSWITCH\",\"propValue\":\"\"},{\"propName\":\"FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"GBASEDBTCONRETRY\",\"propValue\":\"\"},{\"propName\":\"GBASEDBTCONTIME\",\"propValue\":\"\"},{\"propName\":\"GBASEDBTOPCACHE\",\"propValue\":\"\"},{\"propName\":\"GBASEDBTSERVER\",\"propValue\":\"\"},{\"propName\":\"GBASEDBTSERVER_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"GBASEDBTSTACKSIZE\",\"propValue\":\"\"},{\"propName\":\"IFX_AUTOFREE\",\"propValue\":\"\"},{\"propName\":\"IFX_BATCHUPDATE_PER_SPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_CODESETLOB\",\"propValue\":\"\"},{\"propName\":\"IFX_DIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_EXTDIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_GET_SMFLOAT_AS_FLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_ISOLATION_LEVEL\",\"propValue\":\"5\"},{\"propName\":\"IFX_FLAT_UCSQ\",\"propValue\":\"\"},{\"propName\":\"IFX_LOCK_MODE_WAIT\",\"propValue\":\"10\"},{\"propName\":\"IFX_PAD_VARCHAR\",\"propValue\":\"\"},{\"propName\":\"IFX_SET_FLOAT_AS_SMFLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_SOC_TIMEOUT\",\"propValue\":\"\"},{\"propName\":\"IFX_TRIMTRAILINGSPACES\",\"propValue\":\"1\"},{\"propName\":\"IFX_USEPUT\",\"propValue\":\"\"},{\"propName\":\"IFX_USE_STRENC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASTDCOMPLIANCE_XAEND\",\"propValue\":\"\"},{\"propName\":\"IFXHOST\",\"propValue\":\"\"},{\"propName\":\"IFXHOST_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"JDBCTEMP\",\"propValue\":\"\"},{\"propName\":\"LOBCACHE\",\"propValue\":\"\"},{\"propName\":\"LOGINTIMEOUT\",\"propValue\":\"1000\"},{\"propName\":\"NEWCODESET\",\"propValue\":\"\"},{\"propName\":\"NEWNLSMAP\",\"propValue\":\"\"},{\"propName\":\"NODEFDAC\",\"propValue\":\"\"},{\"propName\":\"OPT_GOAL\",\"propValue\":\"\"},{\"propName\":\"OPTCOMPIND\",\"propValue\":\"\"},{\"propName\":\"OPTOFC\",\"propValue\":\"\"},{\"propName\":\"PATH\",\"propValue\":\"\"},{\"propName\":\"PDQPRIORITY\",\"propValue\":\"\"},{\"propName\":\"PORTNO_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"PROXY\",\"propValue\":\"\"},{\"propName\":\"PSORT_DBTEMP\",\"propValue\":\"\"},{\"propName\":\"PSORT_NPROCS\",\"propValue\":\"\"},{\"propName\":\"SECURITY\",\"propValue\":\"\"},{\"propName\":\"SQLIDEBUG\",\"propValue\":\"\"},{\"propName\":\"SQLMODE\",\"propValue\":\"\"},{\"propName\":\"SRV_FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"STMT_CACHE\",\"propValue\":\"\"},{\"propName\":\"TRUSTED_CONTEXT\",\"propValue\":\"\"},{\"propName\":\"METADATA_UPPERCASE\",\"propValue\":\"\"}]";

    private final MetadataRepository metadataRepository = new GbaseMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new GbaseSqlexeRepository();
    private final DdlRepository ddlRepository = new GbaseDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new GbaseInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public IconInfo iconInfo() {
        return new IconInfo(IconPaths.GBASE_LOGO, 0.22, 0.22);
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        String sessionDatabase = getSessionDatabase(connect);
        if (sessionDatabase == null || sessionDatabase.isBlank()) {
            sessionDatabase = connect.getDatabase();
        }
        if (sessionDatabase == null || sessionDatabase.isBlank()) {
            sessionDatabase = defaultDatabase();
        }
        connect.setSessionDatabase(sessionDatabase);
        String url;
        if (connect.getPropByName(NAMED_SERVER_PROP).isEmpty()) {
            url = "jdbc:gbasedbt-sqli://" + connect.getIp() + ":" + connect.getPort() + "/" + sessionDatabase;
        } else {
            url = "jdbc:gbasedbt-sqli:/" + sessionDatabase + ":SQLH_TYPE=FILE;SQLH_FILE=extlib/" + connect.getDbtype() + "/sqlhosts;";
        }
        String jarFilePath = "file:extlib/" + connect.getDbtype() + "/" + connect.getDriver();
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        try {
            conn.createStatement().execute("set environment sqlmode 'gbase'");
        } catch (SQLException e) {
            // ignore
        }
    }

    @Override
    public boolean supportsSessionInit() {
        return true;
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
        return "gbasedbt";
    }

    @Override
    public String defaultConnectionProps() {
        return DEFAULT_CONNECTION_PROPS;
    }

    @Override
    public void setSessionDatabase(Connect connect, String databaseName) {
        if (connect == null) {
            return;
        }
        connect.setSessionDatabase(databaseName);
    }

    @Override
    public boolean supportsInstanceManager(Connect connect) {
        return connect != null && "gbasedbt".equalsIgnoreCase(connect.getUsername());
    }

    @Override
    public String installDirEnvName() {
        return "GBASEDBTDIR";
    }

    @Override
    public String adminOsUser(Connect connect) {
        return "gbasedbt";
    }

    @Override
    public String versionExpectation() {
        return "GBase8sV8.x";
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

    private static final Set<String> SYS_DBS = Set.of(
            "sysmaster", "sysadmin", "sysutils", "syscdcv1", "sys",
            "gbasedbt", "sysuser", "syscdr", "sysha"
    );

    @Override
    public Set<String> systemDatabaseNames() {
        return SYS_DBS;
    }

    @Override
    public boolean isSystemDatabase(String databaseName) {
        if (databaseName == null) {
            return false;
        }
        switch (databaseName) {
            case "sysmaster":
            case "sysadmin":
            case "sysutils":
            case "syscdcv1":
            case "sys":
            case "gbasedbt":
            case "sysuser":
            case "syscdr":
            case "sysha":
                return true;
            default:
                return false;
        }
    }

    @Override
    public String populateConnectInfo(Connection connection, Connect connect) throws Exception {
        String primaryInstance = "";
        ResultSet rs = null;
        String dbversion;
        if (connect.getUsername().equals("gbasedbt")) {
            rs = connection.createStatement().executeQuery("EXECUTE FUNCTION sysadmin:task('onstat','-V');");
            rs.next();
            dbversion = rs.getString(1).replace("GBase Database Server Version 12.10.FC4G1", "")
                    .replace(" Software Serial Number AAA#B000000", "")
                    .replace("\n", "");
            if (!dbversion.contains("GBase8s")) {
                DatabaseMetaData metaData = connection.getMetaData();
                String databaseProductVersion = metaData.getDatabaseProductVersion();
                dbversion = "GBase8sV" + databaseProductVersion + "_" + dbversion;
            }
            rs.close();
            rs = null;
        } else {
            dbversion = I18n.t("metadata.dbversion.no_permission",
                    "当前用户无权限获取版本信息，请使用gbasedbt用户连接获取\n");
        }
        connect.setDbversion(dbversion);
        String info = "##########################################################################################\n";
        info += "Instance Boot Information\n";
        info += "##########################################################################################\n";
        rs = connection.createStatement().executeQuery("select trim(env_name),trim(env_value) from sysmaster:sysenv");

        while (rs.next()) {
            info += String.format("%-30s", rs.getString(1)) + rs.getString(2) + "\n";
            if (rs.getString(1).equals("DB_LOCALE")) {
                connect.setProps(modifyProps(connect, "DB_LOCALE", rs.getString(2).toUpperCase().trim()));
            }
        }
        rs.close();
        info += "\n##########################################################################################\n";
        info += "System Information\n";
        info += "##########################################################################################\n";
        rs = connection.createStatement().executeQuery("SELECT * from sysmaster:sysmachineinfo ");
        rs.next();
        for (int i = 1; i <= 24; i++) {
            info += String.format("%-30s", rs.getMetaData().getColumnName(i).trim());
            info += (rs.getString(i)==null?"":rs.getString(i).trim()) + "\n";
        }
        rs.close();
        rs = null;

        if (!connect.getPropByName("GBASEDBTSERVER").isEmpty()) {
            rs = connection.createStatement().executeQuery("select dbservername from dual");
            if (rs.next()) {
                primaryInstance = rs.getString(1);
            }
        }
        if (rs != null) {
            rs.close();
        }
        connect.setInfo(info);
        return primaryInstance;
    }

    /**
     * GBase：对 DB_LOCALE 做编码映射，其它属性走通用键值更新。
     */
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
                "CHAR", "VARCHAR", "VARCHAR2", "LVARCHAR", "NCHAR", "NVARCHAR", "NVARCHAR2",
                "DATE", "DATETIME YEAR TO SECOND", "DATETIME YEAR TO FRACTION(5)", "INTERVAL",
                "RAW", "TEXT", "BYTE", "BLOB", "CLOB",
                "BOOLEAN", "JSON", "BSON"
        );
    }

    @Override
    public boolean supportsPackages() {
        return true;
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
