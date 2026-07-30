package com.dbboys.ui.controller.tree;
import com.dbboys.service.BackgroundSqlService;

import com.dbboys.app.AppState;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.ConnectionPropertyUtil;
import com.dbboys.infra.util.SqlParserUtil;
import com.dbboys.model.*;
import com.dbboys.ui.treemodel.*;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.TreeItem;
import javafx.stage.DirectoryChooser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class DatabaseImportHandler {
    private static final Logger log = LogManager.getLogger(DatabaseImportHandler.class);
    private static final Pattern IMPORT_BUNDLE_DB_LOCALE_PATTERN =
            Pattern.compile("(?im)^\\s*--\\s*DB_LOCALE\\s*=\\s*(\\S+)\\s*$");
    private static final Pattern IMPORT_BUNDLE_CREATE_DATABASE_PATTERN =
            Pattern.compile("(?im)^\\s*create\\s+database\\s+([^\\s;]+)(?:\\s+in\\s+[^\\s;]+)?(?:\\s+with\\s+(buffered\\s+log|log))?\\s*;");
    private static final Pattern IMPORT_BUNDLE_DATABASE_PATTERN =
            Pattern.compile("(?im)^\\s*database\\s+([^\\s;]+)\\s*;");

    private static final class DatabaseImportBundle {
        private final File directory;
        private final File preDdlFile;
        private final File postDdlFile;
        private final String databaseName;
        private final String dbLocale;
        private final String dbLog;
        private final List<File> dataFiles;

        private DatabaseImportBundle(File directory,
                                     File preDdlFile,
                                     File postDdlFile,
                                     String databaseName,
                                     String dbLocale,
                                     String dbLog,
                                     List<File> dataFiles) {
            this.directory = directory;
            this.preDdlFile = preDdlFile;
            this.postDdlFile = postDdlFile;
            this.databaseName = databaseName;
            this.dbLocale = dbLocale;
            this.dbLog = dbLog;
            this.dataFiles = dataFiles;
        }
    }

    private static final class DatabaseImportRuntime {
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
        private final Set<BackgroundSqlTask> activeTasks = ConcurrentHashMap.newKeySet();

        private void registerTask(BackgroundSqlTask task) {
            if (task == null) {
                return;
            }
            activeTasks.add(task);
            if (cancelRequested.get()) {
                task.cancel();
            }
        }

        private void unregisterTask(BackgroundSqlTask task) {
            if (task != null) {
                activeTasks.remove(task);
            }
        }

        private void cancel() {
            cancelRequested.set(true);
            ExecutorService executor = executorRef.getAndSet(null);
            if (executor != null) {
                executor.shutdownNow();
            }
            for (BackgroundSqlTask task : activeTasks) {
                task.cancel();
            }
        }

        private void clear() {
            activeTasks.clear();
            executorRef.set(null);
        }
    }

    public static void importDatabaseDdlAndData(TreeItem<TreeData> selectedItem) {
        if (selectedItem == null || !(selectedItem.getValue() instanceof Database)) {
            return;
        }

        Connect metaConnect = TreeNavigator.getMetaConnect(selectedItem);
        if (metaConnect == null) {
            AlertUtil.CustomAlert(
                    I18n.t("metadata.import_ddl_data.error.title", "导入数据库失败"),
                    I18n.t("metadata.import_ddl_data.error.connection_missing", "未找到可用连接，无法导入数据库")
            );
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("metadata.import_ddl_data.dir.title", "选择数据库导出目录"));
        File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
        if (desktopDir.exists()) chooser.setInitialDirectory(desktopDir);
        File dir = chooser.showDialog(AppState.getWindow());
        if (dir == null) {
            return;
        }

        Connect baseConnect = new Connect(metaConnect);
        long beginMillis = System.currentTimeMillis();
        String beginTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(beginMillis);
        String queuedDatabaseName = parseBundleDatabaseName("", dir);
        String importSummary = buildDatabaseImportTaskTitle(queuedDatabaseName, null);
        BackgroundSqlTask backSqlTask = new BackgroundSqlTask();
        DatabaseImportRuntime runtime = new DatabaseImportRuntime();
        backSqlTask.setConnect(baseConnect);
        backSqlTask.setBeginTime(beginTime);
        backSqlTask.setConnectName(baseConnect.getName());
        backSqlTask.setDatabaseName(queuedDatabaseName);
        backSqlTask.setSql(importSummary);
        backSqlTask.setProgress(I18n.t("metadata.import_ddl_data.progress.validating", "校验目录"));
        BackgroundSqlService.backSqlTaskList.add(backSqlTask);
        BackgroundSqlService.updateBackSqlUIOnStart();

        AtomicReference<DatabaseImportBundle> bundleRef = new AtomicReference<>();
        Task<Integer> bgTask = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                UpdateResult updateResult = new UpdateResult();
                updateResult.setConnectId(baseConnect.getId());
                updateResult.setDatabase(queuedDatabaseName);
                updateResult.setUpdateSql(importSummary);
                updateResult.setStartTime(beginTime);
                try {
                    DatabaseImportBundle bundle = resolveDatabaseImportBundle(dir);
                    bundleRef.set(bundle);
                    backSqlTask.setDatabaseName(bundle.databaseName);
                    int flowTotalSteps = resolveDatabaseImportFlowTotalSteps(bundle);
                    String preparingSql = formatDatabaseImportPreparingStep(bundle.databaseName, flowTotalSteps);
                    Platform.runLater(() -> backSqlTask.setSql(preparingSql));
                    updateResult.setDatabase(bundle.databaseName);
                    updateResult.setUpdateSql(buildDatabaseImportTaskTitle(bundle.databaseName, null));
                    BackgroundSqlService.updateTaskProgress(
                            backSqlTask,
                            I18n.t("metadata.import_ddl_data.progress.preparing", "准备中")
                    );
                    int affectedRows = importDatabaseBundle(baseConnect, bundle, backSqlTask, runtime);
                    long endMillis = System.currentTimeMillis();
                    updateResult.setAffectedRows(affectedRows);
                    updateResult.setElapsedTime(String.format("%.3f", (endMillis - beginMillis) / 1000.0) + " sec");
                    updateResult.setEndTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(endMillis));
                    updateResult.setMark(I18n.t("backsql.history.mark.ui_task", "界面操作任务,独立事务"));
                    LocalDbRepository.saveSqlHistory(updateResult);
                    return affectedRows;
                } catch (CancellationException e) {
                    runtime.cancel();
                    throw e;
                } catch (SQLException e) {
                    runtime.cancel();
                    BackgroundSqlService.handleBackgroundSqlError(backSqlTask, e);
                    throw e;
                } catch (Exception e) {
                    runtime.cancel();
                    String message = e.getMessage();
                    if (message == null || message.isBlank()) {
                        message = I18n.t("metadata.import_ddl_data.error.unknown", "导入数据库失败，请检查目录内容或数据库连接");
                    }
                    showDatabaseBundleImportError(message);
                    throw e;
                } finally {
                    backSqlTask.setStmt(null);
                    backSqlTask.setConnection(null);
                    BackgroundSqlService.backSqlTaskList.remove(backSqlTask);
                    BackgroundSqlService.updateBackSqlUIOnFinish();
                    runtime.clear();
                }
            }
        };
        bgTask.setOnSucceeded(event -> {
            DatabaseImportBundle bundle = bundleRef.get();
            String databaseName = bundle == null ? queuedDatabaseName : bundle.databaseName;
            String bundlePath = bundle == null ? dir.getAbsolutePath() : bundle.directory.getAbsolutePath();
            selectedItem.getChildren().clear();
            selectedItem.setExpanded(false);
            selectedItem.setExpanded(true);
            NotificationUtil.showMainNotification(
                    I18n.t("metadata.import_ddl_data.notice.completed", "数据库\"%s\"导入完成：%s")
                            .formatted(databaseName, bundlePath)
            );
        });
        backSqlTask.setCancelAction(() -> {
            runtime.cancel();
            bgTask.cancel(true);
        });
        backSqlTask.setFuture(BackgroundSqlService.backSqlExecutor.submit(bgTask));
    }

    private static int importDatabaseBundle(Connect baseConnect,
                                            DatabaseImportBundle bundle,
                                            BackgroundSqlTask backSqlTask,
                                            DatabaseImportRuntime runtime) throws Exception {
        if (baseConnect == null || bundle == null) {
            return 0;
        }

        Database database = new Database(bundle.databaseName);
        database.setDbLocale(bundle.dbLocale);
        database.setDbLog(bundle.dbLog);
        Connect bootstrapConnect = new Connect(baseConnect);
        applyImportBootstrapConnectionProps(bootstrapConnect);
        if (bundle.dbLocale != null && !bundle.dbLocale.isBlank()) {
            ConnectionPropertyUtil.applySupportedConnectionProperty(
                    TreeViewUtil.connectionService,
                    TreeObjectCrudHandler.resolvePlatformResolver(),
                    bootstrapConnect,
                    TreeObjectCrudHandler.PROP_DB_LOCALE,
                    bundle.dbLocale
            );
        }

        int affectedRows = 0;
        int flowTotalSteps = resolveDatabaseImportFlowTotalSteps(bundle);
        updateDatabaseImportTask(
                backSqlTask,
                bootstrapConnect,
                database.getName(),
                formatDatabaseImportFlowStep(
                        database.getName(),
                        1,
                        flowTotalSteps,
                        "metadata.import_ddl_data.task.flow.phase_ddl",
                        "导入DDL")
        );
        affectedRows += TreeViewUtil.databaseService.importSqlScriptSync(
                new Connect(bootstrapConnect),
                bundle.preDdlFile,
                backSqlTask
        );
        backSqlTask.setConnection(null);
        backSqlTask.setStmt(null);

        Connect databaseConnect = new Connect(baseConnect);
        TreeObjectCrudHandler.applyDatabaseConnectionProps(databaseConnect, database, database.getName());
        affectedRows += importDatabaseDataFilesParallel(
                databaseConnect,
                database,
                bundle.dataFiles,
                backSqlTask,
                runtime,
                flowTotalSteps);

        if (bundle.postDdlFile != null) {
            throwIfDatabaseImportCancelled(backSqlTask);
            updateDatabaseImportTask(
                    backSqlTask,
                    databaseConnect,
                    database.getName(),
                    formatDatabaseImportFlowStep(
                            database.getName(),
                            flowTotalSteps,
                            flowTotalSteps,
                            "metadata.import_ddl_data.task.flow.phase_constraints",
                            "导入约束/索引/触发器")
            );
            affectedRows += TreeViewUtil.databaseService.importSqlScriptSync(
                    new Connect(databaseConnect),
                    bundle.postDdlFile,
                    backSqlTask
            );
            backSqlTask.setConnection(null);
            backSqlTask.setStmt(null);
        }
        return affectedRows;
    }

    private static void applyImportBootstrapConnectionProps(Connect connect) {
        if (connect == null) {
            return;
        }
        var platform = TreeObjectCrudHandler.resolvePlatformResolver().getPlatform(connect.getDbtype());
        if (platform == null) {
            return;
        }
        String bootstrapCatalog = platform.connection().defaultDatabase();
        if (bootstrapCatalog == null || bootstrapCatalog.isBlank()) {
            return;
        }
        platform.connection().setSessionCatalog(connect, bootstrapCatalog);
    }

    private static int importDatabaseDataFilesParallel(Connect databaseConnect,
                                                       Database database,
                                                       List<File> dataFiles,
                                                       BackgroundSqlTask backSqlTask,
                                                       DatabaseImportRuntime runtime,
                                                       int flowTotalSteps) throws Exception {
        List<File> validFiles = new ArrayList<>();
        if (dataFiles != null) {
            for (File dataFile : dataFiles) {
                if (dataFile != null && dataFile.isFile()) {
                    String tableName = resolveBundleTableName(dataFile);
                    if (!tableName.isBlank()) {
                        validFiles.add(dataFile);
                    }
                }
            }
        }
        updateDatabaseImportTask(
                backSqlTask,
                databaseConnect,
                database.getName(),
                formatDatabaseImportFlowStep(
                        database.getName(),
                        2,
                        flowTotalSteps,
                        "metadata.import_ddl_data.task.flow.phase_data",
                        "导入数据")
        );
        if (validFiles.isEmpty()) {
            return 0;
        }

        int totalTables = validFiles.size();
        BackgroundSqlService.updateTaskProgress(backSqlTask, formatDatabaseImportTableProgress(0, totalTables));

        int maxConcurrency = Math.min(8, totalTables);
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency, r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("dbboys-db-import-" + thread.getId());
            return thread;
        });
        runtime.executorRef.set(executor);

        ConcurrentLinkedQueue<File> fileQueue = new ConcurrentLinkedQueue<>(validFiles);
        AtomicInteger completedTables = new AtomicInteger(0);
        AtomicInteger affectedRows = new AtomicInteger(0);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int workerIndex = 0; workerIndex < maxConcurrency; workerIndex++) {
                final int workerNo = workerIndex + 1;
                futures.add(executor.submit(() -> {
                    List<String> workerFailures = new ArrayList<>();
                    while (true) {
                        if (isDatabaseImportCancelled(runtime, backSqlTask)) {
                            throw new CancellationException("Database data import cancelled");
                        }
                        File dataFile = fileQueue.poll();
                        if (dataFile == null) {
                            break;
                        }

                        String tableName = resolveBundleTableName(dataFile);
                        BackgroundSqlTask workerTask = new BackgroundSqlTask();
                        Connect workerConnect = new Connect(databaseConnect);
                        workerTask.setConnect(workerConnect);
                        workerTask.setConnectName(workerConnect.getName());
                        workerTask.setDatabaseName(database.getName());
                        workerTask.setSql(
                                I18n.t("metadata.import_ddl_data.task.table", "导入表%s <- %s")
                                        .formatted(tableName, dataFile.getName())
                        );
                        runtime.registerTask(workerTask);
                        try {
                            Database workerDatabase = copyImportDatabase(database);
                            int importedRows = TreeViewUtil.tableService.importTableDataSync(
                                    new Connect(workerConnect),
                                    workerDatabase,
                                    tableName,
                                    dataFile,
                                    workerTask
                            );
                            affectedRows.addAndGet(importedRows);
                        } catch (CancellationException e) {
                            throw e;
                        } catch (Exception e) {
                            if (isDatabaseImportCancelled(runtime, backSqlTask)) {
                                throw new CancellationException("Database data import cancelled");
                            }
                            String message = e.getMessage();
                            if (message == null || message.isBlank()) {
                                message = e.toString();
                            }
                            workerFailures.add(tableName + ": " + message);
                            log.error(
                                    "Database data import failed. database={}, table={}, worker={}, thread={}",
                                    database.getName(),
                                    tableName,
                                    workerNo,
                                    Thread.currentThread().getName(),
                                    e
                            );
                        } finally {
                            runtime.unregisterTask(workerTask);
                            int completed = completedTables.incrementAndGet();
                            BackgroundSqlService.updateTaskProgress(
                                    backSqlTask,
                                    formatDatabaseImportTableProgress(completed, totalTables)
                            );
                        }
                    }
                    return workerFailures;
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
                        throw new CancellationException("Database data import cancelled");
                    }
                    String message = cause == null ? e.getMessage() : cause.getMessage();
                    failures.add(message == null || message.isBlank() ? e.toString() : message);
                }
            }
            if (!failures.isEmpty()) {
                throw new Exception(buildDatabaseImportFailureMessage(failures));
            }
            return affectedRows.get();
        } finally {
            runtime.executorRef.compareAndSet(executor, null);
            executor.shutdownNow();
        }
    }

    private static Database copyImportDatabase(Database database) {
        Database copy = new Database(database == null ? "" : database.getName());
        if (database != null) {
            copy.setDbLocale(database.getDbLocale());
            copy.setDbLog(database.getDbLog());
            copy.setDbSpace(database.getDbSpace());
        }
        return copy;
    }

    /** 并行导入表阶段：进度列仅显示数字比例，流程文案在「SQL任务」列（{@link #updateDatabaseImportTask} 的 phase）。 */
    private static String formatDatabaseImportTableProgress(int completedTables, int totalTables) {
        int safeCompleted = Math.max(0, completedTables);
        int safeTotal = Math.max(safeCompleted, Math.max(0, totalTables));
        return "%d/%d".formatted(safeCompleted, safeTotal);
    }

    private static String buildDatabaseImportFailureMessage(List<String> failures) {
        if (failures == null || failures.isEmpty()) {
            return I18n.t("metadata.import_ddl_data.error.unknown", "导入数据库失败，请检查目录内容或数据库连接");
        }
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
        return I18n.t("metadata.import_ddl_data.failure.summary", "共有 %d 张表导入失败：%s")
                .formatted(failures.size(), builder);
    }

    private static boolean isDatabaseImportCancelled(DatabaseImportRuntime runtime, BackgroundSqlTask backSqlTask) {
        return (runtime != null && runtime.cancelRequested.get())
                || (backSqlTask != null && backSqlTask.isCancelled())
                || Thread.currentThread().isInterrupted();
    }

    private static DatabaseImportBundle resolveDatabaseImportBundle(File dir) throws Exception {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            throw new IOException(I18n.t("metadata.import_ddl_data.error.invalid_dir", "导入目录无效，请选择 .dbb 导出目录：%s")
                    .formatted(dir == null ? "" : dir.getAbsolutePath()));
        }

        File preDdlFile = findBundleSqlFile(dir, "01_pre_data.sql", "_01_pre_data.sql");
        if (preDdlFile == null) {
            throw new IOException(I18n.t("metadata.import_ddl_data.error.pre_missing", "导入目录缺少预处理脚本：01_pre_data.sql"));
        }

        String preSql = readBundleScript(preDdlFile);
        if (SqlParserUtil.countExecutableStatements(preSql) <= 0) {
            throw new IOException(I18n.t("metadata.import_ddl_data.error.pre_invalid", "预处理脚本中没有可执行语句：%s")
                    .formatted(preDdlFile.getName()));
        }

        File postDdlFile = findBundleSqlFile(dir, "02_post_data.sql", "_02_post_data.sql");
        if (postDdlFile != null) {
            String postSql = readBundleScript(postDdlFile);
            if (SqlParserUtil.countExecutableStatements(postSql) <= 0) {
                postDdlFile = null;
            }
        }

        String databaseName = parseBundleDatabaseName(preSql, dir);
        if (databaseName.isBlank()) {
            throw new IOException(I18n.t("metadata.import_ddl_data.error.database_name", "无法识别导入数据库名：%s")
                    .formatted(dir.getAbsolutePath()));
        }

        List<File> dataFiles = listBundleDataFiles(dir, preDdlFile, postDdlFile);
        return new DatabaseImportBundle(
                dir,
                preDdlFile,
                postDdlFile,
                databaseName,
                parseBundleDbLocale(preSql),
                parseBundleDbLog(preSql),
                dataFiles
        );
    }

    private static File findBundleSqlFile(File dir, String standardName, String legacySuffix) {
        File standardFile = new File(dir, standardName);
        if (standardFile.isFile()) {
            return standardFile;
        }
        File[] candidates = dir.listFiles(file ->
                file != null
                        && file.isFile()
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(legacySuffix.toLowerCase(Locale.ROOT))
        );
        if (candidates == null || candidates.length == 0) {
            return null;
        }
        List<File> files = new ArrayList<>(List.of(candidates));
        files.sort(Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        return files.get(0);
    }

    private static List<File> listBundleDataFiles(File dir, File preDdlFile, File postDdlFile) {
        File[] files = dir.listFiles(file -> {
            if (file == null || !file.isFile()) {
                return false;
            }
            if (matchesFile(preDdlFile, file) || matchesFile(postDdlFile, file)) {
                return false;
            }
            String lowerName = file.getName().toLowerCase(Locale.ROOT);
            return lowerName.endsWith(".csv") || lowerName.endsWith(".json");
        });
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }
        List<File> dataFiles = new ArrayList<>(List.of(files));
        dataFiles.sort(Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        return dataFiles;
    }

    private static boolean matchesFile(File expected, File actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private static String readBundleScript(File file) throws IOException {
        return stripLeadingBom(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    private static String stripLeadingBom(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static String parseBundleDatabaseName(String preSql, File dir) {
        String databaseName = extractBundleToken(IMPORT_BUNDLE_CREATE_DATABASE_PATTERN, preSql, 1);
        if (databaseName.isBlank()) {
            databaseName = extractBundleToken(IMPORT_BUNDLE_DATABASE_PATTERN, preSql, 1);
        }
        if (!databaseName.isBlank()) {
            return databaseName;
        }
        if (dir == null || dir.getName() == null) {
            return "";
        }
        String directoryName = dir.getName();
        if (directoryName.toLowerCase(Locale.ROOT).endsWith(".dbb")) {
            directoryName = directoryName.substring(0, directoryName.length() - 4);
        }
        return normalizeImportBundleToken(directoryName);
    }

    private static String parseBundleDbLocale(String preSql) {
        return extractBundleToken(IMPORT_BUNDLE_DB_LOCALE_PATTERN, preSql, 1);
    }

    private static String parseBundleDbLog(String preSql) {
        Matcher matcher = IMPORT_BUNDLE_CREATE_DATABASE_PATTERN.matcher(preSql == null ? "" : preSql);
        if (!matcher.find()) {
            return "";
        }
        String logMode = matcher.group(2);
        if (logMode == null || logMode.isBlank()) {
            return "nolog";
        }
        if ("buffered log".equalsIgnoreCase(logMode.trim())) {
            return "buffered";
        }
        return "unbuffered";
    }

    private static String extractBundleToken(Pattern pattern, String text, int groupIndex) {
        if (pattern == null || text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find() || matcher.groupCount() < groupIndex) {
            return "";
        }
        return normalizeImportBundleToken(matcher.group(groupIndex));
    }

    private static String normalizeImportBundleToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.length() >= 2 && (
                (normalized.startsWith("\"") && normalized.endsWith("\""))
                        || (normalized.startsWith("`") && normalized.endsWith("`"))
                        || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static String resolveBundleTableName(File dataFile) {
        if (dataFile == null) {
            return "";
        }
        String fileName = dataFile.getName();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private static void updateDatabaseImportTask(BackgroundSqlTask backSqlTask,
                                                 Connect connect,
                                                 String databaseName,
                                                 String phaseLabel) {
        throwIfDatabaseImportCancelled(backSqlTask);
        if (backSqlTask == null) {
            return;
        }
        if (connect != null) {
            backSqlTask.setConnect(connect);
            backSqlTask.setConnectName(connect.getName());
        }
        backSqlTask.setDatabaseName(databaseName);
        // 「SQL任务」列与进度在 JavaFX 线程更新，否则 TableView 对 sql 列可能仍停留在上一阶段（如「导入 DDL」）
        final String sqlText = phaseLabel == null || phaseLabel.isBlank()
                ? buildDatabaseImportTaskTitle(databaseName, null)
                : phaseLabel.trim();
        final String runningProgress = I18n.t("metadata.import_ddl_data.progress.running", "执行中");
        Platform.runLater(() -> {
            if (backSqlTask.isCancelled()) {
                return;
            }
            backSqlTask.setSql(sqlText);
            backSqlTask.setProgress(runningProgress);
        });
    }

    private static String safeImportDatabaseDisplayName(String databaseName) {
        return databaseName == null || databaseName.isBlank() ? "?" : databaseName.trim();
    }

    /** 有 02_post 脚本为 3 步（DDL / 数据 / 约束索引触发器），否则为 2 步。 */
    private static int resolveDatabaseImportFlowTotalSteps(DatabaseImportBundle bundle) {
        return bundle != null && bundle.postDdlFile != null ? 3 : 2;
    }

    private static String formatDatabaseImportFlowStep(String databaseName,
                                                       int step,
                                                       int totalSteps,
                                                       String phaseKey,
                                                       String phaseFallback) {
        String phase = I18n.t(phaseKey, phaseFallback);
        return I18n.t("metadata.import_ddl_data.task.flow.step_line", "导入数据库 %s %d/%d - %s")
                .formatted(safeImportDatabaseDisplayName(databaseName), step, totalSteps, phase);
    }

    private static String formatDatabaseImportPreparingStep(String databaseName, int totalSteps) {
        return I18n.t("metadata.import_ddl_data.task.flow.preparing_step", "导入数据库 %s 0/%d - 准备导入")
                .formatted(safeImportDatabaseDisplayName(databaseName), totalSteps);
    }

    private static String buildDatabaseImportTaskTitle(String databaseName, String phaseLabel) {
        String safeDatabaseName = safeImportDatabaseDisplayName(databaseName);
        String title = I18n.t("metadata.import_ddl_data.task.summary", "导入数据库\"%s\"")
                .formatted(safeDatabaseName);
        if (phaseLabel == null || phaseLabel.isBlank()) {
            return title;
        }
        return title + " - " + phaseLabel.trim();
    }

    private static void throwIfDatabaseImportCancelled(BackgroundSqlTask backSqlTask) {
        if ((backSqlTask != null && backSqlTask.isCancelled()) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("database ddl and data import cancelled");
        }
    }

    private static void showDatabaseBundleImportError(String message) {
        Platform.runLater(() -> AlertUtil.CustomAlert(
                I18n.t("metadata.import_ddl_data.error.title", "导入数据库失败"),
                message
        ));
    }
}
