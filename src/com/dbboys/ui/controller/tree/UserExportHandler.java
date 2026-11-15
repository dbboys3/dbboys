package com.dbboys.ui.controller.tree;

import com.dbboys.app.AppErrorHandler;
import com.dbboys.app.AppState;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.TreeData;
import com.dbboys.model.User;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.treemodel.UserFolder;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exports all database users as CREATE USER statements with a shared default password. */
public final class UserExportHandler {
    public static final String DEFAULT_PASSWORD = "dbboys@123";

    private UserExportHandler() {
    }

    public static void exportUsers(TreeView<TreeData> treeView) {
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || !(selectedItem.getValue() instanceof UserFolder)) {
            return;
        }

        Connect connect = TreeNavigator.getMetaConnect(selectedItem);
        DatabasePlatform platform = TreeNavigator.resolvePlatform(selectedItem);
        if (connect == null || platform == null) {
            return;
        }

        connect.executeSqlTask(() -> {
            try {
                List<User> users = TreeViewUtil.userService.getUsers(connect, connect.getConn());
                String sql = buildExportSql(platform, users, DEFAULT_PASSWORD);
                Platform.runLater(() -> saveExport(sql, users.size()));
            } catch (Exception e) {
                Platform.runLater(() -> AppErrorHandler.handle(e));
            }
        });
    }

    static String buildExportSql(DatabasePlatform platform, List<User> users, String password) {
        Set<String> userNames = new LinkedHashSet<>();
        if (users != null) {
            for (User user : users) {
                if (user != null && user.getName() != null && !user.getName().isBlank()) {
                    userNames.add(user.getName().trim());
                }
            }
        }

        String dbType = platform == null || platform.getDbType() == null ? "" : platform.getDbType();
        StringBuilder sql = new StringBuilder(256 + userNames.size() * 80);
        sql.append("-- DBBOYS user export\n");
        sql.append("-- Database type: ").append(dbType).append('\n');
        sql.append("-- Default password: ").append(password).append('\n');
        sql.append("-- User count: ").append(userNames.size()).append("\n\n");

        if (platform != null) {
            for (String userName : userNames) {
                String statement = platform.createUserSql(userName, password);
                if (statement == null || statement.isBlank()) {
                    continue;
                }
                String trimmed = statement.strip();
                sql.append(trimmed);
                if (!trimmed.endsWith(";")) {
                    sql.append(';');
                }
                sql.append('\n');
            }
        }
        return sql.toString();
    }

    private static void saveExport(String sql, int userCount) {
        FileChooser fileChooser = new FileChooser();
        File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
        if (desktopDir.exists()) {
            fileChooser.setInitialDirectory(desktopDir);
        }
        fileChooser.setTitle(I18n.t("metadata.dialog.export_users.title", "导出用户"));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL Files", "*.sql"));
        fileChooser.setInitialFileName("users.sql");

        File file = fileChooser.showSaveDialog(AppState.getWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), sql, StandardCharsets.UTF_8);
            NotificationUtil.showMainNotification(
                    I18n.t("metadata.notice.export_users_saved", "已导出 %d 个用户到：%s")
                            .formatted(userCount, file.getAbsolutePath()));
        } catch (IOException e) {
            AppErrorHandler.handle(e);
        }
    }
}
