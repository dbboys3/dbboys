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
import com.dbboys.model.MigrationObjectRef;
import com.dbboys.model.Sql;
import com.dbboys.model.TreeData;
import com.dbboys.model.Trigger;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 并行：{@code threadCount} 个工作线程（{@code WorkerSession}，每线程独立目标连接 + 按分组缓存的源连接）
 * 经共享游标瓜分对象列表；对象级 DDL（冲突判定/DROP/建表）按 kind 临界区串行，数据复制并行。
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
                                   boolean truncateTable, int threadCount,
                                   java.util.Map<String, TableMapping> mappings) {
        /** 线程数下限 1（=串行语义）。 */
        public MigrationRequest {
            threadCount = Math.max(1, threadCount);
        }

        /** 兼容旧调用：无清空表/线程数/自定义数据映射。 */
        public MigrationRequest(Connect source, Connect target,
                                String targetDatabase, String targetSchema,
                                List<MigrationObjectRef> objects,
                                boolean migrateDdl, boolean migrateData, boolean overwrite) {
            this(source, target, targetDatabase, targetSchema, objects,
                    migrateDdl, migrateData, overwrite, false, 1, java.util.Map.of());
        }

        /** 兼容旧调用：无清空表/线程数。 */
        public MigrationRequest(Connect source, Connect target,
                                String targetDatabase, String targetSchema,
                                List<MigrationObjectRef> objects,
                                boolean migrateDdl, boolean migrateData, boolean overwrite,
                                java.util.Map<String, TableMapping> mappings) {
            this(source, target, targetDatabase, targetSchema, objects,
                    migrateDdl, migrateData, overwrite, false, 1, mappings);
        }
    }

    /** errorSql：出错时正在执行的 SQL（无则 null），明细 tab 双击错误列时展示。 */
    public record ItemResult(MigrationObjectRef object, ItemStatus status, long rowsCopied, String message, String errorSql) {}

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
        ctx.targetMeta = ctx.resolver.metadata(request.target());
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
            // 2. 目标端主会话：仅用于预取冲突检测与后台任务展示；DDL/数据在各工作线程独立连接上执行
            Connect targetSessionConnect = buildTargetSessionConnect(request, ctx.resolver);
            Connection targetConn = BackgroundSqlService.getConnectionService()
                    .getConnectionWithSessionInit(targetSessionConnect);
            if (targetConn == null) {
                throw new SQLException("cannot open target connection");
            }
            sessions.track(targetConn);
            backSqlTask.setConnection(targetConn);
            ctx.targetPlatform = ctx.resolver.requirePlatform(targetSessionConnect);
            ctx.targetParser = ctx.targetPlatform.parser();

            // 3. 按请求中的 kind 预取目标已存在对象名集合（冲突检测；触发器额外记录宿主表）
            prefetchTargetNames(ctx, targetConn, targetSessionConnect);

            // 4. 源端按 catalog(+schema) 分组后扁平化（保持首次出现顺序），供游标按序分发
            Map<GroupKey, List<MigrationObjectRef>> groups = groupObjects(request.objects());
            List<GroupKey> flatGroups = new ArrayList<>(total);
            List<MigrationObjectRef> flatObjects = new ArrayList<>(total);
            for (Map.Entry<GroupKey, List<MigrationObjectRef>> entry : groups.entrySet()) {
                for (MigrationObjectRef object : entry.getValue()) {
                    flatGroups.add(entry.getKey());
                    flatObjects.add(object);
                }
            }

            // 5. 按配置线程数并行迁移（threadCount=1 时单工作线程，语义同串行）
            int workers = Math.max(1, Math.min(request.threadCount(), Math.max(total, 1)));
            AtomicInteger cursor = new AtomicInteger();
            AtomicReference<Exception> workerFatal = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(workers, new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "migration-worker-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            });
            try {
                for (int w = 0; w < workers; w++) {
                    pool.submit(() -> runWorker(ctx, flatGroups, flatObjects, cursor, results, workerFatal));
                }
                pool.shutdown();
                while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    checkCancelled(backSqlTask);
                }
                checkCancelled(backSqlTask);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("table migration cancelled");
            } finally {
                pool.shutdownNow();
            }
            // 有对象未出结果且工作线程发生过致命错误（如目标连接打不开）→ 整体失败
            if (results.size() < total && workerFatal.get() != null) {
                throw workerFatal.get();
            }
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
     * 工作线程主循环：共享游标取对象下标，逐对象迁移（每线程独立 {@link WorkerSession}）。
     * 目标连接打开失败等致命错误记入 {@code workerFatal} 后退出，剩余对象由其他工作线程继续拾取；
     * 单对象失败由 {@link #migrateOne} 兜住记 FAILED；取消时安静退出。
     */
    private void runWorker(MigrationContext ctx, List<GroupKey> groups, List<MigrationObjectRef> objects,
                           AtomicInteger cursor, List<ItemResult> results,
                           AtomicReference<Exception> workerFatal) {
        int total = objects.size();
        try (WorkerSession ws = new WorkerSession(ctx)) {
            for (int index = cursor.getAndIncrement(); index < total; index = cursor.getAndIncrement()) {
                checkCancelled(ctx.backSqlTask);
                MigrationObjectRef object = objects.get(index);
                if (ctx.listener != null) {
                    ctx.listener.onItemStart(index, total, object.name());
                }
                ItemResult result;
                try {
                    ws.ensureSource(groups.get(index));
                    result = migrateOne(ctx, ws, object);
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    // 源连接打开失败等对象级错误：记 FAILED 后继续下一对象
                    String reason = errorMessage(e);
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.failed", "%s failed: %s"), object.displayName(), reason));
                    result = new ItemResult(object, ItemStatus.FAILED, 0, reason, null);
                }
                results.add(result);
                if (ctx.listener != null) {
                    ctx.listener.onItemDone(index, total, result);
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

    /** 单对象迁移；失败记录后由调用方继续下一对象，不中断整体。 */
    private ItemResult migrateOne(MigrationContext ctx, WorkerSession ws, MigrationObjectRef object) {
        MigrationRequest request = ctx.request;
        String displayName = object.displayName();
        String objectName = object.name();
        long[] rowsCopied = {0};
        try {
            // 通配防御：正常情况下 runner 已把通配（整节点 kind=ALL 或类型级 name=null）展开，服务不展开
            if (object.needsExpansion()) {
                String message = String.format(
                        I18n.t("migration.log.failed_wildcard",
                                "Wildcard object %s was not expanded before migration"),
                        displayName);
                log(ctx.listener, message);
                return new ItemResult(object, ItemStatus.FAILED, 0, message, null);
            }
            MigrationObjectRef.Kind kind = object.kind();
            // PACKAGE 需要源端能打印、目标端能执行
            if (kind == MigrationObjectRef.Kind.PACKAGE && request.migrateDdl() && !packagesSupported(ctx, ws)) {
                String message = String.format(
                        I18n.t("migration.log.failed_unsupported",
                                "%s object %s is not supported by source or target platform"),
                        kindLabel(kind), displayName);
                log(ctx.listener, message);
                return new ItemResult(object, ItemStatus.FAILED, 0, message, null);
            }

            Set<String> existing = ctx.existingNames.get(kind);
            // 并行下同 kind 对象（可能同名不同 catalog/schema）的冲突判定 + DROP/DDL 必须串行；数据复制在锁外并行
            Object ddlLock = existing != null ? existing : kind;
            boolean exists;
            synchronized (ddlLock) {
                exists = existing != null && existing.contains(normalizeName(objectName));

                if (!exists && !request.migrateDdl()) {
                    String reason = "target object does not exist (DDL migration disabled)";
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.failed", "%s failed: %s"), displayName, reason));
                    return new ItemResult(object, ItemStatus.FAILED, 0, reason, null);
                }
                if (exists && request.overwrite() && request.migrateDdl()) {
                    String dropSql = buildDropSql(ctx, object);
                    if (dropSql == null) {
                        // PostgreSQL 等 DROP TRIGGER 需要 ON <table>，预取不到宿主表则无法安全 DROP
                        String reason = "cannot resolve trigger's table for DROP";
                        log(ctx.listener, String.format(
                                I18n.t("migration.log.failed", "%s failed: %s"), displayName, reason));
                        return new ItemResult(object, ItemStatus.FAILED, 0, reason, null);
                    }
                    executeTargetStatement(ctx, ws, dropSql);
                    commitTarget(ctx, ws);
                    if (existing != null) {
                        existing.remove(normalizeName(objectName));
                    }
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.drop", "%s dropped in target"), displayName));
                }
                if (request.migrateDdl()) {
                    String script = buildDdlScript(ctx, ws, object);
                    executeScript(ctx, ws, script);
                    commitTarget(ctx, ws);
                    if (existing != null) {
                        existing.add(normalizeName(objectName));
                    }
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.ddl_ok", "%s structure created"), displayName));
                }
            }
            if (request.migrateData() && kind == MigrationObjectRef.Kind.TABLE) {
                boolean recreated = exists && request.overwrite() && request.migrateDdl();
                if (!recreated && request.truncateTable()) {
                    // 勾选"清空表"才先清目标表：优先方言 TRUNCATE，方言不支持时退化为 DELETE FROM；
                    // 未勾选时直接追加复制，不再先 DELETE
                    String truncateSql = ctx.targetPlatform.truncateTableSql(objectName);
                    executeTargetStatement(ctx, ws, truncateSql != null && !truncateSql.isBlank()
                            ? truncateSql : "DELETE FROM " + objectName);
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.truncate_ok", "%s target table cleared"), displayName));
                }
                copyData(ctx, ws, object, rowsCopied);
                commitTarget(ctx, ws);
                log(ctx.listener, String.format(
                        I18n.t("migration.log.data_ok", "%s data migrated, %d rows copied"),
                        displayName, rowsCopied[0]));
            }
            return new ItemResult(object, ItemStatus.SUCCESS, rowsCopied[0], null, null);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            rollbackTargetQuietly(ctx, ws);
            // SqlFailedException 解包：取原始错误信息 + 出错 SQL
            String errorSql = null;
            if (e instanceof SqlFailedException sqlFailed) {
                errorSql = sqlFailed.getSql();
                if (sqlFailed.getCause() instanceof Exception cause) {
                    e = cause;
                }
            }
            String reason = errorMessage(e);
            log(ctx.listener, String.format(
                    I18n.t("migration.log.failed", "%s failed: %s"), displayName, reason));
            return new ItemResult(object, ItemStatus.FAILED, rowsCopied[0], reason, errorSql);
        }
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
        if (object.kind() != MigrationObjectRef.Kind.TABLE
                || (ctx.sameFamily && !applyMapping && !applyGlobal)) {
            // 原生 DDL 按 kind 分派回放：同族全保真；跨族非表对象尽力回放（不兼容由目标库报错记 FAILED）
            DdlRepository ddl = ctx.resolver.ddl(ws.sourceSessionConnect);
            return switch (object.kind()) {
                case TABLE -> ddl.printTable(ws.sourceConn, objectName);
                case VIEW -> ddl.printView(ws.sourceConn, objectName);
                case SEQUENCE -> ddl.printSequence(ws.sourceConn, objectName);
                case SYNONYM -> ddl.printSynonym(ws.sourceConn, objectName);
                case TRIGGER -> ddl.printTrigger(ws.sourceConn, objectName);
                case FUNCTION -> ddl.printFunction(ws.sourceConn, objectName);
                case PROCEDURE -> ddl.printProcedure(ws.sourceConn, objectName);
                case PACKAGE -> ddl.printPackage(ws.sourceConn, objectName);
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
        // 跨族或应用自定义映射（逐表/全局）的表：TypeMapper 类型映射建表
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
                ctx.request.source().getDbtype(), ctx.request.target().getDbtype(),
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
     * （PostgreSQL 需要 ON &lt;table&gt;，表名来自目标端预取映射；查不到返回 null，由调用方记 FAILED）。
     */
    private String buildDropSql(MigrationContext ctx, MigrationObjectRef object) {
        if (object.kind() == MigrationObjectRef.Kind.TRIGGER) {
            String tableName = ctx.existingTriggerTables.get(normalizeName(object.name()));
            if (tableName == null || tableName.isBlank()) {
                return null;
            }
            return ctx.targetPlatform.dropTriggerSql(object.name(), tableName);
        }
        return ctx.targetPlatform.dropObjectSql(dropObjectType(object.kind()), object.name());
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
            case ALL -> throw new IllegalArgumentException("wildcard object has no drop type");
        };
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

    private void copyData(MigrationContext ctx, WorkerSession ws, MigrationObjectRef table, long[] rowsCopied) throws Exception {
        String tableName = table.name();
        ArrayList<ColumnsInfo> columns = ctx.sourceMeta.getColumns(ws.sourceConn, tableName);
        if (columns == null || columns.isEmpty()) {
            return;
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
        StringBuilder columnList = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columnCount; i++) {
            ColumnsInfo column = columns.get(i);
            types[i] = TypeMapper.normalize(ctx.request.source().getDbtype(), column);
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
        emitDataProgress(ctx, table, totalRows, 0);

        // 出错 SQL 归因：查询打开前按 SELECT 计，打开后按 INSERT 计
        String failingSql = selectSql;
        try {
            try (PreparedStatement selectStmt = ws.sourceConn.prepareStatement(
                         selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                 PreparedStatement insertStmt = ws.targetConn.prepareStatement(insertSql)) {
                try {
                    selectStmt.setFetchSize(BATCH_SIZE);
                } catch (Exception e) {
                    log.trace("setFetchSize not supported", e);
                }
                ctx.sessions.track(selectStmt);
                ctx.backSqlTask.setStmt(selectStmt);
                long rows = 0;
                int batchCount = 0;
                try (ResultSet rs = selectStmt.executeQuery()) {
                    failingSql = insertSql;
                    ctx.sessions.track(insertStmt);
                    ctx.backSqlTask.setStmt(insertStmt);
                    while (rs.next()) {
                        checkCancelled(ctx.backSqlTask);
                        for (int i = 0; i < columnCount; i++) {
                            bindValue(rs, insertStmt, i + 1, types[i]);
                        }
                        insertStmt.addBatch();
                        rows++;
                        batchCount++;
                        if (batchCount >= BATCH_SIZE) {
                            insertStmt.executeBatch();
                            commitTarget(ctx, ws);
                            batchCount = 0;
                            rowsCopied[0] = rows;
                            emitDataProgress(ctx, table, totalRows, rows);
                        }
                    }
                    if (batchCount > 0) {
                        insertStmt.executeBatch();
                        commitTarget(ctx, ws);
                    }
                    rowsCopied[0] = rows;
                    emitDataProgress(ctx, table, totalRows, rows);
                } finally {
                    ctx.sessions.untrack(insertStmt);
                    ctx.sessions.untrack(selectStmt);
                    ctx.backSqlTask.setStmt(null);
                }
            }
        } catch (SQLException e) {
            throw new SqlFailedException(failingSql, e);
        }
    }

    /** 按 TypeMapper.normalize 的 GenericType 绑值；NULL（rs.wasNull）→ setNull(Types.OTHER)。 */
    private static void bindValue(ResultSet rs,
                                  PreparedStatement ps,
                                  int index,
                                  TypeMapper.GenericType type) throws SQLException {
        switch (type == null ? TypeMapper.GenericType.OTHER : type) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL -> {
                java.math.BigDecimal value = rs.getBigDecimal(index);
                if (rs.wasNull()) {
                    ps.setNull(index, Types.OTHER);
                } else {
                    ps.setBigDecimal(index, value);
                }
            }
            case FLOAT, DOUBLE -> {
                double value = rs.getDouble(index);
                if (rs.wasNull()) {
                    ps.setNull(index, Types.OTHER);
                } else {
                    ps.setDouble(index, value);
                }
            }
            case BINARY, BLOB -> {
                byte[] value = rs.getBytes(index);
                if (rs.wasNull()) {
                    ps.setNull(index, Types.OTHER);
                } else {
                    ps.setBytes(index, value);
                }
            }
            case DATE, TIME, DATETIME, TIMESTAMP -> {
                Timestamp value = rs.getTimestamp(index);
                if (rs.wasNull()) {
                    ps.setNull(index, Types.OTHER);
                } else {
                    ps.setTimestamp(index, value);
                }
            }
            default -> {
                String value = rs.getString(index);
                if (rs.wasNull()) {
                    ps.setNull(index, Types.OTHER);
                } else {
                    ps.setString(index, value);
                }
            }
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

    /**
     * 按请求中出现的 kind 预取目标端对象名集合（冲突检测），单个 kind 失败只降级该 kind。
     * TRIGGER 额外维护 name→tableName 映射，供 DROP TRIGGER ... ON &lt;table&gt; 用。
     */
    private void prefetchTargetNames(MigrationContext ctx, Connection targetConn, Connect targetSessionConnect) {
        String dbName = ctx.request.targetDatabase() != null && !ctx.request.targetDatabase().isBlank()
                ? ctx.request.targetDatabase()
                : (ctx.request.targetSchema() != null && !ctx.request.targetSchema().isBlank()
                        ? ctx.request.targetSchema()
                        : targetSessionConnect.getEffectiveCatalog());
        for (MigrationObjectRef.Kind kind : requestedKinds(ctx.request)) {
            try {
                switch (kind) {
                    case TABLE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getUserTables(targetConn, dbName)));
                    case VIEW -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getViews(targetConn, dbName)));
                    case SEQUENCE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getSequences(targetConn, dbName)));
                    case SYNONYM -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getSynonyms(targetConn, dbName)));
                    case TRIGGER -> {
                        Set<String> names = ConcurrentHashMap.newKeySet();
                        List<Trigger> triggers = ctx.targetMeta.getTriggers(targetConn, dbName);
                        if (triggers != null) {
                            for (Trigger trigger : triggers) {
                                if (trigger == null) {
                                    continue;
                                }
                                String normalized = normalizeName(trigger.getName());
                                names.add(normalized);
                                if (trigger.getTableName() != null && !trigger.getTableName().isBlank()) {
                                    ctx.existingTriggerTables.put(normalized, trigger.getTableName());
                                }
                            }
                        }
                        ctx.existingNames.put(kind, names);
                    }
                    case FUNCTION -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getFunctions(targetConn, dbName, false)));
                    case PROCEDURE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getProcedures(targetConn, dbName, false)));
                    case PACKAGE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getPackages(targetConn, dbName)));
                    case ALL -> {
                        // 通配不预取（服务不展开通配）
                    }
                }
            } catch (Exception e) {
                log.warn("prefetch target {} names failed, conflict detection degraded for this kind", kind, e);
            }
        }
    }

    /** 请求中出现的非通配 kind 集合。 */
    private static Set<MigrationObjectRef.Kind> requestedKinds(MigrationRequest request) {
        Set<MigrationObjectRef.Kind> kinds = EnumSet.noneOf(MigrationObjectRef.Kind.class);
        if (request.objects() != null) {
            for (MigrationObjectRef object : request.objects()) {
                if (object != null && !object.isWildcard()) {
                    kinds.add(object.kind());
                }
            }
        }
        return kinds;
    }

    /** 目标端已存在对象名集合：工作线程并发读写（冲突判定 + DROP/建表后增删），用并发集。 */
    private static Set<String> nameSet(List<? extends TreeData> objects) {
        Set<String> names = ConcurrentHashMap.newKeySet();
        if (objects != null) {
            for (TreeData object : objects) {
                if (object != null) {
                    names.add(normalizeName(object.getName()));
                }
            }
        }
        return names;
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

    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.trim();
        if (normalized.length() >= 2
                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("`") && normalized.endsWith("`")))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** 携带出错 SQL 的包装异常：{@link #migrateOne} 解包后把 SQL 附到 {@link ItemResult#errorSql}。 */
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

    /** 迁移上下文：跨方法共享的执行期状态（字段均只读或并发安全；连接在各 {@link WorkerSession} 上）。 */
    private static final class MigrationContext {
        MigrationRequest request;
        ProgressListener listener;
        DatabasePlatformResolver resolver;
        BackgroundSqlTask backSqlTask;
        SessionHolder sessions;
        MetadataRepository sourceMeta;
        MetadataRepository targetMeta;
        boolean sameFamily;
        DatabasePlatform targetPlatform;
        SqlParser targetParser;
        /** kind → 目标端已存在对象名集合（normalizeName 后，并发集）。 */
        Map<MigrationObjectRef.Kind, Set<String>> existingNames = new EnumMap<>(MigrationObjectRef.Kind.class);
        /** 目标端触发器名（normalizeName 后）→ 宿主表名，供 DROP TRIGGER ... ON table 用。 */
        Map<String, String> existingTriggerTables = new ConcurrentHashMap<>();
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

        WorkerSession(MigrationContext ctx) throws Exception {
            this.ctx = ctx;
            Connect targetSessionConnect = buildTargetSessionConnect(ctx.request, ctx.resolver);
            targetConn = BackgroundSqlService.getConnectionService()
                    .getConnectionWithSessionInit(targetSessionConnect);
            if (targetConn == null) {
                throw new SQLException("cannot open target connection");
            }
            ctx.sessions.track(targetConn);
            targetTransactional = tryDisableAutoCommit(targetConn);
        }

        /** 确保源连接对应给定分组：同组复用，跨组关闭重建。 */
        void ensureSource(GroupKey group) throws Exception {
            if (sourceConn != null && Objects.equals(sourceGroup, group)) {
                return;
            }
            closeSource();
            Connect sessionConnect = buildSourceSessionConnect(ctx.request.source(), group, ctx.resolver);
            sourceConn = BackgroundSqlService.getConnectionService()
                    .getConnectionWithSessionInit(sessionConnect);
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
            ctx.sessions.untrack(targetConn);
            SessionHolder.closeQuietly(targetConn);
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
            for (Statement stmt : statements) {
                try {
                    stmt.cancel();
                } catch (Exception ignored) {
                }
            }
            for (Connection conn : connections) {
                abortQuietly(conn);
            }
        }

        void closeAll() {
            for (Connection conn : connections) {
                closeQuietly(conn);
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
