package com.dbboys.model;

/**
 * 迁移任务一次运行中单个对象的详细执行结果（持久化在 t_migration_task_run_item）。
 * 用于重启后恢复任务明细表的上次执行状态。
 *
 * <pre>
 * t_migration_task_run_item:
 *   c_id         INTEGER PRIMARY KEY AUTOINCREMENT  -- 记录ID
 *   c_task_id    INTEGER                             -- 任务ID（每次运行整批替换）
 *   c_kind       VARCHAR(20)                         -- 对象类型（MigrationObjectRef.Kind）
 *   c_name       VARCHAR(500)                        -- 对象名
 *   c_status     VARCHAR(20)                         -- PENDING/RUNNING/SUCCESS/FAILED
 *   c_start_time VARCHAR(20)                         -- 开始时间
 *   c_end_time   VARCHAR(20)                         -- 结束时间
 *   c_rows       INTEGER                             -- 复制行数
 *   c_error      VARCHAR(3200)                       -- 错误信息
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
    private long rows;
    private String error = "";

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

    public long getRows() { return rows; }
    public void setRows(long rows) { this.rows = rows; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
