package com.dbboys.core;

import com.dbboys.core.ChangeDatabaseFailureKind;
import com.dbboys.core.ConnectionService;
import com.dbboys.core.ConnectionSupport;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.NamedServerConnectionCapability;
import com.dbboys.core.ReconnectFallbackCapability;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.MD5Util;
import com.dbboys.infra.util.SshTunnel;
import com.dbboys.infra.util.SshTunnelUtil;
import com.dbboys.infra.util.SshConnectionWrapper;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.model.*;

import java.nio.file.Files;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ConcurrentHashMap<String, Session> sshSessionCache = new ConcurrentHashMap<>();
    private final DatabasePlatformResolver platformResolver;

    public ConnectionServiceImpl() {
        this(DatabasePlatforms.createDefault());
    }

    public ConnectionServiceImpl(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver != null ? platformResolver : DatabasePlatforms.createDefault();
    }

    private Session getOrCreateSshSession(Connect connect) throws Exception {
        // Remove disconnected sessions from cache
        sshSessionCache.values().removeIf(s -> !s.isConnected());
        String cacheKey = String.format("%s@%s:%s",
                connect.getSshUser(), connect.getSshHost(),
                connect.getSshPort() != null && !connect.getSshPort().isBlank()
                        ? connect.getSshPort().trim() : "22");
        Session session = sshSessionCache.get(cacheKey);
        if (session != null && session.isConnected()) {
            return session;
        }
        JSch jsch = new JSch();
        int sshPort = connect.getSshPort() != null && !connect.getSshPort().isBlank()
                ? Integer.parseInt(connect.getSshPort().trim()) : 22;
        session = jsch.getSession(connect.getSshUser(), connect.getSshHost(), sshPort);
        session.setPassword(connect.getSshPassword());
        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("compression.s2c", "zlib@openssh.com,zlib,none");
        config.put("compression.c2s", "zlib@openssh.com,zlib,none");
        session.setConfig(config);
        session.setServerAliveInterval(30000);
        session.setServerAliveCountMax(5);
        session.connect(10000);
        sshSessionCache.put(cacheKey, session);
        log.info("SSH session created (cached): {}@{}:{}",
                connect.getSshUser(), connect.getSshHost(), sshPort);
        return session;
    }

    public Connection createConnection(Connect connect) throws Exception {
        SshTunnel tunnel = null;
        String originalIp = null;
        String originalPort = null;
        try {
            if (connect.getSshEnabled() != null && connect.getSshEnabled()) {
                originalIp = connect.getIp();
                originalPort = connect.getPort();
                int sshPort = connect.getSshPort() != null && !connect.getSshPort().isBlank()
                        ? Integer.parseInt(connect.getSshPort().trim()) : 22;
                int remotePort = originalPort != null && !originalPort.isBlank()
                        ? Integer.parseInt(originalPort.trim()) : 0;
                Session sharedSession = getOrCreateSshSession(connect);
                int localPort = sharedSession.setPortForwardingL(0, originalIp, remotePort);
                tunnel = new SshTunnel(sharedSession, localPort, true);
                // Point JDBC to local tunnel endpoint
                connect.setIp("127.0.0.1");
                connect.setPort(String.valueOf(tunnel.getLocalPort()));
            }
            DatabasePlatform dialect = platformResolver.requirePlatform(connect);
            ConnectionSupport.ConnectionParams params = dialect.connection().getConnectionParams(connect);
            Driver driver = getOrLoadDriver(params.getDriverClassName(), params.getJarFilePath(), params.getExtraJarPaths());
            Properties info = buildConnectionProperties(connect, false);
            Connection conn = driver.connect(params.getUrl(), info);
            if (conn == null) {
                throw new SQLException(buildDriverRejectedUrlMessage(params, driver));
            }
            if (tunnel != null) {
                conn = new SshConnectionWrapper(conn, tunnel, false);
            }
            return conn;
        } catch (Exception e) {
            if (tunnel != null) {
                SshTunnelUtil.closeTunnel(tunnel);
            }
            throw e;
        } finally {
            // Restore original ip/port on the Connect object
            if (originalIp != null) {
                connect.setIp(originalIp);
            }
            if (originalPort != null) {
                connect.setPort(originalPort);
            }
        }
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

    private Driver getOrLoadDriver(String className, String jarFilePath, java.util.List<String> extraJarPaths) throws Exception {
        if (className == null || jarFilePath == null) {
            throw new IllegalArgumentException("className/jarFilePath is null");
        }
        String extraSuffix = extraJarPaths == null || extraJarPaths.isEmpty() ? "" : "|" + String.join(",", extraJarPaths);
        String key = className + "|" + jarFilePath + extraSuffix;
        Driver cached = DRIVER_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String loaderKey = jarFilePath + extraSuffix;
        synchronized (DRIVER_CACHE) {
            cached = DRIVER_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            URLClassLoader loader = LOADER_CACHE.get(loaderKey);
            if (loader == null) {
                int extraCount = extraJarPaths == null ? 0 : extraJarPaths.size();
                URL[] urls = new URL[1 + extraCount];
                urls[0] = new URL(jarFilePath);
                for (int i = 0; i < extraCount; i++) {
                    urls[i + 1] = new URL(extraJarPaths.get(i));
                }
                loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
                LOADER_CACHE.put(loaderKey, loader);
            }
            Driver driver = (Driver) Class.forName(className, true, loader).getDeclaredConstructor().newInstance();
            DRIVER_CACHE.put(key, driver);
            return driver;
        }
    }
    public Connection getConnectionWithSessionInit(Connect connect) throws Exception {
        SshTunnel tunnel = null;
        String originalIp = null;
        String originalPort = null;
        try {
            if (connect.getSshEnabled() != null && connect.getSshEnabled()) {
                originalIp = connect.getIp();
                originalPort = connect.getPort();
                int sshPort = connect.getSshPort() != null && !connect.getSshPort().isBlank()
                        ? Integer.parseInt(connect.getSshPort().trim()) : 22;
                int remotePort = originalPort != null && !originalPort.isBlank()
                        ? Integer.parseInt(originalPort.trim()) : 0;
                Session sharedSession = getOrCreateSshSession(connect);
                int localPort = sharedSession.setPortForwardingL(0, originalIp, remotePort);
                tunnel = new SshTunnel(sharedSession, localPort, true);
                connect.setIp("127.0.0.1");
                connect.setPort(String.valueOf(tunnel.getLocalPort()));
            }
            DatabasePlatform dialect = platformResolver.requirePlatform(connect);
            ConnectionSupport.ConnectionParams params = dialect.connection().getConnectionParams(connect);
            Driver driver = getOrLoadDriver(params.getDriverClassName(), params.getJarFilePath(), params.getExtraJarPaths());
            Properties info = buildConnectionProperties(connect, shouldIgnoreTrimTrailingSpaces(connect));
            Connection conn = driver.connect(params.getUrl(), info);
            if (conn == null) {
                throw new SQLException(buildDriverRejectedUrlMessage(params, driver));
            }
            if (tunnel != null) {
                conn = new SshConnectionWrapper(conn, tunnel, false);
            }
            initializeSessionIfSupported(connect, conn);
            return conn;
        } catch (Exception e) {
            if (tunnel != null) {
                SshTunnelUtil.closeTunnel(tunnel);
            }
            throw e;
        } finally {
            if (originalIp != null) {
                connect.setIp(originalIp);
            }
            if (originalPort != null) {
                connect.setPort(originalPort);
            }
        }
    }

    private String buildDriverRejectedUrlMessage(ConnectionSupport.ConnectionParams params, Driver driver) {
        String url = params == null ? "" : params.getUrl();
        String driverClass = params == null ? "" : params.getDriverClassName();
        String driverName = driver == null ? driverClass : driver.getClass().getName();
        String lowerDriver = driverName == null ? "" : driverName.toLowerCase();
        String example = jdbcUrlExample(lowerDriver);
        String exampleText = example.isEmpty()
                ? ""
                : "\n" + I18n.t("createconnect.error.jdbc_url_rejected.example", "示例：%s").formatted(example);
        return I18n.t(
                "createconnect.error.jdbc_url_rejected",
                "JDBC URL格式不匹配，当前驱动无法处理该URL。\n驱动：%s\nURL：%s\n请确认已选择正确驱动，并填写完整JDBC URL。%s"
        ).formatted(driverName, url, exampleText);
    }

    private String jdbcUrlExample(String lowerDriver) {
        if (lowerDriver == null) {
            return "";
        }
        if (lowerDriver.contains("mysql")) {
            return "jdbc:mysql://127.0.0.1:3306/database";
        }
        if (lowerDriver.contains("postgresql")) {
            return "jdbc:postgresql://127.0.0.1:5432/database";
        }
        if (lowerDriver.contains("oracle")) {
            return "jdbc:oracle:thin:@//127.0.0.1:1521/service";
        }
        if (lowerDriver.contains("sqlserver")) {
            return "jdbc:sqlserver://127.0.0.1:1433;databaseName=database";
        }
        if (lowerDriver.contains("sqlite")) {
            return "jdbc:sqlite:/path/to/database.db";
        }
        return "";
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
        if (dialect != null && dialect.connection().supportsSessionInit()) {
            try {
                dialect.connection().sessionInit(conn, connect);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public ChangeDefaultDatabaseResult changeDefaultDatabase(Connect connect, Catalog database, boolean sessionInitOnReconnect) {
        return changeDatabase(connect, database, sessionInitOnReconnect, true);
    }

    public ChangeDefaultDatabaseResult changeSessionDatabase(Connect connect, Catalog database, boolean sessionInitOnReconnect) {
        return changeDatabase(connect, database, sessionInitOnReconnect, false);
    }

    private ChangeDefaultDatabaseResult changeDatabase(Connect connect,
                                                       Catalog database,
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
        String previousDatabase = connect.getCatalog();
        String previousSessionDatabase = connect.getSessionCatalog();
        try {
            platformResolver.metadata(connect).changeDatabase(connect.getConn(), database.getName());
            applyTargetDatabase(dialect, connect, database, persistDefaultDatabase);
            if (!dialect.isSystemDatabase(database.getName())) {
                applySupportedConnectionProperty(connect, PROP_DB_LOCALE, database.getDbLocale());
            }
            adjustDefaultDatabaseIsolationLevel(connect, database, persistDefaultDatabase);
            if (persistDefaultDatabase) {
                LocalDbRepository.updateConnect(connect);
            }
            result.setSuccess(true);
        } catch (SQLException e) {
            ChangeDatabaseFailureKind kind = dialect.connection().classifyChangeDatabaseFailure(e);
            if (kind == ChangeDatabaseFailureKind.DISCONNECTED) {
                result.setDisconnected(true);
            } else if (kind == ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION) {
                Connection oldConn = connect.getConn();
                String previousProps = connect.getProps();
                try {
                    applyTargetDatabase(dialect, connect, database, persistDefaultDatabase);
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
                    connect.setConnPreserveKeepAlive(newConn);
                    closeConnectionQuietly(oldConn);
                    if (persistDefaultDatabase) {
                        LocalDbRepository.updateConnect(connect);
                    }
                    result.setSuccess(true);
                    result.setReconnected(true);
                } catch (Exception ex) {
                    connect.setConnPreserveKeepAlive(oldConn);
                    connect.setCatalog(previousDatabase);
                    connect.setSessionCatalog(previousSessionDatabase);
                    connect.setProps(previousProps);
                    recoverFailedChangeDatabaseSession(connect, dialect, previousSessionDatabase, previousDatabase);
                    applyReconnectFailure(result, ex);
                }
            } else {
                recoverFailedChangeDatabaseSession(connect, dialect, previousSessionDatabase, previousDatabase);
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
        return platformResolver.requirePlatform(connect).connection().modifyProps(connect, propName, propValue);
    }

    private void adjustDefaultDatabaseIsolationLevel(Connect connect,
                                                     Catalog database,
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
            //applySupportedConnectionProperty(connect, PROP_IFX_ISOLATION_LEVEL, "5");
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

    @Override
    public String refreshRuntimeConnectInfo(Connect connect) throws Exception {
        String primaryInstance = setConnectInfo(connect);
        populateNamedServerAddressInfo(connect, primaryInstance);
        return primaryInstance;
    }

    private void populateNamedServerAddressInfo(Connect connect, String primaryInstance) {
        if (connect == null) {
            return;
        }
        String namedServerProp = platformResolver.capability(connect, NamedServerConnectionCapability.class)
                .map(NamedServerConnectionCapability::namedServerPropertyName)
                .orElse("");
        if (namedServerProp.isEmpty()) {
            return;
        }
        String groupName = connect.getPropByName(namedServerProp);
        if (groupName == null || groupName.isBlank()) {
            return;
        }
        if (primaryInstance == null || primaryInstance.isBlank()) {
            return;
        }
        try {
            String sqlhostsContent = Files.readString(Paths.get("extlib", connect.getDbtype(), "sqlhosts"));
            String normalizedInstance = primaryInstance.trim();
            String normalizedGroup = groupName.trim();
            for (String line : sqlhostsContent.split("\\R")) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 5) {
                    continue;
                }
                if (!normalizedInstance.equals(parts[0])) {
                    continue;
                }
                boolean inGroup = false;
                for (int i = 4; i < parts.length; i++) {
                    if (("g=" + normalizedGroup).equalsIgnoreCase(parts[i])) {
                        inGroup = true;
                        break;
                    }
                }
                if (!inGroup) {
                    continue;
                }
                connect.setIp(parts[2].trim());
                connect.setPort(parts[3].trim());
                connect.setInfo(replaceInfoValue(connect.getInfo(), namedServerProp, normalizedInstance));
                return;
            }
        } catch (Exception e) {
            log.debug("Populate named-server address info skipped", e);
        }
    }

    private String replaceInfoValue(String info, String key, String value) {
        if (info == null || info.isBlank() || key == null || key.isBlank()) {
            return info;
        }
        Pattern pattern = Pattern.compile("(?m)^(" + Pattern.quote(key) + "\\s+)\\S+\\s*$");
        Matcher matcher = pattern.matcher(info);
        if (matcher.find()) {
            return matcher.replaceAll("$1" + Matcher.quoteReplacement(value == null ? "" : value));
        }
        String suffix = info.endsWith("\n") ? "" : "\n";
        return info + suffix + String.format("%-30s", key) + (value == null ? "" : value) + "\n";
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
            return dialect.connection().testConnection(connect.getConn());
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

    private ConnectionLease acquireConnection(Connect connect, Catalog database) throws Exception {
        Connection conn = connect.getConn();
        var repo = platformResolver.metadata(connect);
        try {
            repo.setDatabase(conn, database.getName());
            return new ConnectionLease(conn, false);
        } catch (SQLException e) {
            DatabasePlatform dialect = platformResolver.getPlatform(connect.getDbtype());
            String fallback = resolveReconnectFallbackDatabase(dialect);
            if (dialect != null
                    && dialect.connection().classifyChangeDatabaseFailure(e) == ChangeDatabaseFailureKind.RETRY_WITH_NEW_CONNECTION
                    && fallback != null) {
                repo.setDatabase(connect.getConn(), fallback);
                Connect connect1 = new Connect(connect);
                dialect.connection().setSessionCatalog(connect1, database.getName());
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

    private void applyTargetDatabase(DatabasePlatform dialect,
                                     Connect connect,
                                     Catalog database,
                                     boolean persistDefaultDatabase) {
        if (dialect == null || connect == null || database == null) {
            return;
        }
        if (persistDefaultDatabase) {
            connect.setCatalog(database.getName());
            if ("ORACLE".equalsIgnoreCase(dialect.getDbType())) {
                connect.setSessionCatalog("");
            } else {
                connect.setSessionCatalog(database.getName());
            }
            return;
        }
        dialect.connection().setSessionCatalog(connect, database.getName());
    }

    public <T> T withMetaSession(Connect connect, Catalog database, SqlWork<T> action) throws Exception {
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

    private boolean shouldIgnoreIsolationLevel(Catalog database) {
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
        return platform != null && containsConnectionProperty(platform.connection().defaultConnectionProps(), propName);
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
        if (dialect == null) {
            return null;
        }
        return dialect.capability(ReconnectFallbackCapability.class)
                .map(ReconnectFallbackCapability::reconnectFallbackDatabaseName)
                .orElse(null);
    }

    private void recoverFailedChangeDatabaseSession(Connect connect,
                                                    DatabasePlatform dialect,
                                                    String previousSessionDatabase,
                                                    String previousDatabase) {
        if (connect == null || connect.getConn() == null || dialect == null) {
            return;
        }
        String recoveryDatabase = previousSessionDatabase;
        if (recoveryDatabase == null || recoveryDatabase.isBlank()) {
            recoveryDatabase = previousDatabase;
        }
        if (recoveryDatabase == null || recoveryDatabase.isBlank()) {
            recoveryDatabase = resolveReconnectFallbackDatabase(dialect);
        }
        if (recoveryDatabase == null || recoveryDatabase.isBlank()) {
            return;
        }
        try {
            platformResolver.metadata(connect).changeDatabase(connect.getConn(), recoveryDatabase);
        } catch (Exception recoveryEx) {
            log.warn("Recover session database after failed changeDatabase failed: {}", connect.getName(), recoveryEx);
        }
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
