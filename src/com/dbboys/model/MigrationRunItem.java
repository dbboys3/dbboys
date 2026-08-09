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

    public enum Status { PENDING, RUNNING, SUCCESS, SKIPPED, FAILED }

    private final ObjectProperty<MigrationObjectRef.Kind> kind = new SimpleObjectProperty<>();
    private final StringProperty name = new SimpleStringProperty();
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.PENDING);
    private final StringProperty startTime = new SimpleStringProperty("");
    private final StringProperty endTime = new SimpleStringProperty("");
    private final LongProperty rows = new SimpleLongProperty(0);
    private final StringProperty errorMessage = new SimpleStringProperty("");

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

    // --- errorMessage ---
    public String getErrorMessage() { return errorMessage.get(); }
    public StringProperty errorMessageProperty() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage.set(errorMessage); }
}
