package com.dbboys.model;

/**
 * A schema node in the metadata tree — used both in the DATABASE_SCHEMA three-level
 * tree model (schema under a database) and in the SCHEMA two-level model
 * (Oracle/Dameng, schema directly under the schema folder).
 * <p>
 * {@code Schema} is independent from {@link Database}; both share the db* property
 * set through their common supertype {@link CatalogNode}.  Use
 * {@code instanceof Schema} to tell schema nodes from database nodes.
 * {@code parentDb} records the owning database name (three-level model only);
 * when non-blank, the SQL-editor combo box shows {@code "schema@database"}.
 */
public class Schema extends CatalogNode {

    /** Name of the database this schema belongs to ({@code null} / blank for database nodes). */
    private String parentDb;

    public Schema() {}

    public Schema(String name) {
        super(name);
    }

    public Schema(String name, String parentDb) {
        super(name);
        this.parentDb = parentDb;
    }

    // ---- accessors ----

    public String getParentDb() {
        return parentDb;
    }

    public void setParentDb(String parentDb) {
        this.parentDb = parentDb;
    }

    /**
     * Schema nodes show {@code schema@database}; plain database nodes show just the name.
     */
    @Override
    public String toString() {
        String db = getParentDb();
        if (db != null && !db.isBlank()) {
            return getName() + "@" + db;
        }
        return getName();
    }
}
