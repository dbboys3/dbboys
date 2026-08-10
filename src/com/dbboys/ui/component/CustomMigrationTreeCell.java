package com.dbboys.ui.component;

import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.MigrationTask;
import com.dbboys.model.TreeData;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.treemodel.MigrationFolder;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Custom TreeCell for the migration task tree view. Renders {@link MigrationFolder}
 * and {@link MigrationTask} items, mimicking {@link CustomSshTreeCell} styling.
 * <p>
 * 任务节点：运行中把图标换成加载动画（监听 runStateProperty）；名称保持默认色不变，
 * 右侧按 runState/lastRunResult 显示状态文本（运行中/已完成/失败/已取消/未开始，随语言切换刷新）。
 * updateItem 复用时解除旧监听/解绑，防止泄漏和串状态。
 * <p>
 * 拖拽：任务节点是拖拽源（setOnDragDetected，快照缩略图仿 {@link CustomSshTreeCell}），
 * Dragboard 字符串内容格式为 {@code "MIGRATIONTASKDRAG;" + task.getId()}（见
 * {@link #DRAG_PAYLOAD_PREFIX}）；目标侧（MainController）按此前缀解析并打开任务明细 tab。
 */
public class CustomMigrationTreeCell extends TreeCell<TreeData> {

    private static final int ICON_SLOT_SIZE = 16;
    private static final String PRIMARY_ICON_STYLE = "icon-primary";
    private static final String HOVER_STYLE_CLASS = "hover";
    /** 任务拖拽 Dragboard 字符串前缀：内容为 {@code DRAG_PAYLOAD_PREFIX + task.getId()}。 */
    private static final String DRAG_PAYLOAD_PREFIX = "MIGRATIONTASKDRAG;";
    private boolean hovered;

    private final Label nameLabel = new Label();
    /** 右侧任务状态（运行中/已完成/失败/已取消/未开始），样式同连接树的描述标签。 */
    private final Label statusLabel = new Label();
    private final SVGPath nodeIcon = new SVGPath();
    private final Group nodeIconGroup = new Group(nodeIcon);
    private final StackPane nodeIconStackpane = new StackPane(nodeIconGroup);
    private final ImageView loadingIcon = IconFactory.loadingImageView(0.7);
    private final HBox graphicHbox = new HBox();
    private final Region spacer = new Region();

    /** 当前绑定的任务/树节点，updateItem 复用时先解除监听。 */
    private MigrationTask boundTask;
    private MigrationFolder boundFolder;
    private TreeItem<TreeData> boundTreeItem;

    private final ChangeListener<MigrationTask.RunState> runStateListener =
            (obs, oldState, newState) -> applyTaskIcon();
    private final ChangeListener<Boolean> expandListener =
            (obs, oldExpanded, newExpanded) -> applyFolderIcon();

    public CustomMigrationTreeCell() {
        graphicHbox.setAlignment(Pos.CENTER_LEFT);
        statusLabel.getStyleClass().add("tree-cell-description");
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Programmatic hover style class to avoid background flash during cell recycling
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> {
            if (!hovered && !isEmpty()) {
                hovered = true;
                getStyleClass().add(HOVER_STYLE_CLASS);
            }
        });
        setOnMouseExited(e -> {
            if (hovered) {
                hovered = false;
                getStyleClass().remove(HOVER_STYLE_CLASS);
            }
        });
    }

    @Override
    protected void updateItem(TreeData item, boolean empty) {
        // Clear hover before recycling to prevent background flash
        if (hovered) {
            hovered = false;
            getStyleClass().remove(HOVER_STYLE_CLASS);
        }
        super.updateItem(item, empty);
        if (item != null && !empty) {
            // 同一条目复用时不重建 graphic，避免右键选中/焦点切换导致文字闪烁
            if (item instanceof MigrationTask task && task == boundTask) {
                return;
            }
            if (item instanceof MigrationFolder folder && folder == boundFolder) {
                return;
            }
        }
        unbindAll();
        if (item == null || empty) {
            return;
        }
        if (item instanceof MigrationFolder) {
            renderMigrationFolder(item);
        } else if (item instanceof MigrationTask task) {
            renderMigrationTask(task);
        }
    }

    // ==================================================================
    // 文件夹
    // ==================================================================

    private void renderMigrationFolder(TreeData item) {
        boundFolder = (MigrationFolder) item;
        applyFolderIcon();
        textProperty().bind(item.nameProperty());
        setGraphic(nodeIconStackpane);
        // 展开/折叠时切换文件夹图标
        TreeItem<TreeData> treeItem = getTreeItem();
        if (treeItem != null) {
            treeItem.expandedProperty().addListener(expandListener);
            boundTreeItem = treeItem;
        }
    }

    private void applyFolderIcon() {
        TreeItem<TreeData> treeItem = getTreeItem();
        if (treeItem != null && treeItem.isExpanded()) {
            nodeIcon.setContent(IconPaths.TREECELL_CONNECT_FOLDER_OPEN);
            nodeIcon.setScaleX(0.62);
            nodeIcon.setScaleY(0.62);
        } else {
            nodeIcon.setContent(IconPaths.CREATE_CONNECT_FOLDER);
            nodeIcon.setScaleX(0.52);
            nodeIcon.setScaleY(0.52);
        }
        applyPrimaryIconStyle(nodeIcon);
    }

    // ==================================================================
    // 任务
    // ==================================================================

    private void renderMigrationTask(MigrationTask task) {
        boundTask = task;
        task.runStateProperty().addListener(runStateListener);

        nodeIconStackpane.setMinSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
        nodeIconStackpane.setPrefSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
        nodeIconStackpane.setMaxSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
        nodeIconStackpane.setAlignment(Pos.CENTER);

        nodeIcon.setContent(IconPaths.MIGRATION_TAB_TOGGLE);
        nodeIcon.setScaleX(0.66);
        nodeIcon.setScaleY(0.66);
        applyPrimaryIconStyle(nodeIcon);

        nameLabel.textProperty().bind(task.nameProperty());
        applyTaskIcon();
        bindTaskStatus(task);
        configureDragActions(task);
    }

    /** RUNNING → 加载动画图标；IDLE → 迁移图标。右侧 spacer 把状态标签推到最右。 */
    private void applyTaskIcon() {
        if (boundTask == null) {
            return;
        }
        boolean running = boundTask.getRunState() == MigrationTask.RunState.RUNNING;
        Node iconNode = running ? loadingIcon : nodeIconStackpane;
        graphicHbox.getChildren().setAll(iconNode, nameLabel, spacer, statusLabel);
        setGraphic(graphicHbox);
    }

    /** 右侧状态文本：运行中/已完成/失败/已取消/未开始；任务名保持默认色不变。 */
    private void bindTaskStatus(MigrationTask task) {
        statusLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            if (task.getRunState() == MigrationTask.RunState.RUNNING) {
                return I18n.t("migration.task.status_running", "Running");
            }
            return switch (task.getLastRunResult()) {
                case SUCCESS -> I18n.t("migration.task.status_done", "Finished");
                case FAILED -> I18n.t("migration.status.failed", "Failed");
                case CANCELLED -> I18n.t("migration.status.cancelled", "Cancelled");
                case NONE -> I18n.t("migration.task.status_not_started", "Not started");
            };
        }, task.runStateProperty(), task.lastRunResultProperty(), I18n.localeProperty()));
    }

    /**
     * 拖拽源：任务节点可拖到中央 TabPane 打开任务明细 tab（接受逻辑在 MainController）。
     * Dragboard 字符串内容：{@code "MIGRATIONTASKDRAG;" + task.getId()}。
     */
    private void configureDragActions(MigrationTask task) {
        setOnDragDetected(event -> {
            if (getItem() == null) {
                return;
            }
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            Image dragImage = this.snapshot(params, null);
            Dragboard db = startDragAndDrop(TransferMode.COPY_OR_MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(DRAG_PAYLOAD_PREFIX + task.getId());
            db.setContent(content);
            db.setDragView(dragImage, event.getX(), event.getY());
            event.consume();
        });
    }

    // ==================================================================
    // 复用清理
    // ==================================================================

    /** 解除旧监听/解绑并复位视觉状态，防止单元格复用泄漏和串状态。 */
    private void unbindAll() {
        if (boundTask != null) {
            boundTask.runStateProperty().removeListener(runStateListener);
            boundTask = null;
        }
        if (boundTreeItem != null) {
            boundTreeItem.expandedProperty().removeListener(expandListener);
            boundTreeItem = null;
        }
        boundFolder = null;
        setOnDragDetected(null);
        statusLabel.textProperty().unbind();
        statusLabel.setText(null);
        resetCellVisualState();
    }

    private void resetCellVisualState() {
        setTooltip(null);
        setGraphic(null);
        textProperty().unbind();
        setText(null);
        nameLabel.textProperty().unbind();
        nodeIconGroup.setVisible(true);
        nodeIconStackpane.getChildren().clear();
        nodeIconStackpane.getChildren().add(nodeIconGroup);
        nodeIconStackpane.setMinSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        nodeIconStackpane.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        nodeIconStackpane.setMaxSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        graphicHbox.getChildren().clear();
    }

    private void applyPrimaryIconStyle(SVGPath icon) {
        icon.getStyleClass().removeAll("icon-warn", "icon-inactive");
        if (!icon.getStyleClass().contains(PRIMARY_ICON_STYLE)) {
            icon.getStyleClass().add(PRIMARY_ICON_STYLE);
        }
    }
}
