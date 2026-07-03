package com.dbboys.infra.util;

import com.dbboys.core.ConnectionService;
import com.dbboys.app.AppContext;
import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.ui.component.*;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.ConnectFolder;
import com.dbboys.model.TreeData;
import com.dbboys.ui.controller.tree.TreeCrudHandler;
import com.dbboys.ui.controller.tree.TreeNavigator;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.input.MouseButton;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


public class TabpaneUtil {
    private static final String TAB_KEY_CONNECTS_INFO = "connectsInfo:";
    private static final String TAB_KEY_INSTANCE = "instance:";
    private static final String TAB_KEY_TABLE = "table:";
    private static final String TAB_KEY_LOCK_SESSION = "lockSession:";

    private TabpaneUtil() {
    }

    private static TabPane tabPane() {
        return AppState.getSqlTabPane();
    }

    public static void addCustomSqlTab(Connect connect) {
        String tabName = "script";
        // Scan scripts/ folder for existing script files, pick next available number
        File scriptsDir = new File("scripts");
        if (!scriptsDir.isDirectory()) {
            scriptsDir.mkdirs();
        }
        File[] existingFiles = scriptsDir.listFiles((dir, name) -> name.matches("script\\d+\\.sql"));
        for (int i = 1; i <= 9999999; i++) {
            boolean isContained = false;
            // Check open tabs
            for (Tab tab : tabPane().getTabs()) {
                if (((CustomTab) tab).getTitle().replaceAll("\\*", "").equals("script" + i + ".sql")) {
                    isContained = true;
                    break;
                }
            }
            // Check scripts/ folder
            if (!isContained && existingFiles != null) {
                String candidate = "script" + i + ".sql";
                for (File f : existingFiles) {
                    if (f.getName().equals(candidate)) {
                        isContained = true;
                        break;
                    }
                }
            }
            if (!isContained) {
                tabName = "script" + i + ".sql";
                break;
            }
        }
        CustomSqlTab newtab = new CustomSqlTab(tabName);
        newtab.filePath = Paths.get("scripts", tabName).toString();
        //newtab.setContent(new CustomSqlTab().getSqlTab());
        tabPane().getTabs().add(newtab);
        tabPane().getSelectionModel().select(newtab);
        refreshSqlTabHeaderLayout();
        //设置一个id，用于生效分隔条CSS
        //newtab.data_manager_splitpane.setId("runSplitPane");

        //双击新建sql面板，connect是null
        if (connect != null) {
            newtab.sqlTabController.sqlConnectChoiceBox.setValue(connect);
        }
    }


    private static void refreshSqlTabHeaderLayout() {
        TabPane sqlTabPane = tabPane();
        if (sqlTabPane == null) {
            return;
        }
        Platform.runLater(() -> {
            sqlTabPane.applyCss();
            sqlTabPane.requestLayout();
            if (sqlTabPane.getParent() != null) {
                sqlTabPane.getParent().requestLayout();
            }
        });
    }

    public static void isRefreshConnectList() {
        for (Tab tab : tabPane().getTabs()) {
            if (tab instanceof CustomSqlTab) {
                ((CustomSqlTab) (tab)).sqlTabController.initializeConnectList();
            }
        }
    }


    public static void addConnectsInfoTab(ConnectFolder connect) {
        String tabKey = TAB_KEY_CONNECTS_INFO + connect.getName();
        Tab existing = findTabByUserData(tabKey);
        if (existing != null) {
            tabPane().getSelectionModel().select(existing);
            return;
        }

        CustomTab newtab = new CustomTab("");
        newtab.setUserData(tabKey);
        newtab.textProperty().bind(Bindings.createStringBinding(
                () -> I18n.t("tabpane.connects_info_tab.title", "[connectsInfo]%s").formatted(connect.getName()),
                I18n.localeProperty()
        ));
        tabPane().getTabs().add(newtab);
        tabPane().getSelectionModel().select(newtab);

        // 分类实例信息列表
        TableView<TreeData> instanceInfoTableView = new CustomInstanceInfoTableView();

        newtab.setContent(instanceInfoTableView);
        //如果分类下有连接，显示所有连接信�?
        ObservableList<TreeData> data = FXCollections.observableArrayList(new ArrayList<>(LocalDbRepository.getFolderConnect(connect)));
        // 设置数据
        instanceInfoTableView.setItems(data);
        //默认选中第一行，保证右键事件当前行不为null
        instanceInfoTableView.getSelectionModel().selectFirst();
        //按名称排�?
        //name.setSortType(TableColumn.SortType.ASCENDING);
        //instance_info_tableview.getSortOrder().add(name);  // 将列添加到排序列表中
        instanceInfoTableView.sort();
    }

    public static void addCustomInstanceTab(Connect connect, int tabNum) {
        Platform.runLater(() -> {
            refreshOpenConnectsInfoTables(connect);
            String tabKey = TAB_KEY_INSTANCE + connect.getName();
            Tab existing = findTabByUserData(tabKey);
            if (existing instanceof CustomInstanceTab) {
                tabPane().getSelectionModel().select(existing);
                ((CustomInstanceTab) existing).mainTabPane.getSelectionModel().select(tabNum);
                return;
            }
            CustomInstanceTab newtab = new CustomInstanceTab(connect, tabNum);
            newtab.setUserData(tabKey);
            tabPane().getTabs().add(newtab);
            tabPane().getSelectionModel().select(newtab);
        });
    }

    public static void addCustomTableInfoTab(TreeItem<TreeData> treeItem) {
        TreeData treeData = treeItem.getValue();
        Platform.runLater(() -> {
            String tableKey = TAB_KEY_TABLE
                    + treeItem.getParent().getParent().getParent().getParent().getValue().getName()
                    + "."
                    + treeItem.getParent().getParent().getValue().getName()
                    + "."
                    + treeData.getName();
            Tab existing = findTabByUserData(tableKey);
            if (existing != null) {
                tabPane().getSelectionModel().select(existing);
                return;
            }
            CustomTableInfoTab newtab = new CustomTableInfoTab(treeItem);
            newtab.setUserData(tableKey);
            tabPane().getTabs().add(newtab);
            tabPane().getSelectionModel().select(newtab);
        });
    }

    public static void addCustomCreateTableTab(TreeItem<TreeData> treeItem) {
        String newTableName = nextNewTableName(treeItem);
        System.out.print("newTableName:"+newTableName);

        Platform.runLater(() -> {
            String tableKey = TAB_KEY_TABLE
                    + treeItem.getParent().getParent().getParent().getValue().getName()
                    + "."
                    + treeItem.getParent().getValue().getName()
                    + "."
                    + newTableName;
            Tab existing = findTabByUserData(tableKey);
            if (existing != null) {
                tabPane().getSelectionModel().select(existing);
                return;
            }
            CustomTableInfoTab newtab = new CustomTableInfoTab(treeItem, newTableName);
            newtab.setUserData(tableKey);
            tabPane().getTabs().add(newtab);
            tabPane().getSelectionModel().select(newtab);
        });
    }

    

    

    private static String nextNewTableName(TreeItem<TreeData> tablesFolderItem) {
        String prefix = "new_table_";
        int idx = 1;
        List<String> existingNames = new ArrayList<>();
        String connectName = "";
        String databaseName = "";
        if (tablesFolderItem != null && tablesFolderItem.getParent() != null && tablesFolderItem.getParent().getParent() != null) {
            databaseName = tablesFolderItem.getParent().getValue().getName();
            connectName = tablesFolderItem.getParent().getParent().getParent().getValue().getName();
        }
        for (TreeItem<TreeData> child : tablesFolderItem.getChildren()) {
            if (child != null && child.getValue() != null && child.getValue().getName() != null) {
                existingNames.add(child.getValue().getName().toLowerCase());
            }
        }
        while (true) {
            String candidate = prefix + idx;
            String candidateLower = candidate.toLowerCase();
            boolean existsInTree = existingNames.contains(candidateLower);
            String tableTabKey = TAB_KEY_TABLE + connectName + "." + databaseName + "." + candidate;
            System.out.println("tableTabKey:"+tableTabKey);
            boolean existsInOpenTabs = findTabByUserData(tableTabKey) != null;
                        System.out.println("existsInOpenTabs:"+existsInOpenTabs);

            if (!existsInTree && !existsInOpenTabs) {
                return candidate;
            }
            idx++;
        }
    }




    public static void addCustomMarkdownTab(File file, boolean modifiable) {
        if (!file.exists()) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "错误"),
                    I18n.t("tabpane.error.file_not_exists", "文件不存在！")
            );
            return;
        }
        Platform.runLater(() -> {
            boolean isContained = false;
            for (Tab tab : tabPane().getTabs()) {
                if (tab instanceof CustomMarkdownTab) {
                    if (((CustomMarkdownTab) tab).filePath.equals(file.getAbsolutePath())) {
                        isContained = true;
                        tabPane().getSelectionModel().select(tab);
                        break;
                    }
                }
            }
            if (!isContained) {

                CustomMarkdownTab newtab = new CustomMarkdownTab(file, modifiable);
                tabPane().getTabs().add(newtab);
                tabPane().getSelectionModel().select(newtab);
            }
        });
    }

    public static CustomMarkdownTab findCustomMarkdownTab(Path path) {
        for (Tab tab : tabPane().getTabs()) {
            if (tab instanceof CustomMarkdownTab) {
                CustomMarkdownTab markdownTab = (CustomMarkdownTab) tab;
                if (markdownTab.filePath.startsWith(path.toString())) {
                    return markdownTab;
                }
            }
        }
        return null;
    }

    public static void renameCustomMarkdownTab(Path oldPath, Path newPath) {
        CustomMarkdownTab tab = findCustomMarkdownTab(oldPath);
        if (tab != null) {
            tab.filePath = newPath.toString();
            if (tab.getTitle().startsWith("*")) {
                tab.requestSave();
            }
            tab.setTitle(newPath.getFileName().toString());
        }
    }


    public static void removeCustomMarkdownTab(Path path) {
        CustomMarkdownTab tab = findCustomMarkdownTab(path);
        if (tab != null) {
            tabPane().getTabs().remove(tab);
        }
    }

    public static void closeOtherTabs(Tab currentTab) {
        boolean canClose = true;
        for (Tab tab : tabPane().getTabs()) {
            if (tab.getText().startsWith("*") && !tab.equals(currentTab)) {
                if (!AlertUtil.CustomAlertConfirm(
                        I18n.t("common.notice", "提示"),
                        I18n.t("tabpane.confirm.close_unsaved", "部分打开的文件未保存，确定要关闭吗？")
                )) {
                    canClose = false;
                }
                break;
            }
        }

        //如果回答确认，执行关闭流�?
        if (canClose) {
            List<Tab> tabsToRemove = new ArrayList<>();
            for (Tab tab : tabPane().getTabs()) {
                if (tab != currentTab) {
                    tabsToRemove.add(tab);
                    if (tab instanceof CustomSqlTab) {
                        ((CustomSqlTab) tab).sqlTabController.closeConn();
                    }
                }
            }
            tabPane().getTabs().removeAll(tabsToRemove);
        }
    }


    public static void closeAllTabs() {
        boolean canClose = true;
        TabPane currentTabPane = AppState.getSqlTabPane();
        for (Tab tab : currentTabPane.getTabs()) {
            if (tab.getText().startsWith("*")) {
                if (!AlertUtil.CustomAlertConfirm(
                        I18n.t("common.notice", "提示"),
                        I18n.t("tabpane.confirm.close_unsaved", "部分打开的文件未保存，确定要关闭吗？")
                )) {
                    canClose = false;
                }
                break;
            }
        }

        //如果回答确认，执行关闭流�?
        if (canClose) {
            for (Tab tab : currentTabPane.getTabs()) {
                if (tab instanceof CustomSqlTab) {
                    ((CustomSqlTab) tab).sqlTabController.closeConn();
                }
            }

            currentTabPane.getTabs().removeAll(currentTabPane.getTabs());

            //tabpane增加一个监听确保双击增加页面有�?
            AppState.getSqlTabPane().setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    TabpaneUtil.addCustomSqlTab(null);
                }
            });

        }
    }

    private static Tab findTabByUserData(String userData) {
        for (Tab tab : tabPane().getTabs()) {
            if (userData.equals(tab.getUserData())) {
                return tab;
            }
        }
        return null;
    }

    public static void addCustomLockSessionTab(TreeItem<TreeData> treeItem) {
        if (treeItem == null || treeItem.getValue() == null) {
            return;
        }
        Platform.runLater(() -> {
            Connect connect = TreeCrudHandler.buildObjectConnect(treeItem, false);
            Connect metaConnect = TreeNavigator.getMetaConnect(treeItem);
            String connectName = metaConnect == null ? "" : metaConnect.getName();
            String databaseName = TreeNavigator.getCurrentDatabase(treeItem).getName();
            String tableName = treeItem.getValue().getName();
            String tabTitle = "[lock]" + connectName + "." + databaseName + "." + tableName;
            String tabKey = TAB_KEY_LOCK_SESSION + tabTitle;
            Tab existing = findTabByUserData(tabKey);
            if (existing != null) {
                tabPane().getSelectionModel().select(existing);
                return;
            }
            CustomLockSessionTab newtab = new CustomLockSessionTab(connect, tabTitle, databaseName, tableName);
            newtab.setUserData(tabKey);
            tabPane().getTabs().add(newtab);
            tabPane().getSelectionModel().select(newtab);
        });
    }

    private static void refreshOpenConnectsInfoTables(Connect updatedConnect) {
        for (Tab tab : tabPane().getTabs()) {
            if (!(tab.getContent() instanceof CustomInstanceInfoTableView tableView)) {
                continue;
            }
            for (Object item : tableView.getItems()) {
                if (!(item instanceof Connect rowConnect) || !isSameConnect(rowConnect, updatedConnect)) {
                    continue;
                }
                rowConnect.setIp(updatedConnect.getIp());
                rowConnect.setPort(updatedConnect.getPort());
                rowConnect.setInfo(updatedConnect.getInfo());
                rowConnect.setDbversion(updatedConnect.getDbversion());
                tableView.refresh();
                break;
            }
        }
    }

    private static boolean isSameConnect(Connect rowConnect, Connect updatedConnect) {
        if (rowConnect == null || updatedConnect == null) {
            return false;
        }
        if (rowConnect.getId() > 0 && updatedConnect.getId() > 0) {
            return rowConnect.getId() == updatedConnect.getId();
        }
        return rowConnect.getName() != null && rowConnect.getName().equals(updatedConnect.getName());
    }
}
