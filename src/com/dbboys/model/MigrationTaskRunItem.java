package com.dbboys.model;

/**
 * 迁移任务一次运行中单个对象的详细执行结果（持久化在 t_migration_task_run_item）。
 * 用于重启后恢复任务明细表的上次执行状态。
 *
 * <pre>
 * t_migration_task_run_item:
 *   c_id          INTEGER PRIMARY KEY AUTOINCREMENT  -- 记录ID
 *   c_task_id     INTEGER                             -- 任务ID（每次运行整批替换）
 *   c_kind        VARCHAR(20)                         -- 对象类型（MigrationObjectRef.Kind）
 *   c_name        VARCHAR(500)                        -- 对象名
 *   c_status      VARCHAR(20)                         -- PENDING/RUNNING/SUCCESS/FAILED/CANCELLED
 *   c_start_time  VARCHAR(20)                         -- 开始时间
 *   c_end_time    VARCHAR(20)                         -- 结束时间
 *   c_source_rows INTEGER                             -- 源表行数（-1 未知）
 *   c_target_rows INTEGER                             -- 迁移行数（实际复制到目标，-1 未知）
 *   c_speed       INTEGER                             -- 复制速度（行/秒，-1 未知）
 *   c_error       VARCHAR(3200)                       -- 错误号及错误信息
 *   c_checksum    VARCHAR(100)                        -- 数据校验结果（预留）
 *   c_info        VARCHAR(3200)                       -- 备注
 * </pre>
 */
public class MigrationTaskRunItem {

    private int id;
    private int taskId;
    private String kind = "";
    private String name = "";
    private String status = "";
    private String startTime = "";
    private String endTime = "";
    private long sourceRows = -1;
    private long targetRows = -1;
    private long speed = -1;
    private String error = "";
    private String checksum = "";
    private String info = "";

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getTaskId() { return taskId; }
    public void setTaskId(int taskId) { this.taskId = taskId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public long getSourceRows() { return sourceRows; }
    public void setSourceRows(long sourceRows) { this.sourceRows = sourceRows; }

    public long getTargetRows() { return targetRows; }
    public void setTargetRows(long targetRows) { this.targetRows = targetRows; }

    public long getSpeed() { return speed; }
    public void setSpeed(long speed) { this.speed = speed; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public String getInfo() { return info; }
    public void setInfo(String info) { this.info = info; }
}
