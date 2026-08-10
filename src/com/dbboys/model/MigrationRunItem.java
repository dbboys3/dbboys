package com.dbboys.model;

import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * 迁移任务明细行（瞬时不落库）：一个待迁移对象在一次运行中的状态记录，
 * 供任务明细中央 tab 的 TableView 直接绑定。
 */
public class MigrationRunItem {

    public enum Status { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED }

    private final ObjectProperty<MigrationObjectRef.Kind> kind = new SimpleObjectProperty<>();
    private final StringProperty name = new SimpleStringProperty();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.PENDING);
    private final StringProperty startTime = new SimpleStringProperty("");
    private final StringProperty endTime = new SimpleStringProperty("");
    /** 行数=源表行数（-1 未知，复制开始时统计）；由 runner/明细 tab 在 FX 线程维护。 */
    private final LongProperty rows = new SimpleLongProperty(-1);
    /** 迁移行数=实际复制行数（-1 未知/未复制）；运行中每秒刷新，完成时写终值。 */
    private final LongProperty migratedRows = new SimpleLongProperty(-1);
    private final StringProperty errorMessage = new SimpleStringProperty("");
    /** 实时源表行数（工作线程写、FX 每秒读；-1 未知）。 */
    private volatile long sourceRowsLive = -1;
    /** 实时已复制行数（工作线程写、FX 每秒读；-1 未知）。 */
    private volatile long copiedRowsLive = -1;

    public MigrationRunItem(MigrationObjectRef.Kind kind, String name) {
        this.kind.set(kind);
        this.name.set(name);
    }

    // --- kind ---
    public MigrationObjectRef.Kind getKind() { return kind.get(); }
    public ObjectProperty<MigrationObjectRef.Kind> kindProperty() { return kind; }
    public void setKind(MigrationObjectRef.Kind kind) { this.kind.set(kind); }

    // --- name ---
    public String getName() { return name.get(); }
    public StringProperty nameProperty() { return name; }
    public void setName(String name) { this.name.set(name); }

    // --- status ---
    public Status getStatus() { return status.get(); }
    public ObjectProperty<Status> statusProperty() { return status; }
    public void setStatus(Status status) { this.status.set(status); }

    // --- startTime ---
    public String getStartTime() { return startTime.get(); }
    public StringProperty startTimeProperty() { return startTime; }
    public void setStartTime(String startTime) { this.startTime.set(startTime); }

    // --- endTime ---
    public String getEndTime() { return endTime.get(); }
    public StringProperty endTimeProperty() { return endTime; }
    public void setEndTime(String endTime) { this.endTime.set(endTime); }

    // --- rows ---
    public long getRows() { return rows.get(); }
    public LongProperty rowsProperty() { return rows; }
    public void setRows(long rows) { this.rows.set(rows); }

    // --- migratedRows ---
    public long getMigratedRows() { return migratedRows.get(); }
    public LongProperty migratedRowsProperty() { return migratedRows; }
    public void setMigratedRows(long migratedRows) { this.migratedRows.set(migratedRows); }

    // --- 实时进度（volatile 字段，工作线程写、FX 线程读） ---
    public long getSourceRowsLive() { return sourceRowsLive; }
    public void setSourceRowsLive(long sourceRowsLive) { this.sourceRowsLive = sourceRowsLive; }
    public long getCopiedRowsLive() { return copiedRowsLive; }
    public void setCopiedRowsLive(long copiedRowsLive) { this.copiedRowsLive = copiedRowsLive; }

    // --- errorMessage ---
    public String getErrorMessage() { return errorMessage.get(); }
    public StringProperty errorMessageProperty() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage.set(errorMessage); }

    // --- speed（复制速度展示串，如 "1,234 行/秒"；由 runner 计算写入） ---
    private final StringProperty speed = new SimpleStringProperty("");
    public String getSpeed() { return speed.get(); }
    public StringProperty speedProperty() { return speed; }
    public void setSpeed(String speed) { this.speed.set(speed); }

    // --- errorSql（出错时正在执行的 SQL，瞬时；双击错误列弹出详情用） ---
    private final StringProperty errorSql = new SimpleStringProperty("");
    public String getErrorSql() { return errorSql.get(); }
    public StringProperty errorSqlProperty() { return errorSql; }
    public void setErrorSql(String errorSql) { this.errorSql.set(errorSql); }

    // --- 起止时刻毫秒（瞬时不落库；速度计算用，0=未知） ---
    private transient long startMillis;
    private transient long endMillis;
    public long getStartMillis() { return startMillis; }
    public void setStartMillis(long startMillis) { this.startMillis = startMillis; }
    public long getEndMillis() { return endMillis; }
    public void setEndMillis(long endMillis) { this.endMillis = endMillis; }
}
