package com.dbboys.util;

import com.dbboys.app.AppExecutor;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.jcraft.jsch.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
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
import javafx.stage.Stage;

import java.io.*;
import java.util.Properties;

public class RemoteCheckEnvUtil {
    private static final int TOTAL_STEPS = 2;
    private static final double DIALOG_WIDTH = 600;
    private static final double DIALOG_HEIGHT = 400;
    private static final JSch JSCH = new JSch();
    private static final DoubleProperty progress = new SimpleDoubleProperty(0);
    private static final IntegerProperty currentStep = new SimpleIntegerProperty(1);

    private static String hostname;
    private static int port = 22;
    private static String username = "root";
    private static String password;
    private static Session session;
    private static StackPane contentStack;
    private static Stage mainDialog;
    private static DialogPane mainDialogPane;
    private static Node step1Pane;
    private static Node step2Pane;

    private static CustomUserTextField hostField;
    private static CustomUserTextField portField;
    private static CustomPasswordField passField;
    private static CustomInlineCssTextArea systemInfoArea;
    private static HBox backgroundHBox;
    private static Button stopButton;
    private static Label runningLabel;
    private static boolean progressListenerInstalled = false;

    public static void startWizard(Stage parent) {
        initMainDialog(parent);
        currentStep.set(1);
        progress.set(0);
        updateWizardState();
        mainDialog.showAndWait();
    }

    private static void initMainDialog(Stage parent) {
        mainDialog = new Stage();
        mainDialog.titleProperty().bind(Bindings.createStringBinding(
                () -> I18n.t("remote.check.title.format", "安装环境检查 - 步骤 %d/%d")
                        .formatted(currentStep.get(), TOTAL_STEPS),
                I18n.localeProperty(),
                currentStep
        ));
        mainDialogPane = new DialogPane();
        mainDialogPane.getButtonTypes().setAll(ButtonType.PREVIOUS, ButtonType.NEXT, ButtonType.FINISH, ButtonType.CANCEL);
        mainDialogPane.setHeader(null);
        mainDialogPane.setMinSize(DIALOG_WIDTH, DIALOG_HEIGHT - 28);
        mainDialogPane.setPrefSize(DIALOG_WIDTH, DIALOG_HEIGHT - 28);
        mainDialogPane.setMaxSize(DIALOG_WIDTH, DIALOG_HEIGHT - 28);

        Button previousBtn = (Button) mainDialogPane.lookupButton(ButtonType.PREVIOUS);
        Button nextBtn = (Button) mainDialogPane.lookupButton(ButtonType.NEXT);
        Button finishBtn = (Button) mainDialogPane.lookupButton(ButtonType.FINISH);
        Button cancelBtn = (Button) mainDialogPane.lookupButton(ButtonType.CANCEL);
        previousBtn.textProperty().bind(I18n.bind("common.previous", "上一步"));
        nextBtn.textProperty().bind(I18n.bind("common.next", "下一步"));
        finishBtn.textProperty().bind(I18n.bind("common.finish", "完成"));
        cancelBtn.textProperty().bind(I18n.bind("common.cancel", "取消"));

        ImageView imageView = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);
        imageView.setFitWidth(12);
        imageView.setFitHeight(12);
        stopButton = new Button("");
        stopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        Tooltip stopTooltip = new Tooltip();
        stopTooltip.textProperty().bind(I18n.bind("remote.check.tooltip.stop", "停止当前任务"));
        stopButton.setTooltip(stopTooltip);
        runningLabel=new Label("");
        HBox imageHBox = new HBox(imageView, runningLabel, stopButton);
        imageHBox.setStyle("-fx-background-color: rgb(58, 58, 60);-fx-background-radius: 2;-fx-padding: 0 0 0 5");
        imageHBox.setAlignment(Pos.CENTER);
        imageHBox.setMaxHeight(15);
        stopButton.setFocusTraversable(false);
        stopButton.getStyleClass().add("small");
        backgroundHBox = new HBox(imageHBox);
        backgroundHBox.setAlignment(Pos.CENTER);
        backgroundHBox.setStyle("-fx-background-color: rgba(0, 0, 0, 0.3);-fx-background-radius: 2;");
        backgroundHBox.setVisible(false);

        previousBtn.disableProperty().bind(backgroundHBox.visibleProperty());
        nextBtn.disableProperty().bind(backgroundHBox.visibleProperty());
        finishBtn.disableProperty().bind(backgroundHBox.visibleProperty());


        initStepPanes(parent);

        contentStack = new StackPane();
        contentStack.getChildren().addAll(step1Pane, step2Pane, backgroundHBox);

        showCurrentStep();

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

        previousBtn.addEventFilter(ActionEvent.ACTION, event -> {
            if (currentStep.get() > 1) {
                currentStep.set(currentStep.get() - 1);
                updateWizardState();
            }
            event.consume();
        });
        cancelBtn.setOnAction(event -> mainDialog.close());

        installProgressListener();
        nextBtn.addEventFilter(ActionEvent.ACTION, event -> {
            switch (currentStep.get()) {
                case 1:
                    if (hostField.getText().trim().isEmpty()) {
                        event.consume();
                        hostField.requestFocus();
                    } else if (portField.getText().trim().isEmpty()) {
                        event.consume();
                        portField.requestFocus();
                    } else if (passField.getText().trim().isEmpty()) {
                        event.consume();
                        passField.requestFocus();
                    } else {
                        backgroundHBox.setVisible(true);
                        hostname = hostField.getText().trim();
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
                                    if (session != null && session.isConnected()) {
                                        session.disconnect();
                                    }
                                    session = JSCH.getSession(username, hostname, port);
                                    session.setPassword(password);
                                    Properties config = new Properties();
                                    config.put("StrictHostKeyChecking", "no");
                                    session.setConfig(config);
                                    session.connect(5000); // 5秒超时
                                    return null;
                                } catch (JSchException e) {
                                    throw new Exception(I18n.t("remote.check.error.connect_failed", "连接失败: %s").formatted(e.getMessage()));
                                }
                            }
                        };

                        runningTask.setOnSucceeded(e -> {
                            runningLabel.setText(" " + I18n.t("remote.check.status.loading_system_info", "正在获取系统信息..."));
                            systemInfoArea.setStyle(0, systemInfoArea.getLength(), "");
                            systemInfoArea.replaceText("");
                            currentStep.set(currentStep.get() + 1);
                            updateWizardState();

                            Task<Void> systeminfoTask = new Task<>() {
                                @Override
                                protected Void call() throws Exception {
                                    try {
                                        // 收集系统信息
                                        String machineInfo = executeCommand("dmidecode -s system-product-name");
                                        String osInfo;
                                        if (isCommandExists("nkvers")) {
                                            osInfo = executeCommand("nkvers");
                                        } else if (executeCommandWithExitStatus("test -f /etc/redhat-release") == 0) {
                                            osInfo = executeCommand("cat /etc/redhat-release");
                                        } else {
                                            osInfo = executeCommand("cat /etc/os-release");
                                        }
                                        String cpuInfo = executeCommand("lscpu");
                                        String memInfo = executeCommand("free -h");
                                        String fileSystemInfo = executeCommand("df -h");
                                        String diskInfo = executeCommand("lsblk");
                                        String kernelInfo = executeCommand("uname -a");

                                        Platform.runLater(() -> {
                                            systemInfoArea.replaceText("");

                                            systemInfoArea.append(I18n.t("remote.check.info.machine", "服务器型号") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(machineInfo + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.check.info.os", "操作系统版本") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(osInfo + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.check.info.kernel", "内核版本") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(kernelInfo + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.check.info.cpu", "CPU信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(cpuInfo + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.check.info.memory", "内存信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(memInfo + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.check.info.disk", "磁盘信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(diskInfo + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.check.info.filesystem", "文件系统信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(fileSystemInfo + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.showParagraphAtTop(0);
                                        });
                                    } catch (Exception e) {
                                        throw new Exception(I18n.t("remote.check.error.system_info_failed", "获取系统信息失败: %s").formatted(e.getMessage()));
                                    }
                                    return null;
                                }

                            };
                            systeminfoTask.setOnSucceeded(e1 -> {
                                backgroundHBox.setVisible(false);

                            });

                            systeminfoTask.setOnFailed(e1 -> {
                                backgroundHBox.setVisible(false);
                                String error = systeminfoTask.getException().getMessage();
                                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                            });

                            startTask(systeminfoTask, "RemoteSystemInfoTask");
                            stopButton.setOnAction(event1 -> {
                                systeminfoTask.cancel();
                                backgroundHBox.setVisible(false);
                            });
                            mainDialog.setOnCloseRequest(event1 -> {
                                systeminfoTask.cancel();
                                if (session != null && session.isConnected()) {
                                    session.disconnect();
                                }
                            });
                            cancelBtn.setOnAction(event1 -> {
                                systeminfoTask.cancel();
                                if (session != null && session.isConnected()) {
                                    session.disconnect();
                                }
                            });

                        });

                        runningTask.setOnFailed(e -> {
                            backgroundHBox.setVisible(false);
                            String error = runningTask.getException().getMessage();
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                        });


                        startTask(runningTask, "RemoteConnectTask");
                        stopButton.setOnAction(event1 -> {
                            runningTask.cancel();
                            backgroundHBox.setVisible(false);
                        });

                        mainDialog.setOnCloseRequest(event1 -> {
                            runningTask.cancel();
                            if (session != null && session.isConnected()) {
                                session.disconnect();
                            }
                        });
                        cancelBtn.setOnAction(event1 -> {
                            runningTask.cancel();
                            if (session != null && session.isConnected()) {
                                session.disconnect();
                            }
                        });
                    }
                    runningLabel.setText(" " + I18n.t("remote.check.status.connecting", "正在连接..."));
                    break;

                default:
                    break;
            }
            event.consume();
        });


        finishBtn.setOnAction(e -> {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            mainDialog.close();
        });

        updateButtonStates(previousBtn, nextBtn, finishBtn, cancelBtn);

    }

    private static void initStepPanes(Stage parent) {
        step1Pane = createStep1Content(parent);
        step2Pane = createStep2Content(parent);
    }

    private static Node createStep1Content(Stage parent) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        hostField = new CustomUserTextField();
        hostField.setPrefWidth(420);

        portField = new CustomUserTextField();
        portField.setText("22");

        passField = new CustomPasswordField();

        // 图标和标签
        Label ipLabel = new Label();
        ipLabel.textProperty().bind(I18n.bind("remote.check.field.host", "主机名/IP"));
        ipLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_IP, 0.6, 0.6));
        ipLabel.setAlignment(Pos.CENTER_LEFT);
        ipLabel.setContentDisplay(ContentDisplay.LEFT);
        ipLabel.setGraphicTextGap(6);

        Label portLabel = new Label();
        portLabel.textProperty().bind(I18n.bind("remote.check.field.port", "端口"));
        portLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PORT, 0.45, 0.45));
        portLabel.setAlignment(Pos.CENTER_LEFT);
        portLabel.setContentDisplay(ContentDisplay.LEFT);
        portLabel.setGraphicTextGap(6);

        Label passwdLabel = new Label();
        passwdLabel.textProperty().bind(I18n.bind("remote.check.field.root_password", "root密码"));
        passwdLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PASSWORD, 0.5, 0.5));
        passwdLabel.setAlignment(Pos.CENTER_LEFT);
        passwdLabel.setContentDisplay(ContentDisplay.LEFT);
        passwdLabel.setGraphicTextGap(6);

        grid.add(ipLabel, 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(portLabel, 0, 1);
        grid.add(portField, 1, 1);
        grid.add(passwdLabel, 0, 2);
        grid.add(passField, 1, 2);
        Label descbefore = new Label();
        descbefore.textProperty().bind(I18n.bind("remote.check.desc.fill_server_info", "请填写需要检查安装环境服务器信息："));

        Label desc = new Label();
        desc.textProperty().bind(I18n.bind("remote.check.desc.only_linux_unix", "说明：安装环境检查仅用于Linux或Unix系统远程安装，不适用于Windows系统。"));
        VBox vBox = new VBox(10);
        vBox.setStyle("-fx-padding: 10 0 0 30");
        grid.setStyle("-fx-padding: 10 0 10 0;");
        vBox.getChildren().addAll(descbefore, grid, desc);
        StackPane stackPane = new StackPane(vBox);

        return stackPane;
    }
    private static Node createStep2Content(Stage parent) {
        systemInfoArea = new CustomInlineCssTextArea();
        CustomInfoStackPane stackPane = new CustomInfoStackPane(systemInfoArea);
        stackPane.showNoticeInMain = false;

        return stackPane;
    }

    private static void updateWizardState() {
        showCurrentStep();
        updateButtonStates(
                (Button) mainDialogPane.lookupButton(ButtonType.PREVIOUS),
                (Button) mainDialogPane.lookupButton(ButtonType.NEXT),
                (Button) mainDialogPane.lookupButton(ButtonType.FINISH),
                (Button) mainDialogPane.lookupButton(ButtonType.CANCEL)
        );
    }

    private static void showCurrentStep() {
        step1Pane.setVisible(currentStep.get() == 1);
        step2Pane.setVisible(currentStep.get() == 2);
    }

    private static void updateButtonStates(Button previous, Button next, Button finish, Button cancel) {

        boolean showPrevious = currentStep.get() > 1;
        previous.setVisible(showPrevious);
        previous.setManaged(showPrevious);

        boolean showNext = currentStep.get() < TOTAL_STEPS;
        next.setVisible(showNext);
        next.setManaged(showNext);
        if (!next.isDisable() && next.isVisible()) {
            next.requestFocus();
        }

        boolean showFinish = currentStep.get() == TOTAL_STEPS;
        finish.setVisible(showFinish);
        finish.setManaged(showFinish);
        cancel.setVisible(!showFinish);
        cancel.setManaged(!showFinish);
        if (cancel.isVisible() && !backgroundHBox.isVisible()) {
            cancel.setOnAction(event -> mainDialog.close());
        }
    }

    private static void installProgressListener() {
        if (progressListenerInstalled) {
            return;
        }
        progress.addListener((obs, old, val) -> {
            int percentage = (int) (val.doubleValue() * 100);
            runningLabel.setText(
                    " " + I18n.t("remote.check.status.uploading", "正在上传安装包... %d%%").formatted(percentage)
            );
        });
        progressListenerInstalled = true;
    }

    private static void startTask(Task<?> task, String threadName) {
        AppExecutor.runTask(task);
    }

    private static String executeCommand(String command) throws JSchException, IOException {
        ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
        channelExec.setCommand(command);

        InputStream in = channelExec.getInputStream();
        channelExec.connect();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder output = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        channelExec.disconnect();
        return output.toString().trim();
    }

    private static class ProgressMonitorInputStream extends FilterInputStream {
        private final long totalSize;
        private long bytesRead = 0;
        private final DoubleProperty progress;

        public ProgressMonitorInputStream(InputStream in, long totalSize, DoubleProperty progress) {
            super(in);
            this.totalSize = totalSize;
            this.progress = progress;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int bytes = super.read(b, off, len);
            if (bytes > 0) {
                bytesRead += bytes;
                double currentProgress = (double) bytesRead / totalSize;
                Platform.runLater(() -> progress.set(currentProgress));
            }
            return bytes;
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

    private static int executeCommandWithExitStatus(String command) throws JSchException, InterruptedException {
        ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
        channelExec.setCommand(command);
        channelExec.connect();

        while (!channelExec.isClosed()) {
            Thread.sleep(100);
        }

        int exitStatus = channelExec.getExitStatus();
        channelExec.disconnect();
        return exitStatus;
    }

    private static boolean isCommandExists(String command) throws JSchException, InterruptedException {
        int exitStatus = executeCommandWithExitStatus("command -v " + command);
        return exitStatus == 0;
    }
}
