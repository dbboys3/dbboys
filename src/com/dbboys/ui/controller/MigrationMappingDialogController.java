package com.dbboys.ui.controller;

import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.ColumnsInfo;
import com.dbboys.service.migration.TableMapping;
import com.dbboys.service.migration.TypeMapper;
import com.dbboys.ui.component.CustomTableView;
import com.dbboys.ui.dialog.AlertUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.ActionEvent;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
    private static final double DIALOG_H = 490;

    private CustomTableView<MappingRow> mappingTable;
    // 源/目标连接自身的数据库类型
    private String sourceDbType;
    private String targetDbType;
    private final ObservableList<MappingRow> rows = FXCollections.observableArrayList();
    private List<String> sourceTypes = List.of();
    private List<String> targetTypes = List.of();
    private boolean sameDbType;
    private Map<String, TableMapping> result;

    /**
     * 打开全局数据映射对话框。
     * {@code initial} 中 {@code "*"} 条目的 typeOverrides 为源类型 → 目标类型。
     * {@code sourceDbType}/{@code targetDbType} 为源/目标连接自身的数据库类型。
     */
    public Map<String, TableMapping> showAndWait(Window owner, String taskName,
                                                 String sourceDbType, String targetDbType,
                                                 Map<String, TableMapping> initial) {
        this.sourceDbType = sourceDbType;
        this.targetDbType = targetDbType;
        this.sourceTypes = columnTypes(sourceDbType);
        this.targetTypes = columnTypes(targetDbType);
        this.sameDbType = sourceDbType != null && sourceDbType.equalsIgnoreCase(targetDbType);
        this.result = null;

        VBox content = buildContent(initial == null ? Map.of() : initial);
        ButtonType addType = new ButtonType(
                I18n.t("migration.mapping.add", "Add Mapping"), ButtonBar.ButtonData.LEFT);
        ButtonType resetType = new ButtonType(
                I18n.t("migration.mapping.reset", "Reset"), ButtonBar.ButtonData.LEFT);
        ButtonType clearType = new ButtonType(
                I18n.t("migration.mapping.clear", "清空"), ButtonBar.ButtonData.LEFT);
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
                clearType,
                okType,
                cancelType);
        Button addButton = dialog.getButton(addType);
        addButton.getStyleClass().add("small");
        addButton.setOnAction(e -> {
            addRow("", "", "");
            scrollToBottom();
        });
        Button resetButton = dialog.getButton(resetType);
        resetButton.getStyleClass().add("small");
        resetButton.setOnAction(e -> resetRows());
        Button clearButton = dialog.getButton(clearType);
        clearButton.getStyleClass().add("small");
        clearButton.setOnAction(e -> rows.clear());
        Button okButton = dialog.getButton(okType);
        okButton.addEventFilter(ActionEvent.ACTION, e -> result = collectResult());
        ButtonType pickedType = dialog.showAndWait();
        return pickedType == okType ? result : null;
    }

    private VBox buildContent(Map<String, TableMapping> initial) {
        mappingTable = new CustomTableView<>();
        mappingTable.setItems(rows);
        mappingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        mappingTable.setPlaceholder(new Label(""));

        TableColumn<MappingRow, ComboBox<String>> sourceCol = new TableColumn<>();
        sourceCol.textProperty().bind(
                I18n.bind("migration.mapping.column.source_type", "Source Type"));
        sourceCol.setPrefWidth(190);
        sourceCol.setCellValueFactory(
                cell -> new ReadOnlyObjectWrapper<>(cell.getValue().sourceType));
        sourceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ComboBox<String> item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : item);
            }
        });

        TableColumn<MappingRow, ComboBox<String>> targetCol = new TableColumn<>();
        targetCol.textProperty().bind(
                I18n.bind("migration.mapping.column.target_type", "Target Type"));
        targetCol.setPrefWidth(180);
        targetCol.setCellValueFactory(
                cell -> new ReadOnlyObjectWrapper<>(cell.getValue().targetType));
        targetCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ComboBox<String> item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : item);
            }
        });

        TableColumn<MappingRow, Button> removeCol = new TableColumn<>();
        removeCol.setSortable(false);
        removeCol.setPrefWidth(44);
        removeCol.setCellValueFactory(
                cell -> new ReadOnlyObjectWrapper<>(cell.getValue().removeButton));
        removeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Button item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : item);
            }
        });

        mappingTable.getColumns().addAll(sourceCol, targetCol, removeCol);
        // 映射表不需要排序，也不允许拖动列
        mappingTable.setSortPolicy(param -> false);
        mappingTable.getColumns().forEach(column -> column.setReorderable(false));
        VBox.setVgrow(mappingTable, Priority.ALWAYS);
        VBox content = new VBox(8, mappingTable);

        populateRows(initial);
        return content;
    }

    private void populateRows(Map<String, TableMapping> initial) {
        TableMapping global = initial.get(TableMapping.GLOBAL_TABLE_KEY);
        Map<String, String> overrides = global == null ? Map.of() : global.typeOverrides();
        boolean prefilled = !sameDbType && !sourceTypes.isEmpty();
        if (prefilled) {
            for (String sourceType : sourceTypes) {
                String defaultTarget = defaultTargetType(sourceType);
                String override = getIgnoreCase(overrides, sourceType);
                addRow(sourceType, override != null ? override : defaultTarget, defaultTarget);
            }
        } else if (!sameDbType && sourceTypes.isEmpty()) {
            addRow("", "", "");
        }
        // 数据库保存的覆盖类型未在预填行中回显时也要补回（否则编辑后会丢值）
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String savedType = entry.getKey();
            if (savedType == null || savedType.isBlank()) {
                continue;
            }
            boolean listed = false;
            if (prefilled) {
                for (String sourceType : sourceTypes) {
                    if (savedType.equalsIgnoreCase(sourceType)) {
                        listed = true;
                        break;
                    }
                }
            }
            if (!listed) {
                addRow(savedType, entry.getValue(), defaultTargetType(savedType));
            }
        }
    }

    private void addRow(String sourceType, String targetType, String defaultTarget) {
        MappingRow row = new MappingRow();
        row.defaultTarget = defaultTarget;
        row.sourceType = typeComboBox(sourceTypes);
        row.sourceType.setValue(sourceType);
        row.targetType = typeComboBox(targetTypes);
        row.targetType.setValue(targetType);
        row.removeButton = new Button("✕");
        row.removeButton.getStyleClass().add("small");
        row.removeButton.setOnAction(e -> rows.remove(row));
        rows.add(row);
    }

    private static ComboBox<String> typeComboBox(List<String> types) {
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(types));
        combo.setEditable(true);
        combo.getStyleClass().add("mapping-type-combo");
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    private void resetRows() {
        rows.clear();
        if (sameDbType) {
            return;
        }
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
        if (mappingTable == null) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            if (!rows.isEmpty()) {
                mappingTable.scrollTo(rows.size() - 1);
            }
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

    private static List<String> columnTypes(String dbtype) {
        if (dbtype == null || dbtype.isBlank()) {
            return List.of();
        }
        try {
            DatabasePlatform platform = PlatformResolvers.get().requirePlatform(dbtype);
            List<String> types = platform.getColumnTypes();
            return types == null ? List.of() : new ArrayList<>(types);
        } catch (Exception e) {
            log.debug("load column types failed for {}", dbtype, e);
            return List.of();
        }
    }

    private String defaultTargetType(String sourceType) {
        try {
            ColumnsInfo column = new ColumnsInfo();
            column.setColName("c");
            column.setColType(sourceType);
            TypeMapper.GenericType generic = TypeMapper.normalize(sourceDbType, column);
            return TypeMapper.toTargetType(targetDbType, generic, column, new ArrayList<>());
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
        Button removeButton;
    }
}
