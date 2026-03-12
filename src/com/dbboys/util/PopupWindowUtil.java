package com.dbboys.util;

import com.dbboys.app.AppState;
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
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class PopupWindowUtil {
    private static final String PRIMARY_BUTTON_STYLE =
            "-fx-background-color: #2d6f9f;" +
            "-fx-text-fill: white;" +
            "-fx-border-color: #2d6f9f;" +
            "-fx-background-radius: 3;" +
            "-fx-border-radius: 3;" +
            "-fx-padding: 6 18 6 18;";
    private static final String SECONDARY_BUTTON_STYLE =
            "-fx-background-color: #2b2b2b;" +
            "-fx-text-fill: #e6e6e6;" +
            "-fx-border-color: #575757;" +
            "-fx-background-radius: 3;" +
            "-fx-border-radius: 3;" +
            "-fx-padding: 6 18 6 18;";

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
    private static CustomResultsetTableView sqlHistoryTableView = new CustomResultsetTableView();
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
    public static TableView<BackgroundSqlTask> sqlTaskTableView = new TableView<>();
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
    private static TableColumn<BackgroundSqlTask, Object> sqlTaskOperateTableColumn;

    //显示DDL
    private static Stage ddlPopupStage = new Stage();
    private static CustomInfoStackPane ddlPopupStageStackPane = new CustomInfoStackPane(new CustomInfoCodeArea());
    private static Scene ddlPopupStageScene = new Scene(ddlPopupStageStackPane, 400, 300);
    private static Image ddlPopupStageIcon = new Image(IconPaths.MAIN_LOGO);
    private static Label ddlPopupStageLoadingLabel = new Label();

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
    static {
        //关于弹出面板
        aboutPopupStageStackPane.setAlignment(Pos.CENTER);
        aboutPopupStage.initStyle(StageStyle.UNDECORATED);
        aboutPopupStage.setResizable(true);
        aboutPopupStageScene = CustomWindowFrameUtil.create(
                aboutPopupStage,
                aboutTitleBinding,
                aboutPopupStageStackPane,
                400,
                300
        ).scene;
        aboutPopupStage.getIcons().add(aboutPopupStageIcon);
        aboutPopupStage.setScene(aboutPopupStageScene);
        aboutPopupStage.titleProperty().bind(aboutTitleBinding);
        aboutPopupStage.initModality(Modality.APPLICATION_MODAL);

        //巡检双击弹出命令输出
        checkOutputPopupStage.initStyle(StageStyle.UNDECORATED);
        checkOutputPopupStage.setResizable(true);
        checkOutputPopupStageScene = CustomWindowFrameUtil.create(
                checkOutputPopupStage,
                cmdOutputTitleBinding,
                checkOutputPopupStageStackPane,
                600,
                400
        ).scene;
        checkOutputPopupStage.getIcons().add(checkOutputPopupStageIcon);
        checkOutputPopupStage.setScene(checkOutputPopupStageScene);
        checkOutputPopupStage.titleProperty().bind(cmdOutputTitleBinding);
        checkOutputPopupStage.initModality(Modality.APPLICATION_MODAL);

        //初始化通知面板
        noticePane.setStyle("-fx-background-color: none;-fx-alignment: center");
        noticePane.setMaxWidth(360);
        noticePane.setMaxHeight(25);
        noticePane.setVisible(false);

        //初始化sql历史记录表格
        //sql_his_tableview.setStyle("");
        //sql_his_tableview.getStyleClass().clear();
        //sql_his_tableview.getStylesheets().add(PopupWindowUtil.class.getResource("/com/dbboys/css/test.css").toExternalForm());

        sqlHistoryPopupStage.initStyle(StageStyle.UNDECORATED);
        sqlHistoryPopupStage.setResizable(true);
        sqlHistoryPopupStage.initModality(Modality.APPLICATION_MODAL);
        sqlHistoryPopupStageScene = CustomWindowFrameUtil.create(
                sqlHistoryPopupStage,
                sqlHistoryTitleBinding,
                sqlHistoryPopupStageStackPane,
                1000,
                500
        ).scene;
        sqlHistoryPopupStage.getIcons().add(sqlHistoryPopupStageIcon);
        sqlHistoryPopupStage.setScene(sqlHistoryPopupStageScene);
        sqlHistoryPopupStage.titleProperty().bind(sqlHistoryTitleBinding);

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
        backSqlPopupStage.initStyle(StageStyle.UNDECORATED);
        backSqlPopupStage.setResizable(true);
        backSqlPopupStage.initModality(Modality.APPLICATION_MODAL);
        backSqlPopupStageScene = CustomWindowFrameUtil.create(
                backSqlPopupStage,
                backSqlTitleBinding,
                backSqlPopupStageStackPane,
                1000,
                500
        ).scene;
        backSqlPopupStage.getIcons().add(backSqlPopupStageIcon);
        backSqlPopupStage.setScene(backSqlPopupStageScene);
        backSqlPopupStageStackPane.getChildren().add(noticePane);
        backSqlPopupStage.titleProperty().bind(backSqlTitleBinding);
        sqlTaskRowNumTableColumn = new TableColumn<>("");
        sqlTaskRowNumTableColumn.setCellFactory(new Callback<>() {
            @Override
            public TableCell<BackgroundSqlTask, Object> call(TableColumn<BackgroundSqlTask, Object> param) {
                return new TableCell<>() {
                    @Override
                    protected void updateItem(Object item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty) {
                            setText(null); // 空行不显示行号
                        } else {
                            setText(String.valueOf(getIndex() + 1)); // 行号从 1 开始
                            setStyle("-fx-background-color: #f2f2f2;-fx-text-fill: black");
                            setOnMouseClicked(event -> {
                                int rowIndex = getIndex();
                                sqlTaskTableView.getSelectionModel().clearAndSelect(rowIndex);
                            });
                        }
                    }
                };
            }
        });
        sqlTaskRowNumTableColumn.setSortable(false);
        sqlTaskRowNumTableColumn.setPrefWidth(30);

        sqlTaskIdTableColumn = new TableColumn<>();
        sqlTaskIdTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.id", "ID"));
        sqlTaskIdTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskIdTableColumn.setCellValueFactory(new PropertyValueFactory<>("id"));
        sqlTaskIdTableColumn.setVisible(false);

        sqlTaskBeginTableColumn = new TableColumn<>();
        sqlTaskBeginTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.start_time", "开始时间"));
        sqlTaskBeginTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskBeginTableColumn.setCellValueFactory(new PropertyValueFactory<>("beginTime"));
        sqlTaskBeginTableColumn.setPrefWidth(200);
        sqlTaskBeginTableColumn.setReorderable(false);
        sqlTaskBeginTableColumn.setSortable(false);

        sqlTaskConnNameTableColumn = new TableColumn<>();
        sqlTaskConnNameTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.connection_name", "连接名称"));
        sqlTaskConnNameTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskConnNameTableColumn.setCellValueFactory(new PropertyValueFactory<>("connectName"));
        sqlTaskConnNameTableColumn.setPrefWidth(300);
        sqlTaskConnNameTableColumn.setReorderable(false);
        sqlTaskConnNameTableColumn.setSortable(false);

        sqlTaskDatabaseTableColumn = new TableColumn<>();
        sqlTaskDatabaseTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.database_name", "库名"));
        sqlTaskDatabaseTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskDatabaseTableColumn.setCellValueFactory(new PropertyValueFactory<>("databaseName"));
        sqlTaskDatabaseTableColumn.setPrefWidth(100);
        sqlTaskDatabaseTableColumn.setReorderable(false);
        sqlTaskDatabaseTableColumn.setSortable(false);

        sqlTaskSqlTableColumn = new TableColumn<>();
        sqlTaskSqlTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.sql_task", "SQL任务"));
        sqlTaskSqlTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskSqlTableColumn.setCellValueFactory(new PropertyValueFactory<>("sql"));
        sqlTaskSqlTableColumn.setPrefWidth(300);
        sqlTaskSqlTableColumn.setReorderable(false);
        sqlTaskSqlTableColumn.setSortable(false);

        sqlTaskOperateTableColumn = new TableColumn<>();
        sqlTaskOperateTableColumn.textProperty().bind(I18n.bind("popup.back_sql.column.operation", "操作"));
        sqlTaskOperateTableColumn.setCellFactory(col -> new CustomTableCell<>());
        sqlTaskOperateTableColumn.setCellValueFactory(new PropertyValueFactory<>("operate"));
        sqlTaskOperateTableColumn.setPrefWidth(100);
        sqlTaskOperateTableColumn.setReorderable(false);
        sqlTaskOperateTableColumn.setSortable(false);

        sqlTaskOperateTableColumn.setCellFactory(new Callback<>() {
            @Override
            public TableCell<BackgroundSqlTask, Object> call(TableColumn<BackgroundSqlTask, Object> param) {
                return new TableCell<>() {
                    @Override
                    protected void updateItem(Object item, boolean empty) {
                        super.updateItem(item, empty);
                        setGraphic(null);
                        if(!empty) {
                            Button status_sqlStopButton = new Button("");
                            status_sqlStopButton.getStyleClass().add("small");
                            Tooltip stopTooltip = new Tooltip();
                            stopTooltip.textProperty().bind(stopTaskTooltipBinding);
                            status_sqlStopButton.setTooltip(stopTooltip);
                            status_sqlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.5, IconFactory.stopColor()));
                            status_sqlStopButton.setFocusTraversable(false);
                            setGraphic(status_sqlStopButton);
                            status_sqlStopButton.setOnAction(event -> {
                                BackgroundSqlTask task = (BackgroundSqlTask) getTableRow().getItem();
                                if (task != null) {
                                    task.cancel();
                                    NotificationUtil.showNotification(noticePane, taskCanceledBinding.get());
                                }
                            });
                        }
                    }
                };
            }
        });


        sqlTaskTableView.getColumns().addAll(
                sqlTaskIdTableColumn,
                sqlTaskRowNumTableColumn,
                sqlTaskBeginTableColumn,
                sqlTaskConnNameTableColumn,
                sqlTaskDatabaseTableColumn,
                sqlTaskSqlTableColumn,
                sqlTaskOperateTableColumn
        );
        sqlTaskTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        sqlTaskTableView.getSelectionModel().setCellSelectionEnabled(true);
        sqlTaskTableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        //初始化ddl显示面板
        ddlPopupStage.initStyle(StageStyle.UNDECORATED);
        ddlPopupStage.setResizable(true);
        ddlPopupStage.initModality(Modality.APPLICATION_MODAL);
        ddlPopupStageScene = CustomWindowFrameUtil.create(
                ddlPopupStage,
                ddlTitleBinding,
                ddlPopupStageStackPane,
                400,
                300
        ).scene;
        ddlPopupStage.getIcons().add(ddlPopupStageIcon);
        ddlPopupStage.setScene(ddlPopupStageScene);
        ddlPopupStage.titleProperty().bind(ddlTitleBinding);
        ddlPopupStageStackPane.showNoticeInMain=false;
        ImageView loadingImage = new ImageView(new Image(IconPaths.LOADING_GIF));
        loadingImage.setFitWidth(13);
        loadingImage.setFitHeight(13);
        ddlPopupStageLoadingLabel.setGraphic(loadingImage);
        ddlPopupStage.setOnCloseRequest(event -> {
            ddlPopupStageStackPane.codeArea.replaceText("");
        });


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
        sqlPreviewPane.setPrefWidth(920);
        sqlPreviewPane.setPrefHeight(520);
        sqlContent = SqlParserUtil.formatSql(sqlContent);
        sqlPreviewPane.codeArea.replaceText(sqlContent == null ? "" : sqlContent);
        sqlPreviewPane.codeArea.moveTo(0);
        sqlPreviewPane.codeArea.requestFollowCaret();
        sqlPreviewPane.codeArea.setStyleSpans(0, KeywordsHighlightUtil.applyHighlighting(sqlPreviewPane.codeArea.getText()));

        VBox contentBox = new VBox(sqlPreviewPane);

        ButtonType executeButton = new ButtonType(
                I18n.t(executeKey, executeFallback),
                ButtonBar.ButtonData.OK_DONE
        );
        ButtonType cancelButton = new ButtonType(
                I18n.t(cancelKey, cancelFallback),
                ButtonBar.ButtonData.CANCEL_CLOSE
        );

        ButtonType clicked = showCustomDialog(
                I18n.t(titleKey, titleFallback),
                contentBox,
                960,
                620,
                executeButton,
                cancelButton
        );
        sqlPreviewPane.dispose();
        return clicked == executeButton;
    }

    public static List<Object> openParamWindow(int paramCount) {
        List<Object> returnList = new ArrayList<>();

        HBox hbox = new HBox();
        hbox.setId("modifyProps");
        hbox.setAlignment(Pos.CENTER_LEFT);

        CustomResultsetTableView tableView = new CustomResultsetTableView();
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
        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle(title == null ? "" : title);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(true);
        Window owner = AppState.getWindow();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.getIcons().add(new Image(IconPaths.MAIN_LOGO));

        AtomicReference<ButtonType> resultRef = new AtomicReference<>();
        HBox buttonBar = new HBox(10);
        buttonBar.setStyle("-fx-alignment: center-right; -fx-padding: 12 0 0 0;");
        ButtonType defaultButton = findDefaultButton(buttonTypes);
        ButtonType cancelButton = findCancelButton(buttonTypes);
        for (ButtonType buttonType : buttonTypes) {
            Button button = new Button(buttonType.getText());
            button.setFocusTraversable(false);
            button.setDefaultButton(buttonType == defaultButton);
            button.setCancelButton(buttonType == cancelButton);
            button.setStyle(buttonType == cancelButton ? SECONDARY_BUTTON_STYLE : PRIMARY_BUTTON_STYLE);
            button.setOnAction(event -> {
                resultRef.set(buttonType);
                stage.close();
            });
            buttonBar.getChildren().add(button);
        }

        VBox body = new VBox(content, buttonBar);
        body.setStyle("-fx-background-color: #151a1f; -fx-padding: 16; -fx-spacing: 0;");
        VBox.setVgrow(content, Priority.ALWAYS);

        CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.create(
                stage,
                stage.titleProperty(),
                body,
                width,
                height
        );
        frame.closeButton.setOnAction(event -> {
            resultRef.set(cancelButton != null ? cancelButton : defaultButton);
            stage.close();
        });
        frame.scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                resultRef.set(cancelButton != null ? cancelButton : defaultButton);
                stage.close();
            } else if (event.getCode() == KeyCode.ENTER) {
                resultRef.set(defaultButton);
                stage.close();
            }
        });

        stage.setScene(frame.scene);
        stage.showAndWait();
        return resultRef.get();
    }

    private static ButtonType findDefaultButton(ButtonType[] buttonTypes) {
        for (ButtonType buttonType : buttonTypes) {
            if (buttonType.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                return buttonType;
            }
        }
        return buttonTypes.length > 0 ? buttonTypes[0] : null;
    }

    private static ButtonType findCancelButton(ButtonType[] buttonTypes) {
        for (ButtonType buttonType : buttonTypes) {
            if (buttonType.getButtonData().isCancelButton()) {
                return buttonType;
            }
        }
        return null;
    }


}
