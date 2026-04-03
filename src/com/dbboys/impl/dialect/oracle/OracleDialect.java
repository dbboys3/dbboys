package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.vo.Connect;

import java.sql.Connection;

/**
 * Oracle 方言占位：建连参数与驱动占位，会话初始化暂不实现。
 */
public final class OracleDialect implements DatabasePlatform {

    private static final String DB_TYPE = "oracle";
    private static final String DRIVER_CLASS = "oracle.jdbc.OracleDriver";

    private final MetadataRepository metadataRepository = new OracleMetadataRepository();
    private final SqlexeRepository sqlexeRepository = new OracleSqlexeRepository();
    private final DdlRepository ddlRepository = new OracleDdlRepository();
    private final InstanceAdminRepository instanceAdminRepository = new OracleInstanceAdminRepository();

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public ConnectionParams getConnectionParams(Connect connect) throws Exception {
        // 使用 thin：jdbc:oracle:thin:@host:port:sid 或 jdbc:oracle:thin:@//host:port/service_name
        String host = connect.getIp() != null ? connect.getIp() : "localhost";
        String port = connect.getPort() != null && !connect.getPort().isEmpty() ? connect.getPort() : "1521";
        String database = connect.getDatabase() != null ? connect.getDatabase() : "ORCL";
        String url = "jdbc:oracle:thin:@" + host + ":" + port + ":" + database;
        String jarFilePath = "file:extlib/" + connect.getDbtype() + "/" + (connect.getDriver() != null && !connect.getDriver().isEmpty() ? connect.getDriver() : "ojdbc8.jar");
        return new ConnectionParams(url, DRIVER_CLASS, jarFilePath);
    }

    @Override
    public void sessionInit(Connection conn, Connect connect) throws Exception {
        // 占位：Oracle 可在此设置 current_schema 等
    }

    @Override
    public boolean supportsSessionInit() {
        return false;
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
    public String testConnectionSql() {
        return "SELECT 1 FROM DUAL";
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
