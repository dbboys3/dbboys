package com.dbboys.impl.dialect.informix;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.api.DatabaseDialect;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.i18n.I18n;
import com.dbboys.impl.dialect.gbase.GbaseDdlRepository;
import com.dbboys.impl.dialect.gbase.GbaseInstanceAdminRepository;
import com.dbboys.impl.dialect.gbase.GbaseMetadataRepository;
import com.dbboys.impl.dialect.gbase.GbaseSqlexeRepository;
import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class InformixDialect implements DatabaseDialect {

    private static final String DB_TYPE = "INFORMIX";
    private static final String DRIVER_CLASS = "com.informix.jdbc.IfxDriver";
    private static final String DEFAULT_CONNECTION_PROPS =
            "[{\"propName\":\"APPENDISAM\",\"propValue\":\"\"},{\"propName\":\"CLIENT_LOCALE\",\"propValue\":\"\"},{\"propName\":\"CSM\",\"propValue\":\"\"},{\"propName\":\"DBANSIWARN\",\"propValue\":\"\"},{\"propName\":\"DBDATE\",\"propValue\":\"Y4MD-\"},{\"propName\":\"DBSPACETEMP\",\"propValue\":\"\"},{\"propName\":\"DBTEMP\",\"propValue\":\"\"},{\"propName\":\"DBUPSPACE\",\"propValue\":\"\"},{\"propName\":\"DB_LOCALE\",\"propValue\":\"\"},{\"propName\":\"DELIMIDENT\",\"propValue\":\"\"},{\"propName\":\"ENABLE_TYPE_CACHE\",\"propValue\":\"\"},{\"propName\":\"ENABLE_HDRSWITCH\",\"propValue\":\"\"},{\"propName\":\"FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"INFORMIXCONRETRY\",\"propValue\":\"\"},{\"propName\":\"INFORMIXCONTIME\",\"propValue\":\"\"},{\"propName\":\"INFORMIXOPCACHE\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSERVER\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSERVER_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"INFORMIXSTACKSIZE\",\"propValue\":\"\"},{\"propName\":\"IFX_AUTOFREE\",\"propValue\":\"\"},{\"propName\":\"IFX_BATCHUPDATE_PER_SPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_CODESETLOB\",\"propValue\":\"\"},{\"propName\":\"IFX_DIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_EXTDIRECTIVES\",\"propValue\":\"\"},{\"propName\":\"IFX_GET_SMFLOAT_AS_FLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_ISOLATION_LEVEL\",\"propValue\":\"5\"},{\"propName\":\"IFX_FLAT_UCSQ\",\"propValue\":\"\"},{\"propName\":\"IFX_LOCK_MODE_WAIT\",\"propValue\":\"10\"},{\"propName\":\"IFX_PAD_VARCHAR\",\"propValue\":\"\"},{\"propName\":\"IFX_SET_FLOAT_AS_SMFLOAT\",\"propValue\":\"\"},{\"propName\":\"IFX_SOC_TIMEOUT\",\"propValue\":\"\"},{\"propName\":\"IFX_TRIMTRAILINGSPACES\",\"propValue\":\"1\"},{\"propName\":\"IFX_USEPUT\",\"propValue\":\"\"},{\"propName\":\"IFX_USE_STRENC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASPEC\",\"propValue\":\"\"},{\"propName\":\"IFX_XASTDCOMPLIANCE_XAEND\",\"propValue\":\"\"},{\"propName\":\"IFXHOST\",\"propValue\":\"\"},{\"propName\":\"IFXHOST_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"JDBCTEMP\",\"propValue\":\"\"},{\"propName\":\"LOBCACHE\",\"propValue\":\"\"},{\"propName\":\"LOGINTIMEOUT\",\"propValue\":\"1000\"},{\"propName\":\"NEWCODESET\",\"propValue\":\"\"},{\"propName\":\"NEWNLSMAP\",\"propValue\":\"\"},{\"propName\":\"NODEFDAC\",\"propValue\":\"\"},{\"propName\":\"OPT_GOAL\",\"propValue\":\"\"},{\"propName\":\"OPTCOMPIND\",\"propValue\":\"\"},{\"propName\":\"OPTOFC\",\"propValue\":\"\"},{\"propName\":\"PATH\",\"propValue\":\"\"},{\"propName\":\"PDQPRIORITY\",\"propValue\":\"\"},{\"propName\":\"PORTNO_SECONDARY\",\"propValue\":\"\"},{\"propName\":\"PROXY\",\"propValue\":\"\"},{\"propName\":\"PSORT_DBTEMP\",\"propValue\":\"\"},{\"propName\":\"PSORT_NPROCS\",\"propValue\":\"\"},{\"propName\":\"SECURITY\",\"propValue\":\"\"},{\"propName\":\"SQLIDEBUG\",\"propValue\":\"\"},{\"propName\":\"SRV_FET_BUF_SIZE\",\"propValue\":\"\"},{\"propName\":\"STMT_CACHE\",\"propValue\":\"\"},{\"propName\":\"TRUSTED_CONTEXT\",\"propValue\":\"\"}]";

    private final MetadataRepository metadataRepository = new GbaseMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new GbaseSqlexeRepository();
    private final DdlRepository ddlRepository = new GbaseDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new GbaseInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) {
        String database = connect.getDatabase() == null || connect.getDatabase().isBlank()
                ? defaultDatabase()
                : connect.getDatabase();
        String url;
        if (connect.getPropByName(namedServerPropName()).isEmpty()) {
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
    public boolean supportsNamedServerConnection() {
        return true;
    }

    @Override
    public String namedServerPropName() {
        return "INFORMIXSERVER";
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
    public String changeDatabaseFallbackCatalogName() {
        return "sysmaster";
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
            connect.setDbversion(I18n.t("metadata.dbversion.no_permission", "当前用户无权限获取版本信息，请使用gbasedbt用户连接获取\n"));
        }
        return "";
    }

    @Override
    public String modifyProps(Connect connect, String propName, String propValue) {
        if (connect == null) {
            return null;
        }
        if (!"DB_LOCALE".equals(propName)) {
            return DatabaseDialect.super.modifyProps(connect, propName, propValue);
        }
        if (propValue == null || propValue.trim().isEmpty()) {
            return connect.getProps();
        }
        String normalized = propValue
                .replaceAll("(?i)" + "UTF8", "57372")
                .replaceAll("(?i)" + "GB18030-2000", "5488")
                .trim();
        return DatabaseDialect.super.modifyProps(connect, propName, normalized);
    }

    @Override
    public MetadataRepository getMetadataRepository() {
        return metadataRepository;
    }

    @Override
    public SqlexeRepository getSqlexeRepository() {
        return sqlexeRepository;
    }

    @Override
    public DdlRepository getDdlRepository() {
        return ddlRepository;
    }

    @Override
    public InstanceAdminRepository getInstanceAdminRepository() {
        return instanceAdminRepository;
    }
}
