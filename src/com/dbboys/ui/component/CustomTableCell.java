package com.dbboys.ui.component;

import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.MenuItemUtil;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Duration;

public class CustomTableCell<S, T> extends TableCell<S, T> {
    private static final String NEWLINE_SYMBOL = "\u21B5";
    private static final int DOUBLE_CLICK_INTERVAL_MS = 300;
    private static final String NULL_FALLBACK = "[NULL]";
    private static final String STYLE_CLASS_EDITING = "table-cell-editing";
    private static final String STYLE_CLASS_NULL = "custom-table-cell-null";
    private static final String STYLE_CLASS_EDITOR = "custom-table-cell-editor";

    private CustomUserTextField textField;
    //结果集拖动鼠标框选
    private static int resultSelectRow1 = 0;
    private static int resultSelectRow2 = 0;
    private static int resultSelectCol1 = 0;
    private static int resultSelectCol2 = 0;
    private static int resultSelectStartRow = 0;
    private static int resultSelectEndRow = 0;
    private static int resultSelectStartCol = 0;
    private static int resultSelectEndCol = 0;
    private static long lastClickTime = 0;



    @Override
    public void startEdit() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < DOUBLE_CLICK_INTERVAL_MS) {
            // 如果两次点击间隔小于300ms，认为是双击
            super.startEdit();
            if (!isEditing()) {
                return;
            }
            if (!getStyleClass().contains(STYLE_CLASS_EDITING)) {
                getStyleClass().add(STYLE_CLASS_EDITING);
            }
            beginTextFieldEditing(formatDisplayValue(getItem()));
        }
        lastClickTime = currentTime;

    }


    @Override
    public void cancelEdit() {
        getStyleClass().remove(STYLE_CLASS_EDITING);
        super.cancelEdit();

        setText(formatDisplayValue(getItem()));
        setGraphic(null);
    }

    @Override
    public void commitEdit(T newValue) {
        getStyleClass().remove(STYLE_CLASS_EDITING);
        super.commitEdit(newValue);
    }

    @Override
    protected void updateItem(T item, boolean empty) {
        super.updateItem(item, empty);
        setText("");
        getStyleClass().remove(STYLE_CLASS_NULL);
        setTooltip(null);
        if (empty) {
        } else if (item == null) {
            setText(getNullLabel());
            getStyleClass().add(STYLE_CLASS_NULL);
        } else if (isEditing()) {
            if (textField != null) {
                syncTextFieldWhileEditing(formatDisplayValue(item));
            } else {
                setText(null);
                setGraphic(null);
            }
        }
        else{
            //text.setText(item.toString()); // 设置带换行符的内容
            //setGraphic(text);
            setText(item.toString().replace("\n", NEWLINE_SYMBOL));
            //setTooltip(new Tooltip(item.toString().replaceAll("\u21B5","\n")));
            setGraphic(null);
        }

    }




    private void beginTextFieldEditing(String editText) {
        if (textField == null) {
            createTextField();
        }
        String value = editText == null ? "" : editText;
        textField.setText(value);
        setText(null);
        setGraphic(textField);
        textField.requestFocus();
        selectAllWithCaretAtEndAfterFocus(textField);
    }

    private void syncTextFieldWhileEditing(String editText) {
        if (textField == null) {
            return;
        }
        String value = editText == null ? "" : editText;
        if (!value.equals(textField.getText())) {
            textField.setText(value);
        }
        setText(null);
        setGraphic(textField);
    }

    private static void selectAllWithCaretAtEndAfterFocus(TextField field) {
        if (field == null) {
            return;
        }
        Platform.runLater(() -> selectAllWithCaretAtEnd(field));
        PauseTransition delay = new PauseTransition(Duration.millis(50));
        delay.setOnFinished(event -> selectAllWithCaretAtEnd(field));
        delay.play();
    }

    private static void selectAllWithCaretAtEnd(TextField field) {
        if (field == null) {
            return;
        }
        field.requestFocus();
        String text = field.getText();
        if (text == null || text.isEmpty()) {
            field.positionCaret(0);
            return;
        }
        field.selectRange(0, text.length());
    }

    private void createTextField() {
        textField = new CustomUserTextField();
        textField.getStyleClass().add(STYLE_CLASS_EDITOR);
        textField.setOnAction(event -> {
            commitEdit((T) textField.getText());
        });

        /*

        textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                commitEdit((Object) textField.getText().toString());
            }
        });

         */




    }

    public CustomTableCell() {
        ContextMenu contextMenu = new CustomContextMenu();
        CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n(
                "customtablecell.menu.copy",
                "Ctrl+C",
                IconFactory.group(IconPaths.COPY, 0.7)
        );
        copyItem.setOnAction(event -> copyCellValue());
        contextMenu.getItems().add(copyItem);
        setContextMenu(contextMenu);
        contextMenu.setOnShowing(event -> copyItem.setDisable(isEmpty()));

        setOnDragDetected(event -> {
            startFullDrag(); // 不执行任何拖动任务
            getScene().setCursor(Cursor.CROSSHAIR);
            resultSelectRow1 = getIndex();
            resultSelectCol1 = getTableView().getColumns().indexOf(getTableColumn());
            event.consume();
        });

        setOnMouseDragEntered(event -> {
            resultSelectRow2 = getIndex();
            resultSelectCol2 = getTableView().getColumns().indexOf(getTableColumn());
            resultSelectStartRow = Math.min(resultSelectRow1, resultSelectRow2);
            resultSelectStartCol = Math.min(resultSelectCol1, resultSelectCol2);
            resultSelectEndRow = Math.max(resultSelectRow1, resultSelectRow2);
            resultSelectEndCol = Math.max(resultSelectCol1, resultSelectCol2);
            getTableView().getSelectionModel().clearSelection();
            for (int i = resultSelectStartRow; i <= resultSelectEndRow; i++) {
                for (int j = resultSelectStartCol; j <= resultSelectEndCol; j++) {
                    getTableView().getSelectionModel().select(i, getTableView().getColumns().get(j));
                }
            }
        });

        setOnMouseReleased(event -> {
            getScene().setCursor(Cursor.DEFAULT);
            event.consume(); // 消费事件
        });
        // 单击更新点击事件，用于下次单击时判断两次单击事件间隔，来确定是否进入编辑模式，在startedit里判断
        setOnMouseClicked(event -> {
            lastClickTime = System.currentTimeMillis();
        });


        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT) {
                // 可选择是否消费（阻止父节点响应）
                event.consume();
            }
        });

    }

    private void copyCellValue() {
        T item = getItem();
        if (isEmpty()) {
            return;
        }
        TableView<S> tableView = getTableView();
        if (tableView != null && tableView.getSelectionModel().getSelectedCells().size() > 1) {
            CustomTableView.copySelectionToClipboard(tableView);
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(item == null ? getNullLabel() : item.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private String getNullLabel() {
        return I18n.t("customtablecell.null", NULL_FALLBACK);
    }

    private String formatDisplayValue(T item) {
        return item == null ? getNullLabel() : item.toString().replace("\n", NEWLINE_SYMBOL);
    }

    protected CustomUserTextField getTextField() {
        return textField;
    }
}
