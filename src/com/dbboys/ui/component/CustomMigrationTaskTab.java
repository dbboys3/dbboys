package com.dbboys.ui.component;

import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.MigrationObjectRef;
import com.dbboys.model.MigrationRunItem;
import com.dbboys.model.MigrationTask;
import com.dbboys.service.migration.MigrationTaskRunner;
import com.dbboys.ui.controller.MigrationDialogController;
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
 * {@link LocalDbRepository#getConnectLeafs()} 解析，解析失败显示 "?"）、
 * 右端编辑按钮（打开任务编辑框，保存后同步路由与明细预览）。
 * <p>
 * 主体：明细 TableView，items 直接绑定 {@link MigrationTask#getRunItems()}（瞬时，
 * 由 {@link MigrationTaskRunner} 在 FX 线程维护）；列宽不压缩、超出可横向滚动；
 * 成功/失败筛选按钮与表格父节点同层，悬浮于整个内容区右下角；双击错误单元格弹出出错 SQL + 具体错误。
 * <p>
 * 底部：总进度（ProgressBar + 已处理/总数）、开始/完成时间、当前对象。
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
    private final Label routeLabel = new Label();
    private final FilteredList<MigrationRunItem> filteredItems;
    private final TableView<MigrationRunItem> detailTable;
    /** 每秒把实时进度（volatile 字段）搬进 FX 属性，驱动明细表刷新。 */
    private final javafx.animation.Timeline refreshTimeline = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), event -> tickLiveProgress()));
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
        routeLabel.setText(buildRouteText());

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

        // 图标规格与 SQL 页运行/停止按钮一致；按钮不可获取焦点
        startButton.setGraphic(IconFactory.group(IconPaths.SQL_RUN, 0.85, Color.valueOf("#51dd66")));
        startButton.getStyleClass().add("custom-button");
        startButton.setFocusTraversable(false);
        Tooltip startTip = new Tooltip();
        startTip.textProperty().bind(I18n.bind("migration.menu.start", "Start"));
        startButton.setTooltip(startTip);

        stopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        stopButton.getStyleClass().add("custom-button");
        stopButton.setFocusTraversable(false);
        Tooltip stopTip = new Tooltip();
        stopTip.textProperty().bind(I18n.bind("migration.menu.stop", "Stop"));
        stopButton.setTooltip(stopTip);

        successFilterButton = createFilterButton(true);
        failureFilterButton = createFilterButton(false);

        // 编辑任务（头部右端图标按钮）：运行中禁用，与树右键菜单一致
        Button editButton = new Button();
        editButton.setGraphic(IconFactory.group(IconPaths.METADATA_RENAME_ITEM, 0.7));
        editButton.getStyleClass().add("custom-button");
        editButton.setFocusTraversable(false);
        editButton.disableProperty().bind(
                task.runStateProperty().isEqualTo(MigrationTask.RunState.RUNNING));
        Tooltip editTip = new Tooltip();
        editTip.textProperty().bind(I18n.bind("migration.menu.edit", "Edit"));
        editButton.setTooltip(editTip);
        editButton.setOnAction(event -> editTask());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(10, startButton, stopButton, routeLabel, headerSpacer, editButton);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        progressBar.setPrefWidth(220);
        // 进度条高度缩小一半
        progressBar.setPrefHeight(7);
        progressBar.setMaxHeight(7);
        // 总进度后始终显示开始时间（有记录时），完成后再追加完成时间；运行中不显示上一轮残留的完成时间
        timeLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            StringBuilder text = new StringBuilder();
            String start = task.getLastStartTime();
            if (start != null && !start.isBlank()) {
                text.append(String.format(I18n.t("migration.detail.started_at", "Started: %s"), start));
            }
            String end = task.getLastEndTime();
            if (task.getRunState() != MigrationTask.RunState.RUNNING
                    && end != null && !end.isBlank()) {
                if (text.length() > 0) {
                    text.append("  ");
                }
                text.append(String.format(I18n.t("migration.detail.finished_at", "Finished: %s"), end));
            }
            return text.toString();
        }, task.runStateProperty(), task.lastStartTimeProperty(), task.lastEndTimeProperty(),
                I18n.localeProperty()));
        HBox bottomRow = new HBox(10, progressBar, progressLabel, timeLabel, currentObjectLabel);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        // 行高增加约 20%（上下各加 2px）
        bottomRow.setPadding(new Insets(2, 0, 2, 0));

        // ---- 明细 TableView ----
        filteredItems = new FilteredList<>(task.getRunItems(), item -> true);
        detailTable = buildDetailTable();
        VBox.setVgrow(detailTable, Priority.ALWAYS);

        // 表格上下不留间距
        VBox contentBox = new VBox(0, headerRow, detailTable, bottomRow);
        contentBox.setPadding(new Insets(0));

        // 成功/失败筛选按钮与表格父节点同层：悬浮于整个内容区右下角；空白处不拦截鼠标事件
        HBox filterOverlay = new HBox(6, successFilterButton, failureFilterButton);
        filterOverlay.setAlignment(Pos.BOTTOM_RIGHT);
        filterOverlay.setPickOnBounds(false);
        filterOverlay.setPadding(new Insets(0, 8, 1, 0));
        javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(contentBox, filterOverlay);
        javafx.scene.layout.StackPane.setAlignment(filterOverlay, Pos.BOTTOM_RIGHT);
        setContent(root);

        // ---- 进度/计数监听（关闭时移除，防泄漏） ----
        for (MigrationRunItem item : task.getRunItems()) {
            item.statusProperty().addListener(rowStatusListener);
        }
        task.getRunItems().addListener(runItemsListener);
        refreshProgress();

        // 每秒刷新一次明细表（实时行数/速度）
        refreshTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        refreshTimeline.play();

        setOnClosed(event -> dispose());

        // 从未运行的任务：后台展开对象清单，预填 PENDING 明细行（不启动迁移）
        MigrationTaskRunner.prepareRunItems(task);
    }

    /** 关闭时移除监听/解绑，防止任务对象持有已关闭 tab。 */
    private void dispose() {
        refreshTimeline.stop();
        task.getRunItems().removeListener(runItemsListener);
        for (MigrationRunItem item : task.getRunItems()) {
            item.statusProperty().removeListener(rowStatusListener);
        }
        textProperty().unbind();
    }

    /** 头部编辑按钮：打开任务编辑框；保存后落库、重置运行状态并刷新本页。 */
    private void editTask() {
        if (task.isRunning()) {
            AlertUtil.CustomAlert(I18n.t("common.hint", "Hint"),
                    I18n.t("migration.error.task_running", "Task is running, stop it first"));
            return;
        }
        MigrationDialogController dialog = new MigrationDialogController();
        MigrationTask result = dialog.showAndWait(task, LocalDbRepository.getConnectLeafs());
        if (result == null) {
            return;
        }
        // 编辑框在同一任务对象上原地修改，直接落库；运行状态重置为与新建任务一致
        LocalDbRepository.updateMigrationTask(result);
        MigrationTaskRunner.resetRunState(task);
        refreshAfterEdit();
        if (dialog.isStartRequested()) {
            MigrationTaskRunner.start(task);
        }
    }

    /** 任务编辑保存后同步本页：路由描述重建，明细行按新对象清单重新预填（跳过历史恢复）。 */
    public void refreshAfterEdit() {
        if (task.isRunning()) {
            return;
        }
        routeLabel.setText(buildRouteText());
        task.getRunItems().clear();
        MigrationTaskRunner.prepareRunItems(task, true);
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
        // 速度存数值（行/秒，-1 未知），单元格按当前语言格式化，语言切换即时刷新
        speedColumn.setCellValueFactory(cell -> Bindings.createStringBinding(
                () -> {
                    long speed = cell.getValue().getSpeed();
                    return speed < 0 ? "" : String.format(I18n.t("migration.detail.speed", "%s rows/s"),
                            String.format("%,d", speed));
                },
                cell.getValue().speedProperty(), I18n.localeProperty()));
        speedColumn.setPrefWidth(90);

        TableColumn<MigrationRunItem, String> rowsColumn = new TableColumn<>();
        rowsColumn.textProperty().bind(I18n.bind("migration.detail.column.rows", "Rows"));
        // 行数=源表行数；未知（-1）显示空白
        rowsColumn.setCellValueFactory(cell -> Bindings.createStringBinding(
                () -> cell.getValue().getRows() < 0 ? "" : String.valueOf(cell.getValue().getRows()),
                cell.getValue().rowsProperty()));
        rowsColumn.setPrefWidth(90);

        TableColumn<MigrationRunItem, String> migratedColumn = new TableColumn<>();
        migratedColumn.textProperty().bind(I18n.bind("migration.detail.column.migrated_rows", "Migrated Rows"));
        // 迁移行数=实际复制行数；未知（-1）显示空白
        migratedColumn.setCellValueFactory(cell -> Bindings.createStringBinding(
                () -> cell.getValue().getMigratedRows() < 0 ? "" : String.valueOf(cell.getValue().getMigratedRows()),
                cell.getValue().migratedRowsProperty()));
        migratedColumn.setPrefWidth(90);

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
        // 错误信息列固定初始宽度：列总宽超出表格时允许横向滚动（CustomTableView 为 UNCONSTRAINED 策略）
        errorColumn.setPrefWidth(400);

        TableColumn<MigrationRunItem, String> verifyColumn = new TableColumn<>();
        verifyColumn.textProperty().bind(I18n.bind("migration.detail.column.verify", "Verify"));
        // 数据校验列：展示持久化的校验结果（c_checksum），暂不做校验操作
        verifyColumn.setCellValueFactory(cell -> cell.getValue().checksumProperty());
        verifyColumn.setPrefWidth(90);

        table.getColumns().addAll(java.util.List.<TableColumn<MigrationRunItem, ?>>of(
                kindColumn, nameColumn, statusColumn,
                startColumn, endColumn, rowsColumn, migratedColumn, speedColumn, errorColumn, verifyColumn));
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
                case PENDING, CANCELLED -> {
                    // 已取消不计入完成数（进度保持中断时的真实完成度）
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

    /** 每秒 tick：运行中把各行的实时进度（工作线程写入的 volatile 字段）同步到 FX 属性。 */
    private void tickLiveProgress() {
        if (!task.isRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (MigrationRunItem item : task.getRunItems()) {
            long sourceRows = item.getSourceRowsLive();
            if (sourceRows >= 0 && item.getRows() != sourceRows) {
                item.setRows(sourceRows);
            }
            long copiedRows = item.getCopiedRowsLive();
            if (copiedRows >= 0 && item.getMigratedRows() != copiedRows) {
                item.setMigratedRows(copiedRows);
            }
            // 运行中的行实时速度：已迁移行数 / 已耗时
            if (item.getStatus() == MigrationRunItem.Status.RUNNING
                    && copiedRows > 0 && item.getStartMillis() > 0) {
                item.setSpeed(MigrationTaskRunner.computeSpeed(copiedRows, item.getStartMillis(), now));
            }
        }
    }

    private Button createFilterButton(boolean success) {
        Button button = new Button();
        button.getStyleClass().addAll("result-filter-button",
                success ? "result-filter-success" : "result-filter-failure");
        button.setFocusTraversable(false);
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

    /** 双击错误单元格：单框展示出错 SQL + 具体错误（SQL 在前、错误在后；SQL 仅本次运行内存中有）。 */
    private static void showErrorDetail(MigrationRunItem item) {
        String sql = item.getErrorSql() == null ? "" : item.getErrorSql().trim();
        String error = item.getErrorMessage() == null ? "" : item.getErrorMessage();
        String content = sql.isEmpty() ? error
                : (error.isEmpty() ? sql : sql + "\n\n" + error);
        TextArea area = new TextArea(content);
        area.setEditable(false);
        area.setWrapText(true);

        ButtonType closeButton = new ButtonType(
                I18n.t("migration.button.close", "Close"), ButtonBar.ButtonData.OK_DONE);
        String title = String.format(I18n.t("migration.detail.error_title", "Error Details - %s"),
                item.getName());
        AlertUtil.createContentDialog(title, area, 560, 320, closeButton).showAndWait();
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
            case CANCELLED -> I18n.t("migration.status.cancelled", "Cancelled");
        };
    }
}
