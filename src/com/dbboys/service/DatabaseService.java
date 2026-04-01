package com.dbboys.service;

import com.dbboys.api.DdlRepositoryProvider;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.i18n.I18n;
import com.dbboys.util.BackgroundSqlUtil;
import com.dbboys.util.SqlParserUtil;
import com.dbboys.vo.Connect;
import com.dbboys.vo.BackgroundSqlTask;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.Sql;
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
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.util.AlertUtil;

public class DatabaseService implements MetaObjectService {
    private static final Logger log = LogManager.getLogger(DatabaseService.class);
    private final MetadataRepositoryProvider metadataRepositoryProvider;
    private final DdlRepositoryProvider ddlRepositoryProvider;

    public DatabaseService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class),
                com.dbboys.app.AppContext.get(DdlRepositoryProvider.class));
    }

    public DatabaseService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this(metadataRepositoryProvider, com.dbboys.app.AppContext.get(DdlRepositoryProvider.class));
    }

    public DatabaseService(MetadataRepositoryProvider metadataRepositoryProvider,
                           DdlRepositoryProvider ddlRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
        this.ddlRepositoryProvider = ddlRepositoryProvider;
    }

    public List<String> getDBspaceForCreateDatabase(Connect connect) throws SQLException {
        return metadataRepositoryProvider.metadata(connect).getDBspaceForCreateDatabase(connect.getConn());
    }

    public List<Database> getDatabases(Connect connect) throws SQLException {
        return metadataRepositoryProvider.metadata(connect).getDatabases(connect.getConn());
    }

    public List<Database> getDatabases(Connect connect, boolean useOracleSyntax) throws SQLException {
        return metadataRepositoryProvider.metadata(connect).getDatabases(connect.getConn(), useOracleSyntax);
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> ddlRepositoryProvider.ddl(connect).printDatabase(conn, objectName);
    }

    public String exportDatabaseDdl(Connect connect, Database database) throws Exception {
        return withMetaSession(connect, database,
                conn -> ddlRepositoryProvider.ddl(connect).printDatabase(conn, database.getName()));
    }

    public String exportDatabaseDdl(Connect connect, Database database, BiConsumer<Long, Long> progressListener) throws Exception {
        return withMetaSession(connect, database, conn -> {
            var ddlRepository = ddlRepositoryProvider.ddl(connect);
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

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
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

        int pkgCount = repo.getPackageCount(conn);
        result.add(pkgCount + "个");

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

        Task<Integer> bgTask = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                long beginTime = System.currentTimeMillis();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                UpdateResult updateResult = new UpdateResult();
                updateResult.setConnectId(connect.getId());
                updateResult.setDatabase(connect.getDatabase());
                updateResult.setUpdateSql(importSummary);
                updateResult.setStartTime(sdf.format(beginTime));

                backSqlTask.setConnect(connect);
                backSqlTask.setBeginTime(sdf.format(beginTime));
                backSqlTask.setConnectName(connect.getName());
                backSqlTask.setDatabaseName(connect.getDatabase());
                backSqlTask.setSql(importSummary);
                backSqlTask.setProgress(I18n.t("metadata.import_sql.task.counting", "正在统计SQL数量"));
                BackgroundSqlUtil.backSqlTaskList.add(backSqlTask);
                BackgroundSqlUtil.updateBackSqlUIOnStart();

                try {
                    String scriptText = stripLeadingBom(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                    if (scriptText.isBlank()) {
                        throw new IOException(I18n.t("metadata.import_sql.error.empty_file", "SQL脚本为空：%s")
                                .formatted(file.getName()));
                    }

                    List<String> statements = parseSqlStatements(scriptText);
                    if (statements.isEmpty()) {
                        throw new IOException(I18n.t("metadata.import_sql.error.no_statements", "SQL脚本中没有可执行语句：%s")
                                .formatted(file.getName()));
                    }

                    BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportSqlProgress(0, statements.size()));

                    int affectedRows = executeStatements(connect, statements, backSqlTask);
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
        backSqlTask.setCancelAction(() -> bgTask.cancel(true));
        backSqlTask.setFuture(BackgroundSqlUtil.backSqlExecutor.submit(bgTask));
    }

    private int executeStatements(Connect connect, List<String> statements, BackgroundSqlTask backSqlTask) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
            if (conn == null) {
                throw new IOException(I18n.t("metadata.import_sql.error.unknown", "导入SQL脚本失败，请检查脚本内容或数据库连接"));
            }
            backSqlTask.setConnection(conn);

            int affectedRows = 0;
            int total = statements.size();
            for (int i = 0; i < total; i++) {
                if (backSqlTask.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("import sql script cancelled");
                }

                String sql = statements.get(i);
                BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportSqlProgress(i + 1, total));

                try (Statement stmt = conn.createStatement()) {
                    backSqlTask.setStmt(stmt);
                    boolean hasResultSet = stmt.execute(sql);
                    if (hasResultSet) {
                        try (ResultSet ignored = stmt.getResultSet()) {
                            // Consume and close result sets; import script runs headlessly.
                        }
                    }
                    int count = stmt.getUpdateCount();
                    if (count > 0) {
                        affectedRows += count;
                    }
                } catch (SQLException e) {
                    backSqlTask.setSql(sql);
                    throw e;
                } finally {
                    backSqlTask.setStmt(null);
                }
            }
            return affectedRows;
        }
    }

    private List<String> parseSqlStatements(String scriptText) {
        List<String> statements = new ArrayList<>();
        Sql currentSql = new Sql();
        for (SqlParserUtil.Segment segment : SqlParserUtil.split(scriptText)) {
            String remainingChunk = segment.getText();
            while (remainingChunk != null && !remainingChunk.isBlank()) {
                currentSql = SqlParserUtil.modifySql(currentSql, remainingChunk);
                if (!currentSql.getSqlEnd()) {
                    break;
                }
                String statement = currentSql.getSqlstr();
                if (statement != null && !statement.trim().isEmpty()) {
                    statements.add(statement.trim());
                }
                remainingChunk = currentSql.getSqlRemainder();
                currentSql = new Sql();
            }
        }
        if (currentSql.getSqlstr() != null && !currentSql.getSqlstr().trim().isEmpty()) {
            statements.add(currentSql.getSqlstr().trim());
        }
        return statements;
    }

    private String stripLeadingBom(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private String formatImportSqlProgress(int completed, int total) {
        if (total <= 0) {
            return "";
        }
        int safeCompleted = Math.max(0, Math.min(completed, total));
        return safeCompleted + "/" + total;
    }

    private void showImportError(String message) {
        Platform.runLater(() -> AlertUtil.CustomAlert(
                I18n.t("metadata.import_sql.error.title", "导入SQL脚本失败"),
                message
        ));
    }
}
