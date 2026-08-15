package com.dbboys.model;

/**
 * 外键定义（数据迁移对象）：由 JDBC {@code DatabaseMetaData#getExportedKeys} 读取，
 * 用于跨库外键迁移时生成标准 {@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY} DDL。
 * 与方言私有 FK 模型（ForeignKeyInfo，Informix/GBase 打印表结构用）不同，本类是通用模型，
 * 只保留生成 DDL 所需的最小字段。
 */
public class ForeignKey extends TreeData {

    /** 宿主表（外键所在表）。 */
    private String tableName;
    /** 被引用表。 */
    private String refTableName;
    /** 外键列（逗号分隔，按 KEY_SEQ 有序）。 */
    private String columns;
    /** 被引用列（逗号分隔，与 columns 一一对应）。 */
    private String refColumns;
    /** ON DELETE 规则：CASCADE / SET NULL / SET DEFAULT / RESTRICT / NO ACTION。 */
    private String deleteRule;
    /** ON UPDATE 规则：取值同 deleteRule。 */
    private String updateRule;

    public ForeignKey() {}

    public ForeignKey(String name) {
        super(name);
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getRefTableName() { return refTableName; }
    public void setRefTableName(String refTableName) { this.refTableName = refTableName; }

    public String getColumns() { return columns; }
    public void setColumns(String columns) { this.columns = columns; }

    public String getRefColumns() { return refColumns; }
    public void setRefColumns(String refColumns) { this.refColumns = refColumns; }

    public String getDeleteRule() { return deleteRule; }
    public void setDeleteRule(String deleteRule) { this.deleteRule = deleteRule; }

    public String getUpdateRule() { return updateRule; }
    public void setUpdateRule(String updateRule) { this.updateRule = updateRule; }
}
