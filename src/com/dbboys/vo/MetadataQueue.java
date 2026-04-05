package com.dbboys.vo;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** Oracle Advanced Queuing {@code ALL_QUEUES} row for metadata tree. */
public class MetadataQueue extends TreeData {

    private final StringProperty database = new SimpleStringProperty();
    private final StringProperty owner = new SimpleStringProperty();

    public MetadataQueue(String name) {
        super(name);
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

    public String getOwner() {
        return owner.get();
    }

    public StringProperty ownerProperty() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner.set(owner);
    }
}
