package com.dbboys.ui.treemodel;

import com.dbboys.model.TreeData;

/**
 * Schema folder node — used as the top-level container for {@code DATABASE_SCHEMA}
 * model connections (PostgreSQL etc.).  Differentiated from {@link DatabaseFolder}
 * so that the tree renders the correct icon and context menu without checking
 * the platform's catalog model at runtime.
 */
public class SchemaFolder extends TreeData {

    public SchemaFolder() {}

    public SchemaFolder(String name) {
        super(name);
    }
}
