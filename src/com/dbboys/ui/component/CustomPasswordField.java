package com.dbboys.ui.component;

import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.MenuItemUtil;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.PasswordField;
import javafx.scene.input.Clipboard;

public class CustomPasswordField extends PasswordField {


    public CustomPasswordField() {
        super();

        CustomShortcutMenuItem pasteMenuItem = MenuItemUtil.createMenuItemI18n(
                "passwordfield.menu.paste",
                "Ctrl+V",
                IconFactory.group(IconPaths.PASTE, 0.65)
        );

        ContextMenu contextMenu = new CustomContextMenu();
        contextMenu.getItems().add(pasteMenuItem);
        setContextMenu(contextMenu);

        pasteMenuItem.setOnAction(event -> {
            paste();
        });

        contextMenu.setOnShowing(event -> {
            pasteMenuItem.setDisable(!Clipboard.getSystemClipboard().hasString());
        });
    }

}
