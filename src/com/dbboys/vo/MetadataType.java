package com.dbboys.vo;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** Oracle {@code ALL_TYPES} / GBase {@code sysxtdtypes} row for metadata tree. */
public class MetadataType extends TreeData {

    private final StringProperty database = new SimpleStringProperty();
    private final StringProperty owner = new SimpleStringProperty();
    private final StringProperty typeKind = new SimpleStringProperty();

    public MetadataType(String name) {
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

    public String getTypeKind() {
        return typeKind.get();
    }

    public StringProperty typeKindProperty() {
        return typeKind;
    }

    public void setTypeKind(String typeKind) {
        this.typeKind.set(typeKind);
    }
}
