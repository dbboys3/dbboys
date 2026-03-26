package com.dbboys.vo;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class CheckInfo {
    private StringProperty constrName = new SimpleStringProperty();     // check约束名
    private StringProperty checkText = new SimpleStringProperty();      // check约束内容

    public String getConstrName() {
        return constrName.get();
    }

    public StringProperty constrNameProperty() {
        return constrName;
    }

    public void setConstrName(String constrName) {
        this.constrName.set(constrName);
    }

    public String getCheckText() {
        return checkText.get();
    }

    public StringProperty checkTextProperty() {
        return checkText;
    }

    public void setCheckText(String checkText) {
        this.checkText.set(checkText);
    }

    @Override
    public String toString(){
        return "CheckName: " + this.constrName + "\n" +
                "CheckText: " + this.checkText;
    }
}

