package com.dbboys.ui.controller;

import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.ColumnsInfo;
import com.dbboys.service.migration.TableMapping;
import com.dbboys.service.migration.TypeMapper;
import com.dbboys.ui.dialog.AlertUtil;
import javafx.event.ActionEvent;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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
    private static final double DIALOG_W = 456;
    private static final double DIALOG_H = 500;

    private GridPane rowsGrid;
    private GridPane headerGrid;
    private ScrollPane scroll;
    private Connect source;
    private Connect target;
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
        this.source = source;
        this.target = target;
        this.sourceTypes = columnTypes(source);
        this.targetTypes = columnTypes(target);
        this.result = null;

        VBox content = buildContent(initial == null ? Map.of() : initial);
        ButtonType addType = new ButtonType(
                I18n.t("migration.mapping.add", "Add Mapping"), ButtonBar.ButtonData.LEFT);
        ButtonType resetType = new ButtonType(
                I18n.t("migration.mapping.reset", "Reset"), ButtonBar.ButtonData.LEFT);
        ButtonType okType = new ButtonType(
                I18n.t("common.confirm", "Confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(
                I18n.t("common.cancel", "Cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                String.format(I18n.t("migration.mapping.title", "Custom Data Mapping - %s"),
                        taskName == null ? "" : taskName),
                content,
                DIALOG_W,
                DIALOG_H,
                addType,
                resetType,
                okType,
                cancelType);
        Button addButton = dialog.getButton(addType);
        addButton.getStyleClass().add("small");
        addButton.setOnAction(e -> addRow("", "", ""));
        Button resetButton = dialog.getButton(resetType);
        resetButton.getStyleClass().add("small");
        resetButton.setOnAction(e -> resetRows());
        Button okButton = dialog.getButton(okType);
        okButton.addEventFilter(ActionEvent.ACTION, e -> result = collectResult());
        ButtonType pickedType = dialog.showAndWait();
        return pickedType == okType ? result : null;
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
        targetCol.setMinWidth(160);
        rowsGrid.getColumnConstraints().setAll(sourceCol, targetCol);

        headerGrid = new GridPane();
        headerGrid.setHgap(10);
        headerGrid.getColumnConstraints().setAll(sourceCol, targetCol);
        Label sourceHeader = new Label();
        sourceHeader.textProperty().bind(
                I18n.bind("migration.mapping.column.source_type", "Source Type"));
        Label targetHeader = new Label();
        targetHeader.textProperty().bind(
                I18n.bind("migration.mapping.column.target_type", "Target Type"));
        headerGrid.addRow(0, sourceHeader, targetHeader);

        scroll = new ScrollPane(rowsGrid);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(8, headerGrid, scroll);

        rebuildGrid();
        TableMapping global = initial.get(TableMapping.GLOBAL_TABLE_KEY);
        Map<String, String> overrides = global == null ? Map.of() : global.typeOverrides();
        if (sourceTypes.isEmpty()) {
            addRow("", "", "");
        } else {
            for (String sourceType : sourceTypes) {
                String defaultTarget = defaultTargetType(sourceType);
                String override = getIgnoreCase(overrides, sourceType);
                addRow(sourceType, override != null ? override : defaultTarget, defaultTarget);
            }
        }
        // 数据库保存的覆盖类型不在源平台类型列表中时也要回显（否则编辑后会丢值）
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String savedType = entry.getKey();
            if (savedType == null || savedType.isBlank()) {
                continue;
            }
            boolean listed = false;
            for (String sourceType : sourceTypes) {
                if (savedType.equalsIgnoreCase(sourceType)) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                addRow(savedType, entry.getValue(), defaultTargetType(savedType));
            }
        }
        return content;
    }

    private void rebuildGrid() {
        rowsGrid.getChildren().clear();
        int rowIndex = 0;
        for (MappingRow row : rows) {
            rowsGrid.addRow(rowIndex++, row.sourceType, row.targetBox);
        }
    }

    private void addRow(String sourceType, String targetType, String defaultTarget) {
        MappingRow row = new MappingRow();
        row.defaultTarget = defaultTarget;
        row.sourceType = new ComboBox<>(FXCollections.observableArrayList(sourceTypes));
        row.sourceType.setEditable(true);
        row.sourceType.getStyleClass().add("choice-box-with-border");
        row.sourceType.setValue(sourceType);
        row.sourceType.setPrefWidth(180);
        row.targetType = new ComboBox<>(FXCollections.observableArrayList(targetTypes));
        row.targetType.setEditable(true);
        row.targetType.getStyleClass().add("choice-box-with-border");
        row.targetType.setValue(targetType);
        row.targetType.setPrefWidth(180);
        row.removeButton = new Button("✕");
        row.removeButton.getStyleClass().add("small");
        row.removeButton.setOnAction(e -> {
            rows.remove(row);
            rebuildGrid();
        });
        row.targetBox = new HBox(6, row.targetType, row.removeButton);
        row.targetBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row.targetType, Priority.ALWAYS);
        rows.add(row);
        rebuildGrid();
        scrollToBottom();
    }

    private void resetRows() {
        rows.clear();
        if (sourceTypes.isEmpty()) {
            addRow("", "", "");
        } else {
            for (String sourceType : sourceTypes) {
                String defaultTarget = defaultTargetType(sourceType);
                addRow(sourceType, defaultTarget, defaultTarget);
            }
        }
    }

    private void scrollToBottom() {
        if (scroll == null) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            scroll.setVvalue(1.0);
            scroll.layout();
        });
    }

    private Map<String, TableMapping> collectResult() {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (MappingRow row : rows) {
            String sourceType = row.sourceType.getValue();
            String targetType = row.targetType.getValue();
            String defaultTarget = row.defaultTarget == null ? "" : row.defaultTarget.trim();
            if (sourceType == null || sourceType.isBlank()
                    || targetType == null || targetType.isBlank()
                    || targetType.trim().equalsIgnoreCase(defaultTarget)) {
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

    private String defaultTargetType(String sourceType) {
        try {
            ColumnsInfo column = new ColumnsInfo();
            column.setColName("c");
            column.setColType(sourceType);
            TypeMapper.GenericType generic = TypeMapper.normalize(source.getDbtype(), column);
            return TypeMapper.toTargetType(target.getDbtype(), generic, column, new ArrayList<>());
        } catch (Exception e) {
            log.debug("default target type failed for {}", sourceType, e);
            return "";
        }
    }

    private static String getIgnoreCase(Map<String, String> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        String exact = map.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static final class MappingRow {
        String defaultTarget;
        ComboBox<String> sourceType;
        ComboBox<String> targetType;
        HBox targetBox;
        Button removeButton;
    }
}
