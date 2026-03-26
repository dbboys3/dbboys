package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class TableColumn {

    private IntegerProperty tableId = new SimpleIntegerProperty();
    private StringProperty tableName = new SimpleStringProperty();
    private IntegerProperty columnNo = new SimpleIntegerProperty();
    private StringProperty columnName = new SimpleStringProperty();

    public String getTableName() {
        return tableName.get();
    }

    public StringProperty tableNameProperty() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName.set(tableName);
    }

    public String getColumnName() {
        return columnName.get();
    }

    public StringProperty columnNameProperty() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName.set(columnName);
    }

    public int getTableId() {
        return tableId.get();
    }

    public IntegerProperty tableIdProperty() {
        return tableId;
    }

    public void setTableId(int tableId) {
        this.tableId.set(tableId);
    }

    public int getColumnNo() {
        return columnNo.get();
    }

    public IntegerProperty columnNoProperty() {
        return columnNo;
    }

    public void setColumnId(int columnNo) {
        this.columnNo.set(columnNo);
    }    
}
