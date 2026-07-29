package com.dbboys.model;

/**
 * A schema inside a database — used in the DATABASE_SCHEMA three-level tree model.
 * <p>
 * {@code parentDb} records the owning database name.  Use {@code instanceof Schema}
 * schema nodes from database nodes.  When non-blank, the tree renders the node as a
 * schema and the SQL-editor combo box shows {@code "schema@database"}.
 */
public class Schema extends Database {

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
