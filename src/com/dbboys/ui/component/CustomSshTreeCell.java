package com.dbboys.ui.component;

import com.dbboys.model.SshFolder;
import com.dbboys.model.TreeData;
import com.dbboys.model.SshConnect;
import com.dbboys.ui.icon.IconPaths;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.image.Image;
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
 * Custom TreeCell for the SSH tree view. Renders SshFolder and SshConnect items
 * with appropriate icons, mimicking the database connection tree styling.
 */
public class CustomSshTreeCell extends TreeCell<TreeData> {

    private static final int ICON_SLOT_SIZE = 16;
    private static final String PRIMARY_ICON_STYLE = "icon-primary";
    private static final String HOVER_STYLE_CLASS = "hover";
    private static final String DRAG_PAYLOAD = "DATABASEOBJECTDRAG";
    private boolean hovered;

    private final Label nameLabel = new Label();
    private final SVGPath nodeIcon = new SVGPath();
    private final Group nodeIconGroup = new Group(nodeIcon);
    private final StackPane nodeIconStackpane = new StackPane(nodeIconGroup);
    private final HBox graphicHbox = new HBox();
    private final Region spacer = new Region();

    public CustomSshTreeCell() {
        graphicHbox.setAlignment(Pos.CENTER_LEFT);
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
        resetCellVisualState();
        if (item == null || empty) {
            return;
        }
        if (item instanceof SshFolder) {
            renderSshFolder(item);
        } else if (item instanceof SshConnect sshConnect) {
            renderSshConnect(sshConnect, item);
            configureDragActions(item);
        }
    }

    private void renderSshFolder(TreeData item) {
        javafx.scene.control.TreeItem<TreeData> treeItem = getTreeItem();
        // Always show disclosure arrow for folders (even empty ones),
        // matching ConnectFolder behavior in TreeViewBuilder.createTreeItem().
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
        textProperty().unbind();
        textProperty().bind(item.nameProperty());
        setGraphic(nodeIconStackpane);
    }

    private void renderSshConnect(SshConnect sshConnect, TreeData item) {
        nodeIconStackpane.setMinSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
        nodeIconStackpane.setPrefSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
        nodeIconStackpane.setMaxSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
        nodeIconStackpane.setAlignment(Pos.CENTER);

        // Use the SSH tab icon (same as left sidebar SSH tab icon)
        nodeIcon.setContent(IconPaths.SSH_TAB_TOGGLE);
        nodeIcon.setScaleX(0.66);
        nodeIcon.setScaleY(0.66);
        applyPrimaryIconStyle(nodeIcon);

        nameLabel.textProperty().unbind();
        nameLabel.textProperty().bind(item.nameProperty());
        graphicHbox.getChildren().clear();
        graphicHbox.getChildren().addAll(nodeIconStackpane, nameLabel);
        setGraphic(graphicHbox);

        // Tooltip with SSH connection info
        javafx.scene.control.Tooltip tooltip = new javafx.scene.control.Tooltip();
        tooltip.textProperty().bind(Bindings.concat(
                "HOST: ", sshConnect.hostProperty(),
                "\nPORT: ", sshConnect.portProperty(),
                "\nUSER: ", sshConnect.usernameProperty()));
        setTooltip(tooltip);
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

    /** Drag source: SSH connections can be dragged to the tab pane to open. */
    private void configureDragActions(TreeData item) {
        setOnDragDetected(null);
        setOnDragDone(null);

        if (!(item instanceof SshConnect)) return;

        setOnDragDetected(event -> {
            if (getItem() == null) return;
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            Image dragImage = this.snapshot(params, null);
            Dragboard db = startDragAndDrop(TransferMode.COPY_OR_MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(DRAG_PAYLOAD);
            db.setContent(content);
            db.setDragView(dragImage, event.getX(), event.getY());
            event.consume();
        });

        setOnDragDone(event -> {
            TransferMode mode = event.getTransferMode();
            if (mode != TransferMode.MOVE && mode != TransferMode.COPY) return;
            // Use the SSH tab helper directly (same as double-click)
            javafx.application.Platform.runLater(() ->
                com.dbboys.infra.util.TabpaneUtil.addCustomSshTab((SshConnect) getItem()));
        });
    }
}
