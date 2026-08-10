package com.dbboys.service.migration;

import com.dbboys.app.AppExecutor;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.MigrationObjectRef;
import com.dbboys.model.MigrationRunItem;
import com.dbboys.model.MigrationTask;
import com.dbboys.model.MigrationTaskRunItem;
import com.dbboys.model.TreeData;
import com.dbboys.service.BackgroundSqlService;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 迁移任务后台运行器：UI（迁移任务树）与 {@link TableMigrationService} 之间的静态薄层。
 * <p>
 * 启动流程：FX 线程校验连接 → 线程池展开通配 ref（独立复制 Connect 会话列对象名；
 * {@code needsExpansion()} 的整节点/类型级通配都展开）→ FX 线程按展开结果生成
 * PENDING 明细行（{@link MigrationTask#getRunItems()}，明细中央 tab 直接绑定）、
 * 组装 {@code MigrationRequest}（含任务自定义数据映射）、创建 Task 并提交
 * {@code BackgroundSqlService.backSqlExecutor}。运行状态/日志/明细行等 UI 相关属性均在 FX 线程更新。
 */
public final class MigrationTaskRunner {
    private static final Logger log = LogManager.getLogger(MigrationTaskRunner.class);
    /** 明细行开始/结束时间格式（带日期）。 */
    private static final DateTimeFormatter RUN_ITEM_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 运行记录开始/结束时间格式（持久化用）。 */
    private static final java.text.SimpleDateFormat RUN_TIME_FORMAT =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private MigrationTaskRunner() {}

    /** 启动任务；已在运行或连接缺失/通配展开失败时直接返回（后者弹错误提示）。 */
    public static void start(MigrationTask task) {
        if (task == null || task.isRunning()) {
            return;
        }
        Connect source = findConnect(task.getSourceId());
        Connect target = findConnect(task.getTargetId());
        if (source == null || target == null) {
            AlertUtil.CustomAlert(I18n.t("common.error", "Error"),
                    I18n.t("migration.error.connect_missing",
                            "The source or target connection of this task no longer exists"));
            return;
        }
        List<MigrationObjectRef> refs = task.getObjectRefs();
        if (refs.isEmpty()) {
            AlertUtil.CustomAlert(I18n.t("common.error", "Error"),
                    I18n.t("migration.error.no_objects", "Please select at least one object"));
            return;
        }
        // 通配展开放在线程池线程（要查源库元数据），完成后回 FX 线程提交
        AppExecutor.runAsync(() -> {
            List<MigrationObjectRef> expanded;
            try {
                expanded = expandWildcards(source, refs);
            } catch (Exception e) {
                log.warn("expand migration wildcards failed for task {}", task.getName(), e);
                String message = e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error", "Error"), message));
                return;
            }
            Platform.runLater(() -> submit(task, source, target, expanded));
        });
    }

    /** 取消正在运行的任务（未运行则空操作）。 */
    public static void stop(MigrationTask task) {
        if (task == null) {
            return;
        }
        javafx.concurrent.Task<?> runningTask = task.getRunningTask();
        if (runningTask != null) {
            runningTask.cancel();
        }
    }

    /**
     * 明细 tab 打开时调用：任务从未运行（runItems 为空）且未在运行时，
     * 优先从持久化的最近运行记录恢复逐对象明细；无记录时后台展开通配 ref
     * 用 PENDING 行预填 {@link MigrationTask#getRunItems()}（仅展示，不启动迁移）。
     */
    public static void prepareRunItems(MigrationTask task) {
        if (task == null || task.isRunning() || !task.getRunItems().isEmpty()) {
            return;
        }
        // 有历史运行记录时直接恢复上次逐对象结果
        List<MigrationTaskRunItem> latestItems = LocalDbRepository.getMigrationTaskRunItems(task.getId());
        if (!latestItems.isEmpty()) {
            List<MigrationRunItem> restored = new ArrayList<>(latestItems.size());
            for (MigrationTaskRunItem record : latestItems) {
                MigrationObjectRef.Kind kind;
                MigrationRunItem.Status status;
                try {
                    kind = MigrationObjectRef.Kind.valueOf(record.getKind());
                } catch (Exception e) {
                    kind = MigrationObjectRef.Kind.TABLE;
                }
                try {
                    status = MigrationRunItem.Status.valueOf(record.getStatus());
                } catch (Exception e) {
                    status = MigrationRunItem.Status.PENDING;
                }
                MigrationRunItem item = new MigrationRunItem(kind, record.getName());
                item.setStatus(status);
                item.setStartTime(record.getStartTime());
                item.setEndTime(record.getEndTime());
                // c_rows=已迁移行数；成功行的源表行数=迁移行数，其余源表行数未知（-1 显示空白）
                item.setMigratedRows(record.getRows());
                item.setRows(status == MigrationRunItem.Status.SUCCESS ? record.getRows() : -1);
                item.setErrorMessage(record.getError());
                // 起止时间回算毫秒，能算则补速度列（旧格式 HH:mm:ss 解析失败则不显示）
                long startMillis = parseRunItemTime(record.getStartTime());
                long endMillis = parseRunItemTime(record.getEndTime());
                item.setStartMillis(startMillis);
                item.setEndMillis(endMillis);
                item.setSpeed(computeSpeed(record.getRows(), startMillis, endMillis));
                restored.add(item);
            }
            task.getRunItems().setAll(restored);
            return;
        }
        Connect source = findConnect(task.getSourceId());
        if (source == null) {
            return;
        }
        List<MigrationObjectRef> refs = task.getObjectRefs();
        if (refs.isEmpty()) {
            return;
        }
        AppExecutor.runAsync(() -> {
            List<MigrationObjectRef> expanded;
            try {
                expanded = expandWildcards(source, refs);
            } catch (Exception e) {
                log.warn("prepare migration run items failed for task {}", task.getName(), e);
                return;
            }
            Platform.runLater(() -> {
                // 展开期间任务已被启动或已有明细行时，不覆盖
                if (task.isRunning() || !task.getRunItems().isEmpty()) {
                    return;
                }
                List<MigrationRunItem> pendingItems = new ArrayList<>(expanded.size());
                for (MigrationObjectRef ref : expanded) {
                    pendingItems.add(new MigrationRunItem(ref.kind(), ref.name()));
                }
                task.getRunItems().setAll(pendingItems);
            });
        });
    }

    public static boolean isRunning(MigrationTask task) {
        return task != null && task.isRunning();
    }

    private static Connect findConnect(int connectId) {
        for (Connect connect : LocalDbRepository.getConnectLeafs()) {
            if (connect != null && connect.getId() == connectId) {
                return connect;
            }
        }
        return null;
    }

    // ==================================================================
    // 通配展开（后台线程）
    // ==================================================================

    private static List<MigrationObjectRef> expandWildcards(Connect source,
                                                            List<MigrationObjectRef> refs) throws Exception {
        List<MigrationObjectRef> expanded = new ArrayList<>();
        for (MigrationObjectRef ref : refs) {
            if (ref == null) {
                continue;
            }
            if (!ref.needsExpansion()) {
                expanded.add(ref);
            } else {
                expanded.addAll(expandOne(source, ref));
            }
        }
        return expanded;
    }

    /**
     * 单条通配 ref → 该 catalog(/schema) 下匹配类型的全部对象（显式 ref）：
     * kind=ALL 展开全部支持类型，类型级通配只展开该类型。
     * 会话构建与对象类型支持度规则同任务编辑器（参照 MigrationDialogController）。
     */
    private static List<MigrationObjectRef> expandOne(Connect source,
                                                      MigrationObjectRef wildcard) throws Exception {
        Connect sessionConnect = new Connect(source);
        DatabasePlatform platform = PlatformResolvers.get().requirePlatform(sessionConnect);
        String catalog = wildcard.catalog();
        String schema = wildcard.schema();
        if (schema != null && !schema.isBlank()) {
            sessionConnect.setCatalog(catalog);
            sessionConnect.setSessionCatalog(schema);
        } else if (catalog != null && !catalog.isBlank()) {
            platform.connection().setSessionCatalog(sessionConnect, catalog);
        }
        String dbName = schema != null && !schema.isBlank() ? schema : catalog;
        MigrationObjectRef.Kind kind = wildcard.kind();
        boolean all = kind == MigrationObjectRef.Kind.ALL;
        List<MigrationObjectRef> result = new ArrayList<>();
        try (Connection conn = BackgroundSqlService.getConnectionService()
                .getConnectionWithSessionInit(sessionConnect)) {
            MetadataRepository meta = PlatformResolvers.get().metadata(sessionConnect);
            if (all || kind == MigrationObjectRef.Kind.TABLE) {
                addObjects(result, meta.getUserTables(conn, dbName), catalog, schema,
                        MigrationObjectRef.Kind.TABLE);
            }
            if (all || kind == MigrationObjectRef.Kind.VIEW) {
                addObjects(result, meta.getViews(conn, dbName), catalog, schema,
                        MigrationObjectRef.Kind.VIEW);
            }
            if ((all || kind == MigrationObjectRef.Kind.SEQUENCE) && platform.supportsSequencesFolder()) {
                addObjects(result, meta.getSequences(conn, dbName), catalog, schema,
                        MigrationObjectRef.Kind.SEQUENCE);
            }
            if ((all || kind == MigrationObjectRef.Kind.SYNONYM) && platform.supportsSynonymsFolder()) {
                addObjects(result, meta.getSynonyms(conn, dbName), catalog, schema,
                        MigrationObjectRef.Kind.SYNONYM);
            }
            if (all || kind == MigrationObjectRef.Kind.TRIGGER) {
                addObjects(result, meta.getTriggers(conn, dbName), catalog, schema,
                        MigrationObjectRef.Kind.TRIGGER);
            }
            if ((all || kind == MigrationObjectRef.Kind.FUNCTION) && platform.supportsFunctionsFolder()) {
                addObjects(result, meta.getFunctions(conn, dbName, false), catalog, schema,
                        MigrationObjectRef.Kind.FUNCTION);
            }
            if ((all || kind == MigrationObjectRef.Kind.PROCEDURE) && platform.supportsProceduresFolder()) {
                addObjects(result, meta.getProcedures(conn, dbName, false), catalog, schema,
                        MigrationObjectRef.Kind.PROCEDURE);
            }
            if ((all || kind == MigrationObjectRef.Kind.PACKAGE) && platform.supportsPackages()) {
                addObjects(result, meta.getPackages(conn, dbName), catalog, schema,
                        MigrationObjectRef.Kind.PACKAGE);
            }
        }
        return result;
    }

    private static void addObjects(List<MigrationObjectRef> out,
                                   List<? extends TreeData> objects,
                                   String catalog, String schema,
                                   MigrationObjectRef.Kind kind) {
        if (objects == null) {
            return;
        }
        for (TreeData object : objects) {
            if (object != null && object.getName() != null && !object.getName().isBlank()) {
                out.add(new MigrationObjectRef(catalog, schema, kind, object.getName()));
            }
        }
    }

    // ==================================================================
    // 提交（FX 线程）
    // ==================================================================

    private static void submit(MigrationTask task, Connect source, Connect target,
                               List<MigrationObjectRef> objects) {
        if (task.isRunning()) {
            // 通配展开期间已被其他途径启动
            return;
        }
        TableMigrationService.MigrationRequest request = new TableMigrationService.MigrationRequest(
                source, target,
                task.getTargetDatabase(), task.getTargetSchema(),
                objects,
                task.isMigrateDdl(), task.isMigrateData(), task.isOverwrite(),
                task.isTruncateTable(), task.getThreadCount(),
                task.getMappings());

        // 明细行：按展开后对象生成 PENDING 行（明细中央 tab 的 TableView 直接绑定；只显示对象名）
        List<MigrationRunItem> pendingItems = new ArrayList<>(objects.size());
        for (MigrationObjectRef ref : objects) {
            pendingItems.add(new MigrationRunItem(ref.kind(), ref.name()));
        }
        task.getRunItems().setAll(pendingItems);

        task.getLastRunLog().clear();
        TableMigrationService.ProgressListener listener = new TableMigrationService.ProgressListener() {
            @Override
            public void onLog(String line) {
                Platform.runLater(() -> task.getLastRunLog().add(line));
            }

            @Override
            public void onItemStart(int index, int total, String objectName) {
                Platform.runLater(() -> markItemRunning(task, objectName));
            }

            @Override
            public void onItemDone(int index, int total, TableMigrationService.ItemResult result) {
                Platform.runLater(() -> applyItemResult(task, result));
            }

            @Override
            public void onDataProgress(MigrationObjectRef object, long totalRows, long copiedRows) {
                // 工作线程直写 volatile 实时字段；FX 侧由明细 tab 每秒 tick 搬进属性，避免刷 FX 队列
                MigrationRunItem item = findRunItem(task, object);
                if (item != null) {
                    item.setSourceRowsLive(totalRows);
                    item.setCopiedRowsLive(copiedRows);
                }
            }
        };

        javafx.concurrent.Task<TableMigrationService.MigrationSummary> migrationTask =
                new TableMigrationService().createTask(request, listener);
        task.setRunningTask(migrationTask);
        task.setRunState(MigrationTask.RunState.RUNNING);
        task.setLastRunResult(MigrationTask.RunResult.NONE);
        final String[] startTimeHolder = { RUN_TIME_FORMAT.format(System.currentTimeMillis()) };
        // 明细 tab 底部在运行期间显示本次开始时间
        task.setLastStartTime(startTimeHolder[0]);

        migrationTask.setOnSucceeded(event -> {
            task.setRunState(MigrationTask.RunState.IDLE);
            TableMigrationService.MigrationSummary summary = migrationTask.getValue();
            long success = summary == null ? 0 : summary.count(TableMigrationService.ItemStatus.SUCCESS);
            long failed = summary == null ? 1 : summary.count(TableMigrationService.ItemStatus.FAILED);
            task.setLastRunResult(failed > 0
                    ? MigrationTask.RunResult.FAILED
                    : MigrationTask.RunResult.SUCCESS);
            NotificationUtil.showMainNotification(String.format(
                    I18n.t("migration.notify.done",
                            "Migration task %s finished: %d succeeded, %d failed"),
                    task.getName(), success, failed));
            persistRun(task, startTimeHolder[0]);
        });
        migrationTask.setOnCancelled(event -> {
            task.setRunState(MigrationTask.RunState.IDLE);
            task.setLastRunResult(MigrationTask.RunResult.FAILED);
            NotificationUtil.showMainNotification(String.format(
                    I18n.t("migration.notify.failed", "Migration task %s failed: %s"),
                    task.getName(), I18n.t("migration.log.cancelled", "Migration cancelled")));
            persistRun(task, startTimeHolder[0]);
        });
        migrationTask.setOnFailed(event -> {
            task.setRunState(MigrationTask.RunState.IDLE);
            task.setLastRunResult(MigrationTask.RunResult.FAILED);
            Throwable ex = migrationTask.getException();
            String message = ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
                    ? String.valueOf(ex)
                    : ex.getMessage();
            NotificationUtil.showMainNotification(String.format(
                    I18n.t("migration.notify.failed", "Migration task %s failed: %s"),
                    task.getName(), message));
            task.getLastRunLog().add(message);
            persistRun(task, startTimeHolder[0]);
        });
        BackgroundSqlService.backSqlExecutor.submit(migrationTask);
    }

    /** 运行结束持久化：按类型成功/失败计数与起止时间写到任务表，逐对象明细整批替换。 */
    private static void persistRun(MigrationTask task, String startTime) {
        // 按类型统计成功/失败数量（跳过不计）；TOTAL 为总成功/总失败
        Map<String, long[]> counts = new LinkedHashMap<>();
        long successTotal = 0;
        long failedTotal = 0;
        for (MigrationRunItem item : task.getRunItems()) {
            if (item.getStatus() != MigrationRunItem.Status.SUCCESS
                    && item.getStatus() != MigrationRunItem.Status.FAILED) {
                continue;
            }
            String kind = item.getKind() == null ? "" : item.getKind().name();
            long[] pair = counts.computeIfAbsent(kind, k -> new long[2]);
            if (item.getStatus() == MigrationRunItem.Status.SUCCESS) {
                pair[0]++;
                successTotal++;
            } else {
                pair[1]++;
                failedTotal++;
            }
        }
        counts.put("TOTAL", new long[]{successTotal, failedTotal});
        String countsJson = MigrationTask.encodeRunCounts(counts);
        String endTime = RUN_TIME_FORMAT.format(System.currentTimeMillis());
        LocalDbRepository.updateMigrationTaskRunInfo(task.getId(), startTime, endTime, countsJson);
        // 同步任务对象上的最近结果字段
        task.setLastStartTime(startTime);
        task.setLastEndTime(endTime);
        task.setRunCountsJson(countsJson);

        // 逐对象明细整批替换（重启后明细 tab 可恢复上次执行状态）
        LocalDbRepository.deleteMigrationTaskRunItemsByTask(task.getId());
        List<MigrationTaskRunItem> runItems = new ArrayList<>(task.getRunItems().size());
        for (MigrationRunItem item : task.getRunItems()) {
            MigrationTaskRunItem record = new MigrationTaskRunItem();
            record.setTaskId(task.getId());
            record.setKind(item.getKind() == null ? "" : item.getKind().name());
            record.setName(item.getName() == null ? "" : item.getName());
            record.setStatus(item.getStatus() == null ? "" : item.getStatus().name());
            record.setStartTime(item.getStartTime() == null ? "" : item.getStartTime());
            record.setEndTime(item.getEndTime() == null ? "" : item.getEndTime());
            // c_rows 保持"已迁移行数"语义（源表行数不持久化）
            record.setRows(item.getMigratedRows());
            record.setError(item.getErrorMessage() == null ? "" : item.getErrorMessage());
            runItems.add(record);
        }
        LocalDbRepository.createMigrationTaskRunItems(runItems);
    }

    // ==================================================================
    // 明细行维护（FX 线程；重复对象名场景下按出现次序一一对应）
    // ==================================================================

    /** onItemStart：首个对象名相同且仍 PENDING 的行 → RUNNING + 开始时间。 */
    private static void markItemRunning(MigrationTask task, String objectName) {
        for (MigrationRunItem item : task.getRunItems()) {
            if (item.getStatus() == MigrationRunItem.Status.PENDING
                    && Objects.equals(item.getName(), objectName)) {
                item.setStatus(MigrationRunItem.Status.RUNNING);
                item.setStartTime(LocalDateTime.now().format(RUN_ITEM_TIME_FORMAT));
                item.setStartMillis(System.currentTimeMillis());
                return;
            }
        }
    }

    /**
     * onItemDone：写入状态/结束时间/行数/速度/错误信息。
     * 行数=源表行数（实时统计值，未知保持 -1）；迁移行数=实际复制行数。
     */
    private static void applyItemResult(MigrationTask task, TableMigrationService.ItemResult result) {
        if (result == null || result.object() == null) {
            return;
        }
        MigrationRunItem target = findRunItem(task, result.object());
        if (target == null) {
            return;
        }
        switch (result.status()) {
            case SUCCESS -> target.setStatus(MigrationRunItem.Status.SUCCESS);
            case FAILED -> target.setStatus(MigrationRunItem.Status.FAILED);
        }
        target.setEndTime(LocalDateTime.now().format(RUN_ITEM_TIME_FORMAT));
        target.setEndMillis(System.currentTimeMillis());
        target.setCopiedRowsLive(result.rowsCopied());
        if (target.getSourceRowsLive() >= 0) {
            target.setRows(target.getSourceRowsLive());
        }
        target.setMigratedRows(result.rowsCopied());
        target.setErrorMessage(result.message() == null ? "" : result.message());
        target.setErrorSql(result.errorSql() == null ? "" : result.errorSql());
        target.setSpeed(computeSpeed(result.rowsCopied(), target.getStartMillis(), target.getEndMillis()));
    }

    /** 按对象名 + kind 匹配明细行：优先 RUNNING 行，无则退回首个 PENDING 行。 */
    private static MigrationRunItem findRunItem(MigrationTask task, MigrationObjectRef object) {
        if (object == null) {
            return null;
        }
        MigrationRunItem pendingFallback = null;
        for (MigrationRunItem item : task.getRunItems()) {
            if (item.getKind() != object.kind() || !Objects.equals(item.getName(), object.name())) {
                continue;
            }
            if (item.getStatus() == MigrationRunItem.Status.RUNNING) {
                return item;
            }
            if (pendingFallback == null && item.getStatus() == MigrationRunItem.Status.PENDING) {
                pendingFallback = item;
            }
        }
        return pendingFallback;
    }

    /** 复制速度展示串（行/秒，千分位）：有行数且起止毫秒已知时计算，否则空串。 */
    public static String computeSpeed(long rows, long startMillis, long endMillis) {
        if (rows <= 0 || startMillis <= 0 || endMillis <= 0) {
            return "";
        }
        long speed = rows * 1000 / Math.max(1, endMillis - startMillis);
        return String.format(I18n.t("migration.detail.speed", "%s rows/s"),
                String.format("%,d", speed));
    }

    /** 解析明细行时间（yyyy-MM-dd HH:mm:ss）为 epoch 毫秒；空白/旧格式解析失败返回 0。 */
    private static long parseRunItemTime(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return LocalDateTime.parse(text, RUN_ITEM_TIME_FORMAT)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}
