package com.dbboys.util.tree;

import com.dbboys.app.AppContext;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.customnode.*;

import com.dbboys.api.ConnectionService;
import com.dbboys.service.DatabaseService;
import com.dbboys.service.FunctionService;
import com.dbboys.service.IndexService;
import com.dbboys.service.ObjectTypeService;
import com.dbboys.service.PackageService;
import com.dbboys.service.ProcedureService;
import com.dbboys.service.RecycleBinService;
import com.dbboys.service.SchedulerJobService;
import com.dbboys.service.QueueService;
import com.dbboys.service.SequenceService;
import com.dbboys.service.SynonymService;
import com.dbboys.service.TableService;
import com.dbboys.service.TriggerService;
import com.dbboys.service.UserService;
import com.dbboys.service.ViewService;
import com.dbboys.vo.*;
import javafx.scene.control.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;



public class TreeViewUtil {


    //public static ExecutorService executorService;
    public static ConnectionService connectionService;
    public static List<TreeItem<TreeData>> searchResults = new ArrayList<>();
    public static ConnectionService metadataService;
    public static DatabaseService databaseService;
    public static IndexService indexService;
    public static TableService tableService;
    public static TriggerService triggerService;
    public static ViewService viewService;
    public static SequenceService sequenceService;
    public static SynonymService synonymService;
    public static FunctionService functionService;
    public static ProcedureService procedureService;
    public static PackageService packageService;
    public static ObjectTypeService objectTypeService;
    public static QueueService queueService;
    public static SchedulerJobService schedulerJobService;
    public static RecycleBinService recycleBinService;
    public static UserService userService;
    public static CustomShortcutMenuItem refreshItem;
    public static CustomShortcutMenuItem databaseOpenFileItem;
    public static CustomShortcutMenuItem connectFolderInfoItem;
    public static CustomShortcutMenuItem connectInfoItem;
    public   static int currentIndex = -1;
    public static Thread testConnThread;
    public static String ddl="";
    static {
        AppContext.init();
        connectionService = AppContext.get(ConnectionService.class);
        metadataService = AppContext.get(ConnectionService.class);
        databaseService = AppContext.get(DatabaseService.class);
        indexService = AppContext.get(IndexService.class);
        tableService = AppContext.get(TableService.class);
        triggerService = AppContext.get(TriggerService.class);
        viewService = AppContext.get(ViewService.class);
        sequenceService = AppContext.get(SequenceService.class);
        synonymService = AppContext.get(SynonymService.class);
        functionService = AppContext.get(FunctionService.class);
        procedureService = AppContext.get(ProcedureService.class);
        packageService = AppContext.get(PackageService.class);
        objectTypeService = AppContext.get(ObjectTypeService.class);
        queueService = AppContext.get(QueueService.class);
        schedulerJobService = AppContext.get(SchedulerJobService.class);
        recycleBinService = AppContext.get(RecycleBinService.class);
        userService = AppContext.get(UserService.class);
    }



    public static void initDatabaseObjectsTreeview(TreeView<TreeData> treeView){
        TreeItem<TreeData> rootItem = new TreeItem<>();
        rootItem.setExpanded(true);
        treeView.setRoot(rootItem);
        treeView.getSelectionModel().select(rootItem);
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        treeView.setShowRoot(false);
        List<ConnectFolder> connectFolders = LocalDbRepository.getConnectFolders();
        List<Connect> connectLeafs = LocalDbRepository.getConnectLeafs();
        //添加分类节点
        for(ConnectFolder connectFolder : connectFolders){
            TreeItem<TreeData> connectFolderItem = createTreeItem(connectFolder);
            if(connectFolder.getExpand()==1){
                connectFolderItem.setExpanded(true);
            }else{
                connectFolderItem.setExpanded(false);
            }
            rootItem.getChildren().add(connectFolderItem);

            //每个分类添加子节点
            for(Connect connectLeaf : connectLeafs){
                if(connectLeaf.getParentId()==connectFolder.getId()){
                    TreeItem<TreeData> connectLeafItem = createTreeItem(connectLeaf);
                    connectFolderItem.getChildren().add(connectLeafItem);
                }
            }

        }

        TreeContextMenuHandler.setupContextMenu(treeView);
    }

    // --- Facade: TreeViewBuilder ---

    public static TreeItem<TreeData> createTreeItem(TreeData treeData) {
        return TreeViewBuilder.createTreeItem(treeData);
    }

    public static TreeItem<TreeData> createLeafTreeItem(TreeData treeData) {
        return TreeViewBuilder.createLeafTreeItem(treeData);
    }

    public static void createConnectFolder(TreeView<TreeData> treeView) {
        TreeViewBuilder.createConnectFolder(treeView);
    }

    public static void createConnectLeaf(TreeView<TreeData> treeView, TreeItem<TreeData> treeItem) {
        TreeViewBuilder.createConnectLeaf(treeView, treeItem);
    }

    public static void reorderTreeview(TreeView<TreeData> treeView, TreeItem<TreeData> treeItem) {
        TreeViewBuilder.reorderTreeview(treeView, treeItem);
    }

    // --- Facade: TreeDataLoader ---

    public static void treeItemAddChildrens(TreeItem<TreeData> treeItem) {
        TreeDataLoader.treeItemAddChildrens(treeItem);
    }

    // --- Facade: TreeCrudHandler ---

    public static void renameTreeItem(TreeView<TreeData> treeView) {
        TreeCrudHandler.renameTreeItem(treeView);
    }

    public static void deleteTreeItem(TreeView<TreeData> treeView) {
        TreeCrudHandler.deleteTreeItem(treeView);
    }

    public static Connect buildObjectConnect(TreeItem<TreeData> selectedItem, boolean useSysmaster) {
        return TreeCrudHandler.buildObjectConnect(selectedItem, useSysmaster);
    }

    public static void handleDdlAction(TreeView<TreeData> treeView, BiConsumer<TreeData, String> onSuccess) {
        TreeCrudHandler.handleDdlAction(treeView, onSuccess);
    }

    // --- Facade: TreeNavigator ---

    public static void connectionDisconnected() {
        TreeNavigator.connectionDisconnected();
    }

    public static TreeItem<TreeData> getMetaConnTreeItem(TreeItem<TreeData> treeItem) {
        return TreeNavigator.getMetaConnTreeItem(treeItem);
    }

    public static Connect getMetaConnect(TreeItem<TreeData> treeItem) {
        return TreeNavigator.getMetaConnect(treeItem);
    }

    public static Database getCurrentDatabase(TreeItem<TreeData> treeItem) {
        return TreeNavigator.getCurrentDatabase(treeItem);
    }

    public static void disconnectItem(TreeItem<TreeData> selectedItem) {
        TreeNavigator.disconnectItem(selectedItem);
    }

    public static void disconnectFolder(TreeItem<TreeData> selectedItem) {
        TreeNavigator.disconnectFolder(selectedItem);
    }

    public static void reconnectItem(TreeItem<TreeData> selectedItem) {
        TreeNavigator.reconnectItem(selectedItem);
    }

    public static void treeViewMoveConnectItem(TreeView<TreeData> treeView, TreeItem<TreeData> treeItem) {
        TreeNavigator.treeViewMoveConnectItem(treeView, treeItem);
    }

    public static void searchTree(TreeView<TreeData> treeView, String searchText, Button nextButton) {
        TreeNavigator.searchTree(treeView, searchText, nextButton);
    }

    public static void showCreateConnectDialog(TreeData treeDataParam, Boolean isCopy) {
        TreeNavigator.showCreateConnectDialog(treeDataParam, isCopy);
    }
}
