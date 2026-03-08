package com.dbboys.app;

import com.dbboys.i18n.I18n;
import com.dbboys.util.ConfigManagerUtil;
import com.dbboys.util.CustomWindowFrameUtil;
import com.dbboys.ctrl.MainController;
import com.dbboys.customnode.CustomSqlEditCodeArea;
import com.dbboys.util.TabpaneUtil;
import com.dbboys.util.UpgradeUtil;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Version;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class Main extends Application {
    private static final Logger log = LogManager.getLogger(Main.class);

    private static final String VERSION_NAME = "DBboys V1.0.0beta.20260301";
    private static final int BUILD_NUMBER = 8;
    private static final String VERSION_URL = "";
    private static final String CHANGELOG = "";

    /** @deprecated Use {@link AppState#getVersion()} */
    @Deprecated public static Version VERSION;
    /** @deprecated Use {@link AppState#getMainController()} */
    @Deprecated public static MainController mainController;
    /** @deprecated Use {@link AppState#getScene()} */
    @Deprecated public static Scene scene;
    /** @deprecated Use {@link AppState#getLoadProgressBar()} */
    @Deprecated public static ProgressBar loadProgressBar = new ProgressBar(0.1);
    /** @deprecated Use {@link AppState#getLastInstallConnect()} */
    @Deprecated public static Connect lastInstallConnect;
    @Override
    public void start(Stage primaryStage) throws Exception {
        AppContext.init();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> AppErrorHandler.handle(e));

        //版本号
        JSONObject jsonObject=new JSONObject();
        jsonObject.put("version", VERSION_NAME);
        jsonObject.put("build", BUILD_NUMBER);
        jsonObject.put("url", VERSION_URL);
        jsonObject.put("changelog", CHANGELOG);
        VERSION=new Version(jsonObject);
        AppState.setVersion(VERSION);

        try {
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> AppErrorHandler.handle(e));

            //语言设置
            String uiLang = ConfigManagerUtil.getProperty("UI_LANG");
            if (uiLang != null && !uiLang.isBlank()) {
                I18n.setLocale(Locale.forLanguageTag(uiLang));
            }

            // 创建加载窗口
            Stage loadingStage = new Stage(StageStyle.UNDECORATED);
            Label loadLabel = new Label("DBboys Loading...");
            loadLabel.setStyle("-fx-font-weight: normal");
            StackPane loadStackpane = new StackPane(loadLabel);
            loadStackpane.getChildren().add(loadProgressBar);
            loadStackpane.setAlignment(loadProgressBar, Pos.BOTTOM_CENTER);
            //loadStackpane.setStyle("-fx-background-color: green;-fx-background-insets: 0");
            Scene bootscene = new Scene(loadStackpane,180,30);
            AppState.applyAppStylesheet(bootscene);
            loadProgressBar.getStyleClass().add("loadProgressBar");
            loadingStage.setScene(bootscene);

            // 显示加载窗口
            loadingStage.show();

            //使用线程后台加载界面
            AppExecutor.runAsync(() -> {
                //初始化数据库和配置文件
                Path dataDir = Paths.get("data");
                Path configFile = dataDir.resolve("dbboys.dat");
                if (Files.notExists(dataDir)) {
                    try {
                        Files.createDirectories(dataDir);
                    } catch (IOException e) {
                        log.error("创建data目录失败", e);
                    }
                    UpgradeUtil.initDefaultConfig();
                } else if (Files.notExists(configFile)) {
                    UpgradeUtil.initDefaultConfig();
                }
    


                //从配置文件读取分隔符位置，配置文件保存的是最后一次拖动的位置
                AppState.setSplit1Pos(Double.parseDouble(ConfigManagerUtil.getProperty("SPLIT_DRIVER_MAIN", "0.2")));
                AppState.setSplit2Pos(Double.parseDouble(ConfigManagerUtil.getProperty("SPLIT_DRIVER_SQL", "0.6")));

                //加载主界面
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dbboys/fxml/Main.fxml"));
                    Pane root = null;
                    try {
                        root = loader.load();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                mainController = loader.getController();
                AppState.setMainController(mainController);

                // 使用 CustomWindowFrameUtil：logo 和菜单放入框架标题栏，去掉原标题栏和边框拖拽层
                VBox mainVBox = mainController.mainVBox;
                HBox oldTitleBar = (HBox) mainVBox.getChildren().get(0);
                Node logo = oldTitleBar.getChildren().get(0);
                Node menuBar = oldTitleBar.getChildren().get(1);
                HBox titleBarLeft = new HBox(logo, menuBar);
                titleBarLeft.getStyleClass().add("window-title-bar-left");
                titleBarLeft.setAlignment(Pos.CENTER_LEFT);
                titleBarLeft.setStyle("-fx-background-color: transparent;");
                mainVBox.getChildren().remove(0);

                mainController.removeCustomFrameResizeLayersFromRoot();

                SimpleStringProperty titleProp = new SimpleStringProperty("DBboys");
                CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.create(
                        primaryStage, titleProp, root, 800, 600, titleBarLeft);
                scene = frame.scene;
                AppState.setScene(scene);

                primaryStage.setTitle("DBboys");
                Image image = new Image("file:images/logo.png");
                primaryStage.getIcons().add(image);
                primaryStage.initStyle(StageStyle.UNDECORATED);

                frame.closeButton.setOnAction(e -> {
                    if (mainController.requestClose()) {
                        mainController.performCloseAndExit();
                    }
                });

                // 在后台线程中预加载，避免首次点击时卡顿，首次打开sql编辑界面300+ms下降到50+ms
                AppExecutor.runAsync(() -> {
                    try {
                        // 预加载FXML文件
                        FXMLLoader sqlTabLoader = new FXMLLoader(getClass().getResource("/com/dbboys/fxml/SqlTab.fxml"));
                        sqlTabLoader.load();
                        
                        //FXMLLoader resultSetLoader = new FXMLLoader(getClass().getResource("/com/dbboys/fxml/ResultSetTab.fxml"));
                        //resultSetLoader.load();
                        
                        // 预创建CustomSqlEditCodeArea实例
                        new CustomSqlEditCodeArea();
                    } catch (IOException e) {
                        log.error("预加载资源失败", e);
                    }
                });
    


                //窗口切换
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    Thread.currentThread().setUncaughtExceptionHandler((t, e) -> AppErrorHandler.handle(e));
                    primaryStage.setScene(scene);
                        //打开软件默认打开一个sql编辑面板
                        //StageStyle.UNDECORATED
                        //StageStyle.DECORATED
                        //primaryStage.setMaximized(true);
                        loadingStage.hide();
                        primaryStage.show();//此处有黑色闪现，不使用系统自带窗口后正常
                        // 根据当前选中的左侧 Tab 设置初始分割线位置（AI 标签时分割线最右）
                        if (mainController.treeviewTabPane.getSelectionModel().getSelectedItem() instanceof com.dbboys.customnode.CustomTreeviewTab currentLeft) {
                            if (currentLeft == mainController.aiTab) {
                                mainController.mainSplitPane.setDividerPositions(1.0);
                            } else {
                                mainController.mainSplitPane.setDividerPositions(AppState.getSplit1Pos());
                            }
                        }
                        //页面渲染后增加监听,否则无法正常监听
                        mainController.mainSplitPane.lookupAll(".split-pane-divider").forEach(divider -> {
                            // 鼠标拖动事件
                            divider.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
                                //ConfigManagerUtil.setProperty("SPLIT_DRIVER_MAIN", String.valueOf(split1Pos));
                                AppState.setSplit1Pos(mainController.mainSplitPane.getDividers().get(0).getPosition());
                            });
                        });

                        /*双击空白区域增加tab*/
                        if (mainController.sqlTabPane.lookup(".tab-header-area") != null) {
                            mainController.sqlTabPane.lookup(".tab-header-area").addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                                    // 检查双击的是否是 tab 空白区域（非 tab 或标签）
                                    var targetClass = event.getTarget().getClass().getName();
                                    if (!targetClass.contains("Tab") && !targetClass.contains("Label")) {
                                       // if(mainController.treeviewTabPane.getSelectionModel().getSelectedIndex()==0){
                                            TabpaneUtil.addCustomSqlTab(null);
                                    }
                                }
                            });
                        }

                        //打开软件默认最大化
                        CustomWindowFrameUtil.requestMaximize(frame);
                        //ResizeHelper.addResizeListener(primaryStage);
                        log.info("dbboys已启动。");

                    }
                });

            });

        } catch(Exception e) {
            log.error("Operation failed", e);
        }

    }
    
    public static void main(String[] args) {

        launch(args);
    }
}




