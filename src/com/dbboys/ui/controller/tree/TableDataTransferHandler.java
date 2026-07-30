package com.dbboys.ui.controller.tree;
import com.dbboys.ui.dialog.SqlExportManager;

import com.dbboys.app.AppContext;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.app.AppState;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.*;
import com.dbboys.ui.treemodel.*;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.TreeItem;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


public class TableDataTransferHandler {
    private static final Logger log = LogManager.getLogger(TableDataTransferHandler.class);

    public enum ExportFormat {CSV, JSON, SQL}

    static final class TableDataExportRequest {
        private final String tableName;
        private final String databaseName;
        private final Connect connect;
        private final File file;
        private final String sql;
        private final long totalRowsHint;

        TableDataExportRequest(String tableName,
                               String databaseName,
                               Connect connect,
                               File file,
                               String sql,
                               long totalRowsHint) {
            this.tableName = tableName;
            this.databaseName = databaseName;
            this.connect = connect;
            this.file = file;
            this.sql = sql;
            this.totalRowsHint = totalRowsHint;
        }
    }

    static final class DatabaseExportRuntime {
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
        private final Set<Connection> activeConnections = ConcurrentHashMap.newKeySet();

        void cancel() {
            cancelRequested.set(true);
            ExecutorService executor = executorRef.getAndSet(null);
            if (executor != null) {
                executor.shutdownNow();
            }
            for (Connection connection : activeConnections) {
                closeConnectionQuietly(connection);
            }
        }
    }

    public static void exportTableData(List<TreeItem<TreeData>> selectedItems, ExportFormat format) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return;
        }
        List<TreeItem<TreeData>> tableItems = new ArrayList<>();
        for (TreeItem<TreeData> item : selectedItems) {
            if (item != null && item.getValue() instanceof Table) {
                tableItems.add(item);
            }
        }
        if (tableItems.isEmpty()) {
            return;
        }
        if (format == ExportFormat.SQL) {
            try {
                if (containsUnsupportedLobForSqlExport(tableItems)) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            I18n.t("metadata.export.error.lob_not_supported_sql", "表包含大对象不支持导出为sql！")
                    );
                    return;
                }
            } catch (Exception e) {
                AppErrorHandler.handle(e);
                return;
            }
        }

        if (tableItems.size() == 1) {
            TreeItem<TreeData> tableItem = tableItems.get(0);
            Table table = (Table) tableItem.getValue();
            Connect connect = TreeObjectCrudHandler.buildObjectConnect(tableItem,false);

            FileChooser chooser = new FileChooser();
            File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
            if (desktopDir.exists()) chooser.setInitialDirectory(desktopDir);
            chooser.setTitle(I18n.t("metadata.export.title", "导出表数据"));
            String baseName = table.getName();
            switch (format) {
                case CSV -> {
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
                    chooser.setInitialFileName(baseName + ".csv");
                }
                case JSON -> {
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
                    chooser.setInitialFileName(baseName + ".json");
                }
                case SQL -> {
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL", "*.sql"));
                    chooser.setInitialFileName(baseName + ".sql");
                }
            }
            File file = chooser.showSaveDialog(AppState.getWindow());
            if (file == null) return;
            if (file.exists()) {
                file.delete();
            }

            String exportSql = "select * from " + table.getName();
            SqlExportManager.addSqlExportTask(
                    connect,
                    exportSql,
                    file,
                    format.name().toLowerCase(),
                    true,
                    table.getNrows(),
                    table.getName()
            );
            return;
        }

        // 多表导出：选择目录，按表名各生成一个文件和任务
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle(I18n.t("metadata.export.dir.title", "选择导出目录"));
        File dir = dirChooser.showDialog(AppState.getWindow());
        if (dir == null) {
            return;
        }

        String extension = switch (format) {
            case CSV -> ".csv";
            case JSON -> ".json";
            case SQL -> ".sql";
        };

        List<TableDataExportRequest> exportRequests = new ArrayList<>();
        for (TreeItem<TreeData> tableItem : tableItems) {
            Table table = (Table) tableItem.getValue();
            Connect connect = TreeObjectCrudHandler.buildObjectConnect(tableItem,false);
            if (connect == null) {
                continue;
            }
            String databaseName = connect == null ? "" : connect.getCatalog();
            File file = new File(dir, table.getName() + extension);
            exportRequests.add(new TableDataExportRequest(
                    table.getName(),
                    databaseName,
                    new Connect(connect),
                    file,
                    "select * from " + table.getName(),
                    table.getNrows()
            ));
        }
        if (exportRequests.isEmpty()) {
            return;
        }

        DatabaseExportRuntime runtime = new DatabaseExportRuntime();
        int totalTables = exportRequests.size();
        String progressPattern = I18n.t("metadata.export.multi.progress.tables", "表 %d/%d");
        String formatLabel = format.name();
        Task<Void> exportTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(0, Math.max(1, totalTables));
                updateMessage(progressPattern.formatted(0, totalTables));
                try {
                    List<String> failures = exportTableDataFilesParallel(
                            exportRequests,
                            format,
                            completed -> {
                                updateProgress(completed, Math.max(1, totalTables));
                                updateMessage(progressPattern.formatted(completed, totalTables));
                            },
                            () -> isDatabaseExportCancelled(this, runtime),
                            runtime,
                            "Table data export cancelled",
                            "Selected table"
                    );
                    if (!failures.isEmpty()) {
                        throw new Exception(buildSelectedTableExportFailureMessage(failures));
                    }
                    Platform.runLater(() -> NotificationUtil.showMainNotification(
                            I18n.t("metadata.export.multi.notice.completed", "已导出 %d 张表数据到：%s")
                                    .formatted(totalTables, dir.getAbsolutePath())
                    ));
                    updateProgress(1, 1);
                    return null;
                } catch (CancellationException e) {
                    runtime.cancel();
                    throw e;
                } catch (Exception e) {
                    runtime.cancel();
                    throw e;
                }
            }
        };
        String taskDisplayName = I18n.t("metadata.export.multi.task.summary", "导出%d张表数据(%s)")
                .formatted(totalTables, formatLabel);
        File taskPlaceholder = new File(
                dir,
                ".dbboys-multi-export-" + format.name().toLowerCase(Locale.ROOT) + "-" + System.nanoTime() + ".task"
        );
        SqlExportManager.addCustomExportTask(taskDisplayName, taskPlaceholder, true, exportTask, runtime::cancel);
    }

    static List<String> exportTableDataFilesParallel(List<TableDataExportRequest> exportRequests,
                                                     ExportFormat format,
                                                     java.util.function.IntConsumer progressUpdater,
                                                     java.util.function.BooleanSupplier cancelChecker,
                                                     DatabaseExportRuntime runtime,
                                                     String cancelMessage,
                                                     String logPrefix) throws Exception {
        List<TableDataExportRequest> validRequests = new ArrayList<>();
        if (exportRequests != null) {
            for (TableDataExportRequest request : exportRequests) {
                if (request != null
                        && request.connect != null
                        && request.tableName != null
                        && !request.tableName.isBlank()
                        && request.file != null
                        && request.sql != null
                        && !request.sql.isBlank()) {
                    validRequests.add(request);
                }
            }
        }
        if (validRequests.isEmpty()) {
            if (progressUpdater != null) {
                progressUpdater.accept(0);
            }
            return List.of();
        }

        int maxConcurrency = Math.min(8, validRequests.size());
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency, r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("dbboys-table-export-" + thread.getId());
            return thread;
        });
        runtime.executorRef.set(executor);
        ConcurrentLinkedQueue<TableDataExportRequest> requestQueue = new ConcurrentLinkedQueue<>(validRequests);
        AtomicInteger completedTables = new AtomicInteger(0);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int workerIndex = 0; workerIndex < maxConcurrency; workerIndex++) {
                final int workerNo = workerIndex + 1;
                futures.add(executor.submit(() -> {
                    List<String> workerFailures = new ArrayList<>();
                    java.util.Map<String, Connection> connectionCache = new java.util.HashMap<>();
                    try {
                        while (true) {
                            if (cancelChecker != null && cancelChecker.getAsBoolean()) {
                                throw new CancellationException(cancelMessage);
                            }
                            TableDataExportRequest request = requestQueue.poll();
                            if (request == null) {
                                break;
                            }

                            deleteFileQuietly(request.file);
                            try {
                                Connection conn = getOrCreateExportConnection(request.connect, connectionCache, runtime);
                                boolean exported = SqlExportManager.exportSqlToFile(
                                        conn,
                                        request.sql,
                                        request.file,
                                        format.name().toLowerCase(Locale.ROOT),
                                        request.totalRowsHint,
                                        null,
                                        cancelChecker,
                                        request.tableName
                                );
                                if (!exported) {
                                    deleteFileQuietly(request.file);
                                }
                            } catch (CancellationException e) {
                                deleteFileQuietly(request.file);
                                throw e;
                            } catch (Exception e) {
                                if (cancelChecker != null && cancelChecker.getAsBoolean()) {
                                    deleteFileQuietly(request.file);
                                    throw new CancellationException(cancelMessage);
                                }
                                deleteFileQuietly(request.file);
                                String message = e.getMessage();
                                log.error(
                                        "{} export failed. database={}, table={}, format={}, worker={}, thread={}",
                                        logPrefix,
                                        request.databaseName,
                                        request.tableName,
                                        format,
                                        workerNo,
                                        Thread.currentThread().getName(),
                                        e
                                );
                                workerFailures.add(request.tableName + ": " + (message == null || message.isBlank() ? e.toString() : message));
                            } finally {
                                int completed = completedTables.incrementAndGet();
                                if (progressUpdater != null) {
                                    progressUpdater.accept(completed);
                                }
                            }
                        }
                        return workerFailures;
                    } finally {
                        closeExportConnections(connectionCache, runtime);
                    }
                }));
            }

            List<String> failures = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                try {
                    List<String> workerFailures = future.get();
                    if (workerFailures != null && !workerFailures.isEmpty()) {
                        failures.addAll(workerFailures);
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancellationException cancellationException) {
                        throw cancellationException;
                    }
                    if (e instanceof CancellationException cancellationException) {
                        throw cancellationException;
                    }
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException(cancelMessage);
                    }
                    String message = cause == null ? e.getMessage() : cause.getMessage();
                    failures.add(message == null || message.isBlank() ? e.toString() : message);
                }
            }
            return failures;
        } finally {
            runtime.executorRef.compareAndSet(executor, null);
            executor.shutdownNow();
        }
    }

    private static Connection getOrCreateExportConnection(Connect connect,
                                                          java.util.Map<String, Connection> connectionCache,
                                                          DatabaseExportRuntime runtime) throws Exception {
        String cacheKey = buildExportConnectionCacheKey(connect);
        Connection cached = connectionCache.get(cacheKey);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }
        Connect workerConnect = new Connect(connect);
        Connection created = AppContext.get(com.dbboys.core.ConnectionService.class).getConnectionWithSessionInit(workerConnect);
        connectionCache.put(cacheKey, created);
        runtime.activeConnections.add(created);
        return created;
    }

    private static void closeExportConnections(java.util.Map<String, Connection> connectionCache,
                                               DatabaseExportRuntime runtime) {
        if (connectionCache == null || connectionCache.isEmpty()) {
            return;
        }
        for (Connection connection : connectionCache.values()) {
            runtime.activeConnections.remove(connection);
            closeConnectionQuietly(connection);
        }
        connectionCache.clear();
    }

    private static String buildExportConnectionCacheKey(Connect connect) {
        if (connect == null) {
            return "";
        }
        return String.join("|",
                normalizeExportConnectionToken(connect.getDbtype()),
                normalizeExportConnectionToken(connect.getIp()),
                normalizeExportConnectionToken(connect.getPort()),
                normalizeExportConnectionToken(connect.getCatalog()),
                normalizeExportConnectionToken(connect.getUsername()),
                normalizeExportConnectionToken(connect.getPassword()),
                normalizeExportConnectionToken(connect.getDriver()),
                normalizeExportConnectionToken(connect.getProps()));
    }

    private static String normalizeExportConnectionToken(String value) {
        return value == null ? "" : value.trim();
    }

    private static String buildSelectedTableExportFailureMessage(List<String> failures) {
        int limit = Math.min(failures.size(), 10);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(failures.get(i));
        }
        if (failures.size() > limit) {
            builder.append(" ...");
        }
        return I18n.t("metadata.export.multi.failure.summary", "共有 %d 张表导出失败：%s")
                .formatted(failures.size(), builder);
    }

    static boolean isDatabaseExportCancelled(Task<?> task, DatabaseExportRuntime runtime) {
        return (task != null && task.isCancelled())
                || Thread.currentThread().isInterrupted()
                || (runtime != null && runtime.cancelRequested.get());
    }

    static void deleteFileQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static void closeConnectionQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }

    private static boolean containsUnsupportedLobForSqlExport(List<TreeItem<TreeData>> tableItems) throws Exception {
        for (TreeItem<TreeData> tableItem : tableItems) {
            if (tableContainsUnsupportedLobForSqlExport(tableItem)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tableContainsUnsupportedLobForSqlExport(TreeItem<TreeData> tableItem) throws Exception {
        if (tableItem == null || !(tableItem.getValue() instanceof Table table)) {
            return false;
        }
        CatalogNode database = TreeNavigator.getCurrentDatabase(tableItem);
        Connect connect = TreeNavigator.getMetaConnect(tableItem);
        if (database == null || connect == null) {
            return false;
        }
        List<ColumnsInfo> columns = TreeViewUtil.tableService.getColumns(connect, database, table.getName());
        for (ColumnsInfo column : columns) {
            if (column != null && isUnsupportedLobTypeForSqlExport(column.getColType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsupportedLobTypeForSqlExport(String columnType) {
        String normalized = normalizeColumnType(columnType);
        return normalized.equals("byte")
                || normalized.endsWith("text")
                || normalized.contains("blob")
                || normalized.contains("clob");
    }

    private static String normalizeColumnType(String columnType) {
        if (columnType == null) {
            return "";
        }
        String normalized = columnType.trim().toLowerCase(Locale.ROOT);
        int parenIndex = normalized.indexOf('(');
        if (parenIndex >= 0) {
            normalized = normalized.substring(0, parenIndex);
        }
        return normalized.replaceAll("\\s+", " ").trim();
    }

    public static void importTableData(TreeItem<TreeData> selectedItem) {
        if (selectedItem == null || !(selectedItem.getValue() instanceof Table table)) {
            return;
        }

        CatalogNode database = TreeNavigator.getCurrentDatabase(selectedItem);
        if (database == null) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "错误"),
                    I18n.t("metadata.notice.database_not_found", "未找到当前数据库，数据库已被删除！")
            );
            return;
        }

        FileChooser chooser = new FileChooser();
            File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
            if (desktopDir.exists()) chooser.setInitialDirectory(desktopDir);
        chooser.setTitle(I18n.t("metadata.import.title", "导入表数据"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        I18n.t("metadata.import.filter.supported", "CSV / JSON 文件"),
                        "*.csv",
                        "*.json"
                ),
                new FileChooser.ExtensionFilter(
                        I18n.t("metadata.import.filter.csv", "CSV 文件"),
                        "*.csv"
                ),
                new FileChooser.ExtensionFilter(
                        I18n.t("metadata.import.filter.json", "JSON 文件"),
                        "*.json"
                )
        );
        File file = chooser.showOpenDialog(AppState.getWindow());
        if (file == null) {
            return;
        }

        Connect connect = TreeObjectCrudHandler.buildObjectConnect(selectedItem, false);
        TreeViewUtil.tableService.importTableData(connect, database, table.getName(), file, insertedRows ->
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.import.notice.completed", "表\"%s\"导入完成，已写入 %d 行")
                                .formatted(table.getName(), insertedRows)
                )
        );
    }
}
