package com.dbboys.ctrl;

import com.dbboys.app.*;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.util.*;
import com.dbboys.util.remote.RemoteCheckEnvUtil;
import com.dbboys.util.remote.RemoteDatabaseProviders;
import com.dbboys.util.tree.TreeViewUtil;
import com.dbboys.vo.*;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.EventHandler;
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
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;


public class MainController {
    private static final Logger log = LogManager.getLogger(MainController.class);
    private static final Pattern AI_REFERENCE_LOCATION_PATTERN = Pattern.compile("第\\d+[页行]");
    private static final double USER_BUBBLE_MAX_WIDTH_RATIO = 0.7;
    private static final int MESSAGE_BUBBLE_RADIUS = 6;
    private static final double AI_INPUT_HEIGHT = 90;
    private static final int AI_HISTORY_TURNS = 3;
    /** 流式正文标签（与主题 token 一致） */
    private static final String AI_STREAMING_LABEL_STYLE =
            "-fx-text-fill: -color-fg-default; -fx-font-size: 10px; -fx-padding: 6 10 6 10;";
    /** 「正在思考」占位：弱化前景色，随明暗主题走 -color-fg-muted */
    private static final String AI_THINKING_LABEL_STYLE =
            "-fx-text-fill: -color-fg-muted; -fx-font-size: 10px; -fx-padding: 6 10 6 10;";
    /** 记忆开关：关 — 与主题 surface 一致 */
    private static final String AI_MEMORY_BTN_STYLE_OFF =
            "-fx-background-color: -color-bg-subtle; -fx-background-radius: 6; "
                    + "-fx-border-color: -color-border-default; -fx-border-radius: 6; -fx-border-width: 0.5; "
                    + "-fx-text-fill: -color-fg-muted; -fx-font-size: 9px; -fx-padding: 3 8 3 8;";
    /** 记忆开关：开 — 使用主题强调色弱背景 */
    private static final String AI_MEMORY_BTN_STYLE_ON =
            "-fx-background-color: -color-accent-subtle; -fx-background-radius: 6; "
                    + "-fx-border-color: -color-accent-muted; -fx-border-radius: 6; -fx-border-width: 0.5; "
                    + "-fx-text-fill: -color-accent-fg; -fx-font-size: 9px; -fx-padding: 3 8 3 8;";
    private static final List<String> AI_AVAILABLE_MODELS = List.of(
            "doubao-seed-2-0-mini-260215",
            "qwen3.6-plus"
            // "kimi-k2.5"
    );

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
    private java.util.concurrent.Future<?> aiTaskFuture;
    private AiMessageView aiStreamingMessage;
    private volatile boolean aiCancelled = false;
    private volatile boolean aiMemoryEnabled = false;
    private final AtomicBoolean aiScrollScheduled = new AtomicBoolean(false);
    private final List<AiConversationMessage> aiConversationHistory = new ArrayList<>();

    private record AiConversationMessage(String role, String content) {}

    private static final class AiMessageView {
        private final CustomAiStyledArea area = new CustomAiStyledArea();
        private final Label streamingLabel = new Label();
        private final AtomicBoolean renderScheduled = new AtomicBoolean(false);
        private final AtomicInteger revision = new AtomicInteger();
        private final AtomicInteger renderedRevision = new AtomicInteger(-1);
        private final VBox messageGroup;
        private final StackPane bubble;
        private final HBox buttonRow;
        private final StringBuilder rawContent = new StringBuilder();
        private FadeTransition thinkingFade;
        private Timeline thinkingDotsTimeline;

        private AiMessageView(VBox messageGroup, StackPane bubble, HBox buttonRow) {
            this.messageGroup = messageGroup;
            this.bubble = bubble;
            this.buttonRow = buttonRow;
        }

        /** 去掉文案末尾省略号，便于与动画点号拼接（避免「正在思考...」叠成多组点） */
        private static String aiThinkingBaseText(String i18nReplying) {
            if (i18nReplying == null) {
                return "";
            }
            return i18nReplying.replaceAll("\\.+\\s*$", "").trim();
        }

        private static String thinkingDotSuffix(int stepMod4) {
            int n = Math.floorMod(stepMod4, 4);
            return n == 0 ? "" : ".".repeat(n);
        }

        private void startThinkingAnimation(String baseText) {
            stopThinkingAnimation();
            streamingLabel.setStyle(AI_THINKING_LABEL_STYLE);
            thinkingFade = new FadeTransition(Duration.millis(950), streamingLabel);
            thinkingFade.setFromValue(0.52);
            thinkingFade.setToValue(1.0);
            thinkingFade.setCycleCount(Animation.INDEFINITE);
            thinkingFade.setAutoReverse(true);
            thinkingFade.play();

            thinkingDotsTimeline = new Timeline(
                    new KeyFrame(Duration.ZERO, e -> streamingLabel.setText(baseText + thinkingDotSuffix(0))),
                    new KeyFrame(Duration.millis(400), e -> streamingLabel.setText(baseText + thinkingDotSuffix(1))),
                    new KeyFrame(Duration.millis(800), e -> streamingLabel.setText(baseText + thinkingDotSuffix(2))),
                    new KeyFrame(Duration.millis(1200), e -> streamingLabel.setText(baseText + thinkingDotSuffix(3)))
            );
            thinkingDotsTimeline.setCycleCount(Timeline.INDEFINITE);
            thinkingDotsTimeline.play();
        }

        private void stopThinkingAnimation() {
            if (thinkingFade != null) {
                thinkingFade.stop();
                thinkingFade = null;
            }
            if (thinkingDotsTimeline != null) {
                thinkingDotsTimeline.stop();
                thinkingDotsTimeline = null;
            }
            streamingLabel.setOpacity(1.0);
            streamingLabel.setStyle(AI_STREAMING_LABEL_STYLE);
        }

        private synchronized void appendRaw(String delta) {
            rawContent.append(delta);
            revision.incrementAndGet();
        }

        private synchronized void setRaw(String text) {
            rawContent.setLength(0);
            rawContent.append(text == null ? "" : text);
            revision.incrementAndGet();
        }

        private synchronized String getRaw() {
            return rawContent.toString();
        }
    }

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
    private Menu menuConfigInformix;
    @FXML
    private CustomShortcutMenuItem menuConfigInstallInformix;
    @FXML
    private CustomShortcutMenuItem menuConfigUninstallInformix;
    @FXML
    private Menu menuConfigGbase;
    @FXML
    private CustomShortcutMenuItem menuConfigInstallGbase;
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
        //menuConfigInformix.setGraphic(IconFactory.group(IconPaths.INFORMIX_LOGO, 0.15,0.12,Color.valueOf("#ff3300")));
        menuConfigInformix.setGraphic(IconFactory.group(IconPaths.INFORMIX_LOGO, 0.15,0.12));

        menuConfigInstallInformix.setGraphic(null);
        menuConfigUninstallInformix.setGraphic(null);
        menuConfigGbase.setGraphic(IconFactory.group(IconPaths.GBASE_LOGO, 0.22));
        menuConfigInstallGbase.setGraphic(null);
        menuConfigUninstallGbase.setGraphic(null);
        menuSettingsLanguage.setGraphic(IconFactory.group(IconPaths.MAIN_MENU_LANGUAGE, 0.68));
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

        if (aiModelChoiceBox != null) {
            var modelOptions = new java.util.ArrayList<>(AI_AVAILABLE_MODELS);
            String currentModel = AiAuthUtil.getModel();
            if (currentModel != null && !currentModel.isBlank() && !modelOptions.contains(currentModel)) {
                modelOptions.add(0, currentModel);
            }
            aiModelChoiceBox.getItems().setAll(modelOptions);
            if (currentModel != null && !currentModel.isBlank()) {
                aiModelChoiceBox.getSelectionModel().select(currentModel);
            } else if (!modelOptions.isEmpty()) {
                aiModelChoiceBox.getSelectionModel().selectFirst();
            }
            aiModelChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && !newVal.isBlank()) {
                    AiAuthUtil.setModel(newVal);
                }
            });
        }

        if (aiSettingsButton != null) {
            aiSettingsButton.setOnAction(event -> showAiApiKeyDialog());
        }
        if (aiMemoryToggleButton != null) {
            aiMemoryToggleButton.setOnAction(event -> {
                aiMemoryEnabled = !aiMemoryEnabled;
                updateAiMemoryToggleButton();
            });
            I18n.localeProperty().addListener((obs, oldVal, newVal) -> updateAiMemoryToggleButton());
            updateAiMemoryToggleButton();
        }

        if (aiChatScrollPane != null) {
            aiChatScrollPane.setFitToWidth(true);
            aiChatScrollPane.setFitToHeight(false);
        }
    }

    private void showAiApiKeyDialog() {
        ButtonType confirmType = new ButtonType(I18n.t("common.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Label promptLabel = new Label(I18n.t("ai.dialog.api_key.prompt"));
        CustomPasswordField keyField = new CustomPasswordField();
        keyField.setPromptText(I18n.t("ai.dialog.api_key.prompt"));
        keyField.setText(AiAuthUtil.getApiToken());

        Label hintLabel = new Label(I18n.t("ai.dialog.api_key.hint") + "\n" + AiAuthUtil.getApiTokenStoragePath());
        hintLabel.setWrapText(true);
        hintLabel.setStyle("-fx-font-size: 10px; -fx-opacity: 0.75;");

        VBox content = new VBox(8, promptLabel, keyField, hintLabel);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("ai.dialog.api_key.title"),
                content,
                420,
                Region.USE_COMPUTED_SIZE,
                confirmType,
                cancelType
        );
        EventHandler<WindowEvent> originalOnShown = dialog.getStage().getOnShown();
        dialog.getStage().setOnShown(event -> {
            if (originalOnShown != null) {
                originalOnShown.handle(event);
            }
            Platform.runLater(() -> {
                keyField.requestFocus();
                keyField.positionCaret(keyField.getText().length());
            });
        });

        ButtonType result = dialog.showAndWait();
        if (result != confirmType) {
            return;
        }

        try {
            String token = keyField.getText() == null ? "" : keyField.getText().trim();
            AiAuthUtil.setApiToken(token);
            NotificationUtil.showMainNotification(
                    token.isEmpty() ? I18n.t("ai.notice.api_key_cleared") : I18n.t("ai.notice.api_key_saved")
            );
        } catch (Exception e) {
            AlertUtil.CustomAlert(I18n.t("common.error"), e.getMessage());
        }
    }

    private void initMenuActions() {
        newSqlFileMenuItem.setOnAction(event -> {
            TabpaneUtil.addCustomSqlTab(null);});
        installSettingsMenuBehavior();
        installConfigMenuBehavior();
    }

    private void installSettingsMenuBehavior() {
        installHoverHideMenuBehavior(menuSettings, "settingsMenuHoverFixInstalled", menuSettingsLanguage);
    }

    private void installConfigMenuBehavior() {
        installHoverHideMenuBehavior(menuConfig, "configMenuHoverFixInstalled", menuConfigInformix, menuConfigGbase);
    }

    private void installHoverHideMenuBehavior(Menu ownerMenu, String propertyKey, Menu... submenus) {
        ownerMenu.setOnShowing(event -> {
            if (submenus == null || submenus.length == 0) {
                return;
            }
            ContextMenu parentPopup = null;
            for (Menu submenu : submenus) {
                if (submenu != null && submenu.getParentPopup() != null) {
                    parentPopup = submenu.getParentPopup();
                    break;
                }
            }
            if (parentPopup == null || parentPopup.getProperties().containsKey(propertyKey)) {
                return;
            }
            parentPopup.getProperties().put(propertyKey, Boolean.TRUE);
            parentPopup.skinProperty().addListener((obs, oldSkin, newSkin) -> {
                if (newSkin != null) {
                    attachMenuHoverHideFilter(newSkin.getNode(), submenus);
                }
            });
            if (parentPopup.getSkin() != null) {
                attachMenuHoverHideFilter(parentPopup.getSkin().getNode(), submenus);
            }
        });
    }

    private void attachMenuHoverHideFilter(Node skinRoot, Menu... submenus) {
        if (skinRoot == null || Boolean.TRUE.equals(skinRoot.getProperties().get("hoverHideFilterInstalled"))) {
            return;
        }
        skinRoot.getProperties().put("hoverHideFilterInstalled", Boolean.TRUE);
        skinRoot.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, mouseEvent -> {
            if (!(mouseEvent.getTarget() instanceof Node target)) {
                return;
            }
            Node menuItemNode = findAncestorMenuItem(target, skinRoot);
            if (menuItemNode == null) {
                return;
            }
            for (Menu submenu : submenus) {
                if (submenu != null && submenu.isShowing() && !isMenuItemNodeForMenu(menuItemNode, submenu)) {
                    submenu.hide();
                }
            }
        });
    }

    private Node findAncestorMenuItem(Node target, Node skinRoot) {
        Node current = target;
        while (current != null && current != skinRoot) {
            if (current.getStyleClass().contains("menu-item")) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean isMenuItemNodeForMenu(Node menuItemNode, Menu menu) {
        return menu != null
                && menuItemNode != null
                && menu.getText() != null
                && nodeContainsText(menuItemNode, menu.getText());
    }

    private boolean nodeContainsText(Node node, String text) {
        if (node == null || text == null) {
            return false;
        }
        if (node instanceof Labeled labeled && text.equals(labeled.getText())) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (nodeContainsText(child, text)) {
                    return true;
                }
            }
        }
        return false;
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
        bindText(menuConfigInformix, "main.menu.config.informix");
        bindText(menuConfigInstallInformix, "main.menu.config.install_informix");
        bindText(menuConfigUninstallInformix, "main.menu.config.uninstall_informix");
        bindText(menuConfigGbase, "main.menu.config.gbase");
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
            addAiMarkdownMessage(buildApiKeyGuideMessage());
            return;
        }

        AiMessageView messageView = addStreamingAiMessage(I18n.t("ai.replying"));
        aiStreamingMessage = messageView;
        updateAiSendButtonText(true);
        aiCancelled = false;

        aiTaskFuture = com.dbboys.app.AppExecutor.submit(() -> {
            // 与 Markdown 侧边栏「搜索」同一套结果：前 5 条片段入提示词，回复末尾展示前 3 条文档链接
            List<MarkdownSearchUtil.KnowledgeReference> references =
                    MarkdownSearchUtil.loadAiKnowledgeFromSearch(text);
            List<AiConversationMessage> historySnapshot = aiMemoryEnabled
                    ? snapshotAiConversationHistory()
                    : List.of();
            String prompt = buildAiPrompt(text, references, historySnapshot);
            log.info("AI request prompt:\n{}", prompt);
            System.out.println("=== AI request prompt begin ===");
            System.out.println(prompt);
            System.out.println("=== AI request prompt end ===");
            String reply = com.dbboys.util.AiApiUtil.chatStream(prompt, delta -> {
                if (aiCancelled || delta == null || delta.isEmpty()) {
                    return;
                }
                messageView.appendRaw(delta);
                scheduleAiMessageRender(messageView);
            });
            Platform.runLater(() -> {
                if (aiCancelled) {
                    aiStreamingMessage = null;
                    aiTaskFuture = null;
                    updateAiSendButtonText(false);
                    return;
                }
                String content = reply != null && !reply.isEmpty() ? reply : I18n.t("ai.notice.api_error");
                rememberAiConversationTurn(text, sanitizeAiReplyForDisplay(reply));
                content = appendAiReferences(content, references);
                renderAiMessage(messageView, content, true);
                scrollAiChatToBottom();
                aiStreamingMessage = null;
                aiTaskFuture = null;
                updateAiSendButtonText(false);
            });
        });
    }

    private String buildAiPrompt(String userQuestion,
                                 List<MarkdownSearchUtil.KnowledgeReference> references,
                                 List<AiConversationMessage> history) {
        String safeQuestion = userQuestion == null ? "" : userQuestion.trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append("请优先参考下面提供的知识库检索结果回答用户问题。");
        prompt.append("如果检索内容不足以支撑结论，结合通用知识或网络信息补充完整。");
        if (history != null && !history.isEmpty()) {
            prompt.append("\n\n最近对话历史（按时间顺序，最多保留最近")
                    .append(AI_HISTORY_TURNS)
                    .append("轮）：");
            for (AiConversationMessage message : history) {
                if (message == null || message.content() == null || message.content().isBlank()) {
                    continue;
                }
                prompt.append("\n\n[")
                        .append("assistant".equalsIgnoreCase(message.role()) ? "助手" : "用户")
                        .append("]\n")
                        .append(message.content().trim());
            }
        }
        prompt.append("\n\n当前用户问题：\n").append(safeQuestion);
        if (!references.isEmpty()) {
            prompt.append("\n\n知识库检索结果（与侧边栏搜索一致，按相关性排序，共")
                    .append(references.size())
                    .append("条片段）：");
            for (int i = 0; i < references.size(); i++) {
                MarkdownSearchUtil.KnowledgeReference ref = references.get(i);
                prompt.append("\n\n[").append(i + 1).append("]");
                if (ref.title() != null && !ref.title().isBlank()) {
                    prompt.append("\n来源：").append(ref.title());
                }
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
        String content = sanitizeAiReplyForDisplay(reply == null ? "" : reply.trim()).trim();
        if (references == null || references.isEmpty()) {
            return content;
        }
        StringBuilder builder = new StringBuilder(content);
        if (!content.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("参考文档：\n");
        int displayIndex = 1;
        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> shownPaths = new java.util.LinkedHashMap<>();
        for (MarkdownSearchUtil.KnowledgeReference ref : references) {
            if (ref == null || ref.path() == null || ref.path().isBlank()) {
                continue;
            }
            java.util.LinkedHashSet<String> pageLabels = shownPaths.get(ref.path());
            if (pageLabels == null) {
                if (shownPaths.size() >= MarkdownSearchUtil.AI_UI_REFERENCE_LINK_COUNT) {
                    continue;
                }
                pageLabels = new java.util.LinkedHashSet<>();
                shownPaths.put(ref.path(), pageLabels);
            }
            pageLabels.addAll(extractAiReferenceLocationLabels(ref.title()));
        }
        for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> entry : shownPaths.entrySet()) {
            String path = entry.getKey();
            if (path == null || path.isBlank()) {
                continue;
            }
            String linkTarget = path.replace('\\', '/');
            String title;
            try {
                title = Paths.get(path).getFileName().toString();
            } catch (Exception ex) {
                title = linkTarget;
            }
            String pageSuffix = formatAiReferencePageSuffix(entry.getValue());
            builder.append(displayIndex++)
                    .append(". [")
                    .append(title)
                    .append(pageSuffix)
                    .append("](")
                    .append(linkTarget)
                    .append(")\n");
        }
        return builder.toString().trim();
    }

    private List<String> extractAiReferenceLocationLabels(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String detail = title;
        int splitIndex = detail.indexOf(" · ");
        if (splitIndex >= 0 && splitIndex + 3 < detail.length()) {
            detail = detail.substring(splitIndex + 3);
        }
        List<String> pageLabels = new ArrayList<>();
        Matcher matcher = AI_REFERENCE_LOCATION_PATTERN.matcher(detail);
        while (matcher.find()) {
            pageLabels.add(matcher.group());
        }
        return pageLabels;
    }

    private String formatAiReferencePageSuffix(java.util.LinkedHashSet<String> pageLabels) {
        if (pageLabels == null || pageLabels.isEmpty()) {
            return "";
        }
        return "（" + String.join("、", pageLabels) + "）";
    }

    private List<AiConversationMessage> snapshotAiConversationHistory() {
        synchronized (aiConversationHistory) {
            return new ArrayList<>(aiConversationHistory);
        }
    }

    private void rememberAiConversationTurn(String userText, String assistantText) {
        if (!aiMemoryEnabled) {
            return;
        }
        String safeUser = userText == null ? "" : userText.trim();
        String safeAssistant = assistantText == null ? "" : assistantText.trim();
        if (safeUser.isEmpty() || safeAssistant.isEmpty()) {
            return;
        }
        synchronized (aiConversationHistory) {
            aiConversationHistory.add(new AiConversationMessage("user", safeUser));
            aiConversationHistory.add(new AiConversationMessage("assistant", safeAssistant));
            int maxMessages = AI_HISTORY_TURNS * 2;
            while (aiConversationHistory.size() > maxMessages) {
                aiConversationHistory.remove(0);
            }
        }
    }

    private String sanitizeAiReplyForDisplay(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return com.dbboys.util.AiApiUtil.stripThinkingFromAssistantReply(content);
    }

    private String buildApiKeyGuideMessage() {
        String provider = AiAuthUtil.getCurrentProviderKey();
        return String.join("\n",
                I18n.t("ai.reply.api_key_guide.title"),
                "",
                I18n.t("ai.reply.api_key_guide.step1"),
                I18n.t("ai.reply.api_key_guide." + provider + ".link", I18n.t("ai.reply.api_key_guide.link")),
                I18n.t("ai.reply.api_key_guide." + provider + ".step2", I18n.t("ai.reply.api_key_guide.step2")),
                I18n.t("ai.reply.api_key_guide.step3"),
                I18n.t("ai.reply.api_key_guide.step4"),
                "",
                I18n.t("ai.reply.api_key_guide." + provider + ".model_reason", I18n.t("ai.reply.api_key_guide.model_reason"))
        );
    }

    private void cancelAiRequest() {
        aiCancelled = true;
        AiMessageView streaming = aiStreamingMessage;
        if (streaming != null) {
            streaming.stopThinkingAnimation();
            String aborted = I18n.t("ai.aborted");
            streaming.setRaw(aborted);
            renderStreamingAiMessage(streaming, aborted);
            setAiMessageActionsVisible(streaming, true);
        }
        if (aiTaskFuture != null && !aiTaskFuture.isDone()) {
            aiTaskFuture.cancel(true);
        }
        aiTaskFuture = null;
        aiStreamingMessage = null;
        updateAiSendButtonText(false);
    }

    private void updateAiMemoryToggleButton() {
        if (aiMemoryToggleButton == null) {
            return;
        }
        aiMemoryToggleButton.setText(aiMemoryEnabled
                ? I18n.t("ai.memory.enabled")
                : I18n.t("ai.memory.disabled"));
        String tooltipKey = aiMemoryEnabled
                ? "ai.memory.toggle.disable.tooltip"
                : "ai.memory.toggle.enable.tooltip";
        Tooltip tooltip = aiMemoryToggleButton.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip();
            aiMemoryToggleButton.setTooltip(tooltip);
        }
        tooltip.setText(I18n.t(tooltipKey));
        aiMemoryToggleButton.setStyle(aiMemoryEnabled ? AI_MEMORY_BTN_STYLE_ON : AI_MEMORY_BTN_STYLE_OFF);
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
        AiMessageView view = createAiMessageView(() -> content == null ? "" : content);
        aiChatMessages.getChildren().add(view.messageGroup);
        keepAiMessageVisible(view.bubble, view.messageGroup);
        renderAiMessage(view, content, true);
        scrollAiChatToBottom();
    }

    private AiMessageView addStreamingAiMessage(String initialContent) {
        AiMessageView[] ref = new AiMessageView[1];
        AiMessageView view = createAiMessageView(() ->
                sanitizeAiReplyForDisplay(ref[0].getRaw() == null ? "" : ref[0].getRaw()));
        ref[0] = view;
        setAiMessageActionsVisible(view, false);
        aiChatMessages.getChildren().add(view.messageGroup);
        keepAiMessageVisible(view.bubble, view.messageGroup);
        renderStreamingAiMessage(view, initialContent);
        scrollAiChatToBottom();
        return view;
    }

    private AiMessageView createAiMessageView(Supplier<String> textSupplier) {
        VBox messageGroup = new VBox();
        StackPane bubble = new StackPane();
        HBox buttonRow = createMessageButtonRow(textSupplier, Pos.CENTER_LEFT);
        AiMessageView view = new AiMessageView(messageGroup, bubble, buttonRow);
        configureAiMessageArea(view.area);
        configureAiStreamingLabel(view.streamingLabel, bubble);
        bubble.getChildren().addAll(view.streamingLabel, view.area);
        bubble.setStyle(
                "-fx-background-color: -color-bg-content;" +
                "-fx-background-radius: " + MESSAGE_BUBBLE_RADIUS + ";" +
                "-fx-border-radius: " + MESSAGE_BUBBLE_RADIUS + ";"
        );
        bubble.prefWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        bubble.maxWidthProperty().bind(aiChatMessages.widthProperty().subtract(24));
        view.area.prefWidthProperty().bind(bubble.widthProperty());
        view.area.maxWidthProperty().bind(bubble.widthProperty());
        messageGroup.getChildren().setAll(bubble, buttonRow);
        messageGroup.setSpacing(4);
        messageGroup.setAlignment(Pos.CENTER_LEFT);
        messageGroup.setFillWidth(true);
        return view;
    }

    private void configureAiMessageArea(CustomAiStyledArea area) {
        area.setEditable(false);
        area.setStyle(
                area.getStyle() +
                        ";-fx-padding: 6 10 6 10;" +
                        "-fx-background-color: transparent;" +
                        "-fx-background-radius: " + MESSAGE_BUBBLE_RADIUS + ";"
        );
    }

    private void configureAiStreamingLabel(Label label, StackPane bubble) {
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle(AI_STREAMING_LABEL_STYLE);
        label.maxWidthProperty().bind(bubble.widthProperty().subtract(20));
        StackPane.setAlignment(label, Pos.CENTER_LEFT);
    }

    private void scheduleAiMessageRender(AiMessageView view) {
        if (view == null || !view.renderScheduled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            try {
                int revision = view.revision.get();
                String displayContent = sanitizeAiReplyForDisplay(
                        view.getRaw() == null ? "" : view.getRaw());
                renderStreamingAiMessage(view, displayContent);
                view.renderedRevision.set(revision);
            } finally {
                view.renderScheduled.set(false);
                if (view.revision.get() != view.renderedRevision.get()) {
                    scheduleAiMessageRender(view);
                }
            }
        });
    }

    private void renderAiMessage(AiMessageView view, String content, boolean updateRaw) {
        if (view == null) {
            return;
        }
        view.stopThinkingAnimation();
        if (updateRaw) {
            view.setRaw(content);
        }
        showFinalAiContent(view);
        view.streamingLabel.setText("");
        view.area.clear();
        view.area.parseMarkdownWithStyles(sanitizeAiReplyForDisplay(content == null ? "" : content));
        setAiMessageActionsVisible(view, updateRaw);
    }

    private void renderStreamingAiMessage(AiMessageView view, String content) {
        if (view == null) {
            return;
        }
        showStreamingAiContent(view);
        String raw = view.getRaw();
        if (raw.isEmpty() && isAiReplyingPlaceholder(content)) {
            view.startThinkingAnimation(AiMessageView.aiThinkingBaseText(I18n.t("ai.replying")));
        } else {
            view.stopThinkingAnimation();
            view.streamingLabel.setText(content == null ? "" : content);
        }
    }

    private static boolean isAiReplyingPlaceholder(String content) {
        if (content == null) {
            return true;
        }
        return content.trim().equals(I18n.t("ai.replying").trim());
    }

    private void showStreamingAiContent(AiMessageView view) {
        if (view == null) {
            return;
        }
        view.streamingLabel.setManaged(true);
        view.streamingLabel.setVisible(true);
        view.area.setManaged(false);
        view.area.setVisible(false);
    }

    private void showFinalAiContent(AiMessageView view) {
        if (view == null) {
            return;
        }
        view.area.setManaged(true);
        view.area.setVisible(true);
        view.streamingLabel.setManaged(false);
        view.streamingLabel.setVisible(false);
    }

    private void setAiMessageActionsVisible(AiMessageView view, boolean visible) {
        if (view == null || view.buttonRow == null) {
            return;
        }
        view.buttonRow.setVisible(visible);
        view.buttonRow.setManaged(visible);
    }

    private void addUserMarkdownMessage(String content) {
        String text = content == null ? "" : content;

        Label messageLabel = new Label(text);
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);
        messageLabel.setStyle("-fx-text-fill: -color-fg-emphasis; -fx-font-size: 10px;");

        StackPane bubble = new StackPane(messageLabel);
        bubble.setStyle(
                "-fx-background-color: -color-accent-emphasis;" +
                "-fx-background-radius: " + MESSAGE_BUBBLE_RADIUS + ";" +
                "-fx-border-radius: " + MESSAGE_BUBBLE_RADIUS + ";" +
                "-fx-padding: 6 10 6 10;"
        );
        bubble.maxWidthProperty().bind(aiChatMessages.widthProperty().multiply(USER_BUBBLE_MAX_WIDTH_RATIO));
        messageLabel.maxWidthProperty().bind(bubble.maxWidthProperty().subtract(20));
        HBox messageRow = createMessageRow(bubble, () -> text, Pos.CENTER_RIGHT, Pos.CENTER_RIGHT);
        aiChatMessages.getChildren().add(messageRow);
        keepAiMessageVisible(bubble, messageRow);
        scrollAiChatToBottom();
    }

    private HBox createMessageRow(Node messageNode, Supplier<String> textSupplier, Pos rowAlignment, Pos buttonAlignment) {
        VBox messageGroup = new VBox(4, messageNode, createMessageButtonRow(textSupplier, buttonAlignment));
        messageGroup.setAlignment(buttonAlignment);
        messageGroup.setFillWidth(true);
        HBox messageRow = new HBox(messageGroup);
        messageRow.setAlignment(rowAlignment);
        messageRow.setFillHeight(false);
        return messageRow;
    }

    private HBox createMessageButtonRow(Supplier<String> textSupplier, Pos buttonAlignment) {
        HBox buttonRow = new HBox(createMessageCopyButton(textSupplier));
        buttonRow.setAlignment(buttonAlignment);
        buttonRow.setFillHeight(false);
        return buttonRow;
    }

    private Button createMessageCopyButton(Supplier<String> textSupplier) {
        Button copyButton = new Button();
        copyButton.setText("");
        copyButton.setGraphic(IconFactory.group(IconPaths.COPY, 0.62));
        copyButton.getStyleClass().add("small");
        copyButton.setFocusTraversable(false);
        copyButton.setTooltip(new Tooltip(I18n.t("genericstyled.menu.copy")));
        copyButton.setOnAction(event -> copyMessageText(textSupplier == null ? "" : textSupplier.get()));
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
        if (aiChatScrollPane == null || !aiScrollScheduled.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            try {
                if (aiChatScrollPane != null) {
                    aiChatScrollPane.setVvalue(1.0);
                }
            } finally {
                aiScrollScheduled.set(false);
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
