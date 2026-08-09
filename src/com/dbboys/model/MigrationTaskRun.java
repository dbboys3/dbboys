package com.dbboys.model;

/**
 * 迁移任务的一次运行记录（持久化在 t_migration_task_run），
 * 用于重启后恢复任务最近运行状态/查看历史日志。
 *
 * <pre>
 * t_migration_task_run:
 *   c_id            INTEGER PRIMARY KEY AUTOINCREMENT  -- 记录ID
 *   c_task_id       INTEGER                             -- 任务ID（t_migration_task.c_id）
 *   c_start_time    VARCHAR(20)                         -- 开始时间 yyyy-MM-dd HH:mm:ss
 *   c_end_time      VARCHAR(20)                         -- 结束时间
 *   c_status        VARCHAR(20)                         -- SUCCESS / FAILED / CANCELLED
 *   c_success_count INT                                 -- 成功对象数
 *   c_skipped_count INT                                 -- 跳过对象数
 *   c_failed_count  INT                                 -- 失败对象数
 *   c_log           TEXT                                -- 运行日志（换行分隔）
 * </pre>
 */
public class MigrationTaskRun {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private int id;
    private int taskId;
    private String startTime = "";
    private String endTime = "";
    private String status = "";
    private int successCount;
    private int skippedCount;
    private int failedCount;
    private String log = "";

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getTaskId() { return taskId; }
    public void setTaskId(int taskId) { this.taskId = taskId; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public String getLog() { return log; }
    public void setLog(String log) { this.log = log; }
}
