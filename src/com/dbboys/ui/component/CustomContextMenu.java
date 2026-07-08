package com.dbboys.ui.component;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

public class CustomContextMenu extends ContextMenu {

    public CustomContextMenu() {
        super();
        clearFocusAfterShow();
    }

    public CustomContextMenu(MenuItem... items) {
        super(items);
        clearFocusAfterShow();
    }

    private void clearFocusAfterShow() {
        setOnShown(e -> Platform.runLater(() -> {
            if (getSkin() != null) {
                getSkin().getNode().requestFocus();
            }
        }));
    }
}
