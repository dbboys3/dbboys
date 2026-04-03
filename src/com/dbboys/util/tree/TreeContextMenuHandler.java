package com.dbboys.util.tree;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.ReconnectFallbackCapability;
import com.dbboys.app.AppContext;
import com.dbboys.app.AppState;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.app.Main;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.ConnectionService;
import com.dbboys.impl.DatabasePlatforms;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.*;
import com.dbboys.vo.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;


public class TreeContextMenuHandler {
    private static final Logger log = LogManager.getLogger(TreeContextMenuHandler.class);

    public static void setupContextMenu(TreeView<TreeData> treeView) {

        //右键弹出框
        ContextMenu treeview_menu = new ContextMenu();
        // 为元数据树的右键菜单增加单独样式，便于控制默认选中项的高亮行为
        treeview_menu.getStyleClass().add("treeview-context-menu");

        CustomShortcutMenuItem addUserItem = MenuItemUtil.createMenuItemI18n("metadata.menu.add_user",
                IconFactory.group(IconPaths.METADATA_ADD_USER, 0.55, 0.55));
        CustomShortcutMenuItem modifyUserItem = MenuItemUtil.createMenuItemI18n("metadata.menu.reset_password",
                IconFactory.group(IconPaths.METADATA_MODIFY_USER, 0.5, 0.5));
        CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n("metadata.menu.copy_name", "Ctrl+C",
                IconFactory.group(IconPaths.METADATA_COPY_ITEM, 0.65, 0.65));
        CustomShortcutMenuItem packageDDLItem = MenuItemUtil.createMenuItemI18n("metadata.menu.show_package_ddl",
                IconFactory.group(IconPaths.METADATA_PACKAGE_DDL_ITEM, 0.6, 0.6));
        CustomShortcutMenuItem modifyToRawItem = MenuItemUtil.createMenuItemI18n("metadata.menu.modify_to_raw",
                IconFactory.group(IconPaths.METADATA_MODIFY_TO_RAW_ITEM, 0.6, 0.6));
        CustomShortcutMenuItem modifyToStandardItem = MenuItemUtil.createMenuItemI18n("metadata.menu.modify_to_standard",
                IconFactory.group(IconPaths.METADATA_MODIFY_TO_STANDARD_ITEM, 0.6, 0.6));
        CustomShortcutMenuItem sqlHisItem = MenuItemUtil.createMenuItemI18n("metadata.menu.sql_history",
                IconFactory.group(IconPaths.METADATA_SQL_HIS_ITEM, 0.85, 0.85));
        CustomShortcutMenuItem updateStatisticsItem = MenuItemUtil.createMenuItemI18n("metadata.menu.update_statistics",
                IconFactory.group(IconPaths.METADATA_UPDATE_STATISTICS_ITEM, 0.66, 0.66));
        CustomShortcutMenuItem truncateItem = MenuItemUtil.createMenuItemI18n("metadata.menu.truncate",
                IconFactory.group(IconPaths.METADATA_TRUNCATE_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem enableItem = MenuItemUtil.createMenuItemI18n("metadata.menu.enable",
                IconFactory.group(IconPaths.METADATA_ENABLE_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem disableItem = MenuItemUtil.createMenuItemI18n("metadata.menu.disable",
                IconFactory.group(IconPaths.METADATA_DISABLE_ITEM, 0.06, 0.06));
        TreeViewUtil.connectFolderInfoItem = MenuItemUtil.createMenuItemI18n("metadata.menu.folder_connect_info",
                IconFactory.group(IconPaths.METADATA_CONNECT_FOLDER_INFO_ITEM, 0.55, 0.55));
        CustomShortcutMenuItem createConnectItem = MenuItemUtil.createMenuItemI18n("metadata.menu.create_connection",
                IconFactory.group(IconPaths.METADATA_CREATE_CONNECT_ITEM, 0.6, 0.6));
        TreeViewUtil.databaseOpenFileItem = MenuItemUtil.createMenuItemI18n("metadata.menu.new_sql", "Ctrl+N",
                IconFactory.group(IconPaths.METADATA_DATABASE_OPEN_FILE_ITEM, 0.6, 0.55));
        //MenuItem disconnectAll = new MenuItem("断开所有连接(Disconnect ALL)",disconnectItemIcon);
        CustomShortcutMenuItem disconnectFolder = MenuItemUtil.createMenuItemI18n("metadata.menu.disconnect_folder",
                IconFactory.group(IconPaths.METADATA_DISCONNECT_FOLDER, 0.6, 0.6));
        CustomShortcutMenuItem renameItem = MenuItemUtil.createMenuItemI18n("metadata.menu.rename", "F2",
                IconFactory.group(IconPaths.METADATA_RENAME_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem deleteItem = MenuItemUtil.createMenuItemI18n("metadata.menu.delete", "Delete",
                IconFactory.group(IconPaths.METADATA_DELETE_ITEM, 0.6, 0.6, IconFactory.dangerColor()));
        CustomShortcutMenuItem expandFolderItem = MenuItemUtil.createMenuItemI18n("metadata.menu.expand_default",
                IconFactory.group(IconPaths.METADATA_EXPAND_FOLDER_ITEM, 0.5, 0.5));
        CustomShortcutMenuItem foldFolderItem = MenuItemUtil.createMenuItemI18n("metadata.menu.collapse_default",
                IconFactory.group(IconPaths.METADATA_FOLD_FOLDER_ITEM, 0.75, 0.75));
        CustomShortcutMenuItem moveItem = MenuItemUtil.createMenuItemI18n("metadata.menu.move_to",
                IconFactory.group(IconPaths.METADATA_MOVE_ITEM, 0.7, 0.7));
        TreeViewUtil.refreshItem = MenuItemUtil.createMenuItemI18n("metadata.menu.refresh", "F5",
                IconFactory.group(IconPaths.METADATA_REFRESH_ITEM, 0.7, 0.7));
        TreeViewUtil.connectInfoItem = MenuItemUtil.createMenuItemI18n("metadata.menu.instance_info",
                IconFactory.group(IconPaths.METADATA_CONNECT_INFO_ITEM, 0.55, 0.55));
        CustomShortcutMenuItem connectItem = MenuItemUtil.createMenuItemI18n("metadata.menu.connect",
                IconFactory.group(IconPaths.METADATA_CONNECT_ITEM, 0.65, 0.65));
        CustomShortcutMenuItem reconnectItem = MenuItemUtil.createMenuItemI18n("metadata.menu.reconnect",
                IconFactory.group(IconPaths.METADATA_RECONNECT_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem disconnectItem = MenuItemUtil.createMenuItemI18n("metadata.menu.disconnect",
                IconFactory.group(IconPaths.METADATA_DISCONNECT_ITEM, 0.6, 0.6));
        CustomShortcutMenuItem copyconnectItem = MenuItemUtil.createMenuItemI18n("metadata.menu.copy_connection",
                IconFactory.group(IconPaths.METADATA_COPY_CONNECT_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem modifyconnectItem = MenuItemUtil.createMenuItemI18n("metadata.menu.modify_connection",
                IconFactory.group(IconPaths.METADATA_MODIFY_CONNECT_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem createDatabaseItem = MenuItemUtil.createMenuItemI18n("metadata.menu.create_database",
                IconFactory.group(IconPaths.METADATA_CREATE_DATABASE_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem createTableItem = MenuItemUtil.createMenuItemI18n("metadata.menu.create_table",
                IconFactory.group(IconPaths.METADATA_CREATE_TABLE_ITEM, 0.6, 0.6));
        CustomShortcutMenuItem setDefaultDatabaseItem = MenuItemUtil.createMenuItemI18n("metadata.menu.set_default_database",
                IconFactory.group(IconPaths.METADATA_SET_DEFAULT_DATABASE_ITEM, 0.6, 0.6));
        //setDefaultDatabaseItem.disableProperty().bind(trans_not_committed_buttons_hbox.visibleProperty());
        SeparatorMenuItem separator1 = new SeparatorMenuItem(); // 第一个分隔线
        SeparatorMenuItem separator2 = new SeparatorMenuItem();
        Menu exportMenu = new Menu();
        exportMenu.textProperty().bind(I18n.bind("metadata.menu.export", "导出数据"));
        exportMenu.setGraphic(IconFactory.group(IconPaths.RESULTSET_EXPORT, 0.6, 0.6));
        CustomShortcutMenuItem importDataItem = MenuItemUtil.createMenuItemI18n("metadata.menu.import_data",
                IconFactory.group(IconPaths.METADATA_IMPORT_DATA_ITEM, 0.6, 0.6));
        CustomShortcutMenuItem exportCsvItem = MenuItemUtil.createMenuItemI18n("metadata.menu.export.csv",null);
        CustomShortcutMenuItem exportJsonItem = MenuItemUtil.createMenuItemI18n("metadata.menu.export.json",null);
        CustomShortcutMenuItem exportSqlItem = MenuItemUtil.createMenuItemI18n("metadata.menu.export.sql",null);
        exportMenu.getItems().setAll(exportCsvItem, exportJsonItem, exportSqlItem);
 // 第一个分隔线
        CustomShortcutMenuItem healthCheckItem = MenuItemUtil.createMenuItemI18n("metadata.menu.health_check",
                IconFactory.group(IconPaths.METADATA_HEALTH_CHECK_ITEM, 0.65, 0.65));
        CustomShortcutMenuItem onlinelogItem = MenuItemUtil.createMenuItemI18n("metadata.menu.online_log",
                IconFactory.group(IconPaths.METADATA_ONLINE_LOG_ITEM, 0.5, 0.5));
        CustomShortcutMenuItem spaceManagerItem = MenuItemUtil.createMenuItemI18n("metadata.menu.space_manager",
                IconFactory.group(IconPaths.METADATA_SPACE_MANAGER_ITEM, 0.55, 0.55));
        CustomShortcutMenuItem onconfigItem = MenuItemUtil.createMenuItemI18n("metadata.menu.onconfig",
                IconFactory.group(IconPaths.METADATA_ONCONFIG_ITEM, 0.55, 0.55));
        CustomShortcutMenuItem instanceStopItem = MenuItemUtil.createMenuItemI18n("metadata.menu.instance_start_stop",
                IconFactory.groupFixedColor(IconPaths.METADATA_INSTANCE_STOP_ITEM, 0.65, 0.65, IconFactory.stopColor()));

        Menu ddlMenu = new Menu();
        ddlMenu.textProperty().bind(I18n.bind("metadata.menu.ddl.title", "查看DDL"));
        ddlMenu.setGraphic(IconFactory.group(IconPaths.METADATA_DDL_MENU, 0.65, 0.65));

        CustomShortcutMenuItem ddlToFile =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_file", null);
        CustomShortcutMenuItem ddlToClipboard  =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_clipboard", null);
        CustomShortcutMenuItem ddlToCurrentSqlEditarea =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_current_sql", null);
        CustomShortcutMenuItem ddlToNewSqlEditarea =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_new_sql", null);
        CustomShortcutMenuItem ddlToPopuWindow =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_popup_window", null);
        ddlMenu.getItems().addAll(ddlToClipboard,ddlToPopuWindow,ddlToFile,ddlToCurrentSqlEditarea,ddlToNewSqlEditarea);

        Menu importMenu = new Menu();
        importMenu.textProperty().bind(I18n.bind("metadata.menu.import", "导入"));
        importMenu.setGraphic(IconFactory.group(IconPaths.METADATA_IMPORT_DATA_ITEM, 0.6, 0.6));

        CustomShortcutMenuItem importSqlScriptItem =
                MenuItemUtil.createMenuItemI18n("metadata.menu.import.sql_script", null);
        importMenu.getItems().add(importSqlScriptItem);
        CustomShortcutMenuItem importDdlAndDataItem =
                MenuItemUtil.createMenuItemI18n("metadata.menu.import_ddl_data",
                        IconFactory.group(IconPaths.METADATA_IMPORT_DATA_ITEM, 0.6, 0.6));

        Menu exportDdlMenu = new Menu();
        exportDdlMenu.textProperty().bind(I18n.bind("metadata.menu.export_ddl.title", "导出DDL"));
        exportDdlMenu.setGraphic(IconFactory.group(IconPaths.METADATA_DDL_MENU, 0.65, 0.65));

        CustomShortcutMenuItem exportDdlAndDataItem =
                MenuItemUtil.createMenuItemI18n("metadata.menu.export_ddl_data",
                        IconFactory.group(IconPaths.RESULTSET_EXPORT, 0.6, 0.6));

        CustomShortcutMenuItem exportDdlToFile =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_file", null);
        CustomShortcutMenuItem exportDdlToClipboard =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_clipboard", null);
        CustomShortcutMenuItem exportDdlToCurrentSqlEditarea =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_current_sql", null);
        CustomShortcutMenuItem exportDdlToNewSqlEditarea =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_new_sql", null);
        CustomShortcutMenuItem exportDdlToPopupWindow =
                MenuItemUtil.createMenuItemI18n("metadata.menu.ddl.to_popup_window", null);
        exportDdlMenu.getItems().addAll(exportDdlToClipboard, exportDdlToPopupWindow, exportDdlToFile,
                exportDdlToCurrentSqlEditarea, exportDdlToNewSqlEditarea);

        ddlMenu.showingProperty().addListener((obs, was, now) -> {
            if (now && exportMenu.isShowing()) exportMenu.hide();
            if (now && importMenu.isShowing()) importMenu.hide();
            if (now && exportDdlMenu.isShowing()) exportDdlMenu.hide();
        });
        exportMenu.showingProperty().addListener((obs, was, now) -> {
            if (now && ddlMenu.isShowing()) ddlMenu.hide();
            if (now && importMenu.isShowing()) importMenu.hide();
            if (now && exportDdlMenu.isShowing()) exportDdlMenu.hide();
        });
        importMenu.showingProperty().addListener((obs, was, now) -> {
            if (now && ddlMenu.isShowing()) ddlMenu.hide();
            if (now && exportMenu.isShowing()) exportMenu.hide();
            if (now && exportDdlMenu.isShowing()) exportDdlMenu.hide();
        });
        exportDdlMenu.showingProperty().addListener((obs, was, now) -> {
            if (now && ddlMenu.isShowing()) ddlMenu.hide();
            if (now && importMenu.isShowing()) importMenu.hide();
            if (now && exportMenu.isShowing()) exportMenu.hide();
        });

        treeview_menu.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) return;
            Node skinRoot = newSkin.getNode();
            skinRoot.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, event -> {
                if (!ddlMenu.isShowing() && !exportMenu.isShowing() && !importMenu.isShowing() && !exportDdlMenu.isShowing()) return;
                Node target = (Node) event.getTarget();
                while (target != null && target != skinRoot) {
                    if (target.getStyleClass().contains("menu-item")) {
                        if (!target.getStyleClass().contains("menu")) {
                            if (ddlMenu.isShowing()) ddlMenu.hide();
                            if (exportMenu.isShowing()) exportMenu.hide();
                            if (importMenu.isShowing()) importMenu.hide();
                            if (exportDdlMenu.isShowing()) exportDdlMenu.hide();
                        }
                        return;
                    }
                    target = target.getParent();
                }
            });
        });
        

        exportCsvItem.setOnAction(ev -> TreeCrudHandler.exportTableData(treeView.getSelectionModel().getSelectedItems(), TreeCrudHandler.ExportFormat.CSV));
        exportJsonItem.setOnAction(ev -> TreeCrudHandler.exportTableData(treeView.getSelectionModel().getSelectedItems(), TreeCrudHandler.ExportFormat.JSON));
        exportSqlItem.setOnAction(ev -> TreeCrudHandler.exportTableData(treeView.getSelectionModel().getSelectedItems(), TreeCrudHandler.ExportFormat.SQL));
        importDataItem.setOnAction(ev -> TreeCrudHandler.importTableData(treeView.getSelectionModel().getSelectedItem()));
        importSqlScriptItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem == null || !(selectedItem.getValue() instanceof Database database)) {
                return;
            }
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("main.filechooser.select_sql", "选择SQL文件"));
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL Files", "*.sql"));
            File file = fileChooser.showOpenDialog(AppState.getWindow());
            if (file == null) {
                return;
            }

            Connect connect = new Connect(TreeNavigator.getMetaConnect(selectedItem));
            TreeCrudHandler.applyDatabaseConnectionProps(connect, database, database.getName());
            TreeViewUtil.databaseService.importSqlScript(connect, file, () ->
                    NotificationUtil.showMainNotification(
                            I18n.t("metadata.import_sql.notice.completed", "数据库\"%s\"导入脚本完成：%s")
                                    .formatted(database.getName(), file.getName())
                    ));
            NotificationUtil.showMainNotification(
                    I18n.t("metadata.import_sql.notice.queued", "数据库\"%s\"导入脚本任务已提交：%s")
                            .formatted(database.getName(), file.getName())
            );
        });
        importDdlAndDataItem.setOnAction(event ->
                TreeCrudHandler.importDatabaseDdlAndData(treeView.getSelectionModel().getSelectedItem()));
        
        //右键连接信息点击响应
        TreeViewUtil.connectInfoItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect((Connect) selectedItem.getValue());
            TabpaneUtil.addCustomInstanceTab(connect,0);
        });


        healthCheckItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect((Connect) selectedItem.getValue());
            TabpaneUtil.addCustomInstanceTab(connect,1);
        });

        onlinelogItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect((Connect) selectedItem.getValue());
            TabpaneUtil.addCustomInstanceTab(connect,2);
        });

        spaceManagerItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect((Connect) selectedItem.getValue());
            TabpaneUtil.addCustomInstanceTab(connect,3);
        });
        onconfigItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect((Connect) selectedItem.getValue());
            TabpaneUtil.addCustomInstanceTab(connect,4);
        });

        instanceStopItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect((Connect) selectedItem.getValue());
            TabpaneUtil.addCustomInstanceTab(connect,5);
        });

        //点击鼠标后右键弹出框隐藏
        treeView.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton().equals(MouseButton.PRIMARY) && event.getClickCount() == 1) {
                treeview_menu.hide();
            }
        });

        //加载进度
        Main.loadProgressBar.setProgress(0.8);




        treeView.setOnKeyPressed((KeyEvent event) -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem == null || selectedItem.getValue() == null) {
                return;
            }
            if (event.getCode() == KeyCode.C && event.isControlDown()) {
                if (TreeNavigator.canCopyItem(selectedItem)) {
                    copyItem.fire();
                    event.consume();
                }
                return;
            }
            if (event.getCode() == KeyCode.N && event.isControlDown()) {
                if (selectedItem.getValue() instanceof Database) {
                    TreeViewUtil.databaseOpenFileItem.fire();
                    event.consume();
                }
                return;
            }
            if (event.getCode() == KeyCode.F5) {
                if (TreeNavigator.canRefreshItem(selectedItem)) {
                    TreeViewUtil.refreshItem.fire();
                    event.consume();
                }
                return;
            }
            if (event.getCode() == KeyCode.F2) {
                if (TreeNavigator.canRenameItem(selectedItem)) {
                    renameItem.fire();
                    event.consume();
                }
                return;
            }
            if (event.getCode() == KeyCode.DELETE) {
                if (TreeNavigator.canDeleteItem(selectedItem)) {
                    deleteItem.fire();
                    event.consume();
                }
            }
        });

        copyItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(selectedItem.getValue().getName());
            clipboard.setContent(content);
        });
        packageDDLItem.setOnAction(event-> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeData connect = selectedItem.getValue();
            if (selectedItem.getChildren().size()==0&&!selectedItem.isExpanded()) {
                ((DBPackage)selectedItem.getValue()).setShowDDL(true);
                selectedItem.setExpanded(true);
                selectedItem.setExpanded(false);
            } else {
                PopupWindowUtil.openDDLWindow(((DBPackage)connect).getDDL());
            }
        });


        //右键弹出框及点击响应事件结束

        //改为裸表
        modifyToRawItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeData treeData = selectedItem.getValue();
            Boolean confirm = AlertUtil.CustomAlertConfirm(
                    I18n.t("metadata.alert.modify_to_raw.title", "改为裸表"),
                    I18n.t("metadata.alert.modify_to_raw.content", "确定将表\"%s\"更改为裸表吗？裸表具有更高的性能但不支持事务回滚，不建议在生产环境使用！")
                            .formatted(treeData.getName())
            );
            if(confirm){
                Connect connect = TreeCrudHandler.buildObjectConnect(selectedItem, false);
                TreeViewUtil.tableService.modifyTableToRaw(connect, treeData.getName(), () -> 
                {
                    ((Table)treeData).setTableTypeCode("raw");
                    NotificationUtil.showMainNotification(
                        I18n.t("backsql.notice.table_raw", "表\"%s\"已改为裸表！").formatted(treeData.getName())
                    );
                }
            );
            }
        });

        //改为标准表
        modifyToStandardItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeData treeData = selectedItem.getValue();
            Boolean confirm = AlertUtil.CustomAlertConfirm(
                    I18n.t("metadata.alert.modify_to_standard.title", "改为标准表"),
                    I18n.t("metadata.alert.modify_to_standard.content", "确定将表\"%s\"更改为标准表吗？")
                            .formatted(treeData.getName())
            );
            if(confirm){
                Connect connect = TreeCrudHandler.buildObjectConnect(selectedItem, false);
                TreeViewUtil.tableService.modifyTableToStandard(connect, treeData.getName(), () -> 
                {
                    ((Table)treeData).setTableTypeCode("standard");
                    NotificationUtil.showMainNotification(
                            I18n.t("backsql.notice.table_standard", "表\"%s\"已改为标准表！").formatted(treeData.getName())
                    );
            }
            );
            }
        });

        //清空表事件
        truncateItem.setOnAction(event -> {
            List<TreeItem<TreeData>> selectedItems = new ArrayList<>(treeView.getSelectionModel().getSelectedItems());
            if (TreeNavigator.isMultiTableSelection(selectedItems)) {
                boolean hasExternalTable = false;
                for (TreeItem<TreeData> item : selectedItems) {
                    if (isTableType(((Table) item.getValue()).getTableTypeCode(), "external")) {
                        hasExternalTable = true;
                        break;
                    }
                }
                if (hasExternalTable) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            I18n.t("metadata.alert.truncate.external_not_supported", "选中项中包含外部表，无法批量清空！")
                    );
                    return;
                }
                boolean confirmBatch = AlertUtil.CustomAlertConfirm(
                        I18n.t("metadata.alert.truncate.title", "清空表"),
                        I18n.t("metadata.alert.truncate.batch_content", "确定要清空选中的%d个表吗？")
                                .formatted(selectedItems.size())
                );
                if (!confirmBatch) {
                    return;
                }
                Connect connect = TreeCrudHandler.buildObjectConnect(selectedItems.get(0), false);
                List<String> sqlList = new ArrayList<>();
                for (TreeItem<TreeData> item : selectedItems) {
                    sqlList.add("truncate table " + item.getValue().getName());
                }
                TreeViewUtil.tableService.executeObjectSqls(connect, sqlList, () -> {
                    for (TreeItem<TreeData> item : selectedItems) {
                        item.getValue().setRunning(true);
                        TreeViewUtil.tableService.refreshTableMeta(
                                TreeNavigator.getMetaConnect(item),
                                TreeNavigator.getCurrentDatabase(item),
                                item.getValue().getName(),
                                item::setValue,
                                () -> item.getValue().setRunning(false)
                        );
                    }
                    NotificationUtil.showMainNotification(
                            I18n.t("backsql.notice.batch_table_truncate_submitted", "已提交%d个表的清空任务！")
                                    .formatted(selectedItems.size())
                    );
                });
                return;
            }
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeData treeData = selectedItem.getValue();
            Boolean confirm = AlertUtil.CustomAlertConfirm(
                    I18n.t("metadata.alert.truncate.title", "清空表"),
                    I18n.t("metadata.alert.truncate.content", "确定要清空表\"%s\"吗？")
                            .formatted(treeData.getName())
            );
            if(confirm){
                Connect connect = TreeCrudHandler.buildObjectConnect(selectedItem, false);
                TreeViewUtil.tableService.truncateTable(connect, treeData.getName(), () -> {
                    selectedItem.getValue().setRunning(true);
                    TreeViewUtil.tableService.refreshTableMeta(
                            TreeNavigator.getMetaConnect(selectedItem),
                            TreeNavigator.getCurrentDatabase(selectedItem),
                            selectedItem.getValue().getName(),
                            selectedItem::setValue,
                            () -> selectedItem.getValue().setRunning(false)
                    );

                    NotificationUtil.showMainNotification(
                        I18n.t("backsql.notice.table_truncated", "表\"%s\"已清空！").formatted(treeData.getName())
                );

                }
            );
            }
        });


        //禁用
        disableItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeCrudHandler.toggleObjectEnabled(selectedItem, false);
        });

        //启用
        enableItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeCrudHandler.toggleObjectEnabled(selectedItem, true);
        });

        sqlHisItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            PopupWindowUtil.openSqlHistoryPopupWindow(((Connect)selectedItem.getValue()).getId());
        });


        TreeViewUtil.connectFolderInfoItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TabpaneUtil.addConnectsInfoTab((ConnectFolder)selectedItem.getValue());

        });
        /*
        connectOpenFileItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = databasemeta_treeview.getSelectionModel().getSelectedItem();
            ComstomTabUtil.addCustomSqlTab(sql_tabpane,selectedItem.getValue());
        });
    */
        TreeViewUtil.databaseOpenFileItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect(TreeNavigator.getMetaConnect(selectedItem));
            Database database=TreeNavigator.getCurrentDatabase(selectedItem);
            TreeCrudHandler.applyDatabaseConnectionProps(connect, database, database.getName());
            TabpaneUtil.addCustomSqlTab(connect);
        });




        //右键连接点击响应
        connectItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            selectedItem.getChildren().clear();
            selectedItem.setExpanded(false);
            selectedItem.setExpanded(true);
        });
        //右键重连点击响应
        reconnectItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeNavigator.reconnectItem(selectedItem);
        });
        //右键断开连接点击响应
        disconnectItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeNavigator.disconnectItem(selectedItem);
        });



        //断开分类下所有连接
        disconnectFolder.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeNavigator.disconnectFolder(selectedItem);
        });


        //右键新建连接点击响应
        createConnectItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeData connect= selectedItem.getValue();
            TreeNavigator.showCreateConnectDialog(connect,false);
        });

        //右键编辑点击响应
        modifyconnectItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=(Connect)selectedItem.getValue();
            TreeNavigator.showCreateConnectDialog(connect,false);
        });

        //右键复制连接点击响应
        copyconnectItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            Connect connect=new Connect((Connect) selectedItem.getValue());
            connect.setConn(null);
            connect.setName(LocalDbRepository.getCopyName(connect));
            TreeNavigator.showCreateConnectDialog(connect,true);
            //String result=LocalDbRepository.createConnect(connect);
            //--------/*

        });


        //右键重命名点击响应
        renameItem.setOnAction(event -> {
            TreeCrudHandler.renameTreeItem(treeView);
        });

        //创建用户
        addUserItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(5);
            grid.setPadding(new Insets(10));
            CustomUserTextField userName = new CustomUserTextField();
            CustomPasswordField passwordField1 = new CustomPasswordField();
            CustomPasswordField passwordField2 = new CustomPasswordField();
            userName.requestFocus();
            Label nameLabel=new Label(I18n.t("metadata.label.username", "用户名")) ;
            SVGPath nameLabelIcon = IconFactory.create(IconPaths.METADATA_NAME_LABEL, 0.55, 0.55, Color.valueOf("#888"));
            nameLabel.setGraphic(nameLabelIcon);

            Label passwordLabel=new Label(I18n.t("metadata.label.password", "密码"));

            SVGPath passwordLabelIcon = IconFactory.create(IconPaths.METADATA_PASSWORD_LABEL, 0.5, 0.5, Color.valueOf("#888"));
            passwordLabel.setGraphic(passwordLabelIcon);

            Label confirmPasswordLabel=new Label(I18n.t("metadata.label.confirm_password", "确认密码"));

            SVGPath confirmPasswordLabelIcon = IconFactory.create(IconPaths.METADATA_CONFIRM_PASSWORD_LABEL, 0.5, 0.5, Color.valueOf("#888"));
            confirmPasswordLabel.setGraphic(confirmPasswordLabelIcon);

            grid.add(nameLabel, 0, 0);
            grid.add(userName, 1, 0);
            grid.add(passwordLabel, 0, 1);
            grid.add(passwordField1, 1, 1);
            grid.add(confirmPasswordLabel, 0, 2);
            grid.add(passwordField2, 1, 2);

            ButtonType createButtonType = new ButtonType(I18n.t("metadata.button.create", "创建"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButtonType = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
            AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                    I18n.t("metadata.dialog.create_user.title", "创建用户"),
                    grid,
                    420,
                    Region.USE_COMPUTED_SIZE,
                    createButtonType,
                    cancelButtonType
            );
            Button commit = dialog.getButton(createButtonType);


            commit.addEventFilter(ActionEvent.ACTION, event1 -> {
                if (userName.getText().trim().isEmpty()) {
                    userName.requestFocus();
                    event1.consume();
                } else if (passwordField1.getText().trim().isEmpty()) {
                    passwordField1.requestFocus();
                    event1.consume();
                } else if (passwordField2.getText().trim().isEmpty()) {
                    passwordField2.requestFocus();
                    event1.consume();
                } else if (!passwordField1.getText().trim().equals(passwordField2.getText().trim())) {
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("metadata.error.password_not_match", "两次密码输入不一致！"));
                    event1.consume();
                } else {
                    event1.consume();
                    Connect connect=TreeCrudHandler.buildObjectConnect(selectedItem,true);
                    TreeViewUtil.userService.executeObjectSql(connect, "create user " + userName.getText().trim() + " with password '" + passwordField1.getText().trim() + "'", 
                    () -> {
                        selectedItem.getChildren().clear();
                        selectedItem.setExpanded(false);
                        selectedItem.setExpanded(true);
                        NotificationUtil.showMainNotification(
                                I18n.t("metadata.success.create_user", "用户创建成功！")
                        );
                        dialog.getStage().close();
                    }
                );
                }

            });

            dialog.showAndWait();

        });

        //创建用户
        modifyUserItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(5);
            grid.setPadding(new Insets(10));
            CustomPasswordField passwordField1 = new CustomPasswordField();
            CustomPasswordField passwordField2 = new CustomPasswordField();


            Label passwordLabel=new Label(I18n.t("metadata.label.new_password", "新密码"));

            SVGPath passwordLabelIcon = IconFactory.create(IconPaths.METADATA_PASSWORD_LABEL, 0.5, 0.5, Color.valueOf("#888"));
            passwordLabel.setGraphic(passwordLabelIcon);

            Label confirmPasswordLabel=new Label(I18n.t("metadata.label.confirm_password", "确认密码"));

            SVGPath confirmPasswordLabelIcon = IconFactory.create(IconPaths.METADATA_CONFIRM_PASSWORD_LABEL, 0.5, 0.5, Color.valueOf("#888"));
            confirmPasswordLabel.setGraphic(confirmPasswordLabelIcon);


            grid.add(passwordLabel, 0, 0);
            grid.add(passwordField1, 1, 0);
            grid.add(confirmPasswordLabel, 0, 1);
            grid.add(passwordField2, 1, 1);

            ButtonType resetButtonType = new ButtonType(I18n.t("metadata.button.reset", "重置"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButtonType = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
            AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                    I18n.t("metadata.dialog.reset_password.title", "重置密码"),
                    grid,
                    420,
                    Region.USE_COMPUTED_SIZE,
                    resetButtonType,
                    cancelButtonType
            );
            Button commit = dialog.getButton(resetButtonType);


            commit.addEventFilter(ActionEvent.ACTION, event1 -> {
                if (passwordField1.getText().trim().isEmpty()) {
                    passwordField1.requestFocus();
                    event1.consume();
                } else if (passwordField2.getText().trim().isEmpty()) {
                    passwordField2.requestFocus();
                    event1.consume();
                } else if (!passwordField1.getText().trim().equals(passwordField2.getText().trim())) {
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("metadata.error.password_not_match", "两次密码输入不一致！"));
                    event1.consume();
                } else {
                    event1.consume();
                    Connect connect=TreeCrudHandler.buildObjectConnect(selectedItem,true);
                    TreeViewUtil.userService.executeObjectSql(
                            connect,
                            "alter user " + selectedItem.getValue().getName() + " modify password '" + passwordField1.getText().trim() + "'",
                            () -> {
                                NotificationUtil.showMainNotification(
                                        I18n.t("backsql.notice.user_password_reset", "用户\"%s\"密码已重置！")
                                                .formatted(selectedItem.getValue().getName())
                                );
                                dialog.getStage().close();
                            }
                    );
                }

            });

            dialog.showAndWait();

        });

        updateStatisticsItem.setOnAction(event -> {
            List<TreeItem<TreeData>> selectedItems = new ArrayList<>(treeView.getSelectionModel().getSelectedItems());
            if (TreeNavigator.isMultiTableSelection(selectedItems)) {
                boolean confirmBatch = AlertUtil.CustomAlertConfirm(
                        I18n.t("backsql.confirm.update_statistics.title", "统计更新"),
                        I18n.t("backsql.confirm.update_statistics.batch_content", "确定要对选中的%d个表执行统计更新吗？")
                                .formatted(selectedItems.size())
                );
                if (!confirmBatch) {
                    return;
                }
                Connect connect = TreeCrudHandler.buildObjectConnect(selectedItems.get(0), false);
                List<String> sqlList = new ArrayList<>();
                for (TreeItem<TreeData> item : selectedItems) {
                    sqlList.add("update statistics for table " + item.getValue().getName());
                }
                TreeViewUtil.tableService.executeObjectSqls(connect, sqlList, () -> NotificationUtil.showMainNotification(
                        I18n.t("backsql.notice.batch_update_statistics_submitted", "%d个表统计更新已完成！")
                                .formatted(selectedItems.size())
                ));
                return;
            }
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TreeData treeData = selectedItem.getValue();
            Connect connect = TreeCrudHandler.buildObjectConnect(selectedItem, false);
            boolean confirm = AlertUtil.CustomAlertConfirm(
                        I18n.t("backsql.confirm.update_statistics.title", "统计更新"),
                        I18n.t("backsql.confirm.update_statistics.content", "确定要执行统计更新吗？")
                );
            if (!confirm) {
                    return;
                }
            if (treeData instanceof Database) {
                TreeViewUtil.databaseService.updateStatistics(connect, "update statistics", ()->{
                    NotificationUtil.showMainNotification(I18n.t("backsql.notice.update_statistics_done", "统计更新执行完成！"));
                });                
            }
            else if (treeData instanceof ObjectFolder) {
                TreeDataLoader.ObjectFolderKind objectFolderKind = TreeDataLoader.getObjectFolderKind(selectedItem);
                if(objectFolderKind == TreeDataLoader.ObjectFolderKind.SYSTEM_TABLE_VIEW || objectFolderKind == TreeDataLoader.ObjectFolderKind.TABLES){
                    TreeViewUtil.tableService.updateStatistics(connect, "update statistics high for table force", ()->{
                        NotificationUtil.showMainNotification(I18n.t("backsql.notice.update_statistics_done", "统计更新执行完成！"));
                    });    
                }
                else if(objectFolderKind == TreeDataLoader.ObjectFolderKind.PROCEDURES){
                    TreeViewUtil.procedureService.updateStatistics(connect, "update statistics for procedure", ()->{
                        NotificationUtil.showMainNotification(I18n.t("backsql.notice.update_statistics_done", "统计更新执行完成！"));
                    });    
                }
            }
            else if(treeData instanceof SysTable||treeData instanceof Table){
                    TreeViewUtil.tableService.updateStatisticsForTable(connect, treeData.getName(), ()->{
                        NotificationUtil.showMainNotification(I18n.t("backsql.notice.update_statistics_done", "统计更新执行完成！"));
                    });
                    
            }
            else if(treeData instanceof Procedure){
                    TreeViewUtil.procedureService.updateStatistics(connect,"update statistics for procedure "+ treeData.getName(), ()->{
                        NotificationUtil.showMainNotification(I18n.t("backsql.notice.update_statistics_done", "统计更新执行完成！"));
                    });  
            }
        });

        ddlToFile.setOnAction(event -> TreeCrudHandler.handleDdlAction(treeView, (treeData, ddlText) -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("metadata.menu.ddl.to_file", "保存DDL为SQL"));
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL Files", "*.sql"));
            fileChooser.setInitialFileName(treeData.getName() + ".sql");
            File file = fileChooser.showSaveDialog(AppState.getWindow());
            if (file != null) {
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(ddlText);
                    NotificationUtil.showMainNotification( I18n.t("metadata.notice.ddl_saved", "DDL已保存到文件"));
                } catch (IOException e) {
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
                }
            }
        }));

        ddlToClipboard.setOnAction(event -> TreeCrudHandler.handleDdlAction(treeView, (treeData, ddlText) -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(ddlText);
            clipboard.setContent(content);
            NotificationUtil.showMainNotification( "DDL已复制到剪切板");
        }));

        ddlToPopuWindow.setOnAction(event -> TreeCrudHandler.handleDdlAction(treeView, (treeData, ddlText) -> {
            PopupWindowUtil.openDDLWindow(ddlText);
        }));

        ddlToNewSqlEditarea.setOnAction(event -> TreeCrudHandler.handleDdlAction(treeView, (treeData, ddlText) -> {
            AppState.getNewSqlFileMenuItem().fire();
            if (AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab) {
                currentSqlTab.sqlTabController.sqlEditCodeArea.replaceText(ddlText);
            }
        }));

        ddlToCurrentSqlEditarea.setOnAction(event -> TreeCrudHandler.handleDdlAction(treeView, (treeData, ddlText) -> {
            if (AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab) {
                int currentPos=currentSqlTab.sqlTabController.sqlEditCodeArea.getCaretPosition();
                currentSqlTab.sqlTabController.sqlEditCodeArea.insertText(currentPos, ddlText);
            }else{
                AppState.getNewSqlFileMenuItem().fire();
                if (AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab) {
                    currentSqlTab.sqlTabController.sqlEditCodeArea.replaceText(ddlText);
                }
            }
        }));

        exportDdlToFile.setOnAction(event -> TreeCrudHandler.handleDatabaseDdlAction(treeView, (treeData, ddlText) -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("metadata.menu.ddl.to_file", "保存DDL为SQL"));
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL Files", "*.sql"));
            fileChooser.setInitialFileName(treeData.getName() + ".sql");
            File file = fileChooser.showSaveDialog(AppState.getWindow());
            if (file != null) {
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(ddlText);
                    NotificationUtil.showMainNotification(I18n.t("metadata.notice.ddl_saved", "DDL已保存到文件"));
                } catch (IOException e) {
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
                }
            }
        }));

        exportDdlToClipboard.setOnAction(event -> TreeCrudHandler.handleDatabaseDdlAction(treeView, (treeData, ddlText) -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(ddlText);
            clipboard.setContent(content);
            NotificationUtil.showMainNotification("DDL已复制到剪切板");
        }));

        exportDdlToPopupWindow.setOnAction(event -> TreeCrudHandler.handleDatabaseDdlAction(treeView, (treeData, ddlText) -> {
            PopupWindowUtil.openDDLWindow(ddlText);
        }));

        exportDdlAndDataItem.setOnAction(event -> TreeCrudHandler.exportDatabaseDdlAndData(treeView));

        exportDdlToNewSqlEditarea.setOnAction(event -> TreeCrudHandler.handleDatabaseDdlAction(treeView, (treeData, ddlText) -> {
            AppState.getNewSqlFileMenuItem().fire();
            if (AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab) {
                currentSqlTab.sqlTabController.sqlEditCodeArea.replaceText(ddlText);
            }
        }));

        exportDdlToCurrentSqlEditarea.setOnAction(event -> TreeCrudHandler.handleDatabaseDdlAction(treeView, (treeData, ddlText) -> {
            if (AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab) {
                int currentPos=currentSqlTab.sqlTabController.sqlEditCodeArea.getCaretPosition();
                currentSqlTab.sqlTabController.sqlEditCodeArea.insertText(currentPos, ddlText);
            }else{
                AppState.getNewSqlFileMenuItem().fire();
                if (AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab) {
                    currentSqlTab.sqlTabController.sqlEditCodeArea.replaceText(ddlText);
                }
            }
        }));

        //右键删除连接点击响应
        deleteItem.setOnAction(event -> {
            List<TreeItem<TreeData>> selectedItems = new ArrayList<>(treeView.getSelectionModel().getSelectedItems());
            if (TreeNavigator.isMultiTableSelection(selectedItems)) {
                boolean confirmBatch = AlertUtil.CustomAlertConfirm(
                        I18n.t("backsql.confirm.delete_table.title", "删除表"),
                        I18n.t("backsql.confirm.delete_table.batch_content", "确定要删除选中的%d个表吗？")
                                .formatted(selectedItems.size())
                );
                if (!confirmBatch) {
                    return;
                }
                Connect connect = TreeCrudHandler.buildObjectConnect(selectedItems.get(0), false);
                List<String> sqlList = new ArrayList<>();
                for (TreeItem<TreeData> item : selectedItems) {
                    sqlList.add("drop table " + item.getValue().getName());
                }
                TreeViewUtil.tableService.executeObjectSqls(connect, sqlList, () -> {
                    for (TreeItem<TreeData> item : selectedItems) {
                        TreeItem<TreeData> parent = item.getParent();
                        if (parent != null) {
                            parent.getChildren().remove(item);
                        }
                    }
                    NotificationUtil.showMainNotification(
                            I18n.t("backsql.notice.batch_table_delete_submitted", "%d个表已删除！")
                                    .formatted(selectedItems.size())
                    );
                });
                return;
            }
            if (TreeNavigator.isMultiDeleteOnlySelection(selectedItems)) {
                TreeItem<TreeData> firstItem = selectedItems.get(0);
                String objectType = TreeNavigator.getDeleteObjectType(firstItem.getValue());
                String objectDisplayName = TreeCrudHandler.getDeleteObjectDisplayName(objectType);
                boolean confirmBatch = AlertUtil.CustomAlertConfirm(
                        I18n.t(TreeCrudHandler.getDeleteConfirmTitleKey(objectType), "删除对象"),
                        I18n.t("metadata.alert.delete_object.batch_content", "确定要删除选中的%d个%s吗？")
                                .formatted(selectedItems.size(), objectDisplayName)
                );
                if (!confirmBatch) {
                    return;
                }
                MetaObjectService service = TreeNavigator.getDeleteService(firstItem.getValue());
                if (service == null) {
                    return;
                }
                Connect connect = TreeCrudHandler.buildObjectConnect(firstItem, false);
                List<String> sqlList = new ArrayList<>();
                for (TreeItem<TreeData> item : selectedItems) {
                    sqlList.add("drop " + objectType + " " + item.getValue().getName());
                }
                service.executeObjectSqls(connect, sqlList, () -> {
                    for (TreeItem<TreeData> item : selectedItems) {
                        TreeItem<TreeData> parent = item.getParent();
                        if (parent != null) {
                            parent.getChildren().remove(item);
                        }
                    }
                    NotificationUtil.showMainNotification(
                            I18n.t("metadata.notice.delete_object_batch_done", "已删除%d个%s！")
                                    .formatted(selectedItems.size(), objectDisplayName)
                    );
                });
                return;
            }
            TreeCrudHandler.deleteTreeItem(treeView);
        });

        //右键移动连接点击响应
        moveItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            HBox hbox = new HBox();
            hbox.getChildren().add(new Label(I18n.t("metadata.dialog.move_connection.target", "请选择移动到  ")));
            hbox.setAlignment(Pos.CENTER_LEFT);
            ChoiceBox<TreeData> choiceBox = new ChoiceBox<>();
            List<TreeData> list = new ArrayList<>();
            for (TreeItem<TreeData> treeItem : treeView.getRoot().getChildren()) {
                if ( !treeItem.getValue().getName().equals(selectedItem.getParent().getValue().getName())) {
                    list.add(treeItem.getValue());
                }
            }
            choiceBox.setItems(FXCollections.observableArrayList(list));
            choiceBox.getSelectionModel().select(0);
            hbox.getChildren().add(choiceBox);

            ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
            ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
            AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                    I18n.t("metadata.dialog.move_connection.title", "移动数据库连接"),
                    hbox,
                    430,
                    180,
                    buttonTypeOk,
                    buttonTypeCancel
            );
            choiceBox.requestFocus();
            choiceBox.setPrefWidth(150);
            Connect connect = (Connect) selectedItem.getValue();
            connect.setParentId(((ConnectFolder) choiceBox.getSelectionModel().getSelectedItem()).getId());
            choiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
                connect.setParentId(((ConnectFolder) newValue).getId());
            });

            ButtonType result = dialog.showAndWait();
            if (result == buttonTypeOk) {
                selectedItem.setValue(connect);
                LocalDbRepository.updateConnect(connect);
                selectedItem.getParent().getChildren().remove(selectedItem);
                TreeNavigator.treeViewMoveConnectItem(treeView,selectedItem);
            }
        });


        //右键展开层级点击响应
        expandFolderItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            ConnectFolder connectFolder =(ConnectFolder) selectedItem.getValue();
            connectFolder.setExpand(1);
            LocalDbRepository.updateConnectFolder(connectFolder);
            selectedItem.setExpanded(true);
        });

        //右键折叠点击响应
        foldFolderItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            ConnectFolder connectFolder =(ConnectFolder) selectedItem.getValue();
            connectFolder.setExpand(0);
            LocalDbRepository.updateConnectFolder(connectFolder);
            selectedItem.setExpanded(false);
        });


        //右键新建数据库点击响应
        createDatabaseItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            Label nameLabel = new Label(I18n.t("metadata.dialog.create_database.name", "数据库名称 "));
            Label charsetLabel = new Label(I18n.t("metadata.dialog.create_database.charset", "选择字符集 "));
            Label dbspaceLabel = new Label(I18n.t("metadata.dialog.create_database.dbspace", "选存储空间 "));

            nameLabel.setMinWidth(80);
            charsetLabel.setMinWidth(80);
            dbspaceLabel.setMinWidth(80);

            CustomUserTextField textField = new CustomUserTextField();
            // 定义过滤器，只允许 ASCII 字符输入（禁止中文）
            UnaryOperator<TextFormatter.Change> filter = change -> {
                String newText = change.getControlNewText();
                if (newText.matches("[\\x00-\\x7F]*")) {
                    return change;  // 如果输入是 ASCII 字符（英文、数字等），则允许修改
                } else {
                    return null;  // 禁止输入中文字符
                }
            };
            // 将过滤器应用到 TextField
            TextFormatter<String> textFormatter = new TextFormatter<>(filter);
            textField.setTextFormatter(textFormatter);
            textField.setTooltip(new Tooltip(I18n.t("metadata.dialog.create_database.name_rule", "不可使用中文或空格或数字开头")));
            textField.setPrefWidth(240);
            ChoiceBox<String> comboBox = new ChoiceBox<>();
            comboBox.getItems().addAll(
                    I18n.t("metadata.dialog.create_database.charset.utf8", "ZH_CN.UTF8(推荐)"),
                    I18n.t("metadata.dialog.create_database.charset.gb18030", "ZH_CN.GB18030-2000(兼容GBK)"),
                    I18n.t("metadata.dialog.create_database.charset.en", "EN_US.819(ISO8859-1)")
            );
            comboBox.setValue(I18n.t("metadata.dialog.create_database.charset.utf8", "ZH_CN.UTF8(推荐)"));
            comboBox.setId("createDatabaseCharset");
            comboBox.setPrefWidth(240);

            ChoiceBox<String> comboBox1 = new ChoiceBox<>();
            comboBox1.setId("createDatabaseDbspace");
            comboBox1.setPrefWidth(240);

            ObservableList<String> dbspaceList = null;
            try {
                if(selectedItem==null){
                    log.info("selectitem is null");
                }
                else {
                    log.info("selectitem is "+selectedItem.getValue().getName());
                }
                Connect connectForDb = (Connect) selectedItem.getParent().getValue();
                dbspaceList = FXCollections.observableArrayList(TreeViewUtil.databaseService.getStorageSpacesForCreateDatabase(connectForDb));
            }catch (SQLException e){
                AppErrorHandler.handle(e);
            }
            catch (Exception e) {
                AppErrorHandler.handle(e);
            }
            comboBox1.setItems(dbspaceList);
            comboBox1.setValue(dbspaceList.get(0));

            grid.add(nameLabel, 0, 0);
            grid.add(textField, 1, 0);
            grid.add(charsetLabel, 0, 1);
            grid.add(comboBox, 1, 1);
            grid.add(dbspaceLabel, 0, 2);
            grid.add(comboBox1, 1, 2);

            ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
            ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
            AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                    I18n.t("metadata.dialog.create_database.title", "新建数据库"),
                    grid,
                    460,
                    250,
                    buttonTypeOk,
                    buttonTypeCancel
            );
            Button button = dialog.getButton(buttonTypeOk);
            button.setDisable(true);
            textField.requestFocus();
            textField.textProperty().addListener((observable, oldValue, newValue) -> {
                textField.setText(newValue.replace(" ", ""));
                if (textField.getText().isEmpty()){
                    button.setDisable(true);
                } else {
                    button.setDisable(false);
                }
            });

            ButtonType result = dialog.showAndWait();
            if (result == buttonTypeOk) {
                Connect connect = new Connect((Connect) selectedItem.getParent().getValue());
                String dbLocale = ((String) comboBox.getValue()).replaceAll("\\([^()]*\\)", "");
                connect.setDatabase(resolveFallbackDatabase(connect));
                ConnectionPropertyUtil.applySupportedConnectionProperty(
                        TreeViewUtil.connectionService,
                        resolvePlatformResolver(),
                        connect,
                        "DB_LOCALE",
                        dbLocale
                );
                String sql = "create database " + textField.getText() + " in "
                        + ((String) comboBox1.getValue()).replaceAll("\\([^()]*\\)", "")
                        + " with log";
                TreeViewUtil.databaseService.executeObjectSql(connect, sql, () -> {
                    NotificationUtil.showMainNotification(
                            I18n.t("backsql.notice.database_created", "数据库[%s]创建成功").formatted(textField.getText())
                    );
                    selectedItem.getChildren().clear();
                    selectedItem.setExpanded(false);
                    selectedItem.setExpanded(true);
                });

            }
        });
        TreeViewUtil.refreshItem.setOnAction(event->{
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if(selectedItem.isLeaf()){
                if(selectedItem.getValue() instanceof Table||selectedItem.getValue() instanceof SysTable){
                    selectedItem.getValue().setRunning(true);
                    TreeViewUtil.tableService.refreshTableMeta(
                            TreeNavigator.getMetaConnect(selectedItem),
                            TreeNavigator.getCurrentDatabase(selectedItem),
                            selectedItem.getValue().getName(),
                            selectedItem::setValue,
                            () -> selectedItem.getValue().setRunning(false)
                    );
                }
                else if(selectedItem.getValue() instanceof Index){
                    selectedItem.getValue().setRunning(true);
                    TreeViewUtil.indexService.refreshIndexMeta(
                            TreeNavigator.getMetaConnect(selectedItem),
                            TreeNavigator.getCurrentDatabase(selectedItem),
                            selectedItem.getValue().getName(),
                            selectedItem::setValue,
                            () -> selectedItem.getValue().setRunning(false)
                    );
                }
                else if(selectedItem.getValue() instanceof Trigger){
                    selectedItem.getValue().setRunning(true);
                    TreeViewUtil.triggerService.refreshTriggerMeta(
                            TreeNavigator.getMetaConnect(selectedItem),
                            TreeNavigator.getCurrentDatabase(selectedItem),
                            selectedItem.getValue().getName(),
                            selectedItem::setValue,
                            () -> selectedItem.getValue().setRunning(false)
                    );
                }

            }else{
                selectedItem.getChildren().clear();
                selectedItem.setExpanded(false);
                selectedItem.setExpanded(true);
            }
        });

        createTableItem.setOnAction(event -> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            TabpaneUtil.addCustomCreateTableTab(selectedItem);
        });
        //设置默认数据库
        setDefaultDatabaseItem.setOnAction(event-> {
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            ConnectionService.ChangeDefaultDatabaseResult result =
                    TreeViewUtil.metadataService.changeDefaultDatabase(TreeNavigator.getMetaConnect(selectedItem),
                            TreeNavigator.getCurrentDatabase(selectedItem));
            if (result.isDisconnected()) {
                TreeNavigator.connectionDisconnected();
            } else if (result.getErrorCode() != null) {
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), "[" + result.getErrorCode() + "]" + result.getErrorMessage());
            }
            treeView.refresh();
        });



        //自定义treecell
        treeView.setCellFactory(param -> new CustomTreeCell());

        //右键内容及处理逻辑
        treeView.setOnContextMenuRequested(event -> {
            ObservableList<TreeItem<TreeData>> selectedItems = treeView.getSelectionModel().getSelectedItems();
            if (selectedItems == null || selectedItems.isEmpty()) {
                treeview_menu.hide();
                return;
            }
            if (selectedItems.size() > 1) {
                TreeItem<TreeData> firstSelected = selectedItems.get(0);
                TreeItem<TreeData> anchorParent = firstSelected == null ? null : firstSelected.getParent();
                Class<?> anchorType = firstSelected == null || firstSelected.getValue() == null
                        ? null
                        : firstSelected.getValue().getClass();
                if (anchorType == Database.class || anchorType == ObjectFolder.class) {
                    treeview_menu.hide();
                    return;
                }
                boolean allDatabaseObjects = true;
                for (TreeItem<TreeData> item : selectedItems) {
                    if (item == null
                            || item.getValue() == null
                            || !TreeNavigator.isDatabaseMenuObject(item.getValue())
                            || item.getParent() != anchorParent
                            || anchorType == null
                            || item.getValue().getClass() != anchorType) {
                        allDatabaseObjects = false;
                        break;
                    }
                }
                if (!allDatabaseObjects) {
                    treeview_menu.hide();
                    return;
                }
            }
            TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                // Rebuild menu each time and force-close any previous sub menu popup.
                if (ddlMenu.isShowing()) {
                    ddlMenu.hide();
                }
                if (importMenu.isShowing()) {
                    importMenu.hide();
                }
                if (exportDdlMenu.isShowing()) {
                    exportDdlMenu.hide();
                }
                if (exportMenu.isShowing()) {
                    exportMenu.hide();
                }
                treeview_menu.getItems().clear();
                //设置初始值
                expandFolderItem.setDisable(false);
                foldFolderItem.setDisable(false);
                disconnectItem.setDisable(false);
                reconnectItem.setDisable(false);
                connectItem.setDisable(false);
                moveItem.setDisable(false);
                modifyconnectItem.setDisable(false);
                setDefaultDatabaseItem.setDisable(false);
                TreeViewUtil.connectFolderInfoItem.setDisable(false);
                createDatabaseItem.setDisable(false);
                deleteItem.setDisable(false);
                renameItem.setDisable(false);
                truncateItem.setDisable(false);
                disableItem.setDisable(false);
                enableItem.setDisable(false);
                updateStatisticsItem.setDisable(false);
                sqlHisItem.setDisable(false);
                modifyToRawItem.setDisable(false);
                modifyToStandardItem.setDisable(false);
                createTableItem.setDisable(false);
                importDataItem.setDisable(false);
                importSqlScriptItem.setDisable(false);
                importDdlAndDataItem.setDisable(false);

                if (TreeNavigator.isMultiTableSelection(selectedItems)) {
                    boolean disableByReadOnlyOrSystem = TreeNavigator.isReadOnlyConnectionSelection(selectedItems);
                    boolean disableTruncateByExternal = false;
                    for (TreeItem<TreeData> item : selectedItems) {
                        if (TreeNavigator.isReadOnlyObject(item) || TreeNavigator.isSystemDatabaseObject(item)) {
                            disableByReadOnlyOrSystem = true;
                        }
                        if (isTableType(((Table) item.getValue()).getTableTypeCode(), "external")) {
                            disableTruncateByExternal = true;
                        }
                    }
                    updateStatisticsItem.setDisable(disableByReadOnlyOrSystem);
                    truncateItem.setDisable(disableByReadOnlyOrSystem || disableTruncateByExternal);
                    deleteItem.setDisable(disableByReadOnlyOrSystem);
                    treeview_menu.getItems().add(updateStatisticsItem);
                    treeview_menu.getItems().add(truncateItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(exportMenu);
                    treeview_menu.getItems().add(ddlMenu);
                    treeview_menu.show(treeView, event.getScreenX(), event.getScreenY());
                    return;
                }
                if (TreeNavigator.isMultiDeleteOnlySelection(selectedItems)) {
                    boolean disableDelete = TreeNavigator.isReadOnlyConnectionSelection(selectedItems);
                    for (TreeItem<TreeData> item : selectedItems) {
                        if (!TreeNavigator.canDeleteItem(item)) {
                            disableDelete = true;
                            break;
                        }
                    }
                    deleteItem.setDisable(disableDelete);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                    treeview_menu.show(treeView, event.getScreenX(), event.getScreenY());
                    return;
                }

                //如果是只读连接，禁用右键变更
                if(!(selectedItem.getValue() instanceof ConnectFolder)&&!(selectedItem.getValue() instanceof Connect)) {
                    Connect connectcheck = TreeNavigator.getMetaConnect(selectedItem);
                    if (connectcheck.getReadonly() != null && connectcheck.getReadonly()) {
                        deleteItem.setDisable(true);
                        renameItem.setDisable(true);
                        truncateItem.setDisable(true);
                        disableItem.setDisable(true);
                        enableItem.setDisable(true);
                        updateStatisticsItem.setDisable(true);
                        modifyToRawItem.setDisable(true);
                        modifyToStandardItem.setDisable(true);
                        createDatabaseItem.setDisable(true);
                        createTableItem.setDisable(true);
                        importDataItem.setDisable(true);
                        importSqlScriptItem.setDisable(true);
                        importDdlAndDataItem.setDisable(true);
                    }
                }
                if(selectedItem.getValue() instanceof Connect&&((Connect) selectedItem.getValue()).getReadonly()){
                    sqlHisItem.setDisable(true);
                }

                //如果是系统库，禁用变更操作
                if(selectedItem.getValue() instanceof Database||
                        selectedItem.getValue() instanceof ObjectFolder||
                        selectedItem.getValue() instanceof SysTable||
                        selectedItem.getValue() instanceof Table||
                        selectedItem.getValue() instanceof View||
                        selectedItem.getValue() instanceof Index||
                        selectedItem.getValue() instanceof Sequence||
                        selectedItem.getValue() instanceof Synonym||
                        selectedItem.getValue() instanceof Trigger||
                        selectedItem.getValue() instanceof Function||
                        selectedItem.getValue() instanceof Procedure||
                        selectedItem.getValue() instanceof DBPackage
                ) {
                    if (TreeNavigator.isSystemDatabaseObject(selectedItem)) {
                        truncateItem.setDisable(true);
                        deleteItem.setDisable(true);
                        renameItem.setDisable(true);
                        enableItem.setDisable(true);
                        disableItem.setDisable(true);
                        modifyToRawItem.setDisable(true);
                        modifyToStandardItem.setDisable(true);
                        importDataItem.setDisable(true);
                        importSqlScriptItem.setDisable(true);
                    }
                }

                //连接分类
                if(selectedItem.getValue() instanceof ConnectFolder){
                    treeview_menu.getItems().add(TreeViewUtil.connectFolderInfoItem);
                    if(selectedItem.getChildren().size()==0){
                        TreeViewUtil.connectFolderInfoItem.setDisable(true);
                    }
                    treeview_menu.getItems().add(createConnectItem);
                    //treeview_menu.getItems().add(addSystemLevel);
                    treeview_menu.getItems().add(disconnectFolder);
                    if (selectedItem.getParent().getChildren().size() <= 1) {
                        deleteItem.setDisable(true);
                    }
                    treeview_menu.getItems().add(expandFolderItem);
                    treeview_menu.getItems().add(foldFolderItem);
                    //根据节点状态设置右键展开和折叠是否disable
                    if (((ConnectFolder)selectedItem.getValue()).getExpand() == 1) {
                        expandFolderItem.setDisable(true);
                        foldFolderItem.setDisable(false);
                    } else {
                        expandFolderItem.setDisable(false);
                        foldFolderItem.setDisable(true);
                    }
                    treeview_menu.getItems().add(renameItem);
                    treeview_menu.getItems().add(deleteItem);
                }
                //连接
                else if(selectedItem.getValue() instanceof Connect){
                    Connect connect =(Connect)selectedItem.getValue();
                    if (!supportsInstanceAdmin(connect)) {
                        healthCheckItem.setDisable(true);
                        onlinelogItem.setDisable(true);
                        spaceManagerItem.setDisable(true);
                        onconfigItem.setDisable(true);
                        instanceStopItem.setDisable(true);
                    }else{
                        healthCheckItem.setDisable(false);
                        onlinelogItem.setDisable(false);
                        spaceManagerItem.setDisable(false);
                        onconfigItem.setDisable(false);
                        instanceStopItem.setDisable(false);
                    }
                    //treeview_menu.getItems().add(createConnectItem);
                    treeview_menu.getItems().add(sqlHisItem);
                    treeview_menu.getItems().add(separator1);
                    //treeview_menu.getItems().add(connectOpenFileItem);
                    treeview_menu.getItems().add(connectItem);
                    treeview_menu.getItems().add(reconnectItem);
                    treeview_menu.getItems().add(disconnectItem);
                    treeview_menu.getItems().add(copyconnectItem);
                    treeview_menu.getItems().add(moveItem);
                    treeview_menu.getItems().add(modifyconnectItem);
                    //treeview_menu.getItems().add(refreshItem);
                    treeview_menu.getItems().add(renameItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(separator2);
                    treeview_menu.getItems().add(TreeViewUtil.connectInfoItem);
                    treeview_menu.getItems().add(healthCheckItem);
                    treeview_menu.getItems().add(onlinelogItem);
                    treeview_menu.getItems().add(spaceManagerItem);
                    treeview_menu.getItems().add(onconfigItem);
                    treeview_menu.getItems().add(instanceStopItem);



                    try {
                        if (!(connect.getConn()==null || connect.getConn().isClosed())) {
                            modifyconnectItem.setDisable(true);
                            renameItem.setDisable(true);
                            deleteItem.setDisable(true);
                            connectItem.setDisable(true);
                        }else{
                            reconnectItem.setDisable(true);
                            disconnectItem.setDisable(true);
                        }
                    } catch (SQLException e) {
                        AppErrorHandler.handle(e);

                        throw new RuntimeException(e);
                    }

                    if (treeView.getRoot().getChildren().size() <= 1) {
                        moveItem.setDisable(true);
                    }
                }
                //数据库对象文件夹
                else if(selectedItem.getValue() instanceof DatabaseFolder){
                    if (!isOracleSchemaFolder(selectedItem)) {
                        treeview_menu.getItems().add(createDatabaseItem);
                        treeview_menu.getItems().add(importDdlAndDataItem);
                    }
                    treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                }
                else if(selectedItem.getValue() instanceof UserFolder) {
                    treeview_menu.getItems().add(addUserItem);
                    treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                }
                else if(selectedItem.getValue() instanceof User) {
                    treeview_menu.getItems().add(modifyUserItem);
                    treeview_menu.getItems().add(deleteItem);
                }
                //数据库
                else if(selectedItem.getValue() instanceof Database) {
                    if (isOracleSchemaNode(selectedItem)) {
                        treeview_menu.getItems().add(TreeViewUtil.databaseOpenFileItem);
                        treeview_menu.getItems().add(copyItem);
                        treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                        treeview_menu.getItems().add(importMenu);
                    } else {
                        treeview_menu.getItems().add(TreeViewUtil.databaseOpenFileItem);
                        treeview_menu.getItems().add(setDefaultDatabaseItem);
                        treeview_menu.getItems().add(updateStatisticsItem);
                        treeview_menu.getItems().add(copyItem);
                        treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                        treeview_menu.getItems().add(renameItem);
                        treeview_menu.getItems().add(deleteItem);
                        treeview_menu.getItems().add(importMenu);
                        treeview_menu.getItems().add(exportDdlAndDataItem);
                        treeview_menu.getItems().add(exportDdlMenu);
                    }
                }
                //对象文件夹
                else if(selectedItem.getValue() instanceof ObjectFolder) {
                    TreeDataLoader.ObjectFolderKind objectFolderKind = TreeDataLoader.getObjectFolderKind(selectedItem);
                    if (objectFolderKind == TreeDataLoader.ObjectFolderKind.TABLES){
                        treeview_menu.getItems().add(createTableItem);
                        treeview_menu.getItems().add(updateStatisticsItem);

                    }else if(objectFolderKind == TreeDataLoader.ObjectFolderKind.PROCEDURES) {
                        treeview_menu.getItems().add(updateStatisticsItem);
                    }
                    treeview_menu.getItems().add(TreeViewUtil.refreshItem);

                }
                //系统表
                else if(selectedItem.getValue() instanceof SysTable) {
                    if(!isTableType(((SysTable)selectedItem.getValue()).getTableTypeCode(), "view")){
                        treeview_menu.getItems().add(updateStatisticsItem);
                        treeview_menu.getItems().add(copyItem);
                        treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                    }
                }
                //表
                else if(selectedItem.getValue() instanceof Table) {
                    if(!isTableType(((Table)selectedItem.getValue()).getTableTypeCode(), "external")){
                        treeview_menu.getItems().add(updateStatisticsItem);
                        treeview_menu.getItems().add(modifyToRawItem);
                        treeview_menu.getItems().add(modifyToStandardItem);
                        treeview_menu.getItems().add(truncateItem);
                    }
                    if(isTableType(((Table)selectedItem.getValue()).getTableTypeCode(), "raw")){
                        modifyToRawItem.setDisable(true);
                    }else{
                        modifyToStandardItem.setDisable(true);
                    }
                    if(isTableType(((Table)selectedItem.getValue()).getTableTypeCode(), "external")){
                        importDataItem.setDisable(true);
                    }
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                    treeview_menu.getItems().add(renameItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(importDataItem);
                    treeview_menu.getItems().add(exportMenu);
                    treeview_menu.getItems().add(ddlMenu);

                }
                //视图
                else if(selectedItem.getValue() instanceof View) {
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                }
                //索引
                else if(selectedItem.getValue() instanceof Index) {
                    treeview_menu.getItems().add(enableItem);
                    treeview_menu.getItems().add(disableItem);
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                    treeview_menu.getItems().add(renameItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                    if(((Index)selectedItem.getValue()).getIsdisabled()) {
                        disableItem.setDisable(true);
                    }else{
                        enableItem.setDisable(true);
                    }
                    if(selectedItem.getValue().getName().charAt(0)==' '){
                        enableItem.setDisable(true);
                        disableItem.setDisable(true);
                        renameItem.setDisable(true);
                        deleteItem.setDisable(true);
                    }
                }
                //序列
                else if(selectedItem.getValue() instanceof Sequence) {
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(renameItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                }
                //同义词
                else if(selectedItem.getValue() instanceof Synonym) {
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                }
                //触发器
                else if(selectedItem.getValue() instanceof Trigger) {
                    treeview_menu.getItems().add(enableItem);
                    treeview_menu.getItems().add(disableItem);
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                    if(((Trigger)selectedItem.getValue()).isIsdisabled()) {
                        disableItem.setDisable(true);
                    }else{
                        enableItem.setDisable(true);
                    }
                }
                //函数
                else if(selectedItem.getValue() instanceof Function) {
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                }
                //存储过程
                else if(selectedItem.getValue() instanceof Procedure) {
                    treeview_menu.getItems().add(updateStatisticsItem);
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                }
                //包
                else if(selectedItem.getValue() instanceof DBPackage) {
                    //treeview_menu.getItems().add(packageDDLItem);
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(TreeViewUtil.refreshItem);
                    treeview_menu.getItems().add(deleteItem);
                    treeview_menu.getItems().add(ddlMenu);
                }
                else if(selectedItem.getValue() instanceof PackageFunction||selectedItem.getValue() instanceof PackageProcedure) {
                    treeview_menu.getItems().add(copyItem);
                    treeview_menu.getItems().add(ddlMenu);

                }

                // 树中右键框显示
                treeview_menu.show(treeView, event.getScreenX(), event.getScreenY());
            }
        });
    }

    private static boolean isTableType(String tableTypeCode, String expectedType) {
        if (tableTypeCode == null || expectedType == null) {
            return false;
        }
        return expectedType.equalsIgnoreCase(tableTypeCode.trim());
    }

    private static boolean isOracleSchemaFolder(TreeItem<TreeData> selectedItem) {
        return selectedItem != null
                && selectedItem.getValue() instanceof DatabaseFolder
                && isOracleTreeItem(selectedItem);
    }

    private static boolean isOracleSchemaNode(TreeItem<TreeData> selectedItem) {
        return selectedItem != null
                && selectedItem.getValue() instanceof Database
                && isOracleTreeItem(selectedItem);
    }

    private static boolean isOracleTreeItem(TreeItem<TreeData> selectedItem) {
        Connect connect = TreeNavigator.getMetaConnect(selectedItem);
        return connect != null && "ORACLE".equalsIgnoreCase(connect.getDbtype());
    }

    private static String resolveFallbackDatabase(Connect connect) {
        if (connect == null) {
            return null;
        }
        try {
            String fallback = resolveReconnectFallbackDatabase(connect);
            if (fallback != null && !fallback.isBlank()) {
                return fallback;
            }
        } catch (Exception ignored) {
        }
        return connect.getDatabase();
    }

    private static boolean supportsInstanceAdmin(Connect connect) {
        if (connect == null) {
            return false;
        }
        try {
            return resolvePlatformResolver().admin(connect).supportsAdminFeatures(connect);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static DatabasePlatformResolver resolvePlatformResolver() {
        try {
            return AppContext.get(DatabasePlatformResolver.class);
        } catch (IllegalStateException e) {
            return DatabasePlatforms.createDefault();
        }
    }

    private static String resolveReconnectFallbackDatabase(Connect connect) {
        if (connect == null) {
            return null;
        }
        try {
            var dialect = resolvePlatformResolver().requirePlatform(connect);
            return dialect.capability(ReconnectFallbackCapability.class)
                    .map(ReconnectFallbackCapability::reconnectFallbackDatabaseName)
                    .orElse(null);
        } catch (Exception ignored) {
        }
        return null;
    }
}
