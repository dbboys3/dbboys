package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class PrimaryKeyInfo {
    private IntegerProperty tableId = new SimpleIntegerProperty();      // 表ID
    private StringProperty constrName = new SimpleStringProperty();     // 主键约束名
    private StringProperty constrType = new SimpleStringProperty();     // 主键约束类型
    private StringProperty idxCols = new SimpleStringProperty();        // 索引字段列表

    public int getTableId() {
        return tableId.get();
    }

    public IntegerProperty tableIdProperty() {
        return tableId;
    }

    public void setTableId(int tableId) {
        this.tableId.set(tableId);
    }

    public String getConstrName() {
        return constrName.get();
    }

    public StringProperty constrNameProperty() {
        return constrName;
    }

    public void setConstrName(String constrName) {
        this.constrName.set(constrName);
    }

    public String getConstrType() {
        return constrType.get();
    }

    public StringProperty constrTypeProperty() {
        return constrType;
    }

    public void setConstrType(String constrType) {
        this.constrType.set(constrType);
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
        return "constrName: " + this.constrName.get() + "\n" +
                "constrType: " + this.constrType.get() + "\n" +
                "index columns: " + this.idxCols.get();
    }
}

