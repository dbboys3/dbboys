package com.dbboys.impl;

import com.dbboys.api.ChangeDatabaseFailureKind;
import com.dbboys.api.ConnectionService;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.ReconnectFallbackCapability;
import com.dbboys.util.MD5Util;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.vo.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConnectionServiceImpl implements ConnectionService {
    private static final Logger log = LogManager.getLogger(ConnectionServiceImpl.class);
    private static final String PROP_DB_LOCALE = "DB_LOCALE";
    private static final String PROP_IFX_ISOLATION_LEVEL = "IFX_ISOLATION_LEVEL";
    private static final String PROP_IFX_TRIMTRAILINGSPACES = "IFX_TRIMTRAILINGSPACES";
    private static final Map<String, Driver> DRIVER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, URLClassLoader> LOADER_CACHE = new ConcurrentHashMap<>();
    private final DatabasePlatformResolver platformResolver;

    public ConnectionServiceImpl() {
        this(DialectServices.createDefault());
    }

    public ConnectionServiceImpl(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver != null ? platformResolver : DialectServices.createDefault();
    }

    public Connection createConnection(Connect connect) throws Exception {
        DatabasePlatform dialect = platformResolver.requirePlatform(connect);
        DatabasePlatform.ConnectionParams params = dialect.getConnectionParams(connect);
        Driver driver = getOrLoadDriver(params.getDriverClassName(), params.getJarFilePath());
        Properties info = buildConnectionProperties(connect, false);
        return driver.connect(params.getUrl(), info);
    }

    private static Properties buildConnectionProperties(Connect connect, boolean ignoreTrimTrailingSpaces) {
        Properties info = new Properties();
        info.setProperty("user", connect.getUsername());
        info.setProperty("password", connect.getPassword());
        JSONArray jsonArray = new JSONArray(connect.getProps());
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            String propName = jsonObject.getString("propName");
            String propValue = jsonObject.getString("propValue");
            if (ignoreTrimTrailingSpaces && PROP_IFX_TRIMTRAILINGSPACES.equalsIgnoreCase(propName)) {
                continue;
            }
            if (propValue != null && (!propValue.equals(""))) {
                info.setProperty(propName, propValue);
            }
        }
        return info;
    }

    private Driver getOrLoadDriver(String className, String jarFilePath) throws Exception {
        if (className == null || jarFilePath == null) {
            throw new IllegalArgumentException("className/jarFilePath is null");
        }
        String key = className + "|" + jarFilePath;
        Driver cached = DRIVER_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (DRIVER_CACHE) {
            cached = DRIVER_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            URLClassLoader loader = LOADER_CACHE.get(jarFilePath);
            if (loader == null) {
                loader = new URLClassLoader(new URL[]{new URL(jarFilePath)}, ClassLoader.getPlatformClassLoader());
                LOADER_CACHE.put(jarFilePath, loader);
            }
            Driver driver = (Driver) Class.forName(className, true, loader).getDeclaredConstructor().newInstance();
            DRIVER_CACHE.put(key, driver);
            return driver;
        }
    }

    public Connection getConnectionWithSessionInit(Connect connect) throws Exception {
        DatabasePlatform dialect = platformResolver.requirePlatform(connect);
        DatabasePlatform.ConnectionParams params = dialect.getConnectionParams(connect);
        Driver driver = getOrLoadDriver(params.getDriverClassName(), params.getJarFilePath());
        Properties info = buildConnectionProperties(connect, shouldIgnoreTrimTrailingSpaces(connect));
        Connection conn = driver.connect(params.getUrl(), info);
        initializeSessionIfSupported(connect, conn);
        return conn;
    }

    private boolean shouldIgnoreTrimTrailingSpaces(Connect connect) {
        return supportsConnectionProperty(connect, PROP_IFX_TRIMTRAILINGSPACES);
    }

    public void changeCommitMode(Connection conn, int commitChoiceBoxIndex) throws SQLException {
        if (commitChoiceBoxIndex == 0) {
            conn.setAutoCommit(true);
        } else if (commitChoiceBoxIndex == 1) {
            conn.setAutoCommit(false);
        }
    }

    private void initializeSessionIfSupported(Connect connect, Connection conn) {
        if (connect == null || conn == null) {
            return;
        }
        DatabasePlatform dialect = platformResolver.getPlatform(connect.getDbtype());
        if (dialect != null && dialect.supportsSessionInit()) {
            try {
                dialect.sessionInit(conn, connect);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public ChangeDefaultDatabaseResult changeDefaultDatabase(Connect connect, Database database, boolean sessionInitOnReconnect) {
        return changeDatabase(connect, database, sessionInitOnReconnect, true);
    }

    public ChangeDefaultDatabaseResult changeSessionDatabase(Connect connect, Database database, boolean sessionInitOnReconnect) {
        return changeDatabase(connect, database, sessionInitOnReconnect, false);
    }

    private ChangeDefaultDatabaseResult changeDatabase(Connect connect,
                                                       Database database,
                                                       boolean sessionInitOnReconnect,
                                                       boolean persistDefaultDatabase) {
        ChangeDefaultDatabaseResult result = new ChangeDefaultDatabaseResult();
        if (connect == null || database == null) {
            result.setSuccess(false);
            return result;
        }
        DatabasePlatform dialect = platformResolver.getPlatform(connect.getDbtype());
        if (dialect == null) {
            result.setSuccess(false);
            return result;
        }
        try {
            platformResolver.metadata(connect).changeDatabase(connect.getConn(), database.getName());
            connect.setDatabase(database.getName());
            if (!dialect.isSystemDatabase(database.getName())) {
                applySupportedConnectionProperty(connect, PROP_DB_LOCALE, database.getDbLocale());
            }
            adjustDefaultDatabaseIsolationLevel(connect, database, persistDefaultDatabase);
            if (persistDefaultDatabase) {
                LocalDbRepository.updateConnect(connect);
            }
            result.setSuccess(true);
        } catch (SQLException e) {
            ChangeDatabaseFailureKind kind = dialect.classifyChangeDatabaseFailure(e);
            if (kind == ChangeDatabaseFailureKind.DISCONNECTED) {
                result.setDisconnected(true);
            } else if (kind == ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION) {
                Connection oldConn = connect.getConn();
                String previousDatabase = connect.getDatabase();
                String previousProps = connect.getProps();
                try {
                    connect.setDatabase(database.getName());
                    if (!dialect.isSystemDatabase(database.getName())) {
                        applySupportedConnectionProperty(connect, PROP_DB_LOCALE, database.getDbLocale());
                    }
                    adjustDefaultDatabaseIsolationLevel(connect, database, persistDefaultDatabase);
                    Connect reconnectConnect = new Connect(connect);
                    if (shouldIgnoreIsolationLevel(database)) {
                        applySupportedConnectionProperty(reconnectConnect, PROP_IFX_ISOLATION_LEVEL, "");
                    }
                    Connection newConn = sessionInitOnReconnect
                            ? getConnectionWithSessionInit(reconnectConnect)
                            : createConnection(reconnectConnect);
                    connect.setConn(newConn);
                    closeConnectionQuietly(oldConn);
                    if (persistDefaultDatabase) {
                        LocalDbRepository.updateConnect(connect);
                    }
                    result.setSuccess(true);
                    result.setReconnected(true);
                } catch (Exception ex) {
                    connect.setConn(oldConn);
                    connect.setDatabase(previousDatabase);
                    connect.setProps(previousProps);
                    applyReconnectFailure(result, ex);
                }
            } else {
                result.setErrorCode(e.getErrorCode());
                result.setErrorMessage(e.getMessage());
            }
        }
        return result;
    }

    public String modifyProps(Connect connect, String propName, String propValue) {
        if (connect == null) {
            return null;
        }
        return platformResolver.requirePlatform(connect).modifyProps(connect, propName, propValue);
    }

    private void adjustDefaultDatabaseIsolationLevel(Connect connect,
                                                     Database database,
                                                     boolean persistDefaultDatabase) {
        if (!persistDefaultDatabase || !supportsConnectionProperty(connect, PROP_IFX_ISOLATION_LEVEL)) {
            return;
        }
        if (shouldIgnoreIsolationLevel(database)) {
            applySupportedConnectionProperty(connect, PROP_IFX_ISOLATION_LEVEL, "");
            return;
        }
        String isolationLevel = connect.getPropByName(PROP_IFX_ISOLATION_LEVEL);
        if (isolationLevel == null || isolationLevel.trim().isEmpty()) {
            applySupportedConnectionProperty(connect, PROP_IFX_ISOLATION_LEVEL, "5");
        }
    }

    public String setConnectInfo(Connect connect) throws Exception {
        DatabasePlatform dialect = platformResolver.requirePlatform(connect);
        try (Connection connection = getConnectionWithSessionInit(connect)) {
            String primaryInstance = dialect.populateConnectInfo(connection, connect);
            if (connect.getDriver() != null && !connect.getDriver().isEmpty()) {
                try {
                    connect.setDrivermd5(MD5Util.getMD5Checksum(
                            Paths.get("extlib/" + connect.getDbtype() + "/" + connect.getDriver()).toFile().getAbsolutePath()));
                } catch (Exception e) {
                    log.debug("Driver md5 skipped", e);
                }
            }
            return primaryInstance;
        }
    }

    public Boolean testConn(Connect connect) {
        if (connect.getConn() == null) {
            return false;
        }
        DatabasePlatform dialect = platformResolver.getPlatform(connect.getDbtype());
        if (dialect == null) {
            return false;
        }
        try {
            return dialect.testConnection(connect.getConn());
        } catch (Exception e) {
            log.error("Operation failed", e);
            return false;
        }
    }

    private static class ConnectionLease {
        private final Connection conn;
        private final boolean shouldClose;

        private ConnectionLease(Connection conn, boolean shouldClose) {
            this.conn = conn;
            this.shouldClose = shouldClose;
        }
    }

    private ConnectionLease acquireConnection(Connect connect, Database database) throws Exception {
        Connection conn = connect.getConn();
        var repo = platformResolver.metadata(connect);
        try {
            repo.setDatabase(conn, database.getName());
            return new ConnectionLease(conn, false);
        } catch (SQLException e) {
            DatabasePlatform dialect = platformResolver.getPlatform(connect.getDbtype());
            String fallback = resolveReconnectFallbackDatabase(dialect);
            if (dialect != null
                    && dialect.classifyChangeDatabaseFailure(e) == ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION
                    && fallback != null) {
                repo.setDatabase(connect.getConn(), fallback);
                Connect connect1 = new Connect(connect);
                connect1.setDatabase(database.getName());
                applySupportedConnectionProperty(connect1, PROP_DB_LOCALE, database.getDbLocale());
                if (shouldIgnoreIsolationLevel(database)) {  // 如果数据库日志为nolog，则不设置隔离级别，否则连接报错-256
                    applySupportedConnectionProperty(connect1, PROP_IFX_ISOLATION_LEVEL, "");
                }
                Connection newConn = getConnectionWithSessionInit(connect1);
                repo.setDatabase(newConn, database.getName());
                return new ConnectionLease(newConn, true);
            }
            throw e;
        }
    }

    public <T> T withMetaSession(Connect connect, Database database, SqlWork<T> action) throws Exception {
        if (connect == null) {
            return null;
        }
        if (database == null) {
            return action.apply(connect.getConn());
        }
        ConnectionLease lease = acquireConnection(connect, database);
        try {
            return action.apply(lease.conn);
        } finally {
            if (lease.shouldClose) {
                lease.conn.close();
            }
        }
    }

    private void closeConnectionQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Close old connection failed during reconnect", e);
        }
    }

    private boolean shouldIgnoreIsolationLevel(Database database) {
        return database != null
                && database.getDbLog() != null
                && "nolog".equalsIgnoreCase(database.getDbLog().trim());
    }

    private void applySupportedConnectionProperty(Connect connect, String propName, String propValue) {
        if (supportsConnectionProperty(connect, propName)) {
            connect.setProps(modifyProps(connect, propName, propValue));
        }
    }

    private boolean supportsConnectionProperty(Connect connect, String propName) {
        if (connect == null || propName == null || propName.isBlank()) {
            return false;
        }
        if (containsConnectionProperty(connect.getProps(), propName)) {
            return true;
        }
        DatabasePlatform platform = platformResolver.getPlatform(connect.getDbtype());
        return platform != null && containsConnectionProperty(platform.defaultConnectionProps(), propName);
    }

    private boolean containsConnectionProperty(String propsJson, String propName) {
        if (propsJson == null || propsJson.isBlank()) {
            return false;
        }
        try {
            JSONArray jsonArray = new JSONArray(propsJson);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                if (propName.equalsIgnoreCase(jsonObject.optString("propName"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Unable to inspect connection properties", e);
        }
        return false;
    }

    private String resolveReconnectFallbackDatabase(DatabasePlatform dialect) {
        if (dialect instanceof ReconnectFallbackCapability capability) {
            return capability.reconnectFallbackDatabaseName();
        }
        return null;
    }

    private void applyReconnectFailure(ChangeDefaultDatabaseResult result, Exception ex) {
        if (ex instanceof SQLException sqlException) {
            result.setErrorCode(sqlException.getErrorCode());
            result.setErrorMessage(sqlException.getMessage());
            return;
        }
        Throwable cause = ex.getCause();
        if (cause instanceof SQLException sqlException) {
            result.setErrorCode(sqlException.getErrorCode());
            result.setErrorMessage(sqlException.getMessage());
            return;
        }
        result.setErrorMessage(ex.getMessage());
    }
}
