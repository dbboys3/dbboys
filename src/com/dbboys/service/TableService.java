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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class TableService implements MetaObjectService {
    private static final int IMPORT_BATCH_SIZE = 500;
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
                    ImportPlan importPlan = buildImportPlan(connect, database, tableName, file, backSqlTask);
                    int affectedRows = executeImport(connect, tableName, importPlan, backSqlTask);
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
        backSqlTask.setFuture(BackgroundSqlUtil.backSqlExecutor.submit(bgTask));
    }

    private ImportPlan buildImportPlan(Connect connect,
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

        ParsedImportData parsedData = switch (resolveImportFormat(file)) {
            case CSV -> parseCsvFile(file, backSqlTask);
            case JSON -> parseJsonFile(file, backSqlTask);
        };
        if (parsedData.rows.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.no_rows", "文件中没有可导入的数据：%s").formatted(file.getName())
            );
        }

        ArrayList<ColumnsInfo> tableColumns = loadImportColumns(connect, tableName);
        if (tableColumns == null || tableColumns.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.no_table_columns", "未获取到表\"%s\"的列信息").formatted(tableName)
            );
        }

        List<ColumnsInfo> matchedColumns = new ArrayList<>();
        List<String> missingRequiredColumns = new ArrayList<>();
        for (ColumnsInfo column : tableColumns) {
            String normalizedColumnName = normalizeFieldName(column.getColName());
            if (parsedData.sourceColumns.containsKey(normalizedColumnName)) {
                matchedColumns.add(column);
            } else if (isRequiredImportColumn(column)) {
                missingRequiredColumns.add(column.getColName());
            }
        }

        if (matchedColumns.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.no_matched_columns", "文件列与表\"%s\"的列没有匹配项").formatted(tableName)
            );
        }
        if (!missingRequiredColumns.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.missing_required_columns", "表\"%s\"存在文件中未提供的必填列：%s")
                            .formatted(tableName, String.join(", ", missingRequiredColumns))
            );
        }
        return new ImportPlan(matchedColumns, parsedData.rows);
    }

    private int executeImport(Connect connect,
                              String tableName,
                              ImportPlan importPlan,
                              BackgroundSqlTask backSqlTask) throws Exception {
        String insertSql = buildInsertSql(tableName, importPlan.targetColumns);
        try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
            if (conn == null) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.unknown", "导入失败，请检查文件内容或数据库连接")
                );
            }

            boolean originalAutoCommit = conn.getAutoCommit();
            if (originalAutoCommit) {
                conn.setAutoCommit(false);
            }
            try (PreparedStatement preparedStatement = conn.prepareStatement(insertSql)) {
                backSqlTask.setStmt(preparedStatement);
                int importedRowCount = 0;
                int batchCount = 0;
                for (Map<String, Object> row : importPlan.rows) {
                    checkImportCancelled(backSqlTask);
                    bindImportRow(preparedStatement, importPlan.targetColumns, row);
                    preparedStatement.addBatch();
                    importedRowCount++;
                    batchCount++;
                    if (batchCount >= IMPORT_BATCH_SIZE) {
                        preparedStatement.executeBatch();
                        batchCount = 0;
                    }
                }
                if (batchCount > 0) {
                    preparedStatement.executeBatch();
                }
                checkImportCancelled(backSqlTask);
                conn.commit();
                return importedRowCount;
            } catch (CancellationException e) {
                rollbackQuietly(conn);
                throw e;
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                backSqlTask.setStmt(null);
                restoreAutoCommitQuietly(conn, originalAutoCommit);
            }
        }
    }

    private void bindImportRow(PreparedStatement preparedStatement,
                               List<ColumnsInfo> targetColumns,
                               Map<String, Object> row) throws SQLException {
        for (int i = 0; i < targetColumns.size(); i++) {
            ColumnsInfo column = targetColumns.get(i);
            Object value = row.get(normalizeFieldName(column.getColName()));
            preparedStatement.setObject(i + 1, normalizeImportedValue(value));
        }
    }

    private ParsedImportData parseCsvFile(File file, BackgroundSqlTask backSqlTask) throws Exception {
        List<List<String>> csvRows = readCsvRows(file, backSqlTask);
        if (csvRows.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.empty_file", "导入文件为空：%s").formatted(file.getName())
            );
        }

        List<String> headerRow = csvRows.get(0);
        if (isAllBlankRow(headerRow)) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.empty_file", "导入文件为空：%s").formatted(file.getName())
            );
        }

        ParsedImportData parsedData = new ParsedImportData();
        List<String> normalizedHeaders = new ArrayList<>();
        for (int i = 0; i < headerRow.size(); i++) {
            String displayName = stripLeadingBom(headerRow.get(i)).trim();
            if (displayName.isBlank()) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.blank_header", "CSV 表头第 %d 列为空").formatted(i + 1)
                );
            }
            String normalizedName = normalizeFieldName(displayName);
            if (parsedData.sourceColumns.containsKey(normalizedName)) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.duplicate_header", "文件中存在重复列名：%s").formatted(displayName)
                );
            }
            parsedData.sourceColumns.put(normalizedName, displayName);
            normalizedHeaders.add(normalizedName);
        }

        for (int rowIndex = 1; rowIndex < csvRows.size(); rowIndex++) {
            checkImportCancelled(backSqlTask);
            List<String> rawRow = csvRows.get(rowIndex);
            if (isAllBlankRow(rawRow)) {
                continue;
            }
            if (rawRow.size() > normalizedHeaders.size()) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.csv_column_overflow", "CSV 第 %d 行的列数超过表头列数")
                                .formatted(rowIndex + 1)
                );
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < normalizedHeaders.size(); columnIndex++) {
                String normalizedHeader = normalizedHeaders.get(columnIndex);
                String value = columnIndex < rawRow.size() ? rawRow.get(columnIndex) : null;
                row.put(normalizedHeader, value);
            }
            parsedData.rows.add(row);
        }
        return parsedData;
    }

    private List<List<String>> readCsvRows(File file, BackgroundSqlTask backSqlTask) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (PushbackReader reader = new PushbackReader(
                new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)),
                1
        )) {
            List<String> currentRow = new ArrayList<>();
            StringBuilder currentField = new StringBuilder();
            boolean inQuotes = false;
            boolean atFileStart = true;
            int nextChar;
            while ((nextChar = reader.read()) != -1) {
                checkImportCancelled(backSqlTask);
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
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    currentField.setLength(0);
                    checkImportCancelled(backSqlTask);
                    continue;
                }
                if (currentChar == '\n') {
                    currentRow.add(currentField.toString());
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    currentField.setLength(0);
                    checkImportCancelled(backSqlTask);
                    continue;
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
                rows.add(currentRow);
            }
        }
        return rows;
    }

    private ParsedImportData parseJsonFile(File file, BackgroundSqlTask backSqlTask) throws Exception {
        String jsonText = stripLeadingBom(java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8));
        if (jsonText.isBlank()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.empty_file", "导入文件为空：%s").formatted(file.getName())
            );
        }

        Object root = new JSONTokener(jsonText).nextValue();
        JSONArray jsonArray;
        if (root instanceof JSONObject jsonObject) {
            jsonArray = new JSONArray();
            jsonArray.put(jsonObject);
        } else if (root instanceof JSONArray array) {
            jsonArray = array;
        } else {
            throw new TableImportException(
                    I18n.t("metadata.import.error.json_root", "JSON 文件必须是对象数组或单个对象")
            );
        }
        if (jsonArray.isEmpty()) {
            throw new TableImportException(
                    I18n.t("metadata.import.error.no_rows", "文件中没有可导入的数据：%s").formatted(file.getName())
            );
        }

        ParsedImportData parsedData = new ParsedImportData();
        for (int i = 0; i < jsonArray.length(); i++) {
            checkImportCancelled(backSqlTask);
            Object rowObject = jsonArray.get(i);
            if (!(rowObject instanceof JSONObject jsonObject)) {
                throw new TableImportException(
                        I18n.t("metadata.import.error.json_row_object", "JSON 第 %d 项必须是对象").formatted(i + 1)
                );
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : jsonObject.keySet()) {
                String displayName = stripLeadingBom(key).trim();
                if (displayName.isBlank()) {
                    throw new TableImportException(
                            I18n.t("metadata.import.error.blank_field_name", "存在空字段名，无法导入")
                    );
                }
                String normalizedName = normalizeFieldName(displayName);
                parsedData.sourceColumns.putIfAbsent(normalizedName, displayName);
                row.put(normalizedName, normalizeImportedValue(jsonObject.opt(key)));
            }
            parsedData.rows.add(row);
        }
        return parsedData;
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

    private void checkImportCancelled(BackgroundSqlTask backSqlTask) {
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

    private static class ParsedImportData {
        private final Map<String, String> sourceColumns = new LinkedHashMap<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();
    }

    private static class ImportPlan {
        private final List<ColumnsInfo> targetColumns;
        private final List<Map<String, Object>> rows;

        private ImportPlan(List<ColumnsInfo> targetColumns, List<Map<String, Object>> rows) {
            this.targetColumns = targetColumns;
            this.rows = rows;
        }
    }

    private static class TableImportException extends Exception {
        private TableImportException(String message) {
            super(message);
        }
    }


}

