package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.ui.component.CustomLabelTextField;
import com.dbboys.ui.component.CustomTableView;
import com.dbboys.ui.component.CustomUserTextField;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.SqlErrorUtil;
import com.dbboys.infra.util.*;
import com.dbboys.infra.config.ConfigManagerUtil;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.model.Connect;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.input.ScrollEvent;
import javafx.fxml.FXML;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
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
    public Button resultSetEditHelpButton;
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
    public Label resultSetLabelTimePrefix;
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
        bindTooltip(resultSetEditHelpButton, "resultset.tooltip.edit_help");
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
        bindText(resultSetLabelTimePrefix, "resultset.label.time_prefix");
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
        resultSetEditHelpButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDIT_HELP, 0.48));
        resultSetInsertRowButton.setGraphic(semanticIcon(IconPaths.CREATE_CONNECT_ADD_DRIVER, 0.55, "icon-success"));
        resultSetDeleteRowButton.setGraphic(semanticIcon(IconPaths.CREATE_CONNECT_REMOVE_DRIVER, 0.47, "icon-resultset-delete"));
        resultSetSaveEditsButton.setGraphic(IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.45));
        resultSetCancelEditsButton.setGraphic(semanticIcon(IconPaths.RESULTSET_CANCEL_EDITS, 0.7, "icon-danger"));
        resultSetNextPageButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_NEXT_PAGE, 0.6));
        resultSetAllRowsButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_ALL_ROWS, 0.5));
        resultSetCountButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_COUNT, 0.5));
        resultSetExportButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EXPORT, 0.5));
    }

    private Group semanticIcon(String path, double scale, String styleClass) {
        SVGPath icon = IconFactory.create(path, scale);
        icon.getStyleClass().remove("icon-button-default");
        icon.getStyleClass().add(styleClass);
        return new Group(icon);
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
        // 必须在 updateItem 里同步行样式：虚拟化复用 TableRow 时仅依赖 itemProperty 可能残留插入/删除着色
        resultSetTableView.setRowFactory(tv -> new TableRow<ObservableList<String>>() {
            @Override
            protected void updateItem(ObservableList<String> item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll(
                        "resultset-pending-dml-row",
                        "resultset-pending-delete-row");
                if (empty || item == null) {
                    return;
                }
                if (pendingDeleteRows.contains(item)) {
                    getStyleClass().add("resultset-pending-delete-row");
                } else if (pendingInsertRows.contains(item)) {
                    getStyleClass().add("resultset-pending-dml-row");
                }
            }
        });


        resultSetTableView.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (resultSetTableView.getItems().size() <= 5 && e.getDeltaY() != 0) e.consume();
        });

        setupResultSetContextMenu();
    }

    private void setupResultSetContextMenu() {
        resultSetTableView.generateInsertSqlMenuItem.setOnAction(e -> generateInsertSql());
        resultSetTableView.generateUpdateSqlMenuItem.setOnAction(e -> generateUpdateSql());
        resultSetTableView.generateDeleteSqlMenuItem.setOnAction(e -> generateDeleteSql());
        resultSetTableView.generateSelectSqlMenuItem.setOnAction(e -> generateSelectSql());

        // 覆盖单元格级 ContextMenu（CustomTableCell 有独立的复制菜单），强制弹出 TableView 级菜单
        resultSetTableView.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            e.consume();
            javafx.application.Platform.runLater(() -> {
                ContextMenu menu = resultSetTableView.getContextMenu();
                if (menu != null) {
                    menu.show(resultSetTableView, e.getScreenX(), e.getScreenY());
                }
            });
        });
        resultSetTableView.getContextMenu().setOnShowing(e -> {
            boolean hasSelection = !resultSetTableView.getSelectionModel().getSelectedCells().isEmpty();
            resultSetTableView.copyMenuItem.setDisable(!hasSelection);

            boolean editable = resultSetEditAllowed.get();
            resultSetTableView.generateInsertSqlMenuItem.setDisable(!hasSelection);
            resultSetTableView.generateUpdateSqlMenuItem.setDisable(!hasSelection || !editable);
            resultSetTableView.generateDeleteSqlMenuItem.setDisable(!hasSelection || !editable);
            resultSetTableView.generateSelectSqlMenuItem.setDisable(!hasSelection || !editable);
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
            File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
            if (desktopDir.exists()) fileChooser.setInitialDirectory(desktopDir);
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
        boolean hasValidAnchor = anchor >= 0 && anchor < items.size();
        if (items.isEmpty()) {
            insertIndex = 0;
            template = null;
        } else if (hasValidAnchor) {
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
        if (!resultSetEditAllowed.get() || row == null) {
            return;
        }
        String normalized = normalizeEditedCellValue(newCellText);
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
        if (oldValue == null) {
            return normalizedNew == null || normalizedNew.isEmpty();
        }
        String o = oldValue.toString().replaceAll("\u21B5", "\n");
        return o.equals(normalizedNew);
    }

    private String normalizeEditedCellValue(String newCellText) {
        if (newCellText == null) {
            return "";
        }
        String value = newCellText.replaceAll("\u21B5", "\n");
        return getNullLabel().equals(value) ? null : value;
    }

    /** Smallest selected row index, or -1 if nothing selected. */
    private int minSelectedRowIndex() {
        ObservableList<? extends TablePosition> cells = resultSetTableView.getSelectionModel().getSelectedCells();
        int itemCount = resultSetTableView.getItems() == null ? 0 : resultSetTableView.getItems().size();
        if (cells == null || cells.isEmpty()) {
            return -1;
        }
        int min = Integer.MAX_VALUE;
        for (TablePosition<?, ?> p : cells) {
            int r = p.getRow();
            if (r >= 0 && r < itemCount && r < min) {
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
        int firstDeletedIndex = rowNums.isEmpty() ? -1 : rowNums.last();
        boolean physicallyRemoved = false;
        for (ObservableList<String> row : snapshot) {
            if (pendingInsertRows.contains(row)) {
                physicallyRemoved |= removeResultSetRowByIdentity(row);
                pendingInsertRows.remove(row);
                continue;
            }
            pendingUpdatedCells.remove(row);
            pendingDeleteRows.add(row);
        }
        if (physicallyRemoved) {
            selectPreviousRowAfterDelete(firstDeletedIndex);
        }
        resultSetTableView.refresh();
    }

    private void selectPreviousRowAfterDelete(int deletedRowIndex) {
        ObservableList<ObservableList<String>> items = resultSetTableView.getItems();
        resultSetTableView.getSelectionModel().clearSelection();
        if (items == null || items.isEmpty()) {
            return;
        }
        int targetIndex = deletedRowIndex <= 0 ? 0 : Math.min(deletedRowIndex - 1, items.size() - 1);
        resultSetTableView.getSelectionModel().select(targetIndex);
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
                        removeResultSetRowByIdentity(row);
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
            if (!containsResultSetRowByIdentity(row) || snap == null) {
                continue;
            }
            for (int i = 0; i < row.size() && i < snap.size(); i++) {
                row.set(i, snap.get(i));
            }
        }
        List<ObservableList<String>> insertCopy = new ArrayList<>(pendingInsertRows);
        for (ObservableList<String> row : insertCopy) {
            removeResultSetRowByIdentity(row);
        }
        clearAllPendingDmlState();
    }

    private boolean removeResultSetRowByIdentity(ObservableList<String> targetRow) {
        if (targetRow == null) {
            return false;
        }
        ObservableList<ObservableList<String>> items = resultSetTableView.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == targetRow) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    private boolean containsResultSetRowByIdentity(ObservableList<String> targetRow) {
        if (targetRow == null) {
            return false;
        }
        ObservableList<ObservableList<String>> items = resultSetTableView.getItems();
        for (ObservableList<String> row : items) {
            if (row == targetRow) {
                return true;
            }
        }
        return false;
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

    // ==================== 右键菜单 SQL 生成 ====================

    private void generateInsertSql() {
        String sql = buildInsertSql();
        closeSqlGenerationMenu();
        if (sql != null && !sql.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(sql);
            Clipboard.getSystemClipboard().setContent(content);
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.sql_copied", "SQL已复制到剪贴板！"));
        }
    }

    private void generateUpdateSql() {
        String sql = buildUpdateSql();
        closeSqlGenerationMenu();
        if (sql != null && !sql.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(sql);
            Clipboard.getSystemClipboard().setContent(content);
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.sql_copied", "SQL已复制到剪贴板！"));
        }
    }

    private void generateDeleteSql() {
        String sql = buildDeleteSql();
        closeSqlGenerationMenu();
        if (sql != null && !sql.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(sql);
            Clipboard.getSystemClipboard().setContent(content);
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.sql_copied", "SQL已复制到剪贴板！"));
        }
    }

    private void generateSelectSql() {
        String sql = buildSelectSql();
        closeSqlGenerationMenu();
        if (sql != null && !sql.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(sql);
            Clipboard.getSystemClipboard().setContent(content);
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.sql_copied", "SQL已复制到剪贴板！"));
        }
    }

    private void closeSqlGenerationMenu() {
        if (resultSetTableView.generateSqlMenu != null) resultSetTableView.generateSqlMenu.hide();
        ContextMenu ctx = resultSetTableView.getContextMenu();
        if (ctx != null) ctx.hide();
    }

    private String formatSqlValue(String val) {
        if (val == null || "[NULL]".equals(val)) return "NULL";
        if (val.isEmpty()) return "''";
        try {
            Double.parseDouble(val);
            return val;
        } catch (NumberFormatException ignored) {}
        return "'" + val.replace("'", "''") + "'";
    }

    private boolean isRowIdColumn(String colName) {
        String trimmed = colName == null ? "" : colName.trim();
        return "ROWID".equalsIgnoreCase(trimmed)
                || "IFX_ROW_ID".equalsIgnoreCase(trimmed);
    }

    private String bareCol(String colName) {
        if (colName == null || colName.isBlank()) return colName;
        String s = colName.trim();
        if (s.startsWith("\"")) return s;
        int idx = s.lastIndexOf('.');
        return idx > 0 && idx < s.length() - 1 ? s.substring(idx + 1) : s;
    }

    private List<String> colNames() {
        List<String> names = new ArrayList<>();
        for (TableColumn<ObservableList<String>, Object> col : colList) {
            // 列名存在 column 的 properties 中（由 ResultSetColumnBuilder.HEADER_NAME 存储）
            Object name = col.getProperties().get("resultset.header.name");
            names.add(name != null ? name.toString() : col.getText());
        }
        return names;
    }

    /** 获取选中行（去重，按行号升序），多行时逐行生成 SQL。 */
    private List<ObservableList<String>> selectedRows() {
        ObservableList<? extends TablePosition> cells = resultSetTableView.getSelectionModel().getSelectedCells();
        if (cells.isEmpty()) return new ArrayList<>();
        Set<Integer> rowSet = new TreeSet<>();
        for (TablePosition<?, ?> pos : cells) rowSet.add(pos.getRow());
        ObservableList<ObservableList<String>> items = resultSetTableView.getItems();
        List<ObservableList<String>> rows = new ArrayList<>();
        for (int r : rowSet) {
            if (r >= 0 && r < items.size()) rows.add(items.get(r));
        }
        return rows;
    }

    String buildInsertSql() {
        if (resultFromTable == null || resultFromTable.isBlank()) return "";
        List<ObservableList<String>> rows = selectedRows();
        if (rows.isEmpty()) return "";
        List<String> cols = colNames();
        if (cols.isEmpty()) return "";

        List<Integer> idxs = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            String bare = bareCol(cols.get(i));
            if (isRowIdColumn(bare)) continue;
            exprs.add(bare);
            idxs.add(i);
        }
        if (exprs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ObservableList<String> row : rows) {
            sb.append("INSERT INTO ").append(resultFromTable).append(" (");
            sb.append(String.join(", ", exprs)).append(") VALUES (");
            for (int j = 0; j < idxs.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(formatSqlValue(row.get(idxs.get(j) + 1)));
            }
            sb.append(");\n");
        }
        return sb.toString().trim();
    }

    String buildUpdateSql() {
        if (resultFromTable == null || resultFromTable.isBlank()) return "";
        if (resultTablePriNum.isEmpty()) return "";
        List<ObservableList<String>> rows = selectedRows();
        if (rows.isEmpty()) return "";
        List<String> cols = colNames();
        if (cols.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ObservableList<String> row : rows) {
            sb.append("UPDATE ").append(resultFromTable).append(" SET ");
            Set<Integer> pkSet = new HashSet<>(resultTablePriNum);
            List<String> setParts = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                if (pkSet.contains(i)) continue;
                String bare = bareCol(cols.get(i));
                if (isRowIdColumn(bare)) continue;
                setParts.add(bare + "=" + formatSqlValue(row.get(i + 1)));
            }
            if (setParts.isEmpty()) continue;
            sb.append(String.join(", ", setParts)).append(" WHERE ");
            List<String> where = new ArrayList<>();
            for (int idx : resultTablePriNum) {
                if (idx < cols.size())
                    where.add(bareCol(cols.get(idx)) + "=" + formatSqlValue(row.get(idx + 1)));
            }
            if (where.isEmpty()) continue;
            sb.append(String.join(" AND ", where)).append(";\n");
        }
        return sb.toString().trim();
    }

    String buildDeleteSql() {
        if (resultFromTable == null || resultFromTable.isBlank()) return "";
        if (resultTablePriNum.isEmpty()) return "";
        List<ObservableList<String>> rows = selectedRows();
        if (rows.isEmpty()) return "";
        List<String> cols = colNames();
        if (cols.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ObservableList<String> row : rows) {
            sb.append("DELETE FROM ").append(resultFromTable).append(" WHERE ");
            List<String> where = new ArrayList<>();
            for (int idx : resultTablePriNum) {
                if (idx < cols.size())
                    where.add(bareCol(cols.get(idx)) + "=" + formatSqlValue(row.get(idx + 1)));
            }
            if (where.isEmpty()) continue;
            sb.append(String.join(" AND ", where)).append(";\n");
        }
        return sb.toString().trim();
    }

    String buildSelectSql() {
        if (resultFromTable == null || resultFromTable.isBlank()) return "";
        List<ObservableList<String>> rows = selectedRows();
        if (rows.isEmpty()) return "";
        List<String> cols = colNames();
        if (cols.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ObservableList<String> row : rows) {
            sb.append("SELECT * FROM ").append(resultFromTable).append(" WHERE ");
            List<String> where = new ArrayList<>();
            if (!resultTablePriNum.isEmpty()) {
                for (int idx : resultTablePriNum)
                    if (idx < cols.size())
                        where.add(bareCol(cols.get(idx)) + "=" + formatSqlValue(row.get(idx + 1)));
            } else {
                for (int i = 0; i < cols.size(); i++)
                    where.add(bareCol(cols.get(i)) + "=" + formatSqlValue(row.get(i + 1)));
            }
            sb.append(String.join(" AND ", where)).append(";\n");
        }
        return sb.toString().trim();
    }
}
