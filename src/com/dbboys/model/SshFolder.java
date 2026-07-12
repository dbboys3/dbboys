package com.dbboys.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * SSH connection folder (classification), like {@link ConnectFolder}.
 * Persisted in t_ssh_folder.
 */
public class SshFolder extends TreeData {

    private IntegerProperty id = new SimpleIntegerProperty();
    private IntegerProperty expand = new SimpleIntegerProperty();

    public SshFolder() {}

    public SshFolder(String name) {
        super(name);
    }

    public int getId() { return id.get(); }
    public IntegerProperty idProperty() { return id; }
    public void setId(int id) { this.id.set(id); }

    public int getExpand() { return expand.get(); }
    public IntegerProperty expandProperty() { return expand; }
    public void setExpand(int expand) { this.expand.set(expand); }
}
