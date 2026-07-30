package com.dbboys.ui.controller.tree;

import com.dbboys.app.AppErrorHandler;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.*;
import com.dbboys.ui.treemodel.*;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.icon.IconFactory;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;


public class TreeDdlViewHandler {

    public static void handleDdlAction(TreeView<TreeData> treeView, BiConsumer<TreeData, String> onSuccess) {
        ObservableList<TreeItem<TreeData>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        if (selectedItems == null || selectedItems.isEmpty()) {
            return;
        }

        List<TreeItem<TreeData>> items = new ArrayList<>(selectedItems);
        TreeItem<TreeData> firstItem = items.get(0);
        if (firstItem == null || firstItem.getValue() == null) {
            return;
        }
        TreeData firstData = firstItem.getValue();
        boolean multi = items.size() > 1;
        int totalItems = items.size();
        AtomicReference<Future<?>> ddlFuture = new AtomicReference<>();

        Task<String> ddlTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                String loadingMessage = I18n.t("metadata.menu.ddl.loading.message", "正在导出DDL...");
                String loadingProgressPattern = I18n.t("metadata.menu.ddl.loading.progress", "正在导出DDL... (%d/%d)");
                StringBuilder sb = new StringBuilder();
                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                    return null;
                }
                updateProgress(multi ? 0 : -1, multi ? totalItems : 1);
                updateMessage(multi ? loadingProgressPattern.formatted(0, totalItems) : loadingMessage);
                int processed = 0;
                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                    return null;
                }
                for (TreeItem<TreeData> item : items) {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    if (item == null || item.getValue() == null) {
                        continue;
                    }
                    TreeData data = item.getValue();
                    if (data.isRunning()) {
                        continue;
                    }
                    data.setRunning(true);
                    updateMessage(multi ? loadingProgressPattern.formatted(processed + 1, totalItems) : loadingMessage);
                    Connect connectParam = TreeNavigator.getMetaConnect(item);
                    CatalogNode database = TreeNavigator.getCurrentDatabase(item);
                    String ddlText = "";
                    if (data instanceof Table) {
                        ddlText = TreeViewUtil.tableService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Index) {
                        ddlText = TreeViewUtil.indexService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof View) {
                        ddlText = TreeViewUtil.viewService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Trigger) {
                        ddlText = TreeViewUtil.triggerService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Sequence) {
                        ddlText = TreeViewUtil.sequenceService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Type) {
                        ddlText = TreeViewUtil.objectTypeService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Queue) {
                        ddlText = TreeViewUtil.queueService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof SchedulerJob) {
                        ddlText = TreeViewUtil.schedulerJobService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof RecycleBinObject) {
                        ddlText = TreeViewUtil.recycleBinService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Synonym) {
                        ddlText = TreeViewUtil.synonymService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Function) {
                        ddlText = TreeViewUtil.functionService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Procedure) {
                        ddlText = TreeViewUtil.procedureService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof DBPackage) {
                        ddlText = TreeViewUtil.packageService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof PackageFunction || data instanceof PackageProcedure) {
                        ddlText = TreeViewUtil.packageService.getChildrenDDL(
                                ((DBPackage) item.getParent().getValue()).getDDL(), data.getName());
                    }

                    if (isCancelled() || Thread.currentThread().isInterrupted()) {
                        data.setRunning(false);
                        return null;
                    }
                    if (!multi) {
                        data.setRunning(false);
                        updateProgress(1, 1);
                        return ddlText;
                    }
                    if (ddlText != null && !ddlText.isEmpty()) {
                        sb.append("-- ").append(data.getName()).append(System.lineSeparator());
                        sb.append(ddlText).append(System.lineSeparator()).append(System.lineSeparator());
                    }
                    processed++;
                    updateProgress(processed, totalItems);
                    data.setRunning(false);
                }
                return sb.toString();
            }
        };

        AlertUtil.ContentDialog loadingDialog = createDdlLoadingDialog(ddlTask, () -> {
            ddlTask.cancel(false);
            Future<?> future = ddlFuture.get();
            if (future != null) {
                future.cancel(false);
            }
        });
        ddlTask.setOnSucceeded(event1 -> {
            closeDdlLoadingDialog(loadingDialog);
            items.forEach(it -> {
                if (it != null && it.getValue() != null) {
                    it.getValue().setRunning(false);
                }
            });
            String ddlText = ddlTask.getValue();
            onSuccess.accept(firstData, ddlText == null ? "" : ddlText);
        });
        AppErrorHandler.bindTask(ddlTask, () -> {
            closeDdlLoadingDialog(loadingDialog);
            items.forEach(it -> {
                if (it != null && it.getValue() != null) {
                    it.getValue().setRunning(false);
                }
            });
        });

        loadingDialog.getStage().show();
        ddlFuture.set(TreeNavigator.getMetaConnect(firstItem).executeSqlTask(ddlTask));
    }

    public static void handleDatabaseDdlAction(TreeView<TreeData> treeView, BiConsumer<TreeData, String> onSuccess) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || !(selectedItem.getValue() instanceof CatalogNode database)) {
            return;
        }

        Connect connect = TreeNavigator.getMetaConnect(selectedItem);
        AtomicReference<Future<?>> ddlFuture = new AtomicReference<>();

        Task<String> ddlTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                String countingMessage = I18n.t("metadata.menu.ddl.loading.counting", "正在统计导出对象...");
                String loadingMessage = I18n.t("metadata.menu.ddl.loading.message", "正在导出DDL...");
                String loadingProgressPattern = I18n.t("metadata.menu.ddl.loading.progress", "正在导出DDL... (%d/%d)");
                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                    return null;
                }
                updateProgress(-1, 1);
                updateMessage(countingMessage);
                return TreeViewUtil.databaseService.exportDatabaseDdl(connect, database, (completed, total) -> {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) {
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
            }
        };

        AlertUtil.ContentDialog loadingDialog = createDdlLoadingDialog(ddlTask, () -> {
            ddlTask.cancel(false);
            Future<?> future = ddlFuture.get();
            if (future != null) {
                future.cancel(false);
            }
        });
        ddlTask.setOnSucceeded(event1 -> {
            closeDdlLoadingDialog(loadingDialog);
            onSuccess.accept(database, ddlTask.getValue() == null ? "" : ddlTask.getValue());
        });
        AppErrorHandler.bindTask(ddlTask, () -> closeDdlLoadingDialog(loadingDialog));

        loadingDialog.getStage().show();
        ddlFuture.set(connect.executeSqlTask(ddlTask));
    }

    private static AlertUtil.ContentDialog createDdlLoadingDialog(Task<?> ddlTask, Runnable cancelAction) {
        ButtonType cancelButtonType = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Label messageLabel = new Label(I18n.t("metadata.menu.ddl.loading.message", "正在导出DDL..."));
        messageLabel.setWrapText(true);
        messageLabel.getStyleClass().add("alert-message-label");
        ddlTask.messageProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isBlank()) {
                messageLabel.setText(newVal);
            }
        });

        ProgressBar progressBar = new ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);
        progressBar.progressProperty().bind(ddlTask.progressProperty());
        progressBar.setPrefWidth(280);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        ImageView loadingIcon = IconFactory.loadingImageView(0.8);
        HBox header = new HBox(10, loadingIcon, messageLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14, header, progressBar);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefWidth(280);

        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("metadata.menu.ddl.loading.title", "导出DDL"),
                content,
                360,
                150,
                cancelButtonType
        );
        Button cancelButton = dialog.getButton(cancelButtonType);
        if (cancelButton != null) {
            cancelButton.setOnAction(event -> {
                if (cancelAction != null) {
                    cancelAction.run();
                } else {
                    ddlTask.cancel(true);
                }
            });
        }
        dialog.getFrame().closeButton.setVisible(false);
        dialog.getFrame().closeButton.setManaged(false);
        dialog.getStage().setOnCloseRequest(event -> event.consume());
        return dialog;
    }

    private static void closeDdlLoadingDialog(AlertUtil.ContentDialog dialog) {
        if (dialog == null) {
            return;
        }
        if (dialog.getStage().isShowing()) {
            dialog.getStage().close();
        }
    }
}
