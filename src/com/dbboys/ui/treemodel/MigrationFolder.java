package com.dbboys.ui.treemodel;

import com.dbboys.model.TreeData;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * 数据迁移任务文件夹（分类），同 {@link SshFolder}。
 * 持久化在 t_migration_folder。
 */
public class MigrationFolder extends TreeData {

    private final IntegerProperty id = new SimpleIntegerProperty();
    private final IntegerProperty expand = new SimpleIntegerProperty();

    public MigrationFolder() {}

    public MigrationFolder(String name) {
        super(name);
    }

    public int getId() { return id.get(); }
    public IntegerProperty idProperty() { return id; }
    public void setId(int id) { this.id.set(id); }

    public int getExpand() { return expand.get(); }
    public IntegerProperty expandProperty() { return expand; }
    public void setExpand(int expand) { this.expand.set(expand); }
}
