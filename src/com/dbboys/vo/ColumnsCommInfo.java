package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ColumnsCommInfo {
    private StringProperty tabName;     // 表名
    private IntegerProperty tabId;      // 表ID
    private StringProperty colName;     // 字段名
    private IntegerProperty colNo;      // 字段序号
    private StringProperty colComm;     // 注释内容

    public ColumnsCommInfo() {
        this.tabName = new SimpleStringProperty();
        this.tabId = new SimpleIntegerProperty();
        this.colName = new SimpleStringProperty();
        this.colNo = new SimpleIntegerProperty();
        this.colComm = new SimpleStringProperty();
    }

    public String getTabName() {
        return tabName.get();
    }

    public StringProperty tabNameProperty() {
        return tabName;
    }

    public void setTabName(String tabName) {
        this.tabName.set(tabName);
    }

    public int getTabId() {
        return tabId.get();
    }

    public IntegerProperty tabIdProperty() {
        return tabId;
    }

    public void setTabId(int tabId) {
        this.tabId.set(tabId);
    }

    public String getColName() {
        return colName.get();
    }

    public StringProperty colNameProperty() {
        return colName;
    }

    public void setColName(String colName) {
        this.colName.set(colName);
    }

    public int getColNo() {
        return colNo.get();
    }

    public IntegerProperty colNoProperty() {
        return colNo;
    }

    public void setColNo(int colNo) {
        this.colNo.set(colNo);
    }

    public String getColComm() {
        return colComm.get();
    }

    public StringProperty colCommProperty() {
        return colComm;
    }

    public void setColComm(String colComm) {
        this.colComm.set(colComm);
    }

    @Override
    public String toString(){
        return "ColComm: " + this.colComm.get();
    }

    /**
     * 设置表字段注释信息
     * @param TabName
     * @param ColName
     * @param ColNo
     * @param ColComm
     */
    public void setColumnsCommInfo(int ColNo, String ColComm, String TabName, String ColName){
        this.tabName.set(TabName);
        this.colName.set(ColName);
        this.colNo.set(ColNo);
        this.colComm.set(ColComm);
    }

    /**
     * 设置表字段注释信息
     * @param ColNo
     * @param ColComm
     */
    public void setColumnsCommInfo(int ColNo, String ColComm){
        this.colNo.set(ColNo);
        this.colComm.set(ColComm);
    }

}
