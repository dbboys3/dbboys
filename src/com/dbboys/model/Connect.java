package com.dbboys.model;

import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.infra.config.ConfigManagerUtil;
import javafx.beans.property.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class Connect extends TreeData{
    private static final Logger log = LogManager.getLogger(Connect.class);
    private static final String KEEPALIVE_INTERVAL_CONFIG_KEY = "CONNECT_KEEPALIVE_SECONDS";
    private static final long DEFAULT_KEEPALIVE_INTERVAL_SECONDS = 180L;
    private static final ScheduledExecutorService KEEPALIVE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "dbboys-connect-keepalive");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static volatile DatabasePlatformResolver platformResolver;

    private IntegerProperty  id=new SimpleIntegerProperty();
    private IntegerProperty  parentId=new SimpleIntegerProperty();
    private StringProperty  dbtype=new SimpleStringProperty();
    private StringProperty  ip=new SimpleStringProperty();
    private StringProperty  port=new SimpleStringProperty();
    private StringProperty  catalog=new SimpleStringProperty();
    private StringProperty  sessionCatalog=new SimpleStringProperty();
    private StringProperty  username=new SimpleStringProperty();
    private StringProperty  password=new SimpleStringProperty();
    private Connection conn;
    private StringProperty  driver=new SimpleStringProperty();
    private StringProperty  props=new SimpleStringProperty();
    private StringProperty  info=new SimpleStringProperty();
    private StringProperty  drivermd5=new SimpleStringProperty();
    private StringProperty  dbversion=new SimpleStringProperty();
    private BooleanProperty readonly=new SimpleBooleanProperty();
    private StringProperty  sshHost=new SimpleStringProperty();
    private StringProperty  sshPort=new SimpleStringProperty();
    private StringProperty  sshUser=new SimpleStringProperty();
    private StringProperty  sshPassword=new SimpleStringProperty();
    private StringProperty  sshAuthType=new SimpleStringProperty("password");
    private StringProperty  sshKeyPath=new SimpleStringProperty();
    private StringProperty  sshKeyPassphrase=new SimpleStringProperty();
    private BooleanProperty sshEnabled=new SimpleBooleanProperty();
    private volatile boolean keepAliveEnabled;
    private volatile ScheduledFuture<?> keepAliveFuture;
    /** 进程内首次读取 CONNECT_KEEPALIVE_SECONDS 后的快照；Long.MIN_VALUE 表示尚未读取。 */
    private static volatile long keepAliveIntervalSecondsSnap = Long.MIN_VALUE;
    //每个连接一个顺序执行线程，对于目录树耗时的加载，顺序执行，避免同一个连接多个任务导致问题
    public ExecutorService executorService= Executors.newSingleThreadExecutor();

    public Connect(){
    }
    public Connect(String name){
        super(name);
    }


    public Connect(Connect connect){  //仅连接不设置
        setId(connect.getId());
        setName(connect.getName());
        setParentId(connect.getParentId());
        setDbtype(connect.getDbtype());
        setIp(connect.getIp());
        setPort(connect.getPort());
        setCatalog(connect.getCatalog());
        setSessionCatalog(connect.getSessionCatalog());
        setUsername(connect.getUsername());
        setPassword(connect.getPassword());
        setDriver(connect.getDriver());
        setProps(connect.getProps());
        setInfo(connect.getInfo());
        setDrivermd5(connect.getDrivermd5());
        setDbversion(connect.getDbversion());
        setReadonly(connect.getReadonly());
        setSshHost(connect.getSshHost());
        setSshPort(connect.getSshPort());
        setSshUser(connect.getSshUser());
        setSshPassword(connect.getSshPassword());
        setSshEnabled(connect.getSshEnabled());
        keepAliveEnabled = connect.keepAliveEnabled;
    }

    @Override
    public Object clone()  {  //实现克隆对象,类型采用SimpleStringProperty之后似乎克隆变成了引用原有对象，暂时弃用，改用构造函数克隆
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public Connection getConn() {
        return conn;
    }
    public void setConn(Connection conn) {
        setConnInternal(conn, false, true);
    }
    public void setConnWithKeepAlive(Connection conn) {
        setConnInternal(conn, true, true);
    }
    public void setConnPreserveKeepAlive(Connection conn) {
        setConnInternal(conn, keepAliveEnabled, false);
    }

    public int getId() {
        return id.get();
    }

    public IntegerProperty idProperty() {
        return id;
    }

    public void setId(int id) {
        this.id.set(id);
    }

    public int getParentId() {
        return parentId.get();
    }

    public IntegerProperty parentIdProperty() {
        return parentId;
    }

    public void setParentId(int parentId) {
        this.parentId.set(parentId);
    }

    public String getDbtype() {
        return dbtype.get();
    }

    public StringProperty dbtypeProperty() {
        return dbtype;
    }

    public void setDbtype(String dbtype) {
        this.dbtype.set(dbtype);
    }

    public String getIp() {
        return ip.get();
    }

    public StringProperty ipProperty() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip.set(ip);
    }

    public String getPort() {
        return port.get();
    }

    public StringProperty portProperty() {
        return port;
    }

    public void setPort(String port) {
        this.port.set(port);
    }

    public String getCatalog() {
        return catalog.get();
    }

    public StringProperty catalogProperty() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog.set(catalog);
    }

    public String getSessionCatalog() {
        return sessionCatalog.get();
    }

    public StringProperty sessionCatalogProperty() {
        return sessionCatalog;
    }

    public void setSessionCatalog(String sessionCatalog) {
        this.sessionCatalog.set(sessionCatalog);
    }

    public String getEffectiveCatalog() {
        String sd = getSessionCatalog();
        return sd != null && !sd.isBlank() ? sd : getCatalog();
    }

    public String getUsername() {
        return username.get();
    }

    public StringProperty usernameProperty() {
        return username;
    }

    public void setUsername(String username) {
        this.username.set(username);
    }

    public String getPassword() {
        return password.get();
    }

    public StringProperty passwordProperty() {
        return password;
    }

    public void setPassword(String password) {
        this.password.set(password);
    }


    public String getDriver() {
        return driver.get();
    }

    public StringProperty driverProperty() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver.set(driver);
    }

    public String getProps() {
        return props.get();
    }

    public StringProperty propsProperty() {
        return props;
    }

    public void setProps(String props) {
        this.props.set(props);
    }

     public void setPropByName(String propName,String propValue) {
        JSONArray jsonArray=new JSONArray(getProps());
        for(int i=0;i<jsonArray.length();i++){
            JSONObject jsonObject=jsonArray.getJSONObject(i);
            if(jsonObject.getString("propName").equals(propName)){
                jsonObject.put("propValue",propValue);
                setProps(jsonArray.toString());
            }
        }
    }

    public String getPropByName(String propName) {
        String reString="";
        JSONArray jsonArray=new JSONArray(getProps());
        for(int i=0;i<jsonArray.length();i++){
            JSONObject jsonObject=jsonArray.getJSONObject(i);
            if(jsonObject.getString("propName").equals(propName)){
                reString= jsonObject.getString("propValue");
            }
        }
        return reString;
    }

    public String getInfo() {
        return info.get();
    }

    public StringProperty infoProperty() {
        return info;
    }

    public void setInfo(String info) {
        this.info.set(info);
    }

    public String getDrivermd5() {
        return drivermd5.get();
    }

    public StringProperty drivermd5Property() {
        return drivermd5;
    }

    public void setDrivermd5(String drivermd5) {
        this.drivermd5.set(drivermd5);
    }

    public String getDbversion() {
        return dbversion.get();
    }

    public StringProperty dbversionProperty() {
        return dbversion;
    }

    public void setDbversion(String dbversion) {
        this.dbversion.set(dbversion);
    }

    public Boolean getReadonly() {
        return readonly.get();
    }

    public BooleanProperty readonlyProperty() {
        return readonly;
    }

    public void setReadonly(Boolean readonly) {
        this.readonly.set(readonly);
    }

    public String getSshHost() {
        return sshHost.get();
    }
    public StringProperty sshHostProperty() {
        return sshHost;
    }
    public void setSshHost(String sshHost) {
        this.sshHost.set(sshHost);
    }

    public String getSshPort() {
        return sshPort.get();
    }
    public StringProperty sshPortProperty() {
        return sshPort;
    }
    public void setSshPort(String sshPort) {
        this.sshPort.set(sshPort);
    }

    public String getSshUser() {
        return sshUser.get();
    }
    public StringProperty sshUserProperty() {
        return sshUser;
    }
    public void setSshUser(String sshUser) {
        this.sshUser.set(sshUser);
    }

    public String getSshPassword() {
        return sshPassword.get();
    }
    public StringProperty sshPasswordProperty() {
        return sshPassword;
    }
    public void setSshPassword(String sshPassword) {
        this.sshPassword.set(sshPassword);
    }

    public Boolean getSshEnabled() {
        return sshEnabled.get();
    }
    public BooleanProperty sshEnabledProperty() {
        return sshEnabled;
    }
    public void setSshEnabled(Boolean sshEnabled) {
        this.sshEnabled.set(sshEnabled);
    }

    // --- sshAuthType ---
    public String getSshAuthType() { return sshAuthType.get(); }
    public StringProperty sshAuthTypeProperty() { return sshAuthType; }
    public void setSshAuthType(String v) { this.sshAuthType.set(v); }
    public boolean isSshAuthKey() { return SshConnect.AUTH_KEY.equals(sshAuthType.get()); }

    // --- sshKeyPath ---
    public String getSshKeyPath() { return sshKeyPath.get(); }
    public StringProperty sshKeyPathProperty() { return sshKeyPath; }
    public void setSshKeyPath(String v) { this.sshKeyPath.set(v); }

    // --- sshKeyPassphrase ---
    public String getSshKeyPassphrase() { return sshKeyPassphrase.get(); }
    public StringProperty sshKeyPassphraseProperty() { return sshKeyPassphrase; }
    public void setSshKeyPassphrase(String v) { this.sshKeyPassphrase.set(v); }

    public String toString(){
        return getName();
    }

    public Future<?> executeSqlTask(Runnable task) {
        return executorService.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                com.dbboys.app.AppErrorHandler.handle(e);
            }
        });
    }

    private void scheduleKeepAlive() {
        long intervalSeconds = resolveKeepAliveIntervalSeconds();
        if (intervalSeconds <= 0 || conn == null) {
            return;
        }
        keepAliveFuture = KEEPALIVE_SCHEDULER.schedule(this::submitKeepAliveTask, intervalSeconds, TimeUnit.SECONDS);
    }

    private void submitKeepAliveTask() {
        if (conn == null) {
            return;
        }
        executeSqlTask(() -> {
            try {
                if (conn == null || conn.isClosed()) {
                    return;
                }
                log.info("Connection keepalive test running: {}", getName());
                DatabasePlatformResolver resolver = resolvePlatformResolver();
                var platform = resolver.getPlatform(getDbtype());
                if (platform == null) {
                    return;
                }
                boolean alive = platform.connection().testConnection(conn);
                if (!alive) {
                    log.warn("Connection keepalive test failed: {}", getName());
                } else {
                    log.info("Connection keepalive test succeeded: {}", getName());
                }
            } catch (Exception e) {
                log.warn("Connection keepalive task failed: {}", getName(), e);
            } finally {
                cancelKeepAlive();
                if (conn != null) {
                    scheduleKeepAlive();
                }
            }
        });
    }

    private void cancelKeepAlive() {
        ScheduledFuture<?> future = keepAliveFuture;
        if (future != null) {
            future.cancel(false);
            keepAliveFuture = null;
        }
    }

    private void setConnInternal(Connection conn, boolean enableKeepAlive, boolean updatePreference) {
        cancelKeepAlive();
        this.conn = conn;
        if (updatePreference) {
            keepAliveEnabled = enableKeepAlive;
        }
        if (this.conn != null && keepAliveEnabled) {
            scheduleKeepAlive();
        }
    }

    private long resolveKeepAliveIntervalSeconds() {
        long snap = keepAliveIntervalSecondsSnap;
        if (snap != Long.MIN_VALUE) {
            return snap;
        }
        synchronized (Connect.class) {
            if (keepAliveIntervalSecondsSnap != Long.MIN_VALUE) {
                return keepAliveIntervalSecondsSnap;
            }
            keepAliveIntervalSecondsSnap = readConnectKeepAliveIntervalSecondsFromConfig();
            return keepAliveIntervalSecondsSnap;
        }
    }

    private static long readConnectKeepAliveIntervalSecondsFromConfig() {
        String configured = ConfigManagerUtil.getProperty(
                KEEPALIVE_INTERVAL_CONFIG_KEY,
                String.valueOf(DEFAULT_KEEPALIVE_INTERVAL_SECONDS)
        );
        if (configured == null || configured.isBlank()) {
            return DEFAULT_KEEPALIVE_INTERVAL_SECONDS;
        }
        try {
            long v = Long.parseLong(configured.trim());
            return v <= 0 ? 0L : v;
        } catch (NumberFormatException e) {
            log.warn("Invalid {} value: {}", KEEPALIVE_INTERVAL_CONFIG_KEY, configured);
            return DEFAULT_KEEPALIVE_INTERVAL_SECONDS;
        }
    }

    private static DatabasePlatformResolver resolvePlatformResolver() {
        DatabasePlatformResolver cached = platformResolver;
        if (cached != null) {
            return cached;
        }
        synchronized (Connect.class) {
            cached = platformResolver;
            if (cached != null) {
                return cached;
            }
            cached = DatabasePlatformResolver.getInstance();
            platformResolver = cached;
            return cached;
        }
    }
}
