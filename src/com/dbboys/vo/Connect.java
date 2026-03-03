package com.dbboys.vo;

import javafx.beans.property.*;

import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public class Connect extends TreeData{

    private IntegerProperty  id=new SimpleIntegerProperty();
    private IntegerProperty  parentId=new SimpleIntegerProperty();
    private StringProperty  dbtype=new SimpleStringProperty();
    private StringProperty  ip=new SimpleStringProperty();
    private StringProperty  port=new SimpleStringProperty();
    private StringProperty  database=new SimpleStringProperty();
    private StringProperty  username=new SimpleStringProperty();
    private StringProperty  password=new SimpleStringProperty();
    private Connection conn;
    private StringProperty  driver=new SimpleStringProperty();
    private StringProperty  props=new SimpleStringProperty();
    private StringProperty  info=new SimpleStringProperty();
    private StringProperty  drivermd5=new SimpleStringProperty();
    private StringProperty  dbversion=new SimpleStringProperty();
    private BooleanProperty readonly=new SimpleBooleanProperty();
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
        setDatabase(connect.getDatabase());
        setUsername(connect.getUsername());
        setPassword(connect.getPassword());
        setDriver(connect.getDriver());
        setProps(connect.getProps());
        setInfo(connect.getInfo());
        setDrivermd5(connect.getDrivermd5());
        setDbversion(connect.getDbversion());
        setReadonly(connect.getReadonly());
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
        this.conn = conn;
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

    public String getDatabase() {
        return database.get();
    }

    public StringProperty databaseProperty() {
        return database;
    }

    public void setDatabase(String database) {
        this.database.set(database);
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
    public String toString(){
        return getName();
    }

    public void executeSqlTask(Runnable task) {
        executorService.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                com.dbboys.app.AppErrorHandler.handle(e);
            }
        });
    }
}

