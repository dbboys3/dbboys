package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.ConnectionSupport;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.vo.Connect;

import java.sql.Connection;
import java.util.List;

/**
 * Oracle 方言占位：建连参数与驱动占位，会话初始化暂不实现。
 */
public final class OracleDialect implements DatabasePlatform, ConnectionSupport {

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
            "{\"propName\":\"oracle.net.disableOob\",\"propValue\":\"\"}]";

    private final MetadataRepository metadataRepository = new OracleMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new OracleSqlexeRepository();
    private final DdlRepository ddlRepository = new OracleDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new OracleInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public ConnectionSupport connection() {
        return this;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        // 使用 thin service_name 形式：jdbc:oracle:thin:@//host:port/service_name
        String host = connect.getIp() != null ? connect.getIp() : "localhost";
        String port = connect.getPort() != null && !connect.getPort().isEmpty() ? connect.getPort() : "1521";
        String database = connect.getDatabase() != null ? connect.getDatabase() : "ORCL";
        String url = "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        String jarFilePath = "file:extlib/" + DB_TYPE + "/" + (connect.getDriver() != null && !connect.getDriver().isEmpty() ? connect.getDriver() : "ojdbc8.jar");
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        String sessionDatabase = getSessionDatabase(connect);
        if (sessionDatabase != null && !sessionDatabase.isBlank()) {
            metadataRepository.setDatabase(conn, sessionDatabase);
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
    public String getSessionDatabase(Connect connect) {
        if (connect == null) {
            return "";
        }
        String sessionDatabase = connect.getSessionDatabase();
        if (sessionDatabase != null && !sessionDatabase.isBlank()) {
            return sessionDatabase;
        }
        String username = connect.getUsername();
        return username == null ? "" : username;
    }

    @Override
    public void setSessionDatabase(Connect connect, String databaseName) {
        if (connect == null) {
            return;
        }
        connect.setSessionDatabase(databaseName);
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
