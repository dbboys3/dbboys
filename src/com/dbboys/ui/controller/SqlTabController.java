package com.dbboys.ui.controller;

import com.dbboys.app.AppContext;
import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.ui.component.*;
import com.dbboys.core.ConnectionService;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.service.SqlexeService;
import com.dbboys.infra.util.*;
import com.dbboys.infra.config.ConfigManagerUtil;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.dialog.PopupWindowUtil;
import com.dbboys.ui.controller.tree.TreeViewUtil;
import com.dbboys.model.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqlTabController {

    /** Same colors as execution-result summary success/failure filter buttons. */
    public static final String RESULT_SUCCESS_BUTTON_COLOR = "#2E9E5B";
    public static final String RESULT_FAILURE_BUTTON_COLOR = "#CC3D3D";
    private static final Logger log = LogManager.getLogger(SqlTabController.class);
    static final String SQL_EXECUTE_PROCESS_TASK_LABEL_KEY = "sqlExecuteProcessTaskLabel";
    private static final String SQL_PANEL_KEEPALIVE_INTERVAL_CONFIG_KEY = "CONNECT_KEEPALIVE_SECONDS";
    private static final int DEFAULT_SQL_PANEL_KEEPALIVE_INTERVAL_SECONDS = 180;
    /** 与 Connect 一致：进程内首次读取 CONNECT_KEEPALIVE_SECONDS；Integer.MIN_VALUE 表示尚未读取。 */
    private static volatile int sqlPanelKeepAliveSecondsSnap = Integer.MIN_VALUE;

    @FXML
    public Button sqlRunButton;
    @FXML
    public ChoiceBox<Connect> sqlConnectChoiceBox;
    @FXML
    public ChoiceBox<Catalog> sqlDbChoiceBox;
    @FXML
    public ChoiceBox<String> sqlCommitModeChoiceBox;
    //sql编辑框及结果集
    @FXML
    public SplitPane sqlSplitPane;
    @FXML
    public StackPane sqlEditStackPane;
    @FXML
    public CustomSqlEditCodeArea sqlEditCodeArea;
    //
    public boolean isRefreshConnectList=false;
    //分隔符位置
    public Double sqlSplitPaneDividerPosition = AppState.getSplit2Pos();
    public Connect sqlConnect = new Connect();
    public Task<Void> sqlTask = new Task<>() {
        @Override
        protected Void call() {
            return null;
        }
    };
    public ObservableList<Catalog> databaseChoiceBoxList = FXCollections.observableArrayList();
    public CustomSearchReplaceVbox searchReplaceBox = new CustomSearchReplaceVbox(null);
    public VBox resultSetVBox = new VBox();
    public ResultSetTabController currentResultSetTabController;
    public CustomResultsetTab customResultsetTab;
    //结果集表格相关列表
    public ObservableList<UpdateResult> updateResults;
    public FilteredList<UpdateResult> filteredUpdateResults;
    public UpdateResult updateResult;
    public String sqlInit = "";  //表或视图拖动新建tab后自动执行的sql
    public String sqlExe;
    public Integer sqlAffect;
    public PreparedStatement sqlStatement;
    public ParameterMetaData parameterMetaData;
    public CallableStatement sqlCallableStatement = null;
    public long sqlUsedTime;
    public long sqlStartTime;
    public long sqlEndTime;
    public long sqlTotalTime;
    public int sqlStatementCount = 0;
    public SimpleStringProperty sqlTransactionText = new SimpleStringProperty("");
    boolean isSingleSql = true;  //判断是否只有一条sql，如果是，select要执行并弹出报错信息，update要弹出报错，且用于是否在任务完成后获取主键
    @FXML
    VBox sqlTab;
    @FXML
    Pane topPane;
    @FXML
    Button sqlExplainButton;
    @FXML
    Button sqlStopButton;
    @FXML
    StackPane sqlConnectChoiceBoxIconStackPane;
    @FXML
    StackPane sqlDbIconPane;
    @FXML
    StackPane sqlUserIconPane;
    @FXML
    CustomLabelTextField sqlUserTextField;
    @FXML
    ChoiceBox<String> sqlSqlModeChoiceBox;
    @FXML
    Button sqlRecordButton;
    @FXML
    Label sqlReadOnlyLabel;
    @FXML
    VirtualizedScrollPane sqlEditScrollPane;
    @FXML
    HBox transactionBox;
    @FXML
    Button transactionCommitButton;
    @FXML
    Button transactionRollbackButton;
    //结果集
    @FXML
    Pane bottomPane;
    @FXML
    StackPane bottomPaneStackPane;
    @FXML
    TabPane resultsetTabPane;
    @FXML
    Tab resultsetSummaryTab;
    @FXML
    CustomTableView resultsetTotalTableView;
    @FXML
    StackPane resultsetStackPane;
    Button resultSuccessFilterButton;
    Button resultFailureFilterButton;
    HBox resultFilterButtonBox;
    //执行过程中提示面板
    @FXML
    StackPane sqlExecuteProcessStackPane;
    @FXML
    HBox sqlExecuteProcessBox;
    @FXML
    Label sqlExecuteLoadingLabel;
    @FXML
    Label sqlExecuteTaskInfo;
    @FXML
    Label sqlExecuteTimeInfo;
    CustomInfoStackPane explain_result_stackpane;
    Boolean isSqlRefresh = false;
    private final ConnectionService connectionService = AppContext.get(ConnectionService.class);
    private final SqlexeService sqlexeService = AppContext.get(SqlexeService.class);
    final Connect defaultConnect = new Connect();
    final Catalog defaultDatabase = new Catalog();

    /** 工具栏当前选中项为已保存的 General JDBC 连接时，禁用库选择与用户名展示（URL 已含库信息）。 */
    public boolean isGeneralJdbcToolbarSelection() {
        if (sqlConnectChoiceBox == null) {
            return false;
        }
        Connect v = sqlConnectChoiceBox.getValue();
        return v != null && v != defaultConnect && v.getDbtype() != null
                && "GENERAL JDBC".equalsIgnoreCase(v.getDbtype().trim());
    }

    List<Object> sqlParamList = new ArrayList<>();
    String sqlExecutionResult = "";
    boolean sqlExecutionSuccess = false;
    String sqlText = "";
    final Tooltip commitButtonTooltip = new Tooltip();
    String newResultsetTabName;
    final int[] sqlSelectionRange = {0, 0};
    boolean suppressConnectChange = false;
    boolean suppressDbChange = false;
    boolean suppressCommitModeChange = false;
    ResultSetTabController activeResultSetController;
    Label sqlConnectChoiceBoxDbIcon;
    Label sqlConnectChoiceBoxLoadingIcon;
    SVGPath sqlConnectIconPath;
    SVGPath sqlDbIconPath;
    SVGPath sqlUserIconPath;
    Timeline sqlPanelKeepAliveTimeline;
    final AtomicBoolean sqlPanelKeepAliveChecking = new AtomicBoolean(false);

    SqlExecutionHelper executionHelper;
    SqlTabUiHelper uiHelper;
    SqlTabI18nHelper i18nHelper;
    SqlConnectionHandler connectionHandler;
    SqlResultHandler resultHandler;
    private ResultFilterMode resultFilterMode = ResultFilterMode.ALL;

    private enum ResultFilterMode {
        ALL,
        SUCCESS,
        FAILURE
    }

    void clearUpdateResults() {
        Platform.runLater(() -> {
            updateResults.clear();
            refreshResultFilterButtons();
        });
    }

    void finishExecution(int selectionStart, int selectionEnd) {
        Platform.runLater(() -> {
            sqlExecuteProcessStackPane.setVisible(false);
            explain_result_stackpane.setVisible(false);
            sqlEditCodeArea.selectRange(selectionStart, selectionEnd);
        });
    }

    void hideExecuteProcess() {
        Platform.runLater(() -> sqlExecuteProcessStackPane.setVisible(false));
    }

    void setResultsetVisible(boolean visible) {
        Platform.runLater(() -> resultSetVBox.setVisible(visible));
    }

    void setExplainVisible(boolean visible) {
        Platform.runLater(() -> explain_result_stackpane.setVisible(visible));
    }

    void showExplainText(String text) {
        Platform.runLater(() -> {
            resultSetVBox.setVisible(false);
            explain_result_stackpane.setVisible(true);
            explain_result_stackpane.codeArea.replaceText(text);
        });
    }

    void selectRangeAndFollow(int start, int end) {
        Platform.runLater(() -> {
            sqlEditCodeArea.selectRange(start, end);
            sqlEditCodeArea.requestFollowCaret();
        });
    }

    void addUpdateResult(UpdateResult result, boolean clearFirst) {
        Platform.runLater(() -> {
            if (clearFirst) {
                updateResults.clear();
            }
            updateResults.add(result);
            refreshResultFilterButtons();
        });
    }

    void applyUpdateResultFilter() {
        if (filteredUpdateResults == null) {
            return;
        }
        filteredUpdateResults.setPredicate(item -> {
            if (item == null) {
                return false;
            }
            if (resultFilterMode == ResultFilterMode.SUCCESS) {
                return isSuccessResult(item);
            }
            if (resultFilterMode == ResultFilterMode.FAILURE) {
                return !isSuccessResult(item);
            }
            return true;
        });
    }

    void refreshResultFilterButtons() {
        if (resultSuccessFilterButton == null || resultFailureFilterButton == null || updateResults == null) {
            return;
        }
        int successCount = 0;
        int failureCount = 0;
        for (UpdateResult item : updateResults) {
            if (isSuccessResult(item)) {
                successCount++;
            } else {
                failureCount++;
            }
        }
        resultSuccessFilterButton.setText(I18n.t("sql.result.filter.success", "成功") + " " + successCount);
        resultFailureFilterButton.setText(I18n.t("sql.result.filter.failure", "失败") + " " + failureCount);
        applyResultFilterButtonState(resultSuccessFilterButton, RESULT_SUCCESS_BUTTON_COLOR, resultFilterMode == ResultFilterMode.SUCCESS);
        applyResultFilterButtonState(resultFailureFilterButton, RESULT_FAILURE_BUTTON_COLOR, resultFilterMode == ResultFilterMode.FAILURE);
    }

    private void toggleResultFilter(ResultFilterMode mode) {
        resultFilterMode = resultFilterMode == mode ? ResultFilterMode.ALL : mode;
        applyUpdateResultFilter();
        refreshResultFilterButtons();
    }

    void toggleResultFilterSuccess() {
        toggleResultFilter(ResultFilterMode.SUCCESS);
    }

    void toggleResultFilterFailure() {
        toggleResultFilter(ResultFilterMode.FAILURE);
    }

    private boolean isSuccessResult(UpdateResult item) {
        return item != null
                && item.getResult() != null
                && item.getResult().startsWith(I18n.t("sql.exec.success"));
    }

    private void applyResultFilterButtonState(Button button, String background, boolean active) {
        if (button == null) {
            return;
        }
        button.getStyleClass().removeAll(
                "accent-pill-success", "accent-pill-failure", "accent-pill-active");
        button.getStyleClass().add(RESULT_SUCCESS_BUTTON_COLOR.equals(background)
                ? "accent-pill-success"
                : "accent-pill-failure");
        if (active) {
            button.getStyleClass().add("accent-pill-active");
        }
    }


    void updateSqlModeChoicebox(List<String> sqlmodes) {
        if (sqlConnect.getConn() == null) {
            Platform.runLater(() -> sqlSqlModeChoiceBox.setVisible(false));
            return;
        }
        if (sqlmodes == null || sqlmodes.isEmpty()) {
            Platform.runLater(() -> sqlSqlModeChoiceBox.setVisible(false));
            return;
        }
        if ("sqlmode=none".equals(sqlmodes.get(0))) {
            Platform.runLater(() -> sqlSqlModeChoiceBox.setVisible(false));
            return;
        }
        List<String> distinctModes = new ArrayList<>(new LinkedHashSet<>(sqlmodes));
        String currentMode = sqlmodes.get(0);
        Platform.runLater(() -> {
            sqlSqlModeChoiceBox.setVisible(true);
            sqlSqlModeChoiceBox.getItems().clear();
            sqlSqlModeChoiceBox.getItems().addAll(distinctModes);
            for (String item : sqlSqlModeChoiceBox.getItems()) {
                if (item.equals(currentMode)) {
                    sqlSqlModeChoiceBox.setValue(item);
                    break;
                }
            }
        });
    }

    private void configureSqlExecuteProcessLabel(Label label) {
        label.getStyleClass().add("sql-execute-process-label");
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setPrefHeight(Region.USE_COMPUTED_SIZE);
        label.setMaxHeight(Region.USE_PREF_SIZE);
    }

    private void setupSqlExecuteProcessPane() {
        sqlExecuteProcessStackPane.getStyleClass().add("sql-execute-process-pane");
        sqlExecuteProcessStackPane.setMinWidth(Region.USE_PREF_SIZE);
        sqlExecuteProcessStackPane.setPrefWidth(Region.USE_COMPUTED_SIZE);
        sqlExecuteProcessStackPane.setMaxWidth(Region.USE_PREF_SIZE);
        sqlExecuteProcessStackPane.setMinHeight(Region.USE_PREF_SIZE);
        sqlExecuteProcessStackPane.setPrefHeight(Region.USE_COMPUTED_SIZE);
        sqlExecuteProcessStackPane.setMaxHeight(Region.USE_PREF_SIZE);
        sqlExecuteProcessBox.setSpacing(2);
        configureSqlExecuteProcessLabel(sqlExecuteLoadingLabel);
        configureSqlExecuteProcessLabel(sqlExecuteTaskInfo);
        configureSqlExecuteProcessLabel(sqlExecuteTimeInfo);
        sqlExecuteProcessStackPane.getProperties().put(SQL_EXECUTE_PROCESS_TASK_LABEL_KEY, sqlExecuteTaskInfo);
    }


    public void initialize() throws IOException {
        executionHelper = new SqlExecutionHelper(this);
        uiHelper = new SqlTabUiHelper(this);
        i18nHelper = new SqlTabI18nHelper(this);
        connectionHandler = new SqlConnectionHandler(this, connectionService, sqlexeService);
        resultHandler = new SqlResultHandler(this);

        uiHelper.setupTransactionTooltips();
        i18nHelper.initI18nBindings();
        uiHelper.setupSqlTabIcons();
        setupSqlExecuteProcessPane();
        uiHelper.setupSearchReplacePanel();
        uiHelper.setupResultSetView();
        uiHelper.setupSplitPaneBehavior();
        uiHelper.bindEditorSizeToPane();
        uiHelper.setupConnectIcons();
        setupAiActions();
        connectionHandler.setupDefaultConnectionState();
        connectionHandler.loadConnectChoices();
        /*
        List connect_list = new ArrayList<Connect>();
        for (TreeItem<TreeData> ti : AppState.getDatabaseMetaTreeView().getRoot().getChildren()) {
            for (TreeItem<TreeData> t : ti.getChildren()) {
                Connect newConnect = new Connect((Connect) t.getValue());
                newConnect.setConn(null);
                connect_list.add(newConnect);
            }
        }


        ObservableList<Connect> dbtypelist = FXCollections.observableArrayList(connect_list);
        sqlConnectChoiceBox.setItems(dbtypelist);

         */




        connectionHandler.bindHeaderControls();
        resultHandler.setupResultsetTotalTable();
        setupRunStopExplainActions();

        connectionHandler.setupConnectionListener();
        connectionHandler.setupDatabaseListener();
        connectionHandler.setupCommitModeListener();

        // Wire autocomplete context — keep it in sync with connection/database selection
        sqlEditCodeArea.setCompletionContext(sqlConnect, sqlDbChoiceBox.getValue());
        sqlDbChoiceBox.valueProperty().addListener((obs, oldVal, newVal) ->
                sqlEditCodeArea.setCompletionContext(sqlConnect, newVal));
        sqlConnectChoiceBox.valueProperty().addListener((obs, oldVal, newVal) ->
                sqlEditCodeArea.setCompletionContext(newVal, sqlDbChoiceBox.getValue()));

        sqlRecordButton.setOnAction(envent -> {
            PopupWindowUtil.openSqlHistoryPopupWindow(sqlConnect.getId());
        });

        transactionCommitButton.setOnAction(event -> {
            int start = sqlEditCodeArea.getLength();
            sqlEditCodeArea.appendText("\ncommit;");
            sqlEditCodeArea.moveTo(start);
            sqlEditCodeArea.requestFollowCaret();
            sqlEditCodeArea.selectRange(sqlEditCodeArea.getLength() - "commit;".length(), sqlEditCodeArea.getLength());
            sqlRunButton.fire();
        });
        transactionRollbackButton.setOnAction(event -> {
            int start = sqlEditCodeArea.getLength();
            sqlEditCodeArea.appendText("\nrollback;");
            sqlEditCodeArea.moveTo(start);
            sqlEditCodeArea.requestFollowCaret();
            sqlEditCodeArea.selectRange(sqlEditCodeArea.getLength() - "rollback;".length(), sqlEditCodeArea.getLength());
            sqlRunButton.fire();
        });

        sqlSqlModeChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!sqlTask.isRunning() && newValue != null) {
                sqlTask = executionHelper.createSqlModeTask(sqlConnect, newValue);
                AppExecutor.runTask(sqlTask);
            }
        });

        final Double[] secondsElapsed = {0.0};
        Timeline taskTimeline = new Timeline(new KeyFrame(Duration.seconds(0.1), event1 -> {
            secondsElapsed[0] += 0.1;
            sqlExecuteTimeInfo.setText(i18nHelper.formatExecuteTime(secondsElapsed[0]));
        }));
        taskTimeline.setCycleCount(Timeline.INDEFINITE);

        sqlExecuteProcessStackPane.visibleProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
                if (newValue) {
                    sqlExecuteTaskInfo.setText(I18n.t("sql.exec.running"));
                    secondsElapsed[0] = 0.0;
                    sqlExecuteTimeInfo.setText(i18nHelper.formatExecuteTime(secondsElapsed[0]));
                    taskTimeline.playFromStart();
                } else {
                    taskTimeline.stop();
                    secondsElapsed[0] = 0.0;
                    sqlExecuteTimeInfo.setText(i18nHelper.formatExecuteTime(secondsElapsed[0]));
                }
            }
        });

        searchReplaceBox.setCodeArea(sqlEditCodeArea);
        startSqlPanelKeepAliveCheck();
    }


    private boolean isDefaultConnectSelected() {
        return sqlConnectChoiceBox.getValue() == defaultConnect;
    }





    private void setupRunStopExplainActions() {
        sqlStopButton.setOnAction(event -> executionHelper.cancelCurrentExecution());

        sqlRunButton.setOnAction(event -> {
            if (isDefaultConnectSelected()) {
                NotificationUtil.showMainNotification(I18n.t("sql.notice.select_connection"));
            } else {
                sqlText = executionHelper.resolveSqlText(true);
                if (sqlText.isEmpty()) {
                    NotificationUtil.showMainNotification(I18n.t("sql.notice.enter_sql"));
                } else {
                    if (sqlTask != null && sqlTask.isRunning()) {
                        executionHelper.cancelCurrentExecution();
                    }

                    sqlExecuteProcessStackPane.setVisible(true);
                    if (currentResultSetTabController != null) {
                        currentResultSetTabController.cancel();
                    }
                    sqlTask = executionHelper.createExecuteSqlTask();
                    closeResultSet();
                    disposeAdditionalResultSetTabs();
                    AppExecutor.runTask(sqlTask);
                    if (sqlSplitPane.getDividers().get(0).getPosition() > AppState.getSplit2Pos()) {
                        sqlSplitPane.getDividers().get(0).setPosition(AppState.getSplit2Pos());
                    }
                }
            }
        });

        sqlExplainButton.setOnAction(event -> {
            if (isDefaultConnectSelected()) {
                NotificationUtil.showMainNotification(I18n.t("sql.notice.select_connection"));
            } else {
                if (!sqlEditCodeArea.ensureExecuteTargetSelected()) {
                    NotificationUtil.showMainNotification(I18n.t("sql.notice.enter_explain_sql"));
                    return;
                }
                sqlText = executionHelper.resolveSqlText(false);
                if (sqlText.isEmpty()) {
                    NotificationUtil.showMainNotification(I18n.t("sql.notice.enter_explain_sql"));
                } else {
                    if (sqlTask != null && sqlTask.isRunning()) {
                        executionHelper.cancelCurrentExecution();
                    }
                    sqlExecuteProcessStackPane.setVisible(true);
                    if (currentResultSetTabController != null) {
                        currentResultSetTabController.cancel();
                    }
                    sqlTask = executionHelper.createExplainTask();
                    closeResultSet();
                    disposeAdditionalResultSetTabs();
                    AppExecutor.runTask(sqlTask);
                    if (sqlSplitPane.getDividers().get(0).getPosition() > AppState.getSplit2Pos()) {
                        sqlSplitPane.getDividers().get(0).setPosition(AppState.getSplit2Pos());
                    }
                }
            }
        });
    }


    //连接断开处理
    public void connectionDisconnected() {
        Platform.runLater(() -> {
            sqlTransactionText.set("");
            //transactionBox.setVisible(false);
            if (AlertUtil.CustomAlertConfirm(I18n.t("common.error"), I18n.t("sql.confirm.reconnect"))) {
                //嵌套Platform保证前一步完成后执行下一步，避免渲染延迟导致前后顺序错误
                Platform.runLater(() -> {
                    sqlConnectChoiceBox.setValue(defaultConnect);
                    Platform.runLater(() -> {
                        sqlConnectChoiceBox.setValue(sqlConnect);
                        Platform.runLater(() -> {
                            if (TreeViewUtil.metadataService.testConn(sqlConnect)) {
                                NotificationUtil.showMainNotification(I18n.t("sql.notice.reconnect_success"));
                                //    data_manager_sqlRunButton.fire();
                            }
                        });
                    });
                });
            } else {
                sqlConnectChoiceBox.setValue(defaultConnect);
            }
        });
    }

    public void closeResultSet() {
        try {
            if (currentResultSetTabController != null) {
                currentResultSetTabController.closeResultSet();
            }
            if (resultsetTabPane != null) {
                for (Tab tab : resultsetTabPane.getTabs()) {
                    if (tab instanceof CustomResultsetTab) {
                        ((CustomResultsetTab) tab).resultSetTabController.closeResultSet();
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            //AlertUtil.CustomAlert("错误", "["+e.getErrorCode()+"]"+e.getMessage());
        }
    }

    void disposeAdditionalResultSetTabs() {
        if (resultsetTabPane == null || resultsetTabPane.getTabs().size() <= 1) {
            return;
        }
        List<Tab> tabsToRemove = new ArrayList<>(resultsetTabPane.getTabs().subList(1, resultsetTabPane.getTabs().size()));
        for (Tab tab : tabsToRemove) {
            if (tab instanceof CustomResultsetTab customTab) {
                customTab.resultSetTabController.dispose();
            }
        }
        resultsetTabPane.getTabs().removeAll(tabsToRemove);
    }

    public void prepareForDatabaseSwitch() {
        closeResultSet();
        if (currentResultSetTabController != null) {
            currentResultSetTabController.init();
        }
        if (resultsetTabPane != null) {
            disposeAdditionalResultSetTabs();
            resultsetTabPane.getSelectionModel().select(resultsetSummaryTab);
        }
        resultSetVBox.setVisible(true);
        if (explain_result_stackpane != null) {
            explain_result_stackpane.setVisible(false);
        }
        activeResultSetController = null;
    }


    public void closeConn() {
        try {
            stopSqlPanelKeepAliveCheck();
            if (!sqlStopButton.isDisable()) {
                sqlStopButton.fire();
            }
            closeResultSet();
            if (sqlConnect.getConn() != null && !sqlConnect.getConn().isClosed()) {
                sqlConnect.getConn().close();  //如果延迟高，如50ms以上，关闭连接可能会需要2-3秒
                sqlConnect = defaultConnect;  //恢复sqlConnect为初始值，避免连接断开后窗口切换连接失败自动连接到刚断开的连接
                sqlConnectChoiceBox.setValue(defaultConnect);
                sqlSqlModeChoiceBox.setVisible(false);
                sqlTransactionText.set("");
                //transactionBox.setVisible(false);
                databaseChoiceBoxList.clear();
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            AlertUtil.CustomAlert(I18n.t("common.error"), "[" + e.getErrorCode() + "]" + e.getMessage());
        }
    }

    public void initializeConnectList() {
        connectionHandler.initializeConnectList();
    }

    private void startSqlPanelKeepAliveCheck() {
        stopSqlPanelKeepAliveCheck();
        int intervalSeconds = resolveSqlPanelKeepAliveIntervalSeconds();
        if (intervalSeconds <= 0) {
            return;
        }
        sqlPanelKeepAliveTimeline = new Timeline(
                new KeyFrame(Duration.seconds(intervalSeconds), event -> checkSqlPanelKeepAlive())
        );
        sqlPanelKeepAliveTimeline.setCycleCount(Timeline.INDEFINITE);
        sqlPanelKeepAliveTimeline.play();
    }

    private void stopSqlPanelKeepAliveCheck() {
        if (sqlPanelKeepAliveTimeline != null) {
            sqlPanelKeepAliveTimeline.stop();
            sqlPanelKeepAliveTimeline = null;
        }
        sqlPanelKeepAliveChecking.set(false);
    }

    private int resolveSqlPanelKeepAliveIntervalSeconds() {
        int snap = sqlPanelKeepAliveSecondsSnap;
        if (snap != Integer.MIN_VALUE) {
            return snap;
        }
        synchronized (SqlTabController.class) {
            if (sqlPanelKeepAliveSecondsSnap != Integer.MIN_VALUE) {
                return sqlPanelKeepAliveSecondsSnap;
            }
            sqlPanelKeepAliveSecondsSnap = readSqlPanelKeepAliveIntervalSecondsFromConfig();
            return sqlPanelKeepAliveSecondsSnap;
        }
    }

    private static int readSqlPanelKeepAliveIntervalSecondsFromConfig() {
        String configured = ConfigManagerUtil.getProperty(
                SQL_PANEL_KEEPALIVE_INTERVAL_CONFIG_KEY,
                String.valueOf(DEFAULT_SQL_PANEL_KEEPALIVE_INTERVAL_SECONDS)
        );
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SQL_PANEL_KEEPALIVE_INTERVAL_SECONDS;
        }
        try {
            int v = Integer.parseInt(configured.trim());
            return v <= 0 ? 0 : v;
        } catch (NumberFormatException e) {
            log.warn("Invalid {} value: {}", SQL_PANEL_KEEPALIVE_INTERVAL_CONFIG_KEY, configured);
            return DEFAULT_SQL_PANEL_KEEPALIVE_INTERVAL_SECONDS;
        }
    }

    private void checkSqlPanelKeepAlive() {
        if (sqlPanelKeepAliveChecking.get()) {
            return;
        }
        if (sqlConnect == null || sqlConnect == defaultConnect || sqlConnect.getConn() == null) {
            return;
        }
        if (sqlTask != null && sqlTask.isRunning()) {
            return;
        }
        if (sqlExecuteProcessStackPane != null && sqlExecuteProcessStackPane.isVisible()) {
            return;
        }
        if (!sqlPanelKeepAliveChecking.compareAndSet(false, true)) {
            return;
        }
        AppExecutor.runAsync(() -> {
            try {
                if (sqlConnect == null || sqlConnect == defaultConnect || sqlConnect.getConn() == null) {
                    return;
                }
                log.info("SQL panel keepalive test running: {}", sqlConnect.getName());
                boolean alive = connectionService.testConn(sqlConnect);
                if (!alive) {
                    log.warn("SQL panel keepalive test failed: {}", sqlConnect.getName());
                } else {
                    log.info("SQL panel keepalive test succeeded: {}", sqlConnect.getName());
                }
            } catch (Exception e) {
                log.warn("SQL panel keepalive check failed", e);
            } finally {
                sqlPanelKeepAliveChecking.set(false);
            }
        });
    }

    // ---- AI SQL actions (wired to CustomSqlEditCodeArea's aiMenu items) ----

    private Future<?> aiSqlFuture;
    private volatile boolean aiSqlCancelled;

    private void setupAiActions() {
        CustomSqlEditCodeArea area = sqlEditCodeArea;
        if (area == null || area.aiMenu == null) return;

        area.aiFormatSqlItem.setOnAction(e -> executeAiAction(
                "formatSql", I18n.t("sql.ai.menu.formatSql")));
        area.aiOptimizeSqlItem.setOnAction(e -> executeAiAction(
                "optimizeSql", I18n.t("sql.ai.menu.optimizeSql")));
        area.aiConvertOracleItem.setOnAction(e -> executeAiAction(
                "convertOracle", I18n.t("sql.ai.menu.convertOracle")));
        area.aiConvertMysqlItem.setOnAction(e -> executeAiAction(
                "convertMysql", I18n.t("sql.ai.menu.convertMysql")));
        area.aiConvertInformixItem.setOnAction(e -> executeAiAction(
                "convertInformix", I18n.t("sql.ai.menu.convertInformix")));
        area.aiConvertPostgresqlItem.setOnAction(e -> executeAiAction(
                "convertPostgresql", I18n.t("sql.ai.menu.convertPostgresql")));
    }

    private void executeAiAction(String actionKey, String actionLabel) {
        if (!AiAuthUtil.hasConfiguredApi()) {
            NotificationUtil.showMainNotification(I18n.t("sql.ai.noApiKey"));
            return;
        }

        String selectedText = sqlEditCodeArea.getSelectedText();
        if (selectedText == null || selectedText.isBlank()) {
            NotificationUtil.showMainNotification(I18n.t("sql.ai.noSelection"));
            return;
        }
        final String sqlText = selectedText;
        final String prompt = buildAiSqlPrompt(actionKey, sqlText);

        // Build standalone AI progress dialog
        Label statusLabel = new Label(actionLabel + " - " + I18n.t("sql.ai.thinking"));
        statusLabel.setWrapText(true);
        Label timeLabel = new Label("0.0 " + I18n.t("sql.exec.elapsed", "秒"));
        timeLabel.getStyleClass().add("ai-time-label");

        Button stopBtn = new Button(I18n.t("ai.button.stop", "停止"));
        stopBtn.getStyleClass().add("accent");
        stopBtn.setGraphic(IconFactory.groupFixedColor(IconPaths.AI_STOP, 0.5, IconFactory.stopColor()));

        HBox bottomRow = new HBox(8, stopBtn);
        bottomRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox content = new VBox(12, statusLabel, timeLabel, bottomRow);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        content.setPadding(new javafx.geometry.Insets(16));
        content.setPrefWidth(480);

        ButtonType cancelType = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                actionLabel, content, 500, javafx.scene.layout.Region.USE_COMPUTED_SIZE, cancelType);
        dialog.getStage().setOnCloseRequest(we -> {
            aiSqlCancelled = true;
            if (aiSqlFuture != null) aiSqlFuture.cancel(true);
        });

        aiSqlCancelled = false;

        // Timer
        final long[] startMs = {System.currentTimeMillis()};
        javafx.animation.Timeline timer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(200), te -> {
                    double secs = (System.currentTimeMillis() - startMs[0]) / 1000.0;
                    timeLabel.setText(String.format("%.1f 秒", secs));
                })
        );
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        timer.play();

        stopBtn.setOnAction(se -> {
            aiSqlCancelled = true;
            if (aiSqlFuture != null) aiSqlFuture.cancel(true);
            statusLabel.setText(I18n.t("sql.ai.aborted"));
            timer.stop();
            stopBtn.setDisable(true);
        });

        aiSqlFuture = AppExecutor.submit(() -> {
            try {
                String result = AiApiUtil.chat(prompt);
                Platform.runLater(() -> {
                    timer.stop();
                    if (aiSqlCancelled) {
                        return;
                    }
                    if (result == null || result.isEmpty()) {
                        statusLabel.setText(I18n.t("ai.notice.api_error"));
                        stopBtn.setText(I18n.t("common.confirm", "确定"));
                        stopBtn.setGraphic(null);
                        stopBtn.setOnAction(ce -> dialog.getStage().close());
                        return;
                    }
                    sqlEditCodeArea.replaceSelection(result);
                    statusLabel.setText(actionLabel + " " + I18n.t("sql.exec.success"));
                    stopBtn.setText(I18n.t("common.confirm", "确定"));
                    stopBtn.setGraphic(null);
                    stopBtn.setOnAction(ce -> dialog.getStage().close());
                });
            } catch (Exception ex) {
                log.error("AI SQL action failed", ex);
                Platform.runLater(() -> {
                    timer.stop();
                    statusLabel.setText(I18n.t("ai.notice.api_error"));
                    stopBtn.setText(I18n.t("common.confirm", "确定"));
                    stopBtn.setGraphic(null);
                    stopBtn.setOnAction(ce -> dialog.getStage().close());
                });
            }
        });

        dialog.showAndWait();
        timer.stop();
        aiSqlCancelled = true;
        if (aiSqlFuture != null && !aiSqlFuture.isDone()) {
            aiSqlFuture.cancel(true);
        }
    }

    private String buildAiSqlPrompt(String actionKey, String sqlText) {
        return switch (actionKey) {
            case "formatSql" -> "请将以下 SQL 语句格式化为规范、易读的格式。只返回格式化后的 SQL，不要包含任何解释或 Markdown 标记。\n\n" + sqlText;
            case "optimizeSql" -> "你是一个数据库专家。请优化以下 SQL 语句以提高性能。说明优化点，然后给出优化后的 SQL。\n\n" + sqlText;
            case "convertOracle" -> "你是一个数据库迁移专家。请将以下 SQL 语句转换为 Oracle 兼容语法。只返回转换后的 SQL，不要包含任何解释或 Markdown 标记。\n\n" + sqlText;
            case "convertMysql" -> "你是一个数据库迁移专家。请将以下 SQL 语句转换为 MySQL 兼容语法。只返回转换后的 SQL，不要包含任何解释或 Markdown 标记。\n\n" + sqlText;
            case "convertInformix" -> "你是一个数据库迁移专家。请将以下 SQL 语句转换为 Informix 兼容语法。只返回转换后的 SQL，不要包含任何解释或 Markdown 标记。\n\n" + sqlText;
            case "convertPostgresql" -> "你是一个数据库迁移专家。请将以下 SQL 语句转换为 PostgreSQL 兼容语法。只返回转换后的 SQL，不要包含任何解释或 Markdown 标记。\n\n" + sqlText;
            default -> sqlText;
        };
    }


}





