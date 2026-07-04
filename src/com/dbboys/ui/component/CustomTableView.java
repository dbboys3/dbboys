package com.dbboys.ui.component;

import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.MenuItemUtil;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CustomTableView<S> extends TableView<S> {

    public final CustomShortcutMenuItem copyMenuItem;
    public final Menu generateSqlMenu;
    public final CustomShortcutMenuItem generateInsertSqlMenuItem;
    public final CustomShortcutMenuItem generateUpdateSqlMenuItem;
    public final CustomShortcutMenuItem generateDeleteSqlMenuItem;
    public final CustomShortcutMenuItem generateSelectSqlMenuItem;

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

        copyMenuItem = MenuItemUtil.createMenuItemI18n(
                "resultset.table.menu.copy",
                "Ctrl+C",
                IconFactory.group(IconPaths.COPY, 0.7)
        );
        copyMenuItem.setOnAction(e -> copySelectionToClipboard());

        generateInsertSqlMenuItem = MenuItemUtil.createMenuItemI18n(
                "resultset.table.menu.generateInsertSql",
                null
        );
        generateUpdateSqlMenuItem = MenuItemUtil.createMenuItemI18n(
                "resultset.table.menu.generateUpdateSql",
                null
        );
        generateDeleteSqlMenuItem = MenuItemUtil.createMenuItemI18n(
                "resultset.table.menu.generateDeleteSql",
                null
        );
        generateSelectSqlMenuItem = MenuItemUtil.createMenuItemI18n(
                "resultset.table.menu.generateSelectSql",
                null
        );

        generateSqlMenu = new CustomMenu();
        generateSqlMenu.textProperty().bind(com.dbboys.infra.i18n.I18n.bind("resultset.table.menu.generateSql", "生成SQL"));
        generateSqlMenu.setGraphic(IconFactory.group(IconPaths.METADATA_DDL_MENU, 0.65, 0.65));
        generateSqlMenu.getGraphic().setTranslateY(2);
        generateSqlMenu.getItems().addAll(
                generateInsertSqlMenuItem,
                generateUpdateSqlMenuItem,
                generateDeleteSqlMenuItem,
                generateSelectSqlMenuItem
        );

        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getStyleClass().add("custom-context-menu");
        contextMenu.getItems().addAll(
                copyMenuItem,
                new SeparatorMenuItem(),
                generateSqlMenu
        );
        setContextMenu(contextMenu);

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.C) {
                copySelectionToClipboard();
                event.consume();
            }
        });

        contextMenu.setOnShowing(e -> {
            boolean hasSelection = !getSelectionModel().getSelectedCells().isEmpty();
            copyMenuItem.setDisable(!hasSelection);
        });
    }

    public void copySelectionToClipboard() {
        copySelectionToClipboard(this);
    }

    static <T> void copySelectionToClipboard(TableView<T> table) {
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
