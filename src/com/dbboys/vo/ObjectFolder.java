package com.dbboys.vo;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ObjectFolder extends TreeData {

    private StringProperty description = new SimpleStringProperty();
    /** TreeDataLoader.ObjectFolderKind.name(); stable when optional folders change child index. */
    private final StringProperty kindTag = new SimpleStringProperty("");

    public ObjectFolder() {
    }

    public ObjectFolder(String name) {
        super(name);
    }

    public String getDescription() {
        return description.get();
    }

    public StringProperty descriptionProperty() {
        return description;
    }

    public void setDescription(String description) {
        this.description.set(description);
    }

    public String getKindTag() {
        return kindTag.get();
    }

    public StringProperty kindTagProperty() {
        return kindTag;
    }

    public void setKindTag(String kindTag) {
        this.kindTag.set(kindTag == null ? "" : kindTag);
    }
}
