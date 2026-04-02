package com.dbboys.service;

import com.dbboys.app.AppExecutor;
import com.dbboys.api.DdlRepositoryProvider;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.i18n.I18n;
import com.dbboys.util.AlertUtil;
import com.dbboys.util.BackgroundSqlUtil;
import com.dbboys.vo.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class TableService implements MetaObjectService {
    private static final int IMPORT_BATCH_SIZE = 500;
    private static final String CSV_BINARY_PREFIX = "base64:";
    private final MetadataRepositoryProvider metadataRepositoryProvider;
    private final DdlRepositoryProvider ddlRepositoryProvider;

    public TableService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class),
                com.dbboys.app.AppContext.get(DdlRepositoryProvider.class));
    }

    public TableService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this(metadataRepositoryProvider, com.dbboys.app.AppContext.get(DdlRepositoryProvider.class));
    }

    public TableService(MetadataRepositoryProvider metadataRepositoryProvider, DdlRepositoryProvider ddlRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
        this.ddlRepositoryProvider = ddlRepositoryProvider;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Table> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getUserTablesCount(conn);
        String size = repo.getUserTablesSize(conn, databaseName);
        String info = count + "个";
        if (size != null) {
            info = info + "/" + size;
        }
        objectList.setInfo(info);
        LOG.info("loadObjects: " + info );
        result.addAll(repo.getUserTables(conn, databaseName));
        return objectList;
    }


    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> ddlRepositoryProvider.ddl(connect).printTable(conn, objectName);
    }


    public ObjectList loadSystemTables(Connect connect, Database database) throws Exception {
        return withMetaSession(connect, database, conn -> buildSystemTables(connect, conn, database.getName()));
    }

    private ObjectList buildSystemTables(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<SysTable> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getSystemTablesCount(conn);
        String size = repo.getSystemTablesSize(conn, databaseName);
        objectList.setInfo(count + "个/" + size);
        result.addAll(repo.getSystemTables(conn, databaseName));
        return objectList;
    }
    public ArrayList<ColumnsInfo> getColumns(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> new ArrayList<>(metadataRepositoryProvider.metadata(connect).getColumns(conn, objectName)));
    }

    public Table getTable(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepositoryProvider.metadata(connect).getTable(conn, database.getName(), objectName));
    }

    public String getTableComment(Connect connect, Database database, String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepositoryProvider.metadata(connect).getTableComment(conn, objectName));
    }

    public void updateStatistics(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

    public void modifyTableToRaw(Connect connect, String tableName, Runnable onSucceededUi) {
        executeObjectSql(connect, "alter table " + tableName + " type(raw)", onSucceededUi);
    }

    public void modifyTableToStandard(Connect connect, String tableName, Runnable onSucceededUi) {
        executeObjectSql(connect, "alter table " + tableName + " type(standard)", onSucceededUi);
    }

    public void truncateTable(Connect connect, String tableName, Runnable onSucceededUi) {
        executeObjectSql(connect, "truncate table " + tableName, onSucceededUi);
    }

    public void updateStatisticsForTable(Connect connect, String tableName, Runnable onSucceededUi){
        Task<List<String>> loadIndexColumnsTask = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
                    return metadataRepositoryProvider.metadata(connect).getIndexColumnsForTable(conn, tableName);
                }
            }
        };

        loadIndexColumnsTask.setOnSucceeded(event -> {
            List<String> indexColumns = loadIndexColumnsTask.getValue();
            String indexColumnsStr = String.join(",", indexColumns);
            String sql="update statistics for table " + tableName;
            if (!indexColumnsStr.isEmpty()) {
                sql = "update statistics high for table " + tableName + "(" + indexColumnsStr + ")";
            }
            executeObjectSql(connect, sql, onSucceededUi);
        });

        loadIndexColumnsTask.setOnFailed(event -> AppErrorHandler.handle(loadIndexColumnsTask.getException()));
        AppExecutor.runAsync(loadIndexColumnsTask);
    }

    public void refreshTableMeta(Connect connect,
                                 Database database,
                                 String tableName,
                                 Consumer<Table> onLoadedUi,
                                 Runnable onFinishedUi) {
        Task<Table> tableMetaTask = new Task<>() {
            @Override
            protected Table call() throws Exception {
                LOG.info("刷新表元数据: {} {} {}", connect.getName(), database.getName(), tableName);
                return getTable(connect, database, tableName);
            }
        };
        tableMetaTask.setOnSucceeded(event -> {
            Table table = tableMetaTask.getValue();
            if (table != null && table.getName() != null && onLoadedUi != null) {
                onLoadedUi.accept(table);
            }
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        tableMetaTask.setOnFailed(event -> {
            AppErrorHandler.handle(tableMetaTask.getException());
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        connect.executeSqlTask(tableMetaTask);
    }


    public void modifyTableFromUi(Connect connect, List<String> sqlList, Runnable onSucceededUi, Runnable onNotSucceededUi) {
            executeObjectSqls(connect, sqlList, onSucceededUi, onNotSucceededUi);

    }

    public void importTableData(Connect connect,
                                Database database,
                                String tableName,
                                File file,
                                IntConsumer onSucceededUi) {
        if (connect == null || database == null || file == null || tableName == null || tableName.isBlank()) {
            return;
        }

        BackgroundSqlTask backSqlTask = new BackgroundSqlTask();
        String importSummary = I18n.t("metadata.import.task.summary", "导入表%s <- %s")
                .formatted(tableName, file.getName());
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
                BackgroundSqlUtil.backSqlTaskList.add(backSqlTask);
                BackgroundSqlUtil.updateBackSqlUIOnStart();

                try {
                    int affectedRows = executeStreamImport(connect, database, tableName, file, backSqlTask);
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
                } catch (TableImportException e) {
                    showImportError(backSqlTask, e.getMessage());
                    throw e;
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message == null || message.isBlank()) {
                        message = I18n.t("metadata.import.error.unknown", "导入失败，请检查文件内容或数据库连接");
                    }
                    showImportError(backSqlTask, message);
                    throw e;
                } finally {
                    backSqlTask.setStmt(null);
                    BackgroundSqlUtil.backSqlTaskList.remove(backSqlTask);
                    BackgroundSqlUtil.updateBackSqlUIOnFinish();
                }
            }
        };
        bgTask.setOnSucceeded(event -> {
            if (onSucceededUi != null) {
                onSucceededUi.accept(bgTask.getValue());
            }
        });
        backSqlTask.setCancelAction(() -> bgTask.cancel(true));
        backSqlTask.setFuture(BackgroundSqlUtil.backSqlExecutor.submit(bgTask));
    }

    public int importTableDataSync(Connect connect,
                                   Database database,
                                   String tableName,
                                   File file,
                                   BackgroundSqlTask backSqlTask) throws Exception {
        if (connect == null || database == null || file == null || tableName == null || tableName.isBlank()) {
            return 0;
        }

        BackgroundSqlTask effectiveTask = backSqlTask == null ? new BackgroundSqlTask() : backSqlTask;
        return executeStreamImport(connect, database, tableName, file, effectiveTask);
    }

    private int executeStreamImport(Connect connect,
                                    Database database,
                                    String tableName,
                                    File file,
                                    BackgroundSqlTask backSqlTask) throws Exception {
        checkImportCancelled(backSqlTask);
        if (!file.exists() || !file.isFile()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.file_missing", "导入文件不存在：%s").formatted(file.getAbsolutePath())
            );
        }
        ArrayList<ColumnsInfo> tableColumns = loadImportColumns(connect, tableName);
        if (tableColumns == null || tableColumns.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.no_table_columns", "未获取到表\"%s\"的列信息").formatted(tableName)
            );
        }
        ImportFileFormat importFormat = resolveImportFormat(file);
        BackgroundSqlUtil.updateTaskProgress(
                backSqlTask,
                I18n.t("metadata.import.progress.counting", "统计行数")
        );
        int totalRows = countImportRows(file, importFormat, backSqlTask);
        if (totalRows <= 0) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.no_rows", "文件中没有可导入的数据：%s").formatted(file.getName())
            );
        }
        BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportProgress(0, totalRows));
        int affectedRows = switch (importFormat) {
            case CSV -> executeCsvStreamImport(connect, database, tableName, file, tableColumns, totalRows, backSqlTask);
            case JSON -> executeJsonStreamImport(connect, database, tableName, file, tableColumns, totalRows, backSqlTask);
        };
        BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportProgress(affectedRows, totalRows));
        return affectedRows;
    }

    private int executeCsvStreamImport(Connect connect,
                                       Database database,
                                       String tableName,
                                       File file,
                                       List<ColumnsInfo> tableColumns,
                                       int totalRows,
                                       BackgroundSqlTask backSqlTask) throws Exception {
        try (CsvRowStream rowStream = new CsvRowStream(file)) {
            List<String> headerRow = rowStream.nextRow(backSqlTask);
            if (headerRow == null || isAllBlankRow(headerRow)) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.empty_file", "导入文件为空：%s").formatted(file.getName())
                );
            }
            CsvImportSchema schema = buildCsvImportSchema(tableName, tableColumns, headerRow);
            return executeStreamingImport(connect, database, backSqlTask, (conn, noLogDatabase) -> {
                try (PreparedStatement preparedStatement = conn.prepareStatement(buildInsertSql(tableName, schema.targetColumns))) {
                    backSqlTask.setStmt(preparedStatement);
                    int importedRowCount = 0;
                    int batchCount = 0;
                    int batchStartRowNumber = 1;
                    List<String> rawRow;
                    while ((rawRow = rowStream.nextRow(backSqlTask)) != null) {
                        checkImportCancelled(backSqlTask);
                        if (isAllBlankRow(rawRow)) {
                            continue;
                        }
                        if (rawRow.size() > headerRow.size()) {
                            throw new TableImportException(
                                    I18n.t("metadata.import.error.csv_column_overflow", "CSV 第 %d 行的列数超过表头列数")
                                            .formatted(rowStream.getCurrentRowNumber())
                            );
                        }
                        bindCsvImportRow(preparedStatement, schema, rawRow, importedRowCount + 1, tableName);
                        preparedStatement.addBatch();
                        importedRowCount++;
                        batchCount++;
                        if (importedRowCount == totalRows || importedRowCount % 50 == 0) {
                            BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportProgress(importedRowCount, totalRows));
                        }
                        if (batchCount >= IMPORT_BATCH_SIZE) {
                            executeImportBatch(preparedStatement, tableName, batchStartRowNumber, backSqlTask);
                            if (!noLogDatabase) {
                                conn.commit();
                            }
                            batchStartRowNumber = importedRowCount + 1;
                            batchCount = 0;
                        }
                    }
                    if (importedRowCount <= 0) {
                        throw new TableImportException(
                                I18n.t("metadata.import.error.no_rows", "文件中没有可导入的数据：%s").formatted(file.getName())
                        );
                    }
                    if (batchCount > 0) {
                        executeImportBatch(preparedStatement, tableName, batchStartRowNumber, backSqlTask);
                        if (!noLogDatabase) {
                            conn.commit();
                        }
                    }
                    checkImportCancelled(backSqlTask);
                    return importedRowCount;
                } finally {
                    backSqlTask.setStmt(null);
                }
            });
        }
    }

    private int executeJsonStreamImport(Connect connect,
                                        Database database,
                                        String tableName,
                                        File file,
                                        List<ColumnsInfo> tableColumns,
                                        int totalRows,
                                        BackgroundSqlTask backSqlTask) throws Exception {
        try (JsonRowStream rowStream = new JsonRowStream(file)) {
            Map<String, String> sourceColumns = new LinkedHashMap<>();
            List<Map<String, Object>> bufferedRows = new ArrayList<>();
            List<ColumnsInfo> currentColumns = List.of();
            while (true) {
                JSONObject jsonObject = rowStream.nextObject(backSqlTask);
                if (jsonObject == null) {
                    break;
                }
                bufferedRows.add(parseJsonImportRow(jsonObject, sourceColumns));
                currentColumns = resolveMatchedImportColumns(tableColumns, sourceColumns);
                if (!currentColumns.isEmpty()) {
                    break;
                }
            }
            if (bufferedRows.isEmpty()) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.no_rows", "文件中没有可导入的数据：%s").formatted(file.getName())
                );
            }
            validateMatchedImportColumns(tableName, currentColumns);
            final List<ColumnsInfo> initialColumns = new ArrayList<>(currentColumns);
            return executeStreamingImport(connect, database, backSqlTask, (conn, noLogDatabase) -> {
                PreparedStatement preparedStatement = null;
                try {
                    List<ColumnsInfo> activeColumns = initialColumns;
                    preparedStatement = conn.prepareStatement(buildInsertSql(tableName, activeColumns));
                    backSqlTask.setStmt(preparedStatement);
                    int importedRowCount = 0;
                    int batchCount = 0;
                    int batchStartRowNumber = 1;

                    for (Map<String, Object> row : bufferedRows) {
                        PreparedStatement activeStatement = preparedStatement;
                        if (hasJsonImportColumnsChanged(tableColumns, sourceColumns, activeColumns)) {
                            if (batchCount > 0) {
                                executeImportBatch(activeStatement, tableName, batchStartRowNumber, backSqlTask);
                                if (!noLogDatabase) {
                                    conn.commit();
                                }
                                batchStartRowNumber = importedRowCount + 1;
                                batchCount = 0;
                            }
                            activeColumns = new ArrayList<>(resolveMatchedImportColumns(tableColumns, sourceColumns));
                            closeStatementQuietly(activeStatement);
                            preparedStatement = conn.prepareStatement(buildInsertSql(tableName, activeColumns));
                            backSqlTask.setStmt(preparedStatement);
                            activeStatement = preparedStatement;
                        }
                        bindImportRow(activeStatement, activeColumns, row, importedRowCount + 1, tableName);
                        activeStatement.addBatch();
                        importedRowCount++;
                        batchCount++;
                        if (importedRowCount == totalRows || importedRowCount % 50 == 0) {
                            BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportProgress(importedRowCount, totalRows));
                        }
                        if (batchCount >= IMPORT_BATCH_SIZE) {
                            executeImportBatch(activeStatement, tableName, batchStartRowNumber, backSqlTask);
                            if (!noLogDatabase) {
                                conn.commit();
                            }
                            batchStartRowNumber = importedRowCount + 1;
                            batchCount = 0;
                        }
                    }

                    JSONObject jsonObject;
                    while ((jsonObject = rowStream.nextObject(backSqlTask)) != null) {
                        Map<String, Object> row = parseJsonImportRow(jsonObject, sourceColumns);
                        PreparedStatement activeStatement = preparedStatement;
                        if (hasJsonImportColumnsChanged(tableColumns, sourceColumns, activeColumns)) {
                            if (batchCount > 0) {
                                executeImportBatch(activeStatement, tableName, batchStartRowNumber, backSqlTask);
                                if (!noLogDatabase) {
                                    conn.commit();
                                }
                                batchStartRowNumber = importedRowCount + 1;
                                batchCount = 0;
                            }
                            activeColumns = new ArrayList<>(resolveMatchedImportColumns(tableColumns, sourceColumns));
                            closeStatementQuietly(activeStatement);
                            preparedStatement = conn.prepareStatement(buildInsertSql(tableName, activeColumns));
                            backSqlTask.setStmt(preparedStatement);
                            activeStatement = preparedStatement;
                        }
                        bindImportRow(activeStatement, activeColumns, row, importedRowCount + 1, tableName);
                        activeStatement.addBatch();
                        importedRowCount++;
                        batchCount++;
                        if (importedRowCount == totalRows || importedRowCount % 50 == 0) {
                            BackgroundSqlUtil.updateTaskProgress(backSqlTask, formatImportProgress(importedRowCount, totalRows));
                        }
                        if (batchCount >= IMPORT_BATCH_SIZE) {
                            executeImportBatch(activeStatement, tableName, batchStartRowNumber, backSqlTask);
                            if (!noLogDatabase) {
                                conn.commit();
                            }
                            batchStartRowNumber = importedRowCount + 1;
                            batchCount = 0;
                        }
                    }

                    if (batchCount > 0) {
                        executeImportBatch(preparedStatement, tableName, batchStartRowNumber, backSqlTask);
                        if (!noLogDatabase) {
                            conn.commit();
                        }
                    }
                    checkImportCancelled(backSqlTask);
                    return importedRowCount;
                } finally {
                    closeStatementQuietly(preparedStatement);
                    backSqlTask.setStmt(null);
                }
            });
        }
    }

    @FunctionalInterface
    private interface StreamingImportExecutor {
        int execute(Connection conn, boolean noLogDatabase) throws Exception;
    }

    private int executeStreamingImport(Connect connect,
                                       Database database,
                                       BackgroundSqlTask backSqlTask,
                                       StreamingImportExecutor executor) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
            if (conn == null) {
                throw new TableImportException(
                    I18n.t("metadata.import.error.unknown", "导入失败，请检查文件内容或数据库连接")
                );
            }
            backSqlTask.setConnection(conn);

            boolean noLogDatabase = isNoLogDatabase(database);
            boolean originalAutoCommit = conn.getAutoCommit();
            boolean autoCommitChanged = false;
            if (noLogDatabase) {
                if (!originalAutoCommit) {
                    conn.setAutoCommit(true);
                    autoCommitChanged = true;
                }
            } else if (originalAutoCommit) {
                conn.setAutoCommit(false);
                autoCommitChanged = true;
            }
            try {
                int importedRows = executor.execute(conn, noLogDatabase);
                checkImportCancelled(backSqlTask);
                return importedRows;
            } catch (CancellationException e) {
                if (!noLogDatabase) {
                    rollbackQuietly(conn);
                }
                throw e;
            } catch (SQLException e) {
                if (backSqlTask != null && backSqlTask.isCancelled()) {
                    throw new CancellationException("import cancelled");
                }
                if (!noLogDatabase) {
                    rollbackQuietly(conn);
                }
                throw e;
            } catch (Exception e) {
                if (backSqlTask != null && backSqlTask.isCancelled()) {
                    throw new CancellationException("import cancelled");
                }
                if (!noLogDatabase) {
                    rollbackQuietly(conn);
                }
                throw e;
            } finally {
                backSqlTask.setConnection(null);
                backSqlTask.setStmt(null);
                if (autoCommitChanged) {
                    restoreAutoCommitQuietly(conn, originalAutoCommit);
                }
            }
        }
    }

    private CsvImportSchema buildCsvImportSchema(String tableName,
                                                 List<ColumnsInfo> tableColumns,
                                                 List<String> headerRow) throws Exception {
        Map<String, String> sourceColumns = new LinkedHashMap<>();
        Map<String, Integer> sourceIndexes = new LinkedHashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            String displayName = stripLeadingBom(headerRow.get(i)).trim();
            if (displayName.isBlank()) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.blank_header", "CSV 表头第 %d 列为空").formatted(i + 1)
                );
            }
            String normalizedName = normalizeFieldName(displayName);
            if (sourceColumns.containsKey(normalizedName)) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.duplicate_header", "文件中存在重复列名：%s").formatted(displayName)
                );
            }
            sourceColumns.put(normalizedName, displayName);
            sourceIndexes.put(normalizedName, i);
        }
        List<ColumnsInfo> matchedColumns = resolveMatchedImportColumns(tableColumns, sourceColumns);
        validateMatchedImportColumns(tableName, matchedColumns);
        validateRequiredImportColumns(tableName, tableColumns, sourceColumns);
        List<Integer> matchedSourceIndexes = new ArrayList<>();
        for (ColumnsInfo column : matchedColumns) {
            matchedSourceIndexes.add(sourceIndexes.get(normalizeFieldName(column.getColName())));
        }
        return new CsvImportSchema(matchedColumns, matchedSourceIndexes);
    }

    private int countImportRows(File file,
                                ImportFileFormat importFormat,
                                BackgroundSqlTask backSqlTask) throws Exception {
        return switch (importFormat) {
            case CSV -> countCsvRows(file, backSqlTask);
            case JSON -> countJsonRows(file, backSqlTask);
        };
    }

    private int countCsvRows(File file, BackgroundSqlTask backSqlTask) throws Exception {
        try (CsvRowStream rowStream = new CsvRowStream(file)) {
            List<String> headerRow = rowStream.nextRow(backSqlTask);
            if (headerRow == null || isAllBlankRow(headerRow)) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.empty_file", "导入文件为空：%s").formatted(file.getName())
                );
            }
            int totalRows = 0;
            List<String> row;
            while ((row = rowStream.nextRow(backSqlTask)) != null) {
                if (!isAllBlankRow(row)) {
                    totalRows++;
                }
            }
            return totalRows;
        }
    }

    private int countJsonRows(File file, BackgroundSqlTask backSqlTask) throws Exception {
        try (JsonRowStream rowStream = new JsonRowStream(file)) {
            int totalRows = 0;
            while (rowStream.nextObject(backSqlTask) != null) {
                totalRows++;
            }
            return totalRows;
        }
    }

    private List<ColumnsInfo> resolveMatchedImportColumns(List<ColumnsInfo> tableColumns,
                                                          Map<String, String> sourceColumns) {
        List<ColumnsInfo> matchedColumns = new ArrayList<>();
        for (ColumnsInfo column : tableColumns) {
            String normalizedColumnName = normalizeFieldName(column.getColName());
            if (sourceColumns.containsKey(normalizedColumnName)) {
                matchedColumns.add(column);
            }
        }
        return matchedColumns;
    }

    private void validateMatchedImportColumns(String tableName,
                                              List<ColumnsInfo> matchedColumns) throws TableImportException {
        if (matchedColumns == null || matchedColumns.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.no_matched_columns", "文件列与表\"%s\"的列没有匹配项").formatted(tableName)
            );
        }
    }

    private void validateRequiredImportColumns(String tableName,
                                               List<ColumnsInfo> tableColumns,
                                               Map<String, String> sourceColumns) throws TableImportException {
        List<String> missingRequiredColumns = new ArrayList<>();
        for (ColumnsInfo column : tableColumns) {
            String normalizedColumnName = normalizeFieldName(column.getColName());
            if (!sourceColumns.containsKey(normalizedColumnName) && isRequiredImportColumn(column)) {
                missingRequiredColumns.add(column.getColName());
            }
        }
        if (!missingRequiredColumns.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.missing_required_columns", "表\"%s\"存在文件中未提供的必填列：%s")
                            .formatted(tableName, String.join(", ", missingRequiredColumns))
            );
        }
    }

    private boolean hasJsonImportColumnsChanged(List<ColumnsInfo> tableColumns,
                                                Map<String, String> sourceColumns,
                                                List<ColumnsInfo> currentColumns) {
        List<ColumnsInfo> resolvedColumns = resolveMatchedImportColumns(tableColumns, sourceColumns);
        return !sameImportColumns(currentColumns, resolvedColumns);
    }

    private boolean sameImportColumns(List<ColumnsInfo> left, List<ColumnsInfo> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!normalizeFieldName(left.get(i).getColName()).equals(normalizeFieldName(right.get(i).getColName()))) {
                return false;
            }
        }
        return true;
    }

    private void bindCsvImportRow(PreparedStatement preparedStatement,
                                  CsvImportSchema schema,
                                  List<String> rawRow,
                                  int rowNumber,
                                  String tableName) throws SQLException, TableImportException {
        for (int i = 0; i < schema.targetColumns.size(); i++) {
            ColumnsInfo column = schema.targetColumns.get(i);
            int sourceIndex = schema.sourceIndexes.get(i);
            Object value = sourceIndex < rawRow.size() ? rawRow.get(sourceIndex) : null;
            try {
                bindImportValue(preparedStatement, i + 1, column, value);
            } catch (SQLException e) {
                throw new TableImportException(
                        I18n.t(
                                "metadata.import.error.bind_column",
                                "表\"%s\"第 %d 行列\"%s\"(%s) 导入失败：%s"
                        ).formatted(
                                tableName,
                                rowNumber,
                                column == null ? "" : column.getColName(),
                                column == null ? "" : normalizeColumnType(column.getColType()),
                                buildImportErrorMessage(e)
                        ),
                        e
                );
            }
        }
    }

    private void bindImportRow(PreparedStatement preparedStatement,
                               List<ColumnsInfo> targetColumns,
                               Map<String, Object> row,
                               int rowNumber,
                               String tableName) throws SQLException, TableImportException {
        for (int i = 0; i < targetColumns.size(); i++) {
            ColumnsInfo column = targetColumns.get(i);
            Object value = row.get(normalizeFieldName(column.getColName()));
            try {
                bindImportValue(preparedStatement, i + 1, column, value);
            } catch (SQLException e) {
                throw new TableImportException(
                        I18n.t(
                                "metadata.import.error.bind_column",
                                "表\"%s\"第 %d 行列\"%s\"(%s) 导入失败：%s"
                        ).formatted(
                                tableName,
                                rowNumber,
                                column == null ? "" : column.getColName(),
                                column == null ? "" : normalizeColumnType(column.getColType()),
                                buildImportErrorMessage(e)
                        ),
                        e
                );
            }
        }
    }

    private void bindImportValue(PreparedStatement preparedStatement,
                                 int parameterIndex,
                                 ColumnsInfo column,
                                 Object value) throws SQLException {
        String columnType = normalizeColumnType(column == null ? null : column.getColType());
        Object normalizedValue = normalizeImportedValue(value);
        if (normalizedValue == null) {
            preparedStatement.setNull(parameterIndex, resolveImportSqlType(columnType));
            return;
        }
        if (normalizedValue instanceof byte[] bytesValue) {
            preparedStatement.setBytes(parameterIndex, bytesValue);
            return;
        }
        if (normalizedValue instanceof String stringValue) {
            bindImportStringValue(preparedStatement, parameterIndex, columnType, stringValue);
            return;
        }
        if (isTextImportColumnType(columnType)) {
            preparedStatement.setString(parameterIndex, normalizedValue.toString());
            return;
        }
        preparedStatement.setObject(parameterIndex, normalizedValue);
    }

    private void bindImportStringValue(PreparedStatement preparedStatement,
                                       int parameterIndex,
                                       String columnType,
                                       String value) throws SQLException {
        if (shouldImportBlankAsNull(columnType, value)) {
            preparedStatement.setNull(parameterIndex, resolveImportSqlType(columnType));
            return;
        }
        if (isBinaryImportColumnType(columnType)) {
            preparedStatement.setBytes(parameterIndex, decodeBinaryImportValue(value));
            return;
        }
        if (shouldTrimImportValue(columnType)) {
            preparedStatement.setString(parameterIndex, value.trim());
            return;
        }
        preparedStatement.setString(parameterIndex, value);
    }

    private void executeImportBatch(PreparedStatement preparedStatement,
                                    String tableName,
                                    int batchStartRowNumber,
                                    BackgroundSqlTask backSqlTask) throws SQLException, TableImportException {
        try {
            preparedStatement.executeBatch();
            checkImportCancelled(backSqlTask);
        } catch (BatchUpdateException e) {
            if (backSqlTask != null && backSqlTask.isCancelled()) {
                throw new CancellationException("import cancelled");
            }
            int failedRowNumber = batchStartRowNumber + countBatchRowsBeforeFailure(e.getUpdateCounts());
            throw new TableImportException(
                    I18n.t(
                            "metadata.import.error.batch_row_failed",
                            "表\"%s\"第 %d 行导入失败：%s"
                    ).formatted(tableName, failedRowNumber, buildImportErrorMessage(e)),
                    e
            );
        } catch (SQLException e) {
            if (backSqlTask != null && backSqlTask.isCancelled()) {
                throw new CancellationException("import cancelled");
            }
            throw new TableImportException(
                    I18n.t(
                            "metadata.import.error.batch_failed",
                            "表\"%s\"从第 %d 行开始的批量导入失败：%s"
                    ).formatted(tableName, batchStartRowNumber, buildImportErrorMessage(e)),
                    e
            );
        }
    }

    private Map<String, Object> parseJsonImportRow(JSONObject jsonObject,
                                                   Map<String, String> sourceColumns) throws TableImportException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : jsonObject.keySet()) {
            String displayName = stripLeadingBom(key).trim();
            if (displayName.isBlank()) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.blank_field_name", "存在空字段名，无法导入")
                );
            }
            String normalizedName = normalizeFieldName(displayName);
            sourceColumns.putIfAbsent(normalizedName, displayName);
            row.put(normalizedName, normalizeImportedValue(jsonObject.opt(key)));
        }
        return row;
    }

    private void closeStatementQuietly(PreparedStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (Exception ignored) {
        }
    }

    private ArrayList<ColumnsInfo> loadImportColumns(Connect connect, String tableName) throws Exception {
        try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
            return new ArrayList<>(metadataRepositoryProvider.metadata(connect).getColumns(conn, tableName));
        }
    }

    private String buildInsertSql(String tableName, List<ColumnsInfo> targetColumns) {
        List<String> columnNames = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        for (ColumnsInfo column : targetColumns) {
            columnNames.add(column.getColName());
            placeholders.add("?");
        }
        return "insert into " + tableName + "(" + String.join(",", columnNames) + ") values(" +
                String.join(",", placeholders) + ")";
    }

    private boolean isRequiredImportColumn(ColumnsInfo column) {
        if (column == null || column.isIsNullable() || column.isIsAutoincrement()) {
            return false;
        }
        String defaultType = column.getColDefType();
        if (defaultType != null && !defaultType.isBlank()) {
            return false;
        }
        String defaultValue = column.getColDef();
        return defaultValue == null || defaultValue.isBlank();
    }

    private boolean isAllBlankRow(List<String> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (String value : row) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String normalizeFieldName(String value) {
        return stripLeadingBom(value).trim().toLowerCase(Locale.ROOT);
    }

    private String stripLeadingBom(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private Object normalizeImportedValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value.toString();
        }
        return value;
    }

    private String normalizeColumnType(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isTextImportColumnType(String columnType) {
        if (columnType == null || columnType.isEmpty()) {
            return false;
        }
        return columnType.startsWith("CHAR")
                || columnType.startsWith("NCHAR")
                || columnType.startsWith("VARCHAR")
                || columnType.startsWith("NVARCHAR")
                || columnType.startsWith("LVARCHAR")
                || columnType.startsWith("TEXT")
                || columnType.startsWith("CLOB")
                || columnType.startsWith("JSON")
                || columnType.startsWith("BSON");
    }

    private boolean isBinaryImportColumnType(String columnType) {
        if (columnType == null || columnType.isEmpty()) {
            return false;
        }
        return columnType.startsWith("BYTE") || columnType.startsWith("BLOB");
    }

    private boolean shouldImportBlankAsNull(String columnType, String value) {
        if (value == null || !value.isBlank()) {
            return false;
        }
        return !isTextImportColumnType(columnType);
    }

    private boolean shouldTrimImportValue(String columnType) {
        return !isTextImportColumnType(columnType) && !isBinaryImportColumnType(columnType);
    }

    private boolean isNoLogDatabase(Database database) {
        return database != null
                && database.getDbLog() != null
                && "nolog".equalsIgnoreCase(database.getDbLog().trim());
    }

    private String formatImportProgress(int completedRows, int totalRows) {
        if (totalRows <= 0) {
            return Math.max(0, completedRows) + "/?";
        }
        int safeCompletedRows = Math.max(0, Math.min(completedRows, totalRows));
        return safeCompletedRows + "/" + totalRows;
    }

    private byte[] decodeBinaryImportValue(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value.regionMatches(true, 0, CSV_BINARY_PREFIX, 0, CSV_BINARY_PREFIX.length())) {
            String encoded = value.substring(CSV_BINARY_PREFIX.length());
            try {
                return Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw new SQLException("BYTE/BLOB 列内容不是有效的 Base64 数据", e);
            }
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private int resolveImportSqlType(String columnType) {
        if (columnType == null || columnType.isEmpty()) {
            return Types.NULL;
        }
        if (isTextImportColumnType(columnType)) {
            return columnType.startsWith("CLOB") ? Types.CLOB : Types.VARCHAR;
        }
        if (isBinaryImportColumnType(columnType)) {
            return columnType.startsWith("BLOB") ? Types.BLOB : Types.LONGVARBINARY;
        }
        if (columnType.startsWith("SMALLINT")) {
            return Types.SMALLINT;
        }
        if (columnType.startsWith("INTEGER") || columnType.startsWith("SERIAL")) {
            return Types.INTEGER;
        }
        if (columnType.startsWith("BIGINT") || columnType.startsWith("BIGSERIAL") || columnType.startsWith("SERIAL8")) {
            return Types.BIGINT;
        }
        if (columnType.startsWith("DECIMAL") || columnType.startsWith("NUMERIC") || columnType.startsWith("MONEY")) {
            return Types.DECIMAL;
        }
        if (columnType.startsWith("FLOAT") || columnType.startsWith("DOUBLE") || columnType.startsWith("REAL") || columnType.startsWith("SMALLFLOAT")) {
            return Types.DOUBLE;
        }
        if (columnType.startsWith("DATE")) {
            return Types.DATE;
        }
        if (columnType.startsWith("DATETIME") || columnType.startsWith("TIMESTAMP")) {
            return Types.TIMESTAMP;
        }
        if (columnType.startsWith("BOOLEAN")) {
            return Types.BOOLEAN;
        }
        return Types.NULL;
    }

    private int countBatchRowsBeforeFailure(int[] updateCounts) {
        if (updateCounts == null || updateCounts.length == 0) {
            return 0;
        }
        int successCount = 0;
        for (int updateCount : updateCounts) {
            if (updateCount == Statement.EXECUTE_FAILED) {
                break;
            }
            successCount++;
        }
        return successCount;
    }

    private String buildImportErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable current = throwable;
        String bestMessage = "";
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                bestMessage = message;
            }
            current = current.getCause();
        }
        if (!bestMessage.isBlank()) {
            return bestMessage;
        }
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static void checkImportCancelled(BackgroundSqlTask backSqlTask) {
        if ((backSqlTask != null && backSqlTask.isCancelled()) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("import cancelled");
        }
    }

    private void rollbackQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (Exception ignored) {
        }
    }

    private void restoreAutoCommitQuietly(Connection conn, boolean autoCommit) {
        if (conn == null) {
            return;
        }
        try {
            conn.setAutoCommit(autoCommit);
        } catch (Exception ignored) {
        }
    }

    private void showImportError(BackgroundSqlTask backSqlTask, String message) {
        if (backSqlTask != null && backSqlTask.isCancelled()) {
            return;
        }
        Platform.runLater(() -> AlertUtil.CustomAlert(
                I18n.t("metadata.import.error.title", "导入数据失败"),
                message == null || message.isBlank()
                        ? I18n.t("metadata.import.error.unknown", "导入失败，请检查文件内容或数据库连接")
                        : message
        ));
    }

    private ImportFileFormat resolveImportFormat(File file) throws TableImportException {
        String fileName = file.getName().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".csv")) {
            return ImportFileFormat.CSV;
        }
        if (fileName.endsWith(".json")) {
            return ImportFileFormat.JSON;
        }
        throw new TableImportException(
                I18n.t("metadata.import.error.unsupported_file", "仅支持导入 CSV 或 JSON 文件：%s")
                        .formatted(file.getName())
        );
    }

    private enum ImportFileFormat {
        CSV,
        JSON
    }

    private static class CsvImportSchema {
        private final List<ColumnsInfo> targetColumns;
        private final List<Integer> sourceIndexes;

        private CsvImportSchema(List<ColumnsInfo> targetColumns, List<Integer> sourceIndexes) {
            this.targetColumns = targetColumns;
            this.sourceIndexes = sourceIndexes;
        }
    }

    private static class CsvRowStream implements AutoCloseable {
        private final PushbackReader reader;
        private boolean atFileStart = true;
        private int currentRowNumber = 0;

        private CsvRowStream(File file) throws Exception {
            this.reader = new PushbackReader(
                    new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)),
                    1
            );
        }

        private List<String> nextRow(BackgroundSqlTask backSqlTask) throws Exception {
            List<String> currentRow = new ArrayList<>();
            StringBuilder currentField = new StringBuilder();
            boolean inQuotes = false;
            int nextChar;
            while ((nextChar = reader.read()) != -1) {
                if ((currentField.length() & 63) == 0) {
                    checkImportCancelled(backSqlTask);
                }
                char currentChar = (char) nextChar;
                if (atFileStart) {
                    atFileStart = false;
                    if (currentChar == '\uFEFF') {
                        continue;
                    }
                }
                if (inQuotes) {
                    if (currentChar == '"') {
                        int escapedChar = reader.read();
                        if (escapedChar == '"') {
                            currentField.append('"');
                        } else {
                            inQuotes = false;
                            if (escapedChar != -1) {
                                reader.unread(escapedChar);
                            }
                        }
                    } else {
                        currentField.append(currentChar);
                    }
                    continue;
                }
                if (currentChar == '"') {
                    if (currentField.length() == 0) {
                        inQuotes = true;
                    } else {
                        currentField.append(currentChar);
                    }
                    continue;
                }
                if (currentChar == ',') {
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                    continue;
                }
                if (currentChar == '\r') {
                    int lineFeed = reader.read();
                    if (lineFeed != '\n' && lineFeed != -1) {
                        reader.unread(lineFeed);
                    }
                    currentRow.add(currentField.toString());
                    currentRowNumber++;
                    return currentRow;
                }
                if (currentChar == '\n') {
                    currentRow.add(currentField.toString());
                    currentRowNumber++;
                    return currentRow;
                }
                currentField.append(currentChar);
            }
            if (inQuotes) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.csv_quote_unclosed", "CSV 文件格式错误：存在未闭合的引号")
                );
            }
            if (currentField.length() > 0 || !currentRow.isEmpty()) {
                currentRow.add(currentField.toString());
                currentRowNumber++;
                return currentRow;
            }
            return null;
        }

        private int getCurrentRowNumber() {
            return currentRowNumber;
        }

        @Override
        public void close() throws Exception {
            reader.close();
        }
    }

    private static class JsonRowStream implements AutoCloseable {
        private final PushbackReader reader;
        private final JSONTokener tokener;
        private boolean initialized;
        private boolean arrayMode;
        private boolean finished;
        private int currentItemIndex;

        private JsonRowStream(File file) throws Exception {
            this.reader = new PushbackReader(
                    new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)),
                    1
            );
            int firstChar = reader.read();
            if (firstChar != -1 && firstChar != '\uFEFF') {
                reader.unread(firstChar);
            }
            this.tokener = new JSONTokener(reader);
        }

        private JSONObject nextObject(BackgroundSqlTask backSqlTask) throws Exception {
            checkImportCancelled(backSqlTask);
            Object rowObject;
            if (!initialized) {
                char first = tokener.nextClean();
                initialized = true;
                if (first == 0) {
                    finished = true;
                    return null;
                }
                if (first == '{') {
                    tokener.back();
                    rowObject = tokener.nextValue();
                    finished = true;
                } else if (first == '[') {
                    arrayMode = true;
                    char next = tokener.nextClean();
                    if (next == ']') {
                        finished = true;
                        return null;
                    }
                    tokener.back();
                    rowObject = tokener.nextValue();
                    consumeArraySeparator();
                } else {
                    throw new TableImportException(
                            I18n.t("metadata.import.error.json_root", "JSON 文件必须是对象数组或单个对象")
                    );
                }
            } else {
                if (finished) {
                    return null;
                }
                rowObject = tokener.nextValue();
                consumeArraySeparator();
            }
            currentItemIndex++;
            if (!(rowObject instanceof JSONObject jsonObject)) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.json_row_object", "JSON 第 %d 项必须是对象").formatted(currentItemIndex)
                );
            }
            return jsonObject;
        }

        private void consumeArraySeparator() throws Exception {
            if (!arrayMode || finished) {
                return;
            }
            char separator = tokener.nextClean();
            if (separator == ',') {
                char next = tokener.nextClean();
                if (next == ']') {
                    finished = true;
                } else {
                    tokener.back();
                }
                return;
            }
            if (separator == ']') {
                finished = true;
                return;
            }
            throw new TableImportException(
                    I18n.t("metadata.import.error.json_root", "JSON 文件必须是对象数组或单个对象")
            );
        }

        @Override
        public void close() throws Exception {
            tokener.close();
        }
    }

    private static class TableImportException extends Exception {
        private TableImportException(String message) {
            super(message);
        }

        private TableImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }


}

