package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Procedure extends TreeData{

    //created by liaosnet
    private StringProperty procBoday = new SimpleStringProperty();
    private IntegerProperty procId = new SimpleIntegerProperty();

    /*
     * 函数/过程标识(int 4字节)：
     * 1, 最低位开始第三位是1时（位与4值为4时），SQLMODE=Oracle，
     */
    private IntegerProperty procFlags = new SimpleIntegerProperty();

    /**
     * 返回存储过程/函数的数据库模式
     * @return
     */
    public String getProcSqlMode(){
        if((this.procFlags.get() & 4) == 4){
            return "Oracle";
        }
        return "GBase";
    }

    @Override
    public String toString(){
        return "ProcName: " + this.getName() + "\n" +
                "ProcBoday: " + this.procBoday.get();
    }

    //created by L3
    private StringProperty database=new SimpleStringProperty();
    private StringProperty owner=new SimpleStringProperty();
    private IntegerProperty rows=new SimpleIntegerProperty();
    public Procedure(String name) {
        super(name);
    }

    public String getProcBoday() {
        return procBoday.get();
    }

    public StringProperty procBodayProperty() {
        return procBoday;
    }

    public void setProcBoday(String procBoday) {
        this.procBoday.set(procBoday);
    }

    public int getProcId() {
        return procId.get();
    }

    public IntegerProperty procIdProperty() {
        return procId;
    }

    public void setProcId(int procId) {
        this.procId.set(procId);
    }

    public int getProcFlags() {
        return procFlags.get();
    }

    public IntegerProperty procFlagsProperty() {
        return procFlags;
    }

    public void setProcFlags(int procFlags) {
        this.procFlags.set(procFlags);
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


    public String getOwner() {
        return owner.get();
    }

    public StringProperty ownerProperty() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner.set(owner);
    }

    public int getRows() {
        return rows.get();
    }

    public IntegerProperty rowsProperty() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows.set(rows);
    }
}
