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
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    public Button resultSetInsertRowButton;
    @FXML
    public Button resultSetDeleteRowButton;
    @FXML
    public Button resultSetSaveEditsButton;
    @FXML
    public Button resultSetCancelEditsButton;

    /** True when PK/rowid allow editing (and connection not read-only). */
    public final BooleanProperty resultSetEditAllowed = new SimpleBooleanProperty(false);
    private SimpleStringProperty editSqlTransactionText;
    private ChoiceBox<?> editCommitMode;

    /** New rows not yet persisted with「保存」. */
    public final Set<ObservableList<String>> pendingInsertRows =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Existing rows with cell edits not yet saved. */
    public final Set<ObservableList<String>> pendingUpdateRows =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Rows marked for DELETE on「保存」. */
    public final Set<ObservableList<String>> pendingDeleteRows =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Snapshot before first edit (per row), for cancel / batch UPDATE. */
    public final Map<ObservableList<String>, ObservableList<String>> originalRowSnapshots = new IdentityHashMap<>();
    /** Existing rows: which data column indices (1-based) have unsaved edits; used for per-cell green highlight. */
    public final Map<ObservableList<String>, Set<Integer>> pendingUpdatedCells = new IdentityHashMap<>();
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
    private final List<Labeled> boundLabels = new ArrayList<>();
    private final List<Control> tooltipBoundControls = new ArrayList<>();
    private ChangeListener<Boolean> placeholderVisibilityListener;

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
        setupResultSetEditToolbar();
    }

    public void setEditCommitContext(SimpleStringProperty tx, ChoiceBox<?> commitMode) {
        editSqlTransactionText = tx;
        editCommitMode = commitMode;
    }

    public boolean getResultSetEditAllowed() {
        return resultSetEditAllowed.get();
    }

    private void bindUiState() {
        var toolbarDisabled = Bindings.createBooleanBinding(
                () -> !resultSetEditAllowed.get(),
                resultSetEditAllowed);
        resultSetInsertRowButton.disableProperty().bind(toolbarDisabled);
        resultSetDeleteRowButton.disableProperty().bind(toolbarDisabled);
        resultSetSaveEditsButton.disableProperty().bind(toolbarDisabled);
        resultSetCancelEditsButton.disableProperty().bind(toolbarDisabled);
        resultSetNextPageButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        resultSetAllRowsButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        lastSqlRefreshButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        resultSetExportButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
        resultSetCountButton.disableProperty().bind(sqlExecuteProcessStackPane.visibleProperty());
    }

    private void initI18nBindings() {
        bindTooltip(lastSqlCopyButton, "resultset.tooltip.copy_sql");
        bindTooltip(lastSqlRefreshButton, "resultset.tooltip.refresh_sql");
        bindTooltip(resultSetInsertRowButton, "resultset.tooltip.insert_row");
        bindTooltip(resultSetDeleteRowButton, "resultset.tooltip.delete_row");
        bindTooltip(resultSetSaveEditsButton, "resultset.tooltip.save_edits");
        bindTooltip(resultSetCancelEditsButton, "resultset.tooltip.cancel_edits");
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
        resultSetInsertRowButton.setGraphic(IconFactory.group(IconPaths.MAIN_ADD_CONNECT, 0.55));
        resultSetDeleteRowButton.setGraphic(IconFactory.group(IconPaths.TABLEINFO_DELETE_COLUMN, 0.55));
        resultSetSaveEditsButton.setGraphic(IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.55));
        resultSetCancelEditsButton.setGraphic(IconFactory.group(IconPaths.TAB_CLOSE_MENU_ITEM, 0.45));
        resultSetNextPageButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_NEXT_PAGE, 0.6));
        resultSetAllRowsButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_ALL_ROWS, 0.5));
        resultSetCountButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_COUNT, 0.5));
        resultSetExportButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EXPORT, 0.5));
    }

    private void bindText(Labeled labeled, String key) {
        boundLabels.add(labeled);
        labeled.textProperty().bind(I18n.bind(key));
    }

    private void bindTooltip(Control control, String key) {
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(I18n.bind(key));
        tooltip.setShowDelay(Duration.millis(100));
        control.setTooltip(tooltip);
        tooltipBoundControls.add(control);
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
        StackPane runningPlaceholder = new StackPane();
        runningPlaceholder.setMinSize(0, 0);
        runningPlaceholder.setPrefSize(0, 0);
        resultSetTableView.getStyleClass().add("resultset-table-view");
        resultSetTableView.setPlaceholder(sqlExecuteProcessStackPane.isVisible() ? runningPlaceholder : tableviewEmptyLabel);
        placeholderVisibilityListener = (obs, oldVal, running) ->
                resultSetTableView.setPlaceholder(running ? runningPlaceholder : tableviewEmptyLabel);
        sqlExecuteProcessStackPane.visibleProperty().addListener(placeholderVisibilityListener);
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
        resultSetTableView.setRowFactory(tv -> {
            TableRow<ObservableList<String>> row = new TableRow<>();
            row.itemProperty().addListener((obs, oldItem, newItem) -> {
                row.getStyleClass().removeAll(
                        "resultset-pending-dml-row",
                        "resultset-pending-delete-row");
                if (newItem == null) {
                    return;
                }
                if (pendingDeleteRows.contains(newItem)) {
                    row.getStyleClass().add("resultset-pending-delete-row");
                } else if (pendingInsertRows.contains(newItem)) {
                    row.getStyleClass().add("resultset-pending-dml-row");
                }
            });
            return row;
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

    private void setupResultSetEditToolbar() {
        resultSetInsertRowButton.setOnAction(e -> insertResultSetRow());
        resultSetDeleteRowButton.setOnAction(e -> deleteSelectedResultSetRows());
        resultSetSaveEditsButton.setOnAction(e -> saveAllPendingDml());
        resultSetCancelEditsButton.setOnAction(e -> cancelAllPendingDml());
    }

    private void stopResultSetCellEdit() {
        resultSetTableView.edit(-1, null);
    }

    private void insertResultSetRow() {
        if (!resultSetEditAllowed.get()) {
            return;
        }
        int n = colList.size();
        if (n <= 0) {
            return;
        }
        ObservableList<ObservableList<String>> items = resultSetTableView.getItems();
        int insertIndex;
        ObservableList<String> template;
        int anchor = minSelectedRowIndex();
        if (items.isEmpty()) {
            insertIndex = 0;
            template = null;
        } else if (anchor >= 0) {
            insertIndex = anchor + 1;
            template = (ObservableList<String>) items.get(anchor);
        } else {
            insertIndex = items.size();
            template = (ObservableList<String>) items.get(items.size() - 1);
        }
        ObservableList<String> newRow = template == null ? newEmptyResultRow(n) : copyResultRow(template);
        items.add(insertIndex, newRow);
        pendingInsertRows.add(newRow);
        resultSetTableView.getSelectionModel().clearSelection();
        resultSetTableView.getSelectionModel().select(insertIndex);
        resultSetTableView.refresh();
        boolean appendedAsLastRow = insertIndex == items.size() - 1;
        Platform.runLater(() -> {
            if (appendedAsLastRow) {
                resultSetTableView.scrollTo(insertIndex);
            }
            resultSetTableView.requestFocus();
        });
    }

    /**
     * Local cell edit only (no DB). Call before row data reflects new value for snapshot.
     */
    public void applyLocalCellEdit(int columnIndex, ObservableList<String> row, Object oldValue, String newCellText) {
        if (!resultSetEditAllowed.get()) {
            return;
        }
        String normalized = newCellText == null ? "" : newCellText.replaceAll("\u21B5", "\n");
        if (isSameLocalCellValue(oldValue, normalized)) {
            return;
        }
        if (pendingDeleteRows.contains(row)) {
            pendingDeleteRows.remove(row);
        }
        if (!pendingInsertRows.contains(row) && !originalRowSnapshots.containsKey(row)) {
            originalRowSnapshots.put(row, copyResultRow(row));
        }
        row.set(columnIndex, normalized);
        if (!pendingInsertRows.contains(row)) {
            pendingUpdateRows.add(row);
            pendingUpdatedCells.computeIfAbsent(row, k -> new HashSet<>()).add(columnIndex);
        }
        resultSetTableView.refresh();
    }

    /** True if committed text equals the value before this edit (no pending DML / highlight). */
    private static boolean isSameLocalCellValue(Object oldValue, String normalizedNew) {
        String n = normalizedNew == null ? "" : normalizedNew;
        if (oldValue == null) {
            return n.isEmpty();
        }
        String o = oldValue.toString().replaceAll("\u21B5", "\n");
        return o.equals(n);
    }

    /** Smallest selected row index, or -1 if nothing selected. */
    private int minSelectedRowIndex() {
        ObservableList<? extends TablePosition> cells = resultSetTableView.getSelectionModel().getSelectedCells();
        if (cells == null || cells.isEmpty()) {
            return -1;
        }
        int min = Integer.MAX_VALUE;
        for (TablePosition<?, ?> p : cells) {
            int r = p.getRow();
            if (r >= 0 && r < min) {
                min = r;
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private static ObservableList<String> copyResultRow(ObservableList<String> source) {
        ObservableList<String> copy = FXCollections.observableArrayList();
        copy.addAll(source);
        return copy;
    }

    private static ObservableList<String> newEmptyResultRow(int dataColumnCount) {
        ObservableList<String> row = FXCollections.observableArrayList();
        row.add(null);
        for (int i = 0; i < dataColumnCount; i++) {
            row.add("");
        }
        return row;
    }

    private void deleteSelectedResultSetRows() {
        if (!resultSetEditAllowed.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        ObservableList<TablePosition<ObservableList<String>, ?>> cells =
                (ObservableList<TablePosition<ObservableList<String>, ?>>)
                        (ObservableList<?>) resultSetTableView.getSelectionModel().getSelectedCells();
        if (cells.isEmpty()) {
            NotificationUtil.showMainNotification(I18n.t("resultset.edit.select_rows", "请先选中要删除的行。"));
            return;
        }
        TreeSet<Integer> rowNums = new TreeSet<>(Collections.reverseOrder());
        for (TablePosition<ObservableList<String>, ?> p : cells) {
            rowNums.add(p.getRow());
        }
        List<ObservableList<String>> snapshot = new ArrayList<>();
        for (int r : rowNums) {
            if (r >= 0 && r < resultSetTableView.getItems().size()) {
                ObservableList<String> row = (ObservableList<String>) resultSetTableView.getItems().get(r);
                snapshot.add(row);
            }
        }
        if (snapshot.isEmpty()) {
            return;
        }
        for (ObservableList<String> row : snapshot) {
            if (pendingInsertRows.contains(row)) {
                resultSetTableView.getItems().remove(row);
                pendingInsertRows.remove(row);
                continue;
            }
            pendingUpdatedCells.remove(row);
            pendingDeleteRows.add(row);
        }
        resultSetTableView.refresh();
    }

    private boolean hasPendingDml() {
        return !pendingInsertRows.isEmpty() || !pendingUpdateRows.isEmpty() || !pendingDeleteRows.isEmpty();
    }

    private void clearAllPendingDmlState() {
        pendingInsertRows.clear();
        pendingUpdateRows.clear();
        pendingDeleteRows.clear();
        originalRowSnapshots.clear();
        pendingUpdatedCells.clear();
        resultSetTableView.refresh();
    }

    /** Whether this data cell should show the pending-update (success green, same as INSERT cells) highlight. */
    public boolean isPendingUpdatedDataCell(ObservableList<String> row, int columnIndex) {
        if (row == null || pendingDeleteRows.contains(row) || pendingInsertRows.contains(row)) {
            return false;
        }
        Set<Integer> cols = pendingUpdatedCells.get(row);
        return cols != null && cols.contains(columnIndex);
    }

    private void saveAllPendingDml() {
        stopResultSetCellEdit();
        if (!hasPendingDml()) {
            NotificationUtil.showMainNotification(I18n.t("resultset.edit.nothing_to_save", "没有待保存的更改。"));
            return;
        }
        SimpleStringProperty txForSave =
                editSqlTransactionText != null ? editSqlTransactionText : new SimpleStringProperty("");
        List<ObservableList<String>> deletes = new ArrayList<>(pendingDeleteRows);
        List<ObservableList<String>> updates = new ArrayList<>(pendingUpdateRows);
        List<ObservableList<String>> inserts = new ArrayList<>(pendingInsertRows);
        Map<ObservableList<String>, ObservableList<String>> snapCopy = new IdentityHashMap<>();
        for (ObservableList<String> r : updates) {
            ObservableList<String> s = originalRowSnapshots.get(r);
            if (s != null) {
                snapCopy.put(r, copyResultRow(s));
            }
        }
        Set<ObservableList<String>> deleteIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        deleteIdentity.addAll(deletes);

        AppExecutor.runAsync(() -> {
            try {
                for (ObservableList<String> row : deletes) {
                    editHelper.deleteRow(row, txForSave, editCommitMode);
                }
                for (ObservableList<String> row : updates) {
                    if (deleteIdentity.contains(row)) {
                        continue;
                    }
                    ObservableList<String> snap = snapCopy.get(row);
                    if (snap == null) {
                        continue;
                    }
                    editHelper.flushRowUpdates(row, snap, txForSave, editCommitMode);
                }
                for (ObservableList<String> row : inserts) {
                    if (deleteIdentity.contains(row)) {
                        continue;
                    }
                    editHelper.insertRow(row, txForSave, editCommitMode);
                }
                Platform.runLater(() -> {
                    for (ObservableList<String> row : deletes) {
                        resultSetTableView.getItems().remove(row);
                    }
                    clearAllPendingDmlState();
                });
            } catch (SQLException ex) {
                log.error(ex.getMessage(), ex);
                Platform.runLater(() -> {
                    if (SqlErrorUtil.isDisconnectError(sqlConnect, ex)) {
                        hiddenDisconnectedButton.fire();
                    } else {
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"),
                                "[" + ex.getErrorCode() + "]" + ex.getMessage());
                    }
                });
            }
        });
    }

    private void cancelAllPendingDml() {
        stopResultSetCellEdit();
        List<Map.Entry<ObservableList<String>, ObservableList<String>>> snapEntries =
                new ArrayList<>(originalRowSnapshots.entrySet());
        for (Map.Entry<ObservableList<String>, ObservableList<String>> e : snapEntries) {
            ObservableList<String> row = e.getKey();
            ObservableList<String> snap = e.getValue();
            if (!resultSetTableView.getItems().contains(row) || snap == null) {
                continue;
            }
            for (int i = 0; i < row.size() && i < snap.size(); i++) {
                row.set(i, snap.get(i));
            }
        }
        List<ObservableList<String>> insertCopy = new ArrayList<>(pendingInsertRows);
        for (ObservableList<String> row : insertCopy) {
            resultSetTableView.getItems().remove(row);
        }
        clearAllPendingDmlState();
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
            setEditCommitContext(null, null);
            resultSetEditAllowed.set(false);
            pendingInsertRows.clear();
            pendingUpdateRows.clear();
            pendingDeleteRows.clear();
            originalRowSnapshots.clear();
            pendingUpdatedCells.clear();
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
                    Platform.runLater(() -> {
                        resultSetTableView.setEditable(false);
                        resultSetEditAllowed.set(false);
                    });
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

    public void dispose() {
        Runnable cleanup = () -> {
            cancel();

            resultSetInsertRowButton.disableProperty().unbind();
            resultSetDeleteRowButton.disableProperty().unbind();
            resultSetSaveEditsButton.disableProperty().unbind();
            resultSetCancelEditsButton.disableProperty().unbind();
            resultSetNextPageButton.disableProperty().unbind();
            resultSetAllRowsButton.disableProperty().unbind();
            lastSqlRefreshButton.disableProperty().unbind();
            resultSetExportButton.disableProperty().unbind();
            resultSetCountButton.disableProperty().unbind();

            resultSetTableView.prefWidthProperty().unbind();
            resultSetTableView.prefHeightProperty().unbind();

            if (placeholderVisibilityListener != null) {
                sqlExecuteProcessStackPane.visibleProperty().removeListener(placeholderVisibilityListener);
                placeholderVisibilityListener = null;
            }

            for (Labeled labeled : boundLabels) {
                labeled.textProperty().unbind();
            }
            boundLabels.clear();

            for (Control control : tooltipBoundControls) {
                Tooltip tooltip = control.getTooltip();
                if (tooltip != null) {
                    tooltip.textProperty().unbind();
                }
            }
            tooltipBoundControls.clear();

            resultSetTableView.getItems().clear();
            resultSetTableView.getColumns().clear();
            resultSetTableView.setPlaceholder(null);
            sqlResultSetList.clear();
            colList.clear();
            resultTablePriNum.clear();
            resultTableCols.clear();
            sqlParamList.clear();
            pendingInsertRows.clear();
            pendingUpdateRows.clear();
            pendingDeleteRows.clear();
            originalRowSnapshots.clear();
            pendingUpdatedCells.clear();
        };
        if (Platform.isFxApplicationThread()) {
            cleanup.run();
        } else {
            Platform.runLater(cleanup);
        }
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
