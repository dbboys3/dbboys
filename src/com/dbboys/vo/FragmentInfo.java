package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class FragmentInfo {
    /*
    分片定义
    Strategy 分段分布策略的类型的代码：
    R = 循环分段存储策略
    E = 基于表达式的分段存储策略
    I = IN DBSPACE 子句指定作为分段存储策略一部分的存储位置
    N = 时间间隔（或滚动窗口）分段存储策略
    L = 列表分段存储策略
    T = 基于表的分段存储策略
    H = 表是表层次结构内的子表
    */
    private IntegerProperty tableId = new SimpleIntegerProperty();
    private StringProperty fragName = new SimpleStringProperty();
    private IntegerProperty colNo = new SimpleIntegerProperty();
    private StringProperty strategy = new SimpleStringProperty();
    private IntegerProperty evalpos = new SimpleIntegerProperty();
    private StringProperty exprtext = new SimpleStringProperty();
    private IntegerProperty flags = new SimpleIntegerProperty();
    private StringProperty dbspace = new SimpleStringProperty();
    private StringProperty partition = new SimpleStringProperty();

    public int getTableId() {
        return tableId.get();
    }
    public IntegerProperty tableIdProperty() {
        return tableId;
    }
    public void setTableId(int tableId) {
        this.tableId.set(tableId);
    }
    
    public String getFragName() {
        return fragName.get();
    }

    public StringProperty fragNameProperty() {
        return fragName;
    }

    public void setFragName(String fragName) {
        this.fragName.set(fragName);
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

    public String getStrategy() {
        return strategy.get();
    }

    public StringProperty strategyProperty() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy.set(strategy);
    }

    public int getEvalpos() {
        return evalpos.get();
    }

    public IntegerProperty evalposProperty() {
        return evalpos;
    }

    public void setEvalpos(int evalpos) {
        this.evalpos.set(evalpos);
    }

    public String getExprtext() {
        return exprtext.get();
    }

    public StringProperty exprtextProperty() {
        return exprtext;
    }

    public void setExprtext(String exprtext) {
        this.exprtext.set(exprtext);
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

    public String getDbspace() {
        return dbspace.get();
    }

    public StringProperty dbspaceProperty() {
        return dbspace;
    }

    public void setDbspace(String dbspace) {
        this.dbspace.set(dbspace);
    }

    public String getPartition() {
        return partition.get();
    }

    public StringProperty partitionProperty() {
        return partition;
    }

    public void setPartition(String partition) {
        this.partition.set(partition);
    }
}

