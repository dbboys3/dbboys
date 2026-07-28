package com.dbboys.ui.controller.tree;

import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.ReconnectFallbackCapability;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.*;
import com.dbboys.ui.component.*;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.model.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.function.UnaryOperator;

class TreeCreateDialogs {
    private static final Logger log = LogManager.getLogger(TreeCreateDialogs.class);

    static void showCreateDatabaseDialog(TreeItem<TreeData> selectedItem) {
        DatabasePlatform platform = TreeNavigator.resolvePlatform(selectedItem);
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
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String newText = change.getControlNewText();
            if (newText.matches("[\\x00-\\x7F]*")) {
                return change;
            } else {
                return null;
            }
        };
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
        if (platform != null) {
            comboBox.getItems().setAll(platform.createDatabaseCharsetOptions());
            comboBox.setValue(platform.defaultCreateDatabaseCharsetOption());
        }
        comboBox.setId("createDatabaseCharset");
        comboBox.setPrefWidth(240);

        ChoiceBox<String> comboBox1 = new ChoiceBox<>();
        comboBox1.setId("createDatabaseDbspace");
        comboBox1.setPrefWidth(240);

        boolean showStorageSpace = platform == null || platform.supportsCreateDatabaseStorageSpace();
        ObservableList<String> dbspaceList = FXCollections.observableArrayList();
        if (showStorageSpace) {
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
            if (!dbspaceList.isEmpty()) {
                comboBox1.setValue(dbspaceList.get(0));
            }
        }

        grid.add(nameLabel, 0, 0);
        grid.add(textField, 1, 0);
        grid.add(charsetLabel, 0, 1);
        grid.add(comboBox, 1, 1);
        if (showStorageSpace) {
            grid.add(dbspaceLabel, 0, 2);
            grid.add(comboBox1, 1, 2);
        }

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
            connect.setCatalog(resolveFallbackDatabase(connect));
            connect.setSessionCatalog("");
            ConnectionPropertyUtil.applySupportedConnectionProperty(
                    TreeViewUtil.connectionService,
                    TreeMenuCapabilities.resolvePlatformResolver(),
                    connect,
                    "DB_LOCALE",
                    dbLocale
            );
            DatabasePlatform createPlatform = platform == null ? TreeMenuCapabilities.resolvePlatformResolver().requirePlatform(connect) : platform;
            String sql = createPlatform.createDatabaseSql(
                    textField.getText(),
                    dbLocale,
                    comboBox1.getValue()
            );
            TreeViewUtil.databaseService.executeObjectSql(connect, sql, () -> {
                NotificationUtil.showMainNotification(
                        I18n.t("backsql.notice.database_created", "数据库[%s]创建成功").formatted(textField.getText())
                );
                selectedItem.getChildren().clear();
                selectedItem.setExpanded(false);
                selectedItem.setExpanded(true);
            });
        }
    }

    static void showCreateSchemaDialog(TreeItem<TreeData> selectedItem) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        grid.setPadding(new Insets(10));

        CustomUserTextField schemaName = new CustomUserTextField();
        CustomPasswordField passwordField1 = new CustomPasswordField();
        CustomPasswordField passwordField2 = new CustomPasswordField();
        schemaName.setPrefWidth(240);
        passwordField1.setPrefWidth(240);
        passwordField2.setPrefWidth(240);

        Label nameLabel = new Label(I18n.t("metadata.dialog.create_schema.name", "模式名"));
        SVGPath nameLabelIcon = IconFactory.create(IconPaths.METADATA_NAME_LABEL, 0.55, 0.55, Color.valueOf("#888"));
        nameLabel.setGraphic(nameLabelIcon);

        Label passwordLabel = new Label(I18n.t("metadata.label.password", "密码"));
        SVGPath passwordLabelIcon = IconFactory.create(IconPaths.METADATA_PASSWORD_LABEL, 0.5, 0.5, Color.valueOf("#888"));
        passwordLabel.setGraphic(passwordLabelIcon);

        Label confirmPasswordLabel = new Label(I18n.t("metadata.label.confirm_password", "确认密码"));
        SVGPath confirmPasswordLabelIcon = IconFactory.create(IconPaths.METADATA_CONFIRM_PASSWORD_LABEL, 0.5, 0.5, Color.valueOf("#888"));
        confirmPasswordLabel.setGraphic(confirmPasswordLabelIcon);

        grid.add(nameLabel, 0, 0);
        grid.add(schemaName, 1, 0);
        grid.add(passwordLabel, 0, 1);
        grid.add(passwordField1, 1, 1);
        grid.add(confirmPasswordLabel, 0, 2);
        grid.add(passwordField2, 1, 2);

        ButtonType createButtonType = new ButtonType(I18n.t("metadata.button.create", "创建"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("metadata.dialog.create_schema.title", "新建模式"),
                grid,
                420,
                Region.USE_COMPUTED_SIZE,
                createButtonType,
                cancelButtonType
        );
        Button commit = dialog.getButton(createButtonType);

        commit.addEventFilter(ActionEvent.ACTION, event -> {
            if (schemaName.getText().trim().isEmpty()) {
                schemaName.requestFocus();
                event.consume();
            } else if (passwordField1.getText().trim().isEmpty()) {
                passwordField1.requestFocus();
                event.consume();
            } else if (passwordField2.getText().trim().isEmpty()) {
                passwordField2.requestFocus();
                event.consume();
            } else if (!passwordField1.getText().trim().equals(passwordField2.getText().trim())) {
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("metadata.error.password_not_match", "两次密码输入不一致！"));
                event.consume();
            } else {
                event.consume();
                Connect connect = new Connect((Connect) selectedItem.getParent().getValue());
                String name = schemaName.getText().trim();
                DatabasePlatform schemaPlatform = TreeNavigator.resolvePlatform(selectedItem);
                if (schemaPlatform == null) {
                    schemaPlatform = TreeMenuCapabilities.resolvePlatformResolver().requirePlatform(connect);
                }
                String sql = schemaPlatform.createUserSql(name, passwordField1.getText().trim());
                TreeViewUtil.databaseService.executeObjectSql(connect, sql, () -> {
                    selectedItem.getChildren().clear();
                    selectedItem.setExpanded(false);
                    selectedItem.setExpanded(true);
                    NotificationUtil.showMainNotification(
                            I18n.t("metadata.success.create_schema", "模式[%s]创建成功").formatted(name)
                    );
                    dialog.getStage().close();
                });
            }
        });

        schemaName.requestFocus();
        dialog.showAndWait();
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
        return connect.getCatalog();
    }

    private static String resolveReconnectFallbackDatabase(Connect connect) {
        if (connect == null) {
            return null;
        }
        try {
            var dialect = TreeMenuCapabilities.resolvePlatformResolver().requirePlatform(connect);
            return dialect.capability(ReconnectFallbackCapability.class)
                    .map(ReconnectFallbackCapability::reconnectFallbackDatabaseName)
                    .orElse(null);
        } catch (Exception ignored) {
        }
        return null;
    }
}
