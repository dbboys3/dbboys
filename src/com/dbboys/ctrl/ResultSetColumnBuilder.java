package com.dbboys.ctrl;

import com.dbboys.customnode.CustomInfoCodeArea;
import com.dbboys.customnode.CustomInfoStackPane;
import com.dbboys.customnode.CustomLostFocusCommitTableCell;
import com.dbboys.i18n.I18n;
import com.dbboys.util.AlertUtil;
import com.dbboys.util.CustomWindowFrameUtil;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Locale;

public class ResultSetColumnBuilder {
    private static final Logger log = LogManager.getLogger(ResultSetColumnBuilder.class);
    private static final double HEADER_BADGE_HORIZONTAL_INSET = 3.2;
    private static final String HEADER_KEY_BADGE = "resultset.header.keyBadge";
    private static final String HEADER_TOOLTIP = "resultset.header.tooltip";
    private static final String HEADER_NAME = "resultset.header.name";
    private static final String HEADER_TYPE = "resultset.header.type";
    private static final String HEADER_NAME_STYLE =
            "-fx-font-size: 11px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 0 0 0 " + HEADER_BADGE_HORIZONTAL_INSET + ";";
    private static final String HEADER_TYPE_BADGE_STYLE =
            "-fx-font-size: 7.2px;" +
            "-fx-text-fill: #6f8498;" +
            "-fx-border-color: #6f8498;" +
            "-fx-border-width: 0.8;" +
            "-fx-border-radius: 2.4;" +
            "-fx-background-radius: 2.4;" +
            "-fx-padding: 0 " + HEADER_BADGE_HORIZONTAL_INSET + " 0 " + HEADER_BADGE_HORIZONTAL_INSET + ";";
    private static final String HEADER_PRI_BADGE_STYLE =
            "-fx-font-size: 7.2px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: #9f453c;" +
            "-fx-border-color: #9f453c;" +
            "-fx-border-width: 0.8;" +
            "-fx-border-radius: 2.4;" +
            "-fx-background-radius: 2.4;" +
            "-fx-padding: 0 " + HEADER_BADGE_HORIZONTAL_INSET + " 0 " + HEADER_BADGE_HORIZONTAL_INSET + ";";
    /** Matches CSS in cupertino-dark.css: unsaved UPDATE cells use same success green as INSERT. */
    private static final String CELL_PENDING_UPDATE_STYLE = "resultset-pending-update-cell";

    private static final String HEADER_ROWID_BADGE_STYLE =
            "-fx-font-size: 7.2px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: #8f6d1d;" +
            "-fx-border-color: #8f6d1d;" +
            "-fx-border-width: 0.8;" +
            "-fx-border-radius: 2.4;" +
            "-fx-background-radius: 2.4;" +
            "-fx-padding: 0 " + HEADER_BADGE_HORIZONTAL_INSET + " 0 " + HEADER_BADGE_HORIZONTAL_INSET + ";";

    private final ResultSetTabController ctrl;

    public ResultSetColumnBuilder(ResultSetTabController ctrl) {
        this.ctrl = ctrl;
    }

    public void buildColumns(ResultSetMetaData metaData,
                             boolean allowEdit,
                             SimpleStringProperty sqlTransactionText,
                             ChoiceBox<?> commitmode) throws SQLException {
        ctrl.setEditCommitContext(sqlTransactionText, commitmode);
        ctrl.colList.clear();
        int columnCount = metaData.getColumnCount();
        double avgColWidth = (ctrl.resultSetTableView.getWidth() - 30) / columnCount;
        for (int j = 1; j <= columnCount; j++) {
            final int columnIndex = j;
            String rawTypeName = metaData.getColumnTypeName(j);
            String normalizedTypeName = rawTypeName == null ? "" : rawTypeName.trim();
            final String colTypeNameFinal = normalizedTypeName;
            final String headerTypeFinal = buildDisplayType(metaData, j, normalizedTypeName);
            final boolean isLob = isLobType(normalizedTypeName);
            String colName = metaData.getColumnLabel(j);
            if (colName == null || colName.isBlank()) {
                colName = metaData.getColumnName(j);
            }
            if (colName == null || colName.isBlank()) {
                colName = "COLUMN_" + j;
            }
            boolean rowIdColumn = isRowIdKey(colName);

            TableColumn<ObservableList<String>, Object> column = new TableColumn<>();
            if (isIntegralType(normalizedTypeName)) {
                column.setCellValueFactory(data -> Bindings.createObjectBinding(() ->
                        parseIntegralCell(data.getValue().get(columnIndex))));
            } else if (isDecimalType(normalizedTypeName)) {
                column.setCellValueFactory(data -> Bindings.createObjectBinding(() ->
                        parseDecimalCell(data.getValue().get(columnIndex))));
            } else {
                column.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(columnIndex)));
                column.setComparator((str1, str2) -> {
                    if (str1 == null && str2 == null) return 0;
                    if (str1 == null) return -1;
                    if (str2 == null) return 1;
                    return str1.toString().compareToIgnoreCase(str2.toString());
                });
            }

            column.setCellFactory(col -> new CustomLostFocusCommitTableCell<ObservableList<String>, Object>() {
                {
                    setOnMouseClicked(ev -> {
                        if (isLob && ev.getClickCount() == 1 && !isEmpty() && getItem() != null) {
                            Platform.runLater(() -> buildLobPopup(columnIndex, colTypeNameFinal, sqlTransactionText, commitmode));
                            ev.consume();
                        }
                    });
                }

                @Override
                protected void updateItem(Object item, boolean empty) {
                    getStyleClass().remove(CELL_PENDING_UPDATE_STYLE);
                    super.updateItem(item, empty);
                    if (empty) {
                        return;
                    }
                    if (item == null) {
                        setText(ctrl.getNullLabel());
                        setTooltip(null);
                    } else if (isLob) {
                        setText(colTypeNameFinal.toUpperCase(Locale.ROOT));
                    } else {
                        setText(item.toString().replace("\n", "\u21B5"));
                        setTooltip(null);
                    }
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        ObservableList<String> dataRow = (ObservableList<String>) getTableRow().getItem();
                        if (ctrl.isPendingUpdatedDataCell(dataRow, columnIndex)) {
                            getStyleClass().add(CELL_PENDING_UPDATE_STYLE);
                        }
                    }
                }

                private void buildLobPopup(int colIdx, String typeName,
                                           SimpleStringProperty txText, ChoiceBox<?> cm) {
                    Stage lobDataPopupStage = new Stage();
                    lobDataPopupStage.setTitle(typeName.toUpperCase(Locale.ROOT));
                    CustomInfoCodeArea customInfoCodeArea = new CustomInfoCodeArea();
                    CustomInfoStackPane customInfoStackPane = new CustomInfoStackPane(customInfoCodeArea);
                    customInfoCodeArea.setWrapText(false);
                    customInfoCodeArea.setEditable(ctrl.getResultSetEditAllowed());
                    customInfoStackPane.codeAreaSnapshotButton.setVisible(false);
                    Button saveBtn = new Button();
                    saveBtn.textProperty().bind(I18n.bind("common.save", "保存"));
                    saveBtn.getStyleClass().add("accent");
                    VBox root = new VBox();
                    if (ctrl.getResultSetEditAllowed()) {
                        saveBtn.setOnAction(e -> {
                            try {
                                ObservableList<String> row = getTableView().getItems().get(getIndex());
                                String oldVal = row.get(colIdx);
                                ctrl.applyLocalCellEdit(colIdx, row, oldVal, customInfoCodeArea.getText());
                                lobDataPopupStage.close();
                            } catch (Exception ex) {
                                log.error(ex.getMessage(), ex);
                                AlertUtil.CustomAlert(I18n.t("common.error"), ex.getMessage());
                            }
                        });
                        saveBtn.setMaxWidth(Double.MAX_VALUE);
                    }
                    VBox.setVgrow(customInfoStackPane, Priority.ALWAYS);
                    root.getChildren().add(customInfoStackPane);
                    if (ctrl.getResultSetEditAllowed()) {
                        root.getChildren().add(saveBtn);
                    }
                    root.setPrefHeight(Double.MAX_VALUE);
                    CustomWindowFrameUtil.createModalPopup(
                            lobDataPopupStage,
                            lobDataPopupStage.titleProperty(),
                            root,
                            800,
                            400,
                            true
                    );
                    customInfoCodeArea.replaceText(getItem().toString());
                    lobDataPopupStage.showAndWait();
                }
            });

            column.setEditable(!rowIdColumn);
            if (allowEdit && !rowIdColumn && sqlTransactionText != null && commitmode != null) {
                bindEditableColumn(column, columnIndex, isIntegralType(normalizedTypeName), isDecimalType(normalizedTypeName));
            }
            ctrl.colList.add(column);

            VBox headerBox = buildColumnHeader(column, colName, headerTypeFinal);
            double preferredWidth = Math.max(
                    Math.max(colName.length() * 15.0, headerTypeFinal.length() * 7.0 + 28),
                    avgColWidth
            );
            column.setPrefWidth(preferredWidth);
            column.setReorderable(false);
            column.setGraphic(headerBox);
            column.getGraphic().addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                ctrl.resultSetTableView.getSelectionModel().clearSelection();
                for (int rowIndex = 0; rowIndex < ctrl.resultSetTableView.getItems().size(); rowIndex++) {
                    ctrl.resultSetTableView.getSelectionModel().select(rowIndex, column);
                }
                event.consume();
            });
        }
    }

    public void markColumnKey(int columnIndex, String keyText) {
        if (columnIndex < 0 || columnIndex + 1 >= ctrl.resultSetTableView.getColumns().size()) {
            return;
        }
        @SuppressWarnings("unchecked")
        TableColumn<ObservableList<String>, Object> column =
                (TableColumn<ObservableList<String>, Object>) ctrl.resultSetTableView.getColumns().get(columnIndex + 1);
        Object badgeValue = column.getProperties().get(HEADER_KEY_BADGE);
        if (badgeValue instanceof Label keyBadge) {
            keyBadge.setText(keyText);
            keyBadge.setStyle(resolveKeyBadgeStyle(keyText));
            keyBadge.setManaged(true);
            keyBadge.setVisible(true);
        }
        if (isRowIdKey(keyText)) {
            column.setEditable(false);
            column.setOnEditCommit(null);
        }
        updateHeaderTooltip(column, keyText);
    }

    private VBox buildColumnHeader(TableColumn<ObservableList<String>, Object> column,
                                   String colName,
                                   String headerType) {
        Label nameLabel = new Label(colName);
        nameLabel.setStyle(HEADER_NAME_STYLE);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label keyBadge = createBadge("", HEADER_PRI_BADGE_STYLE, false);
        Label typeBadge = createBadge(headerType, HEADER_TYPE_BADGE_STYLE, true);
        HBox metaRow = new HBox(4, keyBadge, typeBadge);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        VBox headerBox = new VBox(2, nameLabel, metaRow);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setFillWidth(true);
        headerBox.setMaxWidth(Double.MAX_VALUE);

        Tooltip tooltip = new Tooltip(buildHeaderTooltip(colName, headerType, null));
        tooltip.setShowDelay(Duration.millis(100));
        nameLabel.setTooltip(tooltip);
        keyBadge.setTooltip(tooltip);
        typeBadge.setTooltip(tooltip);
        Tooltip.install(headerBox, tooltip);

        column.getProperties().put(HEADER_KEY_BADGE, keyBadge);
        column.getProperties().put(HEADER_TOOLTIP, tooltip);
        column.getProperties().put(HEADER_NAME, colName);
        column.getProperties().put(HEADER_TYPE, headerType);
        return headerBox;
    }

    private Label createBadge(String text, String style, boolean visible) {
        Label badge = new Label(text);
        badge.setStyle(style);
        badge.setVisible(visible);
        badge.setManaged(visible);
        return badge;
    }

    private void updateHeaderTooltip(TableColumn<?, ?> column, String keyText) {
        Object tooltipValue = column.getProperties().get(HEADER_TOOLTIP);
        if (!(tooltipValue instanceof Tooltip tooltip)) {
            return;
        }
        String name = String.valueOf(column.getProperties().getOrDefault(HEADER_NAME, column.getText()));
        String type = String.valueOf(column.getProperties().getOrDefault(HEADER_TYPE, ""));
        tooltip.setText(buildHeaderTooltip(name, type, keyText));
    }

    private String buildHeaderTooltip(String colName, String headerType, String keyText) {
        if (keyText == null || keyText.isBlank()) {
            return colName + "\n" + headerType;
        }
        return colName + "\n" + keyText + "\n" + headerType;
    }

    private String resolveKeyBadgeStyle(String keyText) {
        return isRowIdKey(keyText) ? HEADER_ROWID_BADGE_STYLE : HEADER_PRI_BADGE_STYLE;
    }

    private boolean isRowIdKey(String keyText) {
        return "ROWID".equalsIgnoreCase(keyText == null ? "" : keyText.trim());
    }

    private boolean isIntegralType(String typeName) {
        String normalized = normalizeTypeName(typeName);
        return normalized.equals("int")
                || normalized.equals("integer")
                || normalized.equals("serial")
                || normalized.equals("smallint")
                || normalized.equals("bigint");
    }

    private boolean isDecimalType(String typeName) {
        String normalized = normalizeTypeName(typeName);
        return normalized.equals("float")
                || normalized.equals("decimal")
                || normalized.equals("numeric")
                || normalized.equals("number")
                || normalized.equals("double")
                || normalized.equals("real");
    }

    private boolean isLobType(String typeName) {
        String normalized = normalizeTypeName(typeName);
        return normalized.matches("(byte|blob|clob|text|bytea|image|longvarbinary|longvarchar)");
    }

    private String buildDisplayType(ResultSetMetaData metaData, int columnIndex, String typeName) throws SQLException {
        String normalized = normalizeTypeName(typeName);
        if (normalized.isEmpty()) {
            return "";
        }

        int precision = metaData.getPrecision(columnIndex);
        int scale = metaData.getScale(columnIndex);
        int displaySize = metaData.getColumnDisplaySize(columnIndex);

        if (normalized.equals("decimal") || normalized.equals("numeric") || normalized.equals("number")) {
            if (precision > 0 && scale > 0) {
                return typeName + "(" + precision + "," + scale + ")";
            }
            if (precision > 0) {
                return typeName + "(" + precision + ")";
            }
            return typeName;
        }

        if (normalized.matches(".*(char|varchar|nvarchar|nchar|binary|varbinary|raw).*")) {
            int size = precision > 0 ? precision : displaySize;
            if (size > 0) {
                return typeName + "(" + size + ")";
            }
        }

        if (normalized.startsWith("datetime")
                || normalized.startsWith("timestamp")
                || normalized.startsWith("date")
                || normalized.startsWith("time")) {
            return typeName;
        }

        return typeName;
    }

    private String normalizeTypeName(String typeName) {
        return typeName == null ? "" : typeName.trim().toLowerCase(Locale.ROOT);
    }

    /** Empty or blank strings (e.g. new insert row) must not go through parseInt — avoids binding errors. */
    private static Integer parseIntegralCell(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Float parseDecimalCell(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Float.parseFloat(t);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void bindEditableColumn(TableColumn<ObservableList<String>, Object> column,
                                    int columnIndex,
                                    boolean integralColumn,
                                    boolean decimalColumn) {
        column.setOnEditCommit(event -> {
            ObservableList<String> row = event.getRowValue();
            String oldValue = row == null ? null : row.get(columnIndex);
            Object newValue = event.getNewValue();
            String colValue = newValue == null ? null : newValue.toString().replaceAll("\u21B5", "\n");
            if (!isValidNumericEdit(colValue, integralColumn, decimalColumn)) {
                ctrl.resultSetTableView.refresh();
                return;
            }
            ctrl.applyLocalCellEdit(columnIndex, row, oldValue, colValue);
        });
    }

    private boolean isValidNumericEdit(String value, boolean integralColumn, boolean decimalColumn) {
        if (!integralColumn && !decimalColumn) {
            return true;
        }
        if (value == null || ctrl.getNullLabel().equals(value)) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            if (integralColumn) {
                new BigInteger(trimmed);
            } else {
                new BigDecimal(trimmed);
            }
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
