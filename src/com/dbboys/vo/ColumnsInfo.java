package com.dbboys.vo;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ColumnsInfo {
    private IntegerProperty colNo;
    private StringProperty colName;
    private StringProperty colType;
    private IntegerProperty colLength;
    private IntegerProperty typeP;
    private IntegerProperty typeS;
    private BooleanProperty isNullable;
    private BooleanProperty isPK;
    private StringProperty colDefType;
    private StringProperty colDef;
    private StringProperty colComm;
    private BooleanProperty isAutoincrement;

    public ColumnsInfo() {
        this.colNo = new SimpleIntegerProperty();
        this.colName = new SimpleStringProperty();
        this.colType = new SimpleStringProperty();
        this.colLength = new SimpleIntegerProperty();
        this.typeP = new SimpleIntegerProperty();
        this.typeS = new SimpleIntegerProperty();
        this.isNullable = new SimpleBooleanProperty();
        this.isPK = new SimpleBooleanProperty();
        this.colDefType = new SimpleStringProperty();
        this.colDef = new SimpleStringProperty();
        this.colComm = new SimpleStringProperty();
        this.isAutoincrement = new SimpleBooleanProperty();
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

    public String getColName() {
        return colName.get();
    }

    public StringProperty colNameProperty() {
        return colName;
    }

    public void setColName(String colName) {
        this.colName.set(colName);
    }

    public String getColType() {
        return colType.get();
    }

    public StringProperty colTypeProperty() {
        return colType;
    }

    public void setColType(String colType) {
        this.colType.set(colType);
    }

    public int getColLength() {
        return colLength.get();
    }

    public IntegerProperty colLengthProperty() {
        return colLength;
    }

    public void setColLength(int colLength) {
        this.colLength.set(colLength);
    }

    /**
     * 设置TypeP和TypeS
     * @param colLength
     * @param colType
     */
    public void setColTypePS(int colLength,String colType,int dbVersion){
        // this.colLength.set(colLength);
        this.typeP.set(getPrecision(colType,colLength,dbVersion));
        if(("DECIMAL".equals(colType) || "MONEY".equals(colType)) && colLength % 256 != 255){
            this.typeS.set(colLength % 256);
        } else if("VARCHAR".equals(colType) || "NVARCHAR".equals(colType) || 
                  "VARCHAR2".equals(colType) || "NVARCHAR2".equals(colType)) {
            this.typeS.set(colLength/65536);
        }
    }

    public int getTypeP() {
        return typeP.get();
    }

    public IntegerProperty typePProperty() {
        return typeP;
    }

    public void setTypeP(int typeP) {
        this.typeP.set(typeP);
    }

    public int getTypeS() {
        return typeS.get();
    }

    public IntegerProperty typeSProperty() {
        return typeS;
    }

    public void setTypeS(int typeS) {
        this.typeS.set(typeS);
    }

    public boolean isIsNullable() {
        return isNullable.get();
    }

    public BooleanProperty isNullableProperty() {
        return isNullable;
    }

    public void setIsNullable(boolean isNullable) {
        this.isNullable.set(isNullable);
    }

    public boolean isIsPK() {
        return isPK.get();
    }

    public BooleanProperty isPKProperty() {
        return isPK;
    }

    public void setIsPK(boolean isPK) {
        this.isPK.set(isPK);
    }

    public String getColDefType() {
        return colDefType.get();
    }

    public StringProperty colDefTypeProperty() {
        return colDefType;
    }

    public void setColDefType(String colDefType) {
        this.colDefType.set(colDefType);
    }

    public String getColDef() {
        return colDef.get();
    }

    public StringProperty colDefProperty() {
        return colDef;
    }

    public void setColDef(String colDef) {
        this.colDef.set(colDef);
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

    public boolean isIsAutoincrement() {
        return isAutoincrement.get();
    }

    public BooleanProperty isAutoincrementProperty() {
        return isAutoincrement;
    }

    public void setIsAutoincrement(boolean isAutoincrement) {
        this.isAutoincrement.set(isAutoincrement);
    }

    @Override
    public String toString() {
        return "ColNo: " + this.colNo.get() + "\n" +
                "ColName: " + this.colName.get() + "\n" +
                "ColType: " + this.colType.get() + "\n" +
                "ColLength: " + this.colLength.get() + "\n" +
                "TypeP: " + this.typeP.get() + "\n" +
                "TypeS: " + this.typeS.get() + "\n" +
                "isNullable: " + this.isNullable.get() + "\n" +
                "isPK: " + this.isPK.get() + "\n" +
                "ColDefType: " + this.colDefType.get() + "\n" +
                "ColDef: " + this.colDef.get() + "\n" +
                "ColComm: " + this.colComm.get() + "\n" +
                "isAutoincrement: " + this.isAutoincrement.get() + "\n\n";
    }

    /**
     * 获取Precision。。  需补充及测试
     * @param coltype
     * @param collength
     * @return
     */
    private static int getPrecision(String coltype, int collength, int dbver){
        int myp = 0;
        if ("DECIMAL".equals(coltype) || "MONEY".equals(coltype)) { myp=collength/256; }
        else if("FLOAT".equals(coltype) || "SMALLFLOAT".equals(coltype)) {  myp=2; }
        else if("VARCHAR".equals(coltype) || "NVARCHAR".equals(coltype) || "VARCHAR2".equals(coltype) ||
                "NVARCHAR".equals(coltype) || "LVARCHAR".equals(coltype)) { 
            if (dbver == 3) {
                if(collength > 0){
                    myp = collength%65536;
                } else {
                    myp = Long.valueOf((collength + 4294967296L) % 65536).intValue();
                }
            } else {
                if(collength > 0){
                    myp=collength%256;
                } else {
                    myp = (collength + 65536) % 256;
                }
            }
        }
        else {myp=collength;}
        return myp;
    }
}