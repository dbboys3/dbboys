package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class View extends TreeData{
    //created by liaosnet
    private StringProperty viewBody = new SimpleStringProperty();
    /*
     * 表标识(smallint两字节)：
     * 1, 最高位开始第一位是1时（位与16384值为16384时），SQLMODE=Oracle，
     */
    private IntegerProperty flags = new SimpleIntegerProperty();
    private StringProperty viewSqlMode = new SimpleStringProperty();

    @Override
    public String toString(){
        return "ViewName: " + this.getName() + "\n" +
                "ViewBody: " + this.viewBody.get();
    }


    //created by L3

    private StringProperty dbname=new SimpleStringProperty();
    private StringProperty owner=new SimpleStringProperty();
    private StringProperty createTime=new SimpleStringProperty();
    public View() {}
    public View(String name) {
        super(name);
    }

    public String getViewBody() {
        return viewBody.get();
    }

    public StringProperty viewBodyProperty() {
        return viewBody;
    }

    public void setViewBody(String viewBody) {
        this.viewBody.set(viewBody);
    }

    public String getViewSqlMode() {
        return viewSqlMode.get();
    }

    public StringProperty viewSqlModeProperty() {
        return viewSqlMode;
    }

    public void setViewSqlMode(int viewSqlMode) {
        String tmpstr = "GBase";
        if ((viewSqlMode & 16384) == 16384) {
            tmpstr = "Oracle";
        } else if ((viewSqlMode & 65536) == 65536){
            tmpstr = "MySQL";
        }
        this.viewSqlMode.set(tmpstr);
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

    public String getDbname() {
        return dbname.get();
    }

    public StringProperty dbnameProperty() {
        return dbname;
    }

    public void setDbname(String dbname) {
        this.dbname.set(dbname);
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

    public String getCreateTime() {
        return createTime.get();
    }

    public StringProperty createTimeProperty() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime.set(createTime);
    }

}
