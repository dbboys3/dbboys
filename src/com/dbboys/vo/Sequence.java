package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.math.BigInteger;

public class Sequence extends TreeData {
    // created by liaosnet
    private StringProperty seqOwner = new SimpleStringProperty();
    private LongProperty startVal = new SimpleLongProperty();      // start value
    private LongProperty incVal = new SimpleLongProperty();        // increment value
    private LongProperty maxVal = new SimpleLongProperty();        // max value
    private LongProperty minVal = new SimpleLongProperty();        // min value
    private StringProperty isCycle = new SimpleStringProperty();   // 0 nocycle, 1 cycle
    private LongProperty cache = new SimpleLongProperty();         // 0 nocache
    private StringProperty isOrder = new SimpleStringProperty();   // 0 noorder, 1 order
    /*
     * 表标识(smallint两字节)：
     * 1, 最高位开始第一位是1时（位与16384值为16384时），SQLMODE=Oracle, MySQL无序列？
     */
    private IntegerProperty flags = new SimpleIntegerProperty();
    private StringProperty seqSqlMode = new SimpleStringProperty();

    // created by L3
    private StringProperty database = new SimpleStringProperty();
    private BigIntegerProperty nextVal = new BigIntegerProperty();
    private StringProperty created = new SimpleStringProperty();

    public Sequence(String name) {
        super(name);
    }

    /**
     * 返回序列的数据库模式
     */
    public String getSequenceSqlMode() {
        return this.seqSqlMode.get();
    }

    @Override
    public String toString() {
        return "SeqName: " + this.getName() + "\n";
    }

    public String getSeqSqlMode() {
        return seqSqlMode.get();
    }

    public StringProperty seqSqlModeProperty() {
        return seqSqlMode;
    }

    public void setSeqSqlMode(int seqSqlMode) {
        String tmpstr = "GBase";
        if ((seqSqlMode & 16384) == 16384) {
            tmpstr = "Oracle";
        }
        this.seqSqlMode.set(tmpstr);
    }

    public String getSeqOwner() {
        return seqOwner.get();
    }

    public StringProperty seqOwnerProperty() {
        return seqOwner;
    }

    public void setSeqOwner(String seqOwner) {
        this.seqOwner.set(seqOwner);
    }

    public long getStartVal() {
        return startVal.get();
    }

    public LongProperty startValProperty() {
        return startVal;
    }

    public void setStartVal(long startVal) {
        this.startVal.set(startVal);
    }

    public long getIncVal() {
        return incVal.get();
    }

    public LongProperty incValProperty() {
        return incVal;
    }

    public void setIncVal(long incVal) {
        this.incVal.set(incVal);
    }

    public long getMaxVal() {
        return maxVal.get();
    }

    public LongProperty maxValProperty() {
        return maxVal;
    }

    public void setMaxVal(long maxVal) {
        this.maxVal.set(maxVal);
    }

    public long getMinVal() {
        return minVal.get();
    }

    public LongProperty minValProperty() {
        return minVal;
    }

    public void setMinVal(long minVal) {
        this.minVal.set(minVal);
    }

    public String getIsCycle() {
        return isCycle.get();
    }

    public StringProperty isCycleProperty() {
        return isCycle;
    }

    public void setIsCycle(String isCycle) {
        this.isCycle.set(isCycle);
    }

    public String getIsOrder() {
        return isOrder.get();
    }

    public StringProperty isOrderProperty() {
        return isOrder;
    }

    public void setIsOrder(String isOrder) {
        this.isOrder.set(isOrder);
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

    public String getDatabase() {
        return database.get();
    }

    public StringProperty databaseProperty() {
        return database;
    }

    public void setDatabase(String database) {
        this.database.set(database);
    }

    // compatibility aliases used by current callers
    public BigInteger getMinValue() {
        return BigInteger.valueOf(minVal.get());
    }

    public LongProperty minValueProperty() {
        return minVal;
    }

    public void setMinValue(BigInteger minValue) {
        this.minVal.set(minValue == null ? 0L : minValue.longValue());
    }

    public BigInteger getMaxValue() {
        return BigInteger.valueOf(maxVal.get());
    }

    public LongProperty maxValueProperty() {
        return maxVal;
    }

    public void setMaxValue(BigInteger maxValue) {
        this.maxVal.set(maxValue == null ? 0L : maxValue.longValue());
    }

    public BigInteger getIncValue() {
        return BigInteger.valueOf(incVal.get());
    }

    public LongProperty incValueProperty() {
        return incVal;
    }

    public void setIncValue(BigInteger incValue) {
        this.incVal.set(incValue == null ? 0L : incValue.longValue());
    }

    public BigInteger getNextVal() {
        return nextVal.get();
    }

    public BigIntegerProperty nextValProperty() {
        return nextVal;
    }

    public void setNextVal(BigInteger nextVal) {
        this.nextVal.set(nextVal);
    }

    public long getCache() {
        return cache.get();
    }

    public LongProperty cacheProperty() {
        return cache;
    }

    public void setCache(long cache) {
        this.cache.set(cache);
    }

    public String getCreated() {
        return created.get();
    }

    public StringProperty createdProperty() {
        return created;
    }

    public void setCreated(String created) {
        this.created.set(created);
    }

    // compatibility aliases used by current callers
    public BigInteger getNextval() {
        return getNextVal();
    }

    public BigIntegerProperty nextvalProperty() {
        return nextValProperty();
    }

    public void setNextval(BigInteger nextval) {
        setNextVal(nextval);
    }
}
