package com.dbboys.ui.component;

import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.MenuItemUtil;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import java.util.function.BooleanSupplier;

public class CustomMarkdownEditCodeArea extends CodeArea {
    private Runnable onSaveRequest = () -> {};
    private Runnable onContentDirty = () -> {};
    private Runnable onSearchRequest = () -> {};
    private Runnable onReplaceRequest = () -> {};
    private BooleanSupplier saveDisabledSupplier = () -> false;
    private boolean suppressDirtyCallback = false;

    public CustomShortcutMenuItem viewItem = MenuItemUtil.createMenuItemI18n(
            "markdown.menu.save_and_preview",
            "Ctrl+Enter",
            IconFactory.group(IconPaths.MARKDOWN_SAVE_PREVIEW, 0.6)
    );
    public CustomShortcutMenuItem codeAreaPasteItem = MenuItemUtil.createMenuItemI18n(
            "menu.paste",
            "Ctrl+V",
            IconFactory.group(IconPaths.PASTE, 0.65)
    );

    public CustomMarkdownEditCodeArea() {
        super();

        CustomShortcutMenuItem searchReplaceItem = MenuItemUtil.createMenuItemI18n(
                "markdown.menu.search_replace",
                "Ctrl+F/R",
                IconFactory.group(IconPaths.MAIN_SEARCH, 0.6)
        );
        CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n(
                "menu.copy",
                "Ctrl+C",
                IconFactory.group(IconPaths.COPY, 0.7)
        );
        CustomShortcutMenuItem cutItem = MenuItemUtil.createMenuItemI18n(
                "menu.cut",
                "Ctrl+X",
                IconFactory.group(IconPaths.CUT, 0.6)
        );
        CustomShortcutMenuItem undoItem = MenuItemUtil.createMenuItemI18n(
                "markdown.menu.undo",
                "Ctrl+Z",
                IconFactory.group(IconPaths.UNDO, 0.6)
        );
        CustomShortcutMenuItem redoItem = MenuItemUtil.createMenuItemI18n(
                "markdown.menu.redo",
                "Ctrl+Y",
                IconFactory.group(IconPaths.REDO, 0.6)
        );
        CustomShortcutMenuItem saveItem = MenuItemUtil.createMenuItemI18n(
                "markdown.menu.save",
                "Ctrl+S",
                IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.6)
        );

        ContextMenu codeAreaMenu = new CustomContextMenu(
                viewItem, searchReplaceItem, copyItem, cutItem, codeAreaPasteItem, undoItem, redoItem, saveItem
        );
        setContextMenu(codeAreaMenu);

        searchReplaceItem.setOnAction(event -> onSearchRequest.run());
        copyItem.setOnAction(event -> copy());
        cutItem.setOnAction(event -> cut());
        codeAreaPasteItem.setOnAction(event -> paste());
        undoItem.setOnAction(event -> undo());
        redoItem.setOnAction(event -> redo());
        saveItem.setOnAction(event -> requestSave());

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.V) {
                event.consume();
                codeAreaPasteItem.fire();
            }
        });

        setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                viewItem.fire();
            }
            if (event.isControlDown() && event.getCode() == KeyCode.S) {
                requestSave();
            }
            if (event.isControlDown() && event.getCode() == KeyCode.F) {
                onSearchRequest.run();
            }
            if (event.isControlDown() && event.getCode() == KeyCode.R) {
                onReplaceRequest.run();
            }
        });

        codeAreaMenu.setOnShowing(event -> {
            boolean hasSelection = !getSelectedText().isEmpty();
            boolean editable = isEditable();
            copyItem.setDisable(!hasSelection);
            cutItem.setDisable(!editable || !hasSelection);
            codeAreaPasteItem.setDisable(!editable || !Clipboard.getSystemClipboard().hasString());
            saveItem.setDisable(saveDisabledSupplier.getAsBoolean());
        });

        // 设置行号
        setParagraphGraphicFactory(LineNumberFactory.get(this));

        // 监听输入变化
        richChanges().subscribe(change -> {
            if (!suppressDirtyCallback) {
                onContentDirty.run();
            }
        });
    }

    public void setOnSaveRequest(Runnable onSaveRequest) {
        this.onSaveRequest = onSaveRequest == null ? () -> {} : onSaveRequest;
    }

    public void setOnContentDirty(Runnable onContentDirty) {
        this.onContentDirty = onContentDirty == null ? () -> {} : onContentDirty;
    }

    public void setOnSearchRequest(Runnable onSearchRequest) {
        this.onSearchRequest = onSearchRequest == null ? () -> {} : onSearchRequest;
    }

    public void setOnReplaceRequest(Runnable onReplaceRequest) {
        this.onReplaceRequest = onReplaceRequest == null ? () -> {} : onReplaceRequest;
    }

    public void setSaveDisabledSupplier(BooleanSupplier saveDisabledSupplier) {
        this.saveDisabledSupplier = saveDisabledSupplier == null ? () -> false : saveDisabledSupplier;
    }

    public void replaceTextWithoutDirty(String text) {
        suppressDirtyCallback = true;
        try {
            replaceText(text);
        } finally {
            suppressDirtyCallback = false;
        }
    }

    private void requestSave() {
        onSaveRequest.run();
    }
}
