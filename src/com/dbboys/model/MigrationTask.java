package com.dbboys.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 数据迁移任务，存于 t_migration_task，在迁移任务树中显示。
 * 命名/文档约定与 {@link SshConnect} 一致。
 *
 * <pre>
 * t_migration_task:
 *   c_id              INTEGER PRIMARY KEY AUTOINCREMENT  -- 任务ID
 *   c_parentid        INTEGER DEFAULT 0                   -- 所属文件夹ID（t_migration_folder.c_id）
 *   c_name            VARCHAR(100)                        -- 任务名称
 *   c_source_id       INTEGER                             -- 源连接ID（t_connect.c_id）
 *   c_target_id       INTEGER                             -- 目标连接ID（t_connect.c_id）
 *   c_target_database VARCHAR(200)                        -- 目标库（按目标平台目录模型解释）
 *   c_target_schema   VARCHAR(200)                        -- 目标模式
 *   c_migrate_ddl     INT                                 -- 迁移结构 1/0
 *   c_migrate_data    INT                                 -- 迁移数据 1/0
 *   c_overwrite       INT                                 -- 覆盖目标已存在对象 1/0
 *   c_objects         TEXT                                -- 迁移对象 JSON 数组（见 MigrationObjectRef）
 *   c_info            VARCHAR(3200)                       -- 备注
 * </pre>
 */
public class MigrationTask extends TreeData {

    /** 运行状态（瞬时不落库）。 */
    public enum RunState { IDLE, RUNNING }

    /** 最近一次运行结果（瞬时不落库）：NONE=未运行过。 */
    public enum RunResult { NONE, SUCCESS, FAILED }

    private final IntegerProperty id = new SimpleIntegerProperty();
    private final IntegerProperty parentId = new SimpleIntegerProperty(0);
    private final IntegerProperty sourceId = new SimpleIntegerProperty();
    private final IntegerProperty targetId = new SimpleIntegerProperty();
    private final StringProperty targetDatabase = new SimpleStringProperty();
    private final StringProperty targetSchema = new SimpleStringProperty();
    private final BooleanProperty migrateDdl = new SimpleBooleanProperty(true);
    private final BooleanProperty migrateData = new SimpleBooleanProperty(true);
    private final BooleanProperty overwrite = new SimpleBooleanProperty(false);
    private final StringProperty objectsJson = new SimpleStringProperty("[]");
    private final StringProperty mappingsJson = new SimpleStringProperty("{}");
    private final StringProperty info = new SimpleStringProperty();

    // ---- 瞬时运行状态（不持久化；仅在 FX 线程读写 UI 相关属性） ----
    private final ObjectProperty<RunState> runState = new SimpleObjectProperty<>(RunState.IDLE);
    private final ObjectProperty<RunResult> lastRunResult = new SimpleObjectProperty<>(RunResult.NONE);
    private final List<String> lastRunLog = new CopyOnWriteArrayList<>();
    private final javafx.collections.ObservableList<MigrationRunItem> runItems =
            javafx.collections.FXCollections.observableArrayList();
    private transient javafx.concurrent.Task<?> runningTask;

    public MigrationTask() {}

    public MigrationTask(String name) {
        super(name);
    }

    // --- id ---
    public int getId() { return id.get(); }
    public IntegerProperty idProperty() { return id; }
    public void setId(int id) { this.id.set(id); }

    // --- parentId ---
    public int getParentId() { return parentId.get(); }
    public IntegerProperty parentIdProperty() { return parentId; }
    public void setParentId(int parentId) { this.parentId.set(parentId); }

    // --- sourceId ---
    public int getSourceId() { return sourceId.get(); }
    public IntegerProperty sourceIdProperty() { return sourceId; }
    public void setSourceId(int sourceId) { this.sourceId.set(sourceId); }

    // --- targetId ---
    public int getTargetId() { return targetId.get(); }
    public IntegerProperty targetIdProperty() { return targetId; }
    public void setTargetId(int targetId) { this.targetId.set(targetId); }

    // --- targetDatabase ---
    public String getTargetDatabase() { return targetDatabase.get(); }
    public StringProperty targetDatabaseProperty() { return targetDatabase; }
    public void setTargetDatabase(String targetDatabase) { this.targetDatabase.set(targetDatabase); }

    // --- targetSchema ---
    public String getTargetSchema() { return targetSchema.get(); }
    public StringProperty targetSchemaProperty() { return targetSchema; }
    public void setTargetSchema(String targetSchema) { this.targetSchema.set(targetSchema); }

    // --- migrateDdl ---
    public boolean isMigrateDdl() { return migrateDdl.get(); }
    public BooleanProperty migrateDdlProperty() { return migrateDdl; }
    public void setMigrateDdl(boolean migrateDdl) { this.migrateDdl.set(migrateDdl); }

    // --- migrateData ---
    public boolean isMigrateData() { return migrateData.get(); }
    public BooleanProperty migrateDataProperty() { return migrateData; }
    public void setMigrateData(boolean migrateData) { this.migrateData.set(migrateData); }

    // --- overwrite ---
    public boolean isOverwrite() { return overwrite.get(); }
    public BooleanProperty overwriteProperty() { return overwrite; }
    public void setOverwrite(boolean overwrite) { this.overwrite.set(overwrite); }

    // --- objectsJson ---
    public String getObjectsJson() { return objectsJson.get(); }
    public StringProperty objectsJsonProperty() { return objectsJson; }
    public void setObjectsJson(String objectsJson) { this.objectsJson.set(objectsJson); }

    /** 解析后的迁移对象列表（解析失败/为空返回空列表）。 */
    public List<MigrationObjectRef> getObjectRefs() {
        return MigrationObjectRef.parseJsonArray(objectsJson.get());
    }

    public void setObjectRefs(List<MigrationObjectRef> refs) {
        setObjectsJson(MigrationObjectRef.toJsonArray(refs));
    }

    // --- mappingsJson ---
    public String getMappingsJson() { return mappingsJson.get(); }
    public StringProperty mappingsJsonProperty() { return mappingsJson; }
    public void setMappingsJson(String mappingsJson) { this.mappingsJson.set(mappingsJson); }

    /** 解析后的自定义数据映射（表名→TableMapping；解析失败/为空返回空 Map）。 */
    public java.util.Map<String, com.dbboys.service.migration.TableMapping> getMappings() {
        return com.dbboys.service.migration.TableMapping.fromJson(mappingsJson.get());
    }

    public void setMappings(java.util.Map<String, com.dbboys.service.migration.TableMapping> mappings) {
        setMappingsJson(com.dbboys.service.migration.TableMapping.toJson(mappings));
    }

    // --- info ---
    public String getInfo() { return info.get(); }
    public StringProperty infoProperty() { return info; }
    public void setInfo(String info) { this.info.set(info); }

    // --- 运行状态（瞬时） ---
    public RunState getRunState() { return runState.get(); }
    public ObjectProperty<RunState> runStateProperty() { return runState; }
    public void setRunState(RunState state) { this.runState.set(state); }
    public boolean isRunning() { return runState.get() == RunState.RUNNING; }

    /** 最近一次运行的日志行（仅内存，随会话结束丢失）。 */
    public List<String> getLastRunLog() { return lastRunLog; }

    /** 明细行列表（瞬时，FX 线程更新）：明细中央 tab 的 TableView 直接绑定。 */
    public javafx.collections.ObservableList<MigrationRunItem> getRunItems() { return runItems; }

    public javafx.concurrent.Task<?> getRunningTask() { return runningTask; }
    public void setRunningTask(javafx.concurrent.Task<?> runningTask) { this.runningTask = runningTask; }

    // --- 最近运行结果（瞬时） ---
    public RunResult getLastRunResult() { return lastRunResult.get(); }
    public ObjectProperty<RunResult> lastRunResultProperty() { return lastRunResult; }
    public void setLastRunResult(RunResult result) { this.lastRunResult.set(result); }

    @Override
    public String toString() {
        return getName();
    }
}
