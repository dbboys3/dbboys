package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.ColumnsInfo;
import com.dbboys.model.Connect;
import com.dbboys.model.Table;
import com.dbboys.service.BackgroundSqlService;
import com.dbboys.service.migration.TableMapping;
import com.dbboys.ui.dialog.CustomWindowFrameUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自定义数据映射对话框：对迁移任务选中的表逐表配置"排除列 + 目标类型覆盖"。
 * <p>
 * 左侧表清单（表类型为"全部"时后台加载全量表名，自定义时用勾选的表）；
 * 右侧当前表的列编辑区：每列一行 = CheckBox(迁移) + 列名 + 源类型 + 目标类型
 * （可编辑 ComboBox，候选值为目标平台 {@code DatabasePlatform.getColumnTypes()}，可手输，
 * 留空=默认映射）。列元数据用源会话 {@code getColumns} 后台懒加载。
 * 返回更新后的 mappings（取消返回 null），本身不落库。
 */
public class MigrationMappingDialogController {
    private static final Logger log = LogManager.getLogger(MigrationMappingDialogController.class);
    private static final double DIALOG_W = 860;
    private static final double DIALOG_H = 560;

    private Stage dialogStage;
    private ListView<String> tableList;
    private GridPane columnsGrid;

    private Connect source;
    private String catalog;
    private String schema;
    private List<String> targetColumnTypes = List.of();
    /** 工作副本：切表/确定时提交，取消丢弃。 */
    private Map<String, TableMapping> workingMappings = new LinkedHashMap<>();
    /** 列元数据缓存（避免切表重复查询）。 */
    private final Map<String, ArrayList<ColumnsInfo>> columnsCache = new HashMap<>();
    private String currentTable;
    private final List<ColumnRow> currentRows = new ArrayList<>();
    private Map<String, TableMapping> result;
    /** 列加载代数：丢弃过期回调。 */
    private int loadGeneration;

    /**
     * 打开映射对话框。fixedTables=null 表示表类型为"全部"（后台加载全量表名）；
     * 否则用传入的勾选表清单。返回更新后的 mappings；取消返回 null。
     */
    public Map<String, TableMapping> showAndWait(Window owner, String taskName,
                                                 Connect source, String catalog, String schema,
                                                 List<String> fixedTables,
                                                 List<String> targetColumnTypes,
                                                 Map<String, TableMapping> initial) {
        this.source = source;
        this.catalog = catalog;
        this.schema = schema;
        this.targetColumnTypes = targetColumnTypes == null ? List.of() : targetColumnTypes;
        this.workingMappings = new LinkedHashMap<>(initial == null ? Map.of() : initial);
        this.result = null;

        dialogStage = new Stage();
        VBox content = buildContent(fixedTables);
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
        dialogStage.setMinWidth(640);
        dialogStage.setMinHeight(420);
        if (owner != null && owner.isShowing()) {
            dialogStage.setX(owner.getX() + (owner.getWidth() - DIALOG_W) / 2);
            dialogStage.setY(owner.getY() + (owner.getHeight() - DIALOG_H) / 2);
        }
        dialogStage.showAndWait();
        return result;
    }

    private VBox buildContent(List<String> fixedTables) {
        // ---- 左：表清单 ----
        Label tablesLabel = new Label();
        tablesLabel.textProperty().bind(I18n.bind("migration.mapping.tables", "Tables"));
        tableList = new ListView<>();
        Label tablesPlaceholder = new Label();
        tablesPlaceholder.textProperty().bind(I18n.bind("migration.status.loading", "Loading..."));
        tableList.setPlaceholder(tablesPlaceholder);
        tableList.setPrefWidth(220);
        VBox.setVgrow(tableList, Priority.ALWAYS);
        VBox leftBox = new VBox(6, tablesLabel, tableList);

        // ---- 右：列编辑区 ----
        columnsGrid = new GridPane();
        columnsGrid.setHgap(10);
        columnsGrid.setVgap(6);
        ColumnConstraints migrateCol = new ColumnConstraints();
        migrateCol.setMinWidth(60);
        ColumnConstraints nameCol = new ColumnConstraints();
        nameCol.setHgrow(Priority.ALWAYS);
        nameCol.setMinWidth(140);
        ColumnConstraints sourceTypeCol = new ColumnConstraints();
        sourceTypeCol.setMinWidth(110);
        ColumnConstraints targetTypeCol = new ColumnConstraints();
        targetTypeCol.setMinWidth(170);
        columnsGrid.getColumnConstraints().setAll(migrateCol, nameCol, sourceTypeCol, targetTypeCol);
        ScrollPane columnsScroll = new ScrollPane(columnsGrid);
        columnsScroll.setFitToWidth(true);
        HBox.setHgrow(columnsScroll, Priority.ALWAYS);

        HBox mainBox = new HBox(12, leftBox, columnsScroll);
        VBox.setVgrow(mainBox, Priority.ALWAYS);

        Label hintLabel = new Label();
        hintLabel.textProperty().bind(I18n.bind("migration.mapping.hint",
                "Leave target type empty to use the default mapping; uncheck a column to skip it"));
        hintLabel.setWrapText(true);

        Button okButton = new Button();
        okButton.textProperty().bind(I18n.bind("common.confirm", "Confirm"));
        okButton.setDefaultButton(true);
        okButton.setOnAction(e -> {
            commitCurrentTable();
            result = workingMappings;
            dialogStage.close();
        });
        Button cancelButton = new Button();
        cancelButton.textProperty().bind(I18n.bind("common.cancel", "Cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> dialogStage.close());
        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        HBox buttonBar = new HBox(10, buttonSpacer, okButton, cancelButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        tableList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            commitCurrentTable();
            showTable(n);
        });

        VBox content = new VBox(8, mainBox, hintLabel, buttonBar);
        content.setPadding(new Insets(10, 14, 10, 14));

        // ---- 表清单来源 ----
        if (fixedTables != null) {
            tableList.getItems().setAll(fixedTables);
            selectFirstTable();
        } else {
            loadAllTables(tablesPlaceholder);
        }
        return content;
    }

    /** 表类型为"全部"：后台加载源范围下全量表名。 */
    private void loadAllTables(Label placeholder) {
        AppExecutor.runAsync(() -> {
            List<String> names = new ArrayList<>();
            String error = null;
            try {
                Connect sessionConnect = buildSessionConnect(source, catalog, schema);
                String dbName = schema != null && !schema.isBlank() ? schema : catalog;
                try (Connection conn = BackgroundSqlService.getConnectionService()
                        .getConnectionWithSessionInit(sessionConnect)) {
                    List<Table> tables = PlatformResolvers.get().metadata(sessionConnect)
                            .getUserTables(conn, dbName);
                    if (tables != null) {
                        for (Table table : tables) {
                            if (table != null && table.getName() != null && !table.getName().isBlank()) {
                                names.add(table.getName());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("load mapping tables failed", e);
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String loadError = error;
            Platform.runLater(() -> {
                tableList.getItems().setAll(names);
                if (loadError != null) {
                    placeholder.textProperty().unbind();
                    placeholder.setText(loadError);
                } else if (names.isEmpty()) {
                    placeholder.textProperty().unbind();
                    placeholder.setText("(0)");
                }
                selectFirstTable();
            });
        });
    }

    private void selectFirstTable() {
        if (!tableList.getItems().isEmpty()) {
            tableList.getSelectionModel().select(0);
        }
    }

    // ==================================================================
    // 列编辑区
    // ==================================================================

    /** 当前表的一列编辑状态。 */
    private static final class ColumnRow {
        String name;
        CheckBox migrate;
        ComboBox<String> targetType;
    }

    private void showTable(String table) {
        currentTable = table;
        currentRows.clear();
        columnsGrid.getChildren().clear();
        if (table == null) {
            return;
        }
        ArrayList<ColumnsInfo> cached = columnsCache.get(table);
        if (cached != null) {
            buildColumnRows(cached);
            return;
        }
        Label loading = new Label();
        loading.textProperty().bind(I18n.bind("migration.status.loading", "Loading..."));
        columnsGrid.add(loading, 0, 0, 4, 1);
        int generation = ++loadGeneration;
        AppExecutor.runAsync(() -> {
            ArrayList<ColumnsInfo> columns;
            String error = null;
            try {
                Connect sessionConnect = buildSessionConnect(source, catalog, schema);
                try (Connection conn = BackgroundSqlService.getConnectionService()
                        .getConnectionWithSessionInit(sessionConnect)) {
                    columns = PlatformResolvers.get().metadata(sessionConnect).getColumns(conn, table);
                }
            } catch (Exception e) {
                log.warn("load columns failed for {}", table, e);
                columns = new ArrayList<>();
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String loadError = error;
            ArrayList<ColumnsInfo> fetched = columns == null ? new ArrayList<>() : columns;
            Platform.runLater(() -> {
                columnsCache.put(table, fetched);
                if (generation != loadGeneration || !table.equals(currentTable)) {
                    return;
                }
                if (loadError != null) {
                    columnsGrid.getChildren().clear();
                    columnsGrid.add(new Label(loadError), 0, 0, 4, 1);
                    return;
                }
                buildColumnRows(fetched);
            });
        });
    }

    /** 构建列编辑行：初始状态回显 workingMappings（排除列=未勾、覆盖类型=目标类型列文本）。 */
    private void buildColumnRows(List<ColumnsInfo> columns) {
        columnsGrid.getChildren().clear();
        currentRows.clear();
        Label migrateHeader = new Label();
        migrateHeader.textProperty().bind(I18n.bind("migration.mapping.column.migrate", "Migrate"));
        Label nameHeader = new Label();
        nameHeader.textProperty().bind(I18n.bind("migration.mapping.column.name", "Column"));
        Label sourceTypeHeader = new Label();
        sourceTypeHeader.textProperty().bind(
                I18n.bind("migration.mapping.column.source_type", "Source Type"));
        Label targetTypeHeader = new Label();
        targetTypeHeader.textProperty().bind(
                I18n.bind("migration.mapping.column.target_type", "Target Type"));
        columnsGrid.addRow(0, migrateHeader, nameHeader, sourceTypeHeader, targetTypeHeader);

        TableMapping mapping = TableMapping.forTable(workingMappings, currentTable);
        int rowIndex = 1;
        for (ColumnsInfo column : columns) {
            if (column == null || column.getColName() == null || column.getColName().isBlank()) {
                continue;
            }
            ColumnRow row = new ColumnRow();
            row.name = column.getColName();
            row.migrate = new CheckBox();
            row.migrate.setSelected(mapping == null || !mapping.isExcluded(row.name));
            Label nameLabel = new Label(row.name);
            Label sourceTypeLabel = new Label(column.getColType() == null ? "" : column.getColType());
            row.targetType = new ComboBox<>(FXCollections.observableArrayList(targetColumnTypes));
            row.targetType.setEditable(true);
            row.targetType.setPrefWidth(160);
            String override = mapping == null ? null : mapping.overrideType(row.name);
            row.targetType.setValue(override == null ? "" : override);
            columnsGrid.addRow(rowIndex++, row.migrate, nameLabel, sourceTypeLabel, row.targetType);
            currentRows.add(row);
        }
    }

    /** 把当前表的编辑状态提交进 workingMappings（空映射则移除条目）。 */
    private void commitCurrentTable() {
        if (currentTable == null || currentRows.isEmpty()) {
            return;
        }
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (ColumnRow row : currentRows) {
            if (!row.migrate.isSelected()) {
                excluded.add(row.name.toLowerCase(Locale.ROOT));
            }
            String value = row.targetType.getValue();
            if (value != null && !value.isBlank()) {
                types.put(row.name, value.trim());
            }
        }
        removeMapping(workingMappings, currentTable);
        if (!excluded.isEmpty() || !types.isEmpty()) {
            workingMappings.put(currentTable, new TableMapping(excluded, types));
        }
    }

    /** 大小写不敏感移除表映射条目。 */
    private static void removeMapping(Map<String, TableMapping> mappings, String table) {
        mappings.keySet().removeIf(key -> key != null && key.equalsIgnoreCase(table));
    }

    /** 源端会话：schema 非空→catalog=库 + sessionCatalog=模式；否则走方言 setSessionCatalog。 */
    private static Connect buildSessionConnect(Connect source, String catalogName, String schemaName) {
        Connect sessionConnect = new Connect(source);
        DatabasePlatform platform = PlatformResolvers.get().requirePlatform(sessionConnect);
        if (schemaName != null && !schemaName.isBlank()) {
            sessionConnect.setCatalog(catalogName);
            sessionConnect.setSessionCatalog(schemaName);
        } else if (catalogName != null && !catalogName.isBlank()) {
            platform.connection().setSessionCatalog(sessionConnect, catalogName);
        }
        return sessionConnect;
    }
}
