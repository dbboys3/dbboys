package com.dbboys.ctrl;

import com.dbboys.app.*;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.util.*;
import com.dbboys.util.tree.TreeViewUtil;
import com.dbboys.vo.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Objects;
import java.util.Locale;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;


public class MainController {
    private static final Logger log = LogManager.getLogger(MainController.class);

    @FXML
    private Region windowTitleBlank;
    @FXML
    private Button windowMinimizeButton;
    @FXML
    public Button windowMaximizeButton;
    @FXML
    private Button windowCloseButton;
    @FXML
    private Pane resizeLayerRight;
    @FXML
    private Pane resizeLayerLeft;
    @FXML
    private Pane resizeLayerBottom;
    @FXML
    private Pane resizeLayerTop;
    @FXML
    private Pane resizeLayerBottom_left;
    @FXML
    private Pane resizeLayerBottom_right;
    @FXML
    private StackPane root;
    @FXML
    public VBox mainVBox;
    @FXML
    public TabPane treeviewTabPane;
    @FXML
    public CustomTreeviewTab connectTab;
    @FXML
    public CustomTreeviewTab markdownTab;
    @FXML
    private Menu menuFile;
    @FXML
    private CustomShortcutMenuItem menuFileAddFolder;
    @FXML
    private CustomShortcutMenuItem menuFileNewConnection;
    @FXML
    private CustomShortcutMenuItem menuFileDisconnectAll;
    @FXML
    private CustomShortcutMenuItem menuFileOpenSql;
    @FXML
    private Menu menuConfig;
    @FXML
    private CustomShortcutMenuItem menuConfigCheckEnv;
    @FXML
    private MenuItem menuConfigInstallGbase;
    @FXML
    private CustomShortcutMenuItem menuConfigUninstallGbase;
    @FXML
    private Menu menuSettings;
    @FXML
    private Menu menuSettingsLanguage;
    @FXML
    private CustomShortcutMenuItem menuSettingsLanguageZh;
    @FXML
    private CustomShortcutMenuItem menuSettingsLanguageEn;
    @FXML
    private CustomShortcutMenuItem menuSettingsLanguageZhTw;
    @FXML
    private CustomShortcutMenuItem menuSettingsReset;
    @FXML
    private Menu menuHelp;
    @FXML
    private CustomShortcutMenuItem menuHelpAbout;
    @FXML
    private CustomShortcutMenuItem menuHelpCommunity;
    @FXML
    private CustomShortcutMenuItem menuHelpCheckUpdate;
    @FXML
    private Label appLogoLabel;
    @FXML
    public CustomUserTextField connectSearchTextField;
    @FXML
    public CustomUserTextField markdownSearchTextField;
    @FXML
    public Button create_connect;
    @FXML
    public Button connectSearchButton;
    @FXML
    public Button markdownSearchButton;
    @FXML
    public TreeView<TreeData> databaseMetaTreeView;
    @FXML
    public VBox markdownTreeViewVBox;
    @FXML
    public CustomShortcutMenuItem newSqlFileMenuItem;
    @FXML
    public SplitPane mainSplitPane;
    @FXML
    public TabPane sqlTabPane;
    @FXML
    public StackPane noticePane;
    @FXML
    public HBox statusHBox;
    @FXML
    public Button statusBackSqlStopButton;
    @FXML
    public Label statusBackSqlCountLabel;
    @FXML
    public Button statusBackSqlListButton;
    @FXML
    public ImageView statusBackSqlProgress;
    @FXML
    public Button snapshotRootButton;
    @FXML
    private Label doubleClickNewSqlLabel;
    @FXML
    public StackPane rebuildMarkdownIndexButton_stackpane;
    @FXML
    public Button rebuildMarkdownIndexButton;
    @FXML
    public StackPane markdownSearchIconStackPane;
    @FXML
    public StackPane downloadStackPane;

    private Boolean windowMaximized=false;
    private Double windowMaxPrevX=0.0;
    private Double windowMaxPrevY=0.0;
    private Double windowMaxPrevWidth=800.0;
    private Double windowMaxPrevHeight=600.0;
    private Double windowX;  //拖动前X坐标
    private Double windowXPos;  //最大化的时候拖动记录鼠标位置，自动缩小后需要自动设置坐标保持鼠标在相同的相对位置
    private Double windowY;  //拖动前Y坐标


    //public Connect sqlConnect=new Connect();

    public void initialize() {
        //UpgradeUtil.initDefaultConfig();  //初始化
        Main.loadProgressBar.setProgress(0.2);
        initMarkdownIndex(false);
        Main.loadProgressBar.setProgress(0.4);
        initI18nBindings();
        setupMainIcons();
        Main.loadProgressBar.setProgress(0.5);
        initDownloadVisibility();
        initSidebarTabs();
        initSidebarSearch();
        Main.loadProgressBar.setProgress(0.6);
        initMarkdownPanel();
        initSqlTabInteractions();
        initMenuActions();
        Main.loadProgressBar.setProgress(0.7);
        initSplitPaneResizeBehavior();
        initWindowControls();
        initTreeView();
        initStatusBar();
        Main.loadProgressBar.setProgress(0.8);
        initBackgroundTasks();
        Main.loadProgressBar.setProgress(1);
    }
    //初始化界面完成




    private void initMarkdownIndex(boolean isNeedNotice){
        Path dataDir = Paths.get("index");
        if (Files.notExists(dataDir)) {
            MarkdownSearchUtil.buildIndex(isNeedNotice);
        } 
    }

    private void initDownloadVisibility() {
        //如果没有下载，界面不显示
        downloadStackPane.visibleProperty().bind(
                Bindings.size(downloadStackPane.getChildren()).greaterThan(0)
        );
        //downloadStackPane.managedProperty().bind(downloadStackPane.visibleProperty());
    }

    private void setupMainIcons() {
        appLogoLabel.setGraphic(IconFactory.imageView(IconPaths.MAIN_LOGO, 18, 18, false));
        menuFileAddFolder.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_ADD_FOLDER, 0.65, Color.valueOf("#074675")));
        menuFileNewConnection.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_NEW_CONNECTION, 0.65, Color.valueOf("#074675")));
        menuFileDisconnectAll.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_DISCONNECT_ALL, 0.6, Color.valueOf("#074675")));
        newSqlFileMenuItem.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_NEW_SQL, 0.55, Color.valueOf("#074675")));
        menuFileOpenSql.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_OPEN_SQL, 0.62, Color.valueOf("#074675")));
        menuConfigCheckEnv.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_CHECK_ENV, 0.5, Color.valueOf("#074675")));
        menuConfigInstallGbase.setGraphic(IconFactory.group(IconPaths.GBASE_LOGO, 0.22, Color.valueOf("#074675")));
        menuConfigUninstallGbase.setGraphic(IconFactory.group(IconPaths.GBASE_LOGO, 0.22, Color.valueOf("#9f453c")));
        menuSettingsLanguage.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_LANGUAGE, 0.68, Color.valueOf("#074675")));
        menuSettingsReset.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_RESET, 0.6, Color.valueOf("#074675")));
        menuHelpAbout.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_HELP, 0.6, Color.valueOf("#074675")));
        menuHelpCommunity.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_COMMUNITY, 0.5, Color.valueOf("#074675")));
        menuHelpCheckUpdate.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_HOME, 0.5, Color.valueOf("#074675")));

        create_connect.setGraphic(IconFactory.group(IconPaths.MAIN_ADD_CONNECT, 0.65, Color.valueOf("#074675")));
        connectSearchButton.setGraphic(IconFactory.group(IconPaths.MAIN_SEARCH, 0.65, Color.valueOf("#074675")));
        rebuildMarkdownIndexButton.setGraphic(IconFactory.group(IconPaths.MAIN_REBUILD, 0.65, Color.valueOf("#074675")));
        markdownSearchButton.setGraphic(IconFactory.group(IconPaths.MAIN_SEARCH, 0.65, Color.valueOf("#074675")));
        statusBackSqlStopButton.setGraphic(IconFactory.group(IconPaths.SQL_STOP, 0.5, Color.valueOf("#9f453c")));
        statusBackSqlListButton.setGraphic(IconFactory.group(IconPaths.MAIN_STATUS_LIST, 0.45, Color.valueOf("#074675")));
        statusBackSqlProgress.setImage(new Image(IconPaths.LOADING_GIF));
        statusBackSqlProgress.setFitWidth(10);
        statusBackSqlProgress.setFitHeight(10);
        statusBackSqlProgress.setPreserveRatio(true);
        snapshotRootButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35, Color.valueOf("#074675")));
    }

    private void initSqlTabInteractions() {
        //拖动表或视图响应
        sqlTabPane.setOnDragOver(event -> {
            if (event.getGestureSource() != sqlTabPane && event.getDragboard().hasString()) {
                Dragboard db = event.getDragboard();
                if (MarkdownUtil.sourceTreeItems==null||(MarkdownUtil.sourceTreeItems.size()==1)&&(!new File(db.getString().replace(";","")).isDirectory())) {
                    event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                }
            }
            event.consume();
        });

        sqlTabPane.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasString()) {  //拖入表或库
                event.setDropCompleted(true);
                if(!db.getString().equals("DATABASEOBJECTDRAG")){
                    if(db.getString().endsWith(".md")){
                        TabpaneUtil.addCustomMarkdownTab(new File(db.getString()),false);
                    }else{
                        NotificationUtil.showMainNotification(I18n.t("main.notice.unsupported_markdown_file"));
                    }
                }

            }else{
                event.setDropCompleted(false);
            }
            event.consume();
        });
        //拖动表或视图响应结束

        //切换tab时，sql编辑器获取焦点，以确保响应各类鼠标事件
        sqlTabPane.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            if (newTab != null) {
                if(newTab instanceof CustomSqlTab){
                    Platform.runLater(() -> {
                        ((CustomSqlTab)newTab).sqlTabController.sqlEditCodeArea.requestFocus();                        // 可选：将光标移动到文本末尾
                    });
                }
            }
        });

        sqlTabPane.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY &&event.getClickCount() == 2) {
                TabpaneUtil.addCustomSqlTab(null);
            }
        });
    }

    private void initSidebarTabs() {
        //左侧连接面板与知识库面板
        // 标题文本与提示由国际化绑定管理

        markdownTab.titleToggle.setOnContextMenuRequested(event -> {
            if(markdownTab.titleToggle.isSelected()){
                MarkdownUtil.treeView.getSelectionModel().clearSelection();
                MarkdownUtil.contextMenu.show(markdownTab.titleToggle, event.getScreenX(), event.getScreenY());
            }
        });
        // tooltip is bound in initI18nBindings
        markdownTab.titleToggleIcon.setContent("M21.0469 4.4844 Q21.5938 4.4844 22.0312 4.9219 Q22.4844 5.3594 22.4844 6 L22.4844 18 Q22.4844 18.6406 22.0312 19.0781 Q21.5938 19.5156 21.0469 19.5156 L3.0469 19.5156 Q2.4062 19.5156 1.9531 19.0781 Q1.5156 18.6406 1.5156 18 L1.5156 6 Q1.5156 5.3594 1.9531 4.9219 Q2.4062 4.4844 2.9531 4.4844 L21.0469 4.4844 ZM3.0469 2.9531 Q1.7656 2.9531 0.875 3.8438 Q0 4.7188 0 6 L0 18 Q0 19.2812 0.875 20.1719 Q1.7656 21.0469 2.9531 21.0469 L21.0469 21.0469 Q22.2344 20.9531 23.1094 20.125 Q24 19.2812 24 18 L24 6 Q24 4.7188 23.1094 3.8438 Q22.2344 2.9531 20.9531 2.9531 L3.0469 2.9531 ZM13.6875 12.2344 Q13.9219 12 14.2344 12 Q14.5625 12 14.7969 12.2344 L17.2812 14.7188 L19.6875 12.2344 Q19.9219 12 20.2344 12 Q20.5625 12 20.7969 12.2031 Q21.0469 12.4062 21.0469 12.7344 Q21.0469 13.0469 20.7969 13.2812 L17.7656 16.3125 Q17.5938 16.4844 17.2656 16.4844 Q16.9531 16.4844 16.7188 16.3125 L13.6875 13.2812 Q13.5156 13.0469 13.5156 12.7344 Q13.5156 12.4062 13.6875 12.2344 ZM17.2812 7.5156 Q17.5938 7.5156 17.7969 7.7188 Q18 7.9219 18 8.2344 L18 14.2344 Q18 14.5625 17.7969 14.8125 Q17.5938 15.0469 17.2656 15.0469 Q16.9531 15.0469 16.7188 14.8125 Q16.4844 14.5625 16.4844 14.2344 L16.4844 8.2344 Q16.4844 7.9219 16.7188 7.7188 Q16.9531 7.5156 17.2812 7.5156 ZM5.3594 16.4844 L5.3594 10.4844 L5.4375 10.4844 L7.5938 15.3594 L8.7188 15.3594 L10.875 10.4844 L10.9531 10.4844 L10.9531 16.4844 L12.5625 16.4844 L12.5625 7.5156 L10.7188 7.5156 L8.1562 13.3594 L8.1562 13.3594 L5.5938 7.5156 L3.7656 7.5156 L3.7656 16.4844 L5.3594 16.4844 Z");

        //左侧tabpane默认选中上次关闭前tab，没有配置则默认第一个tab
        int defaultIndex = 0;
        int preferredIndex = defaultIndex;
        String preferredIndexStr = ConfigManagerUtil.getProperty("DEFAULT_LISTVIEW_TAB", String.valueOf(defaultIndex));
        if (preferredIndexStr != null) {
            try {
                preferredIndex = Integer.parseInt(preferredIndexStr);
            } catch (NumberFormatException e) {
                preferredIndex = defaultIndex;
            }
        }
        if (!treeviewTabPane.getTabs().isEmpty()) {
            if (preferredIndex < 0 || preferredIndex >= treeviewTabPane.getTabs().size()) {
                preferredIndex = defaultIndex;
            }
            Tab tab = treeviewTabPane.getTabs().get(preferredIndex);
            treeviewTabPane.getSelectionModel().select(tab);
            ((CustomTreeviewTab) tab).titleToggle.setSelected(true);
        }
    }

    private void initSidebarSearch() {
        //搜索事件
        connectSearchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            connectSearchTextField.setText(newValue.replace(" ", ""));
            if(!connectSearchTextField.getText().equals(oldValue.replace(" ", ""))){
                String searchText=connectSearchTextField.getText();
                if (!searchText.isEmpty()&&searchText.length()>=2) {
                    TreeViewUtil.searchTree(databaseMetaTreeView,searchText,connectSearchButton);
                }else{
                    connectSearchButton.setDisable(true);
                }
            }

        });
    }

    private void initMarkdownPanel() {
        markdownTreeViewVBox.getChildren().add(MarkdownUtil.treeView);

        Label rebuild_index_running_icon=new Label();
        ImageView loading_icon = IconFactory.loadingImageView(0.7);
        rebuild_index_running_icon.setGraphic(loading_icon);
        Tooltip tooltip=new Tooltip();
        tooltip.textProperty().bind(I18n.bind("main.tooltip.rebuild_index_running"));
        tooltip.setShowDelay(Duration.millis(100));
        rebuild_index_running_icon.setTooltip(tooltip);
        rebuildMarkdownIndexButton_stackpane.getChildren().add(rebuild_index_running_icon);
        rebuild_index_running_icon.visibleProperty().bind(rebuildMarkdownIndexButton.visibleProperty().not());

        Label sreach_running_label=new Label();
        ImageView sreach_running_icon = IconFactory.loadingImageView(0.7);
        sreach_running_label.setGraphic(sreach_running_icon);
        Tooltip sreach_running_tooltip=new Tooltip();
        sreach_running_tooltip.textProperty().bind(I18n.bind("main.tooltip.searching"));
        sreach_running_tooltip.setShowDelay(Duration.millis(100));
        sreach_running_label.setTooltip(sreach_running_tooltip);
        markdownSearchIconStackPane.getChildren().add(sreach_running_label);
        sreach_running_label.visibleProperty().bind(markdownSearchButton.visibleProperty().not());

        markdownSearchButton.setOnAction(event -> {

            AppExecutor.runAsync(() -> {
                Platform.runLater(()->{
                    markdownSearchButton.setVisible(false);
                });
                MarkdownSearchUtil.performSearch(markdownSearchTextField.getText());
                Platform.runLater(()->{
                    markdownSearchButton.setVisible(true);
                });
            });

        });

        markdownSearchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if(markdownSearchTextField.getText().isEmpty()){
                markdownSearchButton.setDisable(true);
            }else{
                markdownSearchButton.setDisable(false);
            }
        });

        markdownSearchTextField.setOnKeyPressed(event -> {
            // 判断按下的是否为 Enter 键（包含普通 Enter 和小键盘 Enter）
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if(!markdownSearchButton.isDisable()&&markdownSearchButton.isVisible()){
                    markdownSearchButton.fire();
                }
            }
        });
    }

    private void initMenuActions() {
        newSqlFileMenuItem.setOnAction(event -> {
            TabpaneUtil.addCustomSqlTab(null);});

        fixSubmenuAutoClose(menuSettings);
    }

    private void fixSubmenuAutoClose(Menu parentMenu) {
        for (MenuItem item : parentMenu.getItems()) {
            if (item instanceof CustomShortcutMenuItem csmi && csmi.getContent() != null) {
                csmi.getContent().addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
                    for (MenuItem sibling : parentMenu.getItems()) {
                        if (sibling instanceof Menu sub && sub.isShowing()) {
                            sub.hide();
                        }
                    }
                });
            }
        }
    }

    private void initI18nBindings() {
        bindText(menuFile, "main.menu.file");
        bindText(menuFileAddFolder, "main.menu.file.add_folder");
        bindText(menuFileNewConnection, "main.menu.file.new_connection");
        bindText(menuFileDisconnectAll, "main.menu.file.disconnect_all");
        bindText(newSqlFileMenuItem, "main.menu.file.new_sql");
        bindText(menuFileOpenSql, "main.menu.file.open_sql");

        bindText(menuConfig, "main.menu.config");
        bindText(menuConfigCheckEnv, "main.menu.config.check_env");
        bindText(menuConfigInstallGbase, "main.menu.config.install_gbase");
        bindText(menuConfigUninstallGbase, "main.menu.config.uninstall_gbase");

        bindText(menuSettings, "main.menu.settings");
        bindText(menuSettingsLanguage, "main.menu.settings.language");
        bindText(menuSettingsLanguageZh, "main.menu.settings.language.zh");
        bindText(menuSettingsLanguageEn, "main.menu.settings.language.en");
        bindText(menuSettingsLanguageZhTw, "main.menu.settings.language.zh_tw");
        bindText(menuSettingsReset, "main.menu.settings.reset");

        bindText(menuHelp, "main.menu.help");
        bindText(menuHelpAbout, "main.menu.help.about");
        bindText(menuHelpCommunity, "main.menu.help.community");
        bindText(menuHelpCheckUpdate, "main.menu.help.check_update");

        bindText(doubleClickNewSqlLabel, "main.center.double_click_new_sql");
        bindText(statusBackSqlCountLabel, "main.status.no_background_tasks");

        if (connectTab != null && connectTab.titleToggle != null) {
            connectTab.titleToggle.textProperty().bind(I18n.bind("main.sidebar.connections"));
            bindTooltip(connectTab.titleToggle, "main.sidebar.connections");
        }
        if (markdownTab != null && markdownTab.titleToggle != null) {
            markdownTab.titleToggle.textProperty().bind(I18n.bind("main.sidebar.knowledge"));
            bindTooltip(markdownTab.titleToggle, "main.tooltip.knowledge_base");
        }
        bindTabText(connectTab, "main.sidebar.connections");
        bindTabText(markdownTab, "main.sidebar.knowledge");

        bindPrompt(connectSearchTextField, "main.prompt.search_objects");
        bindPrompt(markdownSearchTextField, "main.prompt.search_knowledge");

        bindTooltip(create_connect, "main.tooltip.new_connection");
        bindTooltip(connectSearchTextField, "main.tooltip.search_objects_hint");
        bindTooltip(connectSearchButton, "main.tooltip.search_next");
        bindTooltip(rebuildMarkdownIndexButton, "main.tooltip.rebuild_index");
        bindTooltip(markdownSearchTextField, "main.tooltip.case_insensitive");
        bindTooltip(markdownSearchButton, "main.tooltip.start_search");
        bindTooltip(statusBackSqlStopButton, "main.tooltip.stop_all_tasks");
        bindTooltip(statusBackSqlListButton, "main.tooltip.view_task_list");
        bindTooltip(snapshotRootButton, "main.tooltip.snapshot_to_clipboard");
    }

    private void bindText(Labeled labeled, String key) {
        if (labeled != null) {
            labeled.textProperty().bind(I18n.bind(key));
        }
    }

    private void bindText(MenuItem item, String key) {
        if (item != null) {
            item.textProperty().bind(I18n.bind(key));
        }
    }

    private void bindTabText(Tab tab, String key) {
        if (tab != null) {
            tab.textProperty().bind(I18n.bind(key));
        }
    }

    private void bindPrompt(TextInputControl input, String key) {
        if (input != null) {
            input.promptTextProperty().bind(I18n.bind(key));
        }
    }

    private void bindTooltip(Control control, String key) {
        if (control == null) {
            return;
        }
        Tooltip tooltip = control.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip();
            control.setTooltip(tooltip);
        }
        tooltip.textProperty().bind(I18n.bind(key));
    }

    private void initSplitPaneResizeBehavior() {
        mainSplitPane.widthProperty().addListener((obs, oldVal, newVal) -> {
            if(Main.sqledit_codearea_is_max==0) {
                //保留两位小数设置，否则可能因为小数过多而设置不准
                Platform.runLater(() -> {
                    mainSplitPane.setDividerPositions(AppState.getSplit1Pos());
                });
            }else{
                Platform.runLater(() -> {
                    mainSplitPane.setDividerPositions(0);
                });
            }
        });
    }

    private void initWindowControls() {
        windowTitleBlank.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY) {
                windowMaximizeButton.fire();
                // 你可以在这里执行最大化窗口、缩放等操作
            }
        });

        windowTitleBlank.setOnMousePressed(event -> {
            Stage stage = (Stage) windowTitleBlank.getScene().getWindow();
            windowX = event.getScreenX() - stage.getX();
            windowY = event.getScreenY() - stage.getY();
            windowXPos=event.getScreenX()/stage.getWidth();
        });

        windowTitleBlank.setOnMouseDragged(event -> {
            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            if(windowMaximized){
                windowMaximizeButton.fire();
                stage.setX(event.getScreenX()-stage.getWidth()*windowXPos);
                windowX = event.getScreenX() - stage.getX();
                //windowY = event.getScreenY() - stage.getY();
            }
            else {
                stage.setX(event.getScreenX() - windowX);
                stage.setY(event.getScreenY() - windowY);
            }
        });

        //上下左右隐藏拖动边框事件
        resizeLayerRight.prefHeightProperty().bind(root.heightProperty());
        resizeLayerRight.setOnMouseDragged(event -> {
            if(!windowMaximized) {
                resizeLayerRight.setCursor(Cursor.E_RESIZE);
                Stage stage = (Stage) root.getScene().getWindow();
                double newWidth = event.getSceneX();
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                }
            }
        });

        resizeLayerRight.setOnMouseMoved(event -> {
            if(!windowMaximized) {
                resizeLayerRight.setCursor(Cursor.E_RESIZE);
            }
        });

        resizeLayerRight.setOnMouseExited(event -> {
            resizeLayerRight.setCursor(Cursor.DEFAULT);
        });

        resizeLayerBottom.prefWidthProperty().bind(root.widthProperty());
        resizeLayerBottom.setOnMouseDragged(event -> {
            if(!windowMaximized) {
                resizeLayerBottom.setCursor(Cursor.S_RESIZE);
                Stage stage = (Stage) root.getScene().getWindow();
                double newHeight = event.getSceneY();
                //拖动不超过任务栏
                if (newHeight > stage.getMinHeight()&&(event.getSceneY()+stage.getY()<Screen.getPrimary().getVisualBounds().getHeight())) {
                    stage.setHeight(newHeight);
                }
            }
        });

        resizeLayerBottom.setOnMouseMoved(event -> {
            if(!windowMaximized) {
                resizeLayerBottom.setCursor(Cursor.S_RESIZE);
            }
        });

        resizeLayerBottom.setOnMouseExited(event -> {
            resizeLayerBottom.setCursor(Cursor.DEFAULT);
        });

        resizeLayerLeft.prefHeightProperty().bind(root.heightProperty());
        resizeLayerLeft.setOnMouseDragged(event -> {
            if(!windowMaximized) {
                resizeLayerLeft.setCursor(Cursor.W_RESIZE);
                Stage stage = (Stage) root.getScene().getWindow();
                double deltaX = event.getScreenX() - stage.getX();
                double newWidth = stage.getWidth() - deltaX; // 新宽度，保持右边界不变

                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);  // 设置新的宽度
                    stage.setX(event.getScreenX()); // 调整窗口位置，保持左边界与鼠标对齐
                }
            }
        });

        resizeLayerLeft.setOnMouseMoved(event -> {
            if(!windowMaximized) {
                resizeLayerLeft.setCursor(Cursor.W_RESIZE);
            }
        });

        resizeLayerLeft.setOnMouseExited(event -> {
            resizeLayerLeft.setCursor(Cursor.DEFAULT);
        });

        resizeLayerTop.prefWidthProperty().bind(root.widthProperty());
        resizeLayerTop.setOnMouseDragged(event -> {
            if(!windowMaximized) {
                resizeLayerTop.setCursor(Cursor.N_RESIZE);
                Stage stage = (Stage) root.getScene().getWindow();
                double deltaY = event.getScreenY() - stage.getY();
                double newHeight = stage.getHeight() - deltaY; // 新宽度，保持右边界不变
                if (newHeight > stage.getMinHeight()) {
                    stage.setHeight(newHeight);  // 设置新的宽度
                    stage.setY(event.getScreenY()); // 调整窗口位置，保持左边界与鼠标对齐
                }

            }
        });

        resizeLayerTop.setOnMouseMoved(event -> {
            if(!windowMaximized) {
                resizeLayerTop.setCursor(Cursor.N_RESIZE);
            }
        });

        resizeLayerTop.setOnMouseExited(event -> {
            resizeLayerTop.setCursor(Cursor.DEFAULT);
        });

        resizeLayerBottom_left.setOnMouseDragged(event -> {
            if(!windowMaximized) {
                resizeLayerBottom_left.setCursor(Cursor.SW_RESIZE);
                Stage stage = (Stage) root.getScene().getWindow();
                double newHeight = event.getSceneY();
                //拖动不超过任务栏
                if (newHeight > stage.getMinHeight()&&(event.getSceneY()+stage.getY()<Screen.getPrimary().getVisualBounds().getHeight())) {
                    stage.setHeight(newHeight);
                }
                double deltaX = event.getScreenX() - stage.getX();
                double newWidth = stage.getWidth() - deltaX; // 新宽度，保持右边界不变

                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);  // 设置新的宽度
                    stage.setX(event.getScreenX()); // 调整窗口位置，保持左边界与鼠标对齐
                }
            }
        });

        resizeLayerBottom_left.setOnMouseMoved(event -> {
            if(!windowMaximized) {
                resizeLayerBottom_left.setCursor(Cursor.SW_RESIZE);
            }
        });

        resizeLayerBottom_left.setOnMouseExited(event -> {
            resizeLayerBottom_left.setCursor(Cursor.DEFAULT);
        });

        resizeLayerBottom_right.setOnMouseDragged(event -> {
            if(!windowMaximized) {
                resizeLayerBottom_right.setCursor(Cursor.SE_RESIZE);
                Stage stage = (Stage) root.getScene().getWindow();
                double newHeight = event.getSceneY();
                //拖动不超过任务栏
                if (newHeight > stage.getMinHeight()&&(event.getSceneY()+stage.getY()<Screen.getPrimary().getVisualBounds().getHeight())) {
                    stage.setHeight(newHeight);
                }
                double newWidth = event.getSceneX();
                if (newWidth > stage.getMinWidth()) {
                    stage.setWidth(newWidth);
                }
            }
        });

        resizeLayerBottom_right.setOnMouseMoved(event -> {
            if(!windowMaximized) {
                resizeLayerBottom_right.setCursor(Cursor.SE_RESIZE);
            }
        });

        resizeLayerBottom_right.setOnMouseExited(event -> {
            resizeLayerBottom_right.setCursor(Cursor.DEFAULT);
        });

        //窗口最大化按钮
        windowMaximizeButton.setGraphic(IconFactory.group(IconPaths.WINDOW_RESTORE, 0.4, Color.valueOf("#000")));
        windowMinimizeButton.setGraphic(IconFactory.group(IconPaths.WINDOW_MINIMIZE, 0.45, Color.valueOf("#000")));
        windowMinimizeButton.setOnAction(event->{
            Stage primaryStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            primaryStage.setIconified(true);
        });
        windowMaximizeButton.setOnAction(event -> {
            Stage primaryStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            if (windowMaximized) {
                // 还原

                windowMaximizeButton.setGraphic(IconFactory.group(IconPaths.WINDOW_MAXIMIZE, 0.55, Color.valueOf("#000")));
                primaryStage.setX(windowMaxPrevX);
                primaryStage.setY(windowMaxPrevY);
                primaryStage.setWidth(windowMaxPrevWidth);
                primaryStage.setHeight(windowMaxPrevHeight);

            } else {
                // 记录原始位置和大小

                windowMaximizeButton.setGraphic(IconFactory.group(IconPaths.WINDOW_RESTORE, 0.4, Color.valueOf("#000")));

                windowMaxPrevX = primaryStage.getX();
                windowMaxPrevY = primaryStage.getY();
                windowMaxPrevWidth = primaryStage.getWidth();
                windowMaxPrevHeight = primaryStage.getHeight();

                // 最大化为屏幕尺寸
                primaryStage.setX(0);
                primaryStage.setY(0);
                primaryStage.setWidth(Screen.getPrimary().getVisualBounds().getWidth());
                primaryStage.setHeight(Screen.getPrimary().getVisualBounds().getHeight());

            }
            windowMaximized = !windowMaximized;
        });

        //窗口关闭按钮

        windowCloseButton.setOnAction(event->{
            Stage primaryStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            Boolean sureToclosed=true;
            for (Tab tab : sqlTabPane.getTabs()) {
                if (tab.getText().startsWith("*")) {
                    if(!AlertUtil.CustomAlertConfirm(I18n.t("common.hint"), I18n.t("main.confirm.unsaved_sql_close"))){
                        sureToclosed=false;
                        event.consume();
                    }
                    break;
                }
            }

            //如果回答确认，执行关闭流程
            if(sureToclosed) {
                //disconnectAll(); //关闭所有连接,取消此操作，如果连接已中断会导致关闭卡顿，软件关闭会自动关闭连接
                String split1Content = String.valueOf(AppState.getSplit1Pos());
                String split2Content = String.valueOf(AppState.getSplit2Pos());
                ConfigManagerUtil.setProperty("SPLIT_DRIVER_MAIN", split1Content);
                ConfigManagerUtil.setProperty("SPLIT_DRIVER_SQL", split2Content);
                //log.info("开始执行primaryStage.close()。");
                primaryStage.close();
                //log.info("结束执行primaryStage.close()，开始执行System.exit(0)。");
                System.exit(0); //避免executorService线程未关闭
                log.info("dbboys已关闭。");
            }
        });
    }

    private void initTreeView() {
        //连接列表上面的连接管理面板结束

        //设置树根节点
        // 初始化显示连接树
        try {
            TreeViewUtil.initDatabaseObjectsTreeview(databaseMetaTreeView);
        }catch (Exception e){
        }
    }

    private void initStatusBar() {
        //状态栏
        snapshotRootButton.setOnAction(event->{
            SnapshotUtil.snapshotRoot();

        });

        statusBackSqlStopButton.setOnAction(event->{
            Iterator<BackgroundSqlTask> iterator = BackgroundSqlUtil.backSqlTaskList.iterator();
            while (iterator.hasNext()) {
                BackgroundSqlTask bgsql = iterator.next();
                bgsql.cancel();
            }

            NotificationUtil.showNotification(noticePane, I18n.t("main.notice.cancelled_all_tasks"));

        });

        statusBackSqlListButton.setOnAction(event->{
            PopupWindowUtil.openSqlTaskPopupWindow();
        });
        statusBackSqlListButton.disableProperty().bind(statusBackSqlStopButton.disableProperty());

        statusBackSqlProgress.visibleProperty().bind(statusBackSqlStopButton.disableProperty().not());
    }

    private void initBackgroundTasks() {
        //清理sql历史记录保留1000行
        AppExecutor.runAsync(() -> LocalDbRepository.deleteSqlHistory());
        //运行一次搜索，避免首次搜索慢
        AppExecutor.runAsync(() -> MarkdownSearchUtil.warmUpIndex());
    }

    //初始化数据库，响应恢复出厂设置
    public void initDB() {
        UpgradeUtil.initDB();
    }
    public void community()  {
        CustomGenericStyledArea.openUrl("https://www.dbboys.com");
    }
    public void checkVersion()  {
        UpgradeUtil.checkVersion();
    }




    public void aboutDBboys() {
        PopupWindowUtil.openAboutWindow();
    }

    //文件-新建连接响应函数

    public void createConnectLeaf(){
        TreeViewUtil.showCreateConnectDialog(null,false);
    }


    public void rebuildMarkdownIndex(){
        MarkdownSearchUtil.buildIndex();
    }



    //文件-新建连接分类响应函数

    public void createConnectFolder() {
        TreeViewUtil.createConnectFolder(databaseMetaTreeView);
    }


    //文件-断开所有连接响应函数
    public void disconnectAll() {
        for (TreeItem<TreeData> ti : AppState.getDatabaseMetaTreeView().getRoot().getChildren()) {
            TreeViewUtil.disconnectFolder(ti);
        }
    };


    public void openSqlFile(){
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.t("main.filechooser.select_sql"));
        File selectedFile = fileChooser.showOpenDialog(windowCloseButton.getScene().getWindow());
        if (selectedFile != null) {
            String tabName = selectedFile.getName();
            Boolean isOpened = false;
            for (Tab tab : sqlTabPane.getTabs()) {
                if (tab instanceof CustomSqlTab customSqlTab
                        && customSqlTab.filePath.equals(selectedFile.getAbsolutePath())) {
                    isOpened = true;
                    sqlTabPane.getSelectionModel().select(tab);
                    break;
                }
            }
            if (!isOpened) {
                CustomSqlTab newtab = new CustomSqlTab(tabName);
                newtab.filePath=selectedFile.getAbsolutePath();
                newtab.openSqlFile();
                sqlTabPane.getTabs().add(newtab);
                sqlTabPane.getSelectionModel().select(newtab);
            }
        }
    }


    public void checkInstallEnv(){
        RemoteCheckEnvUtil.startWizard((Stage) AppState.getWindow());
    }
    public void installGBase8S(){
        RemoteInstallerUtil.startWizard((Stage) AppState.getWindow());
    }

    public void unInstallGBase8S(){
        RemoteUninstallerUtil.startWizard((Stage) AppState.getWindow());
    }

    public void setLanguageZh() {
        applyLanguage(Locale.SIMPLIFIED_CHINESE, "main.notice.language_switched.zh");
    }

    public void setLanguageEn() {
        applyLanguage(Locale.ENGLISH, "main.notice.language_switched.en");
    }

    public void setLanguageZhHant() {
        applyLanguage(Locale.TRADITIONAL_CHINESE, "main.notice.language_switched.zh_tw");
    }

    private void applyLanguage(Locale locale, String noticeKey) {
        I18n.setLocale(locale);
        ConfigManagerUtil.setProperty("UI_LANG", locale.toLanguageTag());
        NotificationUtil.showMainNotification(I18n.t(noticeKey));
    }

}


