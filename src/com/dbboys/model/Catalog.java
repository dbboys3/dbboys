package com.dbboys.model;

/**
 * @deprecated Use {@link Database} (and {@link Schema} for three-level tree model).
 * This class is kept as a subclass so existing {@code instanceof Catalog} checks
 * and {@code (Catalog)} casts continue to work during migration.
 *
 * <p>Use {@code instanceof Schema} to tell schema nodes apart from database nodes.
 */
public class Catalog extends Database {

    public Catalog() {}

    public Catalog(String name) {
        super(name);
    }
}
