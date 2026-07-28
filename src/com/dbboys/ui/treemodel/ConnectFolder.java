package com.dbboys.ui.treemodel;

import com.dbboys.model.TreeData;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

public class ConnectFolder extends TreeData{

    private IntegerProperty id=new SimpleIntegerProperty();
    private IntegerProperty expand=new SimpleIntegerProperty();
    public ConnectFolder() {}
    public ConnectFolder(String name) {
        super(name);
    }
    public int getId() {
        return id.get();
    }

    public IntegerProperty idProperty() {
        return id;
    }

    public void setId(int id) {
        this.id.set(id);
    }

    public int getExpand() {
        return expand.get();
    }

    public IntegerProperty expandProperty() {
        return expand;
    }

    public void setExpand(int expand) {
        this.expand.set(expand);
    }
}
