package com.dbboys.util.tree;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconPaths;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.vo.*;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import java.util.Comparator;


public class TreeViewBuilder {

    //创建一个TreeItem,重构ifLeaf显示箭头
    public static TreeItem<TreeData> createTreeItem(TreeData treeData) {
        TreeItem<TreeData> treeItem = new TreeItem<>(treeData){
            @Override
            public boolean isLeaf() {
                return false;
            }
        };
        if(!(treeData instanceof ConnectFolder)){
            treeItem.expandedProperty().addListener((obs, wasExpanded, isNowExpanded) -> {
                if (isNowExpanded&& treeItem.getChildren().isEmpty()){
                    AppState.getDatabaseMetaTreeView().getSelectionModel().clearSelection();;
                    AppState.getDatabaseMetaTreeView().getSelectionModel().select(treeItem);
                    TreeDataLoader.treeItemAddChildrens(treeItem);
                };
            });
        }
        return treeItem;
    }


    //创建一个TreeItem,重构ifLeaf显示箭头
    public static TreeItem<TreeData> createLeafTreeItem(TreeData treeData) {
        TreeItem<TreeData> treeItem = new TreeItem<>(treeData){
            @Override
            public boolean isLeaf() {
                return true;
            }
        };
        return treeItem;

    }

    //创建连接分类文件夹
    public static void createConnectFolder(TreeView<TreeData> treeView) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("metadata.dialog.create_folder.title", "新建连接分类"));
        alert.setHeaderText("");
        alert.setGraphic(null);
        //alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        AppState.applyAppStylesheet(alert);
        Stage alterstage = (Stage) alert.getDialogPane().getScene().getWindow();
        alterstage.getIcons().add(new Image(IconPaths.MAIN_LOGO));
        HBox hbox = new HBox();
        hbox.getChildren().add(new Label(I18n.t("metadata.dialog.create_folder.name", "请输入连接分类名称  ")));
        hbox.setAlignment(Pos.CENTER_LEFT);
        TextField textField = new TextField();
        textField.setPrefWidth(200);
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
            if (!textField.getText().isEmpty()) {
                Boolean exists = false;
                for (TreeItem<TreeData> treeItem : treeView.getRoot().getChildren()) {
                    if (treeItem.getValue().getName().equals(textField.getText())) {
                        exists = true;
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
            ConnectFolder connectFolder = new ConnectFolder();
            connectFolder.setName(textField.getText());
            connectFolder.setExpand(1);
            LocalDbRepository.createConnectFolder(connectFolder);
            TreeItem<TreeData> treeItem = createTreeItem(connectFolder);
            treeView.getRoot().getChildren().add(treeItem);
            reorderTreeview(treeView,treeItem);
        }
    }

    //创建一个连接节点
    public static void createConnectLeaf(TreeView<TreeData> treeView,TreeItem<TreeData> treeItem) {
        for (TreeItem<TreeData> folderTreeItem : treeView.getRoot().getChildren()) {
            if (((ConnectFolder)folderTreeItem.getValue()).getId() == ((Connect)treeItem.getValue()).getParentId()) {
                folderTreeItem.getChildren().add(treeItem);
                if(!folderTreeItem.isExpanded()){
                    folderTreeItem.setExpanded(true);
                }
                reorderTreeview(treeView,treeItem);
                break;

            }
        }
    }

    //连接创建后需要重新排序
    public static void reorderTreeview(TreeView<TreeData> treeView,TreeItem<TreeData> treeItem) {
        Comparator<TreeItem<TreeData>> treeItemComparator = (o1, o2) -> o1.getValue().getName().compareTo(o2.getValue().getName());
        treeItem.getParent().getChildren().sort(treeItemComparator);
        //排序后当前选择的元素到了最后一个，需重新设置当前选择项
        treeView.getSelectionModel().clearSelection();
        treeView.getSelectionModel().select(treeItem);
        treeView.scrollTo(treeView.getSelectionModel().getSelectedIndex());
    }
}
