package com.dbboys.ui.controller.tree;

import com.dbboys.app.AppState;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.TreeData;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.treemodel.UserFolder;
import com.dbboys.ui.util.ReadOnlyGuard;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.FileChooser;

import java.io.File;

/** Imports user creation statements from a SQL script and refreshes the user folder. */
public final class UserImportHandler {

    private UserImportHandler() {
    }

    public static void importUsers(TreeView<TreeData> treeView) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || !(selectedItem.getValue() instanceof UserFolder)) {
            return;
        }

        Connect metaConnect = TreeNavigator.getMetaConnect(selectedItem);
        if (metaConnect == null || ReadOnlyGuard.showBlockedAlertIfReadOnly(metaConnect)) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
        if (desktopDir.exists()) {
            fileChooser.setInitialDirectory(desktopDir);
        }
        fileChooser.setTitle(I18n.t("metadata.dialog.import_users.title", "导入用户"));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL Files", "*.sql"));
        File file = fileChooser.showOpenDialog(AppState.getWindow());
        if (file == null) {
            return;
        }

        Connect importConnect = new Connect(metaConnect);
        TreeViewUtil.databaseService.importSqlScript(importConnect, file, () -> {
            selectedItem.getChildren().clear();
            TreeDataLoader.treeItemAddChildrens(selectedItem);
            NotificationUtil.showMainNotification(
                    I18n.t("metadata.import_users.notice.completed", "用户导入完成：%s")
                            .formatted(file.getName()));
        });
        NotificationUtil.showMainNotification(
                I18n.t("metadata.import_users.notice.queued", "用户导入任务已提交：%s")
                        .formatted(file.getName()));
    }
}
