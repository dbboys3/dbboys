package com.dbboys.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Common supertype for catalog-level tree nodes ({@link Database} and {@link Schema}).
 * <p>
 * Holds the shared db* property set that both node kinds expose in the tree
 * (size, owner, locale, log mode, ...).  Use {@code instanceof CatalogNode}
 * where a code path applies to database and schema nodes alike; use the concrete
 * types where the behaviour differs (e.g. context menus).
 */
public abstract class CatalogNode extends TreeData {
    private StringProperty dbCreated = new SimpleStringProperty();
    private StringProperty dbLocale = new SimpleStringProperty();
    private StringProperty dbOwner = new SimpleStringProperty();
    private StringProperty dbLog = new SimpleStringProperty();
    private StringProperty dbUseGLU = new SimpleStringProperty();
    private StringProperty dbSpace = new SimpleStringProperty();
    private StringProperty dbSize = new SimpleStringProperty();

    public CatalogNode() {}

    public CatalogNode(String name) {
        super(name);
    }

    // ---- accessors ----

    public String getDbCreated() { return dbCreated.get(); }
    public StringProperty dbCreatedProperty() { return dbCreated; }
    public void setDbCreated(String dbCreated) { this.dbCreated.set(dbCreated); }

    public String getDbLocale() { return dbLocale.get(); }
    public StringProperty dbLocaleProperty() { return dbLocale; }
    public void setDbLocale(String dbLocale) { this.dbLocale.set(dbLocale); }

    public String getDbOwner() { return dbOwner.get(); }
    public StringProperty dbOwnerProperty() { return dbOwner; }
    public void setDbOwner(String dbOwner) { this.dbOwner.set(dbOwner); }

    public String getDbLog() { return dbLog.get(); }
    public StringProperty dbLogProperty() { return dbLog; }
    public void setDbLog(String dbLog) { this.dbLog.set(dbLog); }

    public String getDbUseGLU() { return dbUseGLU.get(); }
    public StringProperty dbUseGLUProperty() { return dbUseGLU; }
    public void setDbUseGLU(String dbUseGLU) { this.dbUseGLU.set(dbUseGLU); }

    public String getDbSpace() { return dbSpace.get(); }
    public StringProperty dbSpaceProperty() { return dbSpace; }
    public void setDbSpace(String dbSpace) { this.dbSpace.set(dbSpace); }

    public String getDbSize() { return dbSize.get(); }
    public StringProperty dbSizeProperty() { return dbSize; }
    public void setDbSize(String dbSize) { this.dbSize.set(dbSize); }
}
