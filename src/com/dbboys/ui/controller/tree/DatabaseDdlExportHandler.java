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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;


public class DatabaseDdlExportHandler {

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
                    deleteDirectoryIfEmpty(exportDir);
                    throw e;
                } catch (Exception e) {
                    runtime.cancel();
                    TableDataTransferHandler.deleteFileQuietly(preDdlFile);
                    TableDataTransferHandler.deleteFileQuietly(postDdlFile);
                    deleteDirectoryIfEmpty(exportDir);
                    throw e;
                }
            }
        };
        DatabasePlatform taskPlatform = TreeNavigator.resolvePlatform(selectedItem);
        String taskNameKey = taskPlatform != null ? taskPlatform.getExportTaskNameI18nKey() : "metadata.export.ddl_data.task_name";
        String taskNameDefault = taskPlatform != null ? taskPlatform.getExportTaskNameDefaultText() : "导出数据库\"%s\"";
        String taskDisplayName = I18n.t(taskNameKey, taskNameDefault).formatted(database.getName());
        SqlExportManager.addCustomExportTask(taskDisplayName, preDdlFile, true, exportTask, runtime::cancel);
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
