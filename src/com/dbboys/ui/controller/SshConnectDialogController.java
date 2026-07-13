package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.SshTunnelUtil;
import com.dbboys.ssh.SshConnect;
import com.dbboys.ui.component.CustomPasswordField;
import com.dbboys.ui.component.CustomUserTextField;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.dialog.CustomWindowFrameUtil;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Controller for the SSH connection create/edit dialog.
 * Uses Stage + DialogPane + CustomWindowFrameUtil pattern (matching
 * {@link com.dbboys.ui.controller.tree.TreeNavigator#showCreateConnectDialog}).
 */
public class SshConnectDialogController {
    private static final Logger log = LogManager.getLogger(SshConnectDialogController.class);

    private final SshConnect sshConnect;
    private final boolean isNew;
    private boolean committed;

    // Form fields
    private CustomUserTextField nameField;
    private CustomUserTextField hostField;
    private CustomUserTextField portField;
    private CustomUserTextField userField;
    private CustomPasswordField passwordField;
    private ChoiceBox<String> authTypeChoiceBox;
    private CustomUserTextField keyPathField;
    private Button keyBrowseButton;
    private HBox passwordRow;
    private HBox keyPathRow;

    // Connecting overlay
    private HBox connectingHBox;
    private Label connectingStatusLabel;
    private Button connectingStopButton;
    private Stage dialogStage;
    private Button cancelButton;

    // Current task for cancellation
    private Task<Void> currentTask;

    public SshConnectDialogController(SshConnect sshConnect, boolean isNew) {
        this.sshConnect = sshConnect;
        this.isNew = isNew;
    }

    /**
     * Build the dialog and show it. Returns true if the user committed (saved) the connection.
     */
    public boolean showAndWait() {
        String title = I18n.t(isNew ? "ssh.title.new" : "ssh.title.edit",
                isNew ? "New SSH Connection" : "Edit SSH Connection");

        DialogPane dialogPane = buildDialogPane();

        dialogStage = new Stage();
        int dialogW = 460;
        int dialogH = 250;
        int titleBarHeight = 28;
        int contentH = dialogH - titleBarHeight;
        dialogPane.setMinSize(dialogW, contentH);
        dialogPane.setPrefSize(dialogW, contentH);
        dialogPane.setMaxSize(dialogW, contentH);

        SimpleStringProperty titleProp = new SimpleStringProperty(title);
        CustomWindowFrameUtil.createModalPopup(
                dialogStage, titleProp, dialogPane, dialogW, dialogH, false);
        dialogStage.setResizable(false);
        dialogStage.sizeToScene();

        // Cancel running task when window is closed
        dialogStage.setOnCloseRequest(e -> {
            if (currentTask != null && currentTask.isRunning()) {
                currentTask.cancel();
            }
        });

        // Center relative to owner window
        Window owner = AppState.getWindow();
        if (owner != null && owner.isShowing()) {
            dialogStage.setX(owner.getX() + (owner.getWidth() - dialogW) / 2);
            dialogStage.setY(owner.getY() + (owner.getHeight() - dialogH) / 2);
        }

        dialogStage.showAndWait();
        return committed;
    }

    private DialogPane buildDialogPane() {
        DialogPane dialogPane = new DialogPane();
        dialogPane.setHeader(null);

        // --- Build content rows ---
        VBox contentBox = new VBox();
        contentBox.setStyle("-fx-padding: 10 18 10 18;");

        // row 0: name
        HBox nameRow = row30();
        nameField = new CustomUserTextField();
        nameField.setPrefWidth(260);
        nameField.setPromptText(I18n.t("ssh.prompt.name", "Optional, default [Host_Port]"));
        nameField.setText(sshConnect.getName() != null ? sshConnect.getName() : "");
        nameRow.getChildren().addAll(label80("createconnect.label.name"), nameField);

        // row 1: host + port
        HBox hostRow = row30();
        hostField = new CustomUserTextField();
        hostField.setPrefWidth(150);
        hostField.setPromptText(I18n.t("ssh.prompt.host", "SSH server address"));
        hostField.setText(sshConnect.getHost() != null ? sshConnect.getHost() : "");
        Label spacer10 = new Label("");
        spacer10.setPrefWidth(10);
        Label portLabel = new Label();
        portLabel.textProperty().bind(I18n.bind("createconnect.label.ssh_port"));
        Label spacer3 = new Label("");
        spacer3.setPrefWidth(3);
        portField = new CustomUserTextField();
        portField.setPrefWidth(48);
        portField.setPromptText(I18n.t("ssh.prompt.port", "SSH port"));
        portField.setText(sshConnect.getPort() != null ? sshConnect.getPort() : "22");
        portField.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d*") ? change : null));
        hostRow.getChildren().addAll(label80("createconnect.label.ssh_host"), hostField,
                spacer10, portLabel, spacer3, portField);

        // row 2: username
        HBox userRow = row30();
        userField = new CustomUserTextField();
        userField.setPrefWidth(150);
        userField.setPromptText(I18n.t("ssh.prompt.username", "SSH username"));
        userField.setText(sshConnect.getUsername() != null ? sshConnect.getUsername() : "");
        userRow.getChildren().addAll(label80("createconnect.label.ssh_user"), userField);

        // row 3: auth type
        HBox authTypeRow = row30();
        authTypeChoiceBox = new ChoiceBox<>();
        authTypeChoiceBox.setFocusTraversable(false);
        authTypeChoiceBox.getStyleClass().add("choice-box-with-border");
        authTypeChoiceBox.getItems().addAll(
                I18n.t("ssh.label.auth_password", "Password"),
                I18n.t("ssh.label.auth_key", "Key"));
        authTypeChoiceBox.getSelectionModel().select(sshConnect.isAuthKey() ? 1 : 0);
        authTypeRow.getChildren().addAll(label80("ssh.label.auth_type"), authTypeChoiceBox);

        // row 4: password (password auth mode)
        passwordRow = row30();
        passwordField = new CustomPasswordField();
        passwordField.setPrefWidth(170);
        passwordField.setPromptText(I18n.t("ssh.prompt.password", "SSH login password"));
        passwordField.setText(sshConnect.isAuthKey() ? "" :
                (sshConnect.getPassword() != null ? sshConnect.getPassword() : ""));
        passwordRow.getChildren().addAll(label80("createconnect.label.ssh_password"), passwordField);

        // row 5: key file path (key auth mode)
        keyPathRow = row30();
        keyPathField = new CustomUserTextField();
        keyPathField.setPrefWidth(160);
        keyPathField.setPromptText(I18n.t("ssh.prompt.key_path", "Select SSH private key"));
        keyPathField.setText(sshConnect.isAuthKey() ?
                (sshConnect.getKeyPath() != null ? sshConnect.getKeyPath() : "") : "");
        keyBrowseButton = new Button();
        keyBrowseButton.setFocusTraversable(false);
        keyBrowseButton.getStyleClass().addAll("small");
        keyBrowseButton.setGraphic(IconFactory.group(IconPaths.MAIN_SEARCH, 0.65));
        keyBrowseButton.setTooltip(new Tooltip(I18n.t("createconnect.tooltip.browse_file", "Browse")));
        keyBrowseButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("ssh.prompt.key_path", "Select SSH Private Key"));
            File homeDir = new File(System.getProperty("user.home"));
            if (homeDir.isDirectory()) {
                File sshDir = new File(homeDir, ".ssh");
                chooser.setInitialDirectory(sshDir.isDirectory() ? sshDir : homeDir);
            }
            File selected = chooser.showOpenDialog(AppState.getWindow());
            if (selected != null) {
                keyPathField.setText(selected.getAbsolutePath());
            }
        });
        keyPathRow.getChildren().addAll(label80("ssh.label.key_path"), keyPathField,
                new Label(" "), keyBrowseButton);

        contentBox.getChildren().addAll(nameRow, hostRow, userRow, authTypeRow,
                passwordRow, keyPathRow);

        // --- Toggle auth rows ---
        Runnable updateAuthRows = () -> {
            boolean isKeyMode = authTypeChoiceBox.getSelectionModel().getSelectedIndex() == 1;
            passwordRow.setVisible(!isKeyMode);
            passwordRow.setManaged(!isKeyMode);
            keyPathRow.setVisible(isKeyMode);
            keyPathRow.setManaged(isKeyMode);
        };
        updateAuthRows.run();
        authTypeChoiceBox.getSelectionModel().selectedIndexProperty()
                .addListener((obs, o, n) -> updateAuthRows.run());

        // --- Connecting overlay (matches CreateConnect.fxml pattern) ---
        connectingHBox = new HBox();
        connectingHBox.setAlignment(Pos.CENTER);
        connectingHBox.setVisible(false);
        connectingHBox.getStyleClass().add("modal-progress-overlay");

        HBox connectingStatusBox = new HBox();
        connectingStatusBox.setMaxWidth(100);
        connectingStatusBox.setMaxHeight(15);
        connectingStatusBox.setAlignment(Pos.CENTER);
        connectingStatusBox.getStyleClass().add("modal-progress-card");

        ImageView connectingLoadingImageView = new ImageView(new Image(IconPaths.LOADING_GIF));
        connectingLoadingImageView.setFitHeight(12);
        connectingLoadingImageView.setFitWidth(12);

        connectingStatusLabel = new Label();
        connectingStatusLabel.textProperty().bind(I18n.bind("createconnect.status.connecting"));

        connectingStopButton = new Button();
        connectingStopButton.getStyleClass().add("small");
        connectingStopButton.setFocusTraversable(false);
        // Use the same icon-danger style class so CSS applies correctly
        javafx.scene.shape.SVGPath stopIcon = new javafx.scene.shape.SVGPath();
        stopIcon.setContent(IconPaths.SQL_STOP);
        stopIcon.getStyleClass().add("icon-danger");
        stopIcon.setScaleX(0.7);
        stopIcon.setScaleY(0.7);
        connectingStopButton.setGraphic(stopIcon);
        Tooltip stopTooltip = new Tooltip();
        stopTooltip.textProperty().bind(I18n.bind("createconnect.tooltip.stop_connecting"));
        connectingStopButton.setTooltip(stopTooltip);
        connectingStopButton.setOnAction(e -> {
            if (currentTask != null && currentTask.isRunning()) {
                currentTask.cancel();
            }
            setConnectingVisible(false);
        });

        connectingStatusBox.getChildren().addAll(connectingLoadingImageView, connectingStatusLabel, connectingStopButton);
        connectingHBox.getChildren().add(connectingStatusBox);

        StackPane contentStack = new StackPane(contentBox, connectingHBox);

        // --- Button types ---
        ButtonType testButtonType = new ButtonType(
                I18n.t("createconnect.button.test"), ButtonBar.ButtonData.NO);
        ButtonType commitButtonType = new ButtonType(
                I18n.t("createconnect.button.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType(
                I18n.t("createconnect.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        dialogPane.getButtonTypes().addAll(testButtonType, commitButtonType, cancelButtonType);
        dialogPane.setContent(contentStack);

        // --- Wire buttons ---
        Button testButton = (Button) dialogPane.lookupButton(testButtonType);
        testButton.disableProperty().bind(connectingHBox.visibleProperty());
        testButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!checkInput()) {
                event.consume();
                return;
            }
            setValues();
            doTest();
            event.consume();
        });

        Button commitButton = (Button) dialogPane.lookupButton(commitButtonType);
        commitButton.disableProperty().bind(connectingHBox.visibleProperty());
        commitButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!checkInput()) {
                event.consume();
                return;
            }
            setValues();
            doCommit();
            event.consume();
        });

        cancelButton = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelButton.setOnAction(e -> {
            if (currentTask != null && currentTask.isRunning()) {
                currentTask.cancel();
            }
            dialogStage.close();
        });

        return dialogPane;
    }

    // ---- Validation and value setting ----

    private boolean checkInput() {
        if (hostField.getText().isBlank()) {
            hostField.requestFocus();
            return false;
        }
        if (userField.getText().isBlank()) {
            userField.requestFocus();
            return false;
        }
        boolean isKey = authTypeChoiceBox.getSelectionModel().getSelectedIndex() == 1;
        if (!isKey && passwordField.getText().isBlank()) {
            passwordField.requestFocus();
            return false;
        }
        if (isKey && keyPathField.getText().isBlank()) {
            keyPathField.requestFocus();
            return false;
        }
        return true;
    }

    private void setValues() {
        if (nameField.getText().isBlank()) {
            sshConnect.setName("[" + hostField.getText() + "_" + portField.getText() + "]");
        } else {
            sshConnect.setName(nameField.getText());
        }
        sshConnect.setHost(hostField.getText());
        sshConnect.setPort(portField.getText());
        sshConnect.setUsername(userField.getText());
        boolean isKey = authTypeChoiceBox.getSelectionModel().getSelectedIndex() == 1;
        sshConnect.setAuthType(isKey ? SshConnect.AUTH_KEY : SshConnect.AUTH_PASSWORD);
        sshConnect.setPassword(isKey ? "" : passwordField.getText());
        sshConnect.setKeyPath(isKey ? keyPathField.getText() : "");
    }

    // ---- Connecting overlay visibility ----

    private void setConnectingVisible(boolean visible) {
        if (Platform.isFxApplicationThread()) {
            connectingHBox.setVisible(visible);
        } else {
            Platform.runLater(() -> connectingHBox.setVisible(visible));
        }
    }

    // ---- Test connection (using Task for proper cancellation) ----

    private void doTest() {
        setConnectingVisible(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                long start = System.currentTimeMillis();
                try {
                    int port = Integer.parseInt(sshConnect.getPort());
                    var tunnel = SshTunnelUtil.createTunnel(
                            sshConnect.getHost(), port,
                            sshConnect.getUsername(), sshConnect.getPassword(),
                            "127.0.0.1", 1);
                    tunnel.close();
                    if (isCancelled()) return null;
                    long elapsed = System.currentTimeMillis() - start;
                    Platform.runLater(() -> {
                        setConnectingVisible(false);
                        AlertUtil.CustomAlert(I18n.t("common.hint"),
                                String.format(I18n.t("createconnect.notice.test_success"), elapsed));
                    });
                } catch (Exception ex) {
                    if (isCancelled()) return null;
                    log.error("SSH test failed", ex);
                    Platform.runLater(() -> {
                        setConnectingVisible(false);
                        AlertUtil.CustomAlert(I18n.t("common.error"),
                                String.format(I18n.t("createconnect.error.ssh_tunnel_failed"), ex.getMessage()));
                    });
                }
                return null;
            }
        };
        task.setOnFailed(e -> {
            setConnectingVisible(false);
            Throwable ex = task.getException();
            String msg = ex != null ? (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName()) : "Failed";
            AlertUtil.CustomAlert(I18n.t("common.error"), msg);
        });
        currentTask = task;
        AppExecutor.runTask(task);
    }

    // ---- Commit (save) ----

    private void doCommit() {
        setConnectingVisible(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                long start = System.currentTimeMillis();
                try {
                    int port = Integer.parseInt(sshConnect.getPort());
                    var tunnel = SshTunnelUtil.createTunnel(
                            sshConnect.getHost(), port,
                            sshConnect.getUsername(), sshConnect.getPassword(),
                            "127.0.0.1", 1);
                    tunnel.close();
                    if (isCancelled()) return null;
                    long elapsed = System.currentTimeMillis() - start;
                    Platform.runLater(() -> {
                        setConnectingVisible(false);
                        AlertUtil.CustomAlert(I18n.t("common.hint"),
                                String.format(I18n.t("createconnect.notice.test_success"), elapsed));
                        doSave();
                    });
                } catch (Exception ex) {
                    if (isCancelled()) return null;
                    log.error("SSH connect failed", ex);
                    Platform.runLater(() -> {
                        setConnectingVisible(false);
                        AlertUtil.CustomAlert(I18n.t("common.error"),
                                String.format(I18n.t("createconnect.error.ssh_tunnel_failed"), ex.getMessage()));
                    });
                }
                return null;
            }
        };
        task.setOnFailed(e -> {
            setConnectingVisible(false);
            Throwable ex = task.getException();
            String msg = ex != null ? (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName()) : "Failed";
            AlertUtil.CustomAlert(I18n.t("common.error"), msg);
        });
        currentTask = task;
        AppExecutor.runTask(task);
    }

    private void doSave() {
        if (isNew) {
            LocalDbRepository.createSsh(sshConnect);
        } else {
            LocalDbRepository.updateSsh(sshConnect);
        }
        committed = true;
        dialogStage.close();
    }

    // ---- Layout helpers ----

    private static HBox row30() {
        HBox row = new HBox();
        row.setPrefHeight(30);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label label80(String i18nKey) {
        Label label = new Label();
        label.textProperty().bind(I18n.bind(i18nKey));
        label.setPrefWidth(80);
        return label;
    }
}
