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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对象级迁移服务：把源连接中若干对象（表/视图/序列/同义词/触发器/函数/存储过程/包）的
 * 结构（DDL）和/或数据复制到目标连接。数据复制仅支持表。
 * <p>
 * 同构判定按"DDL 方言族"：取两端 {@code resolver.ddl(connect)} 实例沿类继承链归族
 * （oracle/dameng → ORACLE，postgresql → POSTGRESQL，informix/gbase8s → INFORMIX，
 * mysql/sqlite/general-jdbc 各自成族），同族即同构——非表对象原生 DDL 直接回放；
 * 跨族仅表可走 TypeMapper 建表 + 数据复制，其余对象 SKIPPED。
 * <p>
 * 会话模型（与 {@code DatabaseService#buildDatabaseSessionConnect} 一致）：
 * 源端按 catalog(+schema) 分组，每组复制 Connect 并设置 catalog/sessionCatalog 后开一个独立后台会话；
 * 目标端按目标平台 catalogModel 设置后开一个独立会话。全程不使用主树连接。
 * <p>
 * 取消：返回的 Task 监听自身 stateProperty（CANCELLED），取消时中断当前 Statement 并 abort 源/目标活动连接；
 * 同时注册 {@link BackgroundSqlTask}（cancelAction = task.cancel），使主窗口后台任务按钮也能取消。
 * 所有 listener 回调均在工作线程触发，调用方自行切 FX 线程。
 */
public class TableMigrationService {
    private static final Logger log = LogManager.getLogger(TableMigrationService.class);
    private static final int BATCH_SIZE = 500;
    private static final String GENERAL_JDBC = "GENERAL JDBC";

    public enum ItemStatus { SUCCESS, SKIPPED, FAILED }

    public record MigrationRequest(Connect source, Connect target,
                                   String targetDatabase, String targetSchema,
                                   List<MigrationObjectRef> objects,
                                   boolean migrateDdl, boolean migrateData, boolean overwrite,
                                   java.util.Map<String, TableMapping> mappings) {
        /** 兼容旧调用：无自定义数据映射。 */
        public MigrationRequest(Connect source, Connect target,
                                String targetDatabase, String targetSchema,
                                List<MigrationObjectRef> objects,
                                boolean migrateDdl, boolean migrateData, boolean overwrite) {
            this(source, target, targetDatabase, targetSchema, objects,
                    migrateDdl, migrateData, overwrite, java.util.Map.of());
        }
    }

    public record ItemResult(MigrationObjectRef object, ItemStatus status, long rowsCopied, String message) {}

    public record MigrationSummary(java.util.List<ItemResult> results, boolean cancelled) {
        public long count(ItemStatus status) { return results.stream().filter(r -> r.status() == status).count(); }
    }

    public interface ProgressListener {
        void onLog(String line);                                       // 工作线程回调，调用方自行切 FX 线程
        void onItemStart(int index, int total, String tableName);      // index 从 0 开始
        void onItemDone(int index, int total, ItemResult result);      // index 从 0 开始
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

        List<ItemResult> results = new ArrayList<>();
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
            // 2. 目标端独立会话
            Connect targetSessionConnect = buildTargetSessionConnect(request, ctx.resolver);
            Connection targetConn = BackgroundSqlService.getConnectionService()
                    .getConnectionWithSessionInit(targetSessionConnect);
            if (targetConn == null) {
                throw new SQLException("cannot open target connection");
            }
            sessions.target.set(targetConn);
            backSqlTask.setConnection(targetConn);
            ctx.targetConn = targetConn;
            ctx.targetTransactional = tryDisableAutoCommit(targetConn);
            ctx.targetPlatform = ctx.resolver.requirePlatform(targetSessionConnect);
            ctx.targetParser = ctx.targetPlatform.parser();

            // 3. 按请求中的 kind 预取目标已存在对象名集合（冲突检测；触发器额外记录宿主表）
            prefetchTargetNames(ctx, targetSessionConnect);

            // 源端按 catalog(+schema) 分组，保持首次出现顺序
            Map<GroupKey, List<MigrationObjectRef>> groups = groupObjects(request.objects());

            int index = 0;
            for (Map.Entry<GroupKey, List<MigrationObjectRef>> entry : groups.entrySet()) {
                checkCancelled(backSqlTask);
                Connect sourceSessionConnect = buildSourceSessionConnect(request.source(), entry.getKey(), ctx.resolver);
                try (Connection sourceConn = BackgroundSqlService.getConnectionService()
                        .getConnectionWithSessionInit(sourceSessionConnect)) {
                    if (sourceConn == null) {
                        throw new SQLException("cannot open source connection");
                    }
                    sessions.source.set(sourceConn);
                    ctx.sourceConn = sourceConn;
                    ctx.sourceSessionConnect = sourceSessionConnect;
                    for (MigrationObjectRef object : entry.getValue()) {
                        checkCancelled(backSqlTask);
                        int current = index++;
                        if (listener != null) {
                            listener.onItemStart(current, total, object.displayName());
                        }
                        ItemResult result = migrateOne(ctx, object);
                        results.add(result);
                        if (listener != null) {
                            listener.onItemDone(current, total, result);
                        }
                    }
                } finally {
                    sessions.source.set(null);
                    ctx.sourceConn = null;
                }
            }
            return new MigrationSummary(results, false);
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

    /** 单对象迁移；失败记录后由调用方继续下一对象，不中断整体。 */
    private ItemResult migrateOne(MigrationContext ctx, MigrationObjectRef object) {
        MigrationRequest request = ctx.request;
        String displayName = object.displayName();
        String objectName = object.name();
        long[] rowsCopied = {0};
        try {
            // 通配防御：正常情况下 runner 已把通配（整节点 kind=ALL 或类型级 name=null）展开，服务不展开
            if (object.needsExpansion()) {
                String message = String.format(
                        I18n.t("migration.log.skip_wildcard",
                                "Wildcard object %s was not expanded before migration, skipped"),
                        displayName);
                log(ctx.listener, message);
                return new ItemResult(object, ItemStatus.SKIPPED, 0, message);
            }
            MigrationObjectRef.Kind kind = object.kind();
            // 跨族仅表可迁移（TypeMapper 建表 + 数据复制），其余对象跳过
            if (!ctx.sameFamily && kind != MigrationObjectRef.Kind.TABLE) {
                String message = String.format(
                        I18n.t("migration.log.skip_cross_family",
                                "Cross-database migration does not support %s object: %s, skipped"),
                        kindLabel(kind), displayName);
                log(ctx.listener, message);
                return new ItemResult(object, ItemStatus.SKIPPED, 0, message);
            }
            // PACKAGE 需要源端能打印、目标端能执行
            if (kind == MigrationObjectRef.Kind.PACKAGE && request.migrateDdl() && !packagesSupported(ctx)) {
                String message = String.format(
                        I18n.t("migration.log.skip_unsupported",
                                "%s object %s is not supported by source or target platform, skipped"),
                        kindLabel(kind), displayName);
                log(ctx.listener, message);
                return new ItemResult(object, ItemStatus.SKIPPED, 0, message);
            }

            Set<String> existing = ctx.existingNames.get(kind);
            boolean exists = existing != null && existing.contains(normalizeName(objectName));

            if (exists && !request.overwrite()) {
                String message = String.format(
                        I18n.t("migration.log.skip_exists", "%s already exists in target, skipped"), displayName);
                log(ctx.listener, message);
                return new ItemResult(object, ItemStatus.SKIPPED, 0, message);
            }
            if (!exists && !request.migrateDdl()) {
                String reason = "target object does not exist (DDL migration disabled)";
                log(ctx.listener, String.format(
                        I18n.t("migration.log.failed", "%s failed: %s"), displayName, reason));
                return new ItemResult(object, ItemStatus.FAILED, 0, reason);
            }
            if (exists && request.overwrite() && request.migrateDdl()) {
                String dropSql = buildDropSql(ctx, object);
                if (dropSql == null) {
                    // PostgreSQL 等 DROP TRIGGER 需要 ON <table>，预取不到宿主表则无法安全 DROP
                    String reason = "cannot resolve trigger's table for DROP";
                    log(ctx.listener, String.format(
                            I18n.t("migration.log.failed", "%s failed: %s"), displayName, reason));
                    return new ItemResult(object, ItemStatus.FAILED, 0, reason);
                }
                executeTargetStatement(ctx, dropSql);
                commitTarget(ctx);
                if (existing != null) {
                    existing.remove(normalizeName(objectName));
                }
                log(ctx.listener, String.format(
                        I18n.t("migration.log.drop", "%s dropped in target"), displayName));
            }
            if (request.migrateDdl()) {
                String script = buildDdlScript(ctx, object);
                executeScript(ctx, script);
                commitTarget(ctx);
                if (existing != null) {
                    existing.add(normalizeName(objectName));
                }
                log(ctx.listener, String.format(
                        I18n.t("migration.log.ddl_ok", "%s structure created"), displayName));
            }
            if (request.migrateData() && kind == MigrationObjectRef.Kind.TABLE) {
                if (exists && request.overwrite() && !request.migrateDdl()) {
                    // 不重建表时的覆盖语义：先清空目标表
                    executeTargetStatement(ctx, "DELETE FROM " + objectName);
                }
                copyData(ctx, object, rowsCopied);
                commitTarget(ctx);
                log(ctx.listener, String.format(
                        I18n.t("migration.log.data_ok", "%s data migrated, %d rows copied"),
                        displayName, rowsCopied[0]));
            }
            return new ItemResult(object, ItemStatus.SUCCESS, rowsCopied[0], null);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            rollbackTargetQuietly(ctx);
            String reason = errorMessage(e);
            log(ctx.listener, String.format(
                    I18n.t("migration.log.failed", "%s failed: %s"), displayName, reason));
            return new ItemResult(object, ItemStatus.FAILED, rowsCopied[0], reason);
        }
    }

    // ==================================================================
    // DDL
    // ==================================================================

    private String buildDdlScript(MigrationContext ctx, MigrationObjectRef object) throws Exception {
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
        if (ctx.sameFamily && !applyMapping && !applyGlobal) {
            // 同族迁移：原生 DDL 全保真，按 kind 分派
            DdlRepository ddl = ctx.resolver.ddl(ctx.sourceSessionConnect);
            return switch (object.kind()) {
                case TABLE -> ddl.printTable(ctx.sourceConn, objectName);
                case VIEW -> ddl.printView(ctx.sourceConn, objectName);
                case SEQUENCE -> ddl.printSequence(ctx.sourceConn, objectName);
                case SYNONYM -> ddl.printSynonym(ctx.sourceConn, objectName);
                case TRIGGER -> ddl.printTrigger(ctx.sourceConn, objectName);
                case FUNCTION -> ddl.printFunction(ctx.sourceConn, objectName);
                case PROCEDURE -> ddl.printProcedure(ctx.sourceConn, objectName);
                case PACKAGE -> ddl.printPackage(ctx.sourceConn, objectName);
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
        ArrayList<ColumnsInfo> columns = ctx.sourceMeta.getColumns(ctx.sourceConn, objectName);
        List<String> primaryKeyColumns = ctx.sourceMeta.getPrimaryKeyColumns(ctx.sourceConn, objectName);
        String tableComment = null;
        try {
            tableComment = ctx.sourceMeta.getTableComment(ctx.sourceConn, objectName);
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
    private static boolean packagesSupported(MigrationContext ctx) {
        try {
            return ctx.resolver.requirePlatform(ctx.sourceSessionConnect).supportsPackages()
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
    private void executeScript(MigrationContext ctx, String scriptText) throws Exception {
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
                    executeTargetStatement(ctx, statement.trim());
                }
                remainingChunk = currentSql.getSqlRemainder();
                currentSql = new Sql();
            }
        }
        if (SqlParserUtil.isExecutableStatement(currentSql.getSqlstr())) {
            checkCancelled(ctx.backSqlTask);
            executeTargetStatement(ctx, currentSql.getSqlstr().trim());
        }
    }

    // ==================================================================
    // 数据复制
    // ==================================================================

    private void copyData(MigrationContext ctx, MigrationObjectRef table, long[] rowsCopied) throws Exception {
        String tableName = table.name();
        ArrayList<ColumnsInfo> columns = ctx.sourceMeta.getColumns(ctx.sourceConn, tableName);
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
        String insertSql = "INSERT INTO " + tableName + " (" + columnList + ") VALUES (" + placeholders + ")";

        try (PreparedStatement selectStmt = ctx.sourceConn.prepareStatement(
                     selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             PreparedStatement insertStmt = ctx.targetConn.prepareStatement(insertSql)) {
            try {
                selectStmt.setFetchSize(BATCH_SIZE);
            } catch (Exception e) {
                log.trace("setFetchSize not supported", e);
            }
            registerStmt(ctx, selectStmt);
            long rows = 0;
            int batchCount = 0;
            try (ResultSet rs = selectStmt.executeQuery()) {
                registerStmt(ctx, insertStmt);
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
                        commitTarget(ctx);
                        batchCount = 0;
                        rowsCopied[0] = rows;
                    }
                }
                if (batchCount > 0) {
                    insertStmt.executeBatch();
                    commitTarget(ctx);
                }
                rowsCopied[0] = rows;
            } finally {
                registerStmt(ctx, null);
            }
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
    private void prefetchTargetNames(MigrationContext ctx, Connect targetSessionConnect) {
        String dbName = ctx.request.targetDatabase() != null && !ctx.request.targetDatabase().isBlank()
                ? ctx.request.targetDatabase()
                : (ctx.request.targetSchema() != null && !ctx.request.targetSchema().isBlank()
                        ? ctx.request.targetSchema()
                        : targetSessionConnect.getEffectiveCatalog());
        for (MigrationObjectRef.Kind kind : requestedKinds(ctx.request)) {
            try {
                switch (kind) {
                    case TABLE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getUserTables(ctx.targetConn, dbName)));
                    case VIEW -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getViews(ctx.targetConn, dbName)));
                    case SEQUENCE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getSequences(ctx.targetConn, dbName)));
                    case SYNONYM -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getSynonyms(ctx.targetConn, dbName)));
                    case TRIGGER -> {
                        Set<String> names = new TreeSet<>();
                        List<Trigger> triggers = ctx.targetMeta.getTriggers(ctx.targetConn, dbName);
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
                            nameSet(ctx.targetMeta.getFunctions(ctx.targetConn, dbName, false)));
                    case PROCEDURE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getProcedures(ctx.targetConn, dbName, false)));
                    case PACKAGE -> ctx.existingNames.put(kind,
                            nameSet(ctx.targetMeta.getPackages(ctx.targetConn, dbName)));
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

    private static Set<String> nameSet(List<? extends TreeData> objects) {
        Set<String> names = new TreeSet<>();
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

    private void executeTargetStatement(MigrationContext ctx, String sql) throws SQLException {
        String execSql = sql;
        String upper = execSql.stripLeading().toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) && execSql.endsWith(";")) {
            execSql = execSql.substring(0, execSql.length() - 1).trim();
        }
        try (Statement stmt = ctx.targetConn.createStatement()) {
            registerStmt(ctx, stmt);
            stmt.execute(execSql);
        } finally {
            registerStmt(ctx, null);
        }
    }

    private void registerStmt(MigrationContext ctx, Statement stmt) {
        ctx.sessions.stmt.set(stmt);
        ctx.backSqlTask.setStmt(stmt);
    }

    private void commitTarget(MigrationContext ctx) throws SQLException {
        if (ctx.targetTransactional) {
            ctx.targetConn.commit();
        }
    }

    private void rollbackTargetQuietly(MigrationContext ctx) {
        if (ctx.targetTransactional && ctx.targetConn != null) {
            try {
                ctx.targetConn.rollback();
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

    private static void log(ProgressListener listener, String line) {
        if (listener != null) {
            listener.onLog(line);
        }
    }

    /** 迁移上下文：跨方法共享的执行期状态。 */
    private static final class MigrationContext {
        MigrationRequest request;
        ProgressListener listener;
        DatabasePlatformResolver resolver;
        BackgroundSqlTask backSqlTask;
        SessionHolder sessions;
        MetadataRepository sourceMeta;
        MetadataRepository targetMeta;
        boolean sameFamily;
        Connection sourceConn;
        Connect sourceSessionConnect;
        Connection targetConn;
        boolean targetTransactional;
        DatabasePlatform targetPlatform;
        SqlParser targetParser;
        /** kind → 目标端已存在对象名集合（normalizeName 后）。 */
        Map<MigrationObjectRef.Kind, Set<String>> existingNames = new EnumMap<>(MigrationObjectRef.Kind.class);
        /** 目标端触发器名（normalizeName 后）→ 宿主表名，供 DROP TRIGGER ... ON table 用。 */
        Map<String, String> existingTriggerTables = new HashMap<>();
    }

    /** 活动 JDBC 资源持有器：取消时中断当前语句并 abort/close 源、目标连接。 */
    private static final class SessionHolder {
        private final AtomicReference<Connection> source = new AtomicReference<>();
        private final AtomicReference<Connection> target = new AtomicReference<>();
        private final AtomicReference<Statement> stmt = new AtomicReference<>();

        void interrupt() {
            Statement current = stmt.get();
            if (current != null) {
                try {
                    current.cancel();
                } catch (Exception ignored) {
                }
            }
            abortQuietly(source.get());
            abortQuietly(target.get());
        }

        void closeAll() {
            closeQuietly(source.getAndSet(null));
            closeQuietly(target.getAndSet(null));
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
