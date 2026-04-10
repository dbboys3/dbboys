package com.dbboys.vo;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Index extends TreeData {

    // 用于单个索引     created by liaosnet
    private StringProperty indexOwner=new SimpleStringProperty();
    private StringProperty tableName=new SimpleStringProperty();
    private IntegerProperty tableId=new SimpleIntegerProperty();
    private StringProperty tableType=new SimpleStringProperty();
    private StringProperty tableSqlMode=new SimpleStringProperty();
    private StringProperty tableOwner=new SimpleStringProperty();
    private StringProperty indexType=new SimpleStringProperty();           // 索引类型
    private StringProperty indexCluster=new SimpleStringProperty();        // 是否cluster类型
    private StringProperty indexCols=new SimpleStringProperty();           // 索引字段列表
    //合并结束

    //created by L3
    private StringProperty levels=new SimpleStringProperty();
    private StringProperty uniqueValues=new SimpleStringProperty();
    private StringProperty pageSize=new SimpleStringProperty();
    private StringProperty totalPages=new SimpleStringProperty();
    private StringProperty totalSize=new SimpleStringProperty();
    private StringProperty database=new SimpleStringProperty();
    private BooleanProperty isDisabled=new SimpleBooleanProperty();
    public Index() {}
    public Index(String name) {
        super(name);
    }
    public String getTabname() {
        return tableName.get();
    }

    public StringProperty tabnameProperty() {
        return tableName;
    }

    public void setTabname(String tabname) {
        this.tableName.set(tabname);
    }

    public String getTableType() {
        return tableType.get();
    }

    public StringProperty tableTypeProperty() {
        return tableType;
    }

    public void setTableType(String tableType) {
        this.tableType.set(tableType);
    }

    public String getTableSqlMode() {
        return tableSqlMode.get();
    }

    public StringProperty tableSqlModeProperty() {
        return tableSqlMode;
    }

    public void setTableSqlMode(int tableSqlMode) {
        String sqlmode = "GBase";
        if ((tableSqlMode & 16384) == 16384){
            sqlmode = "Oracle";
        } else if ((tableSqlMode & 65536) == 65536){
            sqlmode = "MySQL";
        }
        this.tableSqlMode.set(sqlmode);
    }

    public int getTableId(){
        return tableId.get();
    }
    
    public IntegerProperty tableIdProperty() {
        return tableId;
    }

    public void setTableId(int tableId){
        this.tableId.set(tableId);
    }

    public String getCols() {
        return indexCols.get();
    }

    public StringProperty colsProperty() {
        return indexCols;
    }

    public void setCols(String cols) {
        this.indexCols.set(cols);
    }

    public String getIdxtype() {
        return indexType.get();
    }

    public StringProperty idxtypeProperty() {
        return indexType;
    }

    public void setIdxtype(String idxtype) {
        this.indexType.set(idxtype);
    }

    public String getLevels() {
        return levels.get();
    }

    public StringProperty levelsProperty() {
        return levels;
    }

    public void setLevels(String levels) {
        this.levels.set(levels);
    }

    public String getUniqvalues() {
        return uniqueValues.get();
    }

    public StringProperty uniqvaluesProperty() {
        return uniqueValues;
    }

    public void setUniqvalues(String uniqvalues) {
        this.uniqueValues.set(uniqvalues);
    }

    public String getPagesize() {
        return pageSize.get();
    }

    public StringProperty pagesizeProperty() {
        return pageSize;
    }

    public void setPagesize(String pagesize) {
        this.pageSize.set(pagesize);
    }

    public String getTotalpages() {
        return totalPages.get();
    }

    public StringProperty totalpagesProperty() {
        return totalPages;
    }

    public void setTotalpages(String totalpages) {
        this.totalPages.set(totalpages);
    }

    public String getTotalsize() {
        return totalSize.get();
    }

    public StringProperty totalsizeProperty() {
        return totalSize;
    }

    public void setTotalsize(String totalsize) {
        this.totalSize.set(totalsize);
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

    public boolean getIsdisabled() {
        return isDisabled.get();
    }

    public BooleanProperty isdisabledProperty() {
        return isDisabled;
    }

    public void setIsdisabled(boolean isdisabled) {
        this.isDisabled.set(isdisabled);
    }


    public String getIndexOwner() {
        return indexOwner.get();
    }

    public StringProperty indexOwnerProperty() {
        return indexOwner;
    }

    public void setIndexOwner(String indexOwner) {
        this.indexOwner.set(indexOwner);
    }

    public String getTableName() {
        return tableName.get();
    }

    public StringProperty tableNameProperty() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName.set(tableName);
    }

    public String getTableOwner() {
        return tableOwner.get();
    }

    public StringProperty tableOwnerProperty() {
        return tableOwner;
    }

    public void setTableOwner(String tableOwner) {
        this.tableOwner.set(tableOwner);
    }

    public String getIndexType() {
        return indexType.get();
    }

    public StringProperty indexTypeProperty() {
        return indexType;
    }

    public void setIndexType(String indexType) {
        this.indexType.set(indexType);
    }

    public String getIndexCluster() {
        return indexCluster.get();
    }

    public StringProperty indexClusterProperty() {
        return indexCluster;
    }

    public void setIndexCluster(String indexCluster) {
        this.indexCluster.set(indexCluster);
    }

    public String getIndexCols() {
        return indexCols.get();
    }

    public StringProperty indexColsProperty() {
        return indexCols;
    }

    public void setIndexCols(String indexCols) {
        this.indexCols.set(indexCols);
    }

    public String toString(){
        return "IndexName: " + this.getName();
    }

}
