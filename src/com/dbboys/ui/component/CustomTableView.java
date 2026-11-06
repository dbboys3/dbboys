package com.dbboys.ui.component;

import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.util.MenuItemUtil;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.util.Callback;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CustomTableView<S> extends TableView<S> {
    private static final double DRAG_AUTO_SCROLL_EDGE = 24;
    private static final Duration DRAG_AUTO_SCROLL_INTERVAL = Duration.millis(55);

    public final CustomShortcutMenuItem copyMenuItem;
    /** Created here for consistent i18n/icon, but not added to the context menu by default:
     *  paste only makes sense for editable tables (the result set tab wires it up itself). */
    public final CustomShortcutMenuItem pasteMenuItem;
    public final Menu generateSqlMenu;
    public final CustomShortcutMenuItem generateInsertSqlMenuItem;
    public final CustomShortcutMenuItem generateUpdateSqlMenuItem;
    public final CustomShortcutMenuItem generateDeleteSqlMenuItem;
    public final CustomShortcutMenuItem generateSelectSqlMenuItem;

    private int dragAnchorRow = -1;
    private int dragAnchorColumn = -1;
    private int dragCurrentRow = -1;
    private int dragCurrentColumn = -1;
    private int dragSelectedStartRow = -1;
    private int dragSelectedEndRow = -1;
    private int dragSelectedStartColumn = -1;
    private int dragSelectedEndColumn = -1;
    private int dragAutoScrollDirection;
    private Timeline dragAutoScrollTimeline;

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
                            setOnMouseClicked(null);
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

        pasteMenuItem = MenuItemUtil.createMenuItemI18n(
                "resultset.table.menu.paste",
                "Ctrl+V",
                IconFactory.group(IconPaths.PASTE, 0.62)
        );

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

        ContextMenu contextMenu = new CustomContextMenu();
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

        addEventFilter(MouseEvent.MOUSE_DRAGGED, this::trackCellDrag);
        addEventFilter(MouseDragEvent.MOUSE_DRAG_OVER, this::trackCellDrag);
        addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (dragAnchorRow >= 0) {
                finishCellDrag();
            }
        });
        addEventFilter(MouseDragEvent.MOUSE_DRAG_RELEASED, event -> finishCellDrag());
    }

    void beginCellDrag(int row, int column) {
        if (row < 0 || column < 0) {
            return;
        }
        finishCellDrag();
        getSelectionModel().clearSelection();
        dragAnchorRow = row;
        dragAnchorColumn = column;
        dragCurrentRow = row;
        dragCurrentColumn = column;
        updateCellDragSelection();
    }

    void extendCellDrag(int row, int column) {
        if (dragAnchorRow < 0 || row < 0 || column < 0) {
            return;
        }
        dragCurrentRow = row;
        dragCurrentColumn = column;
        updateCellDragSelection();
    }

    void finishCellDrag() {
        dragAutoScrollDirection = 0;
        if (dragAutoScrollTimeline != null) {
            dragAutoScrollTimeline.stop();
        }
        dragAnchorRow = -1;
        dragAnchorColumn = -1;
        dragCurrentRow = -1;
        dragCurrentColumn = -1;
        resetDragSelectedRange();
        if (getScene() != null) {
            getScene().setCursor(javafx.scene.Cursor.DEFAULT);
        }
    }

    private void trackCellDrag(MouseEvent event) {
        if (dragAnchorRow < 0 || !event.isPrimaryButtonDown()) {
            return;
        }
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        int direction = local.getY() < DRAG_AUTO_SCROLL_EDGE
                ? -1
                : local.getY() > getHeight() - DRAG_AUTO_SCROLL_EDGE ? 1 : 0;
        setDragAutoScrollDirection(direction);
    }

    private void setDragAutoScrollDirection(int direction) {
        if (dragAutoScrollDirection == direction) {
            return;
        }
        dragAutoScrollDirection = direction;
        if (direction == 0) {
            if (dragAutoScrollTimeline != null) {
                dragAutoScrollTimeline.stop();
            }
            return;
        }
        if (dragAutoScrollTimeline == null) {
            dragAutoScrollTimeline = new Timeline(new KeyFrame(DRAG_AUTO_SCROLL_INTERVAL, event ->
                    extendCellDragByRow(dragAutoScrollDirection)));
            dragAutoScrollTimeline.setCycleCount(Animation.INDEFINITE);
        }
        dragAutoScrollTimeline.playFromStart();
    }

    private void extendCellDragByRow(int direction) {
        if (direction == 0 || dragAnchorRow < 0 || getItems() == null || getItems().isEmpty()) {
            return;
        }
        int nextRow = Math.max(0, Math.min(getItems().size() - 1, dragCurrentRow + direction));
        if (nextRow == dragCurrentRow) {
            return;
        }
        dragCurrentRow = nextRow;
        updateCellDragSelection();
        scrollTo(nextRow);
    }

    private void updateCellDragSelection() {
        int startRow = Math.min(dragAnchorRow, dragCurrentRow);
        int endRow = Math.max(dragAnchorRow, dragCurrentRow);
        int startColumn = Math.min(dragAnchorColumn, dragCurrentColumn);
        int endColumn = Math.max(dragAnchorColumn, dragCurrentColumn);
        if (startRow == dragSelectedStartRow
                && endRow == dragSelectedEndRow
                && startColumn == dragSelectedStartColumn
                && endColumn == dragSelectedEndColumn) {
            return;
        }

        TableViewSelectionModel<S> selectionModel = getSelectionModel();
        if (dragSelectedStartRow >= 0) {
            updateRectDifference(selectionModel,
                    dragSelectedStartRow, dragSelectedEndRow,
                    dragSelectedStartColumn, dragSelectedEndColumn,
                    startRow, endRow, startColumn, endColumn,
                    false);
        }
        updateRectDifference(selectionModel,
                startRow, endRow, startColumn, endColumn,
                dragSelectedStartRow, dragSelectedEndRow,
                dragSelectedStartColumn, dragSelectedEndColumn,
                true);
        dragSelectedStartRow = startRow;
        dragSelectedEndRow = endRow;
        dragSelectedStartColumn = startColumn;
        dragSelectedEndColumn = endColumn;
    }

    private void updateRectDifference(TableViewSelectionModel<S> selectionModel,
                                      int baseStartRow, int baseEndRow,
                                      int baseStartColumn, int baseEndColumn,
                                      int otherStartRow, int otherEndRow,
                                      int otherStartColumn, int otherEndColumn,
                                      boolean select) {
        if (baseStartRow < 0 || baseStartColumn < 0) {
            return;
        }
        int overlapStartRow = Math.max(baseStartRow, otherStartRow);
        int overlapEndRow = Math.min(baseEndRow, otherEndRow);
        int overlapStartColumn = Math.max(baseStartColumn, otherStartColumn);
        int overlapEndColumn = Math.min(baseEndColumn, otherEndColumn);
        if (overlapStartRow > overlapEndRow || overlapStartColumn > overlapEndColumn) {
            updateCellRectangle(selectionModel, baseStartRow, baseEndRow,
                    baseStartColumn, baseEndColumn, select);
            return;
        }
        if (otherStartColumn > baseStartColumn) {
            updateCellRectangle(selectionModel, baseStartRow, baseEndRow,
                    baseStartColumn, Math.min(baseEndColumn, otherStartColumn - 1), select);
        }
        if (otherEndColumn < baseEndColumn) {
            updateCellRectangle(selectionModel, baseStartRow, baseEndRow,
                    Math.max(baseStartColumn, otherEndColumn + 1), baseEndColumn, select);
        }
        if (otherStartRow > baseStartRow) {
            updateCellRectangle(selectionModel, baseStartRow, Math.min(baseEndRow, otherStartRow - 1),
                    overlapStartColumn, overlapEndColumn, select);
        }
        if (otherEndRow < baseEndRow) {
            updateCellRectangle(selectionModel, Math.max(baseStartRow, otherEndRow + 1), baseEndRow,
                    overlapStartColumn, overlapEndColumn, select);
        }
    }

    private void updateCellRectangle(TableViewSelectionModel<S> selectionModel,
                                     int startRow, int endRow,
                                     int startColumn, int endColumn,
                                     boolean select) {
        for (int row = startRow; row <= endRow; row++) {
            for (int column = startColumn; column <= endColumn; column++) {
                if (column >= 0 && column < getColumns().size()) {
                    TableColumn<S, ?> tableColumn = getColumns().get(column);
                    if (select) {
                        selectionModel.select(row, tableColumn);
                    } else {
                        selectionModel.clearSelection(row, tableColumn);
                    }
                }
            }
        }
    }

    private void resetDragSelectedRange() {
        dragSelectedStartRow = -1;
        dragSelectedEndRow = -1;
        dragSelectedStartColumn = -1;
        dragSelectedEndColumn = -1;
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
