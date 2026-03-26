package com.dbboys.vo;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ForeignKeyInfo {
    private StringProperty fkName = new SimpleStringProperty();
    private StringProperty fkOwner = new SimpleStringProperty();
    private IntegerProperty fkTabid = new SimpleIntegerProperty();
    private StringProperty fkTabname = new SimpleStringProperty();
    private StringProperty fkCols = new SimpleStringProperty();
    private StringProperty fkIdxName = new SimpleStringProperty();
    private StringProperty pkOwner = new SimpleStringProperty();
    private IntegerProperty pkTabid = new SimpleIntegerProperty();
    private StringProperty pkTabname = new SimpleStringProperty();
    private StringProperty pkCols = new SimpleStringProperty();
    private StringProperty pkIdxName = new SimpleStringProperty();
    private BooleanProperty isdisabled=new SimpleBooleanProperty();
    /*
     * 表标识(smallint两字节)：
     * 1, 最高位开始第一位是1时（位与16384值为16384时），SQLMODE=Oracle，
     * 2，最高位开始第二位是1时（位与8192值为8192时），事务级（commit delete）；为0时则为 会话级（COMMIT PRESERVE）
     * 3，最高位开始第三位是1时（位与4096值为4096时），全局临时表；为0时则为默认的永久表
     */
    private IntegerProperty flags = new SimpleIntegerProperty();

    public String getFkName() {
        return fkName.get();
    }

    public StringProperty fkNameProperty() {
        return fkName;
    }

    public void setFkName(String fkName) {
        this.fkName.set(fkName);
    }

    public String getFkOwner() {
        return fkOwner.get();
    }

    public StringProperty fkOwnerProperty() {
        return fkOwner;
    }

    public void setFkOwner(String fkOwner) {
        this.fkOwner.set(fkOwner);
    }

    public String getFkTabname() {
        return fkTabname.get();
    }

    public StringProperty fkTabnameProperty() {
        return fkTabname;
    }

    public void setFkTabname(String fkTabname) {
        this.fkTabname.set(fkTabname);
    }

    public String getFkCols() {
        return fkCols.get();
    }

    public StringProperty fkColsProperty() {
        return fkCols;
    }

    public void setFkCols(String fkCols) {
        this.fkCols.set(fkCols);
    }

    public String getFkIdxName() {
        return fkIdxName.get();
    }

    public StringProperty fkIdxNameProperty() {
        return fkIdxName;
    }

    public void setFkIdxName(String fkIdxName) {
        this.fkIdxName.set(fkIdxName);
    }

    public String getPkOwner() {
        return pkOwner.get();
    }

    public StringProperty pkOwnerProperty() {
        return pkOwner;
    }

    public void setPkOwner(String pkOwner) {
        this.pkOwner.set(pkOwner);
    }

    public String getPkTabname() {
        return pkTabname.get();
    }

    public StringProperty pkTabnameProperty() {
        return pkTabname;
    }

    public void setPkTabname(String pkTabname) {
        this.pkTabname.set(pkTabname);
    }

    public String getPkCols() {
        return pkCols.get();
    }

    public StringProperty pkColsProperty() {
        return pkCols;
    }

    public void setPkCols(String pkCols) {
        this.pkCols.set(pkCols);
    }

    public String getPkIdxName() {
        return pkIdxName.get();
    }

    public StringProperty pkIdxNameProperty() {
        return pkIdxName;
    }

    public void setPkIdxName(String pkIdxName) {
        this.pkIdxName.set(pkIdxName);
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

    public int getFlags() {
        return flags.get();
    }

    public IntegerProperty flagsProperty() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags.set(flags);
    }

    public int getFkTabid() {
        return fkTabid.get();
    }

    public IntegerProperty fkTabidProperty() {
        return fkTabid;
    }

    public void setFkTabid(int fkTabid) {
        this.fkTabid.set(fkTabid);
    }

    public int getPkTabid() {
        return pkTabid.get();
    }

    public IntegerProperty pkTabidProperty() {
        return pkTabid;
    }

    public void setPkTabid(int pkTabid) {
        this.pkTabid.set(pkTabid);
    }

    /**
     * 返回 SQLMODE
     * @return
     */
    public String getForeignKeyModeFunc(){
        if ((this.flags.get() & 16384) == 16384) {
            return "Oracle";
        }
        return "GBase";
    }

    @Override
    public String toString(){
        return "FKName: " + this.fkName.get();
    }
}

