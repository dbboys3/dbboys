package com.dbboys.util;

import com.dbboys.app.AppExecutor;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.jcraft.jsch.*;
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

import java.util.Properties;

public class RemoteUninstallerUtil {
    // 存储用户输入信息
    private static String hostname;
    private static int port = 22;
    private static String username = "root";
    private static String password;

    private static final int TOTAL_STEPS = 3;
    private static final double DIALOG_WIDTH = 600;
    private static final double DIALOG_HEIGHT = 400;
    private static final JSch JSCH = new JSch();
    private static final DoubleProperty progress = new SimpleDoubleProperty(0);
    private static final IntegerProperty currentStep = new SimpleIntegerProperty(1);

    private static Session session;

    //安装面板
    private static CustomInstallStepHbox customInstallStepHbox1;
    private static CustomInstallStepHbox customInstallStepHbox2;
    private static CustomInstallStepHbox customInstallStepHbox3;
    private static CustomInstallStepHbox customInstallStepHbox4;
    private static CustomInstallStepHbox customInstallStepHbox5;



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
    private static HBox backgroundHBox;
    private static Button stopButton;
    private static Label runningLabel;

    // 入口方法：启动向导
    public static void startWizard(Stage parent) {
        initMainDialog(parent);
        currentStep.set(1);
        updateWizardState();
        mainDialog.showAndWait();
    }

    // 初始化主对话框
    private static void initMainDialog(Stage parent) {
        mainDialog = new Stage();
        mainDialog.titleProperty().bind(Bindings.createStringBinding(
                () -> I18n.t("remote.uninstall.title.format", "远程卸载向导 - 步骤 %d/%d")
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
                            if (session != null && session.isConnected()) {
                                session.disconnect();
                            }
                        });
                        cancelBtn.setOnAction(event1->{
                            runningTask.cancel();
                            if (session != null && session.isConnected()) {
                                session.disconnect();
                            }
                        });
                    }
                    runningLabel.setText(" " + I18n.t("remote.uninstall.status.connecting", "正在连接..."));
                    break;

                case 2:
                    Task installTask = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            Platform.runLater(() -> {
                                customInstallStepHbox1.iconLabel.setVisible(true);
                                runningLabel.setText(" " + I18n.t("remote.uninstall.status.uninstalling", "正在卸载..."));
                            });
                            if(executeCommandWithExitStatus("ps -ef |grep gbasedbt |grep -v grep |awk '{print \"kill -9 \"$2}' |sh")!=0){
                                throw new Exception(I18n.t("remote.uninstall.error.kill_process_failed", "kill gbasedbt用户进程失败！"));
                            };
                            Platform.runLater(() -> {
                                customInstallStepHbox1.iconLabel.setVisible(false);
                                customInstallStepHbox2.iconLabel.setVisible(true);
                            });
                            if((executeCommandWithExitStatus("test -f /GBASEDBTTMP/.infxdirs") == 0)){
                                if(executeCommandWithExitStatus("cat /GBASEDBTTMP/.infxdirs  |awk '{print \"rm -rf \"$1}'|sh")!=0) {
                                    throw new Exception(I18n.t("remote.uninstall.error.remove_install_dirs_failed", "删除数据库安装目录失败！"));
                                }
                                if(executeCommandWithExitStatus("rm -rf /GBASEDBTTMP")!=0) {
                                    throw new Exception(I18n.t("remote.uninstall.error.remove_gbasedbttmp_failed", "删除目录/GBASEDBTTMP失败！"));
                                }
                            }
                            if((executeCommandWithExitStatus("test -d /opt/gbase") == 0)){
                                if(executeCommandWithExitStatus("rm -rf /opt/gbase")!=0) {
                                    throw new Exception(I18n.t("remote.uninstall.error.remove_opt_gbase_failed", "删除数据库安装目录/opt/gbase失败！"));
                                }
                            }
                            Platform.runLater(() -> {
                                customInstallStepHbox2.iconLabel.setVisible(false);
                                customInstallStepHbox3.iconLabel.setVisible(true);
                            });
                            if(executeCommandWithExitStatus("find / -user gbasedbt -group gbasedbt -exec rm -rf {} +")!=0){
                               // throw new Exception("删除gbasedbt用户文件失败！");
                            };

                            Platform.runLater(() -> {
                                customInstallStepHbox3.iconLabel.setVisible(false);
                                customInstallStepHbox4.iconLabel.setVisible(true);
                            });
                            if((executeCommandWithExitStatus("test -d /etc/gbasedbt") == 0)) {
                                if(executeCommandWithExitStatus("rm -rf /etc/gbasedbt")!=0) {
                                    throw new Exception(I18n.t("remote.uninstall.error.remove_etc_gbasedbt_failed", "删除/etc/gbasedbt目录失败！"));
                                }
                            }
                            Platform.runLater(() -> {
                                customInstallStepHbox4.iconLabel.setVisible(false);
                                customInstallStepHbox5.iconLabel.setVisible(true);
                            });
                            if((executeCommandWithExitStatus("id gbasedbt") == 0)) {
                                if(executeCommandWithExitStatus("userdel -r -f gbasedbt")!=0) {
                                    throw new Exception(I18n.t("remote.uninstall.error.remove_user_failed", "删除用户gbasedbt失败！"));
                                }
                                executeCommandWithExitStatus("groupdel gbasedbt");
                            }
                            //卸载完成，开始系统检查
                            Platform.runLater(() -> {
                                customInstallStepHbox5.iconLabel.setVisible(false);
                            });
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
                        currentStep.set(currentStep.get() + 1);
                        updateWizardState();

                    });
                    installTask.setOnFailed(event1 -> {
                        backgroundHBox.setVisible(false);
                        customInstallStepHbox1.iconLabel.setVisible(false);
                        customInstallStepHbox2.iconLabel.setVisible(false);
                        customInstallStepHbox3.iconLabel.setVisible(false);
                        customInstallStepHbox4.iconLabel.setVisible(false);
                        customInstallStepHbox5.iconLabel.setVisible(false);
                        String error = installTask.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });

                    AppExecutor.runTask(installTask);
                    backgroundHBox.setVisible(true);
                    stopButton.setOnAction(event1->{
                        installTask.cancel();
                        backgroundHBox.setVisible(false);
                        customInstallStepHbox1.iconLabel.setVisible(false);
                        customInstallStepHbox2.iconLabel.setVisible(false);
                        customInstallStepHbox3.iconLabel.setVisible(false);
                        customInstallStepHbox4.iconLabel.setVisible(false);
                        customInstallStepHbox5.iconLabel.setVisible(false);
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
                    });
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

        // 输入组件（保存引用）
        hostField = new CustomUserTextField();
        hostField.setPrefWidth(420);

        portField = new CustomUserTextField();
        portField.setText("22");

        passField = new CustomPasswordField();

        // 图标和标签
        Label ipLabel = new Label();
        ipLabel.textProperty().bind(I18n.bind("remote.uninstall.label.host", "主机名/IP"));
        ipLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_IP, 0.6, 0.6));

        Label portLabel = new Label();
        portLabel.textProperty().bind(I18n.bind("remote.uninstall.label.port", "端口"));
        portLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PORT, 0.45, 0.45));

        Label passwdLabel = new Label();
        passwdLabel.textProperty().bind(I18n.bind("remote.uninstall.label.password", "root密码"));
        passwdLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PASSWORD, 0.5, 0.5));



        // 布局
        grid.add(ipLabel, 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(portLabel, 0, 1);
        grid.add(portField, 1, 1);
        grid.add(passwdLabel, 0, 2);
        grid.add(passField, 1, 2);
        Label descbefore=new Label();
        descbefore.textProperty().bind(I18n.bind("remote.uninstall.desc.fill_server_info", "请填写需要远程卸载数据库的服务器信息："));

        Label desc=new Label();
        desc.textProperty().bind(I18n.bind("remote.uninstall.desc.title", "说明："));
        Label desc1=new Label();
        desc1.textProperty().bind(I18n.bind("remote.uninstall.desc.item1", "1、远程卸载仅用于Linux或Unix系统远程卸载，不适用于Windows系统。"));
        Label desc3=new Label();
        desc3.textProperty().bind(I18n.bind("remote.uninstall.desc.item2", "2、远程卸载会自动卸载之前已存在的GBase 8s数据库安装，并清理所有相关信息。"));
        Label desc4=new Label();
        desc4.textProperty().bind(I18n.bind("remote.uninstall.desc.item3", "3、远程卸载向导支持GBase 8s V8.7、GBase 8s V8.8。"));
        VBox vBox=new VBox(10);
        vBox.setStyle("-fx-padding: 10 0 0 30");
        grid.setStyle("-fx-padding: 10 0 10 0;");
        vBox.getChildren().addAll(descbefore,grid,desc,desc1,desc3,desc4);
        StackPane stackPane = new StackPane(vBox);

        // 保存连接任务引用，用于验证步骤1
        //step1ConnectTask = connectTask;

        return stackPane;
    }








    // 步骤2：系统信息面板
    private static Node createStep2Content(Stage parent) {
        customInstallStepHbox1=new CustomInstallStepHbox("", "");
        customInstallStepHbox2=new CustomInstallStepHbox("", "");
        customInstallStepHbox3=new CustomInstallStepHbox("", "");
        customInstallStepHbox4=new CustomInstallStepHbox("", "");
        customInstallStepHbox5=new CustomInstallStepHbox("", "");
        customInstallStepHbox1.checkBox.setDisable(true);
        customInstallStepHbox2.checkBox.setDisable(true);
        customInstallStepHbox3.checkBox.setDisable(true);
        customInstallStepHbox4.checkBox.setDisable(true);
        customInstallStepHbox5.checkBox.setDisable(true);
        bindUninstallStepTexts();
        Label titleLabel = new Label();
        titleLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.title", "点击【下一步】开始卸载"));
        HBox titleHBox=new HBox(titleLabel);
        titleHBox.setAlignment(Pos.CENTER);
        VBox vBox=new VBox(6,titleHBox,customInstallStepHbox1,customInstallStepHbox2,customInstallStepHbox3,customInstallStepHbox4,customInstallStepHbox5);
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

    private static void bindUninstallStepTexts() {
        customInstallStepHbox1.nameLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step1.name", "关闭数据库进程"));
        customInstallStepHbox1.descLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step1.desc", "kill所有owner为gbasedbt用户进程。"));
        customInstallStepHbox2.nameLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step2.name", "删除安装目录"));
        customInstallStepHbox2.descLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step2.desc", "删除/GBASEDBTTMP/.infxdirs记录的所有目录，删除/GBASEDBTTMP目录，删除/opt/gbase。"));
        customInstallStepHbox3.nameLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step3.name", "删除数据文件"));
        customInstallStepHbox3.descLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step3.desc", "删除所有owner为gbasedbt用户的文件。"));
        customInstallStepHbox4.nameLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step4.name", "删除用户目录"));
        customInstallStepHbox4.descLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step4.desc", "如果存在/etc/gbasedbt，删除该目录。"));
        customInstallStepHbox5.nameLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step5.name", "删除用户及组"));
        customInstallStepHbox5.descLabel.textProperty().bind(I18n.bind("remote.uninstall.step2.step5.desc", "删除gbasedbt用户及gbasedbt组。"));
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

}
