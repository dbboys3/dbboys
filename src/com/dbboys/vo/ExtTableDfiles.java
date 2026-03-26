package com.dbboys.vo;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ExtTableDfiles {
    private StringProperty tableName = new SimpleStringProperty();
    private StringProperty dataFile = new SimpleStringProperty();
    private StringProperty blobDir = new SimpleStringProperty();
    private StringProperty clobDir = new SimpleStringProperty();

    public String getTableName() {
        return tableName.get();
    }

    public StringProperty tableNameProperty() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName.set(tableName);
    }

    public String getDataFile() {
        return dataFile.get();
    }

    public StringProperty dataFileProperty() {
        return dataFile;
    }

    public void setDataFile(String dataFile) {
        this.dataFile.set(dataFile);
    }

    public String getBlobDir() {
        return blobDir.get();
    }

    public StringProperty blobDirProperty() {
        return blobDir;
    }

    public void setBlobDir(String blobDir) {
        this.blobDir.set(blobDir);
    }

    public String getClobDir() {
        return clobDir.get();
    }

    public StringProperty clobDirProperty() {
        return clobDir;
    }

    public void setClobDir(String clobDir) {
        this.clobDir.set(clobDir);
    }

    @Override
    public String toString(){
        return "TableName: " + this.tableName.get() + "\n" +
                "DataFile: " + this.dataFile.get();
    }
}

