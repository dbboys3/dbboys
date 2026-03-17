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
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;


public class MainController {
    private static final Logger log = LogManager.getLogger(MainController.class);
    private static final double USER_BUBBLE_MAX_WIDTH_RATIO = 0.7;
    private static final int MESSAGE_BUBBLE_RADIUS = 6;
    private static final double AI_INPUT_HEIGHT = 90;

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
    public CustomTreeviewTab aiTab;
    @FXML
    public VBox aiTabVBox;
    @FXML
    public ScrollPane aiChatScrollPane;
    @FXML
    public VBox aiChatMessages;
    @FXML
    public TextArea aiInputField;
    @FXML
    public Button aiSendButton;
    private java.util.concurrent.Future<?> aiTaskFuture;
    private HBox aiThinkingPlaceholder;
    private volatile boolean aiCancelled = false;
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
        initAiPanel();
        initSqlTabInteractions();
        initMenuActions();
        Main.loadProgressBar.setProgress(0.7);
        initSplitPaneResizeBehavior();
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
        menuFileAddFolder.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_ADD_FOLDER, 0.65));
        menuFileNewConnection.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_NEW_CONNECTION, 0.65));
        menuFileDisconnectAll.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_DISCONNECT_ALL, 0.6));
        newSqlFileMenuItem.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_NEW_SQL, 0.55));
        menuFileOpenSql.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_OPEN_SQL, 0.62));
        menuConfigCheckEnv.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_CHECK_ENV, 0.5));
        menuConfigInstallGbase.setGraphic(IconFactory.group(IconPaths.GBASE_LOGO, 0.22));
        menuConfigUninstallGbase.setGraphic(IconFactory.group(IconPaths.GBASE_LOGO, 0.22, Color.valueOf("#9f453c")));
        menuSettingsLanguage.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_LANGUAGE, 0.68));
        menuSettingsReset.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_RESET, 0.6));
        menuHelpAbout.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_HELP, 0.6));
        menuHelpCommunity.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_COMMUNITY, 0.5));
        menuHelpCheckUpdate.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_HOME, 0.5));

        create_connect.setGraphic(IconFactory.group(IconPaths.MAIN_ADD_CONNECT, 0.65));
        connectSearchButton.setGraphic(IconFactory.group(IconPaths.MAIN_SEARCH, 0.65));
        rebuildMarkdownIndexButton.setGraphic(IconFactory.group(IconPaths.MAIN_REBUILD, 0.65));
        markdownSearchButton.setGraphic(IconFactory.group(IconPaths.MAIN_SEARCH, 0.65));
        statusBackSqlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.5, IconFactory.stopColor()));
        statusBackSqlListButton.setGraphic(IconFactory.group(IconPaths.MAIN_STATUS_LIST, 0.45));
        statusBackSqlProgress.setImage(new Image(IconPaths.LOADING_GIF));
        statusBackSqlProgress.setFitWidth(10);
        statusBackSqlProgress.setFitHeight(10);
        statusBackSqlProgress.setPreserveRatio(true);
        snapshotRootButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35));
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
        markdownTab.titleToggleIcon.setContent(IconPaths.MARKDOWN_TAB_TOGGLE);
        
        aiTab.titleToggleIcon.setContent(IconPaths.AI_TAB_TOGGLE);
        


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
            Platform.runLater(() -> mainSplitPane.setDividerPositions(AppState.getSplit1Pos()));
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

    private void initAiPanel() {
        updateAiSendButtonText(false);

        // AI 输入框支持 Enter 发送，Shift+Enter 换行
        if (aiInputField != null) {
            aiInputField.setMinHeight(AI_INPUT_HEIGHT);
            aiInputField.setPrefHeight(AI_INPUT_HEIGHT);
            aiInputField.setMaxHeight(AI_INPUT_HEIGHT);
            aiInputField.setOnKeyPressed(event -> {
                if (event.isShiftDown() && event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    aiInputField.replaceSelection(System.lineSeparator());
                    event.consume();
                } else if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    sendAiMessage();
                    event.consume();
                }
            });
            if (aiInputField.getParent() instanceof Region inputContainer) {
                inputContainer.setMinHeight(AI_INPUT_HEIGHT);
                inputContainer.setPrefHeight(AI_INPUT_HEIGHT);
                inputContainer.setMaxHeight(AI_INPUT_HEIGHT);
                VBox.setVgrow(inputContainer, Priority.NEVER);
            }
        }

        if (aiChatScrollPane != null) {
            aiChatScrollPane.setFitToWidth(true);
            aiChatScrollPane.setFitToHeight(false);
        }
    }

    private void initMenuActions() {
        newSqlFileMenuItem.setOnAction(event -> {
            TabpaneUtil.addCustomSqlTab(null);});
        installSettingsMenuBehavior();
    }

    private void installSettingsMenuBehavior() {
        menuSettings.setOnShowing(event -> {
            ContextMenu parentPopup = menuSettingsLanguage.getParentPopup();
            if (parentPopup == null || parentPopup.getProperties().containsKey("settingsMenuHoverFixInstalled")) {
                return;
            }
            parentPopup.getProperties().put("settingsMenuHoverFixInstalled", Boolean.TRUE);
            parentPopup.skinProperty().addListener((obs, oldSkin, newSkin) -> {
                if (newSkin == null) {
                    return;
                }
                Node skinRoot = newSkin.getNode();
                skinRoot.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, mouseEvent -> {
                    if (!menuSettingsLanguage.isShowing()) {
                        return;
                    }
                    Node target = (Node) mouseEvent.getTarget();
                    while (target != null && target != skinRoot) {
                        if (target.getStyleClass().contains("menu-item")) {
                            if (!target.getStyleClass().contains("menu")) {
                                menuSettingsLanguage.hide();
                            }
                            return;
                        }
                        target = target.getParent();
                    }
                });
            });
        });
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
        if (aiTab != null && aiTab.titleToggle != null) {
            aiTab.titleToggle.textProperty().bind(I18n.bind("main.sidebar.ai"));
            bindTooltip(aiTab.titleToggle, "main.tooltip.ai");
        }
        bindTabText(connectTab, "main.sidebar.connections");
        bindTabText(markdownTab, "main.sidebar.knowledge");
        bindTabText(aiTab, "main.sidebar.ai");

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
        bindPrompt(aiInputField, "main.prompt.ai_input");
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
            log.info("mainSplitPane.widthProperty() newVal: " + newVal);
            if(AppState.getSqlEditCodeAreaIsMax()==0) {
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

    /** Returns true if the window can close (e.g. user confirmed when there are unsaved SQL tabs). */
    public boolean requestClose() {
        for (Tab tab : sqlTabPane.getTabs()) {
            if (tab.getText().startsWith("*")) {
                return AlertUtil.CustomAlertConfirm(I18n.t("common.hint"), I18n.t("main.confirm.unsaved_sql_close"));
            }
        }
        return true;
    }

    /** Saves split positions, closes the stage, and exits. Call after requestClose() returns true. */
    public void performCloseAndExit() {
        String split1Content = String.valueOf(AppState.getSplit1Pos());
        String split2Content = String.valueOf(AppState.getSplit2Pos());
        ConfigManagerUtil.setProperty("SPLIT_DRIVER_MAIN", split1Content);
        ConfigManagerUtil.setProperty("SPLIT_DRIVER_SQL", split2Content);
        Stage stage = (Stage) root.getScene().getWindow();
        stage.close();
        System.exit(0); //避免executorService线程未关闭
        log.info("dbboys已关闭。");
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

    @FXML
    public void sendAiMessage() {
        if (aiInputField == null || aiChatMessages == null) return;

        if (aiTaskFuture != null && !aiTaskFuture.isDone()) {
            cancelAiRequest();
            return;
        }

        String text = aiInputField.getText();
        if (text == null || text.isBlank()) return;

        // 用户消息使用右侧气泡展示
        addUserMarkdownMessage(text);
        aiInputField.clear();
        scrollAiChatToBottom();

        if (!com.dbboys.util.AiAuthUtil.hasConfiguredApi()) {
            NotificationUtil.showMainNotification(I18n.t("ai.notice.please_login"));
            return;
        }

        Label assistantPlaceholder = new Label(I18n.t("ai.replying"));
        assistantPlaceholder.setWrapText(true);
        assistantPlaceholder.setMaxWidth(1.0E7);
        assistantPlaceholder.setStyle(
                "-fx-padding: 6 10;" +
                "-fx-background-color: -color-base-7;" +
                "-fx-text-fill: -color-fg-emphasis;" +
                "-fx-background-radius: " + MESSAGE_BUBBLE_RADIUS + ";" +
                "-fx-border-radius: " + MESSAGE_BUBBLE_RADIUS + ";"
        );
        HBox placeholderBox = new HBox(assistantPlaceholder);
        placeholderBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        placeholderBox.setFillHeight(false);
        aiThinkingPlaceholder = placeholderBox;
        aiChatMessages.getChildren().add(placeholderBox);
        scrollAiChatToBottom();
        updateAiSendButtonText(true);
        aiCancelled = false;

        aiTaskFuture = com.dbboys.app.AppExecutor.submit(() -> {
            List<MarkdownSearchUtil.KnowledgeReference> references =
                    MarkdownSearchUtil.searchKnowledgeReferences(text, 3);
            String prompt = buildAiPrompt(text, references);
            log.info("AI request prompt:\n{}", prompt);
            System.out.println("=== AI request prompt begin ===");
            System.out.println(prompt);
            System.out.println("=== AI request prompt end ===");
            String reply = com.dbboys.util.AiApiUtil.chat(prompt);
            Platform.runLater(() -> {
                aiChatMessages.getChildren().remove(placeholderBox);
                aiThinkingPlaceholder = null;
                if (aiCancelled) {
                    aiTaskFuture = null;
                    updateAiSendButtonText(false);
                    return;
                }
                String content = reply != null && !reply.isEmpty() ? reply : I18n.t("ai.notice.api_error");
                content = appendAiReferences(content, references);
                // AI 回复同样通过 CustomGenericStyledArea 展示
                addAiMarkdownMessage(content);
                scrollAiChatToBottom();
                aiTaskFuture = null;
                updateAiSendButtonText(false);
            });
        });
    }

    private String buildAiPrompt(String userQuestion, List<MarkdownSearchUtil.KnowledgeReference> references) {
        String safeQuestion = userQuestion == null ? "" : userQuestion.trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是数据库助手。请优先参考下面提供的知识库检索结果回答用户问题。");
        prompt.append("如果检索内容不足以支撑结论，就明确说明不确定，不要编造，不要列参考文档链接。");
        prompt.append("\n\n用户问题：\n").append(safeQuestion);
        if (!references.isEmpty()) {
            prompt.append("\n\n知识库检索结果（按相关性排序，最多3条）：");
            for (int i = 0; i < references.size(); i++) {
                MarkdownSearchUtil.KnowledgeReference ref = references.get(i);
                prompt.append("\n\n[").append(i + 1).append("]");
                if (ref.snippet() != null && !ref.snippet().isBlank()) {
                    prompt.append("\n摘要：").append(ref.snippet());
                }
            }
        } else {
            prompt.append("\n\n知识库检索结果：未匹配到相关文档。");
        }
        return prompt.toString();
    }

    private String appendAiReferences(String reply, List<MarkdownSearchUtil.KnowledgeReference> references) {
        String content = reply == null ? "" : reply.trim();
        if (references == null || references.isEmpty()) {
            return content;
        }
        StringBuilder builder = new StringBuilder(content);
        if (!content.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("参考文档：\n");
        for (int i = 0; i < references.size(); i++) {
            MarkdownSearchUtil.KnowledgeReference ref = references.get(i);
            String linkTarget = ref.path().replace('\\', '/');
            String title = ref.title() == null || ref.title().isBlank() ? linkTarget : ref.title();
            builder.append(i + 1)
                    .append(". [")
                    .append(title)
                    .append("](")
                    .append(linkTarget)
                    .append(")\n");
        }
        return builder.toString().trim();
    }

    private void cancelAiRequest() {
        aiCancelled = true;
        if (aiTaskFuture != null && !aiTaskFuture.isDone()) {
            aiTaskFuture.cancel(true);
        }
        aiTaskFuture = null;
        if (aiThinkingPlaceholder != null) {
            aiChatMessages.getChildren().remove(aiThinkingPlaceholder);
            aiThinkingPlaceholder = null;
        }
        updateAiSendButtonText(false);
    }

    private void updateAiSendButtonText(boolean thinking) {
        if (aiSendButton == null) {
            return;
        }
        aiSendButton.setText("");
        if (thinking) {
            // AI 对话停止：使用独立的圆形停止图标
            aiSendButton.setGraphic(
                    IconFactory.groupFixedColor(IconPaths.AI_STOP, 0.72, IconFactory.stopColor())
            );
        } else {
            // AI 对话发送：使用专用的圆形箭头发送图标
            aiSendButton.setGraphic(
                    IconFactory.group(IconPaths.AI_SEND, 0.7)
            );
        }
    }

    private void addAiMarkdownMessage(String content) {
        com.dbboys.customnode.CustomAiStyledArea area = new com.dbboys.customnode.CustomAiStyledArea();
        area.parseMarkdownWithStyles(content == null ? "" : content);
        area.setEditable(false);
        area.setStyle(
                area.getStyle() +
                ";-fx-padding: 6 10 6 10;" +
                "-fx-background-color: transparent;" +
                "-fx-background-radius: " + MESSAGE_BUBBLE_RADIUS + ";"
        );

        StackPane bubble = new StackPane(area);
        bubble.setStyle(
                "-fx-background-color: -color-bg-content;" +
                "-fx-background-radius: " + MESSAGE_BUBBLE_RADIUS + ";" +
                "-fx-border-radius: " + MESSAGE_BUBBLE_RADIUS + ";"
        );
        bubble.prefWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        bubble.maxWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        area.prefWidthProperty().bind(bubble.widthProperty());
        area.maxWidthProperty().bind(bubble.widthProperty());

        VBox messageGroup = new VBox(4, bubble, createMessageButtonRow(content, Pos.CENTER_LEFT));
        messageGroup.setAlignment(Pos.CENTER_LEFT);
        messageGroup.setFillWidth(true);
        aiChatMessages.getChildren().add(messageGroup);
        keepAiMessageVisible(area, messageGroup);
        scrollAiChatToBottom();
    }

    private void addUserMarkdownMessage(String content) {
        String text = content == null ? "" : content;

        Label messageLabel = new Label(text);
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);
        messageLabel.setStyle("-fx-text-fill: -color-fg-emphasis;");

        StackPane bubble = new StackPane(messageLabel);
        bubble.setStyle(
                "-fx-background-color: -color-accent-emphasis;" +
                "-fx-background-radius: " + MESSAGE_BUBBLE_RADIUS + ";" +
                "-fx-border-radius: " + MESSAGE_BUBBLE_RADIUS + ";" +
                "-fx-padding: 6 10 6 10;"
        );
        bubble.maxWidthProperty().bind(aiChatMessages.widthProperty().multiply(USER_BUBBLE_MAX_WIDTH_RATIO));
        messageLabel.maxWidthProperty().bind(bubble.maxWidthProperty().subtract(20));
        HBox messageRow = createMessageRow(bubble, text, Pos.CENTER_RIGHT, Pos.CENTER_RIGHT);
        aiChatMessages.getChildren().add(messageRow);
        keepAiMessageVisible(bubble, messageRow);
        scrollAiChatToBottom();
    }

    private HBox createMessageRow(Node messageNode, String text, Pos rowAlignment, Pos buttonAlignment) {
        VBox messageGroup = new VBox(4, messageNode, createMessageButtonRow(text, buttonAlignment));
        messageGroup.setAlignment(buttonAlignment);
        messageGroup.setFillWidth(true);
        HBox messageRow = new HBox(messageGroup);
        messageRow.setAlignment(rowAlignment);
        messageRow.setFillHeight(false);
        return messageRow;
    }

    private HBox createMessageButtonRow(String text, Pos buttonAlignment) {
        HBox buttonRow = new HBox(createMessageCopyButton(text));
        buttonRow.setAlignment(buttonAlignment);
        buttonRow.setFillHeight(false);
        return buttonRow;
    }

    private Button createMessageCopyButton(String text) {
        Button copyButton = new Button();
        copyButton.setText("");
        copyButton.setGraphic(IconFactory.group(IconPaths.COPY, 0.62));
        copyButton.getStyleClass().add("small");
        copyButton.setFocusTraversable(false);
        copyButton.setTooltip(new Tooltip(I18n.t("genericstyled.menu.copy")));
        copyButton.setOnAction(event -> copyMessageText(text));
        return copyButton;
    }

    private void copyMessageText(String text) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        clipboard.setContent(content);
        NotificationUtil.showMainNotification(I18n.t("resultset.notice.copied"));
    }

    private void keepAiMessageVisible(Region messageRegion, Region containerRegion) {
        if (messageRegion != null) {
            messageRegion.heightProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
            messageRegion.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
        }
        if (containerRegion != null) {
            containerRegion.heightProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
            containerRegion.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> scrollAiChatToBottom());
        }
    }

    private void scrollAiChatToBottom() {
        Platform.runLater(() -> {
            if (aiChatScrollPane != null) {
                aiChatScrollPane.setVvalue(1.0);
            }
        });
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
        File selectedFile = fileChooser.showOpenDialog(AppState.getWindow());
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

