package com.dbboys.ui.component;

import com.dbboys.app.AppState;
import javafx.geometry.Insets;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * Applies the TreeCell hover color without changing its style class. Mutating a
 * cell's style class can invalidate its preferred width and make VirtualFlow
 * toggle the horizontal scrollbar while the pointer moves between rows.
 */
final class TreeCellHoverSupport {
    private static final Object COLOR_PROBE_KEY = new Object();
    private static final Object COLOR_THEME_KEY = new Object();
    private static final Object COLOR_KEY = new Object();

    private final TreeCell<?> cell;
    private boolean hovered;
    private Background backgroundBeforeHover;

    TreeCellHoverSupport(TreeCell<?> cell) {
        this.cell = cell;
        cell.hoverProperty().addListener((obs, wasHovered, isHovered) ->
                setHovered(isHovered));
        cell.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            if (hovered) {
                backgroundBeforeHover = null;
            }
        });
    }

    void reset() {
        restoreBackground();
    }

    private void setHovered(boolean hovered) {
        if (!hovered) {
            restoreBackground();
            return;
        }
        if (this.hovered || cell.isEmpty()) {
            return;
        }
        Color color = resolveHoverColor(cell.getTreeView());
        if (color == null) {
            return;
        }
        this.hovered = true;
        backgroundBeforeHover = cell.getBackground();
        cell.setBackground(new Background(
                new BackgroundFill(color, CornerRadii.EMPTY, Insets.EMPTY)));
    }

    private void restoreBackground() {
        if (!hovered) {
            return;
        }
        hovered = false;
        Background background = backgroundBeforeHover;
        backgroundBeforeHover = null;
        cell.setBackground(background);
        if (background == null) {
            cell.applyCss();
        }
    }

    private static Color resolveHoverColor(TreeView<?> treeView) {
        if (treeView == null) {
            return null;
        }
        String theme = AppState.getCurrentTheme();
        if (theme.equals(treeView.getProperties().get(COLOR_THEME_KEY))) {
            Object color = treeView.getProperties().get(COLOR_KEY);
            return color instanceof Color value ? value : null;
        }

        Region probe = (Region) treeView.getProperties().get(COLOR_PROBE_KEY);
        if (probe == null) {
            if (!(treeView.getParent() instanceof Pane parent)) {
                return null;
            }
            probe = new Region();
            probe.setManaged(false);
            probe.setMouseTransparent(true);
            probe.setOpacity(0);
            probe.getStyleClass().add("tree-hover-color-probe");
            int treeIndex = parent.getChildren().indexOf(treeView);
            parent.getChildren().add(Math.max(0, treeIndex), probe);
            treeView.getProperties().put(COLOR_PROBE_KEY, probe);
        }

        probe.applyCss();
        Background background = probe.getBackground();
        if (background == null || background.getFills().isEmpty()) {
            return null;
        }
        Color color = background.getFills().get(0).getFill() instanceof Color value
                ? value
                : null;
        if (color != null) {
            treeView.getProperties().put(COLOR_THEME_KEY, theme);
            treeView.getProperties().put(COLOR_KEY, color);
        }
        return color;
    }
}
