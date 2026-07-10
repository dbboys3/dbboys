package com.dbboys.ui.controller;

import com.dbboys.app.*;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.ui.component.*;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.*;
import com.dbboys.search.MarkdownUtil;
import com.dbboys.search.MarkdownSearchUtil;
import com.dbboys.infra.config.UpgradeUtil;
import com.dbboys.infra.config.ConfigManagerUtil;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.dialog.PopupWindowUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.remote.RemoteCheckEnvUtil;
import com.dbboys.remote.RemoteDatabaseProviders;
import com.dbboys.ui.controller.tree.TreeViewUtil;
import com.dbboys.model.*;
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
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;


public class MainController {
    private static final Logger log = LogManager.getLogger(MainController.class);
    private static final int MAX_RECENT_FILES = 10;

    private AiController aiController;

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
    public CustomUserTextarea aiInputField;
    @FXML
    public Button aiSendButton;
    @FXML
    public ChoiceBox<String> aiModelChoiceBox;
    @FXML
    public Button aiSettingsButton;
    @FXML
    public Button aiMemoryToggleButton;

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
    private Menu menuFileRecent;
    @FXML
    private Menu menuConfig;
    @FXML
    private CustomShortcutMenuItem menuConfigCheckEnv;
    @FXML
    private Menu menuConfigInformix;
    @FXML
    private CustomShortcutMenuItem menuConfigInstallInformix;
    @FXML
    private CustomShortcutMenuItem menuConfigUninstallInformix;
    @FXML
    private Menu menuConfigMysql;
    @FXML
    private CustomShortcutMenuItem menuConfigInstallMysql;
    @FXML
    private CustomShortcutMenuItem menuConfigUninstallMysql;
    @FXML
    private Menu menuConfigGbase;
    @FXML
    private CustomShortcutMenuItem menuConfigInstallGbase;
    @FXML
    private CustomShortcutMenuItem menuConfigUninstallGbase;
    @FXML
    private Menu menuConfigOracle;
    @FXML
    private CustomShortcutMenuItem menuConfigInstallOracle;
    @FXML
    private CustomShortcutMenuItem menuConfigUninstallOracle;
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
    private Menu menuSettingsTheme;
    @FXML
    private CustomShortcutMenuItem menuSettingsThemeDark;
    @FXML
    private CustomShortcutMenuItem menuSettingsThemeLight;
    @FXML
    private CustomShortcutMenuItem menuSettingsReset;
    @FXML
    private Menu menuHelp;
    @FXML
    private CustomShortcutMenuItem menuHelpAbout;
    @FXML
    private CustomShortcutMenuItem menuHelpCompatibilityList;
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
        installMenuActions();
        restoreOpenTabs();
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
        //menuConfigInformix.setGraphic(IconFactory.group(IconPaths.INFORMIX_LOGO, 0.15,0.12,Color.valueOf("#ff3300")));
        menuConfigInformix.setGraphic(IconFactory.group(IconPaths.INFORMIX_LOGO, 0.15,0.12));

        menuConfigInstallInformix.setGraphic(null);
        menuConfigUninstallInformix.setGraphic(null);
        menuConfigMysql.setGraphic(IconFactory.group(IconPaths.MYSQL_LOGO, 0.58));
        menuConfigInstallMysql.setGraphic(null);
        menuConfigUninstallMysql.setGraphic(null);
        menuConfigGbase.setGraphic(IconFactory.group(IconPaths.GBASE_LOGO, 0.22));
        menuConfigInstallGbase.setGraphic(null);
        menuConfigUninstallGbase.setGraphic(null);
        menuSettingsLanguage.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_LANGUAGE, 0.68));
        menuSettingsTheme.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_THEME, 0.68));
        menuSettingsReset.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_RESET, 0.6));
        menuHelpAbout.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_HELP, 0.6));
        menuHelpCompatibilityList.setGraphic(IconFactory.group(IconPaths.MAIN_STATUS_LIST, 0.45));
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
        if (aiSettingsButton != null) {
            aiSettingsButton.setText("");
            aiSettingsButton.setGraphic(IconFactory.group(IconPaths.METADATA_ONCONFIG_ITEM, 0.416));
        }
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
            MarkdownSearchUtil.performSearch(markdownSearchTextField.getText());
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
        aiController = new AiController(aiTabVBox, aiChatScrollPane, aiChatMessages,
                aiInputField, aiSendButton, aiModelChoiceBox, aiSettingsButton, aiMemoryToggleButton);
        aiController.init();
    }

    private void installMenuActions() {
        newSqlFileMenuItem.setOnAction(event -> {
            TabpaneUtil.addCustomSqlTab(null);});
    }

    // --- Recent files persistence (user temp dir) ---

    private static File sessionStoreFile() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        Path dir = Path.of(tmpDir, "dbboys");
        log.info("Session store at: {}", dir.resolve("session.json").toAbsolutePath());
        return dir.resolve("session.json").toFile();
    }

    private static void persistRecentFilePaths(LinkedHashSet<String> paths) {
        try {
            File file = sessionStoreFile();
            log.warn("DBBOYS-RECENT: persistRecentFilePaths to {}", file.getAbsolutePath());
            file.getParentFile().mkdirs();
            // Read existing JSON, update recentFiles, write back
            JSONObject root = readSessionJson();
            JSONArray arr = new JSONArray();
            for (String p : paths) {
                arr.put(p);
            }
            root.put("recentFiles", arr);
            Files.writeString(file.toPath(), root.toString());
        } catch (IOException e) {
            log.error("Failed to persist recent file paths.", e);
        }
    }

    private static JSONObject readSessionJson() {
        File file = sessionStoreFile();
        if (file.isFile()) {
            try {
                String content = Files.readString(file.toPath());
                if (content != null && !content.isBlank()) {
                    return new JSONObject(content);
                }
            } catch (IOException ignored) {
            }
        }
        return new JSONObject();
    }

    // --- Recent files menu ---

    private void refreshRecentFilesMenu() {
        if (menuFileRecent == null) {
            log.warn("menuFileRecent is null, cannot refresh");
            return;
        }
        menuFileRecent.getItems().clear();
        LinkedHashSet<String> recentPaths = loadRecentFilePaths();
        log.warn("DBBOYS-RECENT: refreshRecentFilesMenu, {} paths loaded", recentPaths.size());
        if (recentPaths.isEmpty()) {
            MenuItem emptyItem = new MenuItem();
            emptyItem.textProperty().bind(I18n.bind("main.menu.file.recent.empty"));
            emptyItem.setDisable(true);
            menuFileRecent.getItems().add(emptyItem);
            return;
        }
        for (String path : recentPaths) {
            MenuItem item = new MenuItem(path);
            item.setOnAction(e -> openRecentFile(path));
            menuFileRecent.getItems().add(item);
        }
    }

    private void openRecentFile(String filePath) {
        File file = new File(filePath);
        if (!file.isFile()) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "错误"),
                    I18n.t("main.notice.file_not_found", "文件不存在：%s").formatted(filePath)
            );
            removeRecentFilePath(filePath);
            refreshRecentFilesMenu();
            return;
        }
        // Check if already open
        for (Tab tab : sqlTabPane.getTabs()) {
            if (tab instanceof CustomSqlTab customSqlTab
                    && customSqlTab.filePath.equals(filePath)) {
                sqlTabPane.getSelectionModel().select(tab);
                return;
            }
        }
        CustomSqlTab newtab = new CustomSqlTab(file.getName());
        newtab.filePath = filePath;
        newtab.openSqlFile();
        sqlTabPane.getTabs().add(newtab);
        sqlTabPane.getSelectionModel().select(newtab);
    }

    public static void addRecentFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        LinkedHashSet<String> paths = loadRecentFilePaths();
        paths.remove(filePath); // move to front if already present
        LinkedHashSet<String> reordered = new LinkedHashSet<>();
        reordered.add(filePath);
        reordered.addAll(paths);

        // Trim to MAX_RECENT_FILES
        List<String> trimmed = new ArrayList<>();
        for (String p : reordered) {
            trimmed.add(p);
            if (trimmed.size() >= MAX_RECENT_FILES) break;
        }
        persistRecentFilePaths(new LinkedHashSet<>(trimmed));
        Platform.runLater(() -> {
            MainController ctrl = AppState.getMainController();
            if (ctrl != null) {
                ctrl.refreshRecentFilesMenu();
            }
        });
    }

    private static void removeRecentFilePath(String filePath) {
        LinkedHashSet<String> paths = loadRecentFilePaths();
        paths.remove(filePath);
        persistRecentFilePaths(paths);
    }

    private static LinkedHashSet<String> loadRecentFilePaths() {
        try {
            JSONObject root = readSessionJson();
            JSONArray arr = root.optJSONArray("recentFiles");
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "");
                    if (p != null && !p.isBlank()) {
                        paths.add(p);
                    }
                }
            }
            log.info("Loaded {} recent files from session", paths.size());
            return paths;
        } catch (Exception e) {
            log.error("Failed to load recent file paths.", e);
            return new LinkedHashSet<>();
        }
    }

    // --- Session restore: persist open tabs on close, restore on startup ---

    private void persistOpenTabs() {
        List<String> filePaths = new ArrayList<>();
        for (Tab tab : sqlTabPane.getTabs()) {
            if (tab instanceof CustomSqlTab customSqlTab
                    && customSqlTab.filePath != null
                    && !customSqlTab.filePath.isBlank()) {
                filePaths.add(customSqlTab.filePath);
            }
        }
        try {
            JSONObject root = readSessionJson();
            JSONArray arr = new JSONArray();
            for (String p : filePaths) {
                arr.put(p);
            }
            root.put("openTabs", arr);
            File file = sessionStoreFile();
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), root.toString());
        } catch (IOException e) {
            log.error("Failed to persist open tabs.", e);
        }
    }

    private void restoreOpenTabs() {
        try {
            JSONObject root = readSessionJson();
            JSONArray arr = root.optJSONArray("openTabs");
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                String filePath = arr.optString(i, "");
                if (filePath.isBlank()) {
                    continue;
                }
                File file = new File(filePath);
                if (!file.isFile()) {
                    continue;
                }
                CustomSqlTab tab = new CustomSqlTab(file.getName());
                tab.filePath = filePath;
                tab.openSqlFile();
                sqlTabPane.getTabs().add(tab);
            }
        } catch (Exception e) {
            log.error("Failed to restore open tabs.", e);
        }
    }

    private void initI18nBindings() {
        bindText(menuFile, "main.menu.file");
        bindText(menuFileAddFolder, "main.menu.file.add_folder");
        bindText(menuFileNewConnection, "main.menu.file.new_connection");
        bindText(menuFileDisconnectAll, "main.menu.file.disconnect_all");
        bindText(newSqlFileMenuItem, "main.menu.file.new_sql");
        bindText(menuFileOpenSql, "main.menu.file.open_sql");
        if (menuFileRecent != null) {
            menuFileRecent.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_OPEN_SQL, 0.62));
            bindText(menuFileRecent, "main.menu.file.recent");
            menuFileRecent.setOnShowing(e -> refreshRecentFilesMenu());
            // Pre-populate so the submenu has items before first showing,
            // otherwise empty Menu won't trigger onShowing at all.
            refreshRecentFilesMenu();
        }

        bindText(menuConfig, "main.menu.config");
        bindText(menuConfigCheckEnv, "main.menu.config.check_env");
        bindText(menuConfigInformix, "main.menu.config.informix");
        bindText(menuConfigInstallInformix, "main.menu.config.install_informix");
        bindText(menuConfigUninstallInformix, "main.menu.config.uninstall_informix");
        bindText(menuConfigMysql, "main.menu.config.mysql");
        bindText(menuConfigInstallMysql, "main.menu.config.install_mysql");
        bindText(menuConfigUninstallMysql, "main.menu.config.uninstall_mysql");
        bindText(menuConfigGbase, "main.menu.config.gbase");
        bindText(menuConfigInstallGbase, "main.menu.config.install_gbase");
        bindText(menuConfigUninstallGbase, "main.menu.config.uninstall_gbase");
        bindText(menuConfigOracle, "main.menu.config.oracle");
        bindText(menuConfigInstallOracle, "main.menu.config.install_oracle");
        bindText(menuConfigUninstallOracle, "main.menu.config.uninstall_oracle");

        bindText(menuSettings, "main.menu.settings");
        bindText(menuSettingsLanguage, "main.menu.settings.language");
        bindText(menuSettingsLanguageZh, "main.menu.settings.language.zh");
        bindText(menuSettingsLanguageEn, "main.menu.settings.language.en");
        bindText(menuSettingsLanguageZhTw, "main.menu.settings.language.zh_tw");
        bindText(menuSettingsTheme, "main.menu.settings.theme");
        bindText(menuSettingsThemeDark, "main.menu.settings.theme.dark");
        bindText(menuSettingsThemeLight, "main.menu.settings.theme.light");
        bindText(menuSettingsReset, "main.menu.settings.reset");

        bindText(menuHelp, "main.menu.help");
        bindText(menuHelpAbout, "main.menu.help.about");
        bindText(menuHelpCompatibilityList, "main.menu.help.compatibility_list");
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
        bindTooltip(aiSettingsButton, "ai.button.api_key");
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

    /** Saves split positions, persists open tabs, closes the stage, and exits. Call after requestClose() returns true. */
    public void performCloseAndExit() {
        persistOpenTabs();
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
        AppExecutor.runAsync(() -> { LocalDbRepository.deleteSqlHistory();  });
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

    public void showCompatibilityList() {
        PopupWindowUtil.openCompatibilityListWindow();
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
        if (aiController != null) {
            aiController.sendAiMessage();
        }
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
        File scriptsDir = new File("scripts");
        if (scriptsDir.isDirectory()) {
            File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
            if (desktopDir.exists()) fileChooser.setInitialDirectory(desktopDir);
        }
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL Files", "*.sql"));
        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(AppState.getWindow());
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (File selectedFile : selectedFiles) {
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
                    newtab.filePath = selectedFile.getAbsolutePath();
                    newtab.openSqlFile();
                    sqlTabPane.getTabs().add(newtab);
                    sqlTabPane.getSelectionModel().select(newtab);
                    addRecentFilePath(selectedFile.getAbsolutePath());
                }
            }
        }
    }


    public void checkInstallEnv(){
        RemoteCheckEnvUtil.startWizard((Stage) AppState.getWindow());
    }
    public void installGBase8S(){
        RemoteDatabaseProviders.gbase8s().startInstallWizard((Stage) AppState.getWindow());
    }

    public void unInstallGBase8S(){
        RemoteDatabaseProviders.gbase8s().startUninstallWizard((Stage) AppState.getWindow());
    }

    public void installInformix(){
        RemoteDatabaseProviders.informix().startInstallWizard((Stage) AppState.getWindow());
    }

    public void unInstallInformix(){
        RemoteDatabaseProviders.informix().startUninstallWizard((Stage) AppState.getWindow());
    }

    public void installMysql(){
        RemoteDatabaseProviders.mysql().startInstallWizard((Stage) AppState.getWindow());
    }

    public void unInstallMysql(){
        RemoteDatabaseProviders.mysql().startUninstallWizard((Stage) AppState.getWindow());
    }

    public void installOracle(){
        RemoteDatabaseProviders.oracle().startInstallWizard((Stage) AppState.getWindow());
    }

    public void unInstallOracle(){
        RemoteDatabaseProviders.oracle().startUninstallWizard((Stage) AppState.getWindow());
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

    public void setThemeDark() {
        applyTheme(AppState.THEME_DARK, "main.notice.theme_switched.dark");
    }

    public void setThemeLight() {
        applyTheme(AppState.THEME_LIGHT, "main.notice.theme_switched.light");
    }

    private void applyTheme(String theme, String noticeKey) {
        AppState.setCurrentTheme(theme);
        NotificationUtil.showMainNotification(I18n.t(noticeKey));
    }

}
