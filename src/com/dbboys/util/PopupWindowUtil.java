package com.dbboys.util;

import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.app.Main;
import com.dbboys.customnode.*;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.BackgroundSqlTask;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PopupWindowUtil {
    private static final double DDL_POPUP_WIDTH = 400;
    private static final double DDL_POPUP_HEIGHT = 300;

    /** 从进度文案中提取比例（如 {@code 表 2/10}、{@code 1500/5000}），供进度条使用；取最后一次匹配的 {@code 整数/整数}。 */
    private static final Pattern TASK_PROGRESS_FRACTION = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    public static StackPane noticePane = new StackPane();
    @Deprecated
    public static StackPane notice_pane = noticePane;

    //about
    public static Stage aboutPopupStage = new Stage();
    @Deprecated public static Stage about_pupupstage = aboutPopupStage;
    public static Label aboutPopupStageLabel = new Label(Main.VERSION.getVersion());
    @Deprecated public static Label about_pupupstage_label = aboutPopupStageLabel;
    public static StackPane aboutPopupStageStackPane = new StackPane(aboutPopupStageLabel);
    @Deprecated public static StackPane about_pupupstage_stackpane = aboutPopupStageStackPane;
    private static Scene aboutPopupStageScene = new Scene(aboutPopupStageStackPane, 400, 300);
    private static Image aboutPopupStageIcon = new Image(IconPaths.MAIN_LOGO);

    //sql历史记录
    private static Stage sqlHistoryPopupStage = new Stage();
    private static CustomTableView sqlHistoryTableView = new CustomTableView();
    private static StackPane sqlHistoryPopupStageStackPane = new StackPane(sqlHistoryTableView);
    private static Scene sqlHistoryPopupStageScene = new Scene(sqlHistoryPopupStageStackPane, 1000, 500);
    private static Image sqlHistoryPopupStageIcon = new Image(IconPaths.MAIN_LOGO);
    private static TableColumn<ObservableList<String>, Object> sqlHistoryBeginTableColumn;
    private static TableColumn<ObservableList<String>, Object> sqlHistoryStopTableColumn;
    private static TableColumn<ObservableList<String>, Object> sqlHistoryDurationTableColumn;
    private static TableColumn<ObservableList<String>, Object> sqlHistoryAffectTableColumn;
    private static TableColumn<ObservableList<String>, Object> sqlHistorySqlTableColumn;
    private static TableColumn<ObservableList<String>, Object> sqlHistoryMarkTableColumn;
    private static TableColumn<ObservableList<String>, Object> sqlHistoryDatabaseTableColumn;

    //后台sql
    public static CustomTableView<BackgroundSqlTask> sqlTaskTableView = new CustomTableView<>();
    @Deprecated public static TableView<BackgroundSqlTask> sql_task_tableview = sqlTaskTableView;
    private static Stage backSqlPopupStage = new Stage();
    private static StackPane backSqlPopupStageStackPane = new StackPane(sqlTaskTableView);
    private static Scene backSqlPopupStageScene = new Scene(backSqlPopupStageStackPane, 1000, 500);
    private static Image backSqlPopupStageIcon = new Image(IconPaths.MAIN_LOGO);
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskRowNumTableColumn;
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskIdTableColumn;
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskBeginTableColumn;
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskConnNameTableColumn;
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskDatabaseTableColumn;
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskSqlTableColumn;
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskProgressTableColumn;
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskOperateTableColumn;

    //显示DDL
    private static Stage ddlPopupStage = new Stage();
    private static CustomInfoStackPane ddlPopupStageStackPane = new CustomInfoStackPane(new CustomInfoCodeArea());
    private static Scene ddlPopupStageScene = new Scene(ddlPopupStageStackPane, DDL_POPUP_WIDTH, DDL_POPUP_HEIGHT);
    private static Image ddlPopupStageIcon = new Image(IconPaths.MAIN_LOGO);
    private static Label ddlPopupStageLoadingLabel = new Label();

    //适配列表
    private static final double COMPATIBILITY_POPUP_WIDTH = 920;
    private static final double COMPATIBILITY_POPUP_HEIGHT = 460;
    private static final Stage compatibilityPopupStage = new Stage();
    private static final CustomTableView<CompatibilityRow> compatibilityTableView = new CustomTableView<>();
    private static final StackPane compatibilityPopupStageStackPane = new StackPane(compatibilityTableView);

    //显示巡检命令输出
    private static Stage checkOutputPopupStage = new Stage();
    private static CustomInfoStackPane checkOutputPopupStageStackPane = new CustomInfoStackPane(new CustomInfoCodeArea());
    private static Scene checkOutputPopupStageScene = new Scene(checkOutputPopupStageStackPane, 600, 400);
    private static Image checkOutputPopupStageIcon = new Image(IconPaths.MAIN_LOGO);

    private static final StringBinding aboutTitleBinding = I18n.bind("popup.about.title", "关于DBboys");
    private static final StringBinding cmdOutputTitleBinding = I18n.bind("popup.cmd_output.title", "命令输出");
    private static final StringBinding sqlHistoryTitleBinding = I18n.bind("popup.sql_history.title", "当前连接变更SQL执行历史记录");
    private static final StringBinding backSqlTitleBinding = I18n.bind("popup.back_sql.title", "后台正在执行的sql任务");
    private static final StringBinding ddlTitleBinding = I18n.bind("popup.ddl.title", "结构定义语句");
    private static final StringBinding stopTaskTooltipBinding = I18n.bind("popup.back_sql.stop.tooltip", "停止此任务");
    private static final StringBinding taskCanceledBinding = I18n.bind("popup.back_sql.notice.canceled", "任务已取消！");

    //初始化
    private static final StringBinding compatibilityTitleBinding = I18n.bind("main.menu.help.compatibility_list", "适配列表");

    private static final List<String> COMPATIBILITY_FEATURE_LABELS = List.of(
            "元数据列表",
            "元数据详细信息",
            "元数据变更",
            "执行SQL",
            "执行计划",
            "导入导出",
            "实例管理"
    );

    private record CompatibilityFeature(String label, boolean supported) {}

    private record CompatibilityRow(String dbType, String version, List<CompatibilityFeature> features) {}

    static {
        //关于弹出面板
        aboutPopupStageStackPane.setAlignment(Pos.CENTER);
        CustomWindowFrameUtil.Frame aboutFrame = CustomWindowFrameUtil.createModalPopup(
                aboutPopupStage,
                aboutTitleBinding,
                aboutPopupStageStackPane,
                400,
                300,
                true
        );
        aboutPopupStageScene = aboutFrame.scene;
        aboutPopupStage.titleProperty().bind(aboutTitleBinding);

        //巡检双击弹出命令输出
        CustomWindowFrameUtil.Frame checkOutputFrame = CustomWindowFrameUtil.createModalPopup(
                checkOutputPopupStage,
                cmdOutputTitleBinding,
                checkOutputPopupStageStackPane,
                600,
                400,
                true
        );
        checkOutputPopupStageScene = checkOutputFrame.scene;
        checkOutputPopupStage.titleProperty().bind(cmdOutputTitleBinding);

        //初始化通知面板
        noticePane.setStyle("-fx-background-color: none;-fx-alignment: center");
        noticePane.setMaxWidth(360);
        noticePane.setMaxHeight(25);
        noticePane.setVisible(false);

        //初始化sql历史记录表格
        //sql_his_tableview.setStyle("");
        //sql_his_tableview.getStyleClass().clear();
        //sql_his_tableview.getStylesheets().add(PopupWindowUtil.class.getResource("/com/dbboys/css/test.css").toExternalForm());

        CustomWindowFrameUtil.Frame sqlHistoryFrame = CustomWindowFrameUtil.createModalPopup(
                sqlHistoryPopupStage,
                sqlHistoryTitleBinding,
                sqlHistoryPopupStageStackPane,
                1000,
                500,
                true
        );
        sqlHistoryPopupStageScene = sqlHistoryFrame.scene;
        sqlHistoryPopupStage.titleProperty().bind(sqlHistoryTitleBinding);
        sqlHistoryTableView.setStyle("-fx-background-insets: 0;");

        //sql_his_tableview.getSelectionModel().setCellSelectionEnabled(true);
        //sql_his_tableview.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);


        //定义结果集表结构
        sqlHistoryMarkTableColumn = createHistoryColumn("popup.sql_history.column.remark", "备注", "mark", 120);
        sqlHistoryBeginTableColumn = createHistoryColumn("popup.sql_history.column.start_time", "开始时间", "startTime", 190);
        sqlHistoryStopTableColumn = createHistoryColumn("popup.sql_history.column.end_time", "结束时间", "endTime", 190);
        sqlHistoryDurationTableColumn = createHistoryColumn("popup.sql_history.column.elapsed", "执行耗时", "elapsedTime", 100);
        sqlHistoryDatabaseTableColumn = createHistoryColumn("popup.sql_history.column.database", "库/模式", "database", 100);
        sqlHistoryAffectTableColumn = createHistoryColumn("popup.sql_history.column.affected", "更新行数", "affectedRows", 100);
        sqlHistorySqlTableColumn = createHistoryColumn("popup.sql_history.column.sql", "执行语句", "updateSql", 300);
        sqlHistoryTableView.getColumns().addAll(
                sqlHistoryDatabaseTableColumn,
                sqlHistorySqlTableColumn,
                sqlHistoryAffectTableColumn,
                sqlHistoryDurationTableColumn,
                sqlHistoryBeginTableColumn,
                sqlHistoryStopTableColumn,
                sqlHistoryMarkTableColumn
        );


        //初始化后台任务表格
        CustomWindowFrameUtil.Frame backSqlFrame = CustomWindowFrameUtil.createModalPopup(
                backSqlPopupStage,
                backSqlTitleBinding,
                backSqlPopupStageStackPane,
                1000,
                500,
                true
        );
        backSqlPopupStageScene = backSqlFrame.scene;
        backSqlPopupStageStackPane.getChildren().add(noticePane);
        backSqlPopupStage.titleProperty().bind(backSqlTitleBinding);

        sqlTaskIdTableColumn = new TableColumn<>();
        sqlTaskIdTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.id", "ID"));
        sqlTaskIdTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskIdTableColumn.setCellValueFactory(new PropertyValueFactory<>("id"));
        sqlTaskIdTableColumn.setVisible(false);

        sqlTaskBeginTableColumn = new TableColumn<>();
        sqlTaskBeginTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.start_time", "开始时间"));
        sqlTaskBeginTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskBeginTableColumn.setCellValueFactory(new PropertyValueFactory<>("beginTime"));
        sqlTaskBeginTableColumn.setPrefWidth(170);
        sqlTaskBeginTableColumn.setReorderable(false);
        sqlTaskBeginTableColumn.setSortable(false);

        sqlTaskConnNameTableColumn = new TableColumn<>();
        sqlTaskConnNameTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.connection_name", "连接名称"));
        sqlTaskConnNameTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskConnNameTableColumn.setCellValueFactory(new PropertyValueFactory<>("connectName"));
        sqlTaskConnNameTableColumn.setPrefWidth(210);
        sqlTaskConnNameTableColumn.setReorderable(false);
        sqlTaskConnNameTableColumn.setSortable(false);

        sqlTaskDatabaseTableColumn = new TableColumn<>();
        sqlTaskDatabaseTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.database_name", "库名"));
        sqlTaskDatabaseTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskDatabaseTableColumn.setCellValueFactory(new PropertyValueFactory<>("databaseName"));
        sqlTaskDatabaseTableColumn.setPrefWidth(90);
        sqlTaskDatabaseTableColumn.setReorderable(false);
        sqlTaskDatabaseTableColumn.setSortable(false);

        sqlTaskSqlTableColumn = new TableColumn<>();
        sqlTaskSqlTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.sql_task", "SQL任务"));
        sqlTaskSqlTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskSqlTableColumn.setCellValueFactory(new PropertyValueFactory<>("sql"));
        sqlTaskSqlTableColumn.setPrefWidth(300);
        sqlTaskSqlTableColumn.setReorderable(false);
        sqlTaskSqlTableColumn.setSortable(false);

        sqlTaskProgressTableColumn = new TableColumn<>();
        sqlTaskProgressTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.progress", "执行进度"));
        sqlTaskProgressTableColumn.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar progressBar = new ProgressBar(0);
            private final Label progressLabel = new Label();
            private final HBox progressBox = new HBox(8, progressBar, progressLabel);

            {
                progressBar.setPrefWidth(90);
                progressBar.setMinWidth(90);
                progressBar.setMaxWidth(90);
                progressLabel.setMinWidth(55);
                progressBox.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(null);
                if (empty) {
                    return;
                }
                String progressText = item == null ? "" : item.toString().trim();
                if (progressText.isEmpty()) {
                    return;
                }
                double progress = resolveTaskProgress(progressText);
                if (progress < 0) {
                    setText(progressText);
                    return;
                }
                progressLabel.setText(progressText);
                progressBar.setProgress(progress);
                setGraphic(progressBox);
            }
        });
        sqlTaskProgressTableColumn.setCellValueFactory(new PropertyValueFactory<>("progress"));
        sqlTaskProgressTableColumn.setPrefWidth(170);
        sqlTaskProgressTableColumn.setReorderable(false);
        sqlTaskProgressTableColumn.setSortable(false);

        sqlTaskOperateTableColumn = new TableColumn<>();
        sqlTaskOperateTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.operation", "操作"));
        sqlTaskOperateTableColumn.setCellValueFactory(new PropertyValueFactory<>("operate"));
        sqlTaskOperateTableColumn.setPrefWidth(76);
        sqlTaskOperateTableColumn.setReorderable(false);
        sqlTaskOperateTableColumn.setSortable(false);

        sqlTaskOperateTableColumn.setCellFactory(new Callback<>() {
            @Override
            public TableCell<BackgroundSqlTask, Object> call(TableColumn<BackgroundSqlTask, Object> param) {
                return new TableCell<>() {
                    private final Button statusSqlStopButton = new Button();

                    {
                        statusSqlStopButton.getStyleClass().add("small");
                        statusSqlStopButton.setFocusTraversable(false);
                        Tooltip stopTooltip = new Tooltip();
                        stopTooltip.textProperty().bind(stopTaskTooltipBinding);
                        statusSqlStopButton.setTooltip(stopTooltip);
                        statusSqlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.5, IconFactory.stopColor()));
                        statusSqlStopButton.setOnAction(event -> {
                            BackgroundSqlTask task = getTableRow() == null ? null : getTableRow().getItem();
                            if (task != null) {
                                task.cancel();
                                NotificationUtil.showNotification(noticePane, taskCanceledBinding.get());
                            }
                            event.consume();
                        });
                        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                        setAlignment(Pos.CENTER);
                    }

                    @Override
                    protected void updateItem(Object item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(null);
                        setGraphic(null);
                        if(!empty && getTableRow() != null && getTableRow().getItem() != null) {
                            setGraphic(statusSqlStopButton);
                        }
                    }
                };
            }
        });


        sqlTaskTableView.getColumns().addAll(
                sqlTaskBeginTableColumn,
                sqlTaskConnNameTableColumn,
                sqlTaskDatabaseTableColumn,
                sqlTaskSqlTableColumn,
                sqlTaskProgressTableColumn,
                sqlTaskOperateTableColumn
        );
        sqlTaskTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        sqlTaskTableView.getSelectionModel().setCellSelectionEnabled(true);
        sqlTaskTableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        sqlTaskTableView.setStyle("-fx-background-insets: 0;");

        //初始化ddl显示面板
        CustomWindowFrameUtil.Frame ddlFrame = CustomWindowFrameUtil.createModalPopup(
                ddlPopupStage,
                ddlTitleBinding,
                ddlPopupStageStackPane,
                DDL_POPUP_WIDTH,
                DDL_POPUP_HEIGHT,
                true
        );
        ddlPopupStageScene = ddlFrame.scene;
        ddlPopupStage.titleProperty().bind(ddlTitleBinding);
        ddlPopupStage.setResizable(true);
        ddlPopupStage.setMinWidth(DDL_POPUP_WIDTH);
        ddlPopupStage.setMinHeight(DDL_POPUP_HEIGHT);
        ddlPopupStageStackPane.showNoticeInMain=false;
        ImageView loadingImage = new ImageView(new Image(IconPaths.LOADING_GIF));
        loadingImage.setFitWidth(13);
        loadingImage.setFitHeight(13);
        ddlPopupStageLoadingLabel.setGraphic(loadingImage);
        ddlPopupStage.setOnCloseRequest(event -> {
            ddlPopupStageStackPane.codeArea.replaceText("");
        });

        compatibilityTableView.setItems(FXCollections.observableArrayList(buildCompatibilityRows()));
        compatibilityTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        compatibilityTableView.setPlaceholder(new Label(I18n.t("main.compatibility.empty", "暂无适配信息")));
        compatibilityTableView.setFixedCellSize(58);
        compatibilityTableView.setStyle("-fx-background-insets: 0;");

        TableColumn<CompatibilityRow, String> compatibilityTypeColumn = new TableColumn<>();
        compatibilityTypeColumn.textProperty().bind(I18n.bind("main.compatibility.type", "数据库类型"));
        compatibilityTypeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().dbType()));
        compatibilityTypeColumn.setMinWidth(130);
        compatibilityTypeColumn.setReorderable(false);
        compatibilityTypeColumn.setSortable(false);

        TableColumn<CompatibilityRow, String> compatibilityVersionColumn = new TableColumn<>();
        compatibilityVersionColumn.textProperty().bind(I18n.bind("main.compatibility.version", "已适配版本"));
        compatibilityVersionColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().version()));
        compatibilityVersionColumn.setMinWidth(120);
        compatibilityVersionColumn.setReorderable(false);
        compatibilityVersionColumn.setSortable(false);

        TableColumn<CompatibilityRow, List<CompatibilityFeature>> compatibilityFeaturesColumn = new TableColumn<>();
        compatibilityFeaturesColumn.textProperty().bind(I18n.bind("main.compatibility.features", "已适配功能"));
        compatibilityFeaturesColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().features()));
        compatibilityFeaturesColumn.setMinWidth(540);
        compatibilityFeaturesColumn.setReorderable(false);
        compatibilityFeaturesColumn.setSortable(false);
        compatibilityFeaturesColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(List<CompatibilityFeature> item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                FlowPane pane = new FlowPane();
                pane.setHgap(12);
                pane.setVgap(6);
                pane.setPadding(new Insets(4, 0, 4, 0));
                pane.prefWrapLengthProperty().bind(col.widthProperty().subtract(24));
                for (CompatibilityFeature feature : item) {
                    CheckBox checkBox = new CheckBox(feature.label());
                    checkBox.setSelected(feature.supported());
                    checkBox.setDisable(true);
                    pane.getChildren().add(checkBox);
                }
                setText(null);
                setGraphic(pane);
            }
        });
        compatibilityTableView.getColumns().addAll(
                compatibilityTypeColumn,
                compatibilityVersionColumn,
                compatibilityFeaturesColumn
        );

        CustomWindowFrameUtil.Frame compatibilityFrame = CustomWindowFrameUtil.createModalPopup(
                compatibilityPopupStage,
                compatibilityTitleBinding,
                compatibilityPopupStageStackPane,
                COMPATIBILITY_POPUP_WIDTH,
                COMPATIBILITY_POPUP_HEIGHT,
                true
        );
        compatibilityPopupStage.titleProperty().bind(compatibilityTitleBinding);
        compatibilityFrame.scene.setFill(Color.TRANSPARENT);
        compatibilityPopupStage.setResizable(true);
        compatibilityPopupStage.setMinWidth(COMPATIBILITY_POPUP_WIDTH);
        compatibilityPopupStage.setMinHeight(COMPATIBILITY_POPUP_HEIGHT);


    }

    public static void openSqlHistoryPopupWindow(Integer id) {
        sqlHistoryTableView.getItems().clear();
        sqlHistoryTableView.getItems().addAll(LocalDbRepository.getSqlHistoryList(id));
        sqlHistoryTableView.scrollTo(sqlHistoryTableView.getItems().size());
        sqlHistoryPopupStage.show();
    }

    public static void openCmdOutputPopupWindow(String output) {
        checkOutputPopupStageStackPane.codeArea.replaceText(output);
        checkOutputPopupStageStackPane.showNoticeInMain=false;
        checkOutputPopupStage.show();
    }

    public static void openSqlTaskPopupWindow() {
        backSqlPopupStage.show();
    }

    private static double resolveTaskProgress(String progressText) {
        if (progressText == null || progressText.isBlank()) {
            return -1;
        }
        double completed = -1;
        double total = -1;
        Matcher m = TASK_PROGRESS_FRACTION.matcher(progressText);
        while (m.find()) {
            try {
                completed = Double.parseDouble(m.group(1));
                total = Double.parseDouble(m.group(2));
            } catch (NumberFormatException ignored) {
                completed = -1;
                total = -1;
            }
        }
        if (completed < 0 || total <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(1, completed / total));
    }

    public static void openDDLWindow(String ddlSql) {
        if(ddlSql !=null&&!ddlSql.isEmpty()){
            Platform.runLater(() -> {//两层确保按顺序执行，text加载完成后才开始设置高亮，避免未渲染完成就高量出现不可预知问题，如当前行莫名其妙被加粗
                ddlPopupStageStackPane.codeArea.replaceText(ddlSql);
                Platform.runLater(() -> {
                    ddlPopupStageStackPane.codeArea.setStyleSpans(0,KeywordsHighlightUtil.applyHighlighting(ddlPopupStageStackPane.codeArea.getText()));
                });
            });
            ddlPopupStage.show();


            //ddlPopupStageStackPane.codeArea.showParagraphAtTop(0);
        }

    }

    public static void openAboutWindow() {
        aboutPopupStage.show();

    }

    public static void openCompatibilityListWindow() {
        compatibilityPopupStage.show();
        compatibilityPopupStage.toFront();
    }

    public static boolean openSqlConfirmPopupWindow(
            String titleKey,
            String titleFallback,
            String executeKey,
            String executeFallback,
            String cancelKey,
            String cancelFallback,
        String sqlContent
    ) {
        CustomInfoStackPane sqlPreviewPane = new CustomInfoStackPane(new CustomInfoCodeArea());
        sqlPreviewPane.showNoticeInMain = false;
        sqlPreviewPane.setPrefWidth(DDL_POPUP_WIDTH);
        sqlPreviewPane.setPrefHeight(DDL_POPUP_HEIGHT);
        //sqlContent = SqlParserUtil.formatSql(sqlContent);
        sqlPreviewPane.codeArea.replaceText(sqlContent == null ? "" : sqlContent);
        sqlPreviewPane.codeArea.moveTo(0);
        sqlPreviewPane.codeArea.requestFollowCaret();
        sqlPreviewPane.codeArea.setStyleSpans(0, KeywordsHighlightUtil.applyHighlighting(sqlPreviewPane.codeArea.getText()));

        ButtonType executeButton = new ButtonType(
                I18n.t(executeKey, executeFallback),
                ButtonBar.ButtonData.OK_DONE
        );
        ButtonType cancelButton = new ButtonType(
                I18n.t(cancelKey, cancelFallback),
                ButtonBar.ButtonData.CANCEL_CLOSE
        );

        AtomicReference<ButtonType> resultRef = new AtomicReference<>(cancelButton);

        Button executeBtn = new Button(executeButton.getText());
        executeBtn.setDefaultButton(true);
        executeBtn.setOnAction(event -> {
            resultRef.set(executeButton);
            sqlPreviewPane.getScene().getWindow().hide();
        });

        Button cancelBtn = new Button(cancelButton.getText());
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(event -> {
            resultRef.set(cancelButton);
            sqlPreviewPane.getScene().getWindow().hide();
        });

        HBox buttonBar = new HBox(10, executeBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        VBox contentBox = new VBox(16, sqlPreviewPane, buttonBar);
        contentBox.setPadding(new Insets(16, 20, 18, 20));
        contentBox.setStyle("-fx-background-color: -color-bg-default;");
        contentBox.setFillWidth(true);
        VBox.setVgrow(sqlPreviewPane, Priority.ALWAYS);

        Stage stage = new Stage();
        stage.setTitle(I18n.t(titleKey, titleFallback));
        CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.createModalPopup(
                stage,
                stage.titleProperty(),
                contentBox,
                DDL_POPUP_WIDTH,
                DDL_POPUP_HEIGHT,
                true
        );
        frame.closeButton.setOnAction(event -> {
            resultRef.set(cancelButton);
            stage.close();
        });
        frame.scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                resultRef.set(cancelButton);
                stage.close();
            } else if (event.getCode() == KeyCode.ENTER) {
                resultRef.set(executeButton);
                stage.close();
            }
        });
        stage.showAndWait();
        sqlPreviewPane.dispose();
        return resultRef.get() == executeButton;
    }

    public static List<Object> openParamWindow(int paramCount) {
        List<Object> returnList = new ArrayList<>();

        HBox hbox = new HBox();
        hbox.setId("modifyProps");
        hbox.setAlignment(Pos.CENTER_LEFT);

        CustomTableView tableView = new CustomTableView();
        TableColumn<ObservableList<String>, Object> paramcol = new TableColumn<>();
        paramcol.textProperty().bind(I18n.bind("popup.param_window.column.value", "参数值"));
        paramcol.setCellValueFactory(data -> Bindings.createObjectBinding(() ->
                data.getValue().size() > 1 ? data.getValue().get(1) : null
        ));
        paramcol.setPrefWidth(350);
        paramcol.setCellFactory(col -> new CustomLostFocusCommitTableCell<>());
        paramcol.setOnEditCommit(event -> {
            ObservableList<String> rowData = event.getRowValue();
            Object newValue = event.getNewValue();
            if (rowData.size() > 1) {
                rowData.set(1, newValue.equals("[NULL]") ? null : newValue.toString());
                tableView.refresh();
            }
        });

        tableView.getColumns().add(paramcol);
        tableView.setEditable(true);
        ObservableList<ObservableList<String>> observableData= FXCollections.observableArrayList();
        tableView.setItems(observableData);
        for (int x = 0; x < paramCount; x++) {
            observableData.add(FXCollections.observableArrayList(null, null));
        }
        hbox.getChildren().add(tableView);

        // 自定义按钮
        ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);

        // 等待用户点击并获取结果
        ButtonType clicked = showCustomDialog(
                I18n.t("popup.param_window.title", "输入SQL绑定变量参数"),
                hbox,
                420,
                420,
                buttonTypeOk,
                buttonTypeCancel
        );
        if(clicked==buttonTypeOk){
            for (ObservableList<String> row : observableData) {
                // 确保行数据不为null，且至少有2列（索引1存在）
                if (row != null && row.size() > 1) {
                    String secondColumn = row.get(1);
                    returnList.add(secondColumn);
                }
            }

        }

        //sb.append("]");
        return returnList;
    }

    private static TableColumn<ObservableList<String>, Object> createHistoryColumn(
            String i18nKey,
            String fallback,
            String propertyName,
            double prefWidth
    ) {
        TableColumn<ObservableList<String>, Object> column = new TableColumn<>();
        column.textProperty().bind(I18n.bind(i18nKey, fallback));
        column.setCellFactory(col -> new CustomTableCell<ObservableList<String>, Object>());
        column.setCellValueFactory(new PropertyValueFactory<>(propertyName));
        column.setPrefWidth(prefWidth);
        column.setReorderable(false);
        column.setSortable(false);
        return column;
    }

    private static ButtonType showCustomDialog(String title, javafx.scene.Node content, double width, double height, ButtonType... buttonTypes) {
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(title, content, width, height, buttonTypes);
        return dialog.showAndWait();
    }

    private static List<CompatibilityRow> buildCompatibilityRows() {
        return List.of(
                new CompatibilityRow("GBASE 8S", "8.7 / 8.8", compatibilityFeatures(
                        "元数据列表", "元数据详细信息", "元数据变更", "执行SQL", "执行计划", "导入导出", "实例管理")),
                new CompatibilityRow("INFORMIX", "12.1", compatibilityFeatures(
                        "元数据列表", "元数据详细信息", "元数据变更", "执行SQL", "执行计划", "导入导出", "实例管理")),
                new CompatibilityRow("ORACLE", "19C", compatibilityFeatures(
                        "元数据列表", "元数据详细信息", "元数据变更", "执行SQL", "执行计划", "导入导出"))
        );
    }

    private static List<CompatibilityFeature> compatibilityFeatures(String... labels) {
        List<CompatibilityFeature> features = new ArrayList<>();
        List<String> supportedLabels = labels == null ? List.of() : List.of(labels);
        for (String label : COMPATIBILITY_FEATURE_LABELS) {
            features.add(new CompatibilityFeature(label, supportedLabels.contains(label)));
        }
        return features;
    }
}
