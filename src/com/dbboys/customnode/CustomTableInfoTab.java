package com.dbboys.customnode;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.service.TableService;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.*;
import com.dbboys.vo.ColumnsInfo;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.HealthCheck;
import com.dbboys.vo.Index;
import com.dbboys.vo.Table;
import com.dbboys.vo.TreeData;
import com.jcraft.jsch.Session;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Transform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

public class CustomTableInfoTab extends CustomTab {
    private static final Logger log = LogManager.getLogger(CustomTableInfoTab.class);
    private static final String[] COLUMN_I18N_KEYS = {
            "tableinfo.column.name",
            "tableinfo.column.type",
            "tableinfo.column.length",
            "tableinfo.column.scale",
            "tableinfo.column.not_null",
            "tableinfo.column.pk",
            "tableinfo.column.auto_increment",
            "tableinfo.column.default",
            "tableinfo.column.comment"
    };
    private static final String[] COLUMN_I18N_FALLBACKS = {
            "列名", "列类型", "长度", "精度", "非空", "主键", "自增", "默认值", "注释"
    };
    private TreeItem<TreeData> treeItem;
    private Connect connect;
    // 为每个需要懒加载的 Tab 定义「已加载」标记
    private boolean ddlTabLoaded = false;
    private boolean colsTabLoaded = false;
    // 存储 Tab 引用，用于刷新逻辑
    private CustomTab ddlTab;
    private CustomTab colsTab;
    private String ddl="";

    public TabPane mainTabPane;
    private Button refreshButton;


    //configtab
    private CustomResultsetTableView<ObservableList<String>> colsTableView = new CustomResultsetTableView<>();
    
    private List<ObservableList<String>> originalDataList = FXCollections.observableArrayList();//保存原始数据，用于比较生成SQL
    private java.util.IdentityHashMap<ObservableList<String>, String> rowOriginalNameMap = new java.util.IdentityHashMap<>();
    private java.util.IdentityHashMap<ObservableList<String>, Boolean> rowIsNewMap = new java.util.IdentityHashMap<>();
    private boolean tableChangeSubmitting = false;
    private StackPane colsStackPane;
    private Button addColumnButton; // 新增列按钮
    private Button deleteColumnButton; // 删除列按钮
    private Button saveColumnButton; // 删除列按钮
    private CustomUserTextField tableNameField;
    private CustomUserTextField tableCommentField;
    private String originalTableName = "";
    private String originalTableComment = "";
    private boolean createMode = false;
    private String tableName = "";
    private Database database;



    public CustomTableInfoTab(TreeItem<TreeData> treeItem) {
        this(treeItem, null);
    }

    public CustomTableInfoTab(TreeItem<TreeData> treeItem, String tableName) {

        super("[table]"+(MetadataTreeviewUtil.getMetaConnTreeItem(treeItem).getValue().getName()+"."+MetadataTreeviewUtil.getCurrentDatabase(treeItem).getName()+"."+""));
        this.treeItem=treeItem;
        this.connect=(Connect)MetadataTreeviewUtil.getMetaConnTreeItem(treeItem).getValue();
        this.database=MetadataTreeviewUtil.getCurrentDatabase(treeItem);
        final boolean readOnlyConnect = this.connect.getReadonly() != null && this.connect.getReadonly();
        this.createMode = tableName != null;
        this.tableName = tableName == null ? treeItem.getValue().getName() : tableName.trim();
        
        setTitle(buildTabTitle(this.tableName));
        


        //实例信息tab初始化变量
        

        //主tabpane初始化
        mainTabPane=new TabPane();
        ddlTab = new CustomTab("DDL");
        colsTab = new CustomTab("列");
        ddlTab.getTitleLabel().textProperty().bind(I18n.bind("tableinfo.tab.ddl", "DDL"));
        ddlTab.textProperty().bind(I18n.bind("tableinfo.tab.ddl", "DDL"));
        colsTab.getTitleLabel().textProperty().bind(I18n.bind("tableinfo.tab.columns", "列"));
        colsTab.textProperty().bind(I18n.bind("tableinfo.tab.columns", "列"));

        //初始化列面板
        colsTableView.setEditable(true);
            for (int i = 0; i < COLUMN_I18N_KEYS.length; i++) {
                final int columnIndex = i; // 创建一个final变量来存储当前的i值
                TableColumn<ObservableList<String>, String> column = new TableColumn<>(COLUMN_I18N_FALLBACKS[columnIndex]);
                column.textProperty().bind(I18n.bind(COLUMN_I18N_KEYS[columnIndex], COLUMN_I18N_FALLBACKS[columnIndex]));
                column.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(columnIndex)));
                column.setCellFactory(col -> new CustomLostFocusCommitTableCell<ObservableList<String>, String>());
                column.setSortable(false);
                column.setEditable(true);
                column.setOnEditCommit(event -> {
                    // 获取当前行的模型数据（ObservableList<String>）
                    ObservableList<String> rowData = event.getRowValue();
                    // 获取编辑后的新值
                    String newValue = event.getNewValue();
                    // 获取当前编辑的列索引
                    int index = colsTableView.getColumns().indexOf(event.getTableColumn())-1;
                    
                    // 更新行数据中对应列的值
                    if (rowData != null && index >= 0 && index < rowData.size()) {
                        rowData.set(index, newValue);
                        log.info("单元格内容已更新: 行索引=" + event.getTablePosition().getRow() + 
                                ", 列索引=" + index + ", 新值=\"" + newValue + "\"");
                    }
                    colsTableView.refresh();
                });
       
                // 设置列宽
                switch (columnIndex) {
                    case 0: // 列名
                        column.setPrefWidth(150);
                        break;
                    case 1: // 列类型
                        column.setPrefWidth(180);
                        break;
                
                    case 8: // 注释
                        column.setPrefWidth(180);
                        break;
                    default:
                        column.setPrefWidth(100);
                }
                if (columnIndex == 1) { // 列类型
                    column.setCellFactory(col -> {
                        return new TableCell<ObservableList<String>, String>() {
                            private final ChoiceBox<String> choiceBox = new ChoiceBox<>();
                              
                            {   // 初始化ChoiceBox
                                // 添加Informix数据库支持的所有数据类型
                                ObservableList<String> dataTypes = FXCollections.observableArrayList(
                                    // 数值类型
                                    "SMALLINT", "INTEGER", "BIGINT",  "SERIAL", "SERIAL8", "BIGSERIAL",
                                    "DECIMAL", "NUMERIC", "FLOAT", "MONEY", 
                                    // 字符串类型
                                    "CHAR", "VARCHAR","VARCHAR2","LVARCHAR", "NCHAR", "NVARCHAR", "NVARCHAR2",
                                    // 日期时间类型
                                    "DATE", "DATETIME YEAR TO SECOND","DATETIME YEAR TO FRACTION(5)", "INTERVAL",
                                    // 二进制类型
                                    "TEXT","BYTE", "BLOB", "CLOB",
                                    // 其他类型
                                    "BOOLEAN", "JSON","BSON"
                                );
                                choiceBox.setItems(dataTypes);
                                choiceBox.setPrefWidth(150);
                                choiceBox.setMinWidth(150);
                                choiceBox.setMaxWidth(150);
                                // 设置ChoiceBox的高度，与表格行高(21px)匹配
                                choiceBox.setPrefHeight(18);
                                choiceBox.setMinHeight(18);
                                choiceBox.setMaxHeight(18);
                                
                                // 移除默认边框和背景，使其看起来更像普通文本
                                choiceBox.setStyle(
                                    "-fx-background-color: transparent; " +
                                    "-fx-border-color: transparent; " +
                                    "-fx-background-insets: 0; " +
                                    "-fx-border-insets: 0; " +
                                    "-fx-padding: 0; " +
                                    "-fx-font-size: 9; " +
                                    "-fx-cell-size: 18; " // 设置下拉选项的高度
                                );
                                choiceBox.setFocusTraversable(false);
                                choiceBox.setDisable(readOnlyConnect);
                            
                                
                                // 添加CSS类，用于设置下拉列表的样式
                                choiceBox.getStyleClass().add("table-choice-box");
                                
                                // 监听ChoiceBox的值变化，直接更新数据
                                choiceBox.valueProperty().addListener((obs, oldValue, newValue) -> {
                                    if (newValue != null && !newValue.equals(oldValue)) {
                                        int rowIndex = getIndex();
                                        if (rowIndex < 0 || getTableView() == null || rowIndex >= getTableView().getItems().size()) {
                                            return;
                                        }
                                        // 获取当前行的数据
                                        ObservableList<String> rowData = getTableView().getItems().get(rowIndex);
                                        // 更新列类型值
                                        rowData.set(columnIndex, newValue);
                                        // SERIAL/SERIAL8/BIGSERIAL 自动勾选自增
                                        if (rowData.size() > 6) {
                                            rowData.set(6, isAutoIncrementType(newValue) ? "是" : "否");
                                        }
                                    }
                                });
                                
                                setAlignment(Pos.CENTER_LEFT);
                            }
                            
                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                
                                if (empty) {
                                    setGraphic(null);
                                    setText(null);
                                } else {
                                    // 始终显示ChoiceBox
                                    choiceBox.setValue(item);
                                    setGraphic(choiceBox);
                                    setText(null);
                                }
                            }
                            
                            @Override
                            public void startEdit() {
                                // 重写startEdit但不做任何操作，因为始终显示ChoiceBox
                            }
                            
                            @Override
                            public void cancelEdit() {
                                // 重写cancelEdit但不做任何操作，因为始终显示ChoiceBox
                            }
                        };
                    });
                } else if (columnIndex == 4 ) { // 是否可空
                    column.setCellFactory(col -> {
                        return new TableCell<ObservableList<String>, String>() {
                            private final CheckBox checkBox = new CheckBox();
                            {
                                //checkBox.setDisable(true); // 设置为只读
                                checkBox.setDisable(readOnlyConnect);
                                checkBox.setAlignment(Pos.CENTER);
                                setAlignment(Pos.CENTER);
                                checkBox.getStyleClass().add("table-check-box");
                                setGraphic(checkBox);
                                checkBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                                    int rowIndex = getIndex();
                                    if (rowIndex < 0 || getTableView() == null || rowIndex >= getTableView().getItems().size()) {
                                        return;
                                    }
                                    ObservableList<String> rowData = getTableView().getItems().get(rowIndex);
                                    if (rowData != null && rowData.size() > 4) {
                                        rowData.set(4, newValue ? "是" : "否");
                                    }
                                });
                            }
                            
                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                if (empty) {
                                    setGraphic(null);
                                } else {
                                    checkBox.setSelected("是".equals(item));
                                
                                    setGraphic(checkBox);
                                }
                            }
                        };
                    });
                } else if (columnIndex == 5) { // 主键
                    column.setCellFactory(col -> {
                        return new TableCell<ObservableList<String>, String>() {
                            private final CheckBox checkBox = new CheckBox();
                            {
                                checkBox.setDisable(!createMode); // 新建表可编辑
                                checkBox.setAlignment(Pos.CENTER);
                                setAlignment(Pos.CENTER);
                                checkBox.getStyleClass().add("table-check-box");
                                setGraphic(checkBox);
                                checkBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                                    if (!createMode) {
                                        return;
                                    }
                                    int rowIndex = getIndex();
                                    if (rowIndex < 0 || getTableView() == null || rowIndex >= getTableView().getItems().size()) {
                                        return;
                                    }
                                    ObservableList<String> rowData = getTableView().getItems().get(rowIndex);
                                    if (rowData != null && rowData.size() > 5) {
                                        rowData.set(5, newValue ? "是" : "否");
                                    }
                                });
                            }
                            
                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                if (empty) {
                                    setGraphic(null);
                                } else {
                                    checkBox.setSelected("是".equals(item));
                                
                                    setGraphic(checkBox);
                                }
                            }
                        };
                    });
                } else if (columnIndex == 6) { // 自增
                    column.setCellFactory(col -> {
                        return new TableCell<ObservableList<String>, String>() {
                            private final CheckBox checkBox = new CheckBox();
                            {
                                checkBox.setDisable(true); // 只读，按类型自动设置
                                checkBox.setAlignment(Pos.CENTER);
                                setAlignment(Pos.CENTER);
                                checkBox.getStyleClass().add("table-check-box");
                                setGraphic(checkBox);
                            }

                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                if (empty) {
                                    setGraphic(null);
                                } else {
                                    checkBox.setSelected("是".equals(item));

                                    setGraphic(checkBox);
                                }
                            }
                        };
                    });
                }
                
                colsTableView.getColumns().add(column);
            }
        colsTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        //TableColumn<?, ?> firstColumn = (TableColumn<?, ?>) colsTableView.getColumns().get(0);
        //firstColumn.setMaxWidth(30);


        // 刷新按钮
        refreshButton = new Button();
        refreshButton.getStyleClass().add("codearea-camera-button");
        refreshButton.setGraphic(IconFactory.group(IconPaths.METADATA_REFRESH_ITEM, 0.7));
        refreshButton.setFocusTraversable(false);
        Tooltip refreshTooltip = new Tooltip();
        refreshTooltip.textProperty().bind(I18n.bind("tableinfo.button.refresh", "刷新"));
        refreshButton.setTooltip(refreshTooltip);
        refreshButton.setOnAction(e -> {
            refreshCurrentTab();
                });

        
        // 新增列按钮
        addColumnButton = new Button();
        addColumnButton.getStyleClass().add("codearea-camera-button");
        addColumnButton.setGraphic(IconFactory.group(IconPaths.TABLEINFO_ADD_COLUMN, 0.65));
        addColumnButton.setFocusTraversable(false);
        Tooltip addColumnTooltip = new Tooltip();
        addColumnTooltip.textProperty().bind(I18n.bind("tableinfo.button.add_column", "增加列"));
        addColumnButton.setTooltip(addColumnTooltip);
        addColumnButton.setOnAction(e -> {
            addNewColumn();
        });
        
        // 删除列按钮
        deleteColumnButton = new Button();
        deleteColumnButton.getStyleClass().add("codearea-camera-button");
        deleteColumnButton.setGraphic(IconFactory.group(IconPaths.TABLEINFO_DELETE_COLUMN, 0.7, IconFactory.dangerColor()));
        deleteColumnButton.setFocusTraversable(false);
        Tooltip deleteColumnTooltip = new Tooltip();
        deleteColumnTooltip.textProperty().bind(I18n.bind("tableinfo.button.delete_column", "删除当前列"));
        deleteColumnButton.setTooltip(deleteColumnTooltip);
        deleteColumnButton.setOnAction(e -> {
            deleteSelectedColumn();
        });

        // 删除列按钮
        saveColumnButton = new Button();
        saveColumnButton.getStyleClass().add("codearea-camera-button");
        saveColumnButton.setGraphic(IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.6));
        saveColumnButton.setFocusTraversable(false);
        Tooltip saveColumnTooltip = new Tooltip();
        saveColumnTooltip.textProperty().bind(I18n.bind("tableinfo.button.save_changes", "保存变更"));
        saveColumnButton.setTooltip(saveColumnTooltip);
        saveColumnButton.setOnAction(e -> {
            if (tableChangeSubmitting) {
                NotificationUtil.showNotification(
                        AppState.getNoticePane(),
                        I18n.t("tableinfo.save_changes.running", "表结构变更正在后台执行，请等待完成后再保存")
                );
                return;
            }
            List<String> alterSQLList = this.createMode ? generateCreateTableSQL() : generateAlterTableSQL();
            if (alterSQLList == null || alterSQLList.isEmpty()) {
                NotificationUtil.showNotification(
                        AppState.getNoticePane(),
                        I18n.t("tableinfo.save_changes.no_sql", "未检测到可执行的变更SQL")
                );
                return;
            }

            if (!showSaveSqlConfirmDialog(alterSQLList)) {
                return;
            }

            String alterSQL = String.join("\n", alterSQLList);
            log.info("\n" + alterSQL);
            tableChangeSubmitting = true;
            Connect connect=MetadataTreeviewUtil.buildObjectConnect(treeItem,false);
            MetadataTreeviewUtil.tableService.modifyTableFromUi(
                    connect,
                    alterSQLList,
                    () -> {
                        try {


                            rebuildChangeBaselineFromCurrentTable();
                            syncTableMetaBaselineAfterSave();
                            if (this.createMode) {
                                this.createMode = false;
                                colsTableView.refresh();
                            }
                            NotificationUtil.showNotification(
                                    AppState.getNoticePane(),
                                    I18n.t("tableinfo.save_changes.success", "表结构变更已成功执行")
                            );
                            colsTabLoaded = false;
                            ddlTabLoaded = false;
                        
                        } catch (Exception ex) {
                            log.error("保存回调处理失败", ex);
                            try {
                                NotificationUtil.showNotification(
                                        AppState.getNoticePane(),
                                        I18n.t("tableinfo.save_changes.success", "表结构变更已成功执行")
                                );
                            } catch (Exception notifyEx) {
                                log.error("保存成功提示失败", notifyEx);
                            }
                        } finally {
                            tableChangeSubmitting = false;
                        }
                    },
                    () -> tableChangeSubmitting = false
            );
            NotificationUtil.showNotification(
                    AppState.getNoticePane(),
                    I18n.t("tableinfo.save_changes.queued", "变更SQL已提交后台任务，按顺序执行（共 %d 条）").formatted(alterSQLList.size())
            );
        });

        tableNameField = new CustomUserTextField();
        tableNameField.setPromptText(I18n.t("tableinfo.table_name", "表名"));
        tableNameField.setPrefWidth(220);
        tableNameField.setMinWidth(160);
        tableNameField.setStyle("-fx-padding: 1 1 1 3");

        tableCommentField = new CustomUserTextField();
        tableCommentField.setPromptText(I18n.t("tableinfo.table_comment", "表描述"));
        tableCommentField.setPrefWidth(380);
        tableCommentField.setMinWidth(220);
        tableCommentField.setStyle("-fx-padding: 1 1 1 3");

        // 给每个 Tab 绑定「首次选中加载」逻辑
        bindLazyLoadToTab(colsTab, () -> loadColsTabContent(colsTab), () -> colsTabLoaded, (loaded) -> colsTabLoaded = loaded);
        bindLazyLoadToTab(ddlTab, () -> loadDdlTabContent(ddlTab), () -> ddlTabLoaded, (loaded) -> ddlTabLoaded = loaded);
        mainTabPane.getTabs().addAll(colsTab, ddlTab);

        
        mainTabPane.setStyle("-fx-background-color: #fff;");
        StackPane  stackPane=new StackPane(mainTabPane, refreshButton);
        StackPane.setAlignment(refreshButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(refreshButton, new Insets(0, 15, 20, 0));

        setContent(stackPane);
        mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabPane.setSide(Side.BOTTOM);

        //refreshButton.fire();
        mainTabPane.setId("instanceManagerTabPane");


        if(readOnlyConnect){
            saveColumnButton.setDisable(true);
            addColumnButton.setDisable(true);
            deleteColumnButton.setDisable(true);
            colsTableView.setEditable(false);
            tableNameField.setDisable(true);
            tableCommentField.setDisable(true);
        }
    }

    private boolean showSaveSqlConfirmDialog(List<String> sqlList) {
        StringBuilder sqlContent = new StringBuilder();
        for (int i = 0; i < sqlList.size(); i++) {
            sqlContent.append(sqlList.get(i));
            if (i < sqlList.size() - 1) {
                sqlContent.append(System.lineSeparator());
            }
        }
        return PopupWindowUtil.openSqlConfirmPopupWindow(
                "tableinfo.save_changes.dialog.title",
                "执行变更SQL",
                "tableinfo.save_changes.dialog.execute",
                "执行",
                "tableinfo.save_changes.dialog.cancel",
                "取消",
                sqlContent.toString()
        );
    }



    private void refreshCurrentTab() {
        Tab currentTab = mainTabPane.getSelectionModel().getSelectedItem();
        if (currentTab == null) return;

        // 显示加载占位符
        Node currentContent = currentTab.getContent();
        if (currentContent != null && "loadingNode".equals(currentContent.getId())) {
            // 可选：提示用户正在加载中
            //NotificationUtil.showNotification(Main.mainController.noticePane, "当前Tab正在加载中，请稍后再试！");
            return;
        }
        currentTab.setContent(createLoadingNode());

        // 异步刷新（避免阻塞 UI）

        Task task = new Task<>() {
                                @Override
                                protected Void call()  {
                                   try {
                // 根据当前 Tab 类型执行刷新
                if (currentTab == ddlTab) {
                    loadDdlTabContent(ddlTab);
                } else if (currentTab == colsTab) {
                    loadColsTabContent(colsTab);
                }

            } catch (Exception e) {
                log.error(e.getMessage(), e);
                Platform.runLater(() -> {
                    currentTab.setContent(createErrorNode(e.getMessage()));
                });
            }
                                   return null;
                                }
                            };
        connect.executeSqlTask(task);
    }

    

    
    /**
     * 通用 Tab 懒加载绑定方法（抽取公共逻辑，避免重复代码）
     * @param tab 目标 Tab
     * @param loadTask 加载内容的任务（无返回值）
     * @param isLoaded 判断是否已加载的回调
     * @param setLoaded 设置已加载状态的回调
     */
    private void bindLazyLoadToTab(Tab tab, Runnable loadTask, Supplier<Boolean> isLoaded, Consumer<Boolean> setLoaded) {
        // 初始化 Tab 内容为「加载占位符」（可选，提升体验）
        tab.setContent(createLoadingNode());

        // 绑定选中状态监听器
        tab.selectedProperty().addListener((obs, oldVal, newVal) -> {
            // 仅当「首次选中」（newVal 为 true，且未加载）时触发加载
            if (newVal && !isLoaded.get()) {
                // 启动线程执行加载任务（避免阻塞 UI）

                    Task task = new Task<>() {
                                @Override
                                protected Void call()  {
                    try {
                        setLoaded.accept(true); // 标记为已加载
                        loadTask.run(); // 执行加载逻辑
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                        // 加载失败：显示错误信息
                        Platform.runLater(() -> {
                            tab.setContent(createErrorNode(e.getMessage()));
                        });
                    }
                    return null;

                }               

            };
                            connect.executeSqlTask(task);
        }

        });
    }

    // ------------------------------ 各个 Tab 的加载逻辑 ------------------------------
    /**
     * 加载「实例信息」Tab 内容（示例，需根据实际业务补充）
     */
    private void loadDdlTabContent(CustomTab ddlTab)  {
        if (createMode) {
            Platform.runLater(() -> {
                CustomInfoStackPane ddlCodeareaStackPane = new CustomInfoStackPane(new CustomInfoCodeArea());
                String previewDdl = String.join(System.lineSeparator(), generateCreateTableSQL());
                ddlCodeareaStackPane.codeArea.replaceText(SqlParserUtil.formatSql(previewDdl));
                Platform.runLater(() -> {
                    ddlCodeareaStackPane.codeArea.setStyleSpans(0,KeywordsHighlightUtil.applyHighlighting(ddlCodeareaStackPane.codeArea.getText()));
                });
                ddlTab.setContent(ddlCodeareaStackPane);
                ddlCodeareaStackPane.codeArea.showParagraphAtTop(0);
            });
            return;
        }

            try {
                ddl = SqlParserUtil.formatSql(MetadataTreeviewUtil.tableService.getDDL(connect,database, tableName));
                ;
            } catch (Exception e) {
                // TODO Auto-generated catch block
                GlobalErrorHandlerUtil.handle(e);

            }

        //信息框绑定
        Platform.runLater(() -> {
            

            //实例信息界面
            CustomInfoStackPane ddlCodeareaStackPane = new CustomInfoStackPane(new CustomInfoCodeArea());

            //实例信息界面结束
            ddlCodeareaStackPane.codeArea.replaceText(ddl); //如果不用settext，而是appendtext追加，会导致setScrollTop无效
                    Platform.runLater(() -> {
                        ddlCodeareaStackPane.codeArea.setStyleSpans(0,KeywordsHighlightUtil.applyHighlighting(ddlCodeareaStackPane.codeArea.getText()));
                    });

            ddlTab.setContent(ddlCodeareaStackPane);
            ddlCodeareaStackPane.codeArea.showParagraphAtTop(0); //此方法有效
        });

    }

    

    private void loadColsTabContent(CustomTab checkTab) {
        if (createMode) {
            ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
            ObservableList<String> defaultRow = FXCollections.observableArrayList();
            defaultRow.add("column1");
            defaultRow.add("VARCHAR");
            defaultRow.add("100");
            defaultRow.add("");
            defaultRow.add("否");
            defaultRow.add("否");
            defaultRow.add("否");
            defaultRow.add("");
            defaultRow.add("");
            data.add(defaultRow);
            String finalTableName = tableName;
            Platform.runLater(() -> {
                colsTableView.getItems().clear();
                colsTableView.getItems().setAll(data);
                rebuildChangeBaselineFromCurrentTable();
                tableNameField.setText(finalTableName);
                tableCommentField.setText("");
                originalTableName = "";
                originalTableComment = "";
                rowOriginalNameMap.put(defaultRow, null);
                rowIsNewMap.put(defaultRow, Boolean.TRUE);

                colsTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
                colsTableView.getSelectionModel().setCellSelectionEnabled(true);

                HBox buttonBox = new HBox();
                buttonBox.getChildren().addAll(addColumnButton, deleteColumnButton,saveColumnButton);

                Label tableNameLabel = new Label();
                tableNameLabel.textProperty().bind(I18n.bind("tableinfo.table_name", "表名"));
                Label tableCommentLabel = new Label();
                tableCommentLabel.textProperty().bind(I18n.bind("tableinfo.table_comment", "表描述"));
                HBox tableInfoBox = new HBox(8, tableNameLabel, tableNameField, tableCommentLabel, tableCommentField);
                tableInfoBox.setStyle("-fx-background-color: #f0f0f0;");
                tableInfoBox.setAlignment(Pos.CENTER_LEFT);
                tableInfoBox.setPadding(new Insets(8, 10, 6, 10));

                buttonBox.setMaxHeight(15);
                buttonBox.setMaxWidth(50);
                colsStackPane = new StackPane(colsTableView, buttonBox);
                StackPane.setAlignment(buttonBox, Pos.BOTTOM_RIGHT);
                StackPane.setMargin(buttonBox, new Insets(0, 38, -1, 0));
                colsStackPane.setStyle("-fx-background-color: #f0f0f0;");

                VBox colsRoot = new VBox(tableInfoBox, colsStackPane);
                VBox.setVgrow(colsStackPane, javafx.scene.layout.Priority.ALWAYS);
                colsTab.setContent(colsRoot);
            });
            return;
        }

        // 获取列信息
        ArrayList<ColumnsInfo> colInfo = new ArrayList<>();
        String tableComment = "";


            try {
                colInfo =  MetadataTreeviewUtil.tableService.getColumns(connect,database, tableName)
                ;
                tableComment = MetadataTreeviewUtil.tableService.getTableComment(
                        connect,
                        database,
                        tableName
                );
            } catch (Exception e) {
                // TODO Auto-generated catch block
                GlobalErrorHandlerUtil.handle(e);

            }
            

        
        // 准备表格数据
        ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
        
        for (ColumnsInfo column : colInfo) {
            ObservableList<String> row = FXCollections.observableArrayList();
            //row.add(String.valueOf(column.getColNo()));
            row.add(column.getColName());
            String colType = column.getColType();
            row.add(colType);
            String colLength = String.valueOf(column.getTypeP());
            if (isTypeWithoutLengthScale(colType)) {
                colLength = "";
            }
            row.add(colLength);
            //row.add(String.valueOf(column.getTypeP()));
            String colScale = String.valueOf(column.getTypeS()).equals("0") ? "" : String.valueOf(column.getTypeS());
            if (isTypeWithoutLengthScale(colType)) {
                colScale = "";
            }
            row.add(colScale);
            row.add(column.isIsNullable() ? "否" : "是");
            row.add(column.isIsPK() ? "是" : "否");
            //row.add(column.getColDefType() != null ? column.getColDefType() : "");
            row.add(column.isIsAutoincrement() ? "是" : "否");
              row.add(formatDefaultValueForDisplay(column));
              row.add(column.getColComm() != null ? column.getColComm() : "");
              data.add(row);
            rowOriginalNameMap.put(row, column.getColName());
            rowIsNewMap.put(row, Boolean.FALSE);
        }

        String finalTableComment = tableComment;
        String finalTableName = (originalTableName != null && !originalTableName.trim().isEmpty())
                ? originalTableName.trim()
                : tableName;
        Platform.runLater(() -> {
            colsTableView.getItems().clear();
            colsTableView.getItems().setAll(data);
            rebuildChangeBaselineFromCurrentTable();
            tableNameField.setText(finalTableName);
            tableCommentField.setText(finalTableComment == null ? "" : finalTableComment);
            originalTableName = normalizeTableMetaValue(tableNameField.getText());
            originalTableComment = normalizeTableMetaValue(tableCommentField.getText());
            
            // 启用单元格多选功能
            colsTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            colsTableView.getSelectionModel().setCellSelectionEnabled(true);
            
            // 创建按钮容器
            HBox buttonBox = new HBox();
            buttonBox.getChildren().addAll(addColumnButton, deleteColumnButton,saveColumnButton);

            Label tableNameLabel = new Label();
            tableNameLabel.textProperty().bind(I18n.bind("tableinfo.table_name", "表名"));
            Label tableCommentLabel = new Label();
            tableCommentLabel.textProperty().bind(I18n.bind("tableinfo.table_comment", "表描述"));
            HBox tableInfoBox = new HBox(8, tableNameLabel, tableNameField, tableCommentLabel, tableCommentField);
            tableInfoBox.setStyle("-fx-background-color: #f0f0f0;");
            tableInfoBox.setAlignment(Pos.CENTER_LEFT);
            tableInfoBox.setPadding(new Insets(8, 10, 6, 10));
 
            
            // 创建StackPane，将表格和按钮放在不同层
            buttonBox.setMaxHeight(15);
            buttonBox.setMaxWidth(50);
            //buttonBox.setStyle("-fx-background-color: red;");
            colsStackPane = new StackPane(colsTableView, buttonBox);
            StackPane.setAlignment(buttonBox, Pos.BOTTOM_RIGHT);
            //buttonBox.setPadding(new Insets(5, 15, 5, 5));
            StackPane.setMargin(buttonBox, new Insets(0, 38, -1, 0));
            colsStackPane.setStyle("-fx-background-color: #f0f0f0;");
            
            // 显示表格和按钮
            VBox colsRoot = new VBox(tableInfoBox, colsStackPane);
            VBox.setVgrow(colsStackPane, javafx.scene.layout.Priority.ALWAYS);
            colsTab.setContent(colsRoot);
        });
    }

    private void rebuildChangeBaselineFromCurrentTable() {
        originalDataList.clear();
        rowOriginalNameMap.clear();
        rowIsNewMap.clear();
        for (ObservableList<String> row : colsTableView.getItems()) {
            originalDataList.add(FXCollections.observableArrayList(row));
            rowOriginalNameMap.put(row, row.get(0));
            rowIsNewMap.put(row, Boolean.FALSE);
        }
    }

    

    


    // ------------------------------ 补充必要的函数式接口（若项目未引入 Java 8+ 内置接口） ------------------------------
    @FunctionalInterface
    private interface Supplier<T> {
        T get();
    }

    @FunctionalInterface
    private interface Consumer<T> {
        void accept(T t);
    }

    private Node createLoadingNode() {
        ImageView loadingIcon = IconFactory.loadingImageView(0.7);
        StackPane loadingPane = new StackPane(loadingIcon);
        loadingPane.setId("loadingNode");
        return loadingPane;

    }

    /**
     * 新增列功能
     */
    private void addNewColumn() {
        // 创建一个新的空白行
        ObservableList<String> newRow = FXCollections.observableArrayList();
        newRow.add(getNextNewColumnName()); // 列名
        newRow.add("VARCHAR"); // 列类型（默认VARCHAR）
        newRow.add("100"); // 长度
        newRow.add(""); // 标度
        newRow.add("否"); // 非空（默认否=允许空）
        newRow.add("否"); // 主键（默认否）
        newRow.add("否"); // 自增（默认否）
        newRow.add(""); // 默认值
        newRow.add(""); // 注释
        
        // 添加到表格中：插入到当前选中行之后
        int selectedIndex = colsTableView.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0) {
            colsTableView.getItems().add(newRow);
            int newIndex = colsTableView.getItems().size() - 1;
            colsTableView.getSelectionModel().clearSelection();
            colsTableView.getSelectionModel().select(newIndex);
            colsTableView.scrollTo(newIndex);
        } else {
            int insertIndex = Math.min(selectedIndex + 1, colsTableView.getItems().size());
            colsTableView.getItems().add(insertIndex, newRow);
            colsTableView.getSelectionModel().clearSelection();
            colsTableView.getSelectionModel().select(insertIndex);
            colsTableView.scrollTo(insertIndex);
        }
        colsTableView.requestFocus();
        rowOriginalNameMap.put(newRow, null);
        rowIsNewMap.put(newRow, Boolean.TRUE);
    }

    private String getNextNewColumnName() {
        int idx = 1;
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (Object item : colsTableView.getItems()) {
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) item;
            if (row != null && row.size() > 0) {
                existing.add(row.get(0));
            }
        }
        String name;
        do {
            name = "new_column_" + idx;
            idx++;
        } while (existing.contains(name));
        return name;
    }
    
    /**
     * 删除列功能
     */
    private void deleteSelectedColumn() {
        // 在开启单元格选择模式时，getSelectedIndices 可能包含列索引或超界索引，这里改用 SelectedCells 抽取唯一行号。
        ObservableList<TablePosition> selectedCells = colsTableView.getSelectionModel().getSelectedCells();
        java.util.Set<Integer> rowSet = new java.util.HashSet<>();
        for (TablePosition pos : selectedCells) {
            rowSet.add(pos.getRow());
        }

        if (rowSet.isEmpty()) {
            AlterUtil.CustomAlert(
                    I18n.t("tableinfo.delete_column.alert.title", "提示"),
                    I18n.t("tableinfo.delete_column.alert.no_selection", "请先选择要删除的列")
            );
            return;
        }

        // 至少保留一列
        if (colsTableView.getItems() == null || colsTableView.getItems().size() <= 1) {
            AlterUtil.CustomAlert(
                    I18n.t("tableinfo.delete_column.alert.title", "提示"),
                    I18n.t("tableinfo.delete_column.alert.only_one_left", "当前仅剩一列，不能删除")
            );
            return;
        }

        // 过滤掉超界索引，避免 IndexOutOfBounds
        int maxIdx = colsTableView.getItems().size() - 1;
        List<Integer> indicesToRemove = rowSet.stream()
                .filter(i -> i >= 0 && i <= maxIdx)
                .sorted(java.util.Collections.reverseOrder())
                .toList();

        if (indicesToRemove.isEmpty()) {
            return;
        }

        int minRemoved = indicesToRemove.stream().min(Integer::compareTo).orElse(0);
        for (Integer index : indicesToRemove) {
            colsTableView.getItems().remove(index.intValue());
        }

        // 删除后自动选中靠近被删行的现存行：若删的是最后一行，则选中新的最后一行（原倒数第二）
        if (!colsTableView.getItems().isEmpty()) {
            int newSize = colsTableView.getItems().size();
            int target = Math.min(minRemoved, newSize - 1);
            colsTableView.getSelectionModel().clearSelection();
            colsTableView.getSelectionModel().select(target);
            colsTableView.scrollTo(target);
        }
    }
    
    /**
 * 生成修改表结构的SQL语句
 * @return 修改表结构的SQL语句
 */
/**
 * 生成修改表结构的SQL语句
 * @return 修改表结构的SQL语句
 */
public List<String> generateAlterTableSQL() {
    List<String> sqlList = new ArrayList<>();
    List<String> modifyDefs = new ArrayList<>();
    
    // 获取表名（列变更使用旧表名，重命名放在最后执行）
    String tableName = originalTableName == null || originalTableName.trim().isEmpty()
            ? this.tableName.trim()
            : originalTableName.trim();
    String targetTableName = normalizeTableMetaValue(tableNameField == null ? null : tableNameField.getText());
    if (targetTableName.isEmpty()) {
        targetTableName = tableName;
    }
    String schemaName = null;
    String originalComment = normalizeTableMetaValue(originalTableComment);
    String currentComment = normalizeTableMetaValue(tableCommentField == null ? null : tableCommentField.getText());
    
    // 遍历每一行数据，比较原始数据和修改后的数据
    ObservableList<ObservableList<String>> currentData = colsTableView.getItems();

    List<String> originalNames = originalDataList.stream()
            .map(row -> row.get(0))
            .collect(Collectors.toList());
    List<String> currentNames = currentData.stream()
            .map(row -> row.get(0))
            .collect(Collectors.toList());

    // name -> row
    java.util.Map<String, ObservableList<String>> originalMap = new java.util.LinkedHashMap<>();
    for (ObservableList<String> row : originalDataList) {
        if (row != null && row.size() > 0) {
            originalMap.put(row.get(0), row);
        }
    }
    java.util.Map<String, List<String>> originalBySig = new java.util.HashMap<>();
    for (ObservableList<String> row : originalDataList) {
        if (row != null && row.size() > 0) {
            String sig = buildColumnSignature(row);
            originalBySig.computeIfAbsent(sig, k -> new ArrayList<>()).add(row.get(0));
        }
    }
    java.util.Set<String> matchedOriginal = new java.util.HashSet<>();
    java.util.Set<String> matchedCurrent = new java.util.HashSet<>();

    // 1. 处理列名修改和其他属性修改
    List<ObservableList<String>> newRows = new ArrayList<>();
    for (int i = 0; i < currentData.size(); i++) {
        ObservableList<String> currentRow = currentData.get(i);
        String currentColumnName = currentRow.get(0);

        if (Boolean.TRUE.equals(rowIsNewMap.get(currentRow))) {
            newRows.add(currentRow);
            continue;
        }

        String originalNameHint = rowOriginalNameMap.get(currentRow);
        ObservableList<String> originalRow = null;
        if (originalNameHint != null && !safeEquals(originalNameHint, currentColumnName)) {
            ObservableList<String> originalRowByHint = originalMap.get(originalNameHint);
            if (originalRowByHint != null) {
                String renameSQL = generateRenameColumnSQL(schemaName, tableName, originalNameHint, currentColumnName);
                  addSql(sqlList, renameSQL);
                matchedOriginal.add(originalNameHint);
                matchedCurrent.add(currentColumnName);
                originalRow = originalRowByHint;
            }
        }

        boolean matchedByName = false;
        if (originalRow == null) {
            originalRow = originalMap.get(currentColumnName);
            if (originalRow != null) {
                matchedByName = true;
            }
        }
        if (originalRow == null && originalNameHint != null) {
            String sig = buildColumnSignature(currentRow);
            List<String> candidates = originalBySig.get(sig);
            if (candidates != null) {
                String found = null;
                for (String candidate : candidates) {
                    if (!matchedOriginal.contains(candidate)) {
                        if (found == null) {
                            found = candidate;
                        } else {
                            found = null;
                            break;
                        }
                    }
                }
                if (found != null && !safeEquals(found, currentColumnName)) {
                    String renameSQL = generateRenameColumnSQL(schemaName, tableName, found, currentColumnName);
                      addSql(sqlList, renameSQL);
                    originalRow = originalMap.get(found);
                    matchedOriginal.add(found);
                    matchedCurrent.add(currentColumnName);
                }
            }
        } else {
            if (matchedByName) {
                matchedOriginal.add(currentColumnName);
                matchedCurrent.add(currentColumnName);
            }
        }

        if (originalRow != null) {
            boolean typeChanged = false;
            boolean lengthChanged = false;
            boolean scaleChanged = false;
            boolean notNullChanged = false;
            boolean defaultChanged = false;
            if (currentRow.size() > 1 && originalRow.size() > 1) {
                typeChanged = !safeEquals(currentRow.get(1), originalRow.get(1));
            }
            if (currentRow.size() > 2 && originalRow.size() > 2) {
                lengthChanged = !safeEquals(currentRow.get(2), originalRow.get(2));
            }
            if (currentRow.size() > 3 && originalRow.size() > 3) {
                scaleChanged = !safeEquals(currentRow.get(3), originalRow.get(3));
            }
            if (currentRow.size() > 4 && originalRow.size() > 4) {
                notNullChanged = !safeEquals(currentRow.get(4), originalRow.get(4));
            }
            if (currentRow.size() > 7 && originalRow.size() > 7) {
                defaultChanged = !safeEquals(currentRow.get(7), originalRow.get(7));
            }

            // 类型/长度/标度/非空/默认值合并为一个 ALTER
            if (typeChanged || lengthChanged || scaleChanged || notNullChanged || defaultChanged) {
                String modifyDef = buildModifyDefinition(currentRow, currentColumnName, 4, currentRow.get(4));
                if (modifyDef != null && !modifyDef.isEmpty()) {
                    modifyDefs.add(modifyDef);
                }
            }

            // 比较每一列的数据，处理其他属性修改（跳过已合并的 1/2/3/4/7）
            for (int j = 5; j < currentRow.size(); j++) {
                if (j < originalRow.size()) {
                    if (j == 7) {
                        continue;
                    }
                    String currentValue = currentRow.get(j);
                    String originalValue = originalRow.get(j);
                    
                    if (!safeEquals(currentValue, originalValue)) {
                        // 生成对应的ALTER TABLE语句
                        String alterSQL = generateColumnAlterSQL(schemaName, tableName, currentColumnName, j, currentValue, originalValue);
                        if (alterSQL != null && !alterSQL.isEmpty()) {
                            addSql(sqlList, alterSQL);
                        }
                    }
                }
            }
        } else {
            // 新增列
            newRows.add(currentRow);
        }
    }

    // 多条 MODIFY 合并为一条
    if (!modifyDefs.isEmpty()) {
        String modifySQL = "ALTER TABLE " + tableName + " MODIFY (" + String.join(", ", modifyDefs) + ");";
        addSql(sqlList, modifySQL);
    }

    // 新增列：多个列合并为一个 ALTER TABLE ADD (...)
    String addSQL = generateAddColumnsSQL(schemaName, tableName, newRows);
    addSql(sqlList, addSQL);

    // 2. 处理删除的列（合并为一个 DROP）
    List<String> dropNames = new ArrayList<>();
    for (String originalName : originalNames) {
        if (!matchedOriginal.contains(originalName) && !currentNames.contains(originalName)) {
            dropNames.add(originalName);
        }
    }
    String dropSQL = generateDropColumnsSQL(schemaName, tableName, dropNames);
    addSql(sqlList, dropSQL);

    // 3. 表名修改
    boolean tableRenamed = !safeEquals(tableName, targetTableName);
    if (tableRenamed) {
        addSql(sqlList, generateRenameTableSQL(tableName, targetTableName));
    }

    // 4. 表注释修改
    if (!safeEquals(originalComment, currentComment)) {
        String commentTableName = tableRenamed ? targetTableName : tableName;
        addSql(sqlList, generateTableCommentSQL(commentTableName, currentComment));
    }
    
    return sqlList;
}

public List<String> generateCreateTableSQL() {
    List<String> sqlList = new ArrayList<>();
    String tableName = normalizeTableMetaValue(tableNameField == null ? null : tableNameField.getText());
    if (tableName.isEmpty()) {
        return sqlList;
    }

    ObservableList<ObservableList<String>> currentData = colsTableView.getItems();
    List<String> defs = new ArrayList<>();
    List<String> pkColumns = new ArrayList<>();
    if (currentData != null) {
        for (ObservableList<String> row : currentData) {
            String def = buildCreateColumnDefinition(row);
            if (def != null && !def.isEmpty()) {
                defs.add(def);
            }
            if (row != null && row.size() > 5 && "是".equals(row.get(5))) {
                String colName = normalizeTableMetaValue(row.get(0));
                if (!colName.isEmpty()) {
                    pkColumns.add(colName);
                }
            }
        }
    }
    if (defs.isEmpty()) {
        return sqlList;
    }
    if (!pkColumns.isEmpty()) {
        defs.add("PRIMARY KEY(" + String.join(",", pkColumns) + ")");
    }

    addSql(sqlList, "CREATE TABLE " + tableName + " (" + String.join(", ", defs) + ");");
    String tableComment = normalizeTableMetaValue(tableCommentField == null ? null : tableCommentField.getText());
    if (!tableComment.isEmpty()) {
        addSql(sqlList, generateTableCommentSQL(tableName, tableComment));
    }
    if (currentData != null) {
        for (ObservableList<String> row : currentData) {
            if (row == null || row.size() < 9) {
                continue;
            }
            String colName = normalizeTableMetaValue(row.get(0));
            String colComment = normalizeTableMetaValue(row.get(8));
            if (!colName.isEmpty() && !colComment.isEmpty()) {
                addSql(sqlList, generateCommentSQL("", tableName, colName, colComment));
            }
        }
    }
    return sqlList;
}

private String buildCreateColumnDefinition(ObservableList<String> columnRow) {
    if (columnRow == null || columnRow.size() < 2) {
        return "";
    }
    String colName = normalizeTableMetaValue(columnRow.get(0));
    String colType = normalizeTableMetaValue(columnRow.get(1));
    if (colName.isEmpty() || colType.isEmpty()) {
        return "";
    }
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append(colName).append(" ").append(colType);
    String length = columnRow.size() > 2 ? normalizeTableMetaValue(columnRow.get(2)) : "";
    String scale = columnRow.size() > 3 ? normalizeTableMetaValue(columnRow.get(3)) : "";
    if (!length.isEmpty()) {
        sqlBuilder.append("(").append(length);
        if (!scale.isEmpty() && !isTypeIgnoreScale(colType)) {
            sqlBuilder.append(",").append(scale);
        }
        sqlBuilder.append(")");
    }
    if (columnRow.size() > 4 && "是".equals(columnRow.get(4))) {
        sqlBuilder.append(" NOT NULL");
    }
    if (columnRow.size() > 7) {
        String defaultValue = normalizeTableMetaValue(columnRow.get(7));
        if (!defaultValue.isEmpty()) {
            sqlBuilder.append(" DEFAULT ").append(formatDefaultValue(defaultValue));
        }
    }
    return sqlBuilder.toString();
}

private void addSql(List<String> sqlList, String sql) {
    if (sqlList == null || sql == null) {
        return;
    }
    String[] parts = sql.split("\\r?\\n");
    for (String part : parts) {
        String trimmed = part.trim();
        if (!trimmed.isEmpty()) {
            sqlList.add(trimmed);
        }
    }
}

/**
 * 生成重命名列的SQL语句
 * @param schemaName 模式名
 * @param tableName 表名
 * @param oldColumnName 旧列名
 * @param newColumnName 新列名
 * @return 重命名列的SQL语句
 */
private String generateRenameColumnSQL(String schemaName, String tableName, 
                                      String oldColumnName, String newColumnName) {
    StringBuilder sqlBuilder = new StringBuilder();
    
    sqlBuilder.append("RENAME COLUMN ");
    // 去掉schema引用
    sqlBuilder.append(tableName).append(".").append(oldColumnName)
            .append(" TO ").append(newColumnName).append(";");
    
    return sqlBuilder.toString();
}
    

/**
 * 生成修改列的SQL语句
 * @param schemaName 模式名
 * @param tableName 表名
 * @param columnName 列名
 * @param columnIndex 列索引
 * @param currentValue 当前值
 * @param originalValue 原始值
 * @return 修改列的SQL语句
 */
private String generateColumnAlterSQL(String schemaName, String tableName, String columnName, 
                                      int columnIndex, String currentValue, String originalValue) {
    if (columnIndex == 8) {
        return generateCommentSQL(schemaName, tableName, columnName, currentValue);
    }
    StringBuilder sqlBuilder = new StringBuilder();
    
    sqlBuilder.append("ALTER TABLE ");
    // 去掉schema引用
    sqlBuilder.append(tableName).append(" MODIFY ").append(columnName).append(" ");
    
    // 获取当前行的完整数据，用于长度和标度修改时获取其他相关信息
    int rowIndex = getColumnIndexByName(columnName);
    ObservableList<String> currentRow = null;
    if (rowIndex >= 0 && rowIndex < colsTableView.getItems().size()) {
        currentRow = (ObservableList<String>) colsTableView.getItems().get(rowIndex);
    }
    
    // 定义与长度和标度无关的数据类型列表
    List<String> typesWithoutLengthScale = Arrays.asList(
        "SMALLINT", "INTEGER", "BIGINT", 
        "SERIAL", "SERIAL8", "BIGSERIAL",
        "DATE", "DATETIME", "INTERVAL",
        "BOOLEAN", 
        "BYTE", "BLOB", "CLOB", "TEXT",
        "JSON", "BSON","FLOAT"
    );
    
    switch (columnIndex) {
        case 1: // 列类型
            sqlBuilder.append(currentValue);
            // 检查数据类型是否需要长度和标度信息
            boolean needLengthScale = true;
            for (String type : typesWithoutLengthScale) {
                if (currentValue.toUpperCase().startsWith(type)) {
                    needLengthScale = false;
                    break;
                }
            }
            
            // 如果需要长度和标度信息且有相关数据，则包含
            if (needLengthScale && currentRow != null) {
                String length = currentRow.get(2);
                String scale = currentRow.get(3);
                if (length != null && !length.isEmpty()) {
                    sqlBuilder.append("(");
                    sqlBuilder.append(length);
                    if (scale != null && !scale.isEmpty() && !isTypeIgnoreScale(currentValue)) {
                        sqlBuilder.append(",").append(scale);
                    }
                    sqlBuilder.append(")");
                }
            }
            break;
        case 2: // 长度
            // 需要结合列类型一起修改
            if (currentRow != null) {
                String currentType = currentRow.get(1);
                sqlBuilder.append(currentType);
                
                // 检查数据类型是否需要长度信息
                boolean needLength = true;
                for (String type : typesWithoutLengthScale) {
                    if (currentType.toUpperCase().startsWith(type)) {
                        needLength = false;
                        break;
                    }
                }
                
                if (needLength && currentValue != null && !currentValue.isEmpty()) {
                    sqlBuilder.append("(");
                    sqlBuilder.append(currentValue);
                    String scale = currentRow.get(3);
                    if (scale != null && !scale.isEmpty() && !isTypeIgnoreScale(currentType)) {
                        sqlBuilder.append(",").append(scale);
                    }
                    sqlBuilder.append(")");
                }
            }
            break;
        case 3: // 标度
            // 需要结合列类型和长度一起修改
            if (currentRow != null) {
                String currentType = currentRow.get(1);
                String length = currentRow.get(2);
                sqlBuilder.append(currentType);
                
                // 检查数据类型是否需要长度和标度信息
                boolean needLengthScaleForScale = true;
                for (String type : typesWithoutLengthScale) {
                    if (currentType.toUpperCase().startsWith(type)) {
                        needLengthScaleForScale = false;
                        break;
                    }
                }
                
                if (needLengthScaleForScale && length != null && !length.isEmpty() && !isTypeIgnoreScale(currentType)) {
                    sqlBuilder.append("(");
                    sqlBuilder.append(length);
                    sqlBuilder.append(",").append(currentValue);
                    sqlBuilder.append(")");
                }
            }
            break;
        case 4: // 非空
            // Informix: MODIFY col type [NOT NULL]
            if (currentRow != null) {
                String currentType = currentRow.get(1);
                sqlBuilder.append(currentType);
                boolean needLength = true;
                for (String type : typesWithoutLengthScale) {
                    if (currentType.toUpperCase().startsWith(type)) {
                        needLength = false;
                        break;
                    }
                }
                if (needLength) {
                    String length = currentRow.get(2);
                    String scale = currentRow.get(3);
                    if (length != null && !length.isEmpty()) {
                        sqlBuilder.append("(");
                        sqlBuilder.append(length);
                        if (scale != null && !scale.isEmpty() && !isTypeIgnoreScale(currentType)) {
                            sqlBuilder.append(",").append(scale);
                        }
                        sqlBuilder.append(")");
                    }
                }
                String defaultValue = currentRow.get(7);
                if (defaultValue != null && !defaultValue.isEmpty()) {
                    sqlBuilder.append(" DEFAULT ").append(formatDefaultValue(defaultValue));
                }
                if ("是".equals(currentValue)) {
                    sqlBuilder.append(" NOT NULL");
                }
            } else {
                return null;
            }
            break;
        case 5: // 主键
            // 主键修改比较复杂，需要先删除再添加，暂时不支持
            return null;
        case 6: // 自增
            // 自增属性修改比较复杂，暂时不支持
            return null;
        case 7: // 默认值
            if (currentValue != null && !currentValue.isEmpty()) {
                sqlBuilder.append("SET DEFAULT ").append(formatDefaultValue(currentValue));
            } else {
                sqlBuilder.append("DROP DEFAULT");
            }
            break;
        default:
            return null;
    }
    
    sqlBuilder.append(";");
    return sqlBuilder.toString();
}

private String buildModifyDefinition(ObservableList<String> currentRow, String columnName,
                                     int columnIndex, String currentValue) {
    if (currentRow == null || columnName == null || columnName.isEmpty()) {
        return null;
    }
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append(columnName).append(" ");

    List<String> typesWithoutLengthScale = Arrays.asList(
            "SMALLINT", "INTEGER", "BIGINT",
            "SERIAL", "SERIAL8", "BIGSERIAL",
            "DATE", "DATETIME", "INTERVAL",
            "BOOLEAN",
            "BYTE", "BLOB", "CLOB", "TEXT",
            "JSON", "BSON",
            "FLOAT"
    );

    switch (columnIndex) {
        case 1: {
            String type = currentValue;
            if (type == null || type.isEmpty()) {
                return null;
            }
            sqlBuilder.append(type);
            boolean needLengthScale = true;
            for (String t : typesWithoutLengthScale) {
                if (type.toUpperCase().startsWith(t)) {
                    needLengthScale = false;
                    break;
                }
            }
            if (needLengthScale) {
                String length = currentRow.get(2);
                String scale = currentRow.get(3);
                if (length != null && !length.isEmpty()) {
                    sqlBuilder.append("(").append(length);
                    if (scale != null && !scale.isEmpty() && !isTypeIgnoreScale(type)) {
                        sqlBuilder.append(",").append(scale);
                    }
                    sqlBuilder.append(")");
                }
            }
            break;
        }
        case 2: {
            String type = currentRow.get(1);
            if (type == null || type.isEmpty()) {
                return null;
            }
            sqlBuilder.append(type);
            boolean needLength = true;
            for (String t : typesWithoutLengthScale) {
                if (type.toUpperCase().startsWith(t)) {
                    needLength = false;
                    break;
                }
            }
            if (needLength && currentValue != null && !currentValue.isEmpty()) {
                sqlBuilder.append("(").append(currentValue);
                String scale = currentRow.get(3);
                if (scale != null && !scale.isEmpty() && !isTypeIgnoreScale(type)) {
                    sqlBuilder.append(",").append(scale);
                }
                sqlBuilder.append(")");
            }
            break;
        }
        case 3: {
            String type = currentRow.get(1);
            String length = currentRow.get(2);
            if (type == null || type.isEmpty()) {
                return null;
            }
            sqlBuilder.append(type);
            boolean needLengthScale = true;
            for (String t : typesWithoutLengthScale) {
                if (type.toUpperCase().startsWith(t)) {
                    needLengthScale = false;
                    break;
                }
            }
            if (needLengthScale && length != null && !length.isEmpty() && !isTypeIgnoreScale(type)) {
                sqlBuilder.append("(").append(length).append(",").append(currentValue).append(")");
            }
            break;
        }
        case 4: {
            String type = currentRow.get(1);
            if (type == null || type.isEmpty()) {
                return null;
            }
            sqlBuilder.append(type);
            boolean needLength = true;
            for (String t : typesWithoutLengthScale) {
                if (type.toUpperCase().startsWith(t)) {
                    needLength = false;
                    break;
                }
            }
            if (needLength) {
                String length = currentRow.get(2);
                String scale = currentRow.get(3);
                if (length != null && !length.isEmpty()) {
                    sqlBuilder.append("(").append(length);
                    if (scale != null && !scale.isEmpty() && !isTypeIgnoreScale(type)) {
                        sqlBuilder.append(",").append(scale);
                    }
                    sqlBuilder.append(")");
                }
            }
            String defaultValue = currentRow.get(7);
            if (defaultValue != null && !defaultValue.isEmpty()) {
                sqlBuilder.append(" DEFAULT ").append(formatDefaultValue(defaultValue));
            }
            if ("是".equals(currentValue)) {
                sqlBuilder.append(" NOT NULL");
            }
            break;
        }
        default:
            return null;
    }

    return sqlBuilder.toString();
}

/**
 * 根据列名获取列索引
 * @param columnName 列名
 * @return 列索引，如果找不到返回-1
 */
private int getColumnIndexByName(String columnName) {
    if (columnName == null || columnName.isEmpty() || colsTableView == null) {
        return -1;
    }
    
    ObservableList<ObservableList<String>> data = colsTableView.getItems();
    for (int i = 0; i < data.size(); i++) {
        ObservableList<String> row = data.get(i);
        if (row != null && row.size() > 0) {
            String currentColumnName = row.get(0);
            if (columnName.equals(currentColumnName)) {
                return i;
            }
        }
    }
    return -1;
}

private boolean safeEquals(String a, String b) {
    if (a == null && b == null) {
        return true;
    }
    if (a == null || b == null) {
        return false;
    }
    return a.equals(b);
}

private String generateCommentSQL(String schemaName, String tableName, String columnName, String comment) {
    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("COMMENT ON COLUMN ");
    sqlBuilder.append(tableName).append(".").append(columnName).append(" IS ");
    if (comment != null && !comment.isEmpty()) {
        sqlBuilder.append("'").append(comment).append("'");
    } else {
        sqlBuilder.append("''");
    }
    sqlBuilder.append(";");
    return sqlBuilder.toString();
}

private String generateRenameTableSQL(String oldTableName, String newTableName) {
    if (oldTableName == null || newTableName == null) {
        return "";
    }
    String oldName = oldTableName.trim();
    String newName = newTableName.trim();
    if (oldName.isEmpty() || newName.isEmpty() || oldName.equals(newName)) {
        return "";
    }
    return "RENAME TABLE " + oldName + " TO " + newName + ";";
}

private String generateTableCommentSQL(String tableName, String comment) {
    if (tableName == null || tableName.trim().isEmpty()) {
        return "";
    }
    String normalizedComment = normalizeTableMetaValue(comment);
    return "COMMENT ON TABLE " + tableName.trim() + " IS '" + escapeSqlString(normalizedComment) + "';";
}

private String normalizeTableMetaValue(String value) {
    if (value == null) {
        return "";
    }
    return value.trim();
}

private String escapeSqlString(String value) {
    if (value == null || value.isEmpty()) {
        return "";
    }
    return value.replace("'", "''");
}

private void syncTableMetaBaselineAfterSave() {
    originalTableName = normalizeTableMetaValue(tableNameField == null ? null : tableNameField.getText());
    originalTableComment = normalizeTableMetaValue(tableCommentField == null ? null : tableCommentField.getText());
    if (!originalTableName.isEmpty()) {
        tableName = originalTableName;
        setTitle(buildTabTitle(originalTableName));
        setUserData(buildTableUserData(originalTableName));
    }
    if(treeItem != null && treeItem.getValue() != null&&(treeItem.getValue() instanceof Table)){
        treeItem.getValue().setName(originalTableName);
    }
    if(createMode){
        treeItem.getChildren().clear();
        treeItem.setExpanded(false);
        treeItem.setExpanded(true);
        waitAndSelectCreatedTableNode(originalTableName);

    }
}

private void waitAndSelectCreatedTableNode(String targetName) {
    if (targetName == null || targetName.isBlank() || treeItem == null) {
        return;
    }

    final int[] retries = {20};
    final javafx.collections.ListChangeListener<TreeItem<TreeData>>[] listenerRef = new javafx.collections.ListChangeListener[1];
    final Runnable[] trySelectRef = new Runnable[1];

    trySelectRef[0] = () -> {
        TreeItem<TreeData> found = null;
        for (TreeItem<TreeData> child : treeItem.getChildren()) {
            if (child != null && child.getValue() != null && targetName.equals(child.getValue().getName())) {
                found = child;
                break;
            }
        }

        if (found != null && AppState.getMainController() != null && AppState.getDatabaseMetaTreeView() != null) {
            TreeView<TreeData> metaTreeView = AppState.getDatabaseMetaTreeView();
            metaTreeView.getSelectionModel().clearSelection();
            metaTreeView.getSelectionModel().select(found);
            int row = Math.max(0, metaTreeView.getRow(found));
            double cellSize = metaTreeView.getFixedCellSize() > 0 ? metaTreeView.getFixedCellSize() : 24d;
            int visibleRows = Math.max(1, (int) Math.floor(metaTreeView.getHeight() / cellSize));
            int centerStartRow = Math.max(0, row - visibleRows / 2);
            metaTreeView.scrollTo(centerStartRow);
            if (listenerRef[0] != null) {
                treeItem.getChildren().removeListener(listenerRef[0]);
            }
            this.treeItem = found;
            return;
        }

        if (retries[0]-- > 0) {
            javafx.animation.PauseTransition d =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(100));
            d.setOnFinished(e -> trySelectRef[0].run());
            d.play();
        } else if (listenerRef[0] != null) {
            treeItem.getChildren().removeListener(listenerRef[0]);
        }
    };

    listenerRef[0] = c -> Platform.runLater(trySelectRef[0]);
    treeItem.getChildren().addListener(listenerRef[0]);

    Platform.runLater(trySelectRef[0]);
}


private String buildTabTitle(String tableName) {
    String currentTableName = (tableName == null || tableName.trim().isEmpty()) ? this.tableName : tableName.trim();
    return "[table]" + connect + "." + database + "." + currentTableName;
}

private String buildTableUserData(String tableName) {
    String currentTableName = (tableName == null || tableName.trim().isEmpty()) ? this.tableName : tableName.trim();
    return "table:" + connect + "." + database + "." + currentTableName;
}

private boolean isAutoIncrementType(String type) {
    if (type == null) {
        return false;
    }
    String upper = type.trim().toUpperCase();
    return upper.startsWith("SERIAL")
            || upper.startsWith("SERIAL8")
            || upper.startsWith("BIGSERIAL");
}

private boolean isTypeIgnoreScale(String type) {
    if (type == null) {
        return false;
    }
    return "CHAR".equalsIgnoreCase(type) || "FLOAT".equalsIgnoreCase(type);
}

private boolean isTypeWithoutLengthScale(String type) {
    if (type == null) {
        return false;
    }
    List<String> typesWithoutLengthScale = Arrays.asList(
            "SMALLINT", "INTEGER", "BIGINT",
            "SERIAL", "SERIAL8", "BIGSERIAL",
            "DATE", "DATETIME", "INTERVAL",
            "BOOLEAN",
            "BYTE", "BLOB", "CLOB", "TEXT",
            "JSON", "BSON",
            "FLOAT"
    );
    String upper = type.toUpperCase();
    for (String t : typesWithoutLengthScale) {
        if (upper.startsWith(t)) {
            return true;
        }
    }
    return false;
}

private String buildColumnSignature(ObservableList<String> row) {
    if (row == null) {
        return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < row.size(); i++) {
        sb.append("|").append(normalizeValue(row.get(i)));
    }
    return sb.toString();
}

private String normalizeValue(String value) {
    if (value == null) {
        return "";
    }
    return value.trim();
}

    /**
 * 生成新增列的SQL语句
 * @param schemaName 模式名
 * @param tableName 表名
 * @param columnRow 列数据行
 * @return 新增列的SQL语句
 */
private String generateAddColumnSQL(String schemaName, String tableName, ObservableList<String> columnRow) {
    StringBuilder sqlBuilder = new StringBuilder();
    
    sqlBuilder.append("ALTER TABLE ");
    // 去掉schema引用
    sqlBuilder.append(tableName).append(" ADD ").append(buildAddColumnDefinition(columnRow)).append(";\n");

    // 注释独立语句
    String comment = columnRow.get(8);
    if (comment != null && !comment.isEmpty()) {
        sqlBuilder.append(generateCommentSQL(schemaName, tableName, columnRow.get(0), comment)).append("\n");
    }
    
    return sqlBuilder.toString();
}

private String generateAddColumnsSQL(String schemaName, String tableName, List<ObservableList<String>> columnRows) {
    if (columnRows == null || columnRows.isEmpty()) {
        return "";
    }
    if (columnRows.size() == 1) {
        return generateAddColumnSQL(schemaName, tableName, columnRows.get(0));
    }

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("ALTER TABLE ").append(tableName).append(" ADD (");
    for (int i = 0; i < columnRows.size(); i++) {
        if (i > 0) {
            sqlBuilder.append(", ");
        }
        sqlBuilder.append(buildAddColumnDefinition(columnRows.get(i)));
    }
    sqlBuilder.append(");\n");

    for (ObservableList<String> row : columnRows) {
        String comment = row.get(8);
        if (comment != null && !comment.isEmpty()) {
            sqlBuilder.append(generateCommentSQL(schemaName, tableName, row.get(0), comment)).append("\n");
        }
    }
    return sqlBuilder.toString();
}

private String buildAddColumnDefinition(ObservableList<String> columnRow) {
    StringBuilder sqlBuilder = new StringBuilder();

    sqlBuilder.append(columnRow.get(0)).append(" ");

    // 列类型
    sqlBuilder.append(columnRow.get(1));

    // 长度和标度
    String length = columnRow.get(2);
    String scale = columnRow.get(3);
    if (length != null && !length.isEmpty()) {
        sqlBuilder.append("(");
        sqlBuilder.append(length);
        if (scale != null && !scale.isEmpty() && !isTypeIgnoreScale(columnRow.get(1))) {
            sqlBuilder.append(",").append(scale);
        }
        sqlBuilder.append(")");
    }

    // 非空约束
    if ("是".equals(columnRow.get(4))) {
        sqlBuilder.append(" NOT NULL");
    }

    // 默认值
    String defaultValue = columnRow.get(7);
    if (defaultValue != null && !defaultValue.isEmpty()) {
        sqlBuilder.append(" DEFAULT ").append(formatDefaultValue(defaultValue));
    }

    // 位置（before xxx）
    String beforeColumn = getNextColumnName(columnRow);
    if (beforeColumn != null && !beforeColumn.isEmpty()) {
        sqlBuilder.append(" BEFORE ").append(beforeColumn);
    }

    return sqlBuilder.toString();
}

private String formatDefaultValue(String value) {
    if (value == null) {
        return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
        return "";
    }
    if (trimmed.contains("'") ||
            (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("(") && trimmed.endsWith(")"))) {
        return trimmed;
    }
    return "(" + trimmed + ")";
}

private String formatDefaultValueForDisplay(ColumnsInfo column) {
    if (column == null) {
        return "";
    }
    String def = column.getColDef();
    if (def == null) {
        return "";
    }
    String trimmed = def.trim();
    if (trimmed.isEmpty()) {
        if ("L".equalsIgnoreCase(column.getColDefType()) && isStringType(column.getColType())) {
            return "''";
        }
        return "";
    }
    if (trimmed.contains("'")) {
        return trimmed;
    }
    if ("L".equalsIgnoreCase(column.getColDefType()) && isStringType(column.getColType())) {
        return "'" + trimmed + "'";
    }
    return trimmed;
}

private boolean isStringType(String type) {
    if (type == null) {
        return false;
    }
    String upper = type.toUpperCase();
    return upper.startsWith("CHAR")
            || upper.startsWith("VARCHAR")
            || upper.startsWith("VARCHAR2")
            || upper.startsWith("LVARCHAR")
            || upper.startsWith("NCHAR")
            || upper.startsWith("NVARCHAR")
            || upper.startsWith("NVARCHAR2")
            || upper.startsWith("TEXT");
}

private String getNextColumnName(ObservableList<String> columnRow) {
    if (colsTableView == null || columnRow == null) {
        return null;
    }
    int index = colsTableView.getItems().indexOf(columnRow);
    if (index < 0 || index >= colsTableView.getItems().size() - 1) {
        return null;
    }
    for (int i = index + 1; i < colsTableView.getItems().size(); i++) {
        @SuppressWarnings("unchecked")
        ObservableList<String> nextRow = (ObservableList<String>) colsTableView.getItems().get(i);
        if (nextRow == null || nextRow.isEmpty()) {
            continue;
        }
        if (Boolean.TRUE.equals(rowIsNewMap.get(nextRow))) {
            continue;
        }
        return nextRow.get(0);
    }
    return null;
}
    
    /**
 * 生成删除列的SQL语句
 * @param schemaName 模式名
 * @param tableName 表名
 * @param columnName 列名
 * @return 删除列的SQL语句
 */
private String generateDropColumnSQL(String schemaName, String tableName, String columnName) {
    StringBuilder sqlBuilder = new StringBuilder();
    
    sqlBuilder.append("ALTER TABLE ");
    // 去掉schema引用
    sqlBuilder.append(tableName).append(" DROP ").append(columnName).append(";\n");
    
    return sqlBuilder.toString();
}

private String generateDropColumnsSQL(String schemaName, String tableName, List<String> columnNames) {
    if (columnNames == null || columnNames.isEmpty()) {
        return "";
    }
    if (columnNames.size() == 1) {
        return generateDropColumnSQL(schemaName, tableName, columnNames.get(0));
    }

    StringBuilder sqlBuilder = new StringBuilder();
    sqlBuilder.append("ALTER TABLE ");
    // 去掉schema引用
    sqlBuilder.append(tableName).append(" DROP (");
    for (int i = 0; i < columnNames.size(); i++) {
        if (i > 0) {
            sqlBuilder.append(",");
        }
        sqlBuilder.append(columnNames.get(i));
    }
    sqlBuilder.append(");\n");
    return sqlBuilder.toString();
}
    
    private  Node createErrorNode(String mesg){
        ImageView errorIcon = IconFactory.imageView(IconPaths.DIALOG_ERROR, 24, 24, true);
        HBox hBox=new HBox(5,errorIcon,new Label(mesg));
        hBox.setAlignment(Pos.CENTER);
        StackPane errorPane = new StackPane(hBox);
        return errorPane;
    }
}


