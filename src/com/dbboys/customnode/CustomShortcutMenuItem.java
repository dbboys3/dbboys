package com.dbboys.customnode;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

public class CustomShortcutMenuItem extends CustomMenuItem {
    private static final String COMPACT_PADDING = "-fx-padding: 0;";
    private static final double DEFAULT_ROW_WIDTH = 200;
    private static final double DEFAULT_ICON_SLOT_WIDTH = 18;
    private static final double DEFAULT_ICON_TEXT_GAP = 2;  //文字与图标的间距
    private static final double DEFAULT_ICON_LEFT_PADDING = 2; //图标到左边框的间距
    private static final double SHORTCUT_NUDGE_RIGHT = -10;  //往右平移距离，保证快捷键靠右显示

    private final StackPane iconSlot = new StackPane();
    private final Label textLabel = new Label();
    private final Label shortcutLabel = new Label();

    
    public CustomShortcutMenuItem() {
        this("", "");
    }

    public CustomShortcutMenuItem(
            String text,
            String shortcut
    ) {
        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefWidth(DEFAULT_ROW_WIDTH);
        row.setStyle(COMPACT_PADDING);

        iconSlot.setAlignment(Pos.CENTER_LEFT);
        iconSlot.setMinWidth(DEFAULT_ICON_SLOT_WIDTH);
        iconSlot.setPrefWidth(DEFAULT_ICON_SLOT_WIDTH);
        iconSlot.setMaxWidth(DEFAULT_ICON_SLOT_WIDTH);
        iconSlot.setStyle(COMPACT_PADDING);
        HBox.setMargin(iconSlot, new Insets(0, DEFAULT_ICON_TEXT_GAP, 0, DEFAULT_ICON_LEFT_PADDING));

        textLabel.setStyle("-fx-padding: 0;-fx-min-width: 120;-fx-text-fill: -color-fg-default;");
        shortcutLabel.setStyle(COMPACT_PADDING + "-fx-text-fill: #888;");
        shortcutLabel.setTranslateX(SHORTCUT_NUDGE_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        row.getChildren().addAll(iconSlot, textLabel, spacer, shortcutLabel);
        setContent(row);
        setHideOnClick(true);

        textProperty().addListener((obs, oldVal, newVal) -> textLabel.setText(newVal == null ? "" : newVal));
        graphicProperty().addListener((obs, oldVal, newVal) -> setIconNode(newVal));
        setText(text);
        setShortcutText(shortcut);
        setIconNode(getGraphic());
        
    }
    

    public final void setShortcutText(String value) {
        shortcutLabel.setText(value == null ? "" : value);
    }

    private void setIconNode(Node icon) {
        iconSlot.getChildren().setAll(icon == null ? new Group() : icon);
    }
}
