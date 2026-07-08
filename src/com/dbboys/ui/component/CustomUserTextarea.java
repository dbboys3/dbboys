package com.dbboys.ui.component;

import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.MenuItemUtil;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;

public class CustomUserTextarea extends TextArea {
    public CustomUserTextarea() {
        super();

        CustomShortcutMenuItem copyMenuItem = MenuItemUtil.createMenuItemI18n(
                "menu.copy",
                "Ctrl+C",
                IconFactory.group(IconPaths.COPY, 0.7)
        );
        CustomShortcutMenuItem cutMenuItem = MenuItemUtil.createMenuItemI18n(
                "menu.cut",
                "Ctrl+X",
                IconFactory.group(IconPaths.CUT, 0.65)
        );
        CustomShortcutMenuItem pasteMenuItem = MenuItemUtil.createMenuItemI18n(
                "menu.paste",
                "Ctrl+V",
                IconFactory.group(IconPaths.PASTE, 0.65)
        );

        copyMenuItem.setOnAction(event -> copy());
        cutMenuItem.setOnAction(event -> cut());
        pasteMenuItem.setOnAction(event -> paste());

        ContextMenu contextMenu = new CustomContextMenu(copyMenuItem, cutMenuItem, pasteMenuItem);
        contextMenu.setOnShowing(event -> {
            boolean hasSelection = !getSelectedText().isEmpty();
            boolean clipboardHasText = Clipboard.getSystemClipboard().hasString();
            boolean editable = isEditable();

            copyMenuItem.setDisable(!hasSelection);
            cutMenuItem.setDisable(!editable || !hasSelection);
            pasteMenuItem.setDisable(!editable || !clipboardHasText);
        });

        setContextMenu(contextMenu);
    }
}
