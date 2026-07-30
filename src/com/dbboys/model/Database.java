package com.dbboys.model;

/**
 * Represents a database in the metadata tree.
 * <p>
 * The old {@code Catalog} class has been renamed to {@code Database};
 * the three-level tree model (DATABASE_SCHEMA) uses {@link Schema}
 * for the child schema nodes under each database.  {@code Database} and
 * {@link Schema} are sibling types sharing the db* property set through
 * their common supertype {@link CatalogNode}.
 */
public class Database extends CatalogNode {

    public Database() {}

    public Database(String name) {
        super(name);
    }
}
