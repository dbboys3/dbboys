package com.dbboys.ctrl;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.customnode.CustomLabelTextField;
import com.dbboys.customnode.CustomTableView;
import com.dbboys.customnode.CustomUserTextField;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.SqlErrorUtil;
import com.dbboys.util.*;
import com.dbboys.vo.Connect;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ResultSetTabController {
    private static final Logger log = LogManager.getLogger(ResultSetTabController.class);

    @FXML
    public CustomLabelTextField lastSqlTextField;
    @FXML
    public Button lastSqlRefreshButton;
    @FXML
    public VBox resultSetVBox;
    @FXML
    public CustomTableView resultSetTableView;
    @FXML
    public HBox resultSetButtonHBox;
    @FXML
    public Label resultSetEditableEnabledLabel;
    @FXML
    public Label resultSetEditableDisabledLabel;
    @FXML
    public Label resultSetLabelLeftBracket;
    @FXML
    public Label resultSetLabelRightBracket;
    @FXML
    public Label resultSetLabelPerTimePrefix;
    @FXML
    public Label resultSetLabelBetweenNext;
    @FXML
    public Label resultSetLabelBetweenAll;
    @FXML
    public Label resultSetLabelFetchedPrefix;
    @FXML
    public Label resultSetLabelFetchedSuffix;
    @FXML
    public Label resultSetLabelTotalPrefix;
    @FXML
    public Label resultSetLabelTotalSuffix;
    @FXML
    public Label resultSetLabelTimeSuffix;
    @FXML
    public Label resultSetLabelEnd;
    @FXML
    public CustomUserTextField resultSetPerTimeTextField;
    @FXML
    public Button resultSetNextPageButton;
    @FXML
    public Button resultSetAllRowsButton;
    @FXML
    public Button resultSetCountButton;
    @FXML
    public Label resultSetFetchedRowsLabel;
    @FXML
    public Label resultSetTotalRowsLabel;
    @FXML
    public Label sqlUsedTimeLabel;
    @FXML
    public Button resultSetExportButton;
    public Button hiddenDisconnectedButton = new Button();
    public StackPane sqlExecuteProcessStackPane;
    public Connect sqlConnect;
    public ResultSet sqlResultSet;
    public long sqlFetchedTime = 0;
    public long sqlFetchStartTime = 0;
    public long sqlFetchEndTime = 0;
    public Integer sqlFetchedRows = 0;
    public List<ObservableList<String>> sqlResultSetList = new ArrayList<>();
    public PreparedStatement sqlStatement;
    public ParameterMetaData parameterMetaData;
    public CallableStatement sqlCstmt;
    public Boolean callHasResultSet = false;
    public List<TableColumn<ObservableList<String>, Object>> colList = new ArrayList<>();
    public Label sqlExecuteProcessLabel;
    public Task<Void> sqlTask = new Task<Void>() {
        @Override
        protected Void call() throws Exception {
            return null;
        }
    };
//获取主键相关变量
    public List<Integer> resultTablePriNum = new ArrayList<>();
    public String resultFromTable;
    public List<String> resultTableCols = new ArrayList<>();
    public ResultSet priSqlResult;
    public List<Object> sqlParamList = new ArrayList<>();
    public ResultSetMetaData sqlMetaData;
    public Runnable eventEnd = () -> Platform.runLater(this::finishFetch);
    //最后一次执行的sql
    @FXML
    private Button lastSqlCopyButton;
    private String sqlResultCount;

    ResultSetColumnBuilder columnBuilder;
    ResultSetFetchHelper fetchHelper;
    ResultSetEditHelper editHelper;

    public ResultSetTabController(Connect sqlConnect, StackPane sqlExecuteProcessStackPane) {
        this.sqlExecuteProcessStackPane = sqlExecuteProcessStackPane;
        this.sqlConnect = sqlConnect;
    }

    public void initialize() {
        columnBuilder = new ResultSetColumnBuilder(this);
        fetchHelper = new ResultSetFetchHelper(this);
        editHelper = new ResultSetEditHelper(this);

        initI18nBindings();
        setupIcons();
        bindUiState();
        initExecuteProcessLabel();
        setupLastSql();
        setupTableView();
        setupPerTimeField();
        setupButtons();
    }

    private void bindUiState() {
        resultSetEditableDisabledLabel.visibleProperty().bind(resultSetEditableEnabledLabel.visibleProperty().not());
        resultSetNextPageButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        resultSetAllRowsButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        lastSqlRefreshButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        resultSetExportButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        resultSetCountButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
    }

    private void initI18nBindings() {
        bindTooltip(lastSqlCopyButton, "resultset.tooltip.copy_sql");
        bindTooltip(lastSqlRefreshButton, "resultset.tooltip.refresh_sql");
        bindTooltip(resultSetEditableEnabledLabel, "resultset.tooltip.editable_enabled");
        bindTooltip(resultSetEditableDisabledLabel, "resultset.tooltip.editable_disabled");
        bindTooltip(resultSetNextPageButton, "resultset.tooltip.next_page");
        bindTooltip(resultSetAllRowsButton, "resultset.tooltip.all_rows");
        bindTooltip(resultSetCountButton, "resultset.tooltip.count_rows");
        bindTooltip(resultSetExportButton, "resultset.tooltip.export_loaded");

        bindText(resultSetLabelLeftBracket, "resultset.label.left_bracket");
        bindText(resultSetLabelRightBracket, "resultset.label.right_bracket");
        bindText(resultSetLabelPerTimePrefix, "resultset.label.per_time_prefix");
        bindText(resultSetLabelBetweenNext, "resultset.label.between_next");
        bindText(resultSetLabelBetweenAll, "resultset.label.between_all");
        bindText(resultSetLabelFetchedPrefix, "resultset.label.fetched_prefix");
        bindText(resultSetLabelFetchedSuffix, "resultset.label.fetched_suffix");
        bindText(resultSetLabelTotalPrefix, "resultset.label.total_prefix");
        bindText(resultSetLabelTotalSuffix, "resultset.label.total_suffix");
        bindText(resultSetLabelTimeSuffix, "resultset.label.time_suffix");
        bindText(resultSetLabelEnd, "resultset.label.end");

        ensureLastSqlTooltip();
        if (lastSqlTextField.getText() == null || lastSqlTextField.getText().isBlank()) {
            lastSqlTextField.setText(I18n.t("resultset.sample.sql"));
        }
        if (lastSqlTextField.getTooltip().getText() == null || lastSqlTextField.getTooltip().getText().isBlank()) {
            lastSqlTextField.getTooltip().setText(I18n.t("resultset.sample.sql"));
        }
    }

    private void setupIcons() {
        lastSqlCopyButton.setGraphic(IconFactory.group(IconPaths.COPY, 0.6));
        lastSqlRefreshButton.setGraphic(IconFactory.group(IconPaths.MAIN_REBUILD, 0.6));
        resultSetEditableEnabledLabel.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.6));
        resultSetEditableDisabledLabel.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE_DISABLED, 0.45, Color.valueOf("#9f453c")));
        resultSetNextPageButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_NEXT_PAGE, 0.6));
        resultSetAllRowsButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_ALL_ROWS, 0.5));
        resultSetCountButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_COUNT, 0.5));
        resultSetExportButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EXPORT, 0.5));
    }

    private void bindText(Labeled labeled, String key) {
        labeled.textProperty().bind(I18n.bind(key));
    }

    private void bindTooltip(Control control, String key) {
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(I18n.bind(key));
        tooltip.setShowDelay(Duration.millis(100));
        control.setTooltip(tooltip);
    }

    private void ensureLastSqlTooltip() {
        Tooltip tooltip = lastSqlTextField.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip();
            lastSqlTextField.setTooltip(tooltip);
        }
        tooltip.setShowDelay(Duration.millis(100));
    }

    private void initExecuteProcessLabel() {
        Object labelRef = sqlExecuteProcessStackPane.getProperties().get(SqlTabController.SQL_EXECUTE_PROCESS_TASK_LABEL_KEY);
        if (labelRef instanceof Label label) {
            sqlExecuteProcessLabel = label;
            return;
        }
        throw new IllegalStateException("Missing sql execute process task label reference.");
    }

    private void setupLastSql() {
        lastSqlTextField.getTooltip().setShowDelay(Duration.millis(100));
        lastSqlCopyButton.setOnAction(event -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(lastSqlTextField.getTooltip().getText());
            clipboard.setContent(content);
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.copied", "已复制！"));
        });
        lastSqlRefreshButton.setOnAction(event -> {
        });
    }

    private void setupTableView() {
        Label tableviewEmptyLabel = new Label();
        tableviewEmptyLabel.textProperty().bind(I18n.bind("resultset.placeholder.empty"));
        resultSetTableView.getStyleClass().add("resultset-table-view");
        resultSetTableView.setPlaceholder(tableviewEmptyLabel);
        resultSetTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        resultSetTableView.getSelectionModel().setCellSelectionEnabled(true);
        resultSetTableView.prefWidthProperty().bind(resultSetVBox.widthProperty());
        resultSetTableView.prefHeightProperty().bind(resultSetVBox.heightProperty());
        resultSetTableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        resultSetTableView.setSortPolicy(tv -> {
            TableView.DEFAULT_SORT_POLICY.call((TableView<?>) tv);
            Platform.runLater(() -> resultSetTableView.getSelectionModel().clearSelection());
            return true;
        });
    }

    private void setupPerTimeField() {
        resultSetPerTimeTextField.setText(ConfigManagerUtil.getProperty("RESULT_FETCH_PER_TIME", "200"));
        resultSetPerTimeTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*")) {
                resultSetPerTimeTextField.setText(newValue.replaceAll("[^\\d]", ""));
            }
        });
    }

    private void setupButtons() {
        resultSetNextPageButton.setOnAction(event -> {
            sqlTask = createGetNextPageResultSetTask();
            AppExecutor.runTask(sqlTask);
        });
        resultSetAllRowsButton.setOnAction(event -> {
            sqlTask = createGetAllResultSetTask();
            AppExecutor.runTask(sqlTask);
        });
        resultSetCountButton.setOnAction(event -> {
            sqlTask = createGetResultSetCountTask();
            AppExecutor.runTask(sqlTask);
        });
        resultSetExportButton.setOnAction(event -> {
            String sqlText = lastSqlTextField.getText();
            if (resultSetTableView.getItems().isEmpty()) {
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                        I18n.t("resultset.export.empty_sql", "没有可以导出的结果集！"));
                return;
            }
            String exportSql = sqlText.trim();
            if (exportSql.endsWith(";")) exportSql = exportSql.substring(0, exportSql.length() - 1);

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("resultset.export.title", "结果集导出"));
            String defaultName = I18n.t("resultset.export.filename_prefix", "结果集导出")
                    + "_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                    + ".csv";
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            fileChooser.setInitialFileName(defaultName);
            File file = fileChooser.showSaveDialog(AppState.getWindow());
            if (file == null) return;
            if (file.exists()) file.delete();

            final String finalExportSql = exportSql;

            // 复用 DownloadManagerUtil 统一的 SQL 导出入口，在内部获取连接并流式写出
            DownloadManagerUtil.addSqlExportTask(sqlConnect, finalExportSql, file, "csv", true);
        });
    }

    private void finishFetch() {
        sqlExecuteProcessStackPane.setVisible(false);
        sqlFetchEndTime = System.currentTimeMillis();
        sqlFetchedTime += sqlFetchEndTime - sqlFetchStartTime;
        resultSetTableView.getItems().addAll(sqlResultSetList);
        if (sqlFetchedRows > 0) {
            int current = Integer.parseInt(resultSetFetchedRowsLabel.getText());
            resultSetFetchedRowsLabel.setText(String.valueOf(current + sqlFetchedRows));
            sqlUsedTimeLabel.setText(String.valueOf(sqlFetchedTime / 1000.0));
        }
        sqlResultSetList.clear();
    }

    public void init() {
        callHasResultSet = false;
        closeResultSet();
        sqlFetchedTime = 0;
        sqlFetchedRows = 0;
        Platform.runLater(() -> {
            resultSetEditableEnabledLabel.setVisible(false);
            resultSetTableView.setEditable(false);
            resultSetTableView.getColumns().setAll(resultSetTableView.getColumns().get(0));
            resultSetTableView.getItems().clear();
            resultSetFetchedRowsLabel.setText("0");
            resultSetTotalRowsLabel.setText("?");
            sqlUsedTimeLabel.setText("0");
        });

    }

    public Task<Void> createGetNextPageResultSetTask() {
        return fetchHelper.createFetchTask(false);
    }

    public Task<Void> createGetAllResultSetTask() {
        return fetchHelper.createFetchTask(true);
    }


    public Task<Void> createGetResultSetCountTask() {
        //sql_is_count=true;
        sqlExecuteProcessStackPane.setVisible(true);
        fetchHelper.setFetchStatus(" " + I18n.t("sql.result.fetching_total"));
        String sqlCount = "select count(*) from (" + lastSqlTextField.getTooltip().getText().replaceFirst(";\\s*$", "") + ")";
        sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ResultSet rs = null;
                try {
                    sqlStatement = sqlConnect.getConn().prepareStatement(sqlCount);
                    rs = sqlStatement.executeQuery();
                    rs.next();
                    sqlResultCount = String.valueOf(rs.getInt(1));
                    rs.close();
                } catch (SQLException e) {
                    log.error(e.getMessage(), e);
                    if (SqlErrorUtil.isDisconnectError(sqlConnect, e)) {
                        hiddenDisconnectedButton.fire();
                    } else {
                        Platform.runLater(() ->
                            AlertUtil.CustomAlert("错误", "[" + e.getErrorCode() + "]" + e.getMessage()));
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
                return null;
            }
        };

        sqlTask.setOnSucceeded(event1 -> {
            sqlExecuteProcessStackPane.setVisible(false);
            resultSetTotalRowsLabel.setText(sqlResultCount);
        });
        sqlTask.setOnCancelled(event1 -> sqlExecuteProcessStackPane.setVisible(false));
        sqlTask.setOnFailed(event1 -> sqlExecuteProcessStackPane.setVisible(false));

        return sqlTask;
    }


    public long executeSelect(String sqlExe, Task<?> sqlTask, Boolean showFething, SimpleStringProperty sqlTransactionText, ChoiceBox<?> commitmode) throws SQLException {
        init();
        sqlFetchedRows = 0;
        final boolean showFetching = Boolean.TRUE.equals(showFething);
        sqlStatement = sqlConnect.getConn().prepareStatement(sqlExe);
        parameterMetaData = sqlStatement.getParameterMetaData();

        if (!editHelper.prepareParams(parameterMetaData, sqlStatement)) {
            return 0;
        }

        long sqlStartTime = System.currentTimeMillis();
        bindParams(sqlStatement);

        sqlStatement.setFetchSize(200);
        try {
            sqlResultSet = sqlStatement.executeQuery();
        } catch (SQLException e) {
            if (sqlTask != null && sqlTask.isCancelled()) {
                return 0;
            }
            throw e;
        }
        long sqlEndTime = System.currentTimeMillis();
        sqlFetchedTime = sqlEndTime - sqlStartTime;
        sqlMetaData = sqlResultSet.getMetaData();
        int columnCount = sqlMetaData.getColumnCount();
        columnBuilder.buildColumns(sqlMetaData, showFetching, sqlTransactionText, commitmode);
        sqlStartTime = System.currentTimeMillis();
        int perpage = fetchHelper.getPerPageLimit();
        if (showFetching) {
            fetchHelper.setFetchStatus(" " + I18n.t("sql.result.fetching"));
        }

        List<ObservableList<String>> fetchedRows = ResultSetFetchHelper.fetchRows(
                sqlResultSet,
                perpage,
                sqlTask, columnCount,
                showFetching ? fetched -> fetchHelper.setFetchStatus(fetchHelper.formatFetchedRows(fetched)) : null
        );
        sqlResultSetList.addAll(fetchedRows);
        sqlFetchedRows += fetchedRows.size();

        fetchHelper.applyFetchedRows(sqlExe);

        sqlEndTime = System.currentTimeMillis();
        sqlFetchedTime += sqlEndTime - sqlStartTime;
        //sqlStatement.close();


        return sqlFetchedTime;


    }


    public long executeCall(String sqlExe, Task<?> sqlTask, Boolean showFething) throws SQLException {
        init();
        sqlFetchedRows = 0;
        final boolean showFetching = Boolean.TRUE.equals(showFething);
        sqlCstmt = sqlConnect.getConn().prepareCall(sqlExe);

        parameterMetaData = sqlCstmt.getParameterMetaData();
        long sqlStartTime = System.currentTimeMillis();
        if (!editHelper.prepareParams(parameterMetaData, sqlCstmt)) {
            return 0;
        }
        sqlStartTime = System.currentTimeMillis();
        bindParams(sqlCstmt);

        try {
            callHasResultSet = sqlCstmt.execute();
        } catch (SQLException e) {
            if (sqlTask != null && sqlTask.isCancelled()) {
                return 0;
            }
            throw e;
        }

        if (callHasResultSet) {
            sqlResultSet = sqlCstmt.getResultSet();
            long sqlEndTime = System.currentTimeMillis();
            sqlFetchedTime = sqlEndTime - sqlStartTime;
            sqlMetaData = sqlResultSet.getMetaData();
            int columnCount = sqlMetaData.getColumnCount();
            columnBuilder.buildColumns(sqlMetaData, false, null, null);

            sqlStartTime = System.currentTimeMillis();
            int perpage = fetchHelper.getPerPageLimit();
            if (showFetching) {
                fetchHelper.setFetchStatus(" " + I18n.t("sql.result.fetching"));
            }

            List<ObservableList<String>> fetchedRows = ResultSetFetchHelper.fetchRows(
                    sqlResultSet,
                    perpage,
                    sqlTask,
                    columnCount,
                    showFetching ? fetched -> fetchHelper.setFetchStatus(fetchHelper.formatFetchedRows(fetched)) : null
            );
            sqlResultSetList.addAll(fetchedRows);
            sqlFetchedRows += fetchedRows.size();
            Platform.runLater(() -> resultSetButtonHBox.getChildren().remove(resultSetCountButton));
            fetchHelper.applyFetchedRows(sqlExe);

            sqlEndTime = System.currentTimeMillis();
            sqlFetchedTime += sqlEndTime - sqlStartTime;
        }
        return sqlFetchedTime;
    }


    public void getPrimaryKeys(String sqlExe) {
        resultTablePriNum.clear();
        resultFromTable = SqlParserUtil.getFromTable(sqlExe);
        sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (resultFromTable == null || resultFromTable.isBlank()) {
                    Platform.runLater(() -> resultSetTableView.setEditable(false));
                    return null;
                }

                try {
                    ResultSetEditHelper.PrimaryKeyInfo info = editHelper.fetchPrimaryKeyInfo(sqlExe);
                    Platform.runLater(() -> editHelper.applyPrimaryKeyInfo(info));
                } catch (SQLException | UnsupportedOperationException e) {
                    // ignore primary key failures
                }
                return null;
            }
        };

        AppExecutor.runTask(sqlTask);
    }

    public void closeResultSet() {
        closeQuietly(sqlResultSet, "result set");
        sqlResultSet = null;

        closeQuietly(priSqlResult, "primary-key result set");
        priSqlResult = null;

        closeQuietly(sqlStatement, "statement");
        sqlStatement = null;
        parameterMetaData = null;

        closeQuietly(sqlCstmt, "callable statement");
        sqlCstmt = null;
        callHasResultSet = false;
    }

    public void cancel() {
        if (sqlTask != null && sqlTask.isRunning()) {
            sqlTask.cancel();

        }
        if (sqlStatement != null) {
            try {
                sqlStatement.cancel();
            } catch (SQLException e) {
                log.debug("Cancel statement failed", e);
            }
        }
        if (sqlCstmt != null) {
            try {
                sqlCstmt.cancel();
            } catch (SQLException e) {
                log.debug("Cancel callable statement failed", e);
            }
        }
        closeResultSet();

    }

    private void closeQuietly(AutoCloseable resource, String resourceName) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            log.debug("Close {} failed", resourceName, e);
        }
    }


    private void bindParams(PreparedStatement stmt) throws SQLException {
        if (sqlParamList == null || sqlParamList.isEmpty()) {
            return;
        }
        for (int z = 1; z <= sqlParamList.size(); z++) {
            stmt.setObject(z, (sqlParamList.get(z - 1)));
        }
    }

    private void bindParams(CallableStatement stmt) throws SQLException {
        if (sqlParamList == null || sqlParamList.isEmpty()) {
            return;
        }
        for (int z = 1; z <= sqlParamList.size(); z++) {
            stmt.setObject(z, (sqlParamList.get(z - 1)));
        }
    }

    String getNullLabel() {
        return I18n.t("customtablecell.null", "[NULL]");
    }
}
