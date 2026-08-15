package com.dbboys.service.migration;

import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.DdlRepository;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.core.SqlParser;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.SqlParserUtil;
import com.dbboys.model.BackgroundSqlTask;
import com.dbboys.model.ColumnsInfo;
import com.dbboys.model.Connect;
import com.dbboys.model.ForeignKey;
import com.dbboys.model.Index;
import com.dbboys.model.MigrationObjectRef;
import com.dbboys.model.Sql;
import com.dbboys.service.BackgroundSqlService;
import javafx.concurrent.Task;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对象级迁移服务：把源连接中若干对象（表/视图/序列/同义词/触发器/函数/存储过程/包）的
 * 结构（DDL）和/或数据复制到目标连接。数据复制仅支持表。
 * <p>
 * 同构判定按"DDL 方言族"：取两端 {@code resolver.ddl(connect)} 实例沿类继承链归族
 * （oracle/dameng → ORACLE，postgresql → POSTGRESQL，informix/gbase8s → INFORMIX，
 * mysql/sqlite/general-jdbc 各自成族），同族即同构——非表对象原生 DDL 直接回放；
 * 跨族表走 TypeMapper 建表 + 数据复制；非表对象始终原生 DDL 回放（跨族尽力而为，目标库不兼容时报错记 FAILED）。
 * <p>
 * 会话模型（与 {@code DatabaseService#buildDatabaseSessionConnect} 一致）：
 * 源端按 catalog(+schema) 分组，每组复制 Connect 并设置 catalog/sessionCatalog 后开一个独立后台会话；
 * 目标端按目标平台 catalogModel 设置后开一个独立会话。全程不使用主树连接。
 * <p>
 * 并行（生产者-消费者）：{@code readThreadCount} 个读线程（{@code WorkerSession}，每线程独立目标连接 +
 * 按分组缓存的源连接）经共享游标瓜分对象列表，完成对象级 DDL（冲突判定/DROP/建表，按 kind 临界区串行）
 * 并把表数据按批物化投进有界队列；{@code writeThreadCount} 个写线程从队列取批写入目标库，读写互相重叠。
 * 主线程持有的目标连接仅用于启动前的目标对象预取与后台任务展示。
 * <p>
 * 取消：返回的 Task 监听自身 stateProperty（CANCELLED），取消时中断活动 Statement 并 abort 全部活动连接；
 * 同时注册 {@link BackgroundSqlTask}（cancelAction = task.cancel），使主窗口后台任务按钮也能取消。
 * 所有 listener 回调均在工作线程触发，调用方自行切 FX 线程。
 */
public class TableMigrationService {
    private static final Logger log = LogManager.getLogger(TableMigrationService.class);
    private static final int BATCH_SIZE = 500;
    private static final String GENERAL_JDBC = "GENERAL JDBC";

    public enum ItemStatus { SUCCESS, FAILED }

    public record MigrationRequest(Connect source, Connect target,
                                   String targetDatabase, String targetSchema,
                                   List<MigrationObjectRef> objects,
                                   boolean migrateDdl, boolean migrateData, boolean overwrite,
                                   boolean truncateTable, int readThreadCount, int writeThreadCount,
                                   java.util.Map<String, TableMapping> mappings) {
        /** 读/写线程数下限 1。 */
        public MigrationRequest {
            readThreadCount = Math.max(1, readThreadCount);
            writeThreadCount = Math.max(1, writeThreadCount);
        }

        /** 兼容旧调用：无清空表/读写线程数/自定义数据映射。 */
        public MigrationRequest(Connect source, Connect target,
                                String targetDatabase, String targetSchema,
                                List<MigrationObjectRef> objects,
                                boolean migrateDdl, boolean migrateData, boolean overwrite) {
            this(source, target, targetDatabase, targetSchema, objects,
                    migrateDdl, migrateData, overwrite, false, 1, 1, java.util.Map.of());
        }

        /** 兼容旧调用：无清空表/读写线程数。 */
        public MigrationRequest(Connect source, Connect target,
                                String targetDatabase, String targetSchema,
                                List<MigrationObjectRef> objects,
                                boolean migrateDdl, boolean migrateData, boolean overwrite,
                                java.util.Map<String, TableMapping> mappings) {
            this(source, target, targetDatabase, targetSchema, objects,
                    migrateDdl, migrateData, overwrite, false, 1, 1, mappings);
        }
    }

    /** errorSql：出错时正在执行的 SQL；errorCode：数据库错误号（vendor code，无则 SQLState），无则 null。 */
    public record ItemResult(MigrationObjectRef object, ItemStatus status, long rowsCopied, String message, String errorSql, String errorCode) {}

    public record MigrationSummary(java.util.List<ItemResult> results, boolean cancelled) {
        public long count(ItemStatus status) { return results.stream().filter(r -> r.status() == status).count(); }
    }

    public interface ProgressListener {
        void onLog(String line);                                       // 工作线程回调，调用方自行切 FX 线程
        void onItemStart(int index, int total, String tableName);      // index 从 0 开始
        void onItemDone(int index, int total, ItemResult result);      // index 从 0 开始
        /** 数据复制进度（工作线程回调，每批一次；totalRows=源表行数，未知为 -1）。 */
        default void onDataProgress(MigrationObjectRef object, long totalRows, long copiedRows) {}
    }

    /**
     * 返回一个可随时 cancel() 的 Task；内部注册 BackgroundSqlTask 并监听 Task 状态（CANCELLED）中断当前 JDBC。
     * 调用方负责把 Task 提交到 {@link BackgroundSqlService#backSqlExecutor}。
     */
    public Task<MigrationSummary> createTask(MigrationRequest request, ProgressListener listener) {
        Objects.requireNonNull(request, "request");
        BackgroundSqlTask backSqlTask = new BackgroundSqlTask();
        SessionHolder sessions = new SessionHolder();
        Task<MigrationSummary> task = new Task<>() {
            @Override
            protected MigrationSummary call() throws Exception {
                return executeMigration(request, listener, backSqlTask, sessions);
            }
        };
        // 主窗口后台任务"停止"按钮 → BackgroundSqlTask.cancel() → cancelAction → task.cancel()
        backSqlTask.setCancelAction(task::cancel);
        // 对话框"取消"按钮 → task.cancel() → 中断当前 JDBC（取消语句 + abort 源/目标连接）
        // JavaFX 25 的 Task 已无 cancelledProperty()，改用 stateProperty() 监听 CANCELLED
        task.stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.CANCELLED) {
                sessions.interrupt();
                backSqlTask.cancel();
            }
        });
        return task;
    }

    // ==================================================================
    // 迁移主流程
    // ==================================================================

    private MigrationSummary executeMigration(MigrationRequest request,
                                              ProgressListener listener,
                                              BackgroundSqlTask backSqlTask,
                                              SessionHolder sessions) throws Exception {
        // 1. 平台守卫：GENERAL JDBC 不参与类型映射，整任务直接失败
        if (isGeneralJdbc(request.source()) || isGeneralJdbc(request.target())) {
            String reason = I18n.t("migration.error.platform_unsupported",
                    "Data migration does not support GENERAL JDBC connections");
            log(listener, reason);
            throw new IllegalStateException(reason);
        }

        MigrationContext ctx = new MigrationContext();
        ctx.request = request;
        ctx.listener = listener;
        ctx.resolver = PlatformResolvers.get();
        ctx.backSqlTask = backSqlTask;
        ctx.sessions = sessions;
        ctx.sourceMeta = ctx.resolver.metadata(request.source());
        // 同构 = 两端 DDL 方言族相同（oracle/dameng 同族、informix/gbase8s 同族等）
        DdlFamily sourceFamily = ddlFamilyOf(ctx.resolver, request.source());
        DdlFamily targetFamily = ddlFamilyOf(ctx.resolver, request.target());
        ctx.sameFamily = sourceFamily != DdlFamily.UNKNOWN && sourceFamily == targetFamily;

        List<ItemResult> results = new CopyOnWriteArrayList<>();
        int total = request.objects() == null ? 0 : request.objects().size();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        backSqlTask.setConnect(request.target());
        backSqlTask.setBeginTime(sdf.format(System.currentTimeMillis()));
        backSqlTask.setConnectName(request.target().getName());
        backSqlTask.setDatabaseName(request.targetDatabase() != null && !request.targetDatabase().isBlank()
                ? request.targetDatabase() : request.targetSchema());
        backSqlTask.setSql(I18n.t("migration.title", "Data Migration") + " (" + total + ")");
        BackgroundSqlService.backSqlTaskList.add(backSqlTask);
        BackgroundSqlService.updateBackSqlUIOnStart();

        try {
            // 2. 目标端主会话：仅用于目标端类型探测（轻量 init：仅库/模式选择），用完即关——
            //    运行期目标端连接数 = 写线程数（DDL/数据都在写线程连接上执行）
            Connect targetSessionConnect = buildTargetSessionConnect(request, ctx.resolver);
            Connection targetConn = MigrationConnections.create(targetSessionConnect);
            if (targetConn == null) {
                throw new SQLException("cannot open target connection");
            }
            ctx.targetPlatform = ctx.resolver.requirePlatform(targetSessionConnect);
            ctx.targetParser = ctx.targetPlatform.parser();
            // 目标端类型映射基准：支持 sqlmode 的平台（GBase 8S）以探测到的 sqlmode 为准
            ctx.targetMappingType = resolveTargetMappingType(ctx, targetSessionConnect, targetConn);
            // 主目标连接用完即关（不参与展示/取消；工作线程连接均在 SessionHolder 跟踪中）
            SessionHolder.closeQuietly(targetConn);

            Map<GroupKey, List<MigrationObjectRef>> groups = groupObjects(request.objects());
            List<GroupKey> flatGroups = new ArrayList<>(total);
            List<MigrationObjectRef> flatObjects = new ArrayList<>(total);
            for (Map.Entry<GroupKey, List<MigrationObjectRef>> entry : groups.entrySet()) {
                for (MigrationObjectRef object : entry.getValue()) {
                    flatGroups.add(entry.getKey());
                    flatObjects.add(object);
                }
            }

            // 5. 生产者-消费者并行迁移，连接数与线程数一致：
            //    读线程只持源连接（产批），写线程只持目标连接（先做全部表结构 DDL → 消费数据队列
            //    → 全部表数据完成后再做非表对象 DDL）
            ctx.total = total;
            List<GroupKey> tableGroups = new ArrayList<>();
            List<MigrationObjectRef> tableObjects = new ArrayList<>();
            List<GroupKey> otherGroups = new ArrayList<>();
            List<MigrationObjectRef> otherObjects = new ArrayList<>();
            for (int i = 0; i < flatObjects.size(); i++) {
                if (flatObjects.get(i).kind() == MigrationObjectRef.Kind.TABLE) {
                    tableGroups.add(flatGroups.get(i));
                    tableObjects.add(flatObjects.get(i));
                } else {
                    otherGroups.add(flatGroups.get(i));
                    otherObjects.add(flatObjects.get(i));
                }
            }
            runMigration(ctx, tableGroups, tableObjects, otherGroups, otherObjects, results);
            return new MigrationSummary(new ArrayList<>(results), false);
        } catch (CancellationException e) {
            log(listener, I18n.t("migration.log.cancelled", "Migration cancelled"));
            return new MigrationSummary(results, true);
        } catch (Exception e) {
            log(listener, String.format(
                    I18n.t("migration.log.failed", "%s failed: %s"), "-", errorMessage(e)));
            throw e;
        } finally {
            sessions.closeAll();
            backSqlTask.setStmt(null);
            backSqlTask.setConnection(null);
            BackgroundSqlService.backSqlTaskList.remove(backSqlTask);
            BackgroundSqlService.updateBackSqlUIOnFinish();
        }
    }

    /**
     * 单池执行全部迁移，连接数与线程数一致：
     * 写线程（各 1 目标连接）先并行做完全部表结构 DDL（阶段 A），读线程（各 1 源连接，不持目标连接）
     * 等 DDL 完成后产批（阶段 B），写线程消费队列；全部表数据写完（读线程尽、队列空、无在写批次）
     * 后写线程再并行做非表对象 DDL（阶段 C）。
     * 对象结果缺失且工作线程发生过致命错误（如目标连接打不开）时抛出，整体失败。
     */
    private void runMigration(MigrationContext ctx,
                              List<GroupKey> tableGroups, List<MigrationObjectRef> tableObjects,
                              List<GroupKey> otherGroups, List<MigrationObjectRef> otherObjects,
                              List<ItemResult> results) throws Exception {
        int tableCount = tableObjects.size();
        int readers = tableCount == 0 ? 0
                : Math.max(1, Math.min(ctx.request.readThreadCount(), tableCount));
        int writers = Math.max(1, ctx.request.writeThreadCount());
        // 有界队列形成反压：读快写慢时读线程在 offer 上等待，不会无限攒行
        BlockingQueue<RowBatch> dataQueue = new ArrayBlockingQueue<>(Math.max(16, writers * 4));
        AtomicInteger ddlCursor = new AtomicInteger();      // 阶段 A：表结构 DDL 游标（写线程）
        AtomicInteger ddlInFlight = new AtomicInteger();    // 阶段 A：在执行的 DDL 数（读线程放行判定）
        AtomicInteger dataCursor = new AtomicInteger();     // 阶段 B：读线程取表游标
        AtomicInteger otherCursor = new AtomicInteger();    // 阶段 C：非表对象游标（写线程）
        AtomicInteger readersRunning = new AtomicInteger(readers);
        AtomicInteger activeWriters = new AtomicInteger();  // 正在写批次的写线程数（阶段 C 屏障）
        Set<Integer> ddlFailed = ConcurrentHashMap.newKeySet(); // 表结构 DDL 失败的表下标
        AtomicReference<Exception> workerFatal = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "migration-worker-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> runWriter(ctx, tableGroups, tableObjects, ddlCursor, ddlInFlight, ddlFailed,
                        dataQueue, readersRunning, activeWriters, otherGroups, otherObjects, otherCursor,
                        results, workerFatal));
            }
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> runReader(ctx, tableGroups, tableObjects, dataCursor, dataQueue,
                        results, readersRunning, workerFatal, ddlCursor, ddlInFlight, ddlFailed));
            }
            pool.shutdown();
            while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                checkCancelled(ctx.backSqlTask);
            }
            checkCancelled(ctx.backSqlTask);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("table migration cancelled");
        } finally {
            pool.shutdownNow();
        }
        if (results.size() < ctx.total && workerFatal.get() != null) {
            throw workerFatal.get();
        }
    }

    /**
     * 读线程（生产者）主循环：等全部表结构 DDL 完成（阶段 A，写线程执行）后，共享游标取表、
     * 流式拉数产批——纯源端会话，不持目标连接（目标端连接数=写线程数）。
     * DDL 失败的表跳过（结果已在阶段 A 产出）；取消时安静退出。
     */
    private void runReader(MigrationContext ctx, List<GroupKey> tableGroups, List<MigrationObjectRef> tableObjects,
                           AtomicInteger dataCursor, BlockingQueue<RowBatch> dataQueue, List<ItemResult> results,
                           AtomicInteger readersRunning, AtomicReference<Exception> workerFatal,
                           AtomicInteger ddlCursor, AtomicInteger ddlInFlight, Set<Integer> ddlFailed) {
        int total = tableObjects.size();
        try (WorkerSession ws = new WorkerSession(ctx, false)) {
            awaitTableDdl(ctx, ddlCursor, ddlInFlight, total, workerFatal);
            for (int local = dataCursor.getAndIncrement(); local < total; local = dataCursor.getAndIncrement()) {
                checkCancelled(ctx.backSqlTask);
                if (ddlFailed.contains(local)) {
                    continue; // 结构 DDL 已失败，结果已在阶段 A 产出
                }
                MigrationObjectRef object = tableObjects.get(local);
                try {
                    ws.ensureSource(tableGroups.get(local));
                    ItemResult immediate = produceTableData(ctx, ws, object, local, dataQueue, results, workerFatal);
                    if (immediate != null) {
                        // 无列信息/产批前失败：当场出结果；其余由管道完成时异步出结果
                        results.add(immediate);
                        if (ctx.listener != null) {
                            ctx.listener.onItemDone(local, ctx.total, immediate);
                        }
                    }
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    // 源连接打开失败等对象级错误：记 FAILED 后继续下一对象
                    String reason = errorMessage(e);
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.failed", "%s failed: %s"), object.displayName(), reason));
                    ItemResult result = new ItemResult(object, ItemStatus.FAILED, 0, reason, null, null);
                    results.add(result);
                    if (ctx.listener != null) {
                        ctx.listener.onItemDone(local, ctx.total, result);
                    }
                }
            }
        } catch (CancellationException e) {
            // 取消：安静退出
        } catch (Exception e) {
            workerFatal.compareAndSet(null, e);
            log(ctx.listener, String.format(
                    I18n.t("migration.log.failed", "%s failed: %s"), "-", errorMessage(e)));
        } finally {
            readersRunning.decrementAndGet();
        }
    }

    /** 等全部表结构 DDL 完成（游标耗尽且无在执行）；写线程全灭或取消时放行（入队会感知致命错误）。 */
    private static void awaitTableDdl(MigrationContext ctx, AtomicInteger ddlCursor, AtomicInteger ddlInFlight,
                                      int tableCount, AtomicReference<Exception> workerFatal) {
        while (ddlCursor.get() < tableCount || ddlInFlight.get() > 0) {
            checkCancelled(ctx.backSqlTask);
            if (workerFatal.get() != null) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("table migration cancelled");
            }
        }
    }

    /**
     * 读线程拉数产批（无 DDL，阶段 A 已建好结构）：物化行 → 批次入队，写线程消费写入目标。
     * 返回非 null 表示当场出结果（无列信息按 0 行成功）；其余经 {@link #maybeCompletePipe} 异步出。
     */
    private ItemResult produceTableData(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object,
                                        int index, BlockingQueue<RowBatch> dataQueue, List<ItemResult> results,
                                        AtomicReference<Exception> workerFatal) throws Exception {
        TablePipe pipe = null;
        try {
            pipe = preparePipe(ctx, ws, object, index);
            if (pipe == null) {
                // 无列信息：沿用旧 copyData 的早退语义，按 0 行成功处理
                return new ItemResult(object, ItemStatus.SUCCESS, 0, null, null, null);
            }
            streamProduce(ctx, ws, pipe, dataQueue, workerFatal);
            pipe.produceDone = true;
        } catch (CancellationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("table migration cancelled");
        } catch (Exception e) {
            if (pipe == null) {
                throw e; // 管道未建立（取列元数据等失败）：走对象级失败
            }
            pipe.failure = e;
            pipe.produceDone = true;
        }
        maybeCompletePipe(ctx, pipe, results);
        return null;
    }

    /**
     * 阶段 A（写线程）：表结构 DDL——勾选覆盖时 DROP、勾选 DDL 时建表、需迁数据且未重建时清空，全部提交。
     * 返回 null = 结构就绪、待读线程产数（结果由管道出）；非 null = 当场出结果（失败/无需迁数据）。
     */
    private ItemResult migrateTableDdl(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object) {
        MigrationRequest request = ctx.request;
        String displayName = object.displayName();
        String objectName = object.name();
        try {
            ItemResult wildcard = wildcardFailure(ctx, object);
            if (wildcard != null) {
                return wildcard;
            }
            // 同 kind 对象的 DROP/DDL 串行执行；数据复制在锁外并行
            synchronized (object.kind()) {
                if (request.overwrite() && request.migrateDdl()) {
                    String dropSql = buildDropSql(ctx, object);
                    if (dropSql == null) {
                        log.debug("drop skipped for {}: no host table", displayName);
                    } else {
                        try {
                            executeTargetStatement(ctx, ws, dropSql);
                            commitTarget(ctx, ws);
                            log(ctx.listener, String.format(
                                    I18n.t("migration.log.drop", "%s dropped in target"), displayName));
                        } catch (Exception e) {
                            rollbackTargetQuietly(ctx, ws);
                            log.debug("drop ignored for {}: {}", displayName, errorMessage(e));
                        }
                    }
                }
                if (request.migrateDdl()) {
                    String script = buildDdlScript(ctx, ws, object);
                    executeScript(ctx, ws, script);
                    commitTarget(ctx, ws);
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.ddl_ok", "%s structure created"), displayName));
                }
            }
            if (!request.migrateData()) {
                return new ItemResult(object, ItemStatus.SUCCESS, 0, null, null, null);
            }
            boolean recreated = request.overwrite() && request.migrateDdl();
            if (!recreated && request.truncateTable()) {
                // 勾选"清空表"才先清目标表：优先方言 TRUNCATE，方言不支持时退化为 DELETE FROM；
                // 未勾选时直接追加复制，不再先 DELETE
                String truncateSql = ctx.targetPlatform.truncateTableSql(objectName);
                executeTargetStatement(ctx, ws, truncateSql != null && !truncateSql.isBlank()
                        ? truncateSql : "DELETE FROM " + objectName);
                // 清空在写线程连接上提交，且阶段 A 全部完成后读线程才放行——数据写入一定在清空之后
                commitTarget(ctx, ws);
                log(ctx.listener, String.format(
                        I18n.t("migration.log.truncate_ok", "%s target table cleared"), displayName));
            }
            return null;
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            return failureResult(ctx, ws, object, e);
        }
    }

    /**
     * 阶段 C（写线程）：非表对象 DDL 回放（通配/平台守卫 + 覆盖 DROP + 建表），当场出 ItemResult。
     * 调用前提：全部表数据迁移已完成（阶段屏障在写线程主循环里判定）。
     */
    private ItemResult produceObjectDdlOnly(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object) {
        MigrationRequest request = ctx.request;
        String displayName = object.displayName();
        try {
            ItemResult wildcard = wildcardFailure(ctx, object);
            if (wildcard != null) {
                return wildcard;
            }
            MigrationObjectRef.Kind kind = object.kind();
            // PACKAGE 需要源端能打印、目标端能执行
            if (kind == MigrationObjectRef.Kind.PACKAGE && request.migrateDdl() && !packagesSupported(ctx, ws)) {
                String message = String.format(
                        I18n.t("migration.log.failed_unsupported",
                                "%s object %s is not supported by source or target platform"),
                        kindLabel(kind), displayName);
                log(ctx.listener, message);
                return new ItemResult(object, ItemStatus.FAILED, 0, message, null, null);
            }
            // 同 kind 对象的 DROP/DDL 串行执行
            synchronized (kind) {
                if (request.overwrite() && request.migrateDdl()) {
                    String dropSql = buildDropSql(ctx, object);
                    if (dropSql == null) {
                        log.debug("drop skipped for {}: no host table", displayName);
                    } else {
                        try {
                            executeTargetStatement(ctx, ws, dropSql);
                            commitTarget(ctx, ws);
                            log(ctx.listener, String.format(
                                    I18n.t("migration.log.drop", "%s dropped in target"), displayName));
                        } catch (Exception e) {
                            rollbackTargetQuietly(ctx, ws);
                            log.debug("drop ignored for {}: {}", displayName, errorMessage(e));
                        }
                    }
                }
                if (request.migrateDdl()) {
                    String script = buildDdlScript(ctx, ws, object);
                    executeScript(ctx, ws, script);
                    commitTarget(ctx, ws);
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.ddl_ok", "%s structure created"), displayName));
                }
            }
            return new ItemResult(object, ItemStatus.SUCCESS, 0, null, null, null);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            return failureResult(ctx, ws, object, e);
        }
    }

    /** 通配防御：未展开的通配条目记 FAILED 并返回结果；可正常处理返回 null。 */
    private ItemResult wildcardFailure(MigrationContext ctx, MigrationObjectRef object) {
        if (!object.needsExpansion()) {
            return null;
        }
        String message = String.format(
                I18n.t("migration.log.failed_wildcard",
                        "Wildcard object %s was not expanded before migration"),
                object.displayName());
        log(ctx.listener, message);
        return new ItemResult(object, ItemStatus.FAILED, 0, message, null, null);
    }

    /** 对象级失败收尾：回滚 + 解包 SqlFailedException/链式 SQLException，生成 FAILED ItemResult 并打日志。 */
    private ItemResult failureResult(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object, Exception e) {
        rollbackTargetQuietly(ctx, ws);
        String errorSql = null;
        if (e instanceof SqlFailedException sqlFailed) {
            errorSql = sqlFailed.getSql();
            if (sqlFailed.getCause() instanceof Exception cause) {
                e = cause;
            }
        }
        while (e instanceof SQLException sqlEx && sqlEx.getNextException() != null) {
            e = sqlEx.getNextException();
        }
        // 数据库错误号：vendor code 优先，无则 SQLState
        String errorCode = null;
        if (e instanceof SQLException sqlEx) {
            if (sqlEx.getErrorCode() != 0) {
                errorCode = String.valueOf(sqlEx.getErrorCode());
            } else if (sqlEx.getSQLState() != null && !sqlEx.getSQLState().isBlank()) {
                errorCode = sqlEx.getSQLState();
            }
        }
        String reason = errorMessage(e);
        log(ctx.listener, String.format(
                I18n.t("migration.log.failed", "%s failed: %s"), object.displayName(), reason));
        return new ItemResult(object, ItemStatus.FAILED, 0, reason, errorSql, errorCode);
    }

    /**
     * 写线程主循环（每线程独立目标连接 + 按分组缓存的源连接）：阶段 A 并行做全部表结构 DDL →
     * 阶段 B 消费数据队列 → 阶段 C（全部表数据完成后）并行做非表对象 DDL。
     * 管道已失败（读/写首错）的批次直接丢弃；全部工作完成后退出。
     */
    private void runWriter(MigrationContext ctx, List<GroupKey> tableGroups, List<MigrationObjectRef> tableObjects,
                           AtomicInteger ddlCursor, AtomicInteger ddlInFlight, Set<Integer> ddlFailed,
                           BlockingQueue<RowBatch> dataQueue, AtomicInteger readersRunning,
                           AtomicInteger activeWriters,
                           List<GroupKey> otherGroups, List<MigrationObjectRef> otherObjects,
                           AtomicInteger otherCursor,
                           List<ItemResult> results, AtomicReference<Exception> workerFatal) {
        try (WorkerSession ws = new WorkerSession(ctx)) {
            // —— 阶段 A：并行做全部表结构 DDL；读线程等游标耗尽且无在执行后放行 ——
            int tableCount = tableObjects.size();
            for (int i = ddlCursor.getAndIncrement(); i < tableCount; i = ddlCursor.getAndIncrement()) {
                checkCancelled(ctx.backSqlTask);
                ddlInFlight.incrementAndGet();
                try {
                    MigrationObjectRef object = tableObjects.get(i);
                    if (ctx.listener != null) {
                        ctx.listener.onItemStart(i, ctx.total, object.name());
                    }
                    ItemResult ddlResult;
                    try {
                        ws.ensureSource(tableGroups.get(i));
                        ddlResult = migrateTableDdl(ctx, ws, object);
                    } catch (CancellationException e) {
                        throw e;
                    } catch (Exception e) {
                        // 对象级错误：记 FAILED 后继续下一对象
                        String reason = errorMessage(e);
                        log(ctx.listener, String.format(
                                I18n.t("migration.log.failed", "%s failed: %s"), object.displayName(), reason));
                        ddlResult = new ItemResult(object, ItemStatus.FAILED, 0, reason, null, null);
                    }
                    if (ddlResult != null) {
                        if (ddlResult.status() == ItemStatus.FAILED) {
                            ddlFailed.add(i); // 读线程跳过该表的数据生产
                        }
                        results.add(ddlResult);
                        if (ctx.listener != null) {
                            ctx.listener.onItemDone(i, ctx.total, ddlResult);
                        }
                    }
                } finally {
                    ddlInFlight.decrementAndGet();
                }
            }

            // —— 阶段 B/C：消费数据队列；全部表数据完成后转非表对象 DDL ——
            Map<TablePipe, PreparedStatement> stmtCache = new java.util.HashMap<>();
            try {
                while (true) {
                    RowBatch batch;
                    try {
                        batch = dataQueue.poll(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("table migration cancelled");
                    }
                    if (batch == null) {
                        // 表数据未全部完成（读线程未尽/队列未空/有在写批次）：继续等
                        if (readersRunning.get() != 0 || !dataQueue.isEmpty() || activeWriters.get() != 0) {
                            checkCancelled(ctx.backSqlTask);
                            if (workerFatal.get() != null) {
                                return; // 目标连接打不开等致命错误：主流程汇总后整体失败
                            }
                            continue;
                        }
                        // —— 阶段 C：全部表数据已完成，并行做非表对象 DDL；游标取尽即全部完成 ——
                        checkCancelled(ctx.backSqlTask);
                        int oi = otherCursor.getAndIncrement();
                        if (oi >= otherObjects.size()) {
                            return;
                        }
                        MigrationObjectRef object = otherObjects.get(oi);
                        int index = tableObjects.size() + oi; // 全任务清单下标（阶段拆分保持了先后顺序）
                        if (ctx.listener != null) {
                            ctx.listener.onItemStart(index, ctx.total, object.name());
                        }
                        ItemResult result;
                        try {
                            ws.ensureSource(otherGroups.get(oi));
                            result = produceObjectDdlOnly(ctx, ws, object);
                        } catch (CancellationException e) {
                            throw e;
                        } catch (Exception e) {
                            String reason = errorMessage(e);
                            log(ctx.listener, String.format(
                                    I18n.t("migration.log.failed", "%s failed: %s"), object.displayName(), reason));
                            result = new ItemResult(object, ItemStatus.FAILED, 0, reason, null, null);
                        }
                        results.add(result);
                        if (ctx.listener != null) {
                            ctx.listener.onItemDone(index, ctx.total, result);
                        }
                        continue;
                    }
                    TablePipe pipe = batch.pipe;
                    activeWriters.incrementAndGet();
                    try {
                        if (pipe.failure == null) {
                            PreparedStatement ps = stmtCache.get(pipe);
                            if (ps == null) {
                                ps = ws.targetConn.prepareStatement(pipe.insertSql);
                                ctx.sessions.track(ps);
                                stmtCache.put(pipe, ps);
                            }
                            ctx.backSqlTask.setStmt(ps);
                            for (Object[] row : batch.rows) {
                                checkCancelled(ctx.backSqlTask);
                                for (int i = 0; i < row.length; i++) {
                                    bindObject(ps, i + 1, row[i]);
                                }
                                ps.addBatch();
                            }
                            ps.executeBatch();
                            commitTarget(ctx, ws);
                            long copied = pipe.copied.addAndGet(batch.rows.size());
                            emitDataProgress(ctx, pipe.table, pipe.totalRows, copied);
                        }
                    } catch (CancellationException e) {
                        throw e;
                    } catch (Exception e) {
                        // 首错定格后该表后续批次全部丢弃；出错语句从缓存移除
                        if (pipe.failure == null) {
                            pipe.failure = e;
                            pipe.failureSql = pipe.insertSql;
                        }
                        rollbackTargetQuietly(ctx, ws);
                        PreparedStatement broken = stmtCache.remove(pipe);
                        if (broken != null) {
                            ctx.sessions.untrack(broken);
                            try {
                                broken.close();
                            } catch (Exception ignored) {
                            }
                        }
                    } finally {
                        activeWriters.decrementAndGet();
                        pipe.pending.decrementAndGet();
                        maybeCompletePipe(ctx, pipe, results);
                    }
                }
            } finally {
                ctx.backSqlTask.setStmt(null);
                for (PreparedStatement ps : stmtCache.values()) {
                    ctx.sessions.untrack(ps);
                    try {
                        ps.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (CancellationException e) {
            // 取消：安静退出
        } catch (Exception e) {
            workerFatal.compareAndSet(null, e);
            log(ctx.listener, String.format(
                    I18n.t("migration.log.failed", "%s failed: %s"), "-", errorMessage(e)));
        }
    }

    /** 管道完成判定（读产完 + 产出批次全部出结果），恰好出一次 ItemResult；读写线程都会调用。 */
    private void maybeCompletePipe(MigrationContext ctx, TablePipe pipe, List<ItemResult> results) {
        synchronized (pipe) {
            if (pipe.completed || !pipe.produceDone || pipe.pending.get() != 0) {
                return;
            }
            pipe.completed = true;
        }
        ItemResult result = buildPipeResult(ctx, pipe);
        results.add(result);
        if (ctx.listener != null) {
            ctx.listener.onItemDone(pipe.index, ctx.total, result);
        }
    }

    /** 管道收尾：按首错（有则 FAILED）与已写入行数生成 ItemResult，并打完成日志。 */
    private ItemResult buildPipeResult(MigrationContext ctx, TablePipe pipe) {
        String displayName = pipe.table.displayName();
        long copiedRows = pipe.copied.get();
        if (pipe.failure == null) {
            log(ctx.listener, String.format(
                    I18n.t("migration.log.data_ok", "%s data migrated, %d rows copied"),
                    displayName, copiedRows));
            return new ItemResult(pipe.table, ItemStatus.SUCCESS, copiedRows, null, null, null);
        }
        // SqlFailedException 解包：取出错 SQL；驱动链式包装（如 BatchUpdateException）下钻到链尾真实错误
        Exception e = pipe.failure;
        String errorSql = pipe.failureSql;
        if (e instanceof SqlFailedException sqlFailed) {
            errorSql = sqlFailed.getSql();
            if (sqlFailed.getCause() instanceof Exception cause) {
                e = cause;
            }
        }
        while (e instanceof SQLException sqlEx && sqlEx.getNextException() != null) {
            e = sqlEx.getNextException();
        }
        // 数据库错误号：vendor code 优先，无则 SQLState
        String errorCode = null;
        if (e instanceof SQLException sqlEx) {
            if (sqlEx.getErrorCode() != 0) {
                errorCode = String.valueOf(sqlEx.getErrorCode());
            } else if (sqlEx.getSQLState() != null && !sqlEx.getSQLState().isBlank()) {
                errorCode = sqlEx.getSQLState();
            }
        }
        String reason = errorMessage(e);
        log(ctx.listener, String.format(
                I18n.t("migration.log.failed", "%s failed: %s"), displayName, reason));
        return new ItemResult(pipe.table, ItemStatus.FAILED, copiedRows, reason, errorSql, errorCode);
    }

    // ==================================================================
    // DDL
    // ==================================================================

    private String buildDdlScript(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object) throws Exception {
        String objectName = object.name();
        // 自定义数据映射仅对 TABLE 生效；有映射时放弃原生 DDL，改走 TypeMapper 结构生成（同族也如此）
        boolean isTable = object.kind() == MigrationObjectRef.Kind.TABLE;
        TableMapping mapping = isTable
                ? TableMapping.forTable(ctx.request.mappings(), objectName)
                : null;
        Map<String, String> globalTypes = isTable
                ? TableMapping.globalTypeOverrides(ctx.request.mappings())
                : Map.of();
        boolean applyMapping = mapping != null && !mapping.isEmpty();
        boolean applyGlobal = !globalTypes.isEmpty();
        boolean useNativeTableDdl = object.kind() == MigrationObjectRef.Kind.TABLE
                && ctx.sameFamily && !applyMapping && !applyGlobal
                && Objects.equals(sourceTypeForMapping(ctx, ws, objectName), ctx.targetMappingType);
        if (object.kind() != MigrationObjectRef.Kind.TABLE || useNativeTableDdl) {
            // 同族且无自定义映射时，还要源/目标 sqlmode 一致才回放原生建表 DDL；否则走 TypeMapper 转换
            // 原生 DDL 按 kind 分派回放：同族全保真；跨族非表对象尽力回放（不兼容由目标库报错记 FAILED）
            DdlRepository ddl = ctx.resolver.ddl(ws.sourceSessionConnect);
            return switch (object.kind()) {
                case TABLE -> ddl.printTableForMigration(ws.sourceConn, objectName);
                case VIEW -> ddl.printView(ws.sourceConn, objectName);
                case SEQUENCE -> ddl.printSequence(ws.sourceConn, objectName);
                case SYNONYM -> ddl.printSynonym(ws.sourceConn, objectName);
                case TRIGGER -> ddl.printTrigger(ws.sourceConn, objectName);
                case FUNCTION -> ddl.printFunction(ws.sourceConn, objectName);
                case PROCEDURE -> ddl.printProcedure(ws.sourceConn, objectName);
                case PACKAGE -> ddl.printPackage(ws.sourceConn, objectName);
                case INDEX -> buildIndexDdl(ctx, ws, object);
                case FOREIGN_KEY -> buildForeignKeyDdl(ctx, ws, object);
                case ALL -> throw new SQLException("wildcard object has no DDL");
            };
        }
        // 仅全局类型映射时不打 mapping_applied 日志（避免每张表刷屏）
        if (applyMapping) {
            log(ctx.listener, String.format(
                    I18n.t("migration.log.mapping_applied",
                            "Custom mapping applied for table %s, DDL generated from structure"),
                    objectName));
        }
        // 跨族或应用自定义映射（逐表/全局）的表：TypeMapper 类型映射建表（源类型 sqlmode 优先）
        ArrayList<ColumnsInfo> columns = ctx.sourceMeta.getColumns(ws.sourceConn, objectName);
        List<String> primaryKeyColumns = ctx.sourceMeta.getPrimaryKeyColumns(ws.sourceConn, objectName);
        String tableComment = null;
        try {
            tableComment = ctx.sourceMeta.getTableComment(ws.sourceConn, objectName);
        } catch (Exception e) {
            log.debug("getTableComment failed for {}", objectName, e);
        }
        List<String> warnings = new ArrayList<>();
        String script = TypeMapper.buildCreateTableScript(
                sourceTypeForMapping(ctx, ws, objectName),
                ctx.targetMappingType == null ? ctx.request.target().getDbtype() : ctx.targetMappingType,
                objectName, columns, primaryKeyColumns, tableComment, warnings,
                mapping, globalTypes);
        for (String warning : warnings) {
            log(ctx.listener, String.format(
                    I18n.t("migration.log.type_fallback", "%s type fallback: %s"), object.displayName(), warning));
        }
        return script;
    }

    /**
     * 覆盖重建时的 DROP 语句。触发器走 {@link DatabasePlatform#dropTriggerSql}
     * （PostgreSQL 需要 ON &lt;table&gt;）、索引走 {@link DatabasePlatform#dropIndexSql}
     * （MySQL 需要 ON &lt;table&gt;）、外键走 ALTER TABLE ... DROP CONSTRAINT（MySQL 系 DROP FOREIGN KEY）
     * ——宿主表来自源端对象引用；统一去掉 IF EXISTS，DROP 失败由调用方忽略。
     */
    private String buildDropSql(MigrationContext ctx, MigrationObjectRef object) {
        if (object.kind() == MigrationObjectRef.Kind.TRIGGER) {
            String tableName = object.parent();
            if (tableName == null || tableName.isBlank()) {
                return null;
            }
            return withoutDropIfExists(ctx.targetPlatform.dropTriggerSql(object.name(), tableName));
        }
        if (object.kind() == MigrationObjectRef.Kind.INDEX) {
            String tableName = object.parent();
            if (tableName == null || tableName.isBlank()) {
                return null;
            }
            return withoutDropIfExists(ctx.targetPlatform.dropIndexSql(object.name(), tableName));
        }
        if (object.kind() == MigrationObjectRef.Kind.FOREIGN_KEY) {
            String tableName = object.parent();
            if (tableName == null || tableName.isBlank()) {
                return null;
            }
            // MySQL 系外键删除用 DROP FOREIGN KEY，其余按标准 DROP CONSTRAINT（跨族尽力而为）
            boolean mysqlFamily = ddlFamilyOf(ctx.resolver, ctx.request.target()) == DdlFamily.MYSQL;
            return withoutDropIfExists("ALTER TABLE " + tableName
                    + (mysqlFamily ? " DROP FOREIGN KEY " : " DROP CONSTRAINT ") + object.name());
        }
        return withoutDropIfExists(
                ctx.targetPlatform.dropObjectSql(dropObjectType(object.kind()), object.name()));
    }

    private static String withoutDropIfExists(String dropSql) {
        if (dropSql == null || dropSql.isBlank()) {
            return dropSql;
        }
        return dropSql.replaceAll("(?i)\\s+IF EXISTS\\s+", " ").trim();
    }

    /** kind → {@link DatabasePlatform#dropObjectSql} 的 objectType 字面值（与树删除菜单一致）。 */
    private static String dropObjectType(MigrationObjectRef.Kind kind) {
        return switch (kind) {
            case TABLE -> "table";
            case VIEW -> "view";
            case SEQUENCE -> "sequence";
            case SYNONYM -> "synonym";
            case TRIGGER -> "trigger";
            case FUNCTION -> "function";
            case PROCEDURE -> "procedure";
            case PACKAGE -> "package";
            case INDEX, FOREIGN_KEY ->
                    throw new IllegalArgumentException("index/foreign key drop needs host table");
            case ALL -> throw new IllegalArgumentException("wildcard object has no drop type");
        };
    }

    /** 外键 DDL：按 ref.parent（宿主表）从源端 JDBC 元数据取定义，生成标准 ALTER TABLE ADD CONSTRAINT。 */
    private String buildForeignKeyDdl(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object) throws Exception {
        String hostTable = object.parent();
        if (hostTable == null || hostTable.isBlank()) {
            throw new SQLException("foreign key " + object.name() + " has no host table (parent)");
        }
        for (ForeignKey fk : ctx.sourceMeta.getTableForeignKeys(ws.sourceConn, hostTable)) {
            if (fk.getName() != null && fk.getName().equalsIgnoreCase(object.name())) {
                return buildAddForeignKeySql(fk, targetTypeForDdl(ctx));
            }
        }
        throw new SQLException("foreign key " + object.name() + " not found on table " + hostTable);
    }

    /** 按目标 sqlmode/dbtype 生成 CREATE INDEX；源端索引元数据读取失败时回退原生 printIndex。 */
    private String buildIndexDdl(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object) throws Exception {
        String dbName = object.schema() != null && !object.schema().isBlank()
                ? object.schema()
                : object.catalog();
        try {
            Index index = ctx.sourceMeta.getIndex(ws.sourceConn, dbName, object.name());
            if (index != null && index.getName() != null) {
                String targetType = targetTypeForDdl(ctx);
                String indexName = indexNameForTarget(index.getName(), targetType);
                String tableName = index.getTableName() != null && !index.getTableName().isBlank()
                        ? index.getTableName()
                        : object.parent();
                String columns = index.getCols() != null && !index.getCols().isBlank()
                        ? index.getCols()
                        : (index.getIndexCols() == null ? "" : index.getIndexCols());
                if (tableName == null || tableName.isBlank() || columns.isBlank()) {
                    throw new SQLException("index " + object.name() + " has no table or columns");
                }

                if ("MYSQL".equalsIgnoreCase(targetType) && "PRIMARY".equalsIgnoreCase(indexName)) {
                    return "ALTER TABLE " + tableName + " ADD PRIMARY KEY (" + columns + ");";
                }

                String type = index.getIdxtype();
                String cluster = index.getIndexCluster();
                String prefix;
                if ("U".equalsIgnoreCase(type) || "UNIQUE".equalsIgnoreCase(type)) {
                    prefix = "UNIQUE ";
                } else if ("C".equalsIgnoreCase(cluster)) {
                    prefix = "CLUSTER ";
                } else {
                    prefix = "";
                }
                return "CREATE " + prefix + "INDEX " + indexName
                        + " ON " + tableName + " (" + columns + ");";
            }
        } catch (Exception e) {
            log.debug("build index ddl failed for {}, fallback to native", object.name(), e);
        }
        return ctx.resolver.ddl(ws.sourceSessionConnect).printIndex(ws.sourceConn, object.name());
    }

    private static String targetTypeForDdl(MigrationContext ctx) {
        return ctx.targetMappingType == null || ctx.targetMappingType.isBlank()
                ? ctx.request.target().getDbtype()
                : ctx.targetMappingType;
    }

    private static String indexNameForTarget(String indexName, String targetType) {
        if ("MYSQL".equalsIgnoreCase(targetType) && indexName != null) {
            int separator = indexName.lastIndexOf("$$");
            if (separator >= 0 && separator + 2 < indexName.length()) {
                return indexName.substring(separator + 2);
            }
        }
        return indexName;
    }

    private static boolean isGbaseConstraintSyntax(String targetType) {
        return "GBASE 8S".equalsIgnoreCase(targetType) || "INFORMIX".equalsIgnoreCase(targetType);
    }

    /** 类型映射用的源平台类型：表级 sqlmode 优先——有 sqlmode 时以其数据类型为准
     *  （Oracle→ORACLE、MySQL→MYSQL、GBase→GBASE 8S），取不到 sqlmode 再看连接的数据库类型。 */
    private static String sourceTypeForMapping(MigrationContext ctx, WorkerSession ws, String tableName) {
        String dbtype = ctx.request.source().getDbtype();
        try {
            String sqlmode = ctx.sourceMeta.getTableSqlMode(ws.sourceConn, tableName);
            if ("Oracle".equalsIgnoreCase(sqlmode)) {
                return "ORACLE";
            }
            if ("MySQL".equalsIgnoreCase(sqlmode)) {
                return "MYSQL";
            }
            if ("GBase".equalsIgnoreCase(sqlmode)) {
                return "GBASE 8S";
            }
        } catch (Exception e) {
            log.trace("getTableSqlMode failed for {}", tableName, e);
        }
        return dbtype;
    }

    /** 目标端类型映射基准：目标库支持 sqlmode 时以探测到的当前 sqlmode 为准
     *  （mysql→MYSQL、oracle→ORACLE、gbase→GBASE 8S），探测不到/失败用连接的数据库类型。 */
    private static String resolveTargetMappingType(MigrationContext ctx, Connect targetSessionConnect,
                                                   Connection targetConn) {
        String dbtype = ctx.request.target().getDbtype();
        try {
            var repo = ctx.resolver.sqlexe(targetSessionConnect);
            if (repo instanceof com.dbboys.core.SqlModeCapability capability) {
                List<String> modes = capability.getSqlModes(targetConn);
                if (modes != null && !modes.isEmpty()) {
                    // "sqlmode=mysql" 形式，取首项（与 SQL 页当前模式同口径）
                    String mode = modes.get(0) == null ? "" : modes.get(0).replace("sqlmode=", "").trim();
                    if ("mysql".equalsIgnoreCase(mode)) {
                        return "MYSQL";
                    }
                    if ("oracle".equalsIgnoreCase(mode)) {
                        return "ORACLE";
                    }
                    if ("gbase".equalsIgnoreCase(mode)) {
                        return "GBASE 8S";
                    }
                }
            }
        } catch (Exception e) {
            log.trace("probe target sqlmode failed", e);
        }
        return dbtype;
    }

    /** 标准外键 DDL（跨族尽力而为；目标库不兼容由执行报错记 FAILED）。规则仅在有显式动作时附加。 */
    private static String buildAddForeignKeySql(ForeignKey fk, String targetType) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(fk.getTableName());
        String name = fk.getName();
        if (isGbaseConstraintSyntax(targetType)) {
            sb.append(" ADD CONSTRAINT FOREIGN KEY (").append(fk.getColumns()).append(")")
                    .append(" REFERENCES ").append(fk.getRefTableName())
                    .append(" (").append(fk.getRefColumns()).append(")");
            if (name != null && !name.isBlank()) {
                sb.append(" CONSTRAINT ").append(name);
            }
        } else {
            if (name != null && !name.isBlank()) {
                sb.append(" ADD CONSTRAINT ").append(name);
            }
            sb.append(" FOREIGN KEY (").append(fk.getColumns()).append(")")
                    .append(" REFERENCES ").append(fk.getRefTableName())
                    .append(" (").append(fk.getRefColumns()).append(")");
        }
        if (fk.getDeleteRule() != null) {
            sb.append(" ON DELETE ").append(fk.getDeleteRule());
        }
        if (fk.getUpdateRule() != null) {
            sb.append(" ON UPDATE ").append(fk.getUpdateRule());
        }
        return sb.toString();
    }

    /** PACKAGE 仅在源端可打印且目标端可执行时才支持。 */
    private static boolean packagesSupported(MigrationContext ctx, WorkerSession ws) {
        try {
            return ctx.resolver.requirePlatform(ws.sourceSessionConnect).supportsPackages()
                    && ctx.targetPlatform.supportsPackages();
        } catch (Exception e) {
            log.debug("supportsPackages check failed", e);
            return false;
        }
    }

    /** kind 的本地化展示名（properties 缺失时回退英文）。 */
    private static String kindLabel(MigrationObjectRef.Kind kind) {
        return switch (kind) {
            case TABLE -> I18n.t("migration.kind.table", "table");
            case VIEW -> I18n.t("migration.kind.view", "view");
            case SEQUENCE -> I18n.t("migration.kind.sequence", "sequence");
            case SYNONYM -> I18n.t("migration.kind.synonym", "synonym");
            case TRIGGER -> I18n.t("migration.kind.trigger", "trigger");
            case FUNCTION -> I18n.t("migration.kind.function", "function");
            case PROCEDURE -> I18n.t("migration.kind.procedure", "procedure");
            case PACKAGE -> I18n.t("migration.kind.package", "package");
            case INDEX -> I18n.t("migration.kind.index", "index");
            case FOREIGN_KEY -> I18n.t("migration.kind.foreign_key", "foreign key");
            case ALL -> I18n.t("migration.kind.all", "all");
        };
    }

    /** DDL 方言族：同族之间原生 DDL 可直接在目标端回放。 */
    private enum DdlFamily { ORACLE, POSTGRESQL, INFORMIX, MYSQL, SQLITE, GENERAL_JDBC, UNKNOWN }

    private static DdlFamily ddlFamilyOf(DatabasePlatformResolver resolver, Connect connect) {
        try {
            return ddlFamilyOf(resolver.ddl(connect));
        } catch (Exception e) {
            log.debug("resolve ddl repository failed", e);
            return DdlFamily.UNKNOWN;
        }
    }

    /**
     * 沿 DdlRepository 实例的类继承链匹配已知族基类/类名。
     * oracle/dameng 继承 OracleFamilyDdlRepository → ORACLE；
     * postgresql 继承 PostgreSqlFamilyDdlRepository → POSTGRESQL；
     * informix/gbase8s 的 DDL 仓库是并列 final 类（无公共基类），按类名归入 INFORMIX；
     * mysql/sqlite/general-jdbc 各自成族。
     */
    private static DdlFamily ddlFamilyOf(DdlRepository ddl) {
        if (ddl == null) {
            return DdlFamily.UNKNOWN;
        }
        for (Class<?> type = ddl.getClass(); type != null; type = type.getSuperclass()) {
            switch (type.getSimpleName()) {
                case "OracleFamilyDdlRepository", "OracleDdlRepository", "DamengDdlRepository" -> {
                    return DdlFamily.ORACLE;
                }
                case "PostgreSqlFamilyDdlRepository", "PostgresqlDdlRepository" -> {
                    return DdlFamily.POSTGRESQL;
                }
                case "InformixDdlRepository", "Gbase8sDdlRepository" -> {
                    return DdlFamily.INFORMIX;
                }
                case "MysqlDdlRepository" -> {
                    return DdlFamily.MYSQL;
                }
                case "SqliteDdlRepository" -> {
                    return DdlFamily.SQLITE;
                }
                case "GeneralJdbcDdlRepository" -> {
                    return DdlFamily.GENERAL_JDBC;
                }
                default -> {
                    // 沿继承链继续向上匹配
                }
            }
        }
        return DdlFamily.UNKNOWN;
    }

    /** 用目标平台 parser 切分 DDL 脚本文本并逐条 execute（参照 DatabaseService.importSqlScriptSync）。 */
    private void executeScript(MigrationContext ctx, WorkerSession ws, String scriptText) throws Exception {
        if (scriptText == null || scriptText.isBlank()) {
            throw new SQLException("empty DDL script");
        }
        SqlParser parser = ctx.targetParser;
        Sql currentSql = new Sql();
        for (SqlParserUtil.Segment segment : SqlParserUtil.split(scriptText)) {
            String remainingChunk = segment.getText();
            while (remainingChunk != null && !remainingChunk.isBlank()) {
                checkCancelled(ctx.backSqlTask);
                currentSql = parser.modifySql(currentSql, remainingChunk);
                if (!currentSql.getSqlEnd()) {
                    break;
                }
                String statement = currentSql.getSqlstr();
                if (SqlParserUtil.isExecutableStatement(statement)) {
                    executeTargetStatement(ctx, ws, statement.trim());
                }
                remainingChunk = currentSql.getSqlRemainder();
                currentSql = new Sql();
            }
        }
        if (SqlParserUtil.isExecutableStatement(currentSql.getSqlstr())) {
            checkCancelled(ctx.backSqlTask);
            executeTargetStatement(ctx, ws, currentSql.getSqlstr().trim());
        }
    }

    // ==================================================================
    // 数据复制
    // ==================================================================

    /**
     * 建单表数据管道：取列元数据（剔排除列）、算 SELECT/INSERT、统计源表行数并发初始进度。
     * 失败（元数据取不到等）抛给对象级 catch 记 FAILED，此时尚无批次入队。
     */
    private TablePipe preparePipe(MigrationContext ctx, WorkerSession ws, MigrationObjectRef table,
                                  int index) throws Exception {
        String tableName = table.name();
        ArrayList<ColumnsInfo> columns = ctx.sourceMeta.getColumns(ws.sourceConn, tableName);
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        // 自定义数据映射：剔除排除列（绑值数组同步缩小）
        TableMapping mapping = TableMapping.forTable(ctx.request.mappings(), tableName);
        if (mapping != null && !mapping.isEmpty()) {
            ArrayList<ColumnsInfo> kept = new ArrayList<>();
            for (ColumnsInfo column : columns) {
                if (!mapping.isExcluded(column.getColName())) {
                    kept.add(column);
                }
            }
            if (kept.isEmpty()) {
                throw new IllegalArgumentException("all columns excluded");
            }
            columns = kept;
        }
        int columnCount = columns.size();
        TypeMapper.GenericType[] types = new TypeMapper.GenericType[columnCount];
        // 类型映射以表级 sqlmode 优先，再看连接数据库类型
        String sourceType = sourceTypeForMapping(ctx, ws, tableName);
        StringBuilder columnList = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columnCount; i++) {
            ColumnsInfo column = columns.get(i);
            types[i] = TypeMapper.normalize(sourceType, column);
            if (i > 0) {
                columnList.append(", ");
                placeholders.append(", ");
            }
            columnList.append(column.getColName());
            placeholders.append('?');
        }
        String selectSql = "SELECT " + columnList + " FROM " + tableName;
        // 自定义 WHERE 条件过滤（仅数据复制；用户可带或不带 WHERE 关键字）
        String whereSql = "";
        String whereClause = table.where();
        if (whereClause != null && !whereClause.isBlank()) {
            String clause = whereClause.trim();
            whereSql = clause.regionMatches(true, 0, "where", 0, 5)
                    ? " " + clause
                    : " WHERE " + clause;
            selectSql += whereSql;
        }
        String insertSql = "INSERT INTO " + tableName + " (" + columnList + ") VALUES (" + placeholders + ")";

        // 源表行数（含 WHERE 过滤）：供明细 tab"行数"列展示；统计失败按未知 -1，不影响迁移
        long totalRows = -1;
        try {
            totalRows = querySourceRowCount(ws.sourceConn, tableName, whereSql);
        } catch (Exception e) {
            log.trace("count source rows failed for {}", tableName, e);
        }
        TablePipe pipe = new TablePipe(table, index, selectSql, insertSql, types, totalRows);
        emitDataProgress(ctx, table, totalRows, 0);
        return pipe;
    }

    /**
     * 读线程流式拉数：每 {@link #BATCH_SIZE} 行物化为一批投入有界队列
     * （先 pending 计数再入队——写线程的完成判定依赖该计数，顺序不能反）。
     * 出错归 SELECT：抛 {@link SqlFailedException}（携带 selectSql）。
     */
    private void streamProduce(MigrationContext ctx, WorkerSession ws, TablePipe pipe,
                               BlockingQueue<RowBatch> dataQueue,
                               AtomicReference<Exception> workerFatal) throws Exception {
        try (PreparedStatement selectStmt = ws.sourceConn.prepareStatement(
                pipe.selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            try {
                selectStmt.setFetchSize(BATCH_SIZE);
            } catch (Exception e) {
                log.trace("setFetchSize not supported", e);
            }
            ctx.sessions.track(selectStmt);
            ctx.backSqlTask.setStmt(selectStmt);
            try (ResultSet rs = selectStmt.executeQuery()) {
                List<Object[]> rows = new ArrayList<>(BATCH_SIZE);
                while (rs.next()) {
                    checkCancelled(ctx.backSqlTask);
                    if (pipe.failure != null) {
                        return; // 写线程已报错（如目标表不存在）：剩余行不再拉取，直接走失败收尾
                    }
                    Object[] row = new Object[pipe.types.length];
                    for (int i = 0; i < row.length; i++) {
                        row[i] = readValue(rs, i + 1, pipe.types[i]);
                    }
                    rows.add(row);
                    if (rows.size() >= BATCH_SIZE) {
                        pipe.pending.incrementAndGet();
                        enqueue(ctx, dataQueue, new RowBatch(pipe, rows), workerFatal);
                        rows = new ArrayList<>(BATCH_SIZE);
                    }
                }
                if (!rows.isEmpty()) {
                    pipe.pending.incrementAndGet();
                    enqueue(ctx, dataQueue, new RowBatch(pipe, rows), workerFatal);
                }
            } finally {
                ctx.sessions.untrack(selectStmt);
                ctx.backSqlTask.setStmt(null);
            }
        } catch (SQLException e) {
            throw new SqlFailedException(pipe.selectSql, e);
        }
    }

    /** 有界队列反压入队：写线程全灭（workerFatal）或任务取消时退出等待，避免死锁。 */
    private static void enqueue(MigrationContext ctx, BlockingQueue<RowBatch> dataQueue, RowBatch batch,
                                AtomicReference<Exception> workerFatal) throws InterruptedException {
        while (!dataQueue.offer(batch, 100, TimeUnit.MILLISECONDS)) {
            checkCancelled(ctx.backSqlTask);
            if (workerFatal.get() != null) {
                throw new CancellationException("migration writers are gone");
            }
        }
    }

    /** 按 TypeMapper.normalize 的 GenericType 从 ResultSet 物化一列值；SQL NULL → null。 */
    private static Object readValue(ResultSet rs, int index, TypeMapper.GenericType type) throws SQLException {
        switch (type == null ? TypeMapper.GenericType.OTHER : type) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL -> {
                java.math.BigDecimal value = rs.getBigDecimal(index);
                return rs.wasNull() ? null : value;
            }
            case FLOAT, DOUBLE -> {
                double value = rs.getDouble(index);
                return rs.wasNull() ? null : value;
            }
            case BINARY, BLOB -> {
                byte[] value = rs.getBytes(index);
                return rs.wasNull() ? null : value;
            }
            case DATE, TIME, DATETIME, TIMESTAMP -> {
                Timestamp value = rs.getTimestamp(index);
                return rs.wasNull() ? null : value;
            }
            default -> {
                String value = rs.getString(index);
                return rs.wasNull() ? null : value;
            }
        }
    }

    /** 绑定物化值：null → setNull(Types.OTHER)，其余按运行时类型绑定（与 readValue 的物化类型一一对应）。 */
    private static void bindObject(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else if (value instanceof java.math.BigDecimal decimal) {
            ps.setBigDecimal(index, decimal);
        } else if (value instanceof Double doubleValue) {
            ps.setDouble(index, doubleValue);
        } else if (value instanceof byte[] bytes) {
            ps.setBytes(index, bytes);
        } else if (value instanceof Timestamp timestamp) {
            ps.setTimestamp(index, timestamp);
        } else {
            ps.setString(index, String.valueOf(value));
        }
    }

    // ==================================================================
    // 会话构建
    // ==================================================================

    /** 源端会话：DATABASE→库、SCHEMA→模式走方言 setSessionCatalog；DATABASE_SCHEMA→catalog=库 + sessionCatalog=模式。 */
    private Connect buildSourceSessionConnect(Connect source, GroupKey group, DatabasePlatformResolver resolver) {
        Connect sessionConnect = new Connect(source);
        DatabasePlatform platform = resolver.requirePlatform(sessionConnect);
        if (group.schemaName() != null && !group.schemaName().isBlank()) {
            sessionConnect.setCatalog(group.catalogName());
            sessionConnect.setSessionCatalog(group.schemaName());
        } else if (group.catalogName() != null && !group.catalogName().isBlank()) {
            platform.connection().setSessionCatalog(sessionConnect, group.catalogName());
        }
        return sessionConnect;
    }

    /** 目标端会话：DATABASE→sessionCatalog=targetDatabase；SCHEMA→targetSchema；DATABASE_SCHEMA→两者。 */
    private Connect buildTargetSessionConnect(MigrationRequest request, DatabasePlatformResolver resolver) {
        Connect sessionConnect = new Connect(request.target());
        DatabasePlatform platform = resolver.requirePlatform(sessionConnect);
        String targetDatabase = blankToNull(request.targetDatabase());
        String targetSchema = blankToNull(request.targetSchema());
        switch (platform.catalogModel()) {
            case DATABASE -> {
                if (targetDatabase != null) {
                    platform.connection().setSessionCatalog(sessionConnect, targetDatabase);
                }
            }
            case SCHEMA -> {
                if (targetSchema != null) {
                    platform.connection().setSessionCatalog(sessionConnect, targetSchema);
                }
            }
            case DATABASE_SCHEMA -> {
                if (targetDatabase != null) {
                    sessionConnect.setCatalog(targetDatabase);
                }
                if (targetSchema != null) {
                    sessionConnect.setSessionCatalog(targetSchema);
                }
            }
        }
        return sessionConnect;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private record GroupKey(String catalogName, String schemaName) {}

    private static Map<GroupKey, List<MigrationObjectRef>> groupObjects(List<MigrationObjectRef> objects) {
        Map<GroupKey, List<MigrationObjectRef>> groups = new LinkedHashMap<>();
        if (objects == null) {
            return groups;
        }
        for (MigrationObjectRef object : objects) {
            if (object == null) {
                continue;
            }
            groups.computeIfAbsent(new GroupKey(object.catalog(), object.schema()), k -> new ArrayList<>())
                    .add(object);
        }
        return groups;
    }

    private void executeTargetStatement(MigrationContext ctx, WorkerSession ws, String sql) throws SQLException {
        String execSql = sql;
        String upper = execSql.stripLeading().toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) && execSql.endsWith(";")) {
            execSql = execSql.substring(0, execSql.length() - 1).trim();
        }
        try (Statement stmt = ws.targetConn.createStatement()) {
            ctx.sessions.track(stmt);
            ctx.backSqlTask.setStmt(stmt);
            try {
                stmt.execute(execSql);
            } catch (SQLException e) {
                throw new SqlFailedException(execSql, e);
            } finally {
                ctx.sessions.untrack(stmt);
                ctx.backSqlTask.setStmt(null);
            }
        }
    }

    private void commitTarget(MigrationContext ctx, WorkerSession ws) throws SQLException {
        if (ws.targetTransactional) {
            ws.targetConn.commit();
        }
    }

    private void rollbackTargetQuietly(MigrationContext ctx, WorkerSession ws) {
        if (ws.targetTransactional && ws.targetConn != null) {
            try {
                ws.targetConn.rollback();
            } catch (Exception e) {
                log.trace("rollback failed", e);
            }
        }
    }

    /** Informix 无日志库等不支持事务：setAutoCommit(false) 失败则保持自动提交并跳过 commit。 */
    private static boolean tryDisableAutoCommit(Connection conn) {
        try {
            if (conn.getAutoCommit()) {
                conn.setAutoCommit(false);
            }
            return true;
        } catch (SQLException e) {
            log.debug("target connection does not support transactions, running with auto-commit", e);
            return false;
        }
    }

    private static void checkCancelled(BackgroundSqlTask backSqlTask) {
        if (backSqlTask.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("table migration cancelled");
        }
    }

    private static boolean isGeneralJdbc(Connect connect) {
        return connect != null
                && connect.getDbtype() != null
                && GENERAL_JDBC.equalsIgnoreCase(connect.getDbtype().trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 原始错误文本：驱动链式包装（如 BatchUpdateException）取链尾的真实 DB 错误，不再二次包装。 */
    private static String errorMessage(Exception e) {
        while (e instanceof SQLException sqlEx && sqlEx.getNextException() != null) {
            e = sqlEx.getNextException();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 携带出错 SQL 的包装异常：{@link #produceObject}/{@link #buildPipeResult} 解包后把 SQL 附到 {@link ItemResult#errorSql}。 */
    private static final class SqlFailedException extends SQLException {
        private final String sql;

        SqlFailedException(String sql, SQLException cause) {
            super(cause);
            this.sql = sql;
        }

        String getSql() {
            return sql;
        }
    }

    /** 源表行数统计（含自定义 WHERE 过滤）；仅供明细展示，驱动不支持超时设置则忽略。 */
    private static long querySourceRowCount(Connection conn, String tableName, String whereSql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try {
                stmt.setQueryTimeout(60);
            } catch (Exception e) {
                log.trace("setQueryTimeout not supported", e);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName + whereSql)) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    /** 数据复制进度回调（listener 为空则跳过）。 */
    private static void emitDataProgress(MigrationContext ctx, MigrationObjectRef table, long totalRows, long copiedRows) {
        if (ctx.listener != null) {
            ctx.listener.onDataProgress(table, totalRows, copiedRows);
        }
    }

    private static void log(ProgressListener listener, String line) {
        if (listener != null) {
            listener.onLog(line);
        }
    }

    /** 一批已物化的行数据：读线程产出、写线程消费。 */
    private static final class RowBatch {
        final TablePipe pipe;
        final List<Object[]> rows;

        RowBatch(TablePipe pipe, List<Object[]> rows) {
            this.pipe = pipe;
            this.rows = rows;
        }
    }

    /**
     * 单表数据管道：读线程建一次，写线程共享。完成 = 读线程产完（{@code produceDone}）且
     * 产出批次全部出结果（{@code pending} 归零），恰好出一次 ItemResult（见 maybeCompletePipe）。
     */
    private static final class TablePipe {
        final MigrationObjectRef table;
        final int index;
        final String selectSql;
        final String insertSql;
        final TypeMapper.GenericType[] types;
        final long totalRows;
        final AtomicLong copied = new AtomicLong();
        final AtomicInteger pending = new AtomicInteger();
        volatile boolean produceDone;
        boolean completed;              // guarded by this pipe's monitor
        volatile Exception failure;     // 读/写首错，非空即 FAILED
        volatile String failureSql;     // 写失败时归因的 INSERT（读失败经 SqlFailedException 携带 selectSql）

        TablePipe(MigrationObjectRef table, int index, String selectSql, String insertSql,
                  TypeMapper.GenericType[] types, long totalRows) {
            this.table = table;
            this.index = index;
            this.selectSql = selectSql;
            this.insertSql = insertSql;
            this.types = types;
            this.totalRows = totalRows;
        }
    }

    /** 迁移上下文：跨方法共享的执行期状态（字段均只读或并发安全；连接在各 {@link WorkerSession} 上）。 */
    private static final class MigrationContext {
        MigrationRequest request;
        ProgressListener listener;
        DatabasePlatformResolver resolver;
        BackgroundSqlTask backSqlTask;
        SessionHolder sessions;
        MetadataRepository sourceMeta;
        boolean sameFamily;
        DatabasePlatform targetPlatform;
        SqlParser targetParser;
        /** 目标端类型映射基准：支持 sqlmode 的平台以探测到的 sqlmode 为准，否则=目标连接 dbtype。 */
        String targetMappingType;
        int total;
    }

    /**
     * 单工作线程的 JDBC 会话：一个独立目标连接 + 按 catalog/schema 分组缓存的源连接
     * （分组切换才重建）。连接均注册进 {@link SessionHolder}，取消时可被中断。
     */
    private final class WorkerSession implements AutoCloseable {
        private final MigrationContext ctx;
        final Connection targetConn;
        final boolean targetTransactional;
        Connection sourceConn;
        Connect sourceSessionConnect;
        private GroupKey sourceGroup;
        private boolean closed;

        /** 写线程会话：目标连接 + 按分组缓存的源连接。 */
        WorkerSession(MigrationContext ctx) throws Exception {
            this(ctx, true);
        }

        /**
         * @param openTarget false 为纯读线程会话：不持目标连接（保证目标端连接数=写线程数），
         *                   源连接按需 ensureSource 打开
         */
        WorkerSession(MigrationContext ctx, boolean openTarget) throws Exception {
            this.ctx = ctx;
            if (openTarget) {
                Connect targetSessionConnect = buildTargetSessionConnect(ctx.request, ctx.resolver);
                // 迁移连接只做库/模式选择的轻量 init（MigrationConnections），不做 sqlmode 等会话级强制
                targetConn = MigrationConnections.create(targetSessionConnect);
                if (targetConn == null) {
                    throw new SQLException("cannot open target connection");
                }
                ctx.sessions.track(targetConn);
                targetTransactional = tryDisableAutoCommit(targetConn);
            } else {
                targetConn = null;
                targetTransactional = false;
            }
        }

        /** 确保源连接对应给定分组：同组复用，跨组关闭重建。 */
        void ensureSource(GroupKey group) throws Exception {
            if (sourceConn != null && Objects.equals(sourceGroup, group)) {
                return;
            }
            closeSource();
            Connect sessionConnect = buildSourceSessionConnect(ctx.request.source(), group, ctx.resolver);
            sourceConn = MigrationConnections.create(sessionConnect);
            if (sourceConn == null) {
                throw new SQLException("cannot open source connection");
            }
            ctx.sessions.track(sourceConn);
            sourceSessionConnect = sessionConnect;
            sourceGroup = group;
        }

        private void closeSource() {
            if (sourceConn == null) {
                return;
            }
            ctx.sessions.untrack(sourceConn);
            SessionHolder.closeQuietly(sourceConn);
            sourceConn = null;
            sourceSessionConnect = null;
            sourceGroup = null;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeSource();
            if (targetConn != null) {
                ctx.sessions.untrack(targetConn);
                SessionHolder.closeQuietly(targetConn);
            }
        }
    }

    /** 活动 JDBC 资源持有器（多工作线程并发注册）：取消时中断全部活动语句并 abort/close 全部连接。 */
    private static final class SessionHolder {
        private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
        private final Set<Statement> statements = ConcurrentHashMap.newKeySet();

        void track(Connection conn) {
            if (conn != null) {
                connections.add(conn);
            }
        }

        void untrack(Connection conn) {
            connections.remove(conn);
        }

        void track(Statement stmt) {
            if (stmt != null) {
                statements.add(stmt);
            }
        }

        void untrack(Statement stmt) {
            statements.remove(stmt);
        }

        void interrupt() {
            for (Statement stmt : new ArrayList<>(statements)) {
                try {
                    stmt.cancel();
                } catch (Exception ignored) {
                }
            }
            for (Connection conn : new ArrayList<>(connections)) {
                abortQuietly(conn);
            }
        }

        void closeAll() {
            // 快照遍历 + 重试，避免并发 untrack/close 时弱一致性迭代器跳过连接
            for (int attempt = 0; attempt < 3 && !connections.isEmpty(); attempt++) {
                for (Connection conn : new ArrayList<>(connections)) {
                    abortQuietly(conn);
                    connections.remove(conn);
                }
            }
            for (Connection conn : new ArrayList<>(connections)) {
                abortQuietly(conn);
            }
            connections.clear();
        }

        private static void abortQuietly(Connection conn) {
            if (conn == null) {
                return;
            }
            try {
                conn.abort(Runnable::run);
            } catch (Exception ignored) {
            }
            closeQuietly(conn);
        }

        private static void closeQuietly(Connection conn) {
            if (conn == null) {
                return;
            }
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }
}
