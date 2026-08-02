package com.dbboys.ui.controller.tree;
import com.dbboys.ui.dialog.SqlExportManager;

import com.dbboys.app.AppState;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.*;
import com.dbboys.ui.treemodel.*;
import com.dbboys.ui.controller.tree.TableDataTransferHandler.DatabaseExportRuntime;
import com.dbboys.ui.controller.tree.TableDataTransferHandler.ExportFormat;
import com.dbboys.ui.controller.tree.TableDataTransferHandler.TableDataExportRequest;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.DirectoryChooser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;


public class DatabaseDdlExportHandler {

    @FunctionalInterface
    private interface ExportProgressReporter {
        void report(double progress, String message);
    }

    public static void exportDatabaseDdlAndData(TreeView<TreeData> treeView) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || !(selectedItem.getValue() instanceof CatalogNode database)) {
            return;
        }

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle(I18n.t("metadata.export.dir.title", "选择导出目录"));
        File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
        if (desktopDir.exists()) dirChooser.setInitialDirectory(desktopDir);
        File dir = dirChooser.showDialog(AppState.getWindow());
        if (dir == null) {
            return;
        }

        Connect exportBaseConnect = TreeObjectCrudHandler.buildObjectConnect(selectedItem, false);
        boolean databaseSchemaBundle = isDatabaseSchemaDatabaseLevelExport(selectedItem);
        File exportDir = new File(dir, database.getName() + ".dbb");
        if (!ensureDatabaseExportDirectory(exportDir)) {
            return;
        }
        File preDdlFile = new File(exportDir, "01_pre_data.sql");
        File postDdlFile = new File(exportDir, "02_post_data.sql");
        DatabaseExportRuntime runtime = new DatabaseExportRuntime();
        if (preDdlFile.exists()) {
            preDdlFile.delete();
        }
        if (postDdlFile.exists()) {
            postDdlFile.delete();
        }

        Task<Void> exportTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                String countingMessage = I18n.t("metadata.export.ddl_data.progress.counting", "对象统计");
                String loadingMessage = I18n.t("metadata.export.ddl_data.progress.ddl_indeterminate", "DDL");
                String loadingProgressPattern = I18n.t("metadata.export.ddl_data.progress.ddl", "DDL %d/%d");
                String loadingTablesMessage = I18n.t("metadata.export.ddl_data.loading.tables", "表列表");
                String exportingTablesPattern = I18n.t("metadata.export.ddl_data.progress.tables", "表 %d/%d");
                if (TableDataTransferHandler.isDatabaseExportCancelled(this, runtime)) {
                    return null;
                }
                updateProgress(-1, 1);
                updateMessage(countingMessage);
                try {
                    if (databaseSchemaBundle) {
                        ExportProgressReporter reporter = (progress, message) -> {
                            updateProgress(progress, 1.0);
                            updateMessage(message);
                        };
                        exportDatabaseSchemaBundle(exportBaseConnect, database, exportDir, this, runtime, reporter);
                        DatabasePlatform exportPlatform = TreeNavigator.resolvePlatform(selectedItem);
                        String exportNoticeKey = exportPlatform != null ? exportPlatform.getExportNoticeI18nKey() : "metadata.export.ddl_data.notice.completed";
                        String exportNoticeDefault = exportPlatform != null ? exportPlatform.getExportNoticeDefaultText() : "数据库已导出到：%s";
                        Platform.runLater(() -> NotificationUtil.showMainNotification(
                                I18n.t(exportNoticeKey, exportNoticeDefault)
                                        .formatted(exportDir.getAbsolutePath())
                        ));
                        updateProgress(1, 1);
                        return null;
                    }
                    var ddlParts = TreeViewUtil.databaseService.exportDatabaseDdlPartsWithNewConnection(exportBaseConnect, database, (completed, total) -> {
                        if (TableDataTransferHandler.isDatabaseExportCancelled(this, runtime)) {
                            throw new CancellationException("Database DDL export cancelled");
                        }
                        if (total > 0) {
                            long safeCompleted = Math.min(completed, total);
                            updateProgress(safeCompleted, total);
                            updateMessage(loadingProgressPattern.formatted(safeCompleted, total));
                        } else {
                            updateProgress(-1, 1);
                            updateMessage(loadingMessage);
                        }
                    });
                    if (TableDataTransferHandler.isDatabaseExportCancelled(this, runtime)) {
                        throw new CancellationException("Database DDL export cancelled");
                    }
                    String bootstrapSql = buildDatabaseBootstrapSql(exportBaseConnect, database);
                    Files.writeString(preDdlFile.toPath(), bootstrapSql + ddlParts.getPreDataSql(), StandardCharsets.UTF_8);
                    Files.writeString(postDdlFile.toPath(), ddlParts.getPostDataSql(), StandardCharsets.UTF_8);
                    if (TableDataTransferHandler.isDatabaseExportCancelled(this, runtime)) {
                        throw new CancellationException("Database DDL export cancelled");
                    }
                    updateProgress(-1, 1);
                    updateMessage(loadingTablesMessage);
                    List<Table> tables = new ArrayList<>(TreeViewUtil.databaseService.getUserTablesWithNewConnection(exportBaseConnect, database));
                    if (TableDataTransferHandler.isDatabaseExportCancelled(this, runtime)) {
                        throw new CancellationException("Database DDL export cancelled");
                    }
                    int totalTables = tables.size();
                    updateProgress(0, Math.max(1, totalTables));
                    updateMessage(exportingTablesPattern.formatted(0, totalTables));
                    List<String> failures = exportDatabaseCsvFiles(
                            exportBaseConnect,
                            exportDir,
                            tables,
                            completed -> {
                                updateProgress(completed, Math.max(1, totalTables));
                                updateMessage(exportingTablesPattern.formatted(completed, totalTables));
                            },
                            () -> TableDataTransferHandler.isDatabaseExportCancelled(this, runtime),
                            runtime
                    );
                    if (!failures.isEmpty()) {
                        throw new Exception(buildDatabaseExportFailureMessage(failures));
                    }
                    DatabasePlatform exportPlatform = TreeNavigator.resolvePlatform(selectedItem);
                    String exportNoticeKey = exportPlatform != null ? exportPlatform.getExportNoticeI18nKey() : "metadata.export.ddl_data.notice.completed";
                    String exportNoticeDefault = exportPlatform != null ? exportPlatform.getExportNoticeDefaultText() : "数据库已导出到：%s";
                    Platform.runLater(() -> NotificationUtil.showMainNotification(
                            I18n.t(exportNoticeKey, exportNoticeDefault)
                                    .formatted(exportDir.getAbsolutePath())
                    ));
                    updateProgress(1, 1);
                    return null;
                } catch (CancellationException e) {
                    runtime.cancel();
                    TableDataTransferHandler.deleteFileQuietly(preDdlFile);
                    TableDataTransferHandler.deleteFileQuietly(postDdlFile);
                    TableDataTransferHandler.deleteFileQuietly(new File(exportDir, "00_create_database.sql"));
                    deleteDirectoryIfEmpty(exportDir);
                    throw e;
                } catch (Exception e) {
                    runtime.cancel();
                    TableDataTransferHandler.deleteFileQuietly(preDdlFile);
                    TableDataTransferHandler.deleteFileQuietly(postDdlFile);
                    TableDataTransferHandler.deleteFileQuietly(new File(exportDir, "00_create_database.sql"));
                    deleteDirectoryIfEmpty(exportDir);
                    throw e;
                }
            }
        };
        DatabasePlatform taskPlatform = TreeNavigator.resolvePlatform(selectedItem);
        String taskNameKey = taskPlatform != null ? taskPlatform.getExportTaskNameI18nKey() : "metadata.export.ddl_data.task_name";
        String taskNameDefault = taskPlatform != null ? taskPlatform.getExportTaskNameDefaultText() : "导出数据库\"%s\"";
        String taskDisplayName = I18n.t(taskNameKey, taskNameDefault).formatted(database.getName());
        File exportUiFile = databaseSchemaBundle
                ? new File(exportDir, "00_create_database.sql")
                : preDdlFile;
        SqlExportManager.addCustomExportTask(taskDisplayName, exportUiFile, true, exportTask, runtime::cancel);
    }

    private static List<String> exportDatabaseCsvFiles(Connect baseConnect,
                                                       File dir,
                                                       List<Table> tables,
                                                       java.util.function.IntConsumer progressUpdater,
                                                       java.util.function.BooleanSupplier cancelChecker,
                                                       DatabaseExportRuntime runtime) throws Exception {
        List<TableDataExportRequest> exportRequests = new ArrayList<>();
        if (tables != null) {
            for (Table table : tables) {
                if (table != null && table.getName() != null && !table.getName().isBlank()) {
                    exportRequests.add(new TableDataExportRequest(
                            table.getName(),
                            baseConnect == null ? "" : baseConnect.getCatalog(),
                            new Connect(baseConnect),
                            new File(dir, table.getName() + ".csv"),
                            "select * from " + table.getName(),
                            table.getNrows()
                    ));
                }
            }
        }
        return TableDataTransferHandler.exportTableDataFilesParallel(
                exportRequests,
                ExportFormat.CSV,
                progressUpdater,
                cancelChecker,
                runtime,
                "Database data export cancelled",
                "Database"
        );
    }

    /**
     * DATABASE_SCHEMA database-level export: writes {@code 00_create_database.sql}
     * at the bundle root and exports every non-system schema into its own
     * folder (in schema-name order), each with pre/post DDL scripts and CSV data.
     */
    private static void exportDatabaseSchemaBundle(Connect exportBaseConnect,
                                                   CatalogNode database,
                                                   File exportDir,
                                                   Task<Void> task,
                                                   DatabaseExportRuntime runtime,
                                                   ExportProgressReporter reporter) throws Exception {
        DatabasePlatform platform = TreeNavigator.resolvePlatformByDbtype(exportBaseConnect.getDbtype());
        File createDatabaseFile = new File(exportDir, "00_create_database.sql");
        if (createDatabaseFile.exists()) {
            createDatabaseFile.delete();
        }
        String createSql = platform == null
                ? "CREATE DATABASE \"" + database.getName().replace("\"", "\"\"") + "\""
                : platform.createDatabaseSql(database.getName(), "", "");
        Files.writeString(createDatabaseFile.toPath(),
                "-- Database export: " + database.getName() + "\n"
                        + createSql + ";\n",
                StandardCharsets.UTF_8);

        List<Schema> exportSchemas = new ArrayList<>();
        Connect dbConnect = new Connect(exportBaseConnect);
        dbConnect.setCatalog(database.getName());
        dbConnect.setSessionCatalog("");
        try (Connection conn = TreeViewUtil.connectionService.getConnectionWithSessionInit(dbConnect)) {
            List<Schema> schemas = platform == null ? List.of() : platform.metadata().getSchemas(conn);
            for (Schema schema : schemas) {
                if (platform == null || !platform.isSystemDatabase(schema.getName())) {
                    exportSchemas.add(schema);
                }
            }
        }
        exportSchemas.sort(Comparator.comparing(s -> s.getName().toLowerCase(Locale.ROOT)));

        String loadingSchemaPattern = I18n.t(
                "metadata.export.ddl_data.progress.schema",
                "导出模式 %d/%d - %s"
        );
        int totalSchemas = Math.max(1, exportSchemas.size());
        int schemaIndex = 0;
        for (Schema schema : exportSchemas) {
            if (TableDataTransferHandler.isDatabaseExportCancelled(task, runtime)) {
                throw new CancellationException("Database DDL export cancelled");
            }
            schemaIndex++;
            final int currentSchemaIndex = schemaIndex;
            reporter.report((double) schemaIndex / totalSchemas,
                    loadingSchemaPattern.formatted(schemaIndex, totalSchemas, schema.getName()));

            Schema schemaNode = new Schema(schema.getName());
            schemaNode.setParentDb(database.getName());
            File schemaDir = new File(exportDir, schema.getName());
            if (!schemaDir.isDirectory() && !schemaDir.mkdirs()) {
                throw new java.io.IOException("Failed to create schema export directory: "
                        + schemaDir.getAbsolutePath());
            }
            File preDdlFile = new File(schemaDir, "01_pre_data.sql");
            File postDdlFile = new File(schemaDir, "02_post_data.sql");
            if (preDdlFile.exists()) {
                preDdlFile.delete();
            }
            if (postDdlFile.exists()) {
                postDdlFile.delete();
            }

            try (Connection conn = TreeViewUtil.connectionService.getConnectionWithSessionInit(dbConnect)) {
                String ddl = platform == null ? "" : platform.ddl().printDatabase(conn, schema.getName());
                Files.writeString(preDdlFile.toPath(), ddl, StandardCharsets.UTF_8);
                Files.writeString(postDdlFile.toPath(), "", StandardCharsets.UTF_8);

                List<Table> tables = platform == null
                        ? List.of()
                        : platform.metadata().getUserTables(conn, schema.getName());
                Connect schemaConnect = new Connect(exportBaseConnect);
                schemaConnect.setCatalog(database.getName());
                schemaConnect.setSessionCatalog(schema.getName());
                List<String> failures = exportDatabaseCsvFiles(
                        schemaConnect,
                        schemaDir,
                        new ArrayList<>(tables),
                        completed -> reporter.report(
                                (currentSchemaIndex - 1 + (double) completed / Math.max(1, tables.size())) / totalSchemas,
                                loadingSchemaPattern.formatted(currentSchemaIndex, totalSchemas, schema.getName())),
                        () -> TableDataTransferHandler.isDatabaseExportCancelled(task, runtime),
                        runtime
                );
                if (!failures.isEmpty()) {
                    throw new Exception(buildDatabaseExportFailureMessage(failures));
                }
            }
        }
    }

    private static boolean isDatabaseSchemaDatabaseLevelExport(TreeItem<TreeData> selectedItem) {
        if (selectedItem == null || !(selectedItem.getValue() instanceof Database)) {
            return false;
        }
        DatabasePlatform platform = TreeNavigator.resolvePlatform(selectedItem);
        return platform != null
                && platform.catalogModel() == DatabasePlatform.CatalogModel.DATABASE_SCHEMA;
    }

    private static boolean ensureDatabaseExportDirectory(File exportDir) {
        if (exportDir == null) {
            return false;
        }
        if (exportDir.exists()) {
            if (exportDir.isDirectory()) {
                return true;
            }
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "错误"),
                    I18n.t("metadata.export.ddl_data.error.dir_conflict", "导出目录已存在同名文件：%s")
                            .formatted(exportDir.getAbsolutePath())
            );
            return false;
        }
        if (exportDir.mkdirs()) {
            return true;
        }
        AlertUtil.CustomAlert(
                I18n.t("common.error", "错误"),
                I18n.t("metadata.export.ddl_data.error.dir_create_failed", "创建导出目录失败：%s")
                        .formatted(exportDir.getAbsolutePath())
        );
        return false;
    }

    private static String buildDatabaseBootstrapSql(Connect connect, CatalogNode database) {
        if (connect == null || database == null) {
            return "";
        }
        DatabasePlatform platform = TreeNavigator.resolvePlatformByDbtype(connect.getDbtype());
        if (platform == null || !platform.canCreateDatabase()) {
            return "";
        }
        return platform.buildBootstrapSql(database);
    }

    private static String buildDatabaseExportFailureMessage(List<String> failures) {
        if (failures == null || failures.isEmpty()) {
            return "表数据导出失败";
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
        return I18n.t(
                "metadata.export.ddl_data.failure.summary",
                "共有 %d 张表导出失败：%s"
        ).formatted(failures.size(), builder);
    }

    private static void deleteDirectoryIfEmpty(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null && children.length == 0) {
                dir.delete();
            }
        }
    }
}
