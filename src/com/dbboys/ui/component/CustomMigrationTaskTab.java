package com.dbboys.ui.component;

import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.MigrationObjectRef;
import com.dbboys.model.MigrationRunItem;
import com.dbboys.model.MigrationTask;
import com.dbboys.service.migration.MigrationTaskRunner;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 迁移任务明细中央 tab（仿 {@link CustomSshTab}，纯代码 content）。
 * <p>
 * 头部：启动/停止按钮（直接调 {@link MigrationTaskRunner}）、源→目标描述
 * （连接名/库·模式，连接名按 sourceId/targetId 从
 * {@link LocalDbRepository#getConnectLeafs()} 解析，解析失败显示 "?"）。
 * <p>
 * 主体：明细 TableView，items 直接绑定 {@link MigrationTask#getRunItems()}（瞬时，
 * 由 {@link MigrationTaskRunner} 在 FX 线程维护）；错误信息列宽自适应剩余空间，
 * 双击错误单元格弹出出错 SQL + 具体错误。
 * <p>
 * 底部：总进度（ProgressBar + 已处理/总数）、开始/完成时间、当前对象，
 * 成功/失败筛选按钮固定右下（spacer 吸收激活样式的尺寸变化，不挤压进度条）。
 * <p>
 * 进度刷新：监听 runItems 列表变化 + 每行 statusProperty 变化；
 * tab 关闭（setOnClosed）时移除全部监听并解绑标题，防止任务对象长期持有 tab 引用。
 */
public class CustomMigrationTaskTab extends CustomTab {

    private final MigrationTask task;

    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label();
    private final Label timeLabel = new Label();
    private final Label currentObjectLabel = new Label();
    private final FilteredList<MigrationRunItem> filteredItems;
    private final TableView<MigrationRunItem> detailTable;
    private Button successFilterButton;
    private Button failureFilterButton;
    private FilterMode filterMode = FilterMode.ALL;

    private enum FilterMode {
        ALL, SUCCESS, FAILURE
    }

    /** 单行状态变化 → 刷新进度/计数；随 runItems 增删挂接/摘除。 */
    private final ChangeListener<MigrationRunItem.Status> rowStatusListener =
            (obs, oldStatus, newStatus) -> refreshProgress();
    private final ListChangeListener<MigrationRunItem> runItemsListener = change -> {
        while (change.next()) {
            for (MigrationRunItem removed : change.getRemoved()) {
                removed.statusProperty().removeListener(rowStatusListener);
            }
            for (MigrationRunItem added : change.getAddedSubList()) {
                added.statusProperty().addListener(rowStatusListener);
            }
        }
        refreshProgress();
    };

    public CustomMigrationTaskTab(MigrationTask task) {
        super(task.getName());
        this.task = task;
        // 与 TabpaneUtil.addCustomMigrationTaskTab 的去重键一致
        setUserData("migration_task_" + task.getId());
        setTabIcon(IconPaths.MIGRATION_TAB_TOGGLE, 0.6);
        textProperty().bind(task.nameProperty());

        // ---- 头部信息区 ----
        Label routeLabel = new Label(buildRouteText());

        Button startButton = new Button();
        startButton.textProperty().bind(I18n.bind("migration.menu.start", "Start"));
        startButton.disableProperty().bind(
                task.runStateProperty().isEqualTo(MigrationTask.RunState.RUNNING));
        startButton.setOnAction(event -> MigrationTaskRunner.start(task));

        Button stopButton = new Button();
        stopButton.textProperty().bind(I18n.bind("migration.menu.stop", "Stop"));
        stopButton.disableProperty().bind(
                task.runStateProperty().isNotEqualTo(MigrationTask.RunState.RUNNING));
        stopButton.setOnAction(event -> MigrationTaskRunner.stop(task));

        startButton.setGraphic(IconFactory.group(IconPaths.SQL_RUN, 0.8, Color.valueOf("#51dd66")));
        startButton.getStyleClass().add("custom-button");
        Tooltip startTip = new Tooltip();
        startTip.textProperty().bind(I18n.bind("migration.menu.start", "Start"));
        startButton.setTooltip(startTip);

        stopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.8, IconFactory.stopColor()));
        stopButton.getStyleClass().add("custom-button");
        Tooltip stopTip = new Tooltip();
        stopTip.textProperty().bind(I18n.bind("migration.menu.stop", "Stop"));
        stopButton.setTooltip(stopTip);

        successFilterButton = createFilterButton(true);
        failureFilterButton = createFilterButton(false);

        HBox headerRow = new HBox(10, startButton, stopButton, routeLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        progressBar.setPrefWidth(220);
        // 进度条高度缩小一半
        progressBar.setPrefHeight(7);
        progressBar.setMaxHeight(7);
        // 运行中显示开始时间，结束后显示完成时间
        timeLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            if (task.getRunState() == MigrationTask.RunState.RUNNING) {
                String start = task.getLastStartTime();
                return start == null || start.isBlank() ? ""
                        : String.format(I18n.t("migration.detail.started_at", "Started: %s"), start);
            }
            String end = task.getLastEndTime();
            return end == null || end.isBlank() ? ""
                    : String.format(I18n.t("migration.detail.finished_at", "Finished: %s"), end);
        }, task.runStateProperty(), task.lastStartTimeProperty(), task.lastEndTimeProperty(),
                I18n.localeProperty()));
        // 成功/失败筛选固定右下，spacer 吸收激活样式的尺寸变化，不挤压进度条
        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox bottomRow = new HBox(10, progressBar, progressLabel, timeLabel, currentObjectLabel,
                bottomSpacer, successFilterButton, failureFilterButton);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        // ---- 明细 TableView ----
        filteredItems = new FilteredList<>(task.getRunItems(), item -> true);
        detailTable = buildDetailTable();
        VBox.setVgrow(detailTable, Priority.ALWAYS);

        // 表格上下不留间距
        VBox root = new VBox(0, headerRow, detailTable, bottomRow);
        root.setPadding(new Insets(0));
        setContent(root);

        // ---- 进度/计数监听（关闭时移除，防泄漏） ----
        for (MigrationRunItem item : task.getRunItems()) {
            item.statusProperty().addListener(rowStatusListener);
        }
        task.getRunItems().addListener(runItemsListener);
        refreshProgress();

        setOnClosed(event -> dispose());

        // 从未运行的任务：后台展开对象清单，预填 PENDING 明细行（不启动迁移）
        MigrationTaskRunner.prepareRunItems(task);
    }

    /** 关闭时移除监听/解绑，防止任务对象持有已关闭 tab。 */
    private void dispose() {
        task.getRunItems().removeListener(runItemsListener);
        for (MigrationRunItem item : task.getRunItems()) {
            item.statusProperty().removeListener(rowStatusListener);
        }
        textProperty().unbind();
    }

    // ==================================================================
    // 头部
    // ==================================================================

    /** 源连接名/库·模式 → 目标连接名/库·模式。 */
    private String buildRouteText() {
        StringBuilder sb = new StringBuilder(connectName(task.getSourceId()));
        String sourceScope = sourceScope(task.getObjectRefs());
        if (!sourceScope.isBlank()) {
            sb.append(" / ").append(sourceScope);
        }
        sb.append("  →  ").append(connectName(task.getTargetId()));
        StringBuilder targetScope = new StringBuilder();
        if (task.getTargetDatabase() != null && !task.getTargetDatabase().isBlank()) {
            targetScope.append(task.getTargetDatabase());
        }
        if (task.getTargetSchema() != null && !task.getTargetSchema().isBlank()) {
            if (targetScope.length() > 0) {
                targetScope.append('.');
            }
            targetScope.append(task.getTargetSchema());
        }
        if (targetScope.length() > 0) {
            sb.append(" / ").append(targetScope);
        }
        return sb.toString();
    }

    private static String connectName(int connectId) {
        for (Connect connect : LocalDbRepository.getConnectLeafs()) {
            if (connect != null && connect.getId() == connectId) {
                return connect.getName();
            }
        }
        return "?";
    }

    /** 源端库·模式：对象 refs 中去重后的 catalog.schema 组合。 */
    private static String sourceScope(List<MigrationObjectRef> refs) {
        Set<String> scopes = new LinkedHashSet<>();
        for (MigrationObjectRef ref : refs) {
            if (ref == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (ref.catalog() != null) {
                sb.append(ref.catalog());
            }
            if (ref.schema() != null) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(ref.schema());
            }
            if (sb.length() > 0) {
                scopes.add(sb.toString());
            }
        }
        return String.join(", ", scopes);
    }

    // ==================================================================
    // 明细 TableView
    // ==================================================================

    private TableView<MigrationRunItem> buildDetailTable() {
        // CustomTableView 自带行号列、多选/单元格选择、复制菜单
        CustomTableView<MigrationRunItem> table = new CustomTableView<>();
        table.setItems(filteredItems);
        // 移除与本场景无关的“生成SQL”菜单及分隔符，保留复制
        table.getContextMenu().getItems().removeIf(
                mi -> mi == table.generateSqlMenu || mi instanceof javafx.scene.control.SeparatorMenuItem);
        // 失败行红色文字（CSS 类，运行中状态变化时同步刷新）
        table.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            private MigrationRunItem listened;
            private final javafx.beans.value.ChangeListener<MigrationRunItem.Status> statusListener =
                    (o, ov, nv) -> refreshStyle();
            {
                itemProperty().addListener((obs, oldItem, newItem) -> {
                    if (listened != null) {
                        listened.statusProperty().removeListener(statusListener);
                    }
                    listened = newItem;
                    if (newItem != null) {
                        newItem.statusProperty().addListener(statusListener);
                    }
                    refreshStyle();
                });
            }
            private void refreshStyle() {
                MigrationRunItem it = getItem();
                boolean failed = it != null && it.getStatus() == MigrationRunItem.Status.FAILED;
                if (failed) {
                    if (!getStyleClass().contains("migration-row-failed")) {
                        getStyleClass().add("migration-row-failed");
                    }
                } else {
                    getStyleClass().remove("migration-row-failed");
                }
            }
        });

        TableColumn<MigrationRunItem, String> kindColumn = new TableColumn<>();
        kindColumn.textProperty().bind(I18n.bind("migration.detail.column.kind", "Kind"));
        // 与状态列一样绑定 locale，语言切换时同步刷新
        kindColumn.setCellValueFactory(cell -> Bindings.createStringBinding(
                () -> kindLabel(cell.getValue().getKind()),
                I18n.localeProperty()));
        kindColumn.setPrefWidth(80);

        TableColumn<MigrationRunItem, String> nameColumn = new TableColumn<>();
        nameColumn.textProperty().bind(I18n.bind("migration.detail.column.name", "Object"));
        nameColumn.setCellValueFactory(cell -> cell.getValue().nameProperty());
        nameColumn.setPrefWidth(240);

        TableColumn<MigrationRunItem, String> statusColumn = new TableColumn<>();
        statusColumn.textProperty().bind(I18n.bind("migration.detail.column.status", "Status"));
        statusColumn.setCellValueFactory(cell -> Bindings.createStringBinding(
                () -> statusLabel(cell.getValue().getStatus()),
                cell.getValue().statusProperty(), I18n.localeProperty()));
        statusColumn.setPrefWidth(90);

        TableColumn<MigrationRunItem, String> startColumn = new TableColumn<>();
        startColumn.textProperty().bind(I18n.bind("migration.detail.column.start", "Start"));
        startColumn.setCellValueFactory(cell -> cell.getValue().startTimeProperty());
        startColumn.setPrefWidth(150);

        TableColumn<MigrationRunItem, String> endColumn = new TableColumn<>();
        endColumn.textProperty().bind(I18n.bind("migration.detail.column.end", "End"));
        endColumn.setCellValueFactory(cell -> cell.getValue().endTimeProperty());
        endColumn.setPrefWidth(150);

        TableColumn<MigrationRunItem, String> speedColumn = new TableColumn<>();
        speedColumn.textProperty().bind(I18n.bind("migration.detail.column.speed", "Speed"));
        speedColumn.setCellValueFactory(cell -> cell.getValue().speedProperty());
        speedColumn.setPrefWidth(90);

        TableColumn<MigrationRunItem, Number> rowsColumn = new TableColumn<>();
        rowsColumn.textProperty().bind(I18n.bind("migration.detail.column.rows", "Rows"));
        rowsColumn.setCellValueFactory(cell -> cell.getValue().rowsProperty());
        rowsColumn.setPrefWidth(90);

        TableColumn<MigrationRunItem, String> errorColumn = new TableColumn<>();
        errorColumn.textProperty().bind(I18n.bind("migration.detail.column.error", "Error"));
        errorColumn.setCellValueFactory(cell -> cell.getValue().errorMessageProperty());
        // 双击错误单元格：弹出出错 SQL + 具体错误
        errorColumn.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty()) {
                        MigrationRunItem item = getTableView().getItems().get(getIndex());
                        if (item != null) {
                            showErrorDetail(item);
                        }
                    }
                });
            }

            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
            }
        });
        // 错误信息列宽占满剩余空间；prefWidth 被绑定后不允许拖拽改宽，故禁掉列 resize
        errorColumn.setResizable(false);
        errorColumn.prefWidthProperty().bind(table.widthProperty()
                .subtract(table.getColumns().get(0).widthProperty())
                .subtract(kindColumn.widthProperty())
                .subtract(nameColumn.widthProperty())
                .subtract(statusColumn.widthProperty())
                .subtract(startColumn.widthProperty())
                .subtract(endColumn.widthProperty())
                .subtract(speedColumn.widthProperty())
                .subtract(rowsColumn.widthProperty())
                .subtract(18));

        table.getColumns().addAll(java.util.List.<TableColumn<MigrationRunItem, ?>>of(
                kindColumn, nameColumn, statusColumn,
                startColumn, endColumn, speedColumn, rowsColumn, errorColumn));
        return table;
    }

    // ==================================================================
    // 进度/计数刷新
    // ==================================================================

    private void refreshProgress() {
        ObservableList<MigrationRunItem> items = task.getRunItems();
        int total = items.size();
        long success = 0;
        long failed = 0;
        String currentObject = "";
        for (MigrationRunItem item : items) {
            if (item == null || item.getStatus() == null) {
                continue;
            }
            switch (item.getStatus()) {
                case SUCCESS -> success++;
                case FAILED -> failed++;
                case RUNNING -> {
                    if (currentObject.isEmpty() && item.getName() != null) {
                        currentObject = item.getName();
                    }
                }
                case PENDING -> {
                }
            }
        }
        long done = success + failed;
        progressBar.setProgress(total == 0 ? 0 : (double) done / total);
        progressLabel.setText(String.format(
                I18n.t("migration.detail.progress", "%d / %d"), done, total));
        currentObjectLabel.setText(currentObject);
        refreshFilterButtons(success, failed);
    }

    private Button createFilterButton(boolean success) {
        Button button = new Button();
        button.getStyleClass().addAll("result-filter-button",
                success ? "result-filter-success" : "result-filter-failure");
        Region dot = new Region();
        dot.getStyleClass().addAll("result-filter-dot",
                success ? "result-filter-dot-success" : "result-filter-dot-failure");
        button.setGraphic(dot);
        button.setOnAction(e -> {
            FilterMode next = filterMode == (success ? FilterMode.SUCCESS : FilterMode.FAILURE)
                    ? FilterMode.ALL
                    : (success ? FilterMode.SUCCESS : FilterMode.FAILURE);
            setFilterMode(next);
        });
        return button;
    }

    private void setFilterMode(FilterMode mode) {
        filterMode = mode;
        applyFilter();
        refreshProgress();
    }

    private void refreshFilterButtons(long success, long failed) {
        if (successFilterButton != null) {
            successFilterButton.setText(
                    I18n.t("migration.status.success", "Success") + " " + success);
        }
        if (failureFilterButton != null) {
            failureFilterButton.setText(
                    I18n.t("migration.status.failed", "Failed") + " " + failed);
        }
        applyFilter();
    }

    private void applyFilter() {
        if (filteredItems == null) {
            return;
        }
        filteredItems.setPredicate(item -> switch (filterMode) {
            case ALL -> true;
            case SUCCESS -> item != null && item.getStatus() == MigrationRunItem.Status.SUCCESS;
            case FAILURE -> item != null && item.getStatus() == MigrationRunItem.Status.FAILED;
        });
        if (successFilterButton != null) {
            successFilterButton.getStyleClass().remove("result-filter-active");
            if (filterMode == FilterMode.SUCCESS) {
                successFilterButton.getStyleClass().add("result-filter-active");
            }
        }
        if (failureFilterButton != null) {
            failureFilterButton.getStyleClass().remove("result-filter-active");
            if (filterMode == FilterMode.FAILURE) {
                failureFilterButton.getStyleClass().add("result-filter-active");
            }
        }
    }

    // ==================================================================
    // 错误详情弹窗
    // ==================================================================

    /** 双击错误单元格：弹出出错 SQL + 具体错误（SQL 仅本次运行内存中有，恢复的历史记录没有）。 */
    private static void showErrorDetail(MigrationRunItem item) {
        VBox box = new VBox(6);
        if (item.getErrorSql() != null && !item.getErrorSql().isBlank()) {
            Label sqlCaption = new Label();
            sqlCaption.textProperty().bind(I18n.bind("migration.detail.error_sql", "Failed SQL"));
            sqlCaption.setStyle("-fx-font-weight: bold;");
            TextArea sqlArea = new TextArea(item.getErrorSql());
            sqlArea.setEditable(false);
            sqlArea.setWrapText(true);
            VBox.setVgrow(sqlArea, Priority.ALWAYS);
            box.getChildren().addAll(sqlCaption, sqlArea);
        }
        Label errorCaption = new Label();
        errorCaption.textProperty().bind(I18n.bind("migration.detail.column.error", "Error"));
        errorCaption.setStyle("-fx-font-weight: bold;");
        TextArea errorArea = new TextArea(item.getErrorMessage());
        errorArea.setEditable(false);
        errorArea.setWrapText(true);
        VBox.setVgrow(errorArea, Priority.ALWAYS);
        box.getChildren().addAll(errorCaption, errorArea);

        ButtonType closeButton = new ButtonType(
                I18n.t("migration.button.close", "Close"), ButtonBar.ButtonData.OK_DONE);
        String title = String.format(I18n.t("migration.detail.error_title", "Error Details - %s"),
                item.getName());
        AlertUtil.createContentDialog(title, box, 560, 320, closeButton).showAndWait();
    }

    // ==================================================================
    // i18n 文本
    // ==================================================================

    private static String kindLabel(MigrationObjectRef.Kind kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case TABLE -> I18n.t("migration.kind.table", "Table");
            case VIEW -> I18n.t("migration.kind.view", "View");
            case SEQUENCE -> I18n.t("migration.kind.sequence", "Sequence");
            case SYNONYM -> I18n.t("migration.kind.synonym", "Synonym");
            case TRIGGER -> I18n.t("migration.kind.trigger", "Trigger");
            case FUNCTION -> I18n.t("migration.kind.function", "Function");
            case PROCEDURE -> I18n.t("migration.kind.procedure", "Procedure");
            case PACKAGE -> I18n.t("migration.kind.package", "Package");
            case ALL -> I18n.t("migration.kind.all", "All");
        };
    }

    private static String statusLabel(MigrationRunItem.Status status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> I18n.t("migration.status.pending", "Pending");
            case RUNNING -> I18n.t("migration.status.running", "Running");
            case SUCCESS -> I18n.t("migration.status.success", "Success");
            case FAILED -> I18n.t("migration.status.failed", "Failed");
        };
    }
}
