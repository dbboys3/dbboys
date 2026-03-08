package com.dbboys.util.tree;

import com.dbboys.app.AppState;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.ctrl.CreateConnectController;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.api.MetaObjectService;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.*;
import com.dbboys.vo.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.ResourceBundle;


public class TreeNavigator {
    private static final Logger log = LogManager.getLogger(TreeNavigator.class);

    public static void connectionDisconnected(){
        TreeItem<TreeData> treeItem=getMetaConnTreeItem(AppState.getDatabaseMetaTreeView().getSelectionModel().getSelectedItem());
        try {
            ((Connect)treeItem.getValue()).getConn().close();
        } catch (SQLException e) {
            AppErrorHandler.handle(e);
            ((Connect)treeItem.getValue()).setConn(null);
        }

        Platform.runLater(() -> {

            if (AlertUtil.CustomAlertConfirm(
                    I18n.t("common.error", "错误"),
                    I18n.t("metadata.alert.connection_lost", "数据库已断开连接，是否需要重新连接？"))) {
                treeItem.getChildren().clear();
                treeItem.setExpanded(false);
                treeItem.setExpanded(true);

            }
        });
    }

    public static TreeItem<TreeData> getMetaConnTreeItem(TreeItem<TreeData> treeItem){
        TreeItem<TreeData> retrunTreeItem=treeItem;
        if(retrunTreeItem.getValue() instanceof Connect){
            return  retrunTreeItem;
        }else if(retrunTreeItem.getValue() instanceof DatabaseFolder){
            return  retrunTreeItem.getParent();
        }else if(retrunTreeItem.getValue() instanceof UserFolder){
            return  retrunTreeItem.getParent();
        }else if(retrunTreeItem.getValue() instanceof User){
            return  retrunTreeItem.getParent().getParent();
        }else if(retrunTreeItem.getValue() instanceof Database){
            return  retrunTreeItem.getParent().getParent();
        }else if(retrunTreeItem.getValue() instanceof ObjectFolder){
            return  retrunTreeItem.getParent().getParent().getParent();
        }else if(
                retrunTreeItem.getValue() instanceof SysTable||
                        retrunTreeItem.getValue() instanceof Table||
                        retrunTreeItem.getValue() instanceof View||
                        retrunTreeItem.getValue() instanceof Index||
                        retrunTreeItem.getValue() instanceof Sequence||
                        retrunTreeItem.getValue() instanceof Synonym||
                        retrunTreeItem.getValue() instanceof Trigger||
                        retrunTreeItem.getValue() instanceof Function||
                        retrunTreeItem.getValue() instanceof Procedure||
                        retrunTreeItem.getValue() instanceof DBPackage
        ){
            return  retrunTreeItem.getParent().getParent().getParent().getParent();
        }
        else if(
                retrunTreeItem.getValue() instanceof PackageFunction||
                        retrunTreeItem.getValue() instanceof PackageProcedure

        ){
            return  retrunTreeItem.getParent().getParent().getParent().getParent().getParent();
        }
        else{
            return  retrunTreeItem;
        }
    }

    public static Connect getMetaConnect(TreeItem<TreeData> treeItem){
        return (Connect)getMetaConnTreeItem(treeItem).getValue();
    }


    public static Database getCurrentDatabase(TreeItem<TreeData> treeItem){
        TreeItem<TreeData> retrunTreeItem=treeItem;
        if(retrunTreeItem.getValue() instanceof Database){
            return  (Database) retrunTreeItem.getValue();
        }else if(retrunTreeItem.getValue() instanceof ObjectFolder){
            return  (Database) retrunTreeItem.getParent().getValue();
        }else if(retrunTreeItem.getValue() instanceof UserFolder||retrunTreeItem.getValue() instanceof User){
            Database db=new Database("sysuser");
            db.setDbLocale("en_US.819");
            return db;
        }else if(
                retrunTreeItem.getValue() instanceof SysTable||
                        retrunTreeItem.getValue() instanceof Table||
                        retrunTreeItem.getValue() instanceof View||
                        retrunTreeItem.getValue() instanceof Index||
                        retrunTreeItem.getValue() instanceof Sequence||
                        retrunTreeItem.getValue() instanceof Synonym||
                        retrunTreeItem.getValue() instanceof Trigger||
                        retrunTreeItem.getValue() instanceof Function||
                        retrunTreeItem.getValue() instanceof Procedure||
                        retrunTreeItem.getValue() instanceof DBPackage
        ){
            return  (Database) retrunTreeItem.getParent().getParent().getValue();
        }else if(
                retrunTreeItem.getValue() instanceof PackageFunction|| retrunTreeItem.getValue() instanceof PackageProcedure
        ){
            return  (Database) retrunTreeItem.getParent().getParent().getParent().getValue();
        }
        return  (Database) retrunTreeItem.getValue();

    }

    public static void disconnectItem(TreeItem<TreeData> selectedItem) {
        Connect connect =(Connect)selectedItem.getValue();
        try {
            //关闭主连接
            if(connect.getConn()!=null&&!connect.getConn().isClosed()) {
                connect.getConn().close();
                for(Tab tab :AppState.getSqlTabPane().getTabs()){
                    if(tab instanceof CustomSqlTab) {
                        if (selectedItem.getValue().getName().equals(((CustomSqlTab) tab).sqlTabController.sqlConnect.getName())) {
                            ((CustomSqlTab) tab).sqlTabController.closeConn();
                        }
                    }

                }
            }
            CustomInstanceTab needToRemove=null;
            for(Tab tab :AppState.getSqlTabPane().getTabs()) {
                if (tab instanceof CustomInstanceTab) {
                    if (("[instance check]"+selectedItem.getValue().getName()).equals( ((CustomInstanceTab) tab).getTitle())) {
                        needToRemove=(CustomInstanceTab)tab;
                    }
                }
            }
            if(needToRemove!=null)AppState.getSqlTabPane().getTabs().remove(needToRemove);
        } catch (SQLException e) {
            AppErrorHandler.handle(e);
            //new CustomAlert("错误",e.toString());
        }
        selectedItem.setExpanded(false);
        if(selectedItem.getChildren().size()>0) {
            selectedItem.getChildren().clear();
        }



    }


    public static void disconnectFolder(TreeItem<TreeData> selectedItem) {
        for (TreeItem<TreeData> t : selectedItem.getChildren()) {
            disconnectItem(t);
        }
    }

    public static void reconnectItem(TreeItem<TreeData> selectedItem) {
        TreeItem<TreeData> connTreeItem = getMetaConnTreeItem(selectedItem);
        disconnectItem(connTreeItem);
        connTreeItem.setExpanded(true);
    }

    public static void treeViewMoveConnectItem(TreeView<TreeData> treeView, TreeItem<TreeData> treeItem) {
        for (TreeItem<TreeData> ti : treeView.getRoot().getChildren()) {
            if (((ConnectFolder)ti.getValue()).getId() == ((Connect)treeItem.getValue()).getParentId()) {
                ti.getChildren().add(treeItem);
            }
        }
        treeView.getSelectionModel().clearSelection();
        treeView.getSelectionModel().select(treeItem);
        TreeViewBuilder.reorderTreeview(treeView, treeItem);
    }

    public static void searchTree(TreeView<TreeData> treeView, String searchText, Button nextButton) {
        nextButton.setOnAction(e -> findNext(treeView));
        nextButton.setDisable(true);
        //String searchText = searchField.getText().trim();
        if (searchText.isEmpty()) {
            return;
        }

        // 清空之前的搜索结果
        TreeViewUtil.searchResults.clear();
        TreeViewUtil.currentIndex = -1;

        // 执行搜索
        TreeItem<TreeData> root = treeView.getRoot();
        if (root != null) {
            traverseNode(root, searchText);
        }

        // 处理搜索结果
        if (TreeViewUtil.searchResults.isEmpty()) {
            NotificationUtil.showMainNotification(
                    I18n.t("metadata.search.no_match", "未搜索到匹配项，请确保需查找的对象已加载！"));
        } else {
            findNext(treeView);
            // 更新按钮状态
            if(TreeViewUtil.searchResults.size()>1){
                nextButton.setDisable(false);
            }
        }
    }

    public static void findNext(TreeView<TreeData> treeView) {
        if (TreeViewUtil.searchResults.isEmpty()) {
            return;
        }

        // 更新当前索引
        TreeViewUtil.currentIndex = (TreeViewUtil.currentIndex + 1) % TreeViewUtil.searchResults.size();

        // 获取当前匹配项
        TreeItem<TreeData> currentItem = TreeViewUtil.searchResults.get(TreeViewUtil.currentIndex);

        // 滚动到并选中当前项
        treeView.getSelectionModel().clearSelection();
        treeView.getSelectionModel().select(currentItem);
        treeView.scrollTo(treeView.getRow(currentItem));

        // 如果是最后一个，提示用户下一次将从头开始
        if (TreeViewUtil.currentIndex == TreeViewUtil.searchResults.size() - 1) {
            if(TreeViewUtil.currentIndex==0){
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.search.only_one", "仅匹配当前一个！"));
            }else{
                NotificationUtil.showMainNotification(
                        I18n.t("metadata.search.wrap", "搜索已到最后，下一个从头开始搜索！"));
            }
        }
    }

    public static void traverseNode(TreeItem<TreeData> node, String searchText) {

        //rootitem未设置值可能为null
        if(node.getValue()!=null&&node.getValue().getName().toLowerCase().contains(searchText.toLowerCase())){
            TreeViewUtil.searchResults.add(node);
        }
        // 如果节点未展开，则不继续遍历其子节点
        if (!node.isExpanded()) {
            if(node.getChildren().size()>0){
                //node.setExpanded(true);
            }else{
                return;
            }
        }

        // 如果是叶子节点，检查其值

        // 不是叶子节点，则递归遍历所有子节点

        for (TreeItem<TreeData> child : node.getChildren()) {
            traverseNode(child, searchText);
        }

    }

    //弹出创建连接对话框（使用 CustomWindowFrameUtil 框架）
    public static void showCreateConnectDialog(TreeData treeDataParam, Boolean isCopy) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle("com.dbboys.i18n.messages", I18n.getLocale());
            FXMLLoader loader = new FXMLLoader(CreateConnectController.class.getResource("/com/dbboys/fxml/CreateConnect.fxml"), bundle);
            DialogPane dialogPane = loader.load();
            CreateConnectController controller = loader.getController();

            Stage stage = new Stage(StageStyle.UNDECORATED);
            stage.initModality(Modality.APPLICATION_MODAL);
            if (AppState.getWindow() != null) stage.initOwner(AppState.getWindow());
            stage.getIcons().add(new Image(IconPaths.MAIN_LOGO));

            dialogPane.setHeader(null);
            int dialogW = 520;
            int dialogH = 520;
            int titleBarHeight = 36;
            int contentH = dialogH - titleBarHeight;

            dialogPane.setMinSize(dialogW, contentH);
            dialogPane.setPrefSize(dialogW, contentH);
            dialogPane.setMaxSize(dialogW, contentH);

            SimpleStringProperty titleProp = new SimpleStringProperty(I18n.t("createconnect.dialog.title"));
            CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.create(stage, titleProp, dialogPane, dialogW, dialogH, null, false);
            stage.setScene(frame.scene);
            stage.setResizable(false);
            stage.sizeToScene();

            controller.init(treeDataParam, isCopy, stage);
            frame.closeButton.setOnAction(e -> stage.close());

            TextField connectNameTextField = (TextField) loader.getNamespace().get("connectNameTextField");
            stage.setOnShown(event -> connectNameTextField.requestFocus());

            javafx.stage.Window owner = AppState.getWindow();
            if (owner instanceof Stage ownerStage) {
                stage.setX(ownerStage.getX() + (ownerStage.getWidth() - dialogW) / 2);
                stage.setY(ownerStage.getY() + (ownerStage.getHeight() - dialogH) / 2);
            }

            stage.showAndWait();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean canCopyItem(TreeItem<TreeData> selectedItem) {
        TreeData treeData = selectedItem.getValue();
        return selectedItem.isLeaf() || treeData instanceof DBPackage || treeData instanceof Database;
    }

    public static boolean canRefreshItem(TreeItem<TreeData> selectedItem) {
        TreeData treeData = selectedItem.getValue();
        if (treeData instanceof DatabaseFolder || treeData instanceof UserFolder || treeData instanceof Database || treeData instanceof ObjectFolder || treeData instanceof Table || treeData instanceof Index || treeData instanceof Trigger || treeData instanceof DBPackage) {
            return true;
        }
        if (treeData instanceof SysTable) {
            return !"view".equals(((SysTable) treeData).getTableTypeCode());
        }
        return false;
    }

    public static boolean canRenameItem(TreeItem<TreeData> selectedItem) {
        TreeData treeData = selectedItem.getValue();
        if (!(treeData instanceof ConnectFolder
                || treeData instanceof Connect
                || treeData instanceof Database
                || treeData instanceof Table
                || treeData instanceof Index
                || treeData instanceof Sequence)) {
            return false;
        }
        if (treeData instanceof ConnectFolder) {
            return selectedItem.getParent() != null && selectedItem.getParent().getChildren().size() > 1;
        }
        if (treeData instanceof Connect) {
            try {
                Connect connect = (Connect) treeData;
                return connect.getConn() == null || connect.getConn().isClosed();
            } catch (SQLException e) {
                AppErrorHandler.handle(e);
                return false;
            }
        }
        if (isReadOnlyObject(selectedItem) || isSystemDatabaseObject(selectedItem)) {
            return false;
        }
        if (treeData instanceof Index && !treeData.getName().isEmpty() && treeData.getName().charAt(0) == ' ') {
            return false;
        }
        return true;
    }

    public static boolean canDeleteItem(TreeItem<TreeData> selectedItem) {
        TreeData treeData = selectedItem.getValue();
        if (!(treeData instanceof ConnectFolder
                || treeData instanceof Connect
                || treeData instanceof Database
                || treeData instanceof Table
                || treeData instanceof View
                || treeData instanceof Index
                || treeData instanceof Sequence
                || treeData instanceof Synonym
                || treeData instanceof Trigger
                || treeData instanceof Function
                || treeData instanceof Procedure
                || treeData instanceof DBPackage
                || treeData instanceof User)) {
            return false;
        }
        if (treeData instanceof ConnectFolder) {
            return selectedItem.getParent() != null && selectedItem.getParent().getChildren().size() > 1;
        }
        if (treeData instanceof Connect) {
            try {
                Connect connect = (Connect) treeData;
                return connect.getConn() == null || connect.getConn().isClosed();
            } catch (SQLException e) {
                AppErrorHandler.handle(e);
                return false;
            }
        }
        if (isReadOnlyObject(selectedItem) || isSystemDatabaseObject(selectedItem)) {
            return false;
        }
        if (treeData instanceof Index && !treeData.getName().isEmpty() && treeData.getName().charAt(0) == ' ') {
            return false;
        }
        return true;
    }

    public static boolean isReadOnlyObject(TreeItem<TreeData> selectedItem) {
        TreeData treeData = selectedItem.getValue();
        if (treeData instanceof ConnectFolder || treeData instanceof Connect) {
            return false;
        }
        Connect connect = getMetaConnect(selectedItem);
        return connect.getReadonly() != null && connect.getReadonly();
    }

    public static boolean isSystemDatabaseObject(TreeItem<TreeData> selectedItem) {
        TreeData treeData = selectedItem.getValue();
        if (!(treeData instanceof Database
                || treeData instanceof ObjectFolder
                || treeData instanceof SysTable
                || treeData instanceof Table
                || treeData instanceof View
                || treeData instanceof Index
                || treeData instanceof Sequence
                || treeData instanceof Synonym
                || treeData instanceof Trigger
                || treeData instanceof Function
                || treeData instanceof Procedure
                || treeData instanceof DBPackage)) {
            return false;
        }
        String database = getCurrentDatabase(selectedItem).getName();
        return database.equals("sysmaster")
                || database.equals("sysuser")
                || database.equals("sysadmin")
                || database.equals("sysutils")
                || database.equals("sysha")
                || database.equals("syscdr")
                || database.equals("syscdcv1")
                || database.equals("gbasedbt")
                || database.equals("sys");
    }

    public static boolean isDatabaseMenuObject(TreeData treeData) {
        return treeData instanceof DatabaseFolder
                || treeData instanceof UserFolder
                || treeData instanceof User
                || treeData instanceof Database
                || treeData instanceof ObjectFolder
                || treeData instanceof SysTable
                || treeData instanceof Table
                || treeData instanceof View
                || treeData instanceof Index
                || treeData instanceof Sequence
                || treeData instanceof Synonym
                || treeData instanceof Trigger
                || treeData instanceof Function
                || treeData instanceof Procedure
                || treeData instanceof DBPackage
                || treeData instanceof PackageFunction
                || treeData instanceof PackageProcedure;
    }

    public static boolean isMultiTableSelection(List<TreeItem<TreeData>> selectedItems) {
        if (selectedItems == null || selectedItems.size() <= 1) {
            return false;
        }
        for (TreeItem<TreeData> item : selectedItems) {
            if (item == null || !(item.getValue() instanceof Table)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isMultiDeleteOnlySelection(List<TreeItem<TreeData>> selectedItems) {
        if (selectedItems == null || selectedItems.size() <= 1 || isMultiTableSelection(selectedItems)) {
            return false;
        }
        for (TreeItem<TreeData> item : selectedItems) {
            if (item == null || item.getValue() == null) {
                return false;
            }
            TreeData value = item.getValue();
            if (!(value instanceof View
                    || value instanceof Index
                    || value instanceof Sequence
                    || value instanceof Synonym
                    || value instanceof Trigger
                    || value instanceof Function
                    || value instanceof Procedure
                    || value instanceof DBPackage
                    || value instanceof User)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isReadOnlyConnectionSelection(List<TreeItem<TreeData>> selectedItems) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return false;
        }
        for (TreeItem<TreeData> item : selectedItems) {
            if (item == null || item.getValue() == null) {
                continue;
            }
            if (item.getValue() instanceof ConnectFolder || item.getValue() instanceof Connect) {
                continue;
            }
            Connect connect = getMetaConnect(item);
            if (connect.getReadonly() != null && connect.getReadonly()) {
                return true;
            }
        }
        return false;
    }

    public static MetaObjectService getDeleteService(TreeData treeData) {
        if (treeData instanceof View) {
            return TreeViewUtil.viewService;
        }
        if (treeData instanceof Index) {
            return TreeViewUtil.indexService;
        }
        if (treeData instanceof Sequence) {
            return TreeViewUtil.sequenceService;
        }
        if (treeData instanceof Synonym) {
            return TreeViewUtil.synonymService;
        }
        if (treeData instanceof Trigger) {
            return TreeViewUtil.triggerService;
        }
        if (treeData instanceof Function) {
            return TreeViewUtil.functionService;
        }
        if (treeData instanceof Procedure) {
            return TreeViewUtil.procedureService;
        }
        if (treeData instanceof DBPackage) {
            return TreeViewUtil.packageService;
        }
        if (treeData instanceof User) {
            return TreeViewUtil.databaseService;
        }
        return null;
    }

    public static String getDeleteObjectType(TreeData treeData) {
        if (treeData instanceof View) {
            return "view";
        }
        if (treeData instanceof Index) {
            return "index";
        }
        if (treeData instanceof Sequence) {
            return "sequence";
        }
        if (treeData instanceof Synonym) {
            return "synonym";
        }
        if (treeData instanceof Trigger) {
            return "trigger";
        }
        if (treeData instanceof Function) {
            return "function";
        }
        if (treeData instanceof Procedure) {
            return "procedure";
        }
        if (treeData instanceof DBPackage) {
            return "package";
        }
        if (treeData instanceof User) {
            return "user";
        }
        return "object";
    }
}
