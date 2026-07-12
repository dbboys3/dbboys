package com.dbboys.remote;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.ui.component.*;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.dialog.CustomWindowFrameUtil;
import com.dbboys.infra.util.DownloadManagerUtil;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.model.Connect;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.jcraft.jsch.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.filechooser.FileSystemView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class RemoteInstallerUtil {
    private static final Logger log = LogManager.getLogger(RemoteInstallerUtil.class);
    private static final int TOTAL_STEPS = 5;
    private static final double DIALOG_WIDTH = 600;
    private static final double DIALOG_HEIGHT = 400;
    private static final DoubleProperty progress = new SimpleDoubleProperty(0);
    private static final IntegerProperty currentStep = new SimpleIntegerProperty(1);
    private static final String BROWSE_ICON_PATH = "M9.8438 1.7184 Q12.0469 1.7184 13.9219 2.7965 Q15.7969 3.8746 16.8906 5.7496 Q18 7.609 18 9.8278 Q18 12.4684 16.4688 14.6246 L21.8906 20.0934 Q22.2656 20.4371 22.2812 20.9684 Q22.3125 21.484 21.9531 21.8746 Q21.5938 22.2496 21.0938 22.2809 Q20.5938 22.2965 20.2031 21.9684 L14.6406 16.4528 Q12.4844 17.984 9.8438 17.984 Q7.625 17.984 5.75 16.8903 Q3.8906 15.7809 2.8125 13.9059 Q1.7344 12.0309 1.7344 9.8278 Q1.7344 7.609 2.8125 5.7496 Q3.8906 3.8746 5.75 2.7965 Q7.625 1.7184 9.8438 1.7184 ZM9.8438 4.2496 Q8.3594 4.2496 7.0625 4.9996 Q5.7656 5.7496 5.0156 7.0465 Q4.2656 8.3434 4.2656 9.8278 Q4.2656 11.3121 5.0156 12.609 Q5.7656 13.9059 7.0625 14.6559 Q8.3594 15.3903 9.8594 15.3903 Q11.375 15.3903 12.6406 14.6559 Q13.9219 13.9059 14.6562 12.6403 Q15.4062 11.359 15.4062 9.859 Q15.4062 8.3434 14.6562 7.0778 Q13.9219 5.7965 12.6406 5.0309 Q11.375 4.2496 9.8438 4.2496 Z";
    private static final String DOWNLOAD_ICON_PATH = "M19.0156 9 L15 9 L15 3 L9 3 L9 9 L5 9 L12 16.9844 L19.0156 9 ZM4.0156 19 L20 19 L20 21 L4.0156 21 L4.0156 19 Z";

    // 存储用户输入信息
    private static String hostname;
    private static int port = 22;
    private static String username = "root";
    private static String password;
    private static File selectedFile;
    private static String remoteFilePath;
    private static Double freeDiskSize;

    //系统信息
    private static String machineInfo;
    private static String osInfo;
    private static String kernelInfo;
    private static String cpuInfo;
    private static String memInfo;
    private static String diskInfo;
    private static String fileSystemInfo;
    private static final RemoteSessionClient remoteClient = new RemoteSessionClient();

    //安装面板
    private static final List<CustomInstallStepHbox> installStepBoxes = new ArrayList<>();


    // 步骤管理
    private static StackPane contentStack; // 用于切换步骤内容
    private static Stage mainDialog; // 主对话框
    private static DialogPane mainDialogPane;
    private static SimpleStringProperty mainDialogTitle;

    // 步骤内容面板（保存引用，用于状态保持）
    private static Node step1Pane, step2Pane, step3Pane, step4Pane, step5Pane;

    // 输入组件引用（用于跨步骤获取数据）
    private static CustomUserTextField hostField;
    private static CustomUserTextField portField;
    private static CustomPasswordField passField;
    private static CustomUserTextField remotePathField;
    private static CustomUserTextField installFilePathField;
    private static CustomInlineCssTextArea systemInfoArea;
    private static CustomInlineCssTextArea databaseInfoArea;
    private static HBox backgroundHBox;
    private static Button stopButton;
    private static Label runningLabel;
    private static ToggleGroup uploadToggleGroup = new ToggleGroup();
    private static RadioButton notUploadedRadioButton = new RadioButton();
    private static RadioButton uploadedRadioButton = new RadioButton();
    private static final ObservableList<InstallConfigItem> installConfigItems = FXCollections.observableArrayList();
    private static RemoteDatabaseProvider activeProvider = RemoteDatabaseProviders.gbase8s();

    private static final class InstallConfigItem {
        private final String id;
        private String name;
        private String value;
        private String description;

        private InstallConfigItem(String id, String name, String value, String description) {
            this.id = id;
            this.name = name;
            this.value = value;
            this.description = description;
        }

        private InstallConfigItem(InstallConfigItem other) {
            this(other.id, other.name, other.value, other.description);
        }
    }


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
        saveInstallResultToDesktop();
    }

    // 初始化主对话框
    private static void initMainDialog(Stage parent) {
        mainDialog = new Stage();
        mainDialogTitle = new SimpleStringProperty();
        mainDialogTitle.bind(Bindings.createStringBinding(
                () -> I18n.t("remote.install.title.product.format", "%s 远程安装向导 - 步骤 %d/%d")
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

        // 创建按钮
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
        stopButton.setGraphic(IconFactory.group(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        Tooltip stopTooltip = new Tooltip();
        stopTooltip.textProperty().bind(I18n.bind("remote.install.tooltip.stop", "停止当前任务"));
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
        contentStack.getChildren().addAll(step1Pane, step2Pane, step3Pane, step4Pane, step5Pane, backgroundHBox);


        // 显示初始步骤
        showCurrentStep();



        // 设置对话框内容
        mainDialogPane.setContent(contentStack);
        CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.createModalPopup(
                mainDialog,
                mainDialogTitle,
                mainDialogPane,
                DIALOG_WIDTH,
                DIALOG_HEIGHT,
                false,
                parent
        );
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
            runningLabel.setText(" " + I18n.t("remote.install.status.uploading", "正在上传安装包... %d%%").formatted(percentage));
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
                    else if(passField.getText().trim().isEmpty()){
                        event.consume();
                        passField.requestFocus();
                    }else {
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
                                    remoteClient.connect(username, hostname, port, password, 5000);
                                    return null;
                                } catch (JSchException e) {
                                    throw new Exception(I18n.t("remote.install.error.connect_failed", "连接失败: %s").formatted(e.getMessage()));
                                }
                            }
                        };

                        // 任务完成处理
                        runningTask.setOnSucceeded(e -> {
                            //backgroundHBox.setVisible(false);
                            runningLabel.setText(" " + I18n.t("remote.install.status.loading_system_info", "正在获取系统信息..."));
                            systemInfoArea.setStyle(0,systemInfoArea.getLength(),"");
                            systemInfoArea.replaceText("");
                            // 自动进入下一步
                            currentStep.set(currentStep.get() + 1);
                            updateWizardState();

                            //第二步自动执行，并显示执行结果
                            Task<Void> systeminfoTask = new Task<>() {
                                @Override
                                protected Void call() throws Exception {
                                    try {
                                        RemoteSystemInfoSnapshot snapshot = RemoteSystemInfoCollector.collect(remoteClient);
                                        machineInfo = snapshot.machineInfo();
                                        osInfo = snapshot.osInfo();
                                        cpuInfo = snapshot.cpuInfo();
                                        memInfo = snapshot.memoryInfo();
                                        fileSystemInfo = snapshot.fileSystemInfo();
                                        diskInfo = snapshot.diskInfo();
                                        kernelInfo = snapshot.kernelInfo();
                                        // 显示信息
                                        Platform.runLater(() -> {
                                            systemInfoArea.replaceText("");

                                            systemInfoArea.append(I18n.t("remote.install.info.machine", "服务器型号") + "\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(machineInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            // 省略其他信息的显示代码（与原逻辑相同）
                                            systemInfoArea.append(I18n.t("remote.install.info.os", "操作系统版本") + "\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(osInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.kernel", "内核版本") + "\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(kernelInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.cpu", "CPU信息") + "\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(cpuInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.memory", "内存信息") + "\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(memInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.disk", "磁盘信息") + "\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(diskInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.filesystem", "文件系统信息") + "\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(fileSystemInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");



                                            //systemInfoArea.append("内核参数\n","-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;");
                                            //systemInfoArea.append(kernelParams + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.showParagraphAtTop(0);
                                        });
                                    } catch (Exception e) {
                                        throw new Exception(I18n.t("remote.install.error.system_info_failed", "获取系统信息失败: %s").formatted(e.getMessage()));
                                        //String errorMsg = "获取系统信息失败: " + e.getMessage();
                                        //Platform.runLater(() -> {
                                        //    systemInfoArea.replaceText(errorMsg + "\n");
                                        //   showErrorDialog(null, "信息获取失败", errorMsg);
                                        // });
                                    }
                                    return null;
                                }

                            };
                            // 任务完成处理
                            systeminfoTask.setOnSucceeded(e1 -> {
                                backgroundHBox.setVisible(false);

                            });

                            systeminfoTask.setOnFailed(e1 -> {
                                backgroundHBox.setVisible(false);
                                String error = systeminfoTask.getException().getMessage();
                                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                            });

                            AppExecutor.runTask(systeminfoTask);
                            stopButton.setOnAction(event1->{
                                systeminfoTask.cancel();
                                backgroundHBox.setVisible(false);
                            });
                            mainDialog.setOnCloseRequest(event1 -> {
                                systeminfoTask.cancel();
                                remoteClient.disconnect();
                            });
                            cancelBtn.setOnAction(event1->{
                                systeminfoTask.cancel();
                                remoteClient.disconnect();
                                mainDialog.close();
                            });

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
                            mainDialog.close();
                        });
                    }
                    runningLabel.setText(" " + I18n.t("remote.install.status.connecting", "正在连接..."));
                    break;
                case 2:
                    currentStep.set(currentStep.get() + 1);
                    updateWizardState();
                    break;
                case 3:
                    if(notUploadedRadioButton.isSelected()) {
                        if(installFilePathField.getText().isEmpty()){
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("remote.install.error.local_package_empty", "需上传的安装包文件路径不能为空！"));
                        }else if(!new File(installFilePathField.getText()).exists()){
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("remote.install.error.local_package_missing", "需上传的安装包文件不存在！"));
                        }else if(!activeProvider.isPackageCompatible(systemInfoArea.getText(), installFilePathField.getText())){
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("remote.install.error.cpu_mismatch_local", "需上传的安装包文件与远程服务器CPU不匹配！"));
                        }else{
                            selectedFile=new File(installFilePathField.getText());
                            remoteFilePath=remotePathField.getText();
                            if(remoteFilePath.isEmpty()){
                                remoteFilePath="/tmp/"+selectedFile.getName();
                            }
                            Task<Void> runningTask = new Task<>() {
                                @Override
                                protected Void call() throws Exception {
                                    ChannelSftp channelSftp = null;
                                    try {
                                        channelSftp = remoteClient.openSftpChannel();

                                        Platform.runLater(() -> {
                                            runningLabel.setText(" " + I18n.t("remote.install.status.uploading_package", "正在上传安装包..."));
                                            backgroundHBox.setVisible(true);
                                        });

                                        try (FileInputStream fis = new FileInputStream(selectedFile)) {
                                            ProgressMonitorInputStream monitor = new ProgressMonitorInputStream(
                                                    fis, selectedFile.length(), progress);
                                            channelSftp.put(monitor, remoteFilePath+".upload");
                                        }

                                    } catch (Exception e) {
                                        throw new Exception(I18n.t("remote.install.error.upload_failed", "上传安装包失败: %s").formatted(e.getMessage()));
                                    } finally {
                                        if (channelSftp != null && channelSftp.isConnected()) {
                                            channelSftp.disconnect();
                                        }
                                    }
                                    return null;

                                }
                            };
                            runningTask.setOnSucceeded(e->{
                                try {
                                    executeCommandWithExitStatus("mv " + shellQuote(remoteFilePath + ".upload") + " " + shellQuote(remoteFilePath));
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                                initConfigList();
                                backgroundHBox.setVisible(false);
                                currentStep.set(currentStep.get() + 1);
                                updateWizardState();
                            });
                            runningTask.setOnFailed(e -> {
                                try {
                                    executeCommandWithExitStatus("rm -f "+remoteFilePath+".upload");
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                                backgroundHBox.setVisible(false);
                                String error = runningTask.getException().getMessage();
                                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                            });
                            stopButton.setOnAction(event1->{
                                runningTask.cancel();
                                backgroundHBox.setVisible(false);
                                try {
                                    executeCommandWithExitStatus("rm -f "+remoteFilePath+".upload");
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            });
                            mainDialog.setOnCloseRequest(event1 -> {
                                runningTask.cancel();
                                remoteClient.disconnect();
                            });
                            cancelBtn.setOnAction(event1->{
                                runningTask.cancel();
                                remoteClient.disconnect();
                                mainDialog.close();
                            });



                            try {
                                if(remoteFileExists(remoteFilePath)) {
                                    /* 如果存在不执行任何操作
                                    if( AlertUtil.CustomAlertConfirm("文件已存在","安装包在服务器/tmp目录已存在，确定要上传覆盖吗？")){
                                        executeCommandWithExitStatus("cd /tmp");
                                        executeCommandWithExitStatus("rm -rf " + shellQuote(remoteFilePath));
                                        new Thread(runningTask).start();
                                    }
                                     */
                                    initConfigList();
                                    currentStep.set(currentStep.get() + 1);
                                    updateWizardState();
                                }else{
                                    AppExecutor.runTask(runningTask);
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }else{
                        if(remotePathField.getText().isEmpty()){
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("remote.install.error.remote_path_empty", "远程服务器上安装包路径不能为空！"));
                        }else {
                            remoteFilePath=remotePathField.getText();
                            try {
                                if(!remoteFileExists(remoteFilePath)) {
                                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("remote.install.error.remote_package_missing", "远程服务器上安装包文件不存在！"));
                                }else if(!activeProvider.isPackageCompatible(systemInfoArea.getText(), remoteFilePath)){
                                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("remote.install.error.cpu_mismatch_remote", "服务器上的安装包文件与CPU不匹配！"));
                                }else{
                                    initConfigList();
                                    currentStep.set(currentStep.get() + 1);
                                    updateWizardState();
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }

                    break;
                case 4:
                    Task<Void> installTask = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            Platform.runLater(() -> setAllInstallStepsDisabled(true));
                            RemoteInstallExecutionContext installContext = buildInstallExecutionContext();
                            for (int stepNo = 1; stepNo <= installStepBoxes.size(); stepNo++) {
                                if (!isInstallStepSelected(stepNo)) {
                                    continue;
                                }
                                if (isCancelled()) {
                                    return null;
                                }
                                final int currentStepNo = stepNo;
                                Platform.runLater(() -> {
                                    setInstallStepIconVisible(currentStepNo, true);
                                    runningLabel.setText(" " + I18n.t("remote.install.status.installing", "正在安装..."));
                                });
                                activeProvider.executeInstallStep(stepNo, installContext);
                                Platform.runLater(() -> setInstallStepIconVisible(currentStepNo, false));
                            }
                            activeProvider.afterInstallSteps(installContext);
                            return null;
                        }
                    };
                    installTask.setOnSucceeded(event1 -> {
                        backgroundHBox.setVisible(false);
                        hideAllInstallStepIcons();
                        currentStep.set(currentStep.get() + 1);
                        updateWizardState();

                        try {
                            activeProvider.populateInstallResult(buildInstallExecutionContext(), databaseInfoArea);
                        } catch (Exception e) {
                            log.error("Load install result failed", e);
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
                        }

                    });
                    installTask.setOnFailed(event1 -> {
                        backgroundHBox.setVisible(false);
                        hideAllInstallStepIcons();
                        restoreInstallStepDisabledState();

                        String error = installTask.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });
                    installTask.setOnCancelled(event1 -> {
                        backgroundHBox.setVisible(false);
                        hideAllInstallStepIcons();
                        restoreInstallStepDisabledState();
                    });

                    try {
                        AppExecutor.runTask(installTask);
                    }catch (Exception e){
                        log.error("Operation failed", e);
                    }
                    backgroundHBox.setVisible(true);
                    stopButton.setOnAction(event1->{
                        installTask.cancel();
                        backgroundHBox.setVisible(false);
                        hideAllInstallStepIcons();
                        restoreInstallStepDisabledState();

                    });
                    mainDialog.setOnCloseRequest(event1 -> {
                        installTask.cancel();
                        remoteClient.disconnect();

                    });
                    cancelBtn.setOnAction(event1->{
                        installTask.cancel();
                        remoteClient.disconnect();
                        mainDialog.close();
                    });
                    break;
                default:
                    break;
            }
            //currentStep++;
            //updateWizardState(); // 触发按钮状态更新
            event.consume();
        });


        finishBtn.setOnAction(e -> {
            remoteClient.disconnect();
            mainDialog.close();
            Connect installedConnect = activeProvider.buildInstalledConnect(buildInstallExecutionContext());
            if (installedConnect != null) {
                AppState.setLastInstallConnect(installedConnect);
                Platform.runLater(AppState::createConnectLeaf);
            }
        });

        // 更新按钮状态
        updateButtonStates(previousBtn, nextBtn, finishBtn,cancelBtn);

    }

    // 初始化所有步骤面板
    private static void initStepPanes(Stage parent) {
        step1Pane = createStep1Content(parent);
        step2Pane = createStep2Content(parent);
        step3Pane = createStep3Content(parent);
        step4Pane = createStep4Content(parent);
        step5Pane = createStep5Content(parent);
    }

    // 步骤1：连接设置面板
    private static Node createStep1Content(Stage parent) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // 输入组件（保存引用）
        hostField = new CustomUserTextField();
        hostField.setPrefWidth(420);

        portField = new CustomUserTextField();
        portField.setText("22");

        passField = new CustomPasswordField();

        Label ipLabel = new Label();
        ipLabel.textProperty().bind(I18n.bind("remote.install.field.host", "主机名/IP"));
        ipLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_IP, 0.6, 0.6));
        ipLabel.setAlignment(Pos.CENTER_LEFT);
        ipLabel.setContentDisplay(ContentDisplay.LEFT);
        ipLabel.setGraphicTextGap(6);

        Label portLabel = new Label();
        portLabel.textProperty().bind(I18n.bind("remote.install.field.port", "端口"));
        portLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PORT, 0.45, 0.45));
        portLabel.setAlignment(Pos.CENTER_LEFT);
        portLabel.setContentDisplay(ContentDisplay.LEFT);
        portLabel.setGraphicTextGap(6);

        Label passwdLabel = new Label();
        passwdLabel.textProperty().bind(I18n.bind("remote.install.field.root_password", "root密码"));
        passwdLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PASSWORD, 0.5, 0.5));
        passwdLabel.setAlignment(Pos.CENTER_LEFT);
        passwdLabel.setContentDisplay(ContentDisplay.LEFT);
        passwdLabel.setGraphicTextGap(6);



        // 布局
        grid.add(ipLabel, 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(portLabel, 0, 1);
        grid.add(portField, 1, 1);
        grid.add(passwdLabel, 0, 2);
        grid.add(passField, 1, 2);
        Label descbefore = new Label();
        descbefore.textProperty().bind(I18n.bind("remote.install.desc.fill_server_info", "请填写需要远程安装数据库的服务器信息："));
        Label desc = new Label();
        desc.textProperty().bind(I18n.bind("remote.install.desc.title", "说明："));
        VBox vBox=new VBox(10);
        vBox.getStyleClass().add("remote-dialog-root");
        grid.getStyleClass().add("remote-dialog-grid");
        vBox.getChildren().addAll(descbefore,grid,desc);
        int descIndex = 0;
        for (String ignored : activeProvider.installWizardDescriptionLines()) {
            Label lineLabel = new Label();
            final int currentDescIndex = descIndex++;
            lineLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> {
                        List<String> lines = activeProvider.installWizardDescriptionLines();
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



    // 步骤2：系统信息面板
    private static Node createStep2Content(Stage parent) {
        systemInfoArea = new CustomInlineCssTextArea();
        //codeArea.appendText("正在获取系统信息...\n");
        CustomInfoStackPane stackPane = new CustomInfoStackPane(systemInfoArea);
        stackPane.showNoticeInMain=false;
        // 进入步骤2时自动加载信息
        //loadSystemInfo();

        return stackPane;
    }



    // 步骤3：文件选择面板
    private static Node createStep3Content(Stage parent) {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10,20,10,20));

        Button browseButton = new Button("");
        browseButton.setGraphic(IconFactory.group(BROWSE_ICON_PATH, 0.6, 0.6));
        browseButton.getStyleClass().add("small");
        browseButton.setFocusTraversable(false);
        Tooltip browseTooltip = new Tooltip();
        browseTooltip.textProperty().bind(I18n.bind("remote.install.tooltip.browse_package", "浏览安装包"));
        browseButton.setTooltip(browseTooltip);

        Button downloadButton = new Button("");
        downloadButton.setGraphic(IconFactory.group(DOWNLOAD_ICON_PATH, 0.6, 0.6));
        downloadButton.getStyleClass().add("small");
        downloadButton.setFocusTraversable(false);
        Tooltip downloadTooltip = new Tooltip();
        downloadTooltip.textProperty().bind(I18n.bind("remote.install.tooltip.download_package", "下载安装包"));
        downloadButton.setTooltip(downloadTooltip);



        //selectedFileLabel = new Label("选择已下载的安装包，或点击下载图标自动下载与CPU型号匹配的最新版本。");
        remotePathField = new CustomUserTextField();
        //remotePathField.setText("/tmp");
        installFilePathField= new CustomUserTextField();
        installFilePathField.setMinWidth(450);
        installFilePathField.setMaxWidth(450);
        remotePathField.setMaxWidth(450);
        Label localPackageHintLabel = new Label();
        localPackageHintLabel.textProperty().bind(Bindings.createStringBinding(
                activeProvider::localPackageHintText,
                I18n.localeProperty()
        ));
        Label downloadPrefixLabel = new Label();
        downloadPrefixLabel.textProperty().bind(Bindings.createStringBinding(
                activeProvider::downloadHintPrefixText,
                I18n.localeProperty()
        ));
        Label downloadSuffixLabel = new Label();
        downloadSuffixLabel.textProperty().bind(Bindings.createStringBinding(
                activeProvider::downloadHintSuffixText,
                I18n.localeProperty()
        ));
        HBox downloadHBox = new HBox(downloadPrefixLabel, downloadButton, downloadSuffixLabel);
        HBox hBox = new HBox(10);
        hBox.getChildren().addAll(installFilePathField,browseButton);
        // 浏览文件事件
        browseButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("remote.install.step3.select_package", "选择安装包"));
            selectedFile = fileChooser.showOpenDialog(parent);
            if (selectedFile != null) {
                String localPath = selectedFile.getAbsolutePath();
                installFilePathField.setText(localPath);
                remoteFilePath = "/tmp/" + selectedFile.getName();
                remotePathField.setText(remoteFilePath);
            }
        });
        StackPane downloadStackPane=new StackPane();
        downloadButton.setOnAction(event -> {
            if (!activeProvider.supportsPackageDownload()) {
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), activeProvider.unsupportedPlatformMessage());
                return;
            }
            File desktopDir = FileSystemView.getFileSystemView().getHomeDirectory();
            String fileName="";
            String url = activeProvider.resolveDownloadUrl(systemInfoArea.getText());
            if(url == null || url.isEmpty()){
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                        activeProvider.unsupportedPlatformMessage());
                return;
            }
            try {
                /*
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                Optional<String> location = response.headers().firstValue("Location");
                String realUrl=location.orElse("");
                fileName=realUrl.substring(realUrl.lastIndexOf("/")+1);

                 */
                fileName=DownloadManagerUtil.getRealFileNameFromRedirect(url);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                AlertUtil.CustomAlert(I18n.t("common.download_failed", "下载失败"), e.getMessage());
                return;
            }

            File saveFile = new File(desktopDir, fileName);
            DownloadManagerUtil.addInstallDownload(
                    url,
                    saveFile,
                    true,
                    null,
                    downloadStackPane,
                    remotePathField,
                    installFilePathField
            );

        });
        downloadStackPane.setMinHeight(50);
        notUploadedRadioButton.setToggleGroup(uploadToggleGroup);
        notUploadedRadioButton.textProperty().bind(I18n.bind("remote.install.step3.not_uploaded", "我没有上传数据库安装包"));
        notUploadedRadioButton.setSelected(true);
        downloadButton.disableProperty().bind(notUploadedRadioButton.selectedProperty().not().or(Bindings.createBooleanBinding(
                () -> !activeProvider.supportsPackageDownload(),
                I18n.localeProperty()
        )));
        installFilePathField.disableProperty().bind(notUploadedRadioButton.selectedProperty().not());
        browseButton.disableProperty().bind(notUploadedRadioButton.selectedProperty().not());
        uploadedRadioButton.setToggleGroup(uploadToggleGroup);
        uploadedRadioButton.textProperty().bind(I18n.bind("remote.install.step3.uploaded", "我已经上传了数据库安装包"));
        remotePathField.disableProperty().bind(uploadedRadioButton.selectedProperty().not());
        VBox.setMargin(uploadedRadioButton, new Insets(12, 0, 0, 0));
        Label uploadTitleLabel = new Label();
        uploadTitleLabel.textProperty().bind(I18n.bind("remote.install.step3.upload_title", "上传安装包到远程服务器："));
        Label uploadPathLabel = new Label();
        uploadPathLabel.textProperty().bind(I18n.bind("remote.install.step3.upload_path", "远程服务器上安装包路径，如安装包已上传，请在下框填入安装包绝对路径："));
        content.getChildren().add(uploadTitleLabel);
        content.getChildren().add(notUploadedRadioButton);
        content.getChildren().add(activeProvider.supportsPackageDownload() ? downloadHBox : localPackageHintLabel);
        content.getChildren().add(hBox);
        if (activeProvider.supportsPackageDownload()) {
            content.getChildren().add(downloadStackPane);
        }
        Label remotePathHint = new Label();
        remotePathHint.textProperty().bind(I18n.bind("remote.install.step3.remote_path_hint", "如果包含多个压缩文件，以空格分隔"));
        remotePathHint.getStyleClass().add("text-muted");
        content.getChildren().addAll(
                uploadedRadioButton,
                uploadPathLabel,
                remotePathField,
                remotePathHint
        );

        return content;
    }



    // 步骤2：系统信息面板
    private static Node createStep4Content(Stage parent) {
        installStepBoxes.clear();
        List<RemoteInstallStepSpec> stepSpecs = activeProvider.buildInstallStepSpecs();
        for (RemoteInstallStepSpec stepSpec : stepSpecs) {
            CustomInstallStepHbox stepBox = new CustomInstallStepHbox("", "");
            stepBox.checkBox.setSelected(stepSpec.selected());
            stepBox.checkBox.setDisable(stepSpec.disabled());
            stepBox.nameLabel.textProperty().bind(I18n.bind(stepSpec.nameKey(), stepSpec.defaultName()));
            stepBox.descLabel.textProperty().bind(I18n.bind(stepSpec.descKey(), stepSpec.defaultDesc()));
            installStepBoxes.add(stepBox);
        }

        Label titlePrefixLabel = new Label();
        titlePrefixLabel.textProperty().bind(I18n.bind("remote.install.step4.title_prefix", "点击【下一步】开始安装，如需自定义设置，点击【"));
        HBox titleHBox=new HBox(titlePrefixLabel);
        Button envButton=MenuItemUtil.createModifyButton(I18n.t("remote.install.step4.custom_settings", "自定义设置"));
        if (envButton.getTooltip() != null) {
            envButton.getTooltip().textProperty().bind(I18n.bind("remote.install.step4.custom_settings", "自定义设置"));
        }
        titleHBox.getChildren().add(envButton);
        Label titleSuffixLabel = new Label();
        titleSuffixLabel.textProperty().bind(I18n.bind("remote.install.step4.title_suffix", "】编辑"));
        titleHBox.getChildren().add(titleSuffixLabel);

        envButton.setOnAction(event -> {
            modifyEnv();
        });
        titleHBox.setAlignment(Pos.CENTER);
        VBox vBox = new VBox(4);
        vBox.getChildren().add(titleHBox);
        vBox.getChildren().addAll(installStepBoxes);
        vBox.setAlignment(Pos.TOP_CENTER);
        StackPane stackPane = new StackPane(vBox);


        // 进入步骤2时自动加载信息
        //loadSystemInfo();

        return stackPane;
    }

    // 步骤5：安装结果面板
    private static Node createStep5Content(Stage parent) {
        //codeArea.appendText("正在获取系统信息...\n");
        databaseInfoArea=new CustomInlineCssTextArea();
        CustomInfoStackPane stackPane = new CustomInfoStackPane(databaseInfoArea);
        stackPane.showNoticeInMain=false;

        // 进入步骤2时自动加载信息
        //loadSystemInfo();

        return stackPane;
    }



    // 更新向导状态（标题显示步骤）
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
        step4Pane.setVisible(currentStep.get() == 4);
        step5Pane.setVisible(currentStep.get() == 5);
    }

    // 更新按钮状态（显示/隐藏及布局占用）
    private static void updateButtonStates(Button previous, Button next, Button finish,Button cancel) {

        // 上一步：当前步骤>1时显示并参与布局，否则隐藏且不占空间

        boolean showPrevious = currentStep.get() > 1 && currentStep.get() < 5;
        previous.setVisible(showPrevious);
        previous.setManaged(showPrevious);

        // 下一步：当前步骤<5时显示并参与布局，否则隐藏且不占空间
        boolean showNext = currentStep.get() < 5;
        next.setVisible(showNext);
        next.setManaged(showNext);
        if(!next.isDisable()&&next.isVisible())next.requestFocus();

        // 完成：当前步骤=5时显示并参与布局，否则隐藏且不占空间
        boolean showFinish = currentStep.get() == 5;
        finish.setVisible(showFinish);
        finish.setManaged(showFinish);
        cancel.setVisible(!showFinish);
        cancel.setManaged(!showFinish);
    }

    // 以下为原有工具方法（保持不变）
    private static String executeCommand(String command) throws JSchException, IOException {
        return remoteClient.executeCommand(command);
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
        return remoteClient.executeCommandWithExitStatus(command);
    }

    private static boolean isCommandExists(String command) throws JSchException, InterruptedException {
        int exitStatus = executeCommandWithExitStatus("command -v " + command);
        return exitStatus == 0;
    }

    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String activeProviderName() {
        return activeProvider == null ? I18n.t("remote.provider.default.name", "数据库") : activeProvider.displayName();
    }

    private static CustomInstallStepHbox installStepBox(int stepNo) {
        return installStepBoxes.get(stepNo - 1);
    }

    private static boolean isInstallStepSelected(int stepNo) {
        return stepNo <= installStepBoxes.size() && installStepBox(stepNo).checkBox.isSelected();
    }

    private static void setAllInstallStepsDisabled(boolean disabled) {
        for (CustomInstallStepHbox stepBox : installStepBoxes) {
            stepBox.checkBox.setDisable(disabled);
        }
    }

    private static void restoreInstallStepDisabledState() {
        if (activeProvider == null || installStepBoxes.isEmpty()) {
            return;
        }
        List<RemoteInstallStepSpec> stepSpecs = activeProvider.buildInstallStepSpecs();
        int count = Math.min(installStepBoxes.size(), stepSpecs.size());
        for (int index = 0; index < count; index++) {
            installStepBoxes.get(index).checkBox.setDisable(stepSpecs.get(index).disabled());
        }
    }

    private static void setInstallStepIconVisible(int stepNo, boolean visible) {
        if (stepNo <= installStepBoxes.size()) {
            installStepBox(stepNo).iconLabel.setVisible(visible);
        }
    }

    private static void hideAllInstallStepIcons() {
        for (CustomInstallStepHbox stepBox : installStepBoxes) {
            stepBox.iconLabel.setVisible(false);
        }
    }

    private static void setInstallStepDisabledRange(int fromStep, int toStep, boolean disabled) {
        if (installStepBoxes.isEmpty()) {
            return;
        }
        int start = Math.max(1, fromStep);
        int end = Math.min(toStep, installStepBoxes.size());
        for (int stepNo = start; stepNo <= end; stepNo++) {
            installStepBox(stepNo).checkBox.setDisable(disabled);
        }
    }

    private static boolean remoteFileExists(String filePath) throws JSchException, InterruptedException {
        // Handle space-separated paths (e.g. Oracle 11g two-zip packages).
        // Every file in the list must exist.
        if (filePath == null || filePath.isBlank()) return false;
        for (String p : filePath.split("\\s+")) {
            if (p.isEmpty()) continue;
            if (executeCommandWithExitStatus("test -f " + shellQuote(p)) != 0) return false;
        }
        return true;
    }

    private static RemoteInstallExecutionContext buildInstallExecutionContext() {
        return new RemoteInstallExecutionContext(
                remoteClient,
                toRemoteInstallFields(installConfigItems),
                remoteFilePath,
                hostname == null ? hostField.getText() : hostname,
                machineInfo,
                osInfo,
                kernelInfo,
                cpuInfo,
                memInfo,
                diskInfo,
                fileSystemInfo,
                collectSelectedInstallSteps()
        );
    }

    private static Set<Integer> collectSelectedInstallSteps() {
        Set<Integer> selectedSteps = new LinkedHashSet<>();
        for (int stepNo = 1; stepNo <= installStepBoxes.size(); stepNo++) {
            if (isInstallStepSelected(stepNo)) {
                selectedSteps.add(stepNo);
            }
        }
        return selectedSteps;
    }

    private static void applyDiskSizeDefaults(ObservableList<InstallConfigItem> source, double availableDiskSize) {
        List<RemoteInstallField> fields = toRemoteInstallFields(source);
        activeProvider.applyDiskSizeDefaults(fields, availableDiskSize);
        Map<String, RemoteInstallField> fieldMap = fields.stream()
                .collect(Collectors.toMap(RemoteInstallField::getId, field -> field, (left, right) -> left));
        for (InstallConfigItem item : source) {
            RemoteInstallField field = fieldMap.get(item.id);
            if (field != null) {
                item.value = field.getValue();
            }
        }
    }

    private static ObservableList<InstallConfigItem> buildDefaultInstallConfigItems() {
        double totalMem;
        int numCpu = 1;
        try {
            freeDiskSize = Double.parseDouble(executeCommand("df -m /opt |tail -1 |awk '{print $4/1000}'"));
            totalMem = Double.parseDouble(executeCommand("free -m |sed -n 2p |awk '{print $2/1024}'"));
            numCpu = Integer.parseInt(executeCommand("cat /proc/cpuinfo |grep -c processor"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<RemoteInstallField> fields = activeProvider.buildDefaultInstallFields(
                new RemoteHostProfile(freeDiskSize, totalMem, numCpu)
        );
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException("No install fields defined for provider: " + activeProviderName());
        }
        return toInstallConfigItems(fields);
    }

    public static void modifyEnv(){

        ObservableList<InstallConfigItem> datalist = installConfigItems.stream()
                .map(InstallConfigItem::new)
                .collect(Collectors.toCollection(FXCollections::observableArrayList));

        CustomTableView<InstallConfigItem> tableView = new CustomTableView<>();
        tableView.setEditable(true);
        tableView.setSortPolicy((param) -> false);//禁用排序

        TableColumn<InstallConfigItem, Object> nameColumn = new TableColumn<>();
        nameColumn.textProperty().bind(I18n.bind("remote.install.modifyenv.column.name", "配置项"));
        nameColumn.setCellFactory(col -> new CustomLostFocusCommitTableCell<InstallConfigItem, Object>());
        nameColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().name));
        nameColumn.setReorderable(false); // 禁用拖动
        nameColumn.setEditable(false);
        nameColumn.setReorderable(false);
        nameColumn.setPrefWidth(150);
        TableColumn<InstallConfigItem, Object> valueColumn = new TableColumn<>();
        valueColumn.textProperty().bind(I18n.bind("remote.install.modifyenv.column.value", "值（可修改）"));
        valueColumn.setCellFactory(col -> new CustomLostFocusCommitTableCell<InstallConfigItem, Object>());
        valueColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().value));
        valueColumn.setReorderable(false); // 禁用拖动
        valueColumn.setEditable(true);
        valueColumn.setReorderable(false);
        valueColumn.setPrefWidth(120);

        valueColumn.setOnEditCommit(event -> {
            Object oldvalue = (Object) event.getOldValue();

            // 获取当前行的模型数据（ObservableList<String>）
            InstallConfigItem rowData = event.getRowValue();

            // 获取编辑后的新值
            Object newValue = event.getNewValue();

            // 更新ObservableList中索引1的位置（与cellValueFactory对应）
            if (rowData != null) {
                rowData.value = newValue == null ? "" : newValue.toString();
                if ("data_file_path".equals(rowData.id)) {
                    try {
                        String remoteScript =
                                "path=" + shellQuote(String.valueOf(newValue)) + ";" +
                                        "while [ ! -e \"$path\" ]; do " +
                                        "  path=$(dirname \"$path\"); " +
                                        "  [ \"$path\" = \"/\" ] && break; " +
                                        "done;" +
                                        "df -m \"$path\" |tail -1 |awk '{print $4/1000}'";
                        freeDiskSize= Double.valueOf(executeCommand(remoteScript));
                        //System.out.println("checkResult is:"+checkResult);
                        /*
                        if((executeCommandWithExitStatus("test -d "+newValue) == 0)) {
                            diskSize = Double.parseDouble(executeCommand("df -m " + newValue + " |tail -1 |awk '{print $4/1000}'"));
                        }else{
                            event.getRowValue().set(2, oldvalue == null ? null : oldvalue.toString());
                            event.getTableView().refresh();
                        }

                         */
                    } catch (Exception e) {
                        rowData.value = oldvalue == null ? null : oldvalue.toString();
                        event.getTableView().refresh();
                        log.error("Operation failed", e);
                        throw new RuntimeException(e);
                    }
                    //diskSize=1024000000.0;
                    applyDiskSizeDefaults(datalist, freeDiskSize);
                }
                tableView.refresh();
            }
        });

        TableColumn<InstallConfigItem, Object> labelColumn = new TableColumn<>();
        labelColumn.textProperty().bind(I18n.bind("remote.install.modifyenv.column.desc", "说明"));
        labelColumn.setCellFactory(col -> new CustomLostFocusCommitTableCell<InstallConfigItem, Object>());
        labelColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().description));
        labelColumn.setReorderable(false); // 禁用拖动
        labelColumn.setEditable(false);
        labelColumn.setReorderable(false);
        labelColumn.setPrefWidth(420);

        tableView.getColumns().addAll(nameColumn, valueColumn,labelColumn);
        tableView.setItems(datalist);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        VBox contentBox = new VBox();
        contentBox.setAlignment(Pos.TOP_LEFT);
        contentBox.getChildren().add(tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);
        tableView.setMaxHeight(Double.MAX_VALUE);

        ButtonType buttonTypeReset = new ButtonType(I18n.t("remote.install.modifyenv.button.reset", "重置"), ButtonBar.ButtonData.LEFT);
        ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("remote.install.modifyenv.title", "自定义配置"),
                contentBox,
                760,
                600,
                buttonTypeReset,
                buttonTypeOk,
                buttonTypeCancel
        );
        Button resetButton = dialog.getButton(buttonTypeReset);
        Button button = dialog.getButton(buttonTypeOk);
        Button cancelButton = dialog.getButton(buttonTypeCancel);
        resetButton.textProperty().bind(I18n.bind("remote.install.modifyenv.button.reset", "重置"));
        button.textProperty().bind(I18n.bind("common.confirm", "确认"));
        cancelButton.textProperty().bind(I18n.bind("common.cancel", "取消"));
        resetButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            tableView.edit(-1, null);
            try {
                datalist.setAll(buildDefaultInstallConfigItems());
                tableView.getSelectionModel().clearSelection();
                tableView.refresh();
            } catch (RuntimeException e) {
                log.error("Reset install config failed", e);
                AlertUtil.showAlert(
                        I18n.t("remote.install.modifyenv.title", "自定义配置"),
                        I18n.t("remote.install.modifyenv.reset.failed", "恢复默认值失败，请检查远程环境后重试")
                );
            }
        });
        ButtonType result = dialog.showAndWait();
        if (result == buttonTypeOk) {
            installConfigItems.setAll(datalist.stream().map(InstallConfigItem::new).toList());
        }

    }

    public static void initConfigList(){
        installConfigItems.setAll(buildDefaultInstallConfigItems());
    }

    private static ObservableList<InstallConfigItem> toInstallConfigItems(List<RemoteInstallField> fields) {
        ObservableList<InstallConfigItem> items = FXCollections.observableArrayList();
        if (fields == null) {
            return items;
        }
        for (RemoteInstallField field : fields) {
            if (field != null) {
                items.add(new InstallConfigItem(field.getId(), field.getLabel(), field.getValue(), field.getDescription()));
            }
        }
        return items;
    }

    private static List<RemoteInstallField> toRemoteInstallFields(ObservableList<InstallConfigItem> source) {
        List<RemoteInstallField> fields = new ArrayList<>();
        if (source == null) {
            return fields;
        }
        for (InstallConfigItem item : source) {
            fields.add(new RemoteInstallField(item.id, item.name, item.value, item.description));
        }
        return fields;
    }


    private static void saveInstallResultToDesktop() {
        if (databaseInfoArea == null) return;
        String text = databaseInfoArea.getText();
        if (text == null || text.isBlank()) return;

        String desktopPath = System.getProperty("user.home") + File.separator + "Desktop";
        File desktopDir = new File(desktopPath);
        if (!desktopDir.exists()) {
            desktopPath = FileSystemView.getFileSystemView().getHomeDirectory().getAbsolutePath();
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = activeProviderName() + "_" + I18n.t("remote.install.result.file_suffix", "install_info")
                + "_" + timestamp + ".txt";
        File saveFile = new File(desktopPath, fileName);

        try {
            Files.writeString(Paths.get(saveFile.getAbsolutePath()), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to save install result to desktop", e);
            Platform.runLater(() -> AlertUtil.showAlert(
                    I18n.t("common.error", "Error"),
                    I18n.t("remote.install.result.save_failed", "Failed to save install info to desktop: %s").formatted(e.getMessage())
            ));
            return;
        }

        File savedFileRef = saveFile;
        Platform.runLater(() -> {
            String msg = I18n.t("remote.install.result.saved_to_desktop",
                    "Install info saved to desktop: %s").formatted(savedFileRef.getName());
            AlertUtil.showAlert(I18n.t("remote.install.title.product.format",
                    "%s Install Wizard").formatted(activeProviderName()), msg);
        });
    }



}
