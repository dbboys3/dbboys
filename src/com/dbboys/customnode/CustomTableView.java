package com.dbboys.customnode;

import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.MenuItemUtil;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CustomTableView<S> extends TableView<S> {
    public CustomTableView() {
        super();
        TableColumn<S, Object> rowNumberColumn = new TableColumn<>("");
        rowNumberColumn.setCellFactory(new Callback<>() {
            @Override
            public TableCell<S, Object> call(TableColumn<S, Object> param) {
                return new TableCell<>() {
                    @Override
                    protected void updateItem(Object item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty) {
                            setText(null); // 空行不显示行号
                        } else {
                            setText(String.valueOf(getIndex() + 1)); // 行号从 1 开始
                            setOnMouseClicked(event -> {
                                int rowIndex = getIndex();
                                getSelectionModel().clearAndSelect(rowIndex);
                            });
                        }
                    }
                };
            }
        });

        rowNumberColumn.setGraphic(IconFactory.group(IconPaths.RESULTSET_ROW_NUMBER, 0.65));


        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        getSelectionModel().setCellSelectionEnabled(true);
        setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        rowNumberColumn.setSortable(false);
        rowNumberColumn.setPrefWidth(30);
        getColumns().add(rowNumberColumn);

        ContextMenu contextMenu = new ContextMenu();
        CustomShortcutMenuItem copyMenuItem = MenuItemUtil.createMenuItemI18n(
                "resultset.table.menu.copy",
                "Ctrl+C",
                IconFactory.group(IconPaths.COPY, 0.7)
        );
        copyMenuItem.setOnAction(e -> copySelectionToClipboard(this));
        contextMenu.getItems().add(copyMenuItem);
        setContextMenu(contextMenu);

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.C) {
                copySelectionToClipboard(this);
                event.consume();
            }
        });

        contextMenu.setOnShowing(e -> {
            boolean hasSelection = !getSelectionModel().getSelectedCells().isEmpty();
            copyMenuItem.setDisable(!hasSelection); // 没有选中则禁用
        });
    }

    private <T> void copySelectionToClipboard(TableView<T> table) {
        ObservableList<TablePosition> selectedCells = table.getSelectionModel().getSelectedCells();
        if (selectedCells.isEmpty()) return;

        List<TablePosition> posList = new ArrayList<>(selectedCells);
        posList.sort(
                Comparator.comparingInt((TablePosition pos) -> pos.getRow())
                        .thenComparingInt(pos -> pos.getColumn())
        );

        StringBuilder sb = new StringBuilder();
        int prevRow = -1;

        for (int i = 0; i < posList.size(); i++) {
            TablePosition<?, ?> pos = posList.get(i);
            int row = pos.getRow();
            int col = pos.getColumn();

            Object cell = table.getColumns().get(col).getCellData(row);
            if (cell == null) cell = "";

            if (prevRow == row) {
                sb.append('\t');
            } else if (prevRow != -1) {
                sb.append('\n');
            }

            sb.append(cell.toString());
            prevRow = row;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }
}
