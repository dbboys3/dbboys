package com.dbboys.vo;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Trigger extends TreeData{
    //created by liaosnet
    private StringProperty triggerBody = new SimpleStringProperty();
    private StringProperty triggerMode = new SimpleStringProperty();     // sqlmode

    public String toString(){
        return "TriggerName: " + this.getName() + "\n" +
                "TriggerMode: " + this.getTriggerMode() + "\n" + 
                "TriggerBoday: " + this.getTriggerBody();
    }

    /**
     * 返回触发器的数据库模式
     */
    public String getTriggerSqlMode() {
        if ("O".equals(this.triggerMode.get())) {
            return "Oracle";
        }
        return "GBase";
    }

    public String getTriggerMode() {
        return triggerMode.get();
    }

    public void setTriggerMode(String triggerMode) {
        this.triggerMode.set(triggerMode);
    }

    public String getTriggerBody() {
        return triggerBody.get();
    }

    public void setTriggerBody(String triggerBody) {
        this.triggerBody.set(triggerBody);
    }

    //created by L3
    private StringProperty database=new SimpleStringProperty();
    private StringProperty tableName=new SimpleStringProperty();
    private StringProperty triggerType=new SimpleStringProperty();
    private BooleanProperty isdisabled=new SimpleBooleanProperty();
    public Trigger(){};
    public Trigger(String name) {
        super(name);
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

    public String getTableName() {
        return tableName.get();
    }

    public StringProperty tableNameProperty() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName.set(tableName);
    }


    public String getTriggerType() {
        return triggerType.get();
    }

    public StringProperty triggerTypeProperty() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType.set(triggerType);
    }

    public boolean isIsdisabled() {
        return isdisabled.get();
    }

    public BooleanProperty isdisabledProperty() {
        return isdisabled;
    }

    public void setIsdisabled(boolean isdisabled) {
        this.isdisabled.set(isdisabled);
    }
}
