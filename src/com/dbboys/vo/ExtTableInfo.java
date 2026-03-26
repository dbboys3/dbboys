package com.dbboys.vo;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ExtTableInfo {
    private StringProperty tableName = new SimpleStringProperty();
    private StringProperty formatType = new SimpleStringProperty();         // FORMAT值，格式类型：D:定界符（delimited）（默认），F:固定长度（fixed），I:内部使用（informix/gbasedbt）
    private StringProperty codeSet = new SimpleStringProperty();            // ? 定界符类型 ?
    private StringProperty recordDelimiter = new SimpleStringProperty();    // RECORDEND值，记录分隔符    默认  "\n"
    private StringProperty fieldDelimiter = new SimpleStringProperty();     // DELIMITER值，字段分隔符    默认  "|"
    private StringProperty dateFormat = new SimpleStringProperty();         // DBDATE值
    private StringProperty moneyFormat = new SimpleStringProperty();        // DBMONEY值，货币格式
    private IntegerProperty maxErrors = new SimpleIntegerProperty();        // MAXERRORS值，
    private StringProperty rejectFile = new SimpleStringProperty();         // REJECTFILE值，拒绝文件类型
    private IntegerProperty flags = new SimpleIntegerProperty();            // 0：Escape off; 2: Escape on; (默认) 4: DELUXE; 8: Express，可0/2 + 4/8
    private IntegerProperty numDfiles = new SimpleIntegerProperty();        // 指明外部存储定义

    public String getTableName() {
        return tableName.get();
    }

    public StringProperty tableNameProperty() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName.set(tableName);
    }

    public String getFormatType() {
        return formatType.get();
    }

    public StringProperty formatTypeProperty() {
        return formatType;
    }

    public void setFormatType(String formatType) {
        this.formatType.set(formatType);
    }

    public String getCodeSet() {
        return codeSet.get();
    }

    public StringProperty codeSetProperty() {
        return codeSet;
    }

    public void setCodeSet(String codeSet) {
        this.codeSet.set(codeSet);
    }

    public String getRecordDelimiter() {
        return recordDelimiter.get();
    }

    public StringProperty recordDelimiterProperty() {
        return recordDelimiter;
    }

    public void setRecordDelimiter(String recordDelimiter) {
        this.recordDelimiter.set(recordDelimiter);
    }

    public String getFieldDelimiter() {
        return fieldDelimiter.get();
    }

    public StringProperty fieldDelimiterProperty() {
        return fieldDelimiter;
    }

    public void setFieldDelimiter(String fieldDelimiter) {
        this.fieldDelimiter.set(fieldDelimiter);
    }

    public String getDateFormat() {
        return dateFormat.get();
    }

    public StringProperty dateFormatProperty() {
        return dateFormat;
    }

    public void setDateFormat(String dateFormat) {
        this.dateFormat.set(dateFormat);
    }

    public String getMoneyFormat() {
        return moneyFormat.get();
    }

    public StringProperty moneyFormatProperty() {
        return moneyFormat;
    }

    public void setMoneyFormat(String moneyFormat) {
        this.moneyFormat.set(moneyFormat);
    }

    public int getMaxErrors() {
        return maxErrors.get();
    }

    public IntegerProperty maxErrorsProperty() {
        return maxErrors;
    }

    public void setMaxErrors(int maxErrors) {
        this.maxErrors.set(maxErrors);
    }

    public String getRejectFile() {
        return rejectFile.get();
    }

    public StringProperty rejectFileProperty() {
        return rejectFile;
    }

    public void setRejectFile(String rejectFile) {
        this.rejectFile.set(rejectFile);
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

    public int getNumDfiles() {
        return numDfiles.get();
    }

    public IntegerProperty numDfilesProperty() {
        return numDfiles;
    }

    public void setNumDfiles(int numDfiles) {
        this.numDfiles.set(numDfiles);
    }

    @Override
    public String toString(){
        return "TableName: " + this.tableName.get();
    }
}

