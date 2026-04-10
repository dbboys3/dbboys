package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Synonym extends TreeData{


    //created by liaosnet
    private StringProperty synType = new SimpleStringProperty();
    private StringProperty synOwner = new SimpleStringProperty();
    private StringProperty rServerName = new SimpleStringProperty();
    private StringProperty rDbName = new SimpleStringProperty();
    private StringProperty rOwner = new SimpleStringProperty();
    private StringProperty rTabName = new SimpleStringProperty();
    private StringProperty lOwner = new SimpleStringProperty();
    private StringProperty lTabName = new SimpleStringProperty();
    /*
     * 表标识(smallint两字节)：
     * 1, 最高位开始第一位是1时（位与16384值为16384时），SQLMODE=Oracle，
     */
    private IntegerProperty flags = new SimpleIntegerProperty();
    private StringProperty synonymSqlMode = new SimpleStringProperty();

    public String getSynonymSqlMode() {
        return synonymSqlMode.get();
    }

    public StringProperty synonymSqlModeProperty() {
        return synonymSqlMode;
    }

    public void setSynonymSqlMode(int synonymSqlMode) {
        String tmpstr = "GBase";
        if ((synonymSqlMode & 16384) == 16384) {
            tmpstr = "Oracle";
        } else if ((synonymSqlMode & 65536) == 65536){
            tmpstr = "MySQL";
        }
        this.synonymSqlMode.set(tmpstr);
    }

    public String toString(){
        return "SynType: " + this.synType.get() + "\n" +
                "SynOwner: " + this.synOwner.get() + "\n" +
                "SynName: " + this.getName() + "\n" +
                "RServerName: " + this.rServerName.get() + "\n" +
                "RDbName: " + this.rDbName.get() + "\n" +
                "ROwner: " + this.rOwner.get() + "\n" +
                "RTabName: " + this.rTabName.get() + "\n" +
                "LOwner: " + this.lOwner.get() + "\n" +
                "LTabName: " + this.lTabName.get();
    }

    //created by L3
    private StringProperty database=new SimpleStringProperty();
    private StringProperty created = new SimpleStringProperty();
    public Synonym(String name) {
        super(name);
    }

    public String getSynType() {
        return synType.get();
    }

    public StringProperty synTypeProperty() {
        return synType;
    }

    public void setSynType(String synType) {
        this.synType.set(synType);
    }

    public String getSynOwner() {
        return synOwner.get();
    }

    public StringProperty synOwnerProperty() {
        return synOwner;
    }

    public void setSynOwner(String synOwner) {
        this.synOwner.set(synOwner);
    }

    public String getRServerName() {
        return rServerName.get();
    }

    public StringProperty rServerNameProperty() {
        return rServerName;
    }

    public void setRServerName(String rServerName) {
        this.rServerName.set(rServerName);
    }

    public String getRDbName() {
        return rDbName.get();
    }

    public StringProperty rDbNameProperty() {
        return rDbName;
    }

    public void setRDbName(String rDbName) {
        this.rDbName.set(rDbName);
    }

    public String getROwner() {
        return rOwner.get();
    }

    public StringProperty rOwnerProperty() {
        return rOwner;
    }

    public void setROwner(String rOwner) {
        this.rOwner.set(rOwner);
    }

    public String getRTabName() {
        return rTabName.get();
    }

    public StringProperty rTabNameProperty() {
        return rTabName;
    }

    public void setRTabName(String rTabName) {
        this.rTabName.set(rTabName);
    }

    public String getLOwner() {
        return lOwner.get();
    }

    public StringProperty lOwnerProperty() {
        return lOwner;
    }

    public void setLOwner(String lOwner) {
        this.lOwner.set(lOwner);
    }

    public String getLTabName() {
        return lTabName.get();
    }

    public StringProperty lTabNameProperty() {
        return lTabName;
    }

    public void setLTabName(String lTabName) {
        this.lTabName.set(lTabName);
    }

    public int getFlags() {
        return flags.get();
    }

    public IntegerProperty flagsProperty() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags.set(flags);
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


    public String getSynonymType() {
        return getSynType();
    }

    public StringProperty synonymTypeProperty() {
        return synTypeProperty();
    }

    public void setSynonymType(String synonymType) {
        setSynType(synonymType);
    }

    public String getCreated() {
        return created.get();
    }

    public StringProperty createdProperty() {
        return created;
    }

    public void setCreated(String created) {
        this.created.set(created);
    }
}
