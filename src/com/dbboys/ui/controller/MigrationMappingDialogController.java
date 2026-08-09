package com.dbboys.ui.controller;

import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.service.migration.TableMapping;
import com.dbboys.ui.dialog.CustomWindowFrameUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局数据映射对话框：源类型基名 → 目标类型文本，对所有表生效，不再按表列出。
 * 编辑结果保存在任务 mappings 的 {@code "*"} 条目中，由
 * {@link TableMapping#globalTypeOverrides(Map)} 消费。
 */
public class MigrationMappingDialogController {
    private static final Logger log = LogManager.getLogger(MigrationMappingDialogController.class);
    private static final double DIALOG_W = 760;
    private static final double DIALOG_H = 500;

    private Stage dialogStage;
    private GridPane rowsGrid;
    private final ObservableList<MappingRow> rows = FXCollections.observableArrayList();
    private List<String> sourceTypes = List.of();
    private List<String> targetTypes = List.of();
    private Map<String, TableMapping> result;

    /**
     * 打开全局数据映射对话框。
     * {@code initial} 中 {@code "*"} 条目的 typeOverrides 为源类型 → 目标类型。
     */
    public Map<String, TableMapping> showAndWait(Window owner, String taskName,
                                                 Connect source, Connect target,
                                                 Map<String, TableMapping> initial) {
        this.sourceTypes = columnTypes(source);
        this.targetTypes = columnTypes(target);
        this.result = null;

        dialogStage = new Stage();
        VBox content = buildContent(initial == null ? Map.of() : initial);
        CustomWindowFrameUtil.createModalPopup(
                dialogStage,
                new SimpleStringProperty(String.format(
                        I18n.t("migration.mapping.title", "Custom Data Mapping - %s"),
                        taskName == null ? "" : taskName)),
                content,
                DIALOG_W,
                DIALOG_H,
                true,
                owner);
        dialogStage.setMinWidth(600);
        dialogStage.setMinHeight(420);
        if (owner != null && owner.isShowing()) {
            dialogStage.setX(owner.getX() + (owner.getWidth() - DIALOG_W) / 2);
            dialogStage.setY(owner.getY() + (owner.getHeight() - DIALOG_H) / 2);
        }
        dialogStage.showAndWait();
        return result;
    }

    private VBox buildContent(Map<String, TableMapping> initial) {
        rowsGrid = new GridPane();
        rowsGrid.setHgap(10);
        rowsGrid.setVgap(6);
        ColumnConstraints sourceCol = new ColumnConstraints();
        sourceCol.setHgrow(Priority.ALWAYS);
        sourceCol.setMinWidth(180);
        ColumnConstraints targetCol = new ColumnConstraints();
        targetCol.setHgrow(Priority.ALWAYS);
        targetCol.setMinWidth(220);
        ColumnConstraints removeCol = new ColumnConstraints();
        removeCol.setMinWidth(36);
        rowsGrid.getColumnConstraints().setAll(sourceCol, targetCol, removeCol);

        ScrollPane scroll = new ScrollPane(rowsGrid);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label hint = new Label();
        hint.textProperty().bind(I18n.bind("migration.mapping.global_hint",
                "Applies to all tables; leave target type empty to use the default mapping"));
        hint.setWrapText(true);

        Button addButton = new Button();
        addButton.textProperty().bind(I18n.bind("migration.mapping.add", "Add Mapping"));
        addButton.setOnAction(e -> addRow("", ""));

        Button okButton = new Button();
        okButton.textProperty().bind(I18n.bind("common.confirm", "Confirm"));
        okButton.getStyleClass().add("accent");
        okButton.setDefaultButton(true);
        okButton.setOnAction(e -> {
            result = collectResult();
            dialogStage.close();
        });

        Button cancelButton = new Button();
        cancelButton.textProperty().bind(I18n.bind("common.cancel", "Cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> dialogStage.close());

        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        HBox buttonBar = new HBox(10, addButton, buttonSpacer, okButton, cancelButton);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, scroll, hint, buttonBar);
        content.setPadding(new Insets(10, 14, 10, 14));

        rebuildGrid();
        TableMapping global = initial.get(TableMapping.GLOBAL_TABLE_KEY);
        Map<String, String> overrides = global == null ? Map.of() : global.typeOverrides();
        if (overrides.isEmpty()) {
            addRow("", "");
        } else {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                addRow(entry.getKey(), entry.getValue());
            }
        }
        return content;
    }

    private void rebuildGrid() {
        rowsGrid.getChildren().clear();
        Label sourceHeader = new Label();
        sourceHeader.textProperty().bind(I18n.bind("migration.mapping.column.source_type", "Source Type"));
        Label targetHeader = new Label();
        targetHeader.textProperty().bind(I18n.bind("migration.mapping.column.target_type", "Target Type"));
        Label removeHeader = new Label("");
        rowsGrid.addRow(0, sourceHeader, targetHeader, removeHeader);
        int rowIndex = 1;
        for (MappingRow row : rows) {
            rowsGrid.addRow(rowIndex++, row.sourceType, row.targetType, row.removeButton);
        }
    }

    private void addRow(String sourceType, String targetType) {
        MappingRow row = new MappingRow();
        row.sourceType = new ComboBox<>(FXCollections.observableArrayList(sourceTypes));
        row.sourceType.setEditable(true);
        row.sourceType.setValue(sourceType);
        row.sourceType.setPrefWidth(220);
        row.targetType = new ComboBox<>(FXCollections.observableArrayList(targetTypes));
        row.targetType.setEditable(true);
        row.targetType.setValue(targetType);
        row.targetType.setPrefWidth(260);
        row.removeButton = new Button("✕");
        row.removeButton.getStyleClass().add("small");
        row.removeButton.setOnAction(e -> {
            rows.remove(row);
            rebuildGrid();
        });
        rows.add(row);
        rebuildGrid();
    }

    private Map<String, TableMapping> collectResult() {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (MappingRow row : rows) {
            String sourceType = row.sourceType.getValue();
            String targetType = row.targetType.getValue();
            if (sourceType == null || sourceType.isBlank()
                    || targetType == null || targetType.isBlank()) {
                continue;
            }
            overrides.put(sourceType.trim(), targetType.trim());
        }
        if (overrides.isEmpty()) {
            return Map.of();
        }
        return Map.of(TableMapping.GLOBAL_TABLE_KEY,
                new TableMapping(Set.of(), overrides));
    }

    private static List<String> columnTypes(Connect connect) {
        if (connect == null) {
            return List.of();
        }
        try {
            DatabasePlatform platform = PlatformResolvers.get().requirePlatform(connect);
            List<String> types = platform.getColumnTypes();
            return types == null ? List.of() : new ArrayList<>(types);
        } catch (Exception e) {
            log.debug("load column types failed for {}", connect.getDbtype(), e);
            return List.of();
        }
    }

    private static final class MappingRow {
        ComboBox<String> sourceType;
        ComboBox<String> targetType;
        Button removeButton;
    }
}
