package com.dbboys.ctrl;

import com.dbboys.customnode.CustomInfoCodeArea;
import com.dbboys.customnode.CustomInfoStackPane;
import com.dbboys.customnode.CustomTableCell;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public class ResultSetColumnBuilder {
    private static final Logger log = LogManager.getLogger(ResultSetColumnBuilder.class);

    private final ResultSetTabController ctrl;

    public ResultSetColumnBuilder(ResultSetTabController ctrl) {
        this.ctrl = ctrl;
    }

    public void buildColumns(ResultSetMetaData metaData,
                             boolean allowEdit,
                             SimpleStringProperty sqlTransactionText,
                             ChoiceBox<?> commitmode) throws SQLException {
        ctrl.colList.clear();
        int columnCount = metaData.getColumnCount();
        double avgColWidth = (ctrl.resultSetTableView.getWidth() - 30) / columnCount;
        for (int j = 1; j <= columnCount; j++) {
            final int columnIndex = j;
            String colTypeName = metaData.getColumnTypeName(j);
            final String colTypeNameFinal = colTypeName;
            Integer length = metaData.getColumnDisplaySize(j);
            final boolean isLob = colTypeName != null && colTypeName.toLowerCase().matches("(blob|clob|text|bytea|image|longvarbinary|longvarchar)");

            TableColumn<ObservableList<String>, Object> column = new TableColumn<>();
            if (colTypeName.equals("int") || colTypeName.equals("serial") || colTypeName.equals("smallint")) {
                column.setCellValueFactory(data -> Bindings.createObjectBinding(() ->
                        data.getValue().get(columnIndex) == null ? null : Integer.parseInt(data.getValue().get(columnIndex))));
            } else if (colTypeName.equals("float") || colTypeName.equals("decimal")) {
                column.setCellValueFactory(data -> Bindings.createObjectBinding(() ->
                        data.getValue().get(columnIndex) == null ? null : Float.parseFloat(data.getValue().get(columnIndex))));
            } else {
                column.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(columnIndex)));
                String displayType = colTypeName;
                if (!displayType.startsWith("date")) {
                    displayType = displayType + "(" + length + ")";
                }
                column.setComparator((str1, str2) -> {
                    if (str1 == null && str2 == null) return 0;
                    if (str1 == null) return -1;
                    if (str2 == null) return 1;
                    return str1.toString().compareToIgnoreCase(str2.toString());
                });
                colTypeName = displayType;
            }

            final String headerTypeFinal = colTypeName;

            column.setCellFactory(col -> new CustomTableCell<ObservableList<String>, Object>() {
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
                    super.updateItem(item, empty);
                    if (empty) return;
                    if (item == null) {
                        setText(ctrl.getNullLabel());
                        setTooltip(null);
                    } else if (isLob) {
                        setText(colTypeNameFinal.toUpperCase());
                    } else {
                        setText(item.toString().replace("\n", "\u21B5"));
                        setTooltip(null);
                    }
                }

                private void buildLobPopup(int colIdx, String typeName,
                                           SimpleStringProperty txText, ChoiceBox<?> cm) {
                    Stage lobDataPopupStage = new Stage();
                    lobDataPopupStage.setTitle(typeName.toUpperCase());
                    CustomInfoCodeArea customInfoCodeArea = new CustomInfoCodeArea();
                    CustomInfoStackPane customInfoStackPane = new CustomInfoStackPane(customInfoCodeArea);
                    customInfoCodeArea.setWrapText(false);
                    customInfoCodeArea.setEditable(ctrl.resultSetEditableEnabledLabel.isVisible());
                    customInfoStackPane.codeAreaSnapshotButton.setVisible(false);
                    Button saveBtn = new Button();
                    saveBtn.textProperty().bind(I18n.bind("common.save", "保存"));
                    saveBtn.getStyleClass().add("accent");
                    VBox root = new VBox();
                    if (ctrl.resultSetEditableEnabledLabel.isVisible()) {
                        saveBtn.setOnAction(e -> {
                            try {
                                ObservableList<String> row = getTableView().getItems().get(getIndex());
                                ctrl.editHelper.updateCellValue(colIdx, customInfoCodeArea.getText(), row, row.get(colIdx), txText, cm);
                                row.set(colIdx, customInfoCodeArea.getText());
                                ctrl.resultSetTableView.refresh();
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
                    if (ctrl.resultSetEditableEnabledLabel.isVisible()) {
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

            if (allowEdit && sqlTransactionText != null && commitmode != null) {
                bindEditableColumn(column, columnIndex, sqlTransactionText, commitmode);
            }
            ctrl.colList.add(column);

            StackPane colheader = new StackPane();
            String colName = metaData.getColumnLabel(j);
            Label colLabel = new Label(colName);
            Label colType = new Label(headerTypeFinal);
            colheader.getChildren().addAll(colLabel);
            colType.setStyle("-fx-font-size: 5");
            StackPane.setAlignment(colType, Pos.BOTTOM_LEFT);
            Tooltip tp = new Tooltip(headerTypeFinal);
            tp.setShowDelay(Duration.millis(100));
            colLabel.setTooltip(tp);
            column.setPrefWidth(Math.max(colLabel.getText().length() * 15, avgColWidth));
            colLabel.setMaxWidth(Double.MAX_VALUE);
            column.setReorderable(false);
            column.setGraphic(colheader);
            column.getGraphic().addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                ctrl.resultSetTableView.getSelectionModel().clearSelection();
                for (int rowIndex = 0; rowIndex < ctrl.resultSetTableView.getItems().size(); rowIndex++) {
                    ctrl.resultSetTableView.getSelectionModel().select(rowIndex, column);
                }
                event.consume();
            });
        }
    }

    private void bindEditableColumn(TableColumn<ObservableList<String>, Object> column,
                                    int columnIndex,
                                    SimpleStringProperty sqlTransactionText,
                                    ChoiceBox<?> commitmode) {
        column.setOnEditCommit(event -> {
            Object oldvalue = event.getOldValue();
            Object colvalue = event.getNewValue().toString().replaceAll("\u21B5", "\n");
            event.getRowValue().set(columnIndex, colvalue.toString());

            try {
                ctrl.editHelper.updateCellValue(columnIndex, colvalue.toString(), event.getRowValue(), oldvalue, sqlTransactionText, commitmode);
            } catch (SQLException e) {
                Platform.runLater(() -> {
                    event.getRowValue().set(columnIndex, oldvalue == null ? null : oldvalue.toString());
                    event.getTableView().refresh();
                    if (com.dbboys.db.ConnectionErrorHandler.isDisconnectError(e)) {
                        ctrl.hiddenDisconnectedButton.fire();
                    } else {
                        AlertUtil.CustomAlert("错误", "[" + e.getErrorCode() + "]" + e.getMessage());
                    }
                });
            } finally {
                try {
                    ctrl.sqlStatement.close();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
