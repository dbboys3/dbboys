package com.dbboys.ui.component;

import com.dbboys.model.SshFolder;
import com.dbboys.model.TreeData;
import com.dbboys.ssh.SshConnect;
import com.dbboys.ui.icon.IconPaths;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Custom TreeCell for the SSH tree view. Renders SshFolder and SshConnect items
 * with appropriate icons, mimicking the database connection tree styling.
 */
public class CustomSshTreeCell extends TreeCell<TreeData> {

    private static final int ICON_SLOT_SIZE = 16;
    private static final String PRIMARY_ICON_STYLE = "icon-primary";

    private final Label nameLabel = new Label();
    private final SVGPath nodeIcon = new SVGPath();
    private final Group nodeIconGroup = new Group(nodeIcon);
    private final StackPane nodeIconStackpane = new StackPane(nodeIconGroup);
    private final HBox graphicHbox = new HBox();
    private final Region spacer = new Region();

    public CustomSshTreeCell() {
        graphicHbox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);
    }

    @Override
    protected void updateItem(TreeData item, boolean empty) {
        super.updateItem(item, empty);
        resetCellVisualState();
        if (item == null || empty) {
            return;
        }
        if (item instanceof SshFolder) {
            renderSshFolder(item);
        } else if (item instanceof SshConnect sshConnect) {
            renderSshConnect(sshConnect, item);
        }
    }

    private void renderSshFolder(TreeData item) {
        javafx.scene.control.TreeItem<TreeData> treeItem = getTreeItem();
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
}
