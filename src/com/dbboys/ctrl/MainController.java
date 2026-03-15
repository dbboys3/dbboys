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
            if(tab==aiTab){
                log.info("aiTab");
                Platform.runLater(() -> mainSplitPane.setDividerPositions(1.0));
            }else{
                log.info("not aiTab");
                Platform.runLater(() -> mainSplitPane.setDividerPositions(AppState.getSplit1Pos()));
            }
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
            if(treeviewTabPane.getSelectionModel().getSelectedItem()==aiTab){
                mainSplitPane.setDividerPositions(1.0);
            }else{
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

        // 用户消息使用 CustomGenericStyledArea 展示（支持 Markdown）
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
        assistantPlaceholder.setStyle("-fx-padding: 6 10;-fx-background-color: #f0f0f0;-fx-background-radius: 8;-fx-border-radius: 8;");
        HBox placeholderBox = new HBox(assistantPlaceholder);
        placeholderBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        placeholderBox.setFillHeight(false);
        aiThinkingPlaceholder = placeholderBox;
        aiChatMessages.getChildren().add(placeholderBox);
        scrollAiChatToBottom();
        updateAiSendButtonText(true);
        aiCancelled = false;

        aiTaskFuture = com.dbboys.app.AppExecutor.submit(() -> {
            String reply = com.dbboys.util.AiApiUtil.chat(text);
            Platform.runLater(() -> {
                aiChatMessages.getChildren().remove(placeholderBox);
                aiThinkingPlaceholder = null;
                if (aiCancelled) {
                    aiTaskFuture = null;
                    updateAiSendButtonText(false);
                    return;
                }
                String content = reply != null && !reply.isEmpty() ? reply : I18n.t("ai.notice.api_error");
                // AI 回复同样通过 CustomGenericStyledArea 展示
                addAiMarkdownMessage(content);
                scrollAiChatToBottom();
                aiTaskFuture = null;
                updateAiSendButtonText(false);
            });
        });
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
            SVGPath stopIcon = new SVGPath();
            stopIcon.setContent("M3.7656 3.7812 Q7.1719 0.375 12 0.375 Q16.8281 0.375 20.2188 3.7812 Q23.625 7.1719 23.625 12 Q23.625 16.8281 20.2188 20.2344 Q16.8281 23.625 12 23.625 Q7.1719 23.625 3.7656 20.2344 Q0.375 16.8281 0.375 12 Q0.375 7.1719 3.7656 3.7812 ZM16.5 15.75 L16.5 8.25 Q16.5 7.9219 16.2812 7.7188 Q16.0781 7.5 15.75 7.5 L8.25 7.5 Q7.9219 7.5 7.7031 7.7188 Q7.5 7.9219 7.5 8.25 L7.5 15.75 Q7.5 16.0781 7.7031 16.2969 Q7.9219 16.5 8.25 16.5 L15.75 16.5 Q16.0781 16.5 16.2812 16.2969 Q16.5 16.0781 16.5 15.75 Z");
            stopIcon.setFill(Color.valueOf("#b23b3b"));
            IconFactory.applyDangerStyle(stopIcon);
            stopIcon.setScaleX(0.8);
            stopIcon.setScaleY(0.8);
            aiSendButton.setGraphic(stopIcon);
        } else {
            SVGPath sendIcon = new SVGPath();
            sendIcon.setContent("M24 12 Q24 8.7188 22.3906 5.9688 Q20.7969 3.2031 18.0312 1.6094 Q15.2812 0 12 0 Q8.7188 0 5.9531 1.6094 Q3.2031 3.2031 1.5938 5.9688 Q0 8.7188 0 12 Q0 15.2812 1.5938 18.0469 Q3.2031 20.7969 5.9531 22.4062 Q8.7188 24 12 24 Q15.2812 24 18.0312 22.4062 Q20.7969 20.7969 22.3906 18.0469 Q24 15.2812 24 12 ZM12.7188 17.2812 Q12.7188 17.5938 12.5156 17.7969 Q12.3125 18 12 18 Q11.6875 18 11.4844 17.7969 Q11.2812 17.5938 11.2812 17.2812 L11.2812 8.5625 L8 11.7656 Q7.8438 12 7.5156 12 Q7.2031 12 6.9531 11.7969 Q6.7188 11.5938 6.7188 11.2812 Q6.7188 10.9531 6.9531 10.7188 L11.4375 6.2344 Q11.6875 6 12 6 Q12.3125 6 12.5625 6.2344 L17.0469 10.7188 Q17.2812 10.9531 17.2812 11.2812 Q17.2812 11.5938 17.0312 11.7969 Q16.7969 12 16.4688 12 Q16.1562 12 16 11.7656 L12.7188 8.5625 L12.7188 17.2812 Z");
            sendIcon.setFill(Color.valueOf("#074675"));
            IconFactory.applyDefaultStyle(sendIcon);
            sendIcon.setScaleX(0.8);
            sendIcon.setScaleY(0.8);
            aiSendButton.setGraphic(sendIcon);
        }
    }

    private void addAiMarkdownMessage(String content) {
        com.dbboys.customnode.CustomAiStyledArea area = new com.dbboys.customnode.CustomAiStyledArea();
        area.parseMarkdownWithStyles(content == null ? "" : content);
        area.setEditable(false);
        area.maxWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        area.setStyle(area.getStyle() + ";-fx-padding: 6 10 6 10;");
        aiChatMessages.getChildren().add(area);
    }

    private void addUserMarkdownMessage(String content) {
        com.dbboys.customnode.CustomAiStyledArea area = new com.dbboys.customnode.CustomAiStyledArea();
        area.parseMarkdownWithStyles(content == null ? "" : content);
        area.setEditable(false);
        area.maxWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        area.setStyle(area.getStyle() + ";-fx-padding: 6 10 6 10;");
        HBox userBox = new HBox(area);
        userBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        userBox.setFillHeight(false);
        aiChatMessages.getChildren().add(userBox);
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

