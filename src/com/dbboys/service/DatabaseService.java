package com.dbboys.service;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.DdlRepository;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.i18n.I18n;
import com.dbboys.util.BackgroundSqlUtil;
import com.dbboys.util.ConnectionPropertyUtil;
import com.dbboys.util.SqlParserUtil;
import com.dbboys.vo.Connect;
import com.dbboys.vo.BackgroundSqlTask;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.Sql;
import com.dbboys.vo.Table;
import com.dbboys.vo.UpdateResult;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.util.AlertUtil;

public class DatabaseService implements MetaObjectService {
    private static final Logger log = LogManager.getLogger(DatabaseService.class);
    private final DatabasePlatformResolver platformResolver;

    public DatabaseService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public DatabaseService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    public List<String> getStorageSpacesForCreateDatabase(Connect connect) throws SQLException {
        return platformResolver.metadata(connect).getStorageSpacesForCreateDatabase(connect.getConn());
    }

    public List<Database> getDatabases(Connect connect) throws SQLException {
        return platformResolver.metadata(connect).getDatabases(connect.getConn());
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> platformResolver.ddl(connect).printDatabase(conn, objectName);
    }

    public String exportDatabaseDdl(Connect connect, Database database) throws Exception {
        String bootstrap = buildBootstrapHeader(connect, database);
        return bootstrap + withMetaSession(connect, database,
                conn -> platformResolver.ddl(connect).printDatabase(conn, database.getName()));
    }

    public String exportDatabaseDdl(Connect connect, Database database, BiConsumer<Long, Long> progressListener) throws Exception {
        String bootstrap = buildBootstrapHeader(connect, database);
        return bootstrap + withMetaSession(connect, database, conn -> {
            var ddlRepository = platformResolver.ddl(connect);
            long total = ddlRepository.countDatabaseExportItems(conn, database.getName());
            if (progressListener != null) {
                progressListener.accept(0L, total);
            }
            LongConsumer progressCallback = (progressListener != null && total > 0)
                    ? completed -> {progressListener.accept(completed, total);
                        }
                    : null;
            return ddlRepository.printDatabase(conn, database.getName(), progressCallback);
        });
    }

    private String buildBootstrapHeader(Connect connect, Database database) {
        try {
            var platform = platformResolver.getPlatform(connect.getDbtype());
            if (platform != null && platform.canCreateDatabase()) {
                return platform.buildBootstrapSql(database);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public DdlRepository.DatabaseDdlParts exportDatabaseDdlParts(Connect connect,
                                                                 Database database,
                                                                 BiConsumer<Long, Long> progressListener) throws Exception {
        return withMetaSession(connect, database, conn -> {
            var ddlRepository = platformResolver.ddl(connect);
            long total = ddlRepository.countDatabaseExportItems(conn, database.getName());
            if (progressListener != null) {
                progressListener.accept(0L, total);
            }
            LongConsumer progressCallback = (progressListener != null && total > 0)
                    ? completed -> progressListener.accept(completed, total)
                    : null;
            return ddlRepository.exportDatabaseDdlParts(conn, database.getName(), progressCallback);
        });
    }

    public List<Table> getUserTables(Connect connect, Database database) throws Exception {
        return withMetaSession(connect, database,
                conn -> platformResolver.metadata(connect).getUserTables(conn, database.getName()));
    }

    public DdlRepository.DatabaseDdlParts exportDatabaseDdlPartsWithNewConnection(Connect connect,
                                                                                  Database database,
                                                                                  BiConsumer<Long, Long> progressListener) throws Exception {
        Connect sessionConnect = buildDatabaseSessionConnect(connect, database);
        try (Connection conn = connectionService().getConnectionWithSessionInit(sessionConnect)) {
            var ddlRepository = platformResolver.ddl(sessionConnect);
            long total = ddlRepository.countDatabaseExportItems(conn, database.getName());
            if (progressListener != null) {
                progressListener.accept(0L, total);
            }
            LongConsumer progressCallback = (progressListener != null && total > 0)
                    ? completed -> progressListener.accept(completed, total)
                    : null;
            return ddlRepository.exportDatabaseDdlParts(conn, database.getName(), progressCallback);
        }
    }

    public List<Table> getUserTablesWithNewConnection(Connect connect, Database database) throws Exception {
        Connect sessionConnect = buildDatabaseSessionConnect(connect, database);
        try (Connection conn = connectionService().getConnectionWithSessionInit(sessionConnect)) {
            return platformResolver.metadata(sessionConnect).getUserTables(conn, database.getName());
        }
    }

    private Connect buildDatabaseSessionConnect(Connect connect, Database database) {
        Connect sessionConnect = new Connect(connect);
        if (database == null) {
            return sessionConnect;
        }
        platformResolver.requirePlatform(sessionConnect).connection().setSessionDatabase(sessionConnect, database.getName());
        ConnectionPropertyUtil.applySupportedConnectionProperty(
                connectionService(),
                platformResolver,
                sessionConnect,
                "DB_LOCALE",
                database.getDbLocale()
        );
        if (database.getDbLog() != null && "nolog".equalsIgnoreCase(database.getDbLog().trim())) {
            ConnectionPropertyUtil.applySupportedConnectionProperty(
                    connectionService(),
                    platformResolver,
                    sessionConnect,
                    "IFX_ISOLATION_LEVEL",
                    ""
            );
        }
        return sessionConnect;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<String> result = new ArrayList<>();
        objectList.setItems(result);
        objectList.setSuccess(false);

        Database database = repo.getDatabaseInfo(conn, databaseName);
        objectList.setInfo(database);

        boolean filterType = repo.hasSysProcTypeColumn(conn);

        int sysCount = repo.getSystemTablesCount(conn);
        String sysSize = repo.getSystemTablesSize(conn, databaseName);
        result.add(sysSize == null ? (sysCount + "个") : (sysCount + "个/" + sysSize));

        int tableCount = repo.getUserTablesCount(conn);
        String tableSize = repo.getUserTablesSize(conn, databaseName);
        result.add(tableSize == null ? (tableCount + "个") : (tableCount + "个/" + tableSize));

        int viewCount = repo.getViewCount(conn);
        result.add(viewCount + "个");

        int indexCount = repo.getIndexCount(conn);
        String indexSize = repo.getIndexSize(conn);
        result.add(indexSize == null ? (indexCount + "个") : (indexCount + "个/" + indexSize));

        int seqCount = repo.getSequenceCount(conn);
        result.add(seqCount + "个");

        int synCount = repo.getSynonymCount(conn);
        result.add(synCount + "个");

        int triggerCount = repo.getTriggerCount(conn);
        result.add(triggerCount + "个");

        int funcCount = repo.getFunctionCount(conn, filterType);
        result.add(funcCount + "个");

        int procCount = repo.getProcedureCount(conn, filterType);
        result.add(procCount + "个");

        var platform = platformResolver.requirePlatform(connect);
        if (platform.supportsPackages()) {
            int pkgCount = repo.getPackageCount(conn);
            result.add(pkgCount + "个");
        }
        if (platform.supportsObjectTypesFolder()) {
            result.add(repo.getObjectTypeCount(conn, databaseName) + "个");
        }
        if (platform.supportsObjectQueuesFolder()) {
            result.add(repo.getQueueCount(conn, databaseName) + "个");
        }
        if (platform.supportsSchedulerJobsFolder()) {
            result.add(repo.getSchedulerJobCount(conn, databaseName) + "个");
        }
        if (platform.supportsRecycleBinFolder()) {
            result.add(repo.getRecycleBinCount(conn) + "个");
        }

        objectList.setSuccess(true);
        return objectList;
    }

    public void updateStatistics(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

    public void importSqlScript(Connect connect, File file, Runnable onSucceededUi) {
        if (connect == null || file == null) {
            return;
        }

        BackgroundSqlTask backSqlTask = new BackgroundSqlTask();
        String importSummary = I18n.t("metadata.import_sql.task.summary", "导入SQL脚本 %s")
                .formatted(file.getName());
        AtomicReference<CompletableFuture<Integer>> countFutureRef = new AtomicReference<>();

        Task<Integer> bgTask = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                long beginTime = System.currentTimeMillis();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                UpdateResult updateResult = new UpdateResult();
                updateResult.setConnectId(connect.getId());
                updateResult.setDatabase(connect.getEffectiveDatabase());
                updateResult.setUpdateSql(importSummary);
                updateResult.setStartTime(sdf.format(beginTime));

                backSqlTask.setConnect(connect);
                backSqlTask.setBeginTime(sdf.format(beginTime));
                backSqlTask.setConnectName(connect.getName());
                backSqlTask.setDatabaseName(connect.getDatabase());
                backSqlTask.setSql(importSummary);
                backSqlTask.setProgress(formatImportSqlExecuted(0));
                BackgroundSqlUtil.backSqlTaskList.add(backSqlTask);
                BackgroundSqlUtil.updateBackSqlUIOnStart();

                try {
                    String scriptText = stripLeadingBom(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                    if (scriptText.isBlank()) {
                        throw new IOException(I18n.t("metadata.import_sql.error.empty_file", "SQL脚本为空：%s")
                                .formatted(file.getName()));
                    }

                    AtomicInteger executedCount = new AtomicInteger();
                    AtomicInteger totalStatements = new AtomicInteger(-1);
                    CompletableFuture<Integer> countFuture = startImportSqlCountTask(
                            scriptText,
                            backSqlTask,
                            executedCount,
                            totalStatements
                    );
                    countFutureRef.set(countFuture);

                    int affectedRows = executeStatements(
                            connect,
                            scriptText,
                            file.getName(),
                            backSqlTask,
                            executedCount,
                            totalStatements
                    );
                    long endTime = System.currentTimeMillis();
                    updateResult.setAffectedRows(affectedRows);
                    updateResult.setElapsedTime(String.format("%.3f", (endTime - beginTime) / 1000.0) + " sec");
                    updateResult.setEndTime(sdf.format(endTime));
                    updateResult.setMark(I18n.t("backsql.history.mark.ui_task", "界面操作任务,独立事务"));
                    LocalDbRepository.saveSqlHistory(updateResult);
                    return affectedRows;
                } catch (CancellationException e) {
                    throw e;
                } catch (SQLException e) {
                    BackgroundSqlUtil.handleBackgroundSqlError(backSqlTask, e);
                    throw e;
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message == null || message.isBlank()) {
                        message = I18n.t("metadata.import_sql.error.unknown", "导入SQL脚本失败，请检查脚本内容或数据库连接");
                    }
                    showImportError(message);
                    throw e;
                } finally {
                    CompletableFuture<Integer> countFuture = countFutureRef.getAndSet(null);
                    if (countFuture != null && !countFuture.isDone()) {
                        countFuture.cancel(true);
                    }
                    backSqlTask.setStmt(null);
                    backSqlTask.setConnection(null);
                    BackgroundSqlUtil.backSqlTaskList.remove(backSqlTask);
                    BackgroundSqlUtil.updateBackSqlUIOnFinish();
                }
            }
        };

        bgTask.setOnSucceeded(event -> {
            if (onSucceededUi != null) {
                onSucceededUi.run();
            }
        });
        backSqlTask.setCancelAction(() -> {
            bgTask.cancel(true);
            CompletableFuture<Integer> countFuture = countFutureRef.get();
            if (countFuture != null) {
                countFuture.cancel(true);
            }
        });
        backSqlTask.setFuture(BackgroundSqlUtil.backSqlExecutor.submit(bgTask));
    }

    public int importSqlScriptSync(Connect connect, File file, BackgroundSqlTask backSqlTask) throws Exception {
        if (connect == null || file == null) {
            return 0;
        }

        BackgroundSqlTask effectiveTask = backSqlTask == null ? new BackgroundSqlTask() : backSqlTask;
        String scriptText = stripLeadingBom(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        if (scriptText.isBlank()) {
            throw new IOException(I18n.t("metadata.import_sql.error.empty_file", "SQL脚本为空：%s")
                    .formatted(file.getName()));
        }

        AtomicInteger executedCount = new AtomicInteger();
        AtomicInteger totalStatements = new AtomicInteger(Math.max(0, SqlParserUtil.countExecutableStatements(scriptText)));
        updateImportSqlProgress(effectiveTask, 0, totalStatements.get());
        return executeStatements(connect, scriptText, file.getName(), effectiveTask, executedCount, totalStatements);
    }

    private CompletableFuture<Integer> startImportSqlCountTask(String scriptText,
                                                               BackgroundSqlTask backSqlTask,
                                                               AtomicInteger executedCount,
                                                               AtomicInteger totalStatements) {
        CompletableFuture<Integer> countFuture = CompletableFuture.supplyAsync(
                () -> SqlParserUtil.countExecutableStatements(scriptText),
                BackgroundSqlUtil.backSqlExecutor
        );
        countFuture.whenComplete((count, throwable) -> {
            if (throwable != null) {
                if (!isImportSqlCountCancelled(throwable)) {
                    log.warn("failed to count import sql statements", throwable);
                }
                return;
            }
            int safeTotal = Math.max(0, count == null ? 0 : count);
            totalStatements.set(safeTotal);
            if (safeTotal > 0) {
                BackgroundSqlUtil.updateTaskProgress(
                        backSqlTask,
                        formatImportSqlProgress(executedCount.get(), safeTotal)
                );
            }
        });
        return countFuture;
    }

    private int executeStatements(Connect connect,
                                  String scriptText,
                                  String scriptName,
                                  BackgroundSqlTask backSqlTask,
                                  AtomicInteger executedCount,
                                  AtomicInteger totalStatements) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
            if (conn == null) {
                throw new IOException(I18n.t("metadata.import_sql.error.unknown", "导入SQL脚本失败，请检查脚本内容或数据库连接"));
            }
            backSqlTask.setConnection(conn);

            int affectedRows = 0;
            Sql currentSql = new Sql();

            for (SqlParserUtil.Segment segment : SqlParserUtil.split(scriptText)) {
                String remainingChunk = segment.getText();
                while (remainingChunk != null && !remainingChunk.isBlank()) {
                    checkImportSqlCancelled(backSqlTask);

                    currentSql = SqlParserUtil.modifySql(currentSql, remainingChunk);
                    if (!currentSql.getSqlEnd()) {
                        break;
                    }

                    String statement = currentSql.getSqlstr();
                    if (SqlParserUtil.isExecutableStatement(statement)) {
                        affectedRows += executeSingleStatement(conn, statement.trim(), backSqlTask);
                        updateImportSqlProgress(backSqlTask, executedCount.incrementAndGet(), totalStatements.get());
                    }

                    remainingChunk = currentSql.getSqlRemainder();
                    currentSql = new Sql();
                }
            }

            if (SqlParserUtil.isExecutableStatement(currentSql.getSqlstr())) {
                checkImportSqlCancelled(backSqlTask);
                affectedRows += executeSingleStatement(conn, currentSql.getSqlstr().trim(), backSqlTask);
                updateImportSqlProgress(backSqlTask, executedCount.incrementAndGet(), totalStatements.get());
            }

            if (executedCount.get() == 0) {
                throw new IOException(I18n.t("metadata.import_sql.error.no_statements", "SQL脚本中没有可执行语句：%s")
                        .formatted(scriptName));
            }
            return affectedRows;
        }
    }

    private int executeSingleStatement(Connection conn, String sql, BackgroundSqlTask backSqlTask) throws Exception {
        String upper = sql.stripLeading().toUpperCase();
        if (!(upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) && sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        try (Statement stmt = conn.createStatement()) {
            backSqlTask.setStmt(stmt);
            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet ignored = stmt.getResultSet()) {
                    // Consume and close result sets; import script runs headlessly.
                }
            }
            int count = stmt.getUpdateCount();
            return Math.max(count, 0);
        } catch (SQLException e) {
            backSqlTask.setSql(sql);
            throw e;
        } finally {
            backSqlTask.setStmt(null);
        }
    }

    private void checkImportSqlCancelled(BackgroundSqlTask backSqlTask) {
        if (backSqlTask.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("import sql script cancelled");
        }
    }

    private String stripLeadingBom(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private String formatImportSqlExecuted(int executedCount) {
        int safeExecutedCount = Math.max(0, executedCount);
        return I18n.t("metadata.import_sql.task.executed", "已执行 %d 条").formatted(safeExecutedCount);
    }

    private void updateImportSqlProgress(BackgroundSqlTask backSqlTask, int executedCount, int totalStatements) {
        if (totalStatements > 0) {
            BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportSqlProgress(executedCount, totalStatements));
            return;
        }
        BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportSqlExecuted(executedCount));
    }

    private String formatImportSqlProgress(int executedCount, int totalStatements) {
        int safeExecutedCount = Math.max(0, executedCount);
        int safeTotalStatements = Math.max(safeExecutedCount, Math.max(0, totalStatements));
        return safeExecutedCount + "/" + safeTotalStatements;
    }

    private boolean isImportSqlCountCancelled(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void showImportError(String message) {
        Platform.runLater(() -> AlertUtil.CustomAlert(
                I18n.t("metadata.import_sql.error.title", "导入SQL脚本失败"),
                message
        ));
    }
}
