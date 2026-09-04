package com.dbboys.ui.component;
import com.dbboys.ui.util.MenuItemUtil;


import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.*;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.Clipboard;

import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.LineNumberFactory;




public class CustomInlineCssTextArea extends InlineCssTextArea {
    private CustomShortcutMenuItem copyItem ;
    private CustomShortcutMenuItem cutItem ;
    private CustomShortcutMenuItem pasteItem ;
    public ContextMenu inlineCssMenu = new CustomContextMenu();

    public CustomInlineCssTextArea() {
        super();
        setEditable(false);
        getStyleClass().add("code-area");
        copyItem = MenuItemUtil.createMenuItemI18n(
                "genericstyled.menu.copy",
                "Ctrl+C",
                IconFactory.group(IconPaths.COPY, 0.7)
        );
        cutItem = MenuItemUtil.createMenuItemI18n(
                "menu.cut",
                "Ctrl+X",
                IconFactory.group(IconPaths.CUT, 0.65)
        );
        pasteItem = MenuItemUtil.createMenuItemI18n(
                "menu.paste",
                "Ctrl+V",
                IconFactory.group(IconPaths.PASTE, 0.65)
        );

        setContextMenu(inlineCssMenu);

        inlineCssMenu.getItems().addAll(copyItem, cutItem, pasteItem);
        inlineCssMenu.setOnShowing((event) -> {
            boolean hasSelection = !getSelectedText().isEmpty();
            boolean editable = isEditable();
            boolean clipboardHasText = Clipboard.getSystemClipboard().hasString();
            copyItem.setDisable(!hasSelection);
            cutItem.setDisable(!editable || !hasSelection);
            pasteItem.setDisable(!editable || !clipboardHasText);
        });
        copyItem.setOnAction(event -> {
            if(!getSelectedText().isEmpty()){
                copy();
            }
        });
        //RichTextFX的cut()/paste()不检查editable，只读区域需自行拦截
        cutItem.setOnAction(event -> {
            if(isEditable() && !getSelectedText().isEmpty()){
                cut();
            }
        });
        pasteItem.setOnAction(event -> {
            if(isEditable()){
                paste();
            }
        });

        setParagraphGraphicFactory(LineNumberFactory.get(this));


    }



}
