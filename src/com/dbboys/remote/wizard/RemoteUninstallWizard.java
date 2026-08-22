package com.dbboys.remote.wizard;
import com.dbboys.remote.RemoteDatabaseProvider;
import com.dbboys.app.RemoteDatabaseProviders;
import com.dbboys.remote.RemoteInstallStepSpec;
import com.dbboys.remote.RemoteSessionClient;
import com.dbboys.remote.RemoteUninstallExecutionContext;

import com.dbboys.app.AppExecutor;
import com.dbboys.ui.component.*;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.SshConnect;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.dialog.CustomWindowFrameUtil;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

public class RemoteUninstallWizard {
    // 存储用户输入信息
    private static String hostname;
    private static int port = 22;
    private static String username = "root";
    private static String password;

    private static final int TOTAL_STEPS = 3;
    private static final double DIALOG_WIDTH = 600;
    private static final double DIALOG_HEIGHT = 400;
    private static final DoubleProperty progress = new SimpleDoubleProperty(0);
    private static final IntegerProperty currentStep = new SimpleIntegerProperty(1);

    private static final RemoteSessionClient remoteClient = new RemoteSessionClient();

    //安装面板
    private static final java.util.List<CustomInstallStepHbox> uninstallStepBoxes = new ArrayList<>();



    // 步骤管理
    private static StackPane contentStack; // 用于切换步骤内容
    private static Stage mainDialog; // 主对话框
    private static DialogPane mainDialogPane;

    // 步骤内容面板（保存引用，用于状态保持）
    private static Node step1Pane, step2Pane, step3Pane, step4Pane, step5Pane;

    // 输入组件引用（用于跨步骤获取数据）
    private static CustomUserTextField hostField;
    private static CustomUserTextField portField;
    private static CustomPasswordField passField;
    private static CustomUserTextField userField;
    /** Auth type dropdown: index 0 = password, 1 = key. */
    private static ChoiceBox<String> authTypeChoiceBox;
    private static CustomUserTextField keyPathField;
    private static CustomPasswordField keyPassphraseField;
    /** "Use existing SSH connection" dropdown; index 0 is manual entry. */
    private static ChoiceBox<String> sshConnectionChoiceBox;
    /** Maps dropdown index -> ssh connection id (index 0 = manual entry). */
    private static final java.util.List<Integer> sshConnectionIds = new ArrayList<>();
    private static HBox backgroundHBox;
    private static Button stopButton;
    private static Label runningLabel;
    private static RemoteDatabaseProvider activeProvider = RemoteDatabaseProviders.gbase8s();

    // 入口方法：启动向导
    public static void startWizard(Stage parent) {
        startWizard(parent, RemoteDatabaseProviders.gbase8s());
    }

    public static void startWizard(Stage parent, RemoteDatabaseProvider provider) {
        activeProvider = provider == null ? RemoteDatabaseProviders.gbase8s() : provider;
        initMainDialog(parent);
        currentStep.set(1);
        updateWizardState();
        mainDialog.showAndWait();
    }

    // 初始化主对话框
    private static void initMainDialog(Stage parent) {
        mainDialog = new Stage();
        mainDialog.titleProperty().bind(Bindings.createStringBinding(
                () -> I18n.t("remote.uninstall.title.product.format", "%s 远程卸载向导 - 步骤 %d/%d")
                        .formatted(activeProviderName(), currentStep.get(), TOTAL_STEPS),
                I18n.localeProperty(),
                currentStep
        ));
        mainDialogPane = new DialogPane();
        mainDialogPane.getButtonTypes().setAll(ButtonType.PREVIOUS, ButtonType.NEXT, ButtonType.FINISH, ButtonType.CANCEL);
        mainDialogPane.setHeader(null);
        mainDialogPane.setMinSize(DIALOG_WIDTH, DIALOG_HEIGHT - 28);
        mainDialogPane.setPrefSize(DIALOG_WIDTH, DIALOG_HEIGHT - 28);
        mainDialogPane.setMaxSize(DIALOG_WIDTH, DIALOG_HEIGHT - 28);

        // 获取按钮实例
        Button previousBtn = (Button) mainDialogPane.lookupButton(ButtonType.PREVIOUS);
        Button nextBtn = (Button) mainDialogPane.lookupButton(ButtonType.NEXT);
        Button finishBtn = (Button) mainDialogPane.lookupButton(ButtonType.FINISH);
        Button cancelBtn = (Button) mainDialogPane.lookupButton(ButtonType.CANCEL);
        previousBtn.textProperty().bind(I18n.bind("common.previous", "上一步"));
        nextBtn.textProperty().bind(I18n.bind("common.next", "下一步"));
        finishBtn.textProperty().bind(I18n.bind("common.finish", "完成"));
        cancelBtn.textProperty().bind(I18n.bind("common.cancel", "取消"));



        // 加载指示器
        ImageView imageView = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);
        imageView.setFitWidth(12);
        imageView.setFitHeight(12);
        stopButton = new Button("");
        stopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        Tooltip stopTooltip = new Tooltip();
        stopTooltip.textProperty().bind(I18n.bind("remote.uninstall.tooltip.stop", "停止当前任务"));
        stopButton.setTooltip(stopTooltip);
        runningLabel=new Label("");
        HBox imageHBox = new HBox(imageView, runningLabel, stopButton);
        imageHBox.getStyleClass().add("modal-progress-card");
        imageHBox.setAlignment(Pos.CENTER);
        imageHBox.setMaxHeight(15);
        //imageHBox.setMaxWidth(100);
        stopButton.setFocusTraversable(false);
        stopButton.getStyleClass().add("small");
        backgroundHBox = new HBox(imageHBox);
        backgroundHBox.setAlignment(Pos.CENTER);
        backgroundHBox.getStyleClass().add("modal-progress-overlay");
        backgroundHBox.setVisible(false);


        //绑定属性
        previousBtn.disableProperty().bind(backgroundHBox.visibleProperty());
        nextBtn.disableProperty().bind(backgroundHBox.visibleProperty());
        finishBtn.disableProperty().bind(backgroundHBox.visibleProperty());


        // 初始化步骤内容
        initStepPanes(parent);

        // 初始化StackPane
        contentStack = new StackPane();
        contentStack.getChildren().addAll(step1Pane, step2Pane, step3Pane, backgroundHBox);


        // 显示初始步骤
        showCurrentStep();


        // 设置对话框内容
        mainDialogPane.setContent(contentStack);
        CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.createModalPopup(
                mainDialog,
                mainDialog.titleProperty(),
                mainDialogPane,
                DIALOG_WIDTH,
                DIALOG_HEIGHT,
                false,
                parent
        );
        frame.closeButton.setOnAction(event -> mainDialog.close());
        centerDialogToParent(mainDialog, parent);

        // 按钮事件
        previousBtn.addEventFilter(ActionEvent.ACTION, event -> {
            if (currentStep.get() > 1) {
                currentStep.set(currentStep.get() - 1);
                updateWizardState(); // 触发按钮状态更新
            }
            event.consume();
        });
        cancelBtn.setOnAction(event -> mainDialog.close());

        progress.addListener((obs, old, val) -> {
            int percentage = (int) (val.doubleValue() * 100);
            runningLabel.setText(" " + I18n.t("remote.uninstall.status.uploading", "正在上传安装包... %d%%").formatted(percentage));
        });
        nextBtn.addEventFilter(ActionEvent.ACTION, event -> {
            switch (currentStep.get()){
                case 1:
                    if(hostField.getText().trim().isEmpty()){
                        event.consume();
                        hostField.requestFocus();
                    }
                    else if(portField.getText().trim().isEmpty()){
                        event.consume();
                        portField.requestFocus();
                    }
                    else if(userField.getText().trim().isEmpty()){
                        event.consume();
                        userField.requestFocus();
                    }
                    else if(authTypeChoiceBox.getSelectionModel().getSelectedIndex() == 0
                            && passField.getText().trim().isEmpty()){
                        event.consume();
                        passField.requestFocus();
                    }
                    else if(authTypeChoiceBox.getSelectionModel().getSelectedIndex() == 1
                            && keyPathField.getText().trim().isEmpty()){
                        event.consume();
                        keyPathField.requestFocus();
                    }else {
                        backgroundHBox.setVisible(true);
                        hostname = hostField.getText().trim();
                        username = userField.getText().trim();
                        try {
                            port = Integer.parseInt(portField.getText().trim());
                        } catch (NumberFormatException e) {
                            port = 22;
                        }
                        password = passField.getText();

                        // 连接测试任务
                        Task<Void> runningTask = new Task<>() {
                            @Override
                            protected Void call() throws Exception {
                                try {
                                    remoteClient.connect(username, hostname, port, password,
                                            authTypeChoiceBox.getSelectionModel().getSelectedIndex() == 1,
                                            keyPathField.getText().trim(), keyPassphraseField.getText(), 5000);
                                    return null;
                                } catch (Exception e) {
                                    throw new Exception(I18n.t("remote.uninstall.error.connect_failed", "连接失败: %s").formatted(e.getMessage()));
                                }
                            }
                        };

                        // 任务完成处理
                        runningTask.setOnSucceeded(e -> {
                            backgroundHBox.setVisible(false);
                            // 自动进入下一步
                            currentStep.set(currentStep.get() + 1);
                            updateWizardState();

                        });

                        runningTask.setOnFailed(e -> {
                            backgroundHBox.setVisible(false);
                            String error = runningTask.getException().getMessage();
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                        });


                        AppExecutor.runTask(runningTask);
                        stopButton.setOnAction(event1->{
                            runningTask.cancel();
                            backgroundHBox.setVisible(false);
                        });

                        mainDialog.setOnCloseRequest(event1 -> {
                            runningTask.cancel();
                            remoteClient.disconnect();
                        });
                        cancelBtn.setOnAction(event1->{
                            runningTask.cancel();
                            remoteClient.disconnect();
                        });
                    }
                    runningLabel.setText(" " + I18n.t("remote.uninstall.status.connecting", "正在连接..."));
                    break;

                case 2:
                    Task installTask = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            RemoteUninstallExecutionContext uninstallContext = buildUninstallExecutionContext();
                            for (int stepNo = 1; stepNo <= uninstallStepBoxes.size(); stepNo++) {
                                if (!isUninstallStepSelected(stepNo)) {
                                    continue;
                                }
                                if (isCancelled()) {
                                    return null;
                                }
                                final int currentStepNo = stepNo;
                                Platform.runLater(() -> {
                                    setUninstallStepIconVisible(currentStepNo, true);
                                    runningLabel.setText(" " + I18n.t("remote.uninstall.status.uninstalling", "正在卸载..."));
                                });
                                activeProvider.executeUninstallStep(stepNo, uninstallContext);
                                Platform.runLater(() -> setUninstallStepIconVisible(currentStepNo, false));
                            }
                            return null;
                        }
                    };
                    installTask.setOnSucceeded(event1 -> {
                        backgroundHBox.setVisible(false);
                        hideAllUninstallStepIcons();
                        currentStep.set(currentStep.get() + 1);
                        updateWizardState();

                    });
                    installTask.setOnFailed(event1 -> {
                        backgroundHBox.setVisible(false);
                        hideAllUninstallStepIcons();
                        String error = installTask.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });

                    AppExecutor.runTask(installTask);
                    backgroundHBox.setVisible(true);
                    stopButton.setOnAction(event1->{
                        installTask.cancel();
                        backgroundHBox.setVisible(false);
                        hideAllUninstallStepIcons();
                    });
                    mainDialog.setOnCloseRequest(event1 -> {
                        installTask.cancel();
                        remoteClient.disconnect();
                    });
                    cancelBtn.setOnAction(event1->{
                        installTask.cancel();
                        remoteClient.disconnect();
                    });
                    break;
                default:
                    break;
            }

            event.consume();
        });


        finishBtn.setOnAction(e -> {
            remoteClient.disconnect();
            mainDialog.close();
        });

        // 更新按钮状态
        updateButtonStates(previousBtn, nextBtn, finishBtn,cancelBtn);

    }

    // 初始化所有步骤面板
    private static void initStepPanes(Stage parent) {
        step1Pane = createStep1Content(parent);
        step2Pane = createStep2Content(parent);
        step3Pane = createStep3Content(parent);
    }

    // 步骤1：连接设置面板
    private static Node createStep1Content(Stage parent) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // 输入组件（保存引用）——布局参考新建连接面板的SSH区域（主机和端口同一行，支持密钥认证）
        hostField = new CustomUserTextField();
        hostField.setPrefWidth(280);

        portField = new CustomUserTextField();
        portField.setText("22");
        portField.setPrefWidth(65);

        userField = new CustomUserTextField();
        userField.setText("root");
        userField.setPrefWidth(280);

        passField = new CustomPasswordField();
        passField.setPrefWidth(280);

        // 认证方式：密码 / 密钥
        authTypeChoiceBox = new ChoiceBox<>();
        authTypeChoiceBox.getItems().addAll(
                I18n.t("ssh.label.auth_password", "密码认证"),
                I18n.t("ssh.label.auth_key", "密钥认证"));
        authTypeChoiceBox.getSelectionModel().select(0);
        authTypeChoiceBox.setPrefWidth(280);

        keyPathField = new CustomUserTextField();
        keyPathField.setPrefWidth(190);
        keyPassphraseField = new CustomPasswordField();
        keyPassphraseField.setPrefWidth(130);
        Button keyBrowseButton = new Button();
        keyBrowseButton.setGraphic(IconFactory.group(IconPaths.MAIN_SEARCH, 0.65));
        keyBrowseButton.setFocusTraversable(false);
        keyBrowseButton.getStyleClass().addAll("custom-button-with-radius", "small");
        keyBrowseButton.setMaxSize(14, 14);
        keyBrowseButton.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(I18n.t("ssh.prompt.key_path", "选择 SSH 私钥文件"));
            java.io.File homeDir = new java.io.File(System.getProperty("user.home"));
            if (homeDir.isDirectory()) {
                java.io.File sshDir = new java.io.File(homeDir, ".ssh");
                chooser.setInitialDirectory(sshDir.isDirectory() ? sshDir : homeDir);
            }
            java.io.File selected = chooser.showOpenDialog(parent);
            if (selected != null) {
                keyPathField.setText(selected.getAbsolutePath());
            }
        });

        // 已有SSH连接下拉框：选中后填充主机/端口/用户名/认证信息
        sshConnectionChoiceBox = new ChoiceBox<>();
        sshConnectionChoiceBox.setPrefWidth(280);
        populateSshConnectionDropdown();
        sshConnectionChoiceBox.getSelectionModel().selectedIndexProperty()
                .addListener((obs, o, n) -> {
                    int idx = n.intValue();
                    if (idx > 0 && idx < sshConnectionIds.size()) {
                        SshConnect sel = getSshConnectionById(sshConnectionIds.get(idx));
                        if (sel != null) {
                            hostField.setText(sel.getHost());
                            portField.setText(sel.getPort());
                            userField.setText(sel.getUsername());
                            if (sel.isAuthKey()) {
                                authTypeChoiceBox.getSelectionModel().select(1);
                                keyPathField.setText(sel.getKeyPath());
                                keyPassphraseField.setText(sel.getKeyPassphrase());
                                passField.setText("");
                            } else {
                                authTypeChoiceBox.getSelectionModel().select(0);
                                passField.setText(sel.getPassword());
                                keyPathField.setText("");
                                keyPassphraseField.setText("");
                            }
                        }
                    }
                });

        // 标签
        Label sshConnLabel = new Label();
        sshConnLabel.textProperty().bind(I18n.bind("createconnect.label.ssh_connection", "已有连接"));

        Label ipLabel = new Label();
        ipLabel.textProperty().bind(I18n.bind("remote.uninstall.label.host", "主机名/IP"));

        Label portLabel = new Label();
        portLabel.textProperty().bind(I18n.bind("remote.uninstall.label.port", "端口"));

        Label userLabel = new Label();
        userLabel.textProperty().bind(I18n.bind("createconnect.label.ssh_user", "SSH 用户名"));

        Label authTypeLabel = new Label();
        authTypeLabel.textProperty().bind(I18n.bind("ssh.label.auth_type", "认证方式"));

        Label passwdLabel = new Label();
        passwdLabel.textProperty().bind(I18n.bind("createconnect.label.ssh_password", "SSH 密码"));

        Label keyLabel = new Label();
        keyLabel.textProperty().bind(I18n.bind("ssh.label.key_path", "密钥路径"));

        // 密钥行：路径 + 浏览 + 私钥密码（同一行）
        HBox keyFieldsBox = new HBox(6);
        keyFieldsBox.setAlignment(Pos.CENTER_LEFT);
        keyPassphraseField.setPromptText(I18n.t("ssh.prompt.key_passphrase", "私钥密码（可选）"));
        keyFieldsBox.getChildren().addAll(keyPathField, keyBrowseButton, keyPassphraseField);

        // 密码行 / 密钥行按认证方式切换显示
        Runnable updateAuthRows = () -> {
            boolean keyAuth = authTypeChoiceBox.getSelectionModel().getSelectedIndex() == 1;
            passwdLabel.setVisible(!keyAuth);
            passwdLabel.setManaged(!keyAuth);
            passField.setVisible(!keyAuth);
            passField.setManaged(!keyAuth);
            keyLabel.setVisible(keyAuth);
            keyLabel.setManaged(keyAuth);
            keyFieldsBox.setVisible(keyAuth);
            keyFieldsBox.setManaged(keyAuth);
        };
        authTypeChoiceBox.getSelectionModel().selectedIndexProperty()
                .addListener((obs, o, n) -> updateAuthRows.run());
        updateAuthRows.run();

        // 主机 + 端口同一行
        HBox hostPortBox = new HBox(10);
        hostPortBox.setAlignment(Pos.CENTER_LEFT);
        hostPortBox.getChildren().addAll(hostField, portLabel, portField);

        // 布局（密码行与密钥行共用同一网格行：切换认证方式时行距不变，避免隐藏行产生双倍行距）
        grid.add(sshConnLabel, 0, 0);
        grid.add(sshConnectionChoiceBox, 1, 0);
        grid.add(ipLabel, 0, 1);
        grid.add(hostPortBox, 1, 1);
        grid.add(userLabel, 0, 2);
        grid.add(userField, 1, 2);
        grid.add(authTypeLabel, 0, 3);
        grid.add(authTypeChoiceBox, 1, 3);
        grid.add(passwdLabel, 0, 4);
        grid.add(passField, 1, 4);
        grid.add(keyLabel, 0, 4);
        grid.add(keyFieldsBox, 1, 4);
        Label descbefore=new Label();
        descbefore.textProperty().bind(I18n.bind("remote.uninstall.desc.fill_server_info", "请填写需要远程卸载数据库的服务器信息："));

        Label desc=new Label();
        desc.textProperty().bind(I18n.bind("remote.uninstall.desc.title", "说明："));
        VBox vBox=new VBox(10);
        vBox.getStyleClass().add("remote-dialog-root");
        grid.getStyleClass().add("remote-dialog-grid");
        vBox.getChildren().addAll(descbefore,grid,desc);
        int descIndex = 0;
        for (String ignored : activeProvider.uninstallWizardDescriptionLines()) {
            Label lineLabel = new Label();
            final int currentDescIndex = descIndex++;
            lineLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> {
                        java.util.List<String> lines = activeProvider.uninstallWizardDescriptionLines();
                        return currentDescIndex < lines.size() ? lines.get(currentDescIndex) : "";
                    },
                    I18n.localeProperty()
            ));
            vBox.getChildren().add(lineLabel);
        }
        StackPane stackPane = new StackPane(vBox);

        // 保存连接任务引用，用于验证步骤1
        //step1ConnectTask = connectTask;

        return stackPane;
    }

    /** Populate the "use existing SSH connection" dropdown with saved SSH connections. */
    private static void populateSshConnectionDropdown() {
        sshConnectionIds.clear();
        sshConnectionChoiceBox.getItems().clear();
        sshConnectionChoiceBox.getItems().add(I18n.t("ssh.prompt.manual_ssh", "-- Manual --"));
        sshConnectionIds.add(0);
        for (SshConnect sc : LocalDbRepository.getAllSsh()) {
            sshConnectionChoiceBox.getItems().add(sc.getName());
            sshConnectionIds.add(sc.getId());
        }
        sshConnectionChoiceBox.getSelectionModel().select(0);
    }

    /** Get an SshConnect by its database ID. */
    private static SshConnect getSshConnectionById(int id) {
        for (SshConnect sc : LocalDbRepository.getAllSsh()) {
            if (sc.getId() == id) return sc;
        }
        return null;
    }








    // 步骤2：系统信息面板
    private static Node createStep2Content(Stage parent) {
        uninstallStepBoxes.clear();
        for (RemoteInstallStepSpec stepSpec : activeProvider.buildUninstallStepSpecs()) {
            CustomInstallStepHbox stepBox = new CustomInstallStepHbox("", "");
            stepBox.checkBox.setSelected(stepSpec.selected());
            stepBox.checkBox.setDisable(stepSpec.disabled());
            stepBox.nameLabel.textProperty().bind(I18n.bind(stepSpec.nameKey(), stepSpec.defaultName()));
            stepBox.descLabel.textProperty().bind(I18n.bind(stepSpec.descKey(), stepSpec.defaultDesc()));
            uninstallStepBoxes.add(stepBox);
        }
        Label titleLabel = new Label();
        titleLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.title", "点击【下一步】开始卸载"));
        HBox titleHBox=new HBox(titleLabel);
        titleHBox.setAlignment(Pos.CENTER);
        VBox vBox = new VBox(6);
        vBox.getChildren().add(titleHBox);
        vBox.getChildren().addAll(uninstallStepBoxes);
        vBox.setAlignment(Pos.TOP_CENTER);
        StackPane stackPane = new StackPane(vBox);


        // 进入步骤2时自动加载信息
        //loadSystemInfo();

        return stackPane;
    }

    // 步骤5：安装结果面板
    private static Node createStep3Content(Stage parent) {
        //codeArea.appendText("正在获取系统信息...\n");
        //databaseInfoArea=new CustomInlineCssTextArea();
        //CustomInfoStackPane stackPane = new CustomInfoStackPane(databaseInfoArea);
        //stackPane.showNoticeInMain=false;

        Label finishedLabel = new Label();
        finishedLabel.textProperty().bind(I18n.bind("remote.uninstall.step3.finished", "卸载完成！"));
        StackPane stackPane=new StackPane(finishedLabel);
        // 进入步骤2时自动加载信息
        //loadSystemInfo();

        return stackPane;
    }



    // 更新向导状态（标题、显示步骤）
    private static void updateWizardState() {
        showCurrentStep();
        updateButtonStates(
                (Button) mainDialogPane.lookupButton(ButtonType.PREVIOUS),
                (Button) mainDialogPane.lookupButton(ButtonType.NEXT),
                (Button) mainDialogPane.lookupButton(ButtonType.FINISH),
                        (Button) mainDialogPane.lookupButton(ButtonType.CANCEL)
        );
    }

    // 显示当前步骤的面板
    private static void showCurrentStep() {
        step1Pane.setVisible(currentStep.get() == 1);
        step2Pane.setVisible(currentStep.get() == 2);
        step3Pane.setVisible(currentStep.get() == 3);
    }

    // 更新按钮状态（显示/隐藏及布局占用）
    private static void updateButtonStates(Button previous, Button next, Button finish,Button cancel) {

        // 上一步：当前步骤>1时显示并参与布局，否则隐藏且不占空间

        boolean showPrevious = currentStep.get() > 1 && currentStep.get() < TOTAL_STEPS;
        previous.setVisible(showPrevious);
        previous.setManaged(showPrevious);

        // 下一步：当前步骤<5时显示并参与布局，否则隐藏且不占空间
        boolean showNext = currentStep.get() < TOTAL_STEPS;
        next.setVisible(showNext);
        next.setManaged(showNext);
        if(!next.isDisable()&&next.isVisible())next.requestFocus();

        // 完成：当前步骤=5时显示并参与布局，否则隐藏且不占空间
        boolean showFinish = currentStep.get() == TOTAL_STEPS;
        finish.setVisible(showFinish);
        finish.setManaged(showFinish);
        cancel.setVisible(!showFinish);
        cancel.setManaged(!showFinish);
        if (cancel.isVisible() && !backgroundHBox.isVisible()) {
            cancel.setOnAction(event -> mainDialog.close());
        }
    }

    private static void centerDialogToParent(Stage dialog, Stage parent) {
        Platform.runLater(() -> {
            if (parent == null || !parent.isShowing()) {
                return;
            }

            if (dialog.getScene() == null || dialog.getScene().getRoot() == null) {
                return;
            }
            dialog.getScene().getRoot().applyCss();
            dialog.getScene().getRoot().layout();

            double parentX = parent.getX();
            double parentY = parent.getY();
            double parentWidth = parent.getWidth();
            double parentHeight = parent.getHeight();

            double dialogWidth = dialog.getWidth() > 0 ? dialog.getWidth() : DIALOG_WIDTH;
            double dialogHeight = dialog.getHeight() > 0 ? dialog.getHeight() : DIALOG_HEIGHT;

            double dialogX = parentX + (parentWidth - dialogWidth) / 2;
            double dialogY = parentY + (parentHeight - dialogHeight) / 2;

            dialog.setX(dialogX);
            dialog.setY(dialogY);
        });
    }

    private static int executeCommandWithExitStatus(String command) throws IOException {
        return remoteClient.executeCommandWithExitStatus(command);
    }

    private static String activeProviderName() {
        return activeProvider == null ? I18n.t("remote.provider.default.name", "数据库") : activeProvider.displayName();
    }

    private static CustomInstallStepHbox uninstallStepBox(int stepNo) {
        return uninstallStepBoxes.get(stepNo - 1);
    }

    private static boolean isUninstallStepSelected(int stepNo) {
        return stepNo <= uninstallStepBoxes.size() && uninstallStepBox(stepNo).checkBox.isSelected();
    }

    private static RemoteUninstallExecutionContext buildUninstallExecutionContext() {
        return new RemoteUninstallExecutionContext(
                remoteClient,
                hostname == null ? hostField.getText() : hostname
        );
    }

    private static void setUninstallStepIconVisible(int stepNo, boolean visible) {
        if (stepNo <= uninstallStepBoxes.size()) {
            uninstallStepBox(stepNo).iconLabel.setVisible(visible);
        }
    }

    private static void hideAllUninstallStepIcons() {
        for (CustomInstallStepHbox stepBox : uninstallStepBoxes) {
            stepBox.iconLabel.setVisible(false);
        }
    }

}
