package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class Table extends TreeData{

    //created by liaosnet
    private StringProperty tableCatalog = new SimpleStringProperty();       // catalog
    private StringProperty tableOwner = new SimpleStringProperty();         // 属主
    private StringProperty lockType = new SimpleStringProperty();           // 锁类型 P 页锁， R 行锁， B 页锁和行锁
    private IntegerProperty firstExtSize = new SimpleIntegerProperty();     // 首区段大小
    private IntegerProperty nextExtSize = new SimpleIntegerProperty();      // 下一区段大小
    private StringProperty tableComm = new SimpleStringProperty();          // 表注释
    private StringProperty tableTypeCode = new SimpleStringProperty();      // 表类型：T 表, E 外部表, V 视图, Q 序列, P 专用同义词, S 公共同义词
    @Deprecated
    private StringProperty tableSqlMode = new SimpleStringProperty();       // 建表模式：Oracle, GBase, MySql
    /*
     * 表标识(smallint两字节)：
     * 1, 最高位开始第一位是1时（位与16384值为16384时），SQLMODE=Oracle，
     * 2，最高位开始第二位是1时（位与8192值为8192时），事务级（commit delete）；为0时则为 会话级（COMMIT PRESERVE）
     * 3，最高位开始第三位是1时（位与4096值为4096时），全局临时表；为0时则为默认的永久表
     */
    private IntegerProperty flags = new SimpleIntegerProperty();
    private IntegerProperty dbVersion = new SimpleIntegerProperty();

    // 用于导出全库表结构时区分，-- liaosnet 2026-03-02
    private IntegerProperty tableId = new SimpleIntegerProperty();

    //created by L3
    private StringProperty createTime = new SimpleStringProperty();
    private IntegerProperty isfragment = new SimpleIntegerProperty();
    private IntegerProperty extents = new SimpleIntegerProperty();
    private IntegerProperty nrows = new SimpleIntegerProperty();
    private IntegerProperty pagesize = new SimpleIntegerProperty();
    private IntegerProperty nptotal = new SimpleIntegerProperty();
    private StringProperty totalsize = new SimpleStringProperty();
    private IntegerProperty npdata = new SimpleIntegerProperty();
    private StringProperty usedsize = new SimpleStringProperty();

    public Table() {}

    public Table(String name) {
        super(name);
    }

    public String getTableCatalog() {
        return tableCatalog.get();
    }

    public StringProperty tableCatalogProperty() {
        return tableCatalog;
    }

    public void setTableCatalog(String tableCatalog) {
        this.tableCatalog.set(tableCatalog);
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

    public String getLockType() {
        return lockType.get();
    }

    public StringProperty lockTypeProperty() {
        return lockType;
    }

    public void setLockType(String lockType) {
        this.lockType.set(lockType);
    }

    public int getFirstExtSize() {
        return firstExtSize.get();
    }

    public IntegerProperty firstExtSizeProperty() {
        return firstExtSize;
    }

    public void setFirstExtSize(int firstExtSize) {
        this.firstExtSize.set(firstExtSize);
    }

    public int getNextExtSize() {
        return nextExtSize.get();
    }

    public IntegerProperty nextExtSizeProperty() {
        return nextExtSize;
    }

    public void setNextExtSize(int nextExtSize) {
        this.nextExtSize.set(nextExtSize);
    }

    public String getTableComm() {
        return tableComm.get();
    }

    public StringProperty tableCommProperty() {
        return tableComm;
    }

    public void setTableComm(String tableComm) {
        this.tableComm.set(tableComm);
    }

    public String getTableTypeCode() {
        return tableTypeCode.get();
    }

    public StringProperty tableTypeCodeProperty() {
        return tableTypeCode;
    }

    public void setTableTypeCode(String tableTypeCode) {
        this.tableTypeCode.set(tableTypeCode);
    }

    @Deprecated
    public String getTableSqlMode() {
        return tableSqlMode.get();
    }

    @Deprecated
    public StringProperty tableSqlModeProperty() {
        return tableSqlMode;
    }

    @Deprecated
    public void setTableSqlMode(String tableSqlMode) {
        this.tableSqlMode.set(tableSqlMode);
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

    public int getDbVersion() {
        return dbVersion.get();
    }

    public IntegerProperty dbVersionProperty() {
        return dbVersion;
    }

    public void setDbVersion(int dbVersion) {
        this.dbVersion.set(dbVersion);
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

    public String getCreateTime() {
        return createTime.get();
    }

    public StringProperty createTimeProperty() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime.set(createTime);
    }

    public int getIsfragment() {
        return isfragment.get();
    }

    public IntegerProperty isfragmentProperty() {
        return isfragment;
    }

    public void setIsfragment(int isfragment) {
        this.isfragment.set(isfragment);
    }

    public int getExtents() {
        return extents.get();
    }

    public IntegerProperty extentsProperty() {
        return extents;
    }

    public void setExtents(int extents) {
        this.extents.set(extents);
    }

    public int getNrows() {
        return nrows.get();
    }

    public IntegerProperty nrowsProperty() {
        return nrows;
    }

    public void setNrows(int nrows) {
        this.nrows.set(nrows);
    }

    public int getPagesize() {
        return pagesize.get();
    }

    public IntegerProperty pagesizeProperty() {
        return pagesize;
    }

    public void setPagesize(int pagesize) {
        this.pagesize.set(pagesize);
    }

    public int getNptotal() {
        return nptotal.get();
    }

    public IntegerProperty nptotalProperty() {
        return nptotal;
    }

    public void setNptotal(int nptotal) {
        this.nptotal.set(nptotal);
    }

    public String getTotalsize() {
        return totalsize.get();
    }

    public StringProperty totalsizeProperty() {
        return totalsize;
    }

    public void setTotalsize(String totalsize) {
        this.totalsize.set(totalsize);
    }

    public int getNpdata() {
        return npdata.get();
    }

    public IntegerProperty npdataProperty() {
        return npdata;
    }

    public void setNpdata(int npdata) {
        this.npdata.set(npdata);
    }

    public String getUsedsize() {
        return usedsize.get();
    }

    public StringProperty usedsizeProperty() {
        return usedsize;
    }

    public void setUsedsize(String usedsize) {
        this.usedsize.set(usedsize);
    }

    /**
     * 返回 SQLMODE
     * @return
     */
    public String getTableSqlModeFunc(){
        if ((this.flags.get() & 16384) == 16384) {
            return "Oracle";
        }
        return "GBase";
    }

    /**
     * 返回 全局临时表 标识
     * @return
     */
    public String getTableGlobalTemporary(){
        if ((this.flags.get() & 4096) == 4096) {
            return "GLOBAL TEMPORARY ";
        }
        return "";
    }

    /**
     * 返回 全局临时表 级别
     * @return
     */
    public String getTableGlobalTemporaryLevel(){
        if ((this.flags.get() & 8192) == 8192) {
            return "ON COMMIT DELETE ROWS";
        }
        return "ON COMMIT PRESERVE ROWS";
    }

    /**
     * 返回 裸表/外部表 标识
     * @return
     */
    public String getTableFlag(){
        if ((this.flags.get() & 16) == 16) {
            return "RAW ";
        } else if ((this.flags.get() & 32) == 32) {
            return "EXTERNAL ";
        }
        return "";
    }

    /**
     * 返回表锁类型
     * @return
     */
    public String getLockTypeFunc(){
        if("P".equals(this.lockType.get())){
            return "PAGE";
        } else if("B".equals(this.lockType.get())){
            return "PAGE,ROW";
        }
        return "ROW";
    }

    @Override
    public String toString(){
        return "Tablename: " + this.getName() + "\n" +
                "TableCatalog: " + this.tableCatalog.get() + "\n" +
                "TableOwner: " + this.tableOwner.get() + "\n" +
                "TableSqlMode: " + this.getTableSqlModeFunc();
    }

    /**
     * 设置表信息
     * @param TableName
     * @param TableCatalog
     * @param TableOwner
     * @param lockType
     * @param firstExtSize
     * @param nextExtSize
     * @param TableType
     * @param Flags
     * @param dbVersion
     * @param TableId
     */
    public void setTableInfo(String TableName,
                             String TableCatalog,
                             String TableOwner,
                             String lockType,
                             int firstExtSize,
                             int nextExtSize,
                             String TableType,
                             int Flags,
                             int dbVersion,
                             int TableId
    ){
        super.setName(TableName);
        this.tableCatalog.set(TableCatalog);
        this.tableOwner.set(TableOwner);
        this.lockType.set(lockType);
        this.firstExtSize.set(firstExtSize);
        this.nextExtSize.set(nextExtSize);
        this.tableTypeCode.set(TableType);
        this.flags.set(Flags);
        this.dbVersion.set(dbVersion);
        this.tableId.set(TableId);
    }
}
