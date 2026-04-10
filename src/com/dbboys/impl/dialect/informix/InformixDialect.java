package com.dbboys.impl.dialect.informix;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.vo.Catalog;
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

public final class InformixDialect implements DatabasePlatform, ConnectionSupport,
        NamedServerConnectionCapability, ReconnectFallbackCapability, InstanceManagerCapability {

    private static final String DB_TYPE = "INFORMIX";
    private static final String DRIVER_CLASS = "com.informix.jdbc.IfxDriver";
    private static final String NAMED_SERVER_PROP = "INFORMIXSERVER";
    private static final String DEFAULT_CONNECTION_PROPS =
            "[{\"propName\":\"APPENDISAM\",\"propValue\":\"\"},{\"propName\":\"CLIENT_LOCALE\",\"propValue\":\"\"},{\"propName\":\"CSM\",\"propValue\":\"\"},{\"propName\":\"DBANSIWARN\",\"propValue\":\"\"},{\"propName\":\"DBDATE\",\"propValue\":\"Y4MD-\"},{\"propName\":\"DBSPACETEMP\",\"propValue\":\"\"},{\"propName\":\"DBTEMP\",\"propValue\":\"\"},{\"propName\":\"DBUPSPACE\",\"propValue\":\"\"},{\"propName\":\"DB_LOCALE\",\"propValue\":\"\"},{\"propName\":\"DELIMIDENT\",\"propValue\":\"\"},{\"propName\":\"ENABLE_TYPE_CACHE\",\"propValue\":\"\"},{\"propName\":\"ENABLE_HDRSWITCH\",\"propValue\":\"\"},{\"propName\":\"FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"INFORMIXCONRETRY\",\"propValue\":\"\"},{\"propName\":\"INFORMIXCONTIME\",\"propValue\":\"\"},{\"propName\":\"INFORMIXOPCACHE\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSERVER\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSERVER_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSTACKSIZE\",\"propValue\":\"\"},{\"propName\":\"IFX_AUTOFREE\",\"propValue\":\"\"},{\"propName\":\"IFX_BATCHUPDATE_PER_SPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_CODESETLOB\",\"propValue\":\"\"},{\"propName\":\"IFX_DIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_EXTDIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_GET_SMFLOAT_AS_FLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_ISOLATION_LEVEL\",\"propValue\":\"5\"},{\"propName\":\"IFX_FLAT_UCSQ\",\"propValue\":\"\"},{\"propName\":\"IFX_LOCK_MODE_WAIT\",\"propValue\":\"10\"},{\"propName\":\"IFX_PAD_VARCHAR\",\"propValue\":\"\"},{\"propName\":\"IFX_SET_FLOAT_AS_SMFLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_SOC_TIMEOUT\",\"propValue\":\"\"},{\"propName\":\"IFX_TRIMTRAILINGSPACES\",\"propValue\":\"1\"},{\"propName\":\"IFX_USEPUT\",\"propValue\":\"\"},{\"propName\":\"IFX_USE_STRENC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASTDCOMPLIANCE_XAEND\",\"propValue\":\"\"},{\"propName\":\"IFXHOST\",\"propValue\":\"\"},{\"propName\":\"IFXHOST_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"JDBCTEMP\",\"propValue\":\"\"},{\"propName\":\"LOBCACHE\",\"propValue\":\"\"},{\"propName\":\"LOGINTIMEOUT\",\"propValue\":\"1000\"},{\"propName\":\"NEWCODESET\",\"propValue\":\"\"},{\"propName\":\"NEWNLSMAP\",\"propValue\":\"\"},{\"propName\":\"NODEFDAC\",\"propValue\":\"\"},{\"propName\":\"OPT_GOAL\",\"propValue\":\"\"},{\"propName\":\"OPTCOMPIND\",\"propValue\":\"\"},{\"propName\":\"OPTOFC\",\"propValue\":\"\"},{\"propName\":\"PATH\",\"propValue\":\"\"},{\"propName\":\"PDQPRIORITY\",\"propValue\":\"\"},{\"propName\":\"PORTNO_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"PROXY\",\"propValue\":\"\"},{\"propName\":\"PSORT_DBTEMP\",\"propValue\":\"\"},{\"propName\":\"PSORT_NPROCS\",\"propValue\":\"\"},{\"propName\":\"SECURITY\",\"propValue\":\"\"},{\"propName\":\"SQLIDEBUG\",\"propValue\":\"\"},{\"propName\":\"SRV_FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"STMT_CACHE\",\"propValue\":\"\"},{\"propName\":\"TRUSTED_CONTEXT\",\"propValue\":\"\"}]";

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
        connect.setDbversion((metaData.getDatabaseProductName() == null ? "" : metaData.getDatabaseProductName()) + " "
                + (metaData.getDatabaseProductVersion() == null ? "" : metaData.getDatabaseProductVersion()));

        StringBuilder info = new StringBuilder();
        info.append("##########################################################################################\n");
        info.append("Instance Boot Information\n");
        info.append("##########################################################################################\n");

        try (ResultSet envRs = connection.createStatement().executeQuery("select trim(env_name),trim(env_value) from sysmaster:sysenv")) {
            while (envRs.next()) {
                info.append(String.format("%-30s", envRs.getString(1))).append(envRs.getString(2)).append("\n");
                if ("DB_LOCALE".equals(envRs.getString(1))) {
                    connect.setProps(modifyProps(connect, "DB_LOCALE", envRs.getString(2).toUpperCase().trim()));
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
            connect.setDbversion(I18n.t("metadata.dbversion.no_permission", "当前用户无权限获取版本信息，请使用informix用户连接获取\n"));
        }
        return "";
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

