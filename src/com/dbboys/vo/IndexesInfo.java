package com.dbboys.vo;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class IndexesInfo {
    // 用于多个索引信息，生成表的ddl
    private StringProperty idxName;
    private StringProperty idxOwner;
    private StringProperty idxType;
    private StringProperty idxCluster;
    private StringProperty idxCols;

    public IndexesInfo(){
        this.idxName = new SimpleStringProperty();
        this.idxOwner = new SimpleStringProperty();
        this.idxType = new SimpleStringProperty();
        this.idxCluster = new SimpleStringProperty();
        this.idxCols = new SimpleStringProperty();
    }

    public String getIdxName() {
        return idxName.get();
    }

    public StringProperty idxNameProperty() {
        return idxName;
    }

    public void setIdxName(String idxName) {
        this.idxName.set(idxName);
    }

    public String getIdxOwner() {
        return idxOwner.get();
    }

    public StringProperty idxOwnerProperty() {
        return idxOwner;
    }

    public void setIdxOwner(String idxOwner) {
        this.idxOwner.set(idxOwner);
    }

    public String getIdxType() {
        return idxType.get();
    }

    public StringProperty idxTypeProperty() {
        return idxType;
    }

    public void setIdxType(String idxType) {
        this.idxType.set(idxType);
    }

    public String getIdxCluster() {
        return idxCluster.get();
    }

    public StringProperty idxClusterProperty() {
        return idxCluster;
    }

    public void setIdxCluster(String idxCluster) {
        this.idxCluster.set(idxCluster);
    }

    public String getIdxCols() {
        return idxCols.get();
    }

    public StringProperty idxColsProperty() {
        return idxCols;
    }

    public void setIdxCols(String idxCols) {
        this.idxCols.set(idxCols);
    }

    @Override
    public String toString(){
        return "idxName: " + this.idxName.get();
    }
}
