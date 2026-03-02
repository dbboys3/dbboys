package com.dbboys.util.tree;

import com.dbboys.app.AppState;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.impl.IMetaObjectService;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.*;
import com.dbboys.vo.*;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;


public class TreeCrudHandler {
    private static final Logger log = LogManager.getLogger(TreeCrudHandler.class);

    public enum ExportFormat {CSV, JSON, SQL}

    //重命名节点
    public static void renameTreeItem(TreeView<TreeData> treeView) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        String title = buildRenameTitle(selectedItem.getValue());
        alert.setTitle(title);
        alert.setHeaderText("");
        alert.setGraphic(null); //避免显示问号
        //alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        AppState.applyAppStylesheet(alert.getDialogPane().getScene());
        Stage alterstage = (Stage) alert.getDialogPane().getScene().getWindow();
        alterstage.getIcons().add(new Image(IconPaths.MAIN_LOGO));
        HBox hbox = new HBox();
        hbox.getChildren().add(new Label(I18n.t("metadata.dialog.rename.input", "请输入重命名名称  ")));
        hbox.setAlignment(Pos.CENTER_LEFT);
        CustomUserTextField textField = new CustomUserTextField();
        textField.setPrefWidth(200);
        textField.setText(selectedItem.getValue().getName());
        textField.positionCaret(textField.getText().length());
        hbox.getChildren().add(textField);
        alert.getDialogPane().setContent(hbox);

        // 自定义按钮
        ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(buttonTypeOk, buttonTypeCancel);
        Button button = (Button) alert.getDialogPane().lookupButton(buttonTypeOk);
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

        ButtonType result = alert.showAndWait().orElse(buttonTypeCancel);
        if (result == buttonTypeOk) {
            String newName = textField.getText();
            TreeData treeData = selectedItem.getValue();
            if (treeData instanceof ConnectFolder) {
                treeData.setName(newName);
                if (selectedItem.getParent().getChildren().size() > 1) { //多于1个分类，重新排序
                    TreeViewBuilder.reorderTreeview(treeView, selectedItem);
                }
                SqliteDBaccessUtil.updateConnectFolder((ConnectFolder) treeData);
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.folder_renamed", "分类已重命名为：%s").formatted(selectedItem.getValue().getName()));
            }else if(treeData instanceof Connect){
                selectedItem.getValue().setName(newName);
                //connect_list_treeview.refresh();
                if(selectedItem.getParent().getChildren().size()>1) {//多于1个连接重新排序
                    TreeViewBuilder.reorderTreeview(treeView, selectedItem);
                }
                SqliteDBaccessUtil.updateConnect((Connect) selectedItem.getValue());
                TabpaneUtil.isRefreshConnectList();
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.connection_renamed", "连接已重命名为：%s").formatted(selectedItem.getValue().getName()));
            }else if(treeData instanceof Database){
                renameDatabaseObject(MetadataTreeviewUtil.databaseService, selectedItem, newName, "database",
                        true);
            }else if(treeData instanceof Table){
                renameDatabaseObject(MetadataTreeviewUtil.tableService, selectedItem, newName, "table",
                        false);
            }else if(treeData instanceof Index){
                renameDatabaseObject(MetadataTreeviewUtil.indexService, selectedItem, newName, "index",
                        false);
            }else if(treeData instanceof Sequence){
                renameDatabaseObject(MetadataTreeviewUtil.sequenceService, selectedItem, newName, "sequence",
                        false);
            }else if(treeData instanceof View){
                //不支持重命名
                renameDatabaseObject(MetadataTreeviewUtil.viewService, selectedItem, newName, "view",
                        false);
            }else if(treeData instanceof Synonym){
                //不支持重命名
                renameDatabaseObject(MetadataTreeviewUtil.synonymService, selectedItem, newName, "synonym",
                        false);
            }else if(treeData instanceof Trigger){
                //不支持重命名
                renameDatabaseObject(MetadataTreeviewUtil.triggerService, selectedItem, newName, "trigger",
                        false);
            }else if(treeData instanceof Function){
                //不支持重命名
                renameDatabaseObject(MetadataTreeviewUtil.functionService, selectedItem, newName, "function",
                        false);
            }else if(treeData instanceof Procedure){
                //不支持重命名
                renameDatabaseObject(MetadataTreeviewUtil.procedureService, selectedItem, newName, "procedure",
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

    public static void renameDatabaseObject(IMetaObjectService service,
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
            NotificationUtil.showNotification(
                    AppState.getNoticePane(),
                    I18n.t("backsql.notice.renamed", "%s\"%s\"已重命名为\"%s\"")
                            .formatted(objectDisplayName, oldName, newName)
            );
        });
    }

    public static void deleteDatabaseObject(IMetaObjectService service,
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
        boolean confirm = AlterUtil.CustomAlertConfirm(
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
            NotificationUtil.showNotification(
                    AppState.getNoticePane(),
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
                AlterUtil.CustomAlert(I18n.t("metadata.alert.delete_folder.title", "删除连接分类"),
                        I18n.t("metadata.alert.delete_folder.single", "当前只有一个连接分类，不可删除！"));
            } else if (selectedItem.getChildren().size() > 0) {
                Boolean confirm = AlterUtil.CustomAlertConfirm(
                        I18n.t("metadata.alert.delete_folder.title", "删除连接分类"),
                        I18n.t("metadata.alert.delete_folder.content", "删除连接分类\"%s\"将删除该分类下【%d】个连接，确定要删除该分类吗？")
                                .formatted(selectedItem.getValue().getName(), selectedItem.getChildren().size())
                );
                if (confirm) {
                    TreeNavigator.disconnectFolder(selectedItem);
                    selectedItem.getParent().getChildren().remove(selectedItem);
                    SqliteDBaccessUtil.deleteConnectFolder((ConnectFolder) selectedItem.getValue());
                    NotificationUtil.showMainNotification(
                            I18n.t("metadata.notice.folder_deleted", "数据库连接分类\"%s\"已删除！").formatted(selectedItem.getValue().getName()));
                }
            } else {
                SqliteDBaccessUtil.deleteConnectFolder((ConnectFolder)selectedItem.getValue());
                selectedItem.getParent().getChildren().remove(selectedItem);
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.folder_deleted", "数据库连接分类\"%s\"已删除！").formatted(selectedItem.getValue().getName()));
            }

        }else if(treeData instanceof Connect){
            if (AlterUtil.CustomAlertConfirm(
                    I18n.t("metadata.alert.delete_connection.title", "删除连接"),
                    I18n.t("metadata.alert.delete_connection.content", "确定要删除连接\"%s\"吗？").formatted(selectedItem.getValue().getName()))) {
                Connect connect = (Connect) treeData;
                try {
                    if (!selectedItem.getChildren().isEmpty()) {
                        connect.getConn().close();
                        selectedItem.getChildren().clear();
                    }
                } catch (java.sql.SQLException e) {
                    GlobalErrorHandlerUtil.handle(e);
                    throw new RuntimeException(e);
                }
                SqliteDBaccessUtil.deleteConnectLeaf(connect);
                selectedItem.getParent().getChildren().remove(selectedItem);
                TabpaneUtil.isRefreshConnectList();
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.notice.connection_deleted", "数据库连接\"%s\"已删除！").formatted(selectedItem.getValue().getName()));
            }
        }else if(treeData instanceof Database){
            deleteDatabaseObject(MetadataTreeviewUtil.databaseService, selectedItem, "database", true);
        }else if(treeData instanceof Table){
            deleteDatabaseObject(MetadataTreeviewUtil.tableService, selectedItem, "table", false);
        }else if(treeData instanceof View){
            deleteDatabaseObject(MetadataTreeviewUtil.viewService, selectedItem, "view", false);
        }else if(treeData instanceof Index){
            deleteDatabaseObject(MetadataTreeviewUtil.indexService, selectedItem, "index", false);
        }else if(treeData instanceof Sequence){
            deleteDatabaseObject(MetadataTreeviewUtil.sequenceService, selectedItem, "sequence", false);
        }else if(treeData instanceof Synonym){
            deleteDatabaseObject(MetadataTreeviewUtil.synonymService, selectedItem, "synonym", false);
        }else if(treeData instanceof Trigger){
            deleteDatabaseObject(MetadataTreeviewUtil.triggerService, selectedItem, "trigger", false);
        }else if(treeData instanceof Function){
            deleteDatabaseObject(MetadataTreeviewUtil.functionService, selectedItem, "function", false);
        }else if(treeData instanceof Procedure){
            deleteDatabaseObject(MetadataTreeviewUtil.procedureService, selectedItem, "procedure", false);
        }else if(treeData instanceof DBPackage){
            deleteDatabaseObject(MetadataTreeviewUtil.packageService, selectedItem, "package", false);
        }else if(treeData instanceof User){
            deleteDatabaseObject(MetadataTreeviewUtil.databaseService, selectedItem, "user", false);
        }
    }

    public static Connect buildObjectConnect(TreeItem<TreeData> selectedItem, boolean useSysmaster) {
        Connect connect = new Connect(TreeNavigator.getMetaConnect(selectedItem));
        Database currentDatabase = TreeNavigator.getCurrentDatabase(selectedItem);
        connect.setDatabase(useSysmaster ? "sysmaster" : currentDatabase.getName());
        connect.setProps(MetadataTreeviewUtil.connectionService.modifyProps(connect, currentDatabase.getDbLocale()));
        return connect;
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
        boolean confirm = AlterUtil.CustomAlertConfirm(
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

            NotificationUtil.showNotification(
                AppState.getNoticePane(),
                I18n.t(
                        enabled ? "backsql.notice.index_enabled" : "backsql.notice.index_disabled",
                        enabled ? "索引\"%s\"已启用！" : "索引\"%s\"已禁用！"
                ).formatted(treeData.getName())
            );
        };
        if (enabled) {
            MetadataTreeviewUtil.indexService.enableIndex(connect, sql, onSucceeded);
        } else {
            MetadataTreeviewUtil.indexService.disableIndex(connect, sql, onSucceeded);
        }
    }

    public static void toggleTriggerEnabled(Connect connect, TreeData treeData, boolean enabled) {
        String action = enabled ? "enable" : "disable";
        boolean confirm = AlterUtil.CustomAlertConfirm(
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
            NotificationUtil.showNotification(
                AppState.getNoticePane(),
                I18n.t(
                        enabled ? "backsql.notice.trigger_enabled" : "backsql.notice.trigger_disabled",
                        enabled ? "触发器\"%s\"已启用！" : "触发器\"%s\"已禁用！"
                ).formatted(treeData.getName())
            );
        };
        if (enabled) {
            MetadataTreeviewUtil.triggerService.enableTrigger(connect, sql, onSucceeded);
        } else {
            MetadataTreeviewUtil.triggerService.disableTrigger(connect, sql, onSucceeded);
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

        Task<String> ddlTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                StringBuilder sb = new StringBuilder();
                for (TreeItem<TreeData> item : items) {
                    if (item == null || item.getValue() == null) {
                        continue;
                    }
                    TreeData data = item.getValue();
                    if (data.isRunning()) {
                        continue;
                    }
                    data.setRunning(true);
                    Connect connectParam = TreeNavigator.getMetaConnect(item);
                    Database database = TreeNavigator.getCurrentDatabase(item);
                    String ddlText = "";
                    if (data instanceof Table) {
                        ddlText = MetadataTreeviewUtil.tableService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Index) {
                        ddlText = MetadataTreeviewUtil.indexService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof View) {
                        ddlText = MetadataTreeviewUtil.viewService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Trigger) {
                        ddlText = MetadataTreeviewUtil.triggerService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Sequence) {
                        ddlText = MetadataTreeviewUtil.sequenceService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Synonym) {
                        ddlText = MetadataTreeviewUtil.synonymService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Function) {
                        ddlText = MetadataTreeviewUtil.functionService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof Procedure) {
                        ddlText = MetadataTreeviewUtil.procedureService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof DBPackage) {
                        ddlText = MetadataTreeviewUtil.packageService.getDDL(connectParam, database, data.getName());
                    } else if (data instanceof PackageFunction || data instanceof PackageProcedure) {
                        ddlText = MetadataTreeviewUtil.packageService.getChildrenDDL(
                                ((DBPackage) item.getParent().getValue()).getDDL(), data.getName());
                    }

                    if (!multi) {
                        data.setRunning(false);
                        return SqlParserUtil.formatSql(ddlText);
                    }
                    if (ddlText != null && !ddlText.isEmpty()) {
                        sb.append("-- ").append(data.getName()).append(System.lineSeparator());
                        sb.append(ddlText).append(System.lineSeparator()).append(System.lineSeparator());
                    }
                     data.setRunning(false);
                }
                return SqlParserUtil.formatSql(sb.toString());
            }
        };

        ddlTask.setOnSucceeded(event1 -> {
            items.forEach(it -> {
                if (it != null && it.getValue() != null) {
                    it.getValue().setRunning(false);
                }
            });
            String ddlText = ddlTask.getValue();
            onSuccess.accept(firstData, ddlText == null ? "" : ddlText);
        });
        GlobalErrorHandlerUtil.bindTask(ddlTask, () -> items.forEach(it -> {
            if (it != null && it.getValue() != null) {
                it.getValue().setRunning(false);
            }
        }));

        TreeNavigator.getMetaConnect(firstItem).executeSqlTask(ddlTask);
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
            DownloadManagerUtil.addSqlExportTask(connect, exportSql, file, format.name().toLowerCase(), true);
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
            DownloadManagerUtil.addSqlExportTask(connect, exportSql, file, format.name().toLowerCase(), true);
        }
    }
}
