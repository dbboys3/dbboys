package com.dbboys.util;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.app.Main;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Connect;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import com.jcraft.jsch.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.filechooser.FileSystemView;
import java.io.*;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public class RemoteInstallerUtil {
    private static final Logger log = LogManager.getLogger(RemoteInstallerUtil.class);
    private static final int TOTAL_STEPS = 5;
    private static final double DIALOG_WIDTH = 600;
    private static final double DIALOG_HEIGHT = 400;
    private static final JSch JSCH = new JSch();
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


    private static Session session;

    //安装面板
    private static CustomInstallStepHbox customInstallStepHbox1;
    private static CustomInstallStepHbox customInstallStepHbox2;
    private static CustomInstallStepHbox customInstallStepHbox3;
    private static CustomInstallStepHbox customInstallStepHbox4;
    private static CustomInstallStepHbox customInstallStepHbox5;
    private static CustomInstallStepHbox customInstallStepHbox6;
    private static CustomInstallStepHbox customInstallStepHbox7;
    private static CustomInstallStepHbox customInstallStepHbox8;
    private static CustomInstallStepHbox customInstallStepHbox9;
    private static CustomInstallStepHbox customInstallStepHbox10;
    private static CustomInstallStepHbox customInstallStepHbox11;
    private static CustomInstallStepHbox customInstallStepHbox12;
    private static CustomInstallStepHbox customInstallStepHbox13;
    private static CustomInstallStepHbox customInstallStepHbox14;


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
    private static final Map<ConfigKey, InstallConfigItem> installConfigMap = new EnumMap<>(ConfigKey.class);

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

    private enum ConfigKey {
        GBASEDBT_PASSWORD("gbasedbt_password"),
        GBASEDBTDIR("gbasedbtdir"),
        GBASEDBTSERVER("gbasedbtserver"),
        DB_LOCALE("db_locale"),
        GL_USEGLU("gl_useglu"),
        DATA_FILE_PATH("data_file_path"),
        ROOTSIZE("rootsize"),
        LISTEN_IP("listen_ip"),
        LISTEN_PORT("listen_port"),
        PHYSFILE("physfile"),
        LOGSIZE("logsize"),
        LOGFILES("logfiles"),
        TEMPDBS("tempdbs"),
        SBSPACE_SIZE("sbspace_size"),
        DATA_SPACE_SIZE("data_space_size"),
        DEFAULT_DB_NAME("default_db_name"),
        LOCKS("locks"),
        DS_TOTAL_MEMORY("ds_total_memory"),
        DS_NONPDQ_QUERY_MEM("ds_nonpdq"),
        SHMVIRTSIZE("shmvirtsize"),
        SHMADD("shmadd"),
        VPCLASS("vpclass"),
        BUFFERPOOL_2K("bufferpool_2k"),
        BUFFERPOOL_16K("bufferpool_16k"),
        BACKUP_PATH("backup_path");

        private final String id;

        ConfigKey(String id) {
            this.id = id;
        }
    }


    // 入口方法：启动向导
    public static void startWizard(Stage parent) {
        initMainDialog(parent);
        currentStep.set(1);
        updateWizardState();
        mainDialog.showAndWait();
    }

    // 初始化主对话框
    private static void initMainDialog(Stage parent) {
        mainDialog = new Stage(StageStyle.UNDECORATED);
        mainDialog.initModality(Modality.APPLICATION_MODAL);
        mainDialog.initOwner(parent);
        mainDialog.setResizable(false);
        mainDialog.getIcons().add(new Image(IconPaths.MAIN_LOGO));
        mainDialogTitle = new SimpleStringProperty();
        mainDialogTitle.bind(Bindings.createStringBinding(
                () -> I18n.t("remote.install.title.format", "远程安装向导 - 步骤 %d/%d")
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
        stopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        Tooltip stopTooltip = new Tooltip();
        stopTooltip.textProperty().bind(I18n.bind("remote.install.tooltip.stop", "停止当前任务"));
        stopButton.setTooltip(stopTooltip);
        runningLabel=new Label("");
        HBox imageHBox = new HBox(imageView, runningLabel, stopButton);
        imageHBox.setStyle("-fx-background-color: rgb(58, 58, 60);-fx-background-radius: 2;-fx-padding: 0 0 0 5");
        imageHBox.setAlignment(Pos.CENTER);
        imageHBox.setMaxHeight(15);
        //imageHBox.setMaxWidth(100);
        stopButton.setFocusTraversable(false);
        stopButton.getStyleClass().add("small");
        backgroundHBox = new HBox(imageHBox);
        backgroundHBox.setAlignment(Pos.CENTER);
        backgroundHBox.setStyle("-fx-background-color: rgba(0, 0, 0, 0.3);-fx-background-radius: 2;");
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
        CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.create(
                mainDialog,
                mainDialogTitle,
                mainDialogPane,
                DIALOG_WIDTH,
                DIALOG_HEIGHT,
                null,
                false,
                false,
                false
        );
        mainDialog.setScene(frame.scene);
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
                        //ipaddr_textfield.setStyle("-fx-border-color: #ff0000;-fx-border-radius: 3");
                        hostField.requestFocus();
                    }
                    else if(portField.getText().trim().isEmpty()){
                        event.consume();
                        //port_textfield.setStyle("-fx-border-color: #ff0000;-fx-border-radius: 3");
                        portField.requestFocus();
                    }
                    else if(passField.getText().trim().isEmpty()){
                        event.consume();
                        // username_textfield.setStyle("-fx-border-color: #ff0000;-fx-border-radius: 3");
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
                                        // 收集系统信息
                                        machineInfo = executeCommand("dmidecode -s system-product-name");
                                        if (isCommandExists("nkvers")) {
                                            osInfo = executeCommand("nkvers");
                                        } else if (executeCommandWithExitStatus("test -f /etc/redhat-release") == 0) {
                                            osInfo = executeCommand("cat /etc/redhat-release");
                                        } else {
                                            osInfo = executeCommand("cat /etc/os-release");
                                        }
                                        cpuInfo = executeCommand("lscpu");
                                        memInfo = executeCommand("free -h");
                                        fileSystemInfo = executeCommand("df -h");
                                        diskInfo = executeCommand("lsblk");
                                        kernelInfo = executeCommand("uname -a");
                                        // 显示信息
                                        Platform.runLater(() -> {
                                            systemInfoArea.replaceText("");

                                            systemInfoArea.append(I18n.t("remote.install.info.machine", "服务器型号") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(machineInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            // 省略其他信息的显示代码（与原逻辑相同）
                                            systemInfoArea.append(I18n.t("remote.install.info.os", "操作系统版本") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(osInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.kernel", "内核版本") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(kernelInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.cpu", "CPU信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(cpuInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.memory", "内存信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(memInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.disk", "磁盘信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(diskInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                                            systemInfoArea.append(I18n.t("remote.install.info.filesystem", "文件系统信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                                            systemInfoArea.append(fileSystemInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");



                                            //systemInfoArea.append("内核参数\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
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
                                if (session != null && session.isConnected()) {
                                    session.disconnect();
                                }
                            });
                            cancelBtn.setOnAction(event1->{
                                systeminfoTask.cancel();
                                if (session != null && session.isConnected()) {
                                    session.disconnect();
                                }
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
                            if (session != null && session.isConnected()) {
                                session.disconnect();
                            }
                        });
                        cancelBtn.setOnAction(event1->{
                            runningTask.cancel();
                            if (session != null && session.isConnected()) {
                                session.disconnect();
                            }
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
                        }else if(systemInfoArea.getText().contains("x86_64")&&!installFilePathField.getText().contains("x86_64")){
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
                                        channelSftp = (ChannelSftp) session.openChannel("sftp");
                                        channelSftp.connect();

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
                                if (session != null && session.isConnected()) {
                                    session.disconnect();
                                }
                            });
                            cancelBtn.setOnAction(event1->{
                                runningTask.cancel();
                                if (session != null && session.isConnected()) {
                                    session.disconnect();
                                }
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
                                }else if(systemInfoArea.getText().contains("x86_64")&&!remoteFilePath.contains("x86_64")){
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
                            // 任务开始后禁用所有复选框，防止中途修改
                            Platform.runLater(() -> {
                                customInstallStepHbox1.checkBox.setDisable(true);
                                customInstallStepHbox2.checkBox.setDisable(true);
                                customInstallStepHbox3.checkBox.setDisable(true);
                                customInstallStepHbox4.checkBox.setDisable(true);
                                customInstallStepHbox5.checkBox.setDisable(true);
                                customInstallStepHbox6.checkBox.setDisable(true);
                                customInstallStepHbox7.checkBox.setDisable(true);
                                customInstallStepHbox8.checkBox.setDisable(true);
                                customInstallStepHbox9.checkBox.setDisable(true);
                                customInstallStepHbox10.checkBox.setDisable(true);
                                customInstallStepHbox11.checkBox.setDisable(true);
                                customInstallStepHbox12.checkBox.setDisable(true);
                                customInstallStepHbox13.checkBox.setDisable(true);
                                customInstallStepHbox14.checkBox.setDisable(true);
                            });
                            boolean run1 = customInstallStepHbox1.checkBox.isSelected();
                            boolean run2 = customInstallStepHbox2.checkBox.isSelected();
                            boolean run3 = customInstallStepHbox3.checkBox.isSelected();
                            boolean run4 = customInstallStepHbox4.checkBox.isSelected();
                            boolean run5 = customInstallStepHbox5.checkBox.isSelected();
                            boolean run6 = customInstallStepHbox6.checkBox.isSelected();
                            boolean run7 = customInstallStepHbox7.checkBox.isSelected();
                            boolean run8 = customInstallStepHbox8.checkBox.isSelected();
                            boolean run9 = customInstallStepHbox9.checkBox.isSelected();
                            boolean run10 = customInstallStepHbox10.checkBox.isSelected();
                            boolean run11 = customInstallStepHbox11.checkBox.isSelected();
                            boolean run12 = customInstallStepHbox12.checkBox.isSelected();
                            boolean run13 = customInstallStepHbox13.checkBox.isSelected();
                            boolean run14 = customInstallStepHbox14.checkBox.isSelected();
                            if (run1) {
                                Platform.runLater(() -> {
                                    customInstallStepHbox1.iconLabel.setVisible(true);
                                    runningLabel.setText(" " + I18n.t("remote.install.status.installing", "正在安装..."));
                                });
                                executeCommandWithExitStatus("ps -ef |grep gbasedbt |grep -v grep |awk '{print \"kill -9 \"$2}' |sh");
                                executeCommandWithExitStatus("find / -user gbasedbt -exec rm -rf {} +");
                                if((executeCommandWithExitStatus("test -f /GBASEDBTTMP/.infxdirs") == 0)){
                                    if(executeCommandWithExitStatus("cat /GBASEDBTTMP/.infxdirs  |awk '{print \"rm -rf \"$1}'|sh")!=0) {
                                        throw new Exception(I18n.t("remote.install.error.cleanup_install_dir", "删除数据库安装目录失败！"));
                                    }
                                    if(executeCommandWithExitStatus("rm -rf /GBASEDBTTMP")!=0) {
                                        throw new Exception(I18n.t("remote.install.error.cleanup_gbasedbttmp", "删除目录/GBASEDBTTMP失败！"));
                                    }
                                }
                                if((executeCommandWithExitStatus("test -d /opt/gbase") == 0)){
                                    if(executeCommandWithExitStatus("rm -rf /opt/gbase")!=0) {
                                        throw new Exception(I18n.t("remote.install.error.cleanup_opt_gbase", "删除数据库安装目录/opt/gbase失败！"));
                                    }
                                }
                                if((executeCommandWithExitStatus("test -d /etc/gbasedbt") == 0)) {
                                    if(executeCommandWithExitStatus("rm -rf /etc/gbasedbt")!=0) {
                                        throw new Exception(I18n.t("remote.install.error.cleanup_etc_gbasedbt", "删除/etc/gbasedbt目录失败！"));
                                    }
                                }
                                executeCommandWithExitStatus("crontab -u gbasedbt -r"); //如果不存在定时任务，返回1
                            
                                //麒麟系统可能有下面的错误
                                if((executeCommandWithExitStatus("test -f /usr/lib64/libnsl.so.1") != 0)) {
                                    executeCommandWithExitStatus("ln -s /usr/lib64/libnsl.so.2  /usr/lib64/libnsl.so.1");
                                }
                                if((executeCommandWithExitStatus("id gbasedbt") == 0)) {
                                    if(executeCommandWithExitStatus("userdel -r -f gbasedbt")!=0) {
                                        throw new Exception(I18n.t("remote.install.error.delete_gbasedbt_user", "删除用户gbasedbt失败！"));
                                    }
                                }
                                Platform.runLater(() -> customInstallStepHbox1.iconLabel.setVisible(false));
                            
                            } 
                            //卸载完成，开始系统检查
                            if(run2){
                                Platform.runLater(() -> {
                                    customInstallStepHbox2.iconLabel.setVisible(true);
                                });

                                if(executeCommandWithExitStatus("chown root:root /opt&&chmod 755 /opt")!=0) {
                                    throw new Exception(I18n.t("remote.install.error.reset_opt_perm", "更改/opt为默认权限失败！"));
                                }

                                if(Double.parseDouble(executeCommand("df -m /opt |tail -1 |awk '{print $4/1000}'"))<8) {
                                    throw new Exception(I18n.t("remote.install.error.opt_space_low", "空间检查不通过，最小要求/opt可用空间小于8G！"));
                                }
                                if(Double.parseDouble(executeCommand("df -m /tmp |tail -1 |awk '{print $4/1000}'"))<1) {
                                    throw new Exception(I18n.t("remote.install.error.tmp_space_low", "空间检查不通过，最小要求/tmp可用空间小于1G！"));
                                }
                                if(executeCommandWithExitStatus("stat -c \"%a\" /tmp | grep -q '777'")!=0) {
                                    executeCommandWithExitStatus("chmod 777 /tmp");
                                }
                                if(Double.parseDouble(executeCommand("free -m |sed -n 2p |awk '{print $2/1024}'"))<1) {
                                    throw new Exception(I18n.t("remote.install.error.memory_low", "内存检查不通过，最小要求1G！"));
                                }
                                if((executeCommandWithExitStatus("command -v unzip") != 0)) {
                                    throw new Exception(I18n.t("remote.install.error.unzip_missing", "系统缺失unzip！"));
                                }
                                if((executeCommandWithExitStatus("systemctl stop firewalld.service") != 0)) {
                                    executeCommandWithExitStatus("service iptables stop");
                                }
                                if((executeCommandWithExitStatus("systemctl disable firewalld.service") != 0)) {
                                    executeCommandWithExitStatus("chkconfig iptables off");
                                }

                                executeCommandWithExitStatus("sed -i \"s#^hosts.*#hosts:      files#g\" /etc/nsswitch.conf");
                                executeCommandWithExitStatus("sed -i \"s#^SELINUX=.*#SELINUX=disabled#g\" /etc/selinux/config");

                                executeCommandWithExitStatus("sed -i \"s/^#RemoveIPC.*/RemoveIPC=no/g\" /etc/systemd/logind.conf");
                                executeCommandWithExitStatus("systemctl daemon-reload");
                                executeCommandWithExitStatus("systemctl restart systemd-logind");

                                executeCommandWithExitStatus("sed -i '/^[[:space:]]*\\*[[:space:]]\\+\\(soft\\|hard\\)[[:space:]]/d' /etc/security/limits.conf");
                                executeCommandWithExitStatus("sed -i '/^[[:space:]]*\\*[[:space:]]\\+\\(soft\\|hard\\)[[:space:]]/d' /etc/security/limits.d/20-nproc.conf");
                                executeCommandWithExitStatus("echo \"* soft nproc 1048576\">> /etc/security/limits.conf");
                                executeCommandWithExitStatus("echo \"* hard nproc 1048576\">> /etc/security/limits.conf");
                                executeCommandWithExitStatus("echo \"* soft nofile 1048576\">> /etc/security/limits.conf");
                                executeCommandWithExitStatus("echo \"* hard nofile 1048576\">> /etc/security/limits.conf");

                                executeCommandWithExitStatus("sed -i \"/^kernel.shmmni.*/d\" /etc/sysctl.conf");
                                executeCommandWithExitStatus("sed -i \"/^kernel.shmmax.*/d\" /etc/sysctl.conf");
                                executeCommandWithExitStatus("sed -i \"/^kernel.shmall.*/d\" /etc/sysctl.conf");
                                executeCommandWithExitStatus("sed -i \"/^kernel.sem.*/d\" /etc/sysctl.conf");

                                executeCommandWithExitStatus("echo \"kernel.shmmni=4096\">> /etc/sysctl.conf");
                                executeCommandWithExitStatus("echo \"kernel.shmmax=18446744073709547520\">> /etc/sysctl.conf");
                                executeCommandWithExitStatus("echo \"kernel.shmall=18446744073709547520\">> /etc/sysctl.conf");
                                executeCommandWithExitStatus("echo \"kernel.sem=32000 1024000000  500 32000\" >>/etc/sysctl.conf");
                                executeCommandWithExitStatus("sysctl -p");
                                Platform.runLater(() -> {
                                    customInstallStepHbox2.iconLabel.setVisible(false);
                                });
                            }

                            //系统检查完成，开始创建用户和组
                            if(run3){
                                Platform.runLater(() -> {
                                    customInstallStepHbox3.iconLabel.setVisible(true);
                                });
                                System.out.println("change password before");
                                String password=configValue(ConfigKey.GBASEDBT_PASSWORD);
                                System.out.println("change password after");
                                if((executeCommandWithExitStatus("groupadd gbasedbt&&useradd gbasedbt -d /home/gbasedbt -m -g gbasedbt&&echo \"gbasedbt:"+password+"\" | chpasswd") != 0)) {
                                    throw new Exception(I18n.t("remote.install.error.create_user_group_failed", "创建gbasedbt用户或组失败！"));
                                }
                                System.out.println("change password after1");

                                executeCommandWithExitStatus("cat >>~gbasedbt/.bash_profile << EOF\nexport GBASEDBTDIR="+configValue(ConfigKey.GBASEDBTDIR)+
                                        "\nexport GBASEDBTSERVER="+configValue(ConfigKey.GBASEDBTSERVER)+
                                        "\nexport ONCONFIG=onconfig."+configValue(ConfigKey.GBASEDBTSERVER)+
                                        "\nexport GBASEDBTSQLHOSTS=\\$GBASEDBTDIR/etc/sqlhosts."+configValue(ConfigKey.GBASEDBTSERVER)+
                                        "\nexport DB_LOCALE="+configValue(ConfigKey.DB_LOCALE)+
                                        "\nexport CLIENT_LOCALE="+configValue(ConfigKey.DB_LOCALE)+
                                        "\nexport GL_USEGLU="+configValue(ConfigKey.GL_USEGLU)+
                                        "\nexport PATH=\\$GBASEDBTDIR/bin:/usr/bin:\\${PATH}:.\nEOF"
                                );
                                Platform.runLater(() -> {
                                    customInstallStepHbox3.iconLabel.setVisible(false);
                                });
                            }

                            //创建用户和组完成，开始安装
                            if(run4){
                                Platform.runLater(() -> {
                                    customInstallStepHbox4.iconLabel.setVisible(true);
                                });
                                if((executeCommandWithExitStatus("tar -xvf " + shellQuote(remoteFilePath)) != 0)) {
                                    throw new Exception(I18n.t("remote.install.error.extract_package_failed", "解压安装包【%s】失败！").formatted(remoteFilePath));
                                }
                                int status=executeCommandWithExitStatus("source ~gbasedbt/.bash_profile && mkdir -p $GBASEDBTDIR && chown gbasedbt:gbasedbt $GBASEDBTDIR && ./ids_install -i silent -DLICENSE_ACCEPTED=TRUE");
                                if(status!= 0) {
                                    throw new Exception(I18n.t("remote.install.error.install_to_gbasedbtdir_failed", "安装数据库到$GBASEDBTDIR失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox4.iconLabel.setVisible(false);
                                });
                            } else {
                                Platform.runLater(() -> customInstallStepHbox4.iconLabel.setVisible(false));
                            }

                            //安装完成，开始配置并初始化
                            if(run5){
                                Platform.runLater(() -> {
                                    customInstallStepHbox5.iconLabel.setVisible(true);
                                });

                                String cmd=
                                        "source ~gbasedbt/.bash_profile &&" +
                                                "DATADIR="+configValue(ConfigKey.DATA_FILE_PATH)+"&&"+
                                                "mkdir -p ${DATADIR} &&"+
                                                "chown gbasedbt:gbasedbt ${DATADIR} &&"+
                                                "cp $GBASEDBTDIR/etc/onconfig.std  $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                                "chown gbasedbt:gbasedbt $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                                "sed -i \"s#^ROOTPATH.*#ROOTPATH ${DATADIR}/rootdbschk001#g\" $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                                "sed -i \"s#^ROOTSIZE.*#ROOTSIZE "+configValue(ConfigKey.ROOTSIZE)+"#g\" $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                                "sed -i \"s#^DBSERVERNAME.*#DBSERVERNAME $GBASEDBTSERVER#g\" $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                                "sed -i \"s#^TAPEDEV.*#TAPEDEV /dev/null#g\" $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                                "sed -i \"s#^LTAPEDEV.*#LTAPEDEV /dev/null#g\" $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                                "echo \"$GBASEDBTSERVER onsoctcp "+configValue(ConfigKey.LISTEN_IP)+" "+configValue(ConfigKey.LISTEN_PORT)+"\" >> $GBASEDBTSQLHOSTS &&"+
                                                "chown gbasedbt:gbasedbt $GBASEDBTSQLHOSTS &&"+
                                                "touch ${DATADIR}/rootdbschk001 &&"+
                                                "chown gbasedbt:gbasedbt ${DATADIR}/rootdbschk001 &&"+
                                                "chmod 660 ${DATADIR}/rootdbschk001 && oninit -ivyw";
                                if(executeCommandWithExitStatus(cmd)!=0){
                                    throw new Exception(I18n.t("remote.install.error.init_instance_failed", "初始化实例失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox5.iconLabel.setVisible(false);
                                });
                            } 

                            //初始化完成，优化配置参数
                            if(run6){
                                Platform.runLater(() -> {
                                    customInstallStepHbox6.iconLabel.setVisible(true);
                            });
                                String pramsCmd =
                                        "source ~gbasedbt/.bash_profile && " +
                                        "sed -i \"s#^PHYSBUFF.*#PHYSBUFF 2048#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^LOGBUFF.*#LOGBUFF 2048#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^NETTYPE.*#NETTYPE soctcp,4,50,NET#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^MULTIPROCESSOR.*#MULTIPROCESSOR 1#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^CLEANERS.*#CLEANERS 128#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^LOCKS.*#LOCKS " + configValue(ConfigKey.LOCKS) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^DEF_TABLE_LOCKMODE.*#DEF_TABLE_LOCKMODE row#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^DS_TOTAL_MEMORY.*#DS_TOTAL_MEMORY " + configValue(ConfigKey.DS_TOTAL_MEMORY) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^DS_NONPDQ_QUERY_MEM.*#DS_NONPDQ_QUERY_MEM " + configValue(ConfigKey.DS_NONPDQ_QUERY_MEM) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^SHMVIRTSIZE.*#SHMVIRTSIZE " + configValue(ConfigKey.SHMVIRTSIZE) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^SHMADD.*#SHMADD " + configValue(ConfigKey.SHMADD) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^STACKSIZE.*#STACKSIZE 2048#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^SBSPACENAME.*#SBSPACENAME sbspace01#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^DBSPACETEMP.*#DBSPACETEMP tempdbs01#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^VPCLASS cpu.*#VPCLASS " + configValue(ConfigKey.VPCLASS) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^TEMPTAB_NOLOG.*#TEMPTAB_NOLOG 1#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^NS_CACHE.*#NS_CACHE host=0,service=0,user=0,group=0#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^DUMPSHMEM.*#DUMPSHMEM 0#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^USERMAPPING.*#USERMAPPING ADMIN#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                                        "sed -i \"s#^BUFFERPOOL size=2k.*#BUFFERPOOL "+configValue(ConfigKey.BUFFERPOOL_2K)+ "#g\" $GBASEDBTDIR/etc/$ONCONFIG &&"+
                                        "echo \"BUFFERPOOL "+configValue(ConfigKey.BUFFERPOOL_16K)+"\">>$GBASEDBTDIR/etc/$ONCONFIG &&"+
                                        "echo \"ENABLE_NULL_STRING 0\">>$GBASEDBTDIR/etc/$ONCONFIG &&"+
                                        "touch $GBASEDBTDIR/etc/sysadmin/stop &&"+
                                        "chown gbasedbt:gbasedbt $GBASEDBTDIR/etc/sysadmin/stop &&"+
                                        "mkdir -p /etc/gbasedbt &&"+
                                        "echo \"USER:daemon\" > /etc/gbasedbt/allowed.surrogates &&"+
                                        "onmode -ky &&"+
                                        "su - gbasedbt -c \"oninit\" &&"+
                                        "echo \"CREATE DEFAULT USER WITH PROPERTIES USER 'daemon'\" |dbaccess sysuser -"
                                        ;
                                System.out.println("pramsCmd is:"+pramsCmd);
                                //System.out.println(executeCommand(pramsCmd));
                                if(executeCommandWithExitStatus(pramsCmd)!=0){
                                    throw new Exception(I18n.t("remote.install.error.tune_params_failed", "优化配置参数失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox6.iconLabel.setVisible(false);
                                });
                            }

                            if(run7){
                                Platform.runLater(() -> {
                                    customInstallStepHbox7.iconLabel.setVisible(true);
                                });
                                String cmd="source ~gbasedbt/.bash_profile && DATADIR="+configValue(ConfigKey.DATA_FILE_PATH)+ "&&"+
                                        "touch ${DATADIR}/plogdbschk001 &&"+
                                        "chown gbasedbt:gbasedbt ${DATADIR}/plogdbschk001 &&"+
                                        "chmod 660 ${DATADIR}/plogdbschk001 &&"+
                                        "onspaces -c -d plogdbs -p ${DATADIR}/plogdbschk001 -o 0 -s "+(Integer.parseInt(configValue(ConfigKey.PHYSFILE))+10000)+"&&"+
                                        "onparams -p -d plogdbs -s "+configValue(ConfigKey.PHYSFILE)+" -y";
                                System.out.println("plogcmd is:"+cmd);
                                if(executeCommandWithExitStatus(cmd)!=0){
                                    throw new Exception(I18n.t("remote.install.error.tune_physlog_failed", "优化物理日志失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox7.iconLabel.setVisible(false);
                                });
                            }
                            if(run8){
                                Platform.runLater(() -> {
                                    customInstallStepHbox8.iconLabel.setVisible(true);
                                });
                                String logicCmd =
                                        "source ~gbasedbt/.bash_profile && " +
                                        "DATADIR=" + configValue(ConfigKey.DATA_FILE_PATH) + " && " +
                                        "touch ${DATADIR}/llogdbschk001 && " +
                                        "chown gbasedbt:gbasedbt ${DATADIR}/llogdbschk001 && " +
                                        "chmod 660 ${DATADIR}/llogdbschk001 && " +
                                        "onspaces -c -d llogdbs -p ${DATADIR}/llogdbschk001 -o 0 -s " +
                                        (Integer.parseInt(configValue(ConfigKey.LOGSIZE)) * Integer.parseInt(configValue(ConfigKey.LOGFILES)) + 10240) + " && " +
                                        "for i in `seq " + Integer.parseInt(configValue(ConfigKey.LOGFILES)) + "`;do onparams -a -d llogdbs -s " +
                                        Integer.parseInt(configValue(ConfigKey.LOGSIZE)) + ";done && " +
                                        "for i in `seq 7`;do onmode -l;done && " +
                                        "onmode -c && " +
                                        "for i in `seq 6`;do onparams -d -l $i -y;done";
                                System.out.println("logiccmd is:"+logicCmd);
                                if(executeCommandWithExitStatus(logicCmd)!=0){
                                    throw new Exception(I18n.t("remote.install.error.tune_logical_log_failed", "优化逻辑日志失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox8.iconLabel.setVisible(false);
                                });
                            }
                            
                            if(run9){

                                Platform.runLater(() -> {
                                    customInstallStepHbox9.iconLabel.setVisible(true);
                                });
                                String[] parts = configValue(ConfigKey.TEMPDBS).split("\\*");
                                int tempdbsNum = Integer.parseInt(parts[0]);
                                int tempdbsSize = Integer.parseInt(parts[1]);
                                String onspaceCmd="";
                                String dbspaceTemp="tempdbs01";
                                for (int num = 1; num < tempdbsNum+1; num++) {
                                    onspaceCmd+=("touch ${DATADIR}/tempdbs"+String.format("%02d", num)+"chk001 &&" +
                                            "chown gbasedbt:gbasedbt ${DATADIR}/tempdbs"+String.format("%02d", num)+"chk001 &&" +
                                            "chmod 660 ${DATADIR}/tempdbs"+String.format("%02d", num)+"chk001 &&" +
                                            "onspaces -c -d tempdbs"+String.format("%02d", num)+" -p ${DATADIR}/tempdbs"+String.format("%02d", num)+"chk001 -o 0 -s "+tempdbsSize+" -k 16 -t &&");
                                    if(num>1) dbspaceTemp+=(",tempdbs"+String.format("%02d", num));
                                };
                                String tempdbsCmd="source ~gbasedbt/.bash_profile && DATADIR="
                                        +configValue(ConfigKey.DATA_FILE_PATH)+"&&"+onspaceCmd+ "onmode -wf DBSPACETEMP="+dbspaceTemp;
                                System.out.println("tempdbsCmd is:"+tempdbsCmd);
                                if(executeCommandWithExitStatus(tempdbsCmd)!=0){
                                    throw new Exception(I18n.t("remote.install.error.tune_temp_space_failed", "优化临时空间失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox9.iconLabel.setVisible(false);
                                });
                            }


                            if(run10){
                                Platform.runLater(() -> {
                                    customInstallStepHbox10.iconLabel.setVisible(true);
                                });
                                String sbspacecmd = "source ~gbasedbt/.bash_profile && " +
                                        "DATADIR=" + configValue(ConfigKey.DATA_FILE_PATH) + " && " +
                                        "touch ${DATADIR}/sbspace01chk001 && " +
                                        "chown gbasedbt:gbasedbt ${DATADIR}/sbspace01chk001 && " +
                                        "chmod 660 ${DATADIR}/sbspace01chk001 && " +
                                        "onspaces -c -S sbspace01 -p ${DATADIR}/sbspace01chk001 -o 0 -s " +
                                        configValue(ConfigKey.SBSPACE_SIZE) + " " +
                                        "-Df \"LOGGING = ON, AVG_LO_SIZE=1\"";
                                        System.out.println("sbspacecmd is:"+sbspacecmd);
                                if(executeCommandWithExitStatus(sbspacecmd)!=0){
                                    throw new Exception(I18n.t("remote.install.error.create_sbspace_failed", "创建智能大对象空间失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox10.iconLabel.setVisible(false);
                                });
                            }

                            if(run11){

                                Platform.runLater(() -> {
                                    customInstallStepHbox11.iconLabel.setVisible(true);
                                });
                                if(executeCommandWithExitStatus("""
                                        source ~gbasedbt/.bash_profile &&\
                                        DATADIR="""
                                        +configValue(ConfigKey.DATA_FILE_PATH)+
                                        """ 
                                        &&\
                                        touch ${DATADIR}/datadbs01chk001 &&\
                                        chown gbasedbt:gbasedbt ${DATADIR}/datadbs01chk001 &&\
                                        chmod 660 ${DATADIR}/datadbs01chk001 &&\
                                        onspaces -c -d datadbs01 -p ${DATADIR}/datadbs01chk001 -o 0 -s """
                                        +" "+configValue(ConfigKey.DATA_SPACE_SIZE)+ " "+
                                        """
                                        -k 16
                                        """)!=0){
                                    throw new Exception(I18n.t("remote.install.error.create_userdbspace_failed", "创建用户数据库空间失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox11.iconLabel.setVisible(false);
                                });
                            }

                            if(run12){
                            
                                Platform.runLater(() -> {
                                    customInstallStepHbox12.iconLabel.setVisible(true);
                                });
                                if(executeCommandWithExitStatus("""
                                        source ~gbasedbt/.bash_profile &&\
                                        echo "create database """
                                        +" "+configValue(ConfigKey.DEFAULT_DB_NAME)+ " "+
                                        """
                                        in datadbs01 with log" |dbaccess - -
                                        """)!=0){
                                    throw new Exception(I18n.t("remote.install.error.create_default_db_failed", "创建默认数据库失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox12.iconLabel.setVisible(false);
                                });
                            }

                            if(run13){
                                Platform.runLater(() -> {
                                    customInstallStepHbox13.iconLabel.setVisible(true);
                                });
                                if(executeCommandWithExitStatus("""
                                        chmod +x /etc/rc.d/rc.local &&\
                                        sed -i '/^su - gbasedbt/d' /etc/rc.local &&\
                                        echo "su - gbasedbt -c \\"oninit\\"" >>/etc/rc.local
                                        """)!=0){
                                    throw new Exception(I18n.t("remote.install.error.enable_autostart_failed", "配置开启自启动失败！"));
                                }
                                Platform.runLater(() -> {
                                customInstallStepHbox13.iconLabel.setVisible(false);
                                });
                            }

                            executeCommandWithExitStatus("""
                                    source ~gbasedbt/.bash_profile &&
                                    mkdir -p $GBASEDBTDIR/scripts &&
                                    chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts &&
                                    touch $GBASEDBTDIR/scripts/backup.sh &&
                                    chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts/backup.sh &&
                                    chmod 775 $GBASEDBTDIR/scripts/backup.sh &&
                                    cat <<EOF >$GBASEDBTDIR/scripts/backup.sh
                                    #!/bin/bash
                                    . ~gbasedbt/.bash_profile
                                    onstat - |grep "On-Line" >/dev/null
                                    if [ \\$? -ne 1 ]
                                    then
                                    DATE=\\`date\\`
                                    echo "Level 0 backup of "\\$GBASEDBTSERVER" strat at "\\$DATE
                                    ontape -s -L 0
                                    DATE=\\`date\\`
                                    echo "Level 0 backup of "\\$GBASEDBTSERVER" completed at "\\$DATE
                                    TAPEDEV=\\`onstat -c|grep ^TAPEDEV |awk '{print \\$2}'\\`
                                    find \\${TAPEDEV} -mtime +7 -type f ! -name *.sh ! -name *.log |xargs rm -rf
                                    fi
                                    exit 0
                                    EOF
                                    """);
executeCommandWithExitStatus("""
source ~gbasedbt/.bash_profile &&\
touch $GBASEDBTDIR/scripts/GBase8schk.sh &&\
chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts/GBase8schk.sh &&\
chmod 775 $GBASEDBTDIR/scripts/GBase8schk.sh &&\
cat <<GBASEEOF >$GBASEDBTDIR/scripts/GBase8schk.sh
#!/bin/bash
###################################################################################
# filename: GBase8schk.sh
# Last modified by: L3 2025-11-25
# support OS: Linux
# support database version: GBase 8s V8.x
# useage: sh GBase8schk.sh [0]
# 0 do not collect statistics,this may take a long time
###################################################################################

if [[ -n "\\${GBASEDBTSERVER}" ]]; then
    INSTANCE=\\${GBASEDBTSERVER}
elif [[ -n "\\${INFORMIXSERVER}" ]]; then
    INSTANCE=\\${INFORMIXSERVER}
else
    echo "ERROR:can't found instance name!"
    exit 1
fi

echo ""
echo "Begin to collect data for INSTANCE:"\\${INSTANCE}
echo ""
mytime=\\`date '+%Y%m%d%H%M%S'\\`
outpath="GBase8schk_\\${INSTANCE}_\\${mytime}"

if [ ! -d \\${outpath} ]; then
mkdir \\${outpath}
fi

###################################################################################
## Machine
###################################################################################
echo "collect machine info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/machine.unl delimiter '|'
select
os_name,os_release,os_nodename,os_version,os_machine,os_num_procs,os_num_olprocs,
os_pagesize,os_mem_total,os_mem_free,os_open_file_lim,os_shmmax
from  sysmachineinfo;
EOF

###################################################################################
## Instance
###################################################################################
echo "collect instance info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/instance.unl delimiter '|'
select
dbinfo('UTC_TO_DATETIME',sh_boottime)||' T' start_time,
(current year to second - dbinfo('UTC_TO_DATETIME',sh_boottime))||' T'  run_time,
sh_maxchunks as maxchunks,
sh_maxdbspaces maxdbspaces,
sh_maxuserthreads maxuserthreads,
sh_maxtrans maxtrans,
sh_maxlocks locks,
sh_longtx longtxs,
dbinfo('UTC_TO_DATETIME',sh_pfclrtime)||' T'  onstat_z_running_time
from sysshmvals;
EOF

###################################################################################
## CPUVP
###################################################################################
echo "collect cpuvp info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/cpuvp.unl delimiter '|'
select vpid,classname class,pid,round(usecs_user,2) user_cpu,round(usecs_sys,2) sys_cpu,num_ready,
total_semops,total_busy_wts,total_yields,total_spins,vp_cache_size,vp_cache_allocs
from sysvplst ;
EOF

###################################################################################
## Memory
###################################################################################
echo "collect instance memory info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/memory.unl delimiter '|'
select
indx,bufsize pagesize,
nbuffs buffers,
round(nbuffs*bufsize/1024/1024/1024,2)||'GB' buffsize,
nlrus,mindirty,maxdirty,
(bufwaits / (bufwrites + pagreads)) * 100.00 buff_wait_rate,
100 * (bufreads-dskreads)/ bufreads buff_read_rate,
100 * (bufwrites-dskwrites)/ bufwrites buff_write_rate,
fgwrites,lruwrites ,chunkwrites
from sysbufpool;
EOF

###################################################################################
## Network
###################################################################################
echo "collect sqlhosts info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sqlhosts.unl delimiter '|'
select dbsvrnm,nettype,hostname,svcname,options,
svrsecurity,netbuf_size,svrgroup
from  syssqlhosts;
EOF

###################################################################################
## Session time
###################################################################################
echo "collect session runtime info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sessiontime.unl delimiter '|'
SELECT first 500 s.sid, s.username, s.hostname, q.odb_dbname database,
dbinfo('UTC_TO_DATETIME',s.connected) conection_time,
dbinfo('UTC_TO_DATETIME',t.last_run_time) last_run_time,
current - dbinfo('UTC_TO_DATETIME',s.connected) connected_since,
current - dbinfo('UTC_TO_DATETIME',t.last_run_time) idle_time
FROM syssessions s, systcblst t, sysrstcb r, sysopendb q
WHERE t.tid = r.tid AND s.sid = r.sid AND s.sid = q.odb_sessionid
ORDER BY 8 DESC;
EOF

###################################################################################
## Session wait
###################################################################################
echo "collect session waits info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sessionwait.unl delimiter '|'
select first 20 sid,pid, username, hostname,
is_wlatch, -- blocked waiting on a latch
is_wlock, -- blocked waiting on a locked record or table
is_wbuff, -- blocked waiting on a buffer
is_wckpt, -- blocked waiting on a checkpoint
is_incrit -- session is in a critical section of transaction-- (e.g writting to disk)
from syssessions
order by  is_wlatch+is_wlock+is_wbuff+is_wckpt+is_incrit desc;
EOF

###################################################################################
## Session IO
###################################################################################
echo "collect session IO info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sessionio.unl delimiter '|'
select first 100 syssesprof.sid,isreads,iswrites,isrewrites,
isdeletes,bufreads,bufwrites,seqscans ,
pagreads ,pagwrites,total_sorts ,dsksorts  ,
max_sortdiskspace,logspused
from syssesprof, syssessions
where syssesprof.sid = syssessions.sid
order by bufreads+bufwrites desc
;
EOF

###################################################################################
## Checkpoint
###################################################################################
echo "collect checkpoint info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/checkpoint.unl delimiter '|'
select
intvl,type,caller,dbinfo('UTC_TO_DATETIME',clock_time)||' T' clock_time,
round(crit_time,4),round(flush_time,4),round(cp_time,4),n_dirty_buffs,
plogs_per_sec,llogs_per_sec,dskflush_per_sec,ckpt_logid,ckpt_logpos,physused,logused,
n_crit_waits,tot_crit_wait,longest_crit_wait,block_time
from syscheckpoint order by intvl;
EOF

###################################################################################
## Database
###################################################################################
echo "collect database info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/database.unl delimiter '|'
SELECT trim(name) dbname,trim(owner) owner, created||' T'  created_time,
TRIM(DBINFO('dbspace',partnum)) AS dbspace,
CASE WHEN is_logging+is_buff_log=1 THEN "Unbuffered logging"
     WHEN is_logging+is_buff_log=2 THEN "Buffered logging"
     WHEN is_logging+is_buff_log=0 THEN "No logging"
ELSE "" END Logging_mode
FROM sysdatabases
where trim(name) not like 'sys%';
EOF

###################################################################################
## DBspace
###################################################################################
echo "collect dbspaces info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/dbspace.unl delimiter '|'
SELECT A.dbsnum as No, trim(B.name) as name,
CASE  WHEN (bitval(B.flags,'0x10')>0 AND bitval(B.flags,'0x2')>0)
  THEN 'MirroredBlobspace'
  WHEN bitval(B.flags,'0x10')>0  THEN 'Blobspace'
  WHEN bitval(B.flags,'0x2000')>0 AND bitval(B.flags,'0x8000')>0
  THEN 'TempSbspace'
  WHEN bitval(B.flags,'0x2000')>0 THEN 'TempDbspace'
  WHEN (bitval(B.flags,'0x8000')>0 AND bitval(B.flags,'0x2')>0)
  THEN 'MirroredSbspace'
  WHEN bitval(B.flags,'0x8000')>0  THEN 'SmartBlobspace'
  WHEN bitval(B.flags,'0x2')>0    THEN 'MirroredDbspace'
        ELSE   'Dbspace'
END  as dbstype,
 round(sum(chksize)*2/1024/1024,2)||'GB'  as DBS_SIZE ,
 round(sum(decode(mdsize,-1,nfree,udfree))*2/1024/1024,2)||'GB' as free_size,
 case when sum(decode(mdsize,-1,nfree,udfree))*100/sum(decode(mdsize,-1,chksize,udsize))
   >sum(decode(mdsize,-1,nfree,nfree))*100/sum(decode(mdsize,-1,chksize,mdsize))
then TRUNC(100-sum(decode(mdsize,-1,nfree,nfree))*100/sum(decode(mdsize,-1,chksize,mdsize)),2)||"%"
else TRUNC(100-sum(decode(mdsize,-1,nfree,udfree))*100/sum(decode(mdsize,-1,chksize,udsize)),2)||"%"
    end  as used,
  TRUNC(MAX(A.pagesize/1024))||"KB" as pgsize,
  MAX(B.nchunks) as nchunks
FROM syschktab A, sysdbstab B
WHERE A.dbsnum = B.dbsnum
 GROUP BY A.dbsnum,name, 3
ORDER BY A.dbsnum;
EOF

###################################################################################
## Chunks
###################################################################################
echo "collect chunk info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/chunks.unl delimiter '|'
SELECT  A.chknum as num, B.name as spacename,
 TRUNC((A.pagesize/1024)) as pgsize,
 A.offset offset,
 round( A.chksize*2/1024/1024,2)||'GB'  as size,
 round(decode(A.mdsize,-1,A.nfree,A.udfree)*2/1024/1024,2)||'GB' as free,
 TRUNC(100 - decode(A.mdsize,-1,A.nfree,A.udfree)*100/A.chksize,2 )  as used,
 A.fname
FROM syschktab A, sysdbstab B
WHERE A.dbsnum = B.dbsnum
order by B.dbsnum;
EOF

###################################################################################
## Chunk IO
###################################################################################
echo "collect chunk IO using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/chunk_io.unl delimiter '|'
select d.name dbspace, fname[1,125] chunk_name,reads read_count,writes write_count,
reads+writes total_count,pagesread,pageswritten,
pagesread+pageswritten total_pg
from sysmaster:syschkio c, sysmaster:syschunks k, sysmaster:sysdbspaces d
where d.dbsnum = k.dbsnum and k.chknum  = c.chunknum
order by 8 desc;
EOF

###################################################################################
## Logical Log
###################################################################################
echo "collect logical log using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/logicallog.unl delimiter '|'
SELECT  A.number as num,  A.uniqid as uid,  round(A.size*2/1024,2)||'MB' as size,
 TRIM( TRUNC(A.used*100/A.size,0)||'%') as used,
d.name as spacename,
 TRIM( A.chunk||'_'||A.offset ) as location,
 decode(A.filltime,0,'NotFull',
 dbinfo('UTC_TO_DATETIME', A.filltime)::varchar(50))||' T' as filltime,
 CASE  WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x4')>0
   THEN 'UsedBackedUp'
   WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x2')>0
   THEN 'UsedCurrent'
   WHEN bitval(A.flags,'0x1') > 0   THEN 'Used'
   ELSE   hex(A.flags)::varchar(50)
 END as flags,
 CASE  WHEN A.filltime-B.filltime > 0 THEN
  round(CAST(TRUNC(A.size/(A.filltime-B.filltime),4)
      as varchar(20))*2/1024,2)||'MB/S'
   ELSE    ' N/A '   END as pps
FROM syslogfil A, syslogfil B,syschktab c, sysdbstab d
WHERE  A.uniqid-1 = B.uniqid
and c.dbsnum = d.dbsnum
and a.chunk=c.chknum
UNION
SELECT  A.number as num,  A.uniqid as uid, round(A.size*2/1024,2)||'MB' as size,
 TRIM( TRUNC(A.used*100/A.size,0)||'%') as used,
 d.name as spacename,
 TRIM( A.chunk||'_'||A.offset ) as location,
 decode(A.filltime,0,'NotFull',
 dbinfo('UTC_TO_DATETIME', A.filltime)::varchar(50))||' T'  as filltime,
 CASE   WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x4')>0
   THEN 'UsedBackedUp'
   WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x2')>0
   THEN 'UsedCurrent'
   WHEN bitval(A.flags,'0x1') > 0  THEN 'Used'
   WHEN bitval(A.flags,'0x8') > 0  THEN 'NewAdd'
   ELSE hex(A.flags)::varchar(50)  END as flags,
   'N/A' as pps
FROM syslogfil A ,syschktab c, sysdbstab d
WHERE ( A.uniqid = (SELECT min(uniqid) FROM syslogfil WHERE uniqid > 0)
   OR A.uniqid = 0  )
and c.dbsnum = d.dbsnum
and a.chunk=c.chknum
ORDER BY A.uniqid ;
EOF

###################################################################################
## Locks on Table
###################################################################################
echo "collect table locks using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_actlock.unl delimiter '|'
select dbsname,tabname,
sum(pf_rqlock) as locks,
sum(pf_wtlock) as lockwaits,
sum(pf_deadlk) as deadlocks
from sysactptnhdr,systabnames
where systabnames.partnum = sysactptnhdr.partnum
group by dbsname,tabname
order by lockwaits,locks desc;
EOF

dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_lock.unl delimiter '|'
select dbsname,tabname,
sum(lockreqs) as lockreqs,
sum(lockwts) as lockwaits,
sum(deadlks) as deadlocks
from sysptprof
group by dbsname,tabname
order by deadlocks desc,lockwaits desc,lockreqs desc;
EOF

###################################################################################
## Databaes Used Space
###################################################################################
echo "collect database used space using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/database_space.unl delimiter '|'
select t1.dbsname,
round(sum(ti_nptotal)*max(ti_pagesize)/1024/1024/1024,2)||'GB' allocated_size,
round(sum(ti_npused)*max(ti_pagesize)/1024/1024/1024,2)||'GB'  used_size
from systabnames t1, systabinfo t2,sysdatabases t3
where t1.partnum = t2.ti_partnum
and trim(t3.name)=trim(t1.dbsname)
group by dbsname
order by sum(ti_nptotal) desc;
EOF

###################################################################################
## Tables Space
###################################################################################
echo "collect table and index used space using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_space.unl delimiter '|'
SELECT  st.dbsname databasename,  st.tabname,
    MAX(dbinfo('UTC_TO_DATETIME',sin.ti_created)) createdtime,
    SUM( sin.ti_nextns ) extents,
    SUM( sin.ti_nrows ) nrows,
    MAX( sin.ti_nkeys ) nkeys,
    MAX( sin.ti_pagesize ) pagesize,
    SUM( sin.ti_nptotal ) nptotal,
    round(SUM( sin.ti_nptotal*sd.pagesize )/1024/1024,2)||'MB' total_size,
    SUM( sin.ti_npused ) npused,
    round(SUM( sin.ti_npused*sd.pagesize )/1024/1024,2)||'MB' used_size,
    SUM( sin.ti_npdata ) npdata,
    round(SUM( sin.ti_npdata*sd.pagesize )/1024/1024,2)||'MB' data_size
FROM
    sysmaster:systabnames st,
    sysmaster:sysdbspaces sd,
    sysmaster:systabinfo sin
WHERE
    sd.dbsnum = trunc(st.partnum / 1048576)
    AND st.partnum = sin.ti_partnum
    AND st.dbsname NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1')
    AND st.tabname[1,3] NOT IN ('sys','TBL')
GROUP BY  1,  2
ORDER BY  8 DESC;
EOF

###################################################################################
## Tables Space By Partition
###################################################################################
echo "collect table and index partition used space using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_space_frag.unl delimiter '|'
SELECT  st.dbsname databasename,  st.tabname,st.partnum partnum,
    dbinfo('UTC_TO_DATETIME',sin.ti_created) createdtime,
    sin.ti_nextns  extents,
    sin.ti_nrows nrows,
    sin.ti_nkeys  nkeys,
    sin.ti_pagesize  pagesize,
    sin.ti_nptotal  nptotal,
    round(( sin.ti_nptotal*sd.pagesize )/1024/1024,2)||'MB' total_size,
    ( sin.ti_npused ) npused,
    round(( sin.ti_npused*sd.pagesize )/1024/1024,2)||'MB' used_size,
    ( sin.ti_npdata ) npdata,
    round(( sin.ti_npdata*sd.pagesize )/1024/1024,2)||'MB' data_size
FROM
    sysmaster:systabnames st,
    sysmaster:sysdbspaces sd,
    sysmaster:systabinfo sin
WHERE
    sd.dbsnum = trunc(st.partnum / 1048576)
    AND st.partnum = sin.ti_partnum
    AND st.dbsname NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1')
    AND st.tabname[1,3] NOT IN ('sys','TBL')
ORDER BY  9 DESC;
EOF

###################################################################################
## Tables and index IO and seqscans
###################################################################################
echo "collect table and index io and seqscans using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_io.unl delimiter '|'
SELECT
    st.dbsname,p.tabname,SUM( sin.ti_nrows ) nrows,
    round(SUM( sin.ti_nptotal*sd.pagesize )/1024/1024,2)||'MB' total_size,
    round(SUM( sin.ti_npused*sd.pagesize )/1024/1024,2)||'MB' used_size,
    SUM( seqscans ) AS seqscans,
    SUM( pagreads ) diskreads,
    SUM( bufreads ) bufreads,
    SUM( bufwrites ) bufwrites,
    SUM( pagwrites ) diskwrites,
    SUM( pagreads )+ SUM( pagwrites ) disk_rsws,
    trunc(decode(SUM( bufreads ),0,0,(100 -((SUM( pagreads )* 100)/ SUM( bufreads + pagreads )))),2) AS rbufhits,
    trunc(decode(SUM( bufwrites ),0,0,(100 -((SUM( pagwrites )* 100)/ SUM( bufwrites + pagwrites )))),2) AS wbufhits
FROM
    sysmaster:sysptprof p,
    sysmaster:systabinfo sin,
    sysmaster:sysdbspaces sd,
    sysmaster:systabnames st
WHERE
    sd.dbsnum = trunc(st.partnum / 1048576)
    AND p.partnum = st.partnum
    AND st.partnum = sin.ti_partnum
    AND st.dbsname NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1')
    AND st.tabname[1,3] NOT IN ('sys','TBL')
GROUP BY 1,  2
ORDER BY 11 DESC;
EOF

###################################################################################
## Current slowest sql
###################################################################################
echo "collect current slowest sql using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/slowsql.unl delimiter '|'
Select first 100 sqx_estcost,sqx_estrows,sqx_sqlstatement
FROM sysmaster:syssqexplain
order by sqx_estcost desc;
EOF

###################################################################################
## Table statistics,lockmode,index keys
###################################################################################
if [[ -z "\\$1" ]]; then
echo "collect tables statistics,lockmode,index keys using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tabstat.sql delimiter ";"
select
"unload to ./\\${outpath}/"||trim(name)||"_stat.unl Select t.tabname,t.created as tabcreated,t.nrows,(select sum( ti_nrows ) from sysmaster:systabnames tn join sysmaster:systabinfo ti on ti.ti_partnum = tn.partnum  where t.tabname=tn.tabname   and dbsname = '"||trim(name)||"' )  as realrows,t.locklevel,t.ustlowts,i.idxname,"||
"trim(case when i.part1>0 then (select colname from "||trim(name)||":syscolumns where colno=i.part1 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part2>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part2 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part3>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part3 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part4>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part4 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part5>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part5 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part6>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part6 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part7>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part7 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part8>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part8 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part9>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part9 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part10>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part10 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part11>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part11 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part12>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part12 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part13>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part13 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part14>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part14 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part15>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part15 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part16>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part16 and tabid=i.tabid) else '' end ) index_cols"||
",i.nunique "||
"from "||trim(name)||":systables t left join "||trim(name)||":sysindexes i on t.tabid=i.tabid "||
"where t.tabid>99 "||
"and t.tabtype='T' "||
"order by 4 desc,1"
from sysdatabases
where name NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1','sys')
and is_logging=1;
EOF
dbaccess sysmaster \\${outpath}/tabstat.sql
fi

###################################################################################
## onstat cmd
###################################################################################
echo "collect instance running status using onstat commands ......"
onstat -b > ./\\${outpath}/onstat_b.unl
onstat -C all > ./\\${outpath}/onstat_C_all.unl
onstat -C > ./\\${outpath}/onstat_bigc.unl
onstat -c > ./\\${outpath}/onstat_c.unl
onstat -D > ./\\${outpath}/onstat_bigd.unl
onstat -d > ./\\${outpath}/onstat_d.unl
onstat -F  > ./\\${outpath}/onstat_F.unl
onstat -g act > ./\\${outpath}/onstat_g_act.unl
onstat -g arc > ./\\${outpath}/onstat_g_arc.unl
onstat -g ath > ./\\${outpath}/onstat_g_ath.unl
onstat -g buf > ./\\${outpath}/onstat_g_buf.unl
onstat -g cluster > ./\\${outpath}/onstat_g_cluster.unl
onstat -g cmsm > ./\\${outpath}/onstat_g_cmsm.unl
onstat -g cfg > ./\\${outpath}/onstat_g_cfg.unl
onstat -g cfg diff > ./\\${outpath}/onstat_g_cfg_diff.unl
onstat -g ckp > ./\\${outpath}/onstat_g_ckp.unl
onstat -g con > ./\\${outpath}/onstat_g_con.unl
onstat -g cpu > ./\\${outpath}/onstat_g_cpu.unl
onstat -g dic > ./\\${outpath}/onstat_g_dic.unl
onstat -g dis > ./\\${outpath}/onstat_g_dis.unl
onstat -g dsc > ./\\${outpath}/onstat_g_dsc.unl
onstat -g env > ./\\${outpath}/onstat_g_env.unl
onstat -g glo > ./\\${outpath}/onstat_g_glo.unl
onstat -g iof > ./\\${outpath}/onstat_g_iof.unl
onstat -g iog > ./\\${outpath}/onstat_g_iog.unl
onstat -g ioq > ./\\${outpath}/onstat_g_ioq.unl
onstat -g iov > ./\\${outpath}/onstat_g_iov.unl
onstat -g lmx > ./\\${outpath}/onstat_g_lmx.unl
#onstat -g mem > ./\\${outpath}/onstat_g_mem.unl
onstat -g mgm > ./\\${outpath}/onstat_g_mgm.unl
onstat -g ntd  > ./\\${outpath}/onstat_g_ntd.unl
onstat -g ntt  > ./\\${outpath}/onstat_g_ntt.unl
onstat -g ntu  > ./\\${outpath}/onstat_g_ntu.unl
onstat -g osi > ./\\${outpath}/onstat_g_osi.unl
onstat -g rea > ./\\${outpath}/onstat_g_rea.unl
onstat -g seg > ./\\${outpath}/onstat_g_seg.unl
onstat -g ses 0 > ./\\${outpath}/onstat_g_ses_0.unl
onstat -g ses > ./\\${outpath}/onstat_g_ses.unl
onstat -g smb s > ./\\${outpath}/onstat_g_smb_s.unl
#onstat -g spi | sort -n -k 2 | tail -200 > ./\\${outpath}/onstat_g_spi.unl
onstat -g sql > ./\\${outpath}/onstat_g_sql.unl
onstat -g sql 0 > ./\\${outpath}/onstat_g_sql_0.unl
#onstat -g ssc > ./\\${outpath}/onstat_g_ssc.unl
#onstat -g stk >onstat_g_stk.unl
#onstat -g sts >onstat_g_sts.unl
onstat -g wai > ./\\${outpath}/onstat_g_wai.unl
onstat -L > ./\\${outpath}/onstat_bigl.unl
onstat -l > ./\\${outpath}/onstat_l.unl
onstat -p > ./\\${outpath}/onstat_p.unl
onstat -R > ./\\${outpath}/onstat_R.unl
onstat -u > ./\\${outpath}/onstat_u.unl
onstat -V > ./\\${outpath}/onstat_V.unl
onstat -x > ./\\${outpath}/onstat_x.unl
onstat -X > ./\\${outpath}/onstat_bigx.unl

###################################################################################
## system cmd
###################################################################################
echo ""
echo "collect instance running status using system command ......"
echo ""
echo "collect cm memory ......"
ps -aux |grep cmsm > ./\\${outpath}/cm_mem.unl

echo ""
echo "collect online.log last 50000 rows......"
onlinefile=\\`onstat -m |grep 'Message Log File' | awk '{print \\$4}'\\`
tail -50000 \\${onlinefile} > ./\\${outpath}/online.log

echo ""
echo "collect current user env ......"
env > ./\\${outpath}/env.unl

echo ""
echo "collect system cpu and memory using vmstat ......"
vmstat 1 5 > ./\\${outpath}/vmstat.unl

cp GBase8schk.sh ./\\${outpath}

echo ""
echo "##################################################################"
echo "GBase 8s Database Health Check Finshed"
echo "tar all of the output files in path: \\${outpath}"
echo "tar -cvf \\${outpath}.tar \\${outpath} "
echo "##################################################################"

###################################################################################
## end of all
###################################################################################
GBASEEOF
""");

executeCommandWithExitStatus("""
source ~gbasedbt/.bash_profile &&\
touch $GBASEDBTDIR/scripts/GBase8smon.sh &&\
chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts/GBase8smon.sh &&\
chmod 775 $GBASEDBTDIR/scripts/GBase8smon.sh &&\
cat <<GBASEEOF >$GBASEDBTDIR/scripts/GBase8smon.sh
#!/bin/bash
###################################################################################
# filename: GBase8smon.sh
# Last modified by: L3 2025-11-25
# support OS: Linux
# support database version: GBase 8s V8.x
# useage: sh GBase8smon.sh 5 100  #每5秒收集一次，收集100次
###################################################################################
# 以下信息，收集一次
if [ \\$# -lt 2 ]; then
  echo "Useage:sh gen.sh <interval> <count>"
  exit 0
else
  INTERVAL=\\$1
  COUNT=\\$2
fi
GENDATADIR=GBase8smon_\\$(date +%Y%m%d%H%M%S)
mkdir -p \\${GENDATADIR}
cd \\${GENDATADIR}
dmesg > dmesg.txt
free -m > free_m.txt
onstat -V > onstat_V.txt
onstat -d > onstat_d.txt
onstat -g seg > onstat_g_seg.txt
onstat -g env > onstat_g_env.txt
onstat -g osi > onstat_g_osi.txt
onstat -c > onstat_c.txt
onstat -g cluster > onstat_g_cluster.txt
onstat -g cmsm > onstat_g_cmsm.txt
ps -aux |grep cmsm > cm_mem.txt

# 以下信息，根据输入参数循环收集
for i in \\`seq \\$COUNT\\`
do
tmpdir=\\$(date +%Y%m%d%H%M%S)
mkdir \\$tmpdir
cd \\$tmpdir
onstat -g ses 0 > onstat_g_ses_0.txt
onstat -g stk > onstat_g_stk.txt
onstat -u > onstat_u.txt
onstat -x > onstat_x.txt
onstat -g ckp > onstat_g_ckp.txt
onstat -g ath > onstat_g_ath.txt
onstat -p > onstat_p.txt
onstat -g sql > onstat_g_sql.txt
vmstat > vmstat.txt
mpstat -P ALL > mpstat_P_ALL.txt
sar -d > sar_d.txt
cd ..
sleep \\$INTERVAL
done
cd ..
tar -cvf \\${GENDATADIR}.tar \\${GENDATADIR} >/dev/null 2>&1
rm -rf \\${GENDATADIR}
echo "GBase8smon.sh finished!"
echo "datafile is:"\\${GENDATADIR}.tar
GBASEEOF
""");


                            if(run14){
                                Platform.runLater(() -> {
                                    customInstallStepHbox14.iconLabel.setVisible(true);
                                });
                                String backupPath = configValue(ConfigKey.BACKUP_PATH);
                                String escapedBackupPath = backupPath;
                                String backupCmd="source ~gbasedbt/.bash_profile && mkdir -p "+escapedBackupPath+"&& chown gbasedbt:gbasedbt "+escapedBackupPath+"&& chmod 775 "+escapedBackupPath+
                                        "&& onmode -wf TAPEBLK=2048 && onmode -wf LTAPEBLK=2048 && onmode -wf LTAPEDEV="
                                        +escapedBackupPath+"&& onmode -wf TAPEDEV="+escapedBackupPath+
                                        """
                                        &&\
                                        sed -i "s#^BACKUP_CMD.*#BACKUP_CMD=\\"ontape -a -d\\" #g" $GBASEDBTDIR/etc/log_full.sh &&\
                                        onmode -wf ALARMPROGRAM=$GBASEDBTDIR/etc/log_full.sh &&\
                                        sh -c 'if [ -f /etc/cron.allow ]; then grep -q "^gbasedbt$" /etc/cron.allow || echo "gbasedbt" >> /etc/cron.allow; fi; exit 0' &&\
                                        echo "0 0 * * * $GBASEDBTDIR/scripts/backup.sh >> $GBASEDBTDIR/tmp/backup.log 2>&1" | crontab -u gbasedbt -
                                        """;

                                System.out.println("backupCmd is:\n"+backupCmd);
                                //System.out.println(executeCommandWithExitStatus(backupCmd));

                                if(executeCommandWithExitStatus(backupCmd)!=0) {
                                    throw new Exception(I18n.t("remote.install.error.configure_backup_failed", "配置备份失败！"));
                                }
                                Platform.runLater(() -> {
                                    customInstallStepHbox14.iconLabel.setVisible(false);
                                });
                            }
                            return null;
                        }
                    };
                    installTask.setOnSucceeded(event1 -> {
                        backgroundHBox.setVisible(false);
                        customInstallStepHbox1.iconLabel.setVisible(false);
                        customInstallStepHbox2.iconLabel.setVisible(false);
                        customInstallStepHbox3.iconLabel.setVisible(false);
                        customInstallStepHbox4.iconLabel.setVisible(false);
                        customInstallStepHbox5.iconLabel.setVisible(false);
                        customInstallStepHbox6.iconLabel.setVisible(false);
                        customInstallStepHbox7.iconLabel.setVisible(false);
                        customInstallStepHbox8.iconLabel.setVisible(false);
                        customInstallStepHbox9.iconLabel.setVisible(false);
                        customInstallStepHbox10.iconLabel.setVisible(false);
                        customInstallStepHbox11.iconLabel.setVisible(false);
                        customInstallStepHbox12.iconLabel.setVisible(false);
                        customInstallStepHbox13.iconLabel.setVisible(false);
                        customInstallStepHbox14.iconLabel.setVisible(false);
                        currentStep.set(currentStep.get() + 1);
                        updateWizardState();

                        try {
                            databaseInfoArea.replaceText("");
                            databaseInfoArea.append(I18n.t("remote.install.result.db_version", "数据库版本") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(new File(remoteFilePath).getName() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            databaseInfoArea.append(I18n.t("remote.install.result.db_instance_info", "数据库实例信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(I18n.t("remote.install.result.install_path", "安装路径") + "："+configValue(ConfigKey.GBASEDBTDIR) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.instance_name", "实例名") + "："+configValue(ConfigKey.GBASEDBTSERVER) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.listen_ip", "监听IP") + "："+configValue(ConfigKey.LISTEN_IP)  + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.port", "端口") + "："+configValue(ConfigKey.LISTEN_PORT) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.db_name", "库名") + "：" +configValue(ConfigKey.DEFAULT_DB_NAME) +"\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.user_password", "用户名/密码") + "：gbasedbt/"+configValue(ConfigKey.GBASEDBT_PASSWORD) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.charset", "字符集") + "："+configValue(ConfigKey.DB_LOCALE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append("GL_USEGLU："+configValue(ConfigKey.GL_USEGLU) + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            databaseInfoArea.append(I18n.t("remote.install.result.space_config", "空间配置") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(I18n.t("remote.install.result.data_path", "数据文件路径") + "："+configValue(ConfigKey.DATA_FILE_PATH) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.physlog_size", "物理日志大小") + "："+configValue(ConfigKey.PHYSFILE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.log_size", "逻辑日志大小") + "："+configValue(ConfigKey.LOGSIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.log_files", "逻辑日志个数") + "："+configValue(ConfigKey.LOGFILES) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.tempdbs", "临时空间配置") + "："+configValue(ConfigKey.TEMPDBS) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.sbspace", "智能大对象空间大小") + "："+configValue(ConfigKey.SBSPACE_SIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.user_space", "用户空间大小") + "："+configValue(ConfigKey.DATA_SPACE_SIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(I18n.t("remote.install.result.onstat_d", "onstat -d输出") + "：\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
                            databaseInfoArea.append(executeCommand("source ~gbasedbt/.bash_profile;onstat -d |sed '1,2d'") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            databaseInfoArea.append(I18n.t("remote.install.result.param_config", "参数配置") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(executeCommand("source ~gbasedbt/.bash_profile;onstat -g cfg |grep -v '^$' |sed '1,5d'") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            //databaseInfoArea.insert(databaseInfoArea.getLength(), systemInfoArea.getDocument());
                            databaseInfoArea.append(I18n.t("remote.install.info.machine", "服务器型号") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(machineInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            // 省略其他信息的显示代码（与原逻辑相同）
                            databaseInfoArea.append(I18n.t("remote.install.info.os", "操作系统版本") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(osInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            databaseInfoArea.append(I18n.t("remote.install.info.kernel", "内核版本") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(kernelInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            databaseInfoArea.append(I18n.t("remote.install.info.cpu", "CPU信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(cpuInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            databaseInfoArea.append(I18n.t("remote.install.info.memory", "内存信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(memInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            databaseInfoArea.append(I18n.t("remote.install.info.disk", "磁盘信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(diskInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            fileSystemInfo = executeCommand("df -h");
                            databaseInfoArea.append(I18n.t("remote.install.info.filesystem", "文件系统信息") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(fileSystemInfo + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            String coreParams= executeCommand("ipcs -l");
                            databaseInfoArea.append(I18n.t("remote.install.result.kernel_params", "内核参数") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(coreParams + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

                            String ulimit= executeCommand("su - gbasedbt -c \"ulimit -a\"");
                            databaseInfoArea.append(I18n.t("remote.install.result.gbasedbt_ulimit", "gbasedt用户限制") + "\n","-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            databaseInfoArea.append(ulimit + "\n\n","-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");


                            // 更新目标 TextArea 的文档
                            //databaseInfoArea.append("系统信息\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
                            //databaseInfoArea.append(systemInfoArea.getText()+ "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");



                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }

                    });
                    installTask.setOnFailed(event1 -> {
                        backgroundHBox.setVisible(false);
                        customInstallStepHbox1.iconLabel.setVisible(false);
                        customInstallStepHbox2.iconLabel.setVisible(false);
                        customInstallStepHbox3.iconLabel.setVisible(false);
                        customInstallStepHbox4.iconLabel.setVisible(false);
                        customInstallStepHbox5.iconLabel.setVisible(false);
                        customInstallStepHbox6.iconLabel.setVisible(false);
                        customInstallStepHbox7.iconLabel.setVisible(false);
                        customInstallStepHbox8.iconLabel.setVisible(false);
                        customInstallStepHbox9.iconLabel.setVisible(false);
                        customInstallStepHbox10.iconLabel.setVisible(false);
                        customInstallStepHbox11.iconLabel.setVisible(false);
                        customInstallStepHbox12.iconLabel.setVisible(false);
                        customInstallStepHbox13.iconLabel.setVisible(false);
                        customInstallStepHbox14.iconLabel.setVisible(false);

                        String error = installTask.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
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
                        customInstallStepHbox1.iconLabel.setVisible(false);
                        customInstallStepHbox2.iconLabel.setVisible(false);
                        customInstallStepHbox3.iconLabel.setVisible(false);
                        customInstallStepHbox4.iconLabel.setVisible(false);
                        customInstallStepHbox5.iconLabel.setVisible(false);
                        customInstallStepHbox6.iconLabel.setVisible(false);
                        customInstallStepHbox7.iconLabel.setVisible(false);
                        customInstallStepHbox8.iconLabel.setVisible(false);
                        customInstallStepHbox9.iconLabel.setVisible(false);
                        customInstallStepHbox10.iconLabel.setVisible(false);
                        customInstallStepHbox11.iconLabel.setVisible(false);
                        customInstallStepHbox12.iconLabel.setVisible(false);
                        customInstallStepHbox13.iconLabel.setVisible(false);
                        customInstallStepHbox14.iconLabel.setVisible(false);
                        customInstallStepHbox6.checkBox.setDisable(false);
                        customInstallStepHbox7.checkBox.setDisable(false);
                        customInstallStepHbox8.checkBox.setDisable(false);
                        customInstallStepHbox9.checkBox.setDisable(false);
                        customInstallStepHbox10.checkBox.setDisable(false);
                        customInstallStepHbox11.checkBox.setDisable(false);
                        customInstallStepHbox12.checkBox.setDisable(false);
                        customInstallStepHbox13.checkBox.setDisable(false);
                        customInstallStepHbox14.checkBox.setDisable(false);

                    });
                    mainDialog.setOnCloseRequest(event1 -> {
                        installTask.cancel();
                        if (session != null && session.isConnected()) {
                            session.disconnect();
                        }

                    });
                    cancelBtn.setOnAction(event1->{
                        installTask.cancel();
                        if (session != null && session.isConnected()) {
                            session.disconnect();
                        }
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
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            mainDialog.close();
            Main.lastInstallConnect=new Connect();
            Main.lastInstallConnect.setIp(hostField.getText());
            Main.lastInstallConnect.setPort(configValue(ConfigKey.LISTEN_PORT));
            Main.lastInstallConnect.setUsername("gbasedbt");
            Main.lastInstallConnect.setPassword(configValue(ConfigKey.GBASEDBT_PASSWORD));
            Platform.runLater(() -> {
                AppState.createConnectLeaf();
            });
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
        ipLabel.setGraphic(IconFactory.create(IconPaths.CREATE_CONNECT_IP, 0.6, 0.6));
        ipLabel.setAlignment(Pos.CENTER_LEFT);
        ipLabel.setContentDisplay(ContentDisplay.LEFT);
        ipLabel.setGraphicTextGap(6);

        Label portLabel = new Label();
        portLabel.textProperty().bind(I18n.bind("remote.install.field.port", "端口"));
        portLabel.setGraphic(IconFactory.create(IconPaths.CREATE_CONNECT_PORT, 0.45, 0.45));
        portLabel.setAlignment(Pos.CENTER_LEFT);
        portLabel.setContentDisplay(ContentDisplay.LEFT);
        portLabel.setGraphicTextGap(6);

        Label passwdLabel = new Label();
        passwdLabel.textProperty().bind(I18n.bind("remote.install.field.root_password", "root密码"));
        passwdLabel.setGraphic(IconFactory.create(IconPaths.CREATE_CONNECT_PASSWORD, 0.5, 0.5));
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
        Label desc1 = new Label();
        desc1.textProperty().bind(I18n.bind("remote.install.desc.item1", "1远程安装仅用于Linux或Unix系统远程安装，不适用于Windows系统。"));
        Label desc2 = new Label();
        desc2.textProperty().bind(I18n.bind("remote.install.desc.item2", "2安装前可准备好已下载的安装包，如未准备，可在安装过程中自动下载。"));
        Label desc3 = new Label();
        desc3.textProperty().bind(I18n.bind("remote.install.desc.item3", "3安装前会自动卸载之前已存在的GBase 8s数据库安装，并清理所有相关信息。"));
        Label desc4 = new Label();
        desc4.textProperty().bind(I18n.bind("remote.install.desc.item4", "4远程安装向导支持GBase 8s V8.7GBase 8s V8.8。"));
        VBox vBox=new VBox(10);
        vBox.setStyle("-fx-padding: 10 0 0 30");
        grid.setStyle("-fx-padding: 10 0 10 0;");
        vBox.getChildren().addAll(descbefore,grid,desc,desc1,desc2,desc3,desc4);
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
        Label downloadPrefixLabel = new Label();
        downloadPrefixLabel.textProperty().bind(I18n.bind("remote.install.step3.download_prefix", "选择已下载的安装包，或点击"));
        Label downloadSuffixLabel = new Label();
        downloadSuffixLabel.textProperty().bind(I18n.bind("remote.install.step3.download_suffix", "自动下载与CPU型号匹配的最新试用版本，下载到桌面并填充下框。"));
        HBox downloadHBox = new HBox(downloadPrefixLabel, downloadButton, downloadSuffixLabel);
        HBox hBox = new HBox(10);
        hBox.getChildren().addAll(installFilePathField,browseButton);
        // 浏览文件事件
        browseButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("remote.install.step3.select_package", "选择安装包"));
            selectedFile = fileChooser.showOpenDialog(parent);
            if (selectedFile != null) {
                installFilePathField.setText(selectedFile.getAbsolutePath());
                remoteFilePath = "/tmp/" + selectedFile.getName();
                remotePathField.setText(remoteFilePath);

                // 自动建议安装命令
                if (selectedFile.getName().endsWith(".deb")) {
                    installFilePathField.setText("dpkg -i " + remoteFilePath);
                } else if (selectedFile.getName().endsWith(".rpm")) {
                    installFilePathField.setText("rpm -ivh " + remoteFilePath);
                } else if (selectedFile.getName().endsWith(".sh")) {
                    installFilePathField.setText("chmod +x " + remoteFilePath + " && ./" + remoteFilePath);
                }
            }
        });
        StackPane downloadStackPane=new StackPane();
        downloadButton.setOnAction(event -> {
            File desktopDir = FileSystemView.getFileSystemView().getHomeDirectory();
            String fileName="";
            String url="";
            if(systemInfoArea.getText().contains("x86_64")) {
                url = "https://www.dbboys.com/dl/gbase8s/server/x86/latest.tar";
            }else if(systemInfoArea.getText().contains("aarch64")) {
                url="https://www.dbboys.com/dl/gbase8s/server/arm/latest.tar";
            }else{
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                        I18n.t("remote.install.error.unknown_platform", "未知系统平台，请手动下载数据库安装包！"));
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
            //DownloadManagerUtil.addInstallDownload("https://www.dbboys.com/dl/gbase8s/server/arm/latest",saveFile,true,null,downloadStackPane,remotePathField,installFilePathField);

        });
        downloadStackPane.setMinHeight(50);
        notUploadedRadioButton.setToggleGroup(uploadToggleGroup);
        notUploadedRadioButton.textProperty().bind(I18n.bind("remote.install.step3.not_uploaded", "我没有上传数据库安装包"));
        notUploadedRadioButton.setSelected(true);
        downloadButton.disableProperty().bind(notUploadedRadioButton.selectedProperty().not());
        installFilePathField.disableProperty().bind(notUploadedRadioButton.selectedProperty().not());
        browseButton.disableProperty().bind(notUploadedRadioButton.selectedProperty().not());
        uploadedRadioButton.setToggleGroup(uploadToggleGroup);
        uploadedRadioButton.textProperty().bind(I18n.bind("remote.install.step3.uploaded", "我已经上传了数据库安装包"));
        remotePathField.disableProperty().bind(uploadedRadioButton.selectedProperty().not());
        Label uploadTitleLabel = new Label();
        uploadTitleLabel.textProperty().bind(I18n.bind("remote.install.step3.upload_title", "上传安装包到远程服务器："));
        Label uploadPathLabel = new Label();
        uploadPathLabel.textProperty().bind(I18n.bind("remote.install.step3.upload_path", "远程服务器上安装包路径，如安装包已上传，请在下框填入安装包绝对路径："));
        content.getChildren().addAll(
                uploadTitleLabel,
                notUploadedRadioButton,
                downloadHBox,
                hBox,
                downloadStackPane,
                uploadedRadioButton,
                uploadPathLabel,
                remotePathField
        );

        return content;
    }



    // 步骤2：系统信息面板
    private static Node createStep4Content(Stage parent) {
        customInstallStepHbox1=new CustomInstallStepHbox("", "");
        customInstallStepHbox2=new CustomInstallStepHbox("", "");
        customInstallStepHbox3=new CustomInstallStepHbox("", "");
        customInstallStepHbox4=new CustomInstallStepHbox("", "");
        customInstallStepHbox5=new CustomInstallStepHbox("", "");
        customInstallStepHbox6=new CustomInstallStepHbox("", "");
        customInstallStepHbox7=new CustomInstallStepHbox("", "");
        customInstallStepHbox8=new CustomInstallStepHbox("", "");
        customInstallStepHbox9=new CustomInstallStepHbox("", "");
        customInstallStepHbox10=new CustomInstallStepHbox("", "");
        customInstallStepHbox11=new CustomInstallStepHbox("", "");
        customInstallStepHbox12=new CustomInstallStepHbox("", "");
        customInstallStepHbox13=new CustomInstallStepHbox("", "");
        customInstallStepHbox14=new CustomInstallStepHbox("", "");
        // 前五步必须执行，默认勾选且不可编辑
        customInstallStepHbox1.checkBox.setSelected(true); customInstallStepHbox1.checkBox.setDisable(true);
        customInstallStepHbox2.checkBox.setSelected(true); customInstallStepHbox2.checkBox.setDisable(true);
        customInstallStepHbox3.checkBox.setSelected(true); customInstallStepHbox3.checkBox.setDisable(true);
        customInstallStepHbox4.checkBox.setSelected(true); customInstallStepHbox4.checkBox.setDisable(true);
        customInstallStepHbox5.checkBox.setSelected(true); customInstallStepHbox5.checkBox.setDisable(true);
        customInstallStepHbox14.checkBox.setSelected(false);
        bindInstallStepTexts();

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
        VBox vBox=new VBox(4,titleHBox,customInstallStepHbox1,customInstallStepHbox2,customInstallStepHbox3,customInstallStepHbox4,customInstallStepHbox5,customInstallStepHbox6,customInstallStepHbox7,customInstallStepHbox8,customInstallStepHbox9,customInstallStepHbox10,customInstallStepHbox11,customInstallStepHbox12,customInstallStepHbox13,customInstallStepHbox14);
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

    private static void bindInstallStepTexts() {
        customInstallStepHbox1.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step1.name", "卸载现有安装"));
        customInstallStepHbox1.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step1.desc", "kill所有gbasedbt用户进程，删除所有安装路径，删除gbasedbt数据文件，删除gbasedbt用户及组。"));
        customInstallStepHbox2.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step2.name", "检查系统依赖"));
        customInstallStepHbox2.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step2.desc", "检查/opt不小于8G，权限755，/tmp不小于1G，内存不小于1G，检查所需unzip等依赖包关闭防火墙等。"));
        customInstallStepHbox3.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step3.name", "创建用户组及用户"));
        customInstallStepHbox3.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step3.desc", "创建gbasedbt用户组和gbasedbt用户，配置环境变量GBASEDBTDIRGBASEDBTSERVER等。"));
        customInstallStepHbox4.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step4.name", "安装数据库软件"));
        customInstallStepHbox4.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step4.desc", "安装软件到gbasedbt用户默认环境变量$GBASEDBTDIR指定路径。"));
        customInstallStepHbox5.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step5.name", "初始化数据库实例"));
        customInstallStepHbox5.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step5.desc", "初始化数据库实例，数据文件路径$GBASEDBTDIR/dbs，监听IP 0.0.0.0，端口 9088。"));
        customInstallStepHbox6.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step6.name", "优化配置参数"));
        customInstallStepHbox6.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step6.desc", "优化CPU内存等关键参数，启用数据库用户，关闭sysadmin，重启数据库实例。"));
        customInstallStepHbox7.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step7.name", "优化物理日志"));
        customInstallStepHbox7.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step7.desc", "创建物理日志空间plogdbs，并将物理日志从rootdbs中移动到plogdbs。"));
        customInstallStepHbox8.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step8.name", "优化逻辑日志"));
        customInstallStepHbox8.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step8.desc", "创建逻辑日志空间llogdbs，并将物理日志从rootdbs中移动到llogdbs。"));
        customInstallStepHbox9.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step9.name", "优化临时空间"));
        customInstallStepHbox9.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step9.desc", "创建临时数据库空间tmpdbs01，避免在rootdbs中执行排序等操作。"));
        customInstallStepHbox10.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step10.name", "创建大对象空间"));
        customInstallStepHbox10.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step10.desc", "创建默认智能大对象空间sbspace01，用于存放blob/clob数据。"));
        customInstallStepHbox11.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step11.name", "创建用户数据空间"));
        customInstallStepHbox11.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step11.desc", "创建用户数据空间datadbs01，存放用户数据。"));
        customInstallStepHbox12.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step12.name", "创建默认数据库"));
        customInstallStepHbox12.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step12.desc", "创建默认用户数据库gbasedb，存储于datadbs01。"));
        customInstallStepHbox13.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step13.name", "配置开机自启"));
        customInstallStepHbox13.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step13.desc", "开机自启，自启方式为在/etc/rc.local中添加启动命令。"));
        customInstallStepHbox14.nameLabel.textProperty().bind(I18n.bind("remote.install.step4.step14.name", "配置备份"));
        customInstallStepHbox14.descLabel.textProperty().bind(I18n.bind("remote.install.step4.step14.desc", "备份脚本位于$GBASEDBTDIR/scripts/backup.sh，默认每天0备，保留7天。"));
    }

    // 以下为原有工具方法（保持不变）
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

    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void rebuildInstallConfigMap() {
        installConfigMap.clear();
        for (ConfigKey key : ConfigKey.values()) {
            InstallConfigItem item = installConfigItems.stream()
                    .filter(config -> key.id.equals(config.id))
                    .findFirst()
                    .orElse(null);
            if (item != null) {
                installConfigMap.put(key, item);
            }
        }
    }

    private static String configValue(ConfigKey key) {
        InstallConfigItem item = installConfigMap.get(key);
        if (item == null) {
            throw new IllegalStateException("Missing config key: " + key.id);
        }
        return item.value == null ? "" : item.value;
    }

    private static InstallConfigItem findConfigItem(ObservableList<InstallConfigItem> source, ConfigKey key) {
        return source.stream().filter(item -> key.id.equals(item.id)).findFirst().orElse(null);
    }

    private static void updateConfigValue(ObservableList<InstallConfigItem> source, ConfigKey key, String value) {
        InstallConfigItem item = findConfigItem(source, key);
        if (item != null) {
            item.value = value;
        }
    }


    private static boolean remoteFileExists(String filePath) throws JSchException, InterruptedException {
        return executeCommandWithExitStatus("test -f " + shellQuote(filePath)) == 0;
    }

    private static void refreshLegacyConfigListFromItems() {
        rebuildInstallConfigMap();
    }

    public static void modifyEnv(){

        ObservableList<InstallConfigItem> datalist = installConfigItems.stream()
                .map(InstallConfigItem::new)
                .collect(Collectors.toCollection(FXCollections::observableArrayList));

        CustomResultsetTableView<InstallConfigItem> tableView = new CustomResultsetTableView<>();
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
                    int PHYSFILE=1024000;
                    int LOGFILES=10;
                    String TEMPDBS="1*1024000";
                    int SBDBSSIZE=1024000;
                    int DATADBSSIZE=1024000;
                    if(freeDiskSize>100&&freeDiskSize<=200){
                        PHYSFILE=5120000;
                        LOGFILES=50;
                        TEMPDBS="2*5120000";
                        SBDBSSIZE=5120000;
                        DATADBSSIZE=5120000;
                    }else if(freeDiskSize>200){
                        PHYSFILE=10240000;
                        LOGFILES=100;
                        TEMPDBS="2*10240000";
                        SBDBSSIZE=10240000;
                        DATADBSSIZE=10240000;
                    }
                    updateConfigValue(datalist, ConfigKey.PHYSFILE, String.valueOf(PHYSFILE));
                    updateConfigValue(datalist, ConfigKey.LOGFILES, String.valueOf(LOGFILES));
                    updateConfigValue(datalist, ConfigKey.TEMPDBS, TEMPDBS);
                    updateConfigValue(datalist, ConfigKey.SBSPACE_SIZE, String.valueOf(SBDBSSIZE));
                    updateConfigValue(datalist, ConfigKey.DATA_SPACE_SIZE, String.valueOf(DATADBSSIZE));
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
        tableView.getItems().clear();
        tableView.getItems().addAll(datalist);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        VBox contentBox = new VBox();
        contentBox.setAlignment(Pos.TOP_LEFT);
        contentBox.getChildren().add(tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);
        tableView.setMaxHeight(Double.MAX_VALUE);

        ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("remote.install.modifyenv.title", "自定义配置"),
                contentBox,
                760,
                360,
                buttonTypeOk,
                buttonTypeCancel
        );
        Button button = dialog.getButton(buttonTypeOk);
        Button cancelButton = dialog.getButton(buttonTypeCancel);
        button.textProperty().bind(I18n.bind("common.confirm", "确认"));
        cancelButton.textProperty().bind(I18n.bind("common.cancel", "取消"));
        ButtonType result = dialog.showAndWait();
        if (result == buttonTypeOk) {
            installConfigItems.setAll(datalist.stream().map(InstallConfigItem::new).toList());
            refreshLegacyConfigListFromItems();
        }

    }

    public static void initConfigList(){
        //自定义安装配置
        Double totalMem;
        int NUMCPU=1;
        try {
            freeDiskSize=Double.parseDouble(executeCommand("df -m /opt |tail -1 |awk '{print $4/1000}'"));
            totalMem=Double.parseDouble(executeCommand("free -m |sed -n 2p |awk '{print $2/1024}'"));
            NUMCPU=Integer.parseInt(executeCommand("cat /proc/cpuinfo |grep -c processor"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        int LOCKS=1000000;
        int SHMVIRTSIZE=102400;
        int DS_TOTAL_MEMORY=102400;
        int K2BUFFERS=51200;
        int K16BUFFERS=51200;
        if(totalMem>4&&totalMem<=8){
            SHMVIRTSIZE=512000;
            DS_TOTAL_MEMORY=512000;
            K2BUFFERS=102400;
            K16BUFFERS=102400;
        }else if(totalMem>8&&totalMem<=16){
            SHMVIRTSIZE=1024000;
            DS_TOTAL_MEMORY=1024000;
            K2BUFFERS=512000;
            K16BUFFERS=204800;
        }else if(totalMem>16&&totalMem<=32){
            LOCKS=10000000;
            SHMVIRTSIZE=2048000;
            DS_TOTAL_MEMORY=2048000;
            K2BUFFERS=512000;
            K16BUFFERS=409600;
        }else if(totalMem>32&&totalMem<=64){
            LOCKS=10000000;
            SHMVIRTSIZE=4096000;
            DS_TOTAL_MEMORY=4096000;
            K2BUFFERS=512000;
            K16BUFFERS=819200;
        }else if(totalMem>64&&totalMem<=128){
            LOCKS=10000000;
            SHMVIRTSIZE=4096000;
            DS_TOTAL_MEMORY=4096000;
            K2BUFFERS=512000;
            K16BUFFERS=2000000;
        }else if(totalMem>128){
            LOCKS=10000000;
            SHMVIRTSIZE=10240000;
            DS_TOTAL_MEMORY=4096000;
            K2BUFFERS=512000;
            K16BUFFERS=4000000;
        }

        /*
        int PHYSFILE=102400;
        int LOGFILES=3;
        String TEMPDBS="1*102400";
        int SBDBSSIZE=102400;
        int DATADBSSIZE=102400;

        */
        int PHYSFILE=1024000;
        int LOGFILES=10;
        String TEMPDBS="1*1024000";
        int SBDBSSIZE=1024000;
        int DATADBSSIZE=1024000;


        if(freeDiskSize>100&&freeDiskSize<=200){
            PHYSFILE=5120000;
            LOGFILES=50;
            TEMPDBS="2*5120000";
            SBDBSSIZE=5120000;
            DATADBSSIZE=5120000;
        }else if(freeDiskSize>200){
            PHYSFILE=10240000;
            LOGFILES=100;
            TEMPDBS="2*10240000";
            SBDBSSIZE=10240000;
            DATADBSSIZE=10240000;
        }
        installConfigItems.clear();
        installConfigItems.add(new InstallConfigItem("gbasedbt_password", I18n.t("remote.install.cfg.gbasedbt_password.name", "gbasedbt用户密码"), "8S*P)0Od@.&", I18n.t("remote.install.cfg.gbasedbt_password.desc", "保持密码强度，部分系统如强度不够可能导致设置密码失败")));
        installConfigItems.add(new InstallConfigItem("gbasedbtdir", "GBASEDBTDIR", "/opt/gbase", I18n.t("remote.install.cfg.gbasedbtdir.desc", "数据库软件安装路径，无特殊要求不修改")));
        installConfigItems.add(new InstallConfigItem("gbasedbtserver", "GBASEDBTSERVER", "gbase01", I18n.t("remote.install.cfg.gbasedbtserver.desc", "数据库实例名，无特殊要求不修改")));
        installConfigItems.add(new InstallConfigItem("db_locale", "DB_LOCALE", "zh_CN.utf8", I18n.t("remote.install.cfg.db_locale.desc", "默认字符集推荐utf8，如要兼容GBK使用zh_CN.gb18030-2000")));
        installConfigItems.add(new InstallConfigItem("gl_useglu", "GL_USEGLU", "1", I18n.t("remote.install.cfg.gl_useglu.desc", "是否开启GLU，建议开启，0关闭")));
        installConfigItems.add(new InstallConfigItem("data_file_path", I18n.t("remote.install.cfg.data_file_path.name", "数据文件路径"), "$GBASEDBTDIR/dbs", I18n.t("remote.install.cfg.data_file_path.desc", "如/data，路径必须存在，修改后相关空间大小根据空间可用量自动重新计算")));
        installConfigItems.add(new InstallConfigItem("rootsize", "ROOTSIZE", "1024000", I18n.t("remote.install.cfg.rootsize.desc", "根空间大小，建议不小于1G，固定值。")));
        installConfigItems.add(new InstallConfigItem("listen_ip", I18n.t("remote.install.cfg.listen_ip.name", "监听IP"), "0.0.0.0", I18n.t("remote.install.cfg.listen_ip.desc", "默认监听所有IP，如无特殊要求不修改")));
        installConfigItems.add(new InstallConfigItem("listen_port", I18n.t("remote.install.cfg.listen_port.name", "监听端口"), "9088", I18n.t("remote.install.cfg.listen_port.desc", "默认端口9088，如无特殊要求不修改")));
        installConfigItems.add(new InstallConfigItem("physfile", "PHYSFILE", String.valueOf(PHYSFILE), I18n.t("remote.install.cfg.physfile.desc", "物理日志大小，建议不小于10G，默认根据数据文件路径可用空间自动计算")));
        installConfigItems.add(new InstallConfigItem("logsize", "LOGSIZE", "102400", I18n.t("remote.install.cfg.logsize.desc", "单个逻辑日志大小，建议100MB固定值")));
        installConfigItems.add(new InstallConfigItem("logfiles", "LOGFILES", String.valueOf(LOGFILES), I18n.t("remote.install.cfg.logfiles.desc", "逻辑日志个数，建议不小于100个，默认根据数据文件路径可用空间自动计算")));
        installConfigItems.add(new InstallConfigItem("tempdbs", I18n.t("remote.install.cfg.tempdbs.name", "临时空间配置"), TEMPDBS, I18n.t("remote.install.cfg.tempdbs.desc", "数量*大小，如1*10240000，建议不小于10G，默认根据数据文件路径可用空间自动计算")));
        installConfigItems.add(new InstallConfigItem("sbspace_size", I18n.t("remote.install.cfg.sbspace_size.name", "智能大对象空间大小"), String.valueOf(SBDBSSIZE), I18n.t("remote.install.cfg.sbspace_size.desc", "建议不小于10G，默认根据数据文件路径可用空间自动计算")));
        installConfigItems.add(new InstallConfigItem("data_space_size", I18n.t("remote.install.cfg.data_space_size.name", "用户数据空间大小"), String.valueOf(DATADBSSIZE), I18n.t("remote.install.cfg.data_space_size.desc", "建议不小于10G，默认根据数据文件路径可用空间自动计算")));
        installConfigItems.add(new InstallConfigItem("default_db_name", I18n.t("remote.install.cfg.default_db_name.name", "用户默认数据库名"), "gbasedb", I18n.t("remote.install.cfg.default_db_name.desc", "默认gbasedb，可自定义修改")));
        installConfigItems.add(new InstallConfigItem("locks", "LOCKS", String.valueOf(LOCKS), I18n.t("remote.install.cfg.locks.desc", "建议不小于10000000，默认根据内存自动计算")));
        installConfigItems.add(new InstallConfigItem("ds_total_memory", "DS_TOTAL_MEMORY", String.valueOf(DS_TOTAL_MEMORY), I18n.t("remote.install.cfg.ds_total_memory.desc", "建议不小于4096000，默认根据内存自动计算")));
        installConfigItems.add(new InstallConfigItem("ds_nonpdq", "DS_NONPDQ_QUERY_MEM", String.valueOf(DS_TOTAL_MEMORY/4), I18n.t("remote.install.cfg.ds_nonpdq.desc", "建议不小于1024000，默认根据内存自动计算")));
        installConfigItems.add(new InstallConfigItem("shmvirtsize", "SHMVIRTSIZE", String.valueOf(SHMVIRTSIZE), I18n.t("remote.install.cfg.shmvirtsize.desc", "建议不小于4096000，默认根据内存自动计算")));
        installConfigItems.add(new InstallConfigItem("shmadd", "SHMADD", String.valueOf(SHMVIRTSIZE/4), I18n.t("remote.install.cfg.shmadd.desc", "建议不小于1024000，默认根据内存自动计算")));
        installConfigItems.add(new InstallConfigItem("vpclass", "VPCLASS", "cpu,num="+NUMCPU+",noage", I18n.t("remote.install.cfg.vpclass.desc", "如是numa架构多路服务器，可绑定CPU，默认等于CPU内核数量")));
        installConfigItems.add(new InstallConfigItem("bufferpool_2k", "BUFFERPOOL", "size=2k,buffers="+K2BUFFERS+",lrus=32,lru_min_dirty=50,lru_max_dirty=60", I18n.t("remote.install.cfg.bufferpool_2k.desc", "建议不小于1G，默认根据内存自动计算")));
        installConfigItems.add(new InstallConfigItem("bufferpool_16k", "BUFFERPOOL", "size=16k,buffers="+K16BUFFERS+",lrus=128,lru_min_dirty=50,lru_max_dirty=60", I18n.t("remote.install.cfg.bufferpool_16k.desc", "建议不超过内存的50%，默认根据内存自动计算")));
        installConfigItems.add(new InstallConfigItem("backup_path", I18n.t("remote.install.cfg.backup_path.name", "备份路径"), "$GBASEDBTDIR/backup", I18n.t("remote.install.cfg.backup_path.desc", "填写路径后每天0点执行全量备份到填写的指定路径，逻辑日志自动归档，保留7天。")));
        refreshLegacyConfigListFromItems();
    }



}
