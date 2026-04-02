package com.dbboys.util.tree;

import com.dbboys.app.AppContext;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.app.AppState;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.impl.DialectServices;
import com.dbboys.api.MetaObjectService;
import com.dbboys.ui.IconFactory;
import com.dbboys.util.*;
import com.dbboys.vo.*;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.function.BiConsumer;


public class TreeCrudHandler {
    private static final Logger log = LogManager.getLogger(TreeCrudHandler.class);
    private static final String PROP_DB_LOCALE = "DB_LOCALE";
    private static final String PROP_IFX_ISOLATION_LEVEL = "IFX_ISOLATION_LEVEL";

    private static final class DatabaseExportRuntime {
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
        private final Set<Connection> activeConnections = ConcurrentHashMap.newKeySet();

        private void cancel() {
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

    public enum ExportFormat {CSV, JSON, SQL}

    //重命名节点
    public static void renameTreeItem(TreeView<TreeData> treeView) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        String title = buildRenameTitle(selectedItem.getValue());
        HBox hbox = new HBox();
        hbox.getChildren().add(new Label(I18n.t("metadata.dialog.rename.input", "请输入重命名名称  ")));
        hbox.setAlignment(Pos.CENTER_LEFT);
        CustomUserTextField textField = new CustomUserTextField();
        textField.setPrefWidth(200);
        textField.setText(selectedItem.getValue().getName());
        textField.positionCaret(textField.getText().length());
        hbox.getChildren().add(textField);

        ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(title, hbox, 430, 180, buttonTypeOk, buttonTypeCancel);
        Button button = dialog.getButton(buttonTypeOk);
        button.setDisable(true);
        textField.requestFocus();
        textField.textProperty().addListener((observable, oldValue, newValue) -> {
            textField.setText(newValue.replace(" ", ""));
            if (!textField.getText().isEmpty() && !textField.getText().equals(selectedItem.getValue().getName())) {
                Boolean exists = false;

                //如果是数据库连接，需要判断所有分类里是否重复
                if(selectedItem.getValue() instanceof Connect){
                    for (TreeItem<TreeData> treeItem : treeView.getRoot().getChildren()){
                        for (TreeItem<TreeData> treeItem1 : treeItem.getChildren()){
                            if (treeItem1.getValue().getName().equals(textField.getText())) {
                                exists = true;
                            }
                        }
                    }
                }else{
                    for (TreeItem<TreeData> treeItem : selectedItem.getParent().getChildren()) {
                        if (treeItem.getValue().getName().equals(textField.getText())) {
                            exists = true;
                        }
                    }
                }
                if (exists) {
                    button.setDisable(true);
                } else {
                    button.setDisable(false);
                }
            } else {
                button.setDisable(true);
            }
        });

        ButtonType result = dialog.showAndWait();
        if (result == buttonTypeOk) {
            String newName = textField.getText();
            TreeData treeData = selectedItem.getValue();
            if (treeData instanceof ConnectFolder) {
                treeData.setName(newName);
                if (selectedItem.getParent().getChildren().size() > 1) { //多于1个分类，重新排序
                    TreeViewBuilder.reorderTreeview(treeView, selectedItem);
                }
                LocalDbRepository.updateConnectFolder((ConnectFolder) treeData);
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.folder_renamed", "分类已重命名为：%s").formatted(selectedItem.getValue().getName()));
            }else if(treeData instanceof Connect){
                selectedItem.getValue().setName(newName);
                //connect_list_treeview.refresh();
                if(selectedItem.getParent().getChildren().size()>1) {//多于1个连接重新排序
                    TreeViewBuilder.reorderTreeview(treeView, selectedItem);
                }
                LocalDbRepository.updateConnect((Connect) selectedItem.getValue());
                TabpaneUtil.isRefreshConnectList();
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.connection_renamed", "连接已重命名为：%s").formatted(selectedItem.getValue().getName()));
            }else if(treeData instanceof Database){
                renameDatabaseObject(TreeViewUtil.databaseService, selectedItem, newName, "database",
                        true);
            }else if(treeData instanceof Table){
                renameDatabaseObject(TreeViewUtil.tableService, selectedItem, newName, "table",
                        false);
            }else if(treeData instanceof Index){
                renameDatabaseObject(TreeViewUtil.indexService, selectedItem, newName, "index",
                        false);
            }else if(treeData instanceof Sequence){
                renameDatabaseObject(TreeViewUtil.sequenceService, selectedItem, newName, "sequence",
                        false);
            }else if(treeData instanceof View){
                //不支持重命名
                renameDatabaseObject(TreeViewUtil.viewService, selectedItem, newName, "view",
                        false);
            }else if(treeData instanceof Synonym){
                //不支持重命名
                renameDatabaseObject(TreeViewUtil.synonymService, selectedItem, newName, "synonym",
                        false);
            }else if(treeData instanceof Trigger){
                //不支持重命名
                renameDatabaseObject(TreeViewUtil.triggerService, selectedItem, newName, "trigger",
                        false);
            }else if(treeData instanceof Function){
                //不支持重命名
                renameDatabaseObject(TreeViewUtil.functionService, selectedItem, newName, "function",
                        false);
            }else if(treeData instanceof Procedure){
                //不支持重命名
                renameDatabaseObject(TreeViewUtil.procedureService, selectedItem, newName, "procedure",
                        false);
            }
        }
    }

    public static String buildRenameTitle(TreeData treeData) {
        if (treeData instanceof ConnectFolder) {
            return I18n.t("metadata.dialog.rename.folder", "重命名连接分类：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Connect) {
            return I18n.t("metadata.dialog.rename.connection", "重命名数据库连接：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Database) {
            return I18n.t("metadata.dialog.rename.database", "重命名数据库：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Table) {
            return I18n.t("metadata.dialog.rename.table", "重命名表：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Index) {
            return I18n.t("metadata.dialog.rename.index", "重命名索引：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Sequence) {
            return I18n.t("metadata.dialog.rename.sequence", "重命名序列：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Trigger) {
            return I18n.t("metadata.dialog.rename.trigger", "重命名触发器：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Function) {
            return I18n.t("metadata.dialog.rename.function", "重命名函数：%s").formatted(treeData.getName());
        }
        if (treeData instanceof Procedure) {
            return I18n.t("metadata.dialog.rename.procedure", "重命名存储过程：%s").formatted(treeData.getName());
        }
        return I18n.t("metadata.dialog.rename.title", "重命名");
    }

    public static void renameDatabaseObject(MetaObjectService service,
                                             TreeItem<TreeData> selectedItem,
                                             String newName,
                                             String objectType,
                                             boolean useSysmaster) {
        String oldName = selectedItem.getValue().getName();
        String objectDisplayName = getDeleteObjectDisplayName(objectType);
        String sql = "rename " + objectType + " " + oldName + " to " + newName;
        Connect connect = buildObjectConnect(selectedItem, useSysmaster);
        service.renameObject(connect, sql, () -> {
            selectedItem.getValue().setName(newName);
            NotificationUtil.showMainNotification(
                    I18n.t("backsql.notice.renamed", "%s\"%s\"已重命名为\"%s\"")
                            .formatted(objectDisplayName, oldName, newName)
            );
        });
    }

    public static void deleteDatabaseObject(MetaObjectService service,
                                             TreeItem<TreeData> selectedItem,
                                             String objectType,
                                             boolean useSysmaster) {
        String objectDisplayName = getDeleteObjectDisplayName(objectType);
        String confirmTitleKey = getDeleteConfirmTitleKey(objectType);
        String confirmContentKey = getDeleteConfirmContentKey(objectType);
        String objectName = selectedItem.getValue().getName();
        String confirmContent;
        if (confirmContentKey != null) {
            confirmContent = I18n.t(confirmContentKey, "确定要删除\"%s\"吗？")
                    .formatted(objectName);
        } else {
            confirmContent = I18n.t("metadata.alert.delete_object.content", "确定要删除%s\"%s\"吗？")
                    .formatted(objectDisplayName, objectName);
        }
        boolean confirm = AlertUtil.CustomAlertConfirm(
                I18n.t(confirmTitleKey, "删除对象"),
                confirmContent
        );
        if (!confirm) {
            return;
        }

        String sql = "drop " + objectType + " " + selectedItem.getValue().getName();
        Connect connect = buildObjectConnect(selectedItem, useSysmaster);
        service.deleteObject(connect, sql, () -> {
            TreeItem<TreeData> parent = selectedItem.getParent();
            if (parent != null) {
                parent.getChildren().remove(selectedItem);
            }
            NotificationUtil.showMainNotification(
                    I18n.t("backsql.notice.deleted", "%s\"%s\"已删除！")
                            .formatted(objectDisplayName, selectedItem.getValue().getName())
            );
        });
    }

    //删除节点
    public static void deleteTreeItem(TreeView<TreeData> treeView) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        TreeData treeData = selectedItem.getValue();
        if(treeData instanceof  ConnectFolder){
            if (selectedItem.getParent().getChildren().size() <= 1) {
                AlertUtil.CustomAlert(I18n.t("metadata.alert.delete_folder.title", "删除连接分类"),
                        I18n.t("metadata.alert.delete_folder.single", "当前只有一个连接分类，不可删除！"));
            } else if (selectedItem.getChildren().size() > 0) {
                Boolean confirm = AlertUtil.CustomAlertConfirm(
                        I18n.t("metadata.alert.delete_folder.title", "删除连接分类"),
                        I18n.t("metadata.alert.delete_folder.content", "删除连接分类\"%s\"将删除该分类下【%d】个连接，确定要删除该分类吗？")
                                .formatted(selectedItem.getValue().getName(), selectedItem.getChildren().size())
                );
                if (confirm) {
                    TreeNavigator.disconnectFolder(selectedItem);
                    selectedItem.getParent().getChildren().remove(selectedItem);
                    LocalDbRepository.deleteConnectFolder((ConnectFolder) selectedItem.getValue());
                    NotificationUtil.showMainNotification(
                            I18n.t("metadata.notice.folder_deleted", "数据库连接分类\"%s\"已删除！").formatted(selectedItem.getValue().getName()));
                }
            } else {
                LocalDbRepository.deleteConnectFolder((ConnectFolder)selectedItem.getValue());
                selectedItem.getParent().getChildren().remove(selectedItem);
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.folder_deleted", "数据库连接分类\"%s\"已删除！").formatted(selectedItem.getValue().getName()));
            }

        }else if(treeData instanceof Connect){
            if (AlertUtil.CustomAlertConfirm(
                    I18n.t("metadata.alert.delete_connection.title", "删除连接"),
                    I18n.t("metadata.alert.delete_connection.content", "确定要删除连接\"%s\"吗？").formatted(selectedItem.getValue().getName()))) {
                Connect connect = (Connect) treeData;
                try {
                    if (!selectedItem.getChildren().isEmpty()) {
                        connect.getConn().close();
                        selectedItem.getChildren().clear();
                    }
                } catch (java.sql.SQLException e) {
                    AppErrorHandler.handle(e);
                    throw new RuntimeException(e);
                }
                LocalDbRepository.deleteConnectLeaf(connect);
                selectedItem.getParent().getChildren().remove(selectedItem);
                TabpaneUtil.isRefreshConnectList();
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.connection_deleted", "数据库连接\"%s\"已删除！").formatted(selectedItem.getValue().getName()));
            }
        }else if(treeData instanceof Database){
            deleteDatabaseObject(TreeViewUtil.databaseService, selectedItem, "database", true);
        }else if(treeData instanceof Table){
            deleteDatabaseObject(TreeViewUtil.tableService, selectedItem, "table", false);
        }else if(treeData instanceof View){
            deleteDatabaseObject(TreeViewUtil.viewService, selectedItem, "view", false);
        }else if(treeData instanceof Index){
            deleteDatabaseObject(TreeViewUtil.indexService, selectedItem, "index", false);
        }else if(treeData instanceof Sequence){
            deleteDatabaseObject(TreeViewUtil.sequenceService, selectedItem, "sequence", false);
        }else if(treeData instanceof Synonym){
            deleteDatabaseObject(TreeViewUtil.synonymService, selectedItem, "synonym", false);
        }else if(treeData instanceof Trigger){
            deleteDatabaseObject(TreeViewUtil.triggerService, selectedItem, "trigger", false);
        }else if(treeData instanceof Function){
            deleteDatabaseObject(TreeViewUtil.functionService, selectedItem, "function", false);
        }else if(treeData instanceof Procedure){
            deleteDatabaseObject(TreeViewUtil.procedureService, selectedItem, "procedure", false);
        }else if(treeData instanceof DBPackage){
            deleteDatabaseObject(TreeViewUtil.packageService, selectedItem, "package", false);
        }else if(treeData instanceof User){
            deleteDatabaseObject(TreeViewUtil.databaseService, selectedItem, "user", false);
        }
    }

    public static Connect buildObjectConnect(TreeItem<TreeData> selectedItem, boolean useSysmaster) {
        Connect connect = new Connect(TreeNavigator.getMetaConnect(selectedItem));
        Database currentDatabase = TreeNavigator.getCurrentDatabase(selectedItem);
        String databaseName = currentDatabase.getName();
        if (useSysmaster) {
            try {
                String fallback = resolveDialectServices().requireDialect(connect).changeDatabaseFallbackCatalogName();
                if (fallback != null && !fallback.isBlank()) {
                    databaseName = fallback;
                }
            } catch (Exception ignored) {
            }
        }
        applyDatabaseConnectionProps(connect, currentDatabase, databaseName);
        return connect;
    }

    public static void applyDatabaseConnectionProps(Connect connect, Database database, String databaseName) {
        if (connect == null || database == null) {
            return;
        }
        connect.setDatabase(databaseName);
        connect.setProps(TreeViewUtil.connectionService.modifyProps(connect, PROP_DB_LOCALE, database.getDbLocale()));
        if (isNoLogDatabase(database)) {
            connect.setProps(TreeViewUtil.connectionService.modifyProps(connect, PROP_IFX_ISOLATION_LEVEL, ""));
        }
    }

    private static boolean isNoLogDatabase(Database database) {
        return database != null
                && database.getDbLog() != null
                && "nolog".equalsIgnoreCase(database.getDbLog().trim());
    }

    private static DialectServices resolveDialectServices() {
        try {
            return AppContext.get(DialectServices.class);
        } catch (IllegalStateException e) {
            return DialectServices.createDefault();
        }
    }

    public static void toggleObjectEnabled(TreeItem<TreeData> selectedItem, boolean enabled) {
        TreeData treeData = selectedItem.getValue();
        Connect connect = buildObjectConnect(selectedItem, false);
        if (treeData instanceof Index) {
            toggleIndexEnabled(connect, treeData, enabled);
            return;
        }
        if (treeData instanceof Trigger) {
            toggleTriggerEnabled(connect, treeData, enabled);
        }
    }

    public static void toggleIndexEnabled(Connect connect, TreeData treeData, boolean enabled) {
        String action = enabled ? "enable" : "disable";
        boolean confirm = AlertUtil.CustomAlertConfirm(
                I18n.t("metadata.alert." + action + "_index.title", enabled ? "启用索引" : "禁用索引"),
                I18n.t("metadata.alert." + action + "_index.content",
                                enabled ? "确定要启用索引\"%s\"吗？启用索引可能会较长时间锁表！" : "确定要禁用索引\"%s\"吗？索引禁用后启用需要自动重建耗费较长时间！")
                        .formatted(treeData.getName())
        );
        if (!confirm) {
            return;
        }
        String sql = "set indexes " + treeData.getName() + (enabled ? " enabled" : " disabled");
        Runnable onSucceeded = () -> {
            ((Index)treeData).setIsdisabled(!enabled);

            NotificationUtil.showMainNotification(
                I18n.t(
                        enabled ? "backsql.notice.index_enabled" : "backsql.notice.index_disabled",
                        enabled ? "索引\"%s\"已启用！" : "索引\"%s\"已禁用！"
                ).formatted(treeData.getName())
            );
        };
        if (enabled) {
            TreeViewUtil.indexService.enableIndex(connect, sql, onSucceeded);
        } else {
            TreeViewUtil.indexService.disableIndex(connect, sql, onSucceeded);
        }
    }

    public static void toggleTriggerEnabled(Connect connect, TreeData treeData, boolean enabled) {
        String action = enabled ? "enable" : "disable";
        boolean confirm = AlertUtil.CustomAlertConfirm(
                I18n.t("metadata.alert." + action + "_trigger.title", enabled ? "启用触发器" : "禁用触发器"),
                I18n.t(
                        "metadata.alert." + action + "_trigger.content",
                        enabled ? "确定要启用触发器\"%s\"吗？" : "确定要禁用触发器\"%s\"吗？"
                ).formatted(treeData.getName())
        );
        if (!confirm) {
            return;
        }
        String sql = "set triggers " + treeData.getName() + (enabled ? " enabled" : " disabled");
        Runnable onSucceeded = () -> {
            ((Trigger)treeData).setIsdisabled(!enabled);
            NotificationUtil.showMainNotification(
                I18n.t(
                        enabled ? "backsql.notice.trigger_enabled" : "backsql.notice.trigger_disabled",
                        enabled ? "触发器\"%s\"已启用！" : "触发器\"%s\"已禁用！"
                ).formatted(treeData.getName())
            );
        };
        if (enabled) {
            TreeViewUtil.triggerService.enableTrigger(connect, sql, onSucceeded);
        } else {
            TreeViewUtil.triggerService.disableTrigger(connect, sql, onSucceeded);
        }
    }

    public static String getDeleteObjectDisplayName(String objectType) {
        return switch (objectType == null ? "" : objectType.toLowerCase()) {
            case "database" -> I18n.t("backsql.object.database", "数据库");
            case "table" -> I18n.t("backsql.object.table", "表");
            case "view" -> I18n.t("backsql.object.view", "视图");
            case "index", "indexes" -> I18n.t("backsql.object.index", "索引");
            case "sequence" -> I18n.t("backsql.object.sequence", "序列");
            case "synonym" -> I18n.t("backsql.object.synonym", "同义词");
            case "trigger", "triggers" -> I18n.t("backsql.object.trigger", "触发器");
            case "function" -> I18n.t("backsql.object.function", "函数");
            case "procedure" -> I18n.t("backsql.object.procedure", "存储过程");
            case "package" -> I18n.t("backsql.object.package", "包");
            case "user" -> I18n.t("backsql.object.user", "用户");
            default -> I18n.t("backsql.object.default", "对象");
        };
    }

    public static String getDeleteConfirmTitleKey(String objectType) {
        return switch (objectType == null ? "" : objectType.toLowerCase()) {
            case "database" -> "backsql.confirm.delete_database.title";
            case "table" -> "backsql.confirm.delete_table.title";
            case "view" -> "backsql.confirm.delete_view.title";
            case "index", "indexes" -> "backsql.confirm.delete_index.title";
            case "sequence" -> "backsql.confirm.delete_sequence.title";
            case "synonym" -> "backsql.confirm.delete_synonym.title";
            case "trigger", "triggers" -> "backsql.confirm.delete_trigger.title";
            case "function" -> "backsql.confirm.delete_function.title";
            case "procedure" -> "backsql.confirm.delete_procedure.title";
            case "user" -> "backsql.confirm.delete_user.title";
            default -> "backsql.error.title";
        };
    }

    public static String getDeleteConfirmContentKey(String objectType) {
        return switch (objectType == null ? "" : objectType.toLowerCase()) {
            case "database" -> "backsql.confirm.delete_database.content";
            case "table" -> "backsql.confirm.delete_table.content";
            case "view" -> "backsql.confirm.delete_view.content";
            case "index", "indexes" -> "backsql.confirm.delete_index.content";
            case "sequence" -> "backsql.confirm.delete_sequence.content";
            case "synonym" -> "backsql.confirm.delete_synonym.content";
            case "trigger", "triggers" -> "backsql.confirm.delete_trigger.content";
            case "function" -> "backsql.confirm.delete_function.content";
            case "procedure" -> "backsql.confirm.delete_procedure.content";
            case "user" -> "backsql.confirm.delete_user.content";
            default -> null;
        };
    }

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
                    Database database = TreeNavigator.getCurrentDatabase(item);
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
            ddlTask.cancel(true);
            Future<?> future = ddlFuture.get();
            if (future != null) {
                future.cancel(true);
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
        if (selectedItem == null || !(selectedItem.getValue() instanceof Database database)) {
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
            ddlTask.cancel(true);
            Future<?> future = ddlFuture.get();
            if (future != null) {
                future.cancel(true);
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

    public static void exportDatabaseDdlAndData(TreeView<TreeData> treeView) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || !(selectedItem.getValue() instanceof Database database)) {
            return;
        }

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle(I18n.t("metadata.export.dir.title", "选择导出目录"));
        File dir = dirChooser.showDialog(AppState.getWindow());
        if (dir == null) {
            return;
        }

        Connect exportBaseConnect = buildObjectConnect(selectedItem, false);
        File exportDir = new File(dir, database.getName() + ".dbb");
        if (!ensureDatabaseExportDirectory(exportDir)) {
            return;
        }
        File preDdlFile = new File(exportDir, database.getName() + "_01_pre_data.sql");
        File postDdlFile = new File(exportDir, database.getName() + "_02_post_data.sql");
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
                if (isDatabaseExportCancelled(this, runtime)) {
                    return null;
                }
                updateProgress(-1, 1);
                updateMessage(countingMessage);
                try {
                    var ddlParts = TreeViewUtil.databaseService.exportDatabaseDdlPartsWithNewConnection(exportBaseConnect, database, (completed, total) -> {
                        if (isDatabaseExportCancelled(this, runtime)) {
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
                    if (isDatabaseExportCancelled(this, runtime)) {
                        throw new CancellationException("Database DDL export cancelled");
                    }
                    String bootstrapSql = buildDatabaseBootstrapSql(exportBaseConnect, database);
                    Files.writeString(preDdlFile.toPath(), bootstrapSql + ddlParts.getPreDataSql(), StandardCharsets.UTF_8);
                    Files.writeString(postDdlFile.toPath(), ddlParts.getPostDataSql(), StandardCharsets.UTF_8);
                    if (isDatabaseExportCancelled(this, runtime)) {
                        throw new CancellationException("Database DDL export cancelled");
                    }
                    updateProgress(-1, 1);
                    updateMessage(loadingTablesMessage);
                    List<Table> tables = new ArrayList<>(TreeViewUtil.databaseService.getUserTablesWithNewConnection(exportBaseConnect, database));
                    if (isDatabaseExportCancelled(this, runtime)) {
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
                            () -> isDatabaseExportCancelled(this, runtime),
                            runtime
                    );
                    if (!failures.isEmpty()) {
                        throw new Exception(buildDatabaseExportFailureMessage(failures));
                    }
                    Platform.runLater(() -> NotificationUtil.showMainNotification(
                            I18n.t(
                                    "metadata.export.ddl_data.notice.completed",
                                    "数据库\"%s\"DDL脚本和表数据已导出到：%s"
                            ).formatted(database.getName(), exportDir.getAbsolutePath())
                    ));
                    updateProgress(1, 1);
                    return null;
                } catch (CancellationException e) {
                    runtime.cancel();
                    deleteFileQuietly(preDdlFile);
                    deleteFileQuietly(postDdlFile);
                    deleteDirectoryIfEmpty(exportDir);
                    throw e;
                } catch (Exception e) {
                    runtime.cancel();
                    deleteFileQuietly(preDdlFile);
                    deleteFileQuietly(postDdlFile);
                    deleteDirectoryIfEmpty(exportDir);
                    throw e;
                }
            }
        };
        String taskDisplayName = I18n.t("metadata.export.ddl_data.task_name", "%s DDL及数据")
                .formatted(database.getName());
        DownloadManagerUtil.addCustomExportTask(taskDisplayName, preDdlFile, true, exportTask, runtime::cancel);
    }

    private static AlertUtil.ContentDialog createDdlLoadingDialog(Task<?> ddlTask, Runnable cancelAction) {
        ButtonType cancelButtonType = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Label messageLabel = new Label(I18n.t("metadata.menu.ddl.loading.message", "正在导出DDL..."));
        messageLabel.setWrapText(true);
        messageLabel.setStyle("-fx-text-fill: -color-fg-default;");
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

    private static List<String> exportDatabaseCsvFiles(Connect baseConnect,
                                                       File dir,
                                                       List<Table> tables,
                                                       java.util.function.IntConsumer progressUpdater,
                                                       java.util.function.BooleanSupplier cancelChecker,
                                                       DatabaseExportRuntime runtime) throws Exception {
        List<Table> validTables = new ArrayList<>();
        if (tables != null) {
            for (Table table : tables) {
                if (table != null && table.getName() != null && !table.getName().isBlank()) {
                    validTables.add(table);
                }
            }
        }
        if (validTables.isEmpty()) {
            if (progressUpdater != null) {
                progressUpdater.accept(0);
            }
            return List.of();
        }

        int maxConcurrency = Math.min(8, validTables.size());
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency, r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("dbboys-db-export-" + thread.getId());
            return thread;
        });
        runtime.executorRef.set(executor);
        ConcurrentLinkedQueue<Table> tableQueue = new ConcurrentLinkedQueue<>(validTables);
        AtomicInteger activeExports = new AtomicInteger(0);
        AtomicInteger activeConnections = new AtomicInteger(0);
        AtomicInteger completedTables = new AtomicInteger(0);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int workerIndex = 0; workerIndex < maxConcurrency; workerIndex++) {
                final int workerNo = workerIndex + 1;
                futures.add(executor.submit(() -> {
                    List<String> workerFailures = new ArrayList<>();
                    if (cancelChecker != null && cancelChecker.getAsBoolean()) {
                        throw new CancellationException("Database data export cancelled");
                    }
                    Connect workerConnect = new Connect(baseConnect);
                    int openedConnections = activeConnections.incrementAndGet();
                    Connection conn = null;
                    try {
                        conn = AppContext.get(com.dbboys.api.ConnectionService.class).createConnection(workerConnect);
                        runtime.activeConnections.add(conn);
                        while (true) {
                            if (cancelChecker != null && cancelChecker.getAsBoolean()) {
                                throw new CancellationException("Database data export cancelled");
                            }
                            Table table = tableQueue.poll();
                            if (table == null) {
                                break;
                            }
                            File file = new File(dir, table.getName() + ".csv");
                            deleteFileQuietly(file);
                            int active = activeExports.incrementAndGet();
                            try {
                                boolean exported = DownloadManagerUtil.exportSqlToCsvFile(
                                        conn,
                                        "select * from " + table.getName(),
                                        file,
                                        cancelChecker
                                );
                                if (!exported) {
                                    deleteFileQuietly(file);
                                }
                            } catch (CancellationException e) {
                                deleteFileQuietly(file);
                                throw e;
                            } catch (Exception e) {
                                if (cancelChecker != null && cancelChecker.getAsBoolean()) {
                                    deleteFileQuietly(file);
                                    throw new CancellationException("Database data export cancelled");
                                }
                                String message = e.getMessage();
                                log.error("Database CSV export failed. database={}, table={}, worker={}, thread={}, activeExports={}, activeConnections={}",
                                        baseConnect.getDatabase(), table.getName(), workerNo, Thread.currentThread().getName(), active, activeConnections.get(), e);
                                workerFailures.add(table.getName() + ": " + (message == null || message.isBlank() ? e.toString() : message));
                            } finally {
                                activeExports.decrementAndGet();
                                int completed = completedTables.incrementAndGet();
                                if (progressUpdater != null) {
                                    progressUpdater.accept(completed);
                                }
                            }
                        }
                    } finally {
                        runtime.activeConnections.remove(conn);
                        closeConnectionQuietly(conn);
                        int remainingConnections = activeConnections.decrementAndGet();
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

    private static String buildDatabaseBootstrapSql(Connect connect, Database database) {
        if (connect == null || database == null || !"GBASE 8S".equals(connect.getDbtype())) {
            return "";
        }
        String databaseName = normalizeSqlToken(database.getName());
        if (databaseName.isEmpty()) {
            return "";
        }
        String dbspace = normalizeSqlToken(database.getDbSpace());
        String dbLog = normalizeSqlToken(database.getDbLog()).toLowerCase(Locale.ROOT);
        String dbLocale = normalizeSqlToken(database.getDbLocale());
        String lineSeparator = System.lineSeparator();

        StringBuilder builder = new StringBuilder();
        if (!dbLocale.isEmpty()) {
            builder.append("-- DB_LOCALE=").append(dbLocale).append(lineSeparator);
        }
        builder.append("create database ").append(databaseName);
        if (!dbspace.isEmpty()) {
            builder.append(" in ").append(dbspace);
        }
        if ("buffered".equals(dbLog)) {
            builder.append(" with buffered log");
        } else if ("unbuffered".equals(dbLog)) {
            builder.append(" with log");
        }
        builder.append(";").append(lineSeparator);
        builder.append("database ").append(databaseName).append(";").append(lineSeparator).append(lineSeparator);
        return builder.toString();
    }

    private static String normalizeSqlToken(String value) {
        return value == null ? "" : value.trim();
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

    private static void deleteFileQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static void deleteDirectoryIfEmpty(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null && children.length == 0) {
                dir.delete();
            }
        }
    }

    private static boolean isDatabaseExportCancelled(Task<?> task, DatabaseExportRuntime runtime) {
        return (task != null && task.isCancelled())
                || Thread.currentThread().isInterrupted()
                || (runtime != null && runtime.cancelRequested.get());
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
        Database database = TreeNavigator.getCurrentDatabase(tableItem);
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
            Connect connect = buildObjectConnect(tableItem,false);

            FileChooser chooser = new FileChooser();
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
            DownloadManagerUtil.addSqlExportTask(connect, exportSql, file, format.name().toLowerCase(), true, table.getNrows());
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

        for (TreeItem<TreeData> tableItem : tableItems) {
            Table table = (Table) tableItem.getValue();
            Connect connect = buildObjectConnect(tableItem,false);
            File file = new File(dir, table.getName() + extension);
            if (file.exists()) {
                file.delete();
            }
            String exportSql = "select * from " + table.getName();
            DownloadManagerUtil.addSqlExportTask(connect, exportSql, file, format.name().toLowerCase(), true, table.getNrows());
        }
    }

    public static void importTableData(TreeItem<TreeData> selectedItem) {
        if (selectedItem == null || !(selectedItem.getValue() instanceof Table table)) {
            return;
        }

        Database database = TreeNavigator.getCurrentDatabase(selectedItem);
        if (database == null) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "错误"),
                    I18n.t("metadata.notice.database_not_found", "未找到当前数据库，数据库已被删除！")
            );
            return;
        }

        FileChooser chooser = new FileChooser();
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

        Connect connect = buildObjectConnect(selectedItem, false);
        TreeViewUtil.tableService.importTableData(connect, database, table.getName(), file, insertedRows ->
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.import.notice.completed", "表\"%s\"导入完成，已写入 %d 行")
                                .formatted(table.getName(), insertedRows)
                )
        );
        NotificationUtil.showMainNotification(
                I18n.t("metadata.import.notice.queued", "表\"%s\"导入任务已提交：%s")
                        .formatted(table.getName(), file.getName())
        );
    }
}
