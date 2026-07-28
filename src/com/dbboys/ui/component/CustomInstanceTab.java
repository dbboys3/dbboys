package com.dbboys.ui.component;
import com.dbboys.ui.util.SnapshotUtil;
import com.dbboys.ui.util.KeywordsHighlightUtil;
import com.dbboys.dialect.common.InstanceMutationUtil;

import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.core.InstanceManagerCapability;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.core.NamedServerConnectionCapability;
import com.dbboys.app.AppContext;
import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.service.AdminService;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.core.ConnectionService;
import com.dbboys.infra.util.*;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.dialog.PopupWindowUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.model.SpaceUsage;
import com.dbboys.model.Connect;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.WindowEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomInstanceTab extends CustomTab {
    private static final Logger log = LogManager.getLogger(CustomInstanceTab.class);
    private final AdminService adminService = new AdminService();
    private final ConnectionService connectionService = com.dbboys.app.AppContext.get(ConnectionService.class);
    private final DatabasePlatformResolver platformResolver = AppContext.get(DatabasePlatformResolver.class);
    private Connect connect;
    // 为每个需要懒加载的 Tab 定义“已加载”标记
    private boolean infoTabLoaded = false;
    private boolean checkTabLoaded = false;
    private boolean logTabLoaded = false;
    private boolean spaceTabLoaded = false;
    private boolean paramsTabLoaded = false;
    private boolean startTabLoaded = false;
    // 存储 Tab 引用，用于刷新逻辑
    private CustomTab infoTab;
    private CustomTab checkTab;
    private CustomTab logTab;
    private CustomTab spaceTab;
    private CustomTab paramsTab;
    private CustomTab startTab;
    public TabPane mainTabPane;
    private Label instanceInfoLabel;
    private Button refreshButton;
    //private Session session;

    //checktab

    private CustomTableView<InstanceTabCapability.CheckRow> checkTableView = new CustomTableView<>();
    private List<InstanceTabCapability.CheckRow> checkDatalist = FXCollections.observableArrayList();// 如果确认，返回更新后的 list
    private StackPane checkStackPane;

    //configtab
    private CustomTableView<ObservableList<String>> configTableView = new CustomTableView<>();
    private List<ObservableList<String>> configDatalist = FXCollections.observableArrayList();// 如果确认，返回更新后的 list
    private StackPane configStackPane;

    // space tab 变量
    private CustomSpaceChart.ContextMenuListener menuListener;
    private InstanceSpaceAdminOps spaceAdminOps;
    private CustomSpaceChart dbspaceChart;
    private CustomSpaceChart chunkChart;
    private CustomSpaceChart databaseChart;
    private CustomSpaceChart tabChart;
    private StackPane dbspaceStackPane;
    List<SpaceUsage> dbspaceChartList = new ArrayList<>();
    List<SpaceUsage> chunkChartList = new ArrayList<>();
    List<SpaceUsage> databaseChartList = new ArrayList<>();
    List<SpaceUsage> tabChartList = new ArrayList<>();



    // 启停变量
    private Button startButton;
    private Button stopButton;
    private Label statusLabel;
    private StackPane startStackPane;
    private Label ipLabel;
    private final SimpleStringProperty instanceStatusTextCode = new SimpleStringProperty("unknown");
    private final SimpleStringProperty instanceNameText = new SimpleStringProperty("");
    private final SimpleStringProperty instanceIpText = new SimpleStringProperty("");
    private final SimpleStringProperty instancePortText = new SimpleStringProperty("");




    public CustomInstanceTab(Connect connect,int tabNum) {
        super(connect.getName());
        setTabIcon(IconPaths.CONNECTION_LINK, 0.5);
        this.connect=connect;

        // 实例信息 tab 初始化变量
        String instanceName = resolveInstanceName();
        instanceNameText.set(instanceName);
        instanceIpText.set(connect.getIp());
        instancePortText.set(connect.getPort());
        instanceInfoLabel=new Label();
        instanceInfoLabel.textProperty().bind(Bindings.createStringBinding(
                () -> isMysqlConnect()
                        ? String.format(
                        I18n.t("instance.info.current.mysql.format", "当前实例信息 ( IP：%s   端口：%s )"),
                        instanceIpText.get(),
                        instancePortText.get()
                )
                        : !instanceNameText.get().isEmpty()
                        ? String.format(
                        I18n.t("instance.info.current.format", "当前实例信息 ( IP：%s   端口：%s   实例名：%s )"),
                        instanceIpText.get(),
                        instancePortText.get(),
                        instanceNameText.get()
                )
                        : String.format(
                        I18n.t("instance.info.current.no_name.format", "当前实例信息 ( IP：%s   端口：%s )"),
                        instanceIpText.get(),
                        instancePortText.get()
                ),
                instanceIpText,
                instancePortText,
                instanceNameText,
                I18n.localeProperty()
        ));
        instanceInfoLabel.getStyleClass().add("instance-info-label");

        if(connect.getReadonly()){
            instanceInfoLabel.setGraphic(IconFactory.group(IconPaths.SQL_READONLY, 0.45, IconFactory.dangerColor()));
            instanceInfoLabel.setContentDisplay(ContentDisplay.RIGHT);
            Tooltip readonlyTooltip = new Tooltip();
            readonlyTooltip.textProperty().bind(I18n.bind("instance.info.readonly.tooltip", "当前连接只读，管理操作已禁用！"));
            instanceInfoLabel.setTooltip(readonlyTooltip);
        }

        //UI
        checkTableView.setEditable(false);
        checkTableView.setSortPolicy((param) -> false);// 禁用排序
        checkTableView.setRowFactory(tv -> {
            TableRow<InstanceTabCapability.CheckRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY && !row.isEmpty()) {
                    InstanceTabCapability.CheckRow rowData = row.getItem();
                    if(rowData.openLogOnDoubleClick()) {
                        mainTabPane.getSelectionModel().select(logTab);
                    }else if(rowData.cmdOutput()!=null&&!rowData.cmdOutput().isEmpty()){
                            PopupWindowUtil.openCmdOutputPopupWindow(rowData.cmdOutput());
                    }
                }
            });
            return row;
        });
        initCheckTableColumns();
        initCheckDatalist(checkDatalist);
        checkTableView.getItems().addAll(checkDatalist);
        checkTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        ImageView loading_icon = IconFactory.loadingImageView(0.7);
        checkStackPane = new StackPane(loading_icon);
        checkStackPane.getChildren().add(checkTableView);
        ((TableColumn<?, ?>) checkTableView.getColumns().get(0)).setMaxWidth(30);
        ((TableColumn<?, ?>) checkTableView.getColumns().get(0)).setMinWidth(30);
        checkStackPane.getStyleClass().add("bg-content");
        Button checkshotButton= new Button();
        checkshotButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35));
        checkshotButton.setFocusTraversable(false);
        checkshotButton.setId("codearea-camera-button");
        checkStackPane.getChildren().add(checkshotButton);
        checkStackPane.setAlignment(checkshotButton, Pos.TOP_RIGHT);
        checkStackPane.setMargin(checkshotButton, new javafx.geometry.Insets(0, 15, 20, 20));
        checkshotButton.setOnAction(e->{
            SnapshotUtil.snapshotTableView(checkTableView);
        });

        // 参数优化 UI
        configTableView.setEditable(canEditConfig());
        configTableView.setSortPolicy((param) -> false);// 禁用排序
        TableColumn<ObservableList<String>, Object> configNameColumn = new TableColumn<>();
        configNameColumn.textProperty().bind(I18n.bind("instance.config.column.name", "参数名"));
        configNameColumn.setCellFactory(col -> new CustomTableCell<ObservableList<String>, Object>());
        configNameColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(1)));
        configNameColumn.setReorderable(false); // 绂佺敤鎷栧姩
        configNameColumn.setEditable(false);
        configNameColumn.setReorderable(false);
        configNameColumn.setPrefWidth(300);
        TableColumn<ObservableList<String>, Object> configValueColumn = new TableColumn<>();
        configValueColumn.textProperty().bind(I18n.bind("instance.config.column.value", "参数值"));
        configValueColumn.setCellFactory(col -> new CustomTableCell<ObservableList<String>, Object>());
        configValueColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(2)));
        configValueColumn.setReorderable(false); // 绂佺敤鎷栧姩
        configValueColumn.setEditable(canEditConfig());
        configValueColumn.setReorderable(false);
        configValueColumn.setPrefWidth(500);

        configValueColumn.setOnEditCommit(event -> {
            if (!canEditConfig()) {
                event.getTableView().refresh();
                return;
            }
            String paramName=event.getRowValue().get(1).toString();
            Object oldvalue = event.getOldValue();
            //Object colvalue = event.getNewValue();
            //鏇挎崲鎹㈣
            Object colvalue = event.getNewValue().toString();

            // 替换换行后显示
            if(oldvalue.equals(colvalue)){
                return;
            }
            event.getRowValue().set(2, colvalue.toString());

            event.getTableView().refresh();

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    InstanceTabCapability capability = resolveInstanceTabCapability()
                            .orElseThrow(() -> new UnsupportedOperationException("Config update is not supported"));
                    InstanceTabCapability.ConfigUpdateResult result = capability.updateConfig(connect, paramName, colvalue.toString());
                    Platform.runLater(() -> {
                        switch (result.status()) {
                            case APPLIED, FILE_ONLY -> NotificationUtil.showMainNotification(result.message());
                            case RESTART_REQUIRED -> AlertUtil.CustomAlert("提醒", result.message());
                        }
                    });
                    return null;
                }
            };
            task.setOnFailed(event1->{
                event.getRowValue().set(2, oldvalue.toString());
                event.getTableView().refresh();
                String error = task.getException().getMessage();
                AlertUtil.CustomAlert("错误", error);
            });
            AppExecutor.runTask(task);

        });

        configTableView.getColumns().addAll(configNameColumn, configValueColumn);
        configTableView.getItems().addAll(configDatalist);
        configTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        configStackPane = new StackPane();
        configStackPane.getChildren().add(configTableView);
        ((TableColumn<?, ?>) configTableView.getColumns().get(0)).setMaxWidth(36);
        ((TableColumn<?, ?>) configTableView.getColumns().get(0)).setMinWidth(36);
        configStackPane.getStyleClass().add("bg-content");
        Button configshotButton= new Button();
        configshotButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35));
        configshotButton.setFocusTraversable(false);
        configshotButton.setId("codearea-camera-button");
        configStackPane.getChildren().add(configshotButton);
        configStackPane.setAlignment(configshotButton, Pos.TOP_RIGHT);
        configStackPane.setMargin(configshotButton, new javafx.geometry.Insets(0, 15, 20, 20));
        configshotButton.setOnAction(e->{
            SnapshotUtil.snapshotTableView(configTableView);
        });

        //鍒濆鍖杝pacetab UI
        InstanceTabCapability.SpaceLabels spaceLabels = resolveSpaceLabels();
        CustomSpaceChart.SpaceContextMenuPolicy spaceMenuPolicy = CustomSpaceChart.menuPolicyFor(connect);
        dbspaceChart = new CustomSpaceChart(
                dbspaceChartList,
                CustomSpaceChart.ColorMode.DBSPACE,
                spaceLabels.unusedBarLabelI18nKey(),
                spaceLabels.unusedBarLabelFallback(),
                spaceMenuPolicy);
        Node dbspaceChartLegend = dbspaceChart.createLegend();
        Label spaceType=new Label();
        bindSpaceLabel(spaceType, spaceLabels.legendI18nKey(), spaceLabels.legendText());
        spaceType.setVisible(spaceLabels.legendText() != null && !spaceLabels.legendText().isBlank());
        spaceType.setManaged(spaceType.isVisible());
        spaceType.getStyleClass().add("instance-space-type");
        Label dbspaceTitleLabel = new Label();
        bindSpaceLabel(dbspaceTitleLabel, spaceLabels.dbspaceTitleI18nKey(), spaceLabels.dbspaceTitle());
        VBox dbspaceChartVbox = new VBox(dbspaceChart,dbspaceChartLegend,spaceType,dbspaceTitleLabel);
        dbspaceChartVbox.setAlignment(Pos.CENTER);
        chunkChart = new CustomSpaceChart(
                chunkChartList,
                CustomSpaceChart.ColorMode.CHUNK,
                spaceLabels.unusedBarLabelI18nKey(),
                spaceLabels.unusedBarLabelFallback(),
                spaceMenuPolicy);
        Node chunkChartLegend = chunkChart.createLegend();
        Label chunkTitleLabel = new Label();
        bindSpaceLabel(chunkTitleLabel, spaceLabels.chunkTitleI18nKey(), spaceLabels.chunkTitle());
        VBox chunkChartVbox = new VBox(chunkChart,chunkChartLegend,chunkTitleLabel);
        chunkChartVbox.setAlignment(Pos.CENTER);
        databaseChart = new CustomSpaceChart(
                databaseChartList,
                CustomSpaceChart.ColorMode.DATABASE,
                spaceLabels.unusedBarLabelI18nKey(),
                spaceLabels.unusedBarLabelFallback(),
                spaceMenuPolicy);
        Node databaseChartLegend = databaseChart.createLegend();
        Label databaseTitleLabel = new Label();
        bindSpaceLabel(databaseTitleLabel, spaceLabels.databaseTitleI18nKey(), spaceLabels.databaseTitle());
        VBox databaseChartVbox = new VBox(databaseChart,databaseChartLegend,databaseTitleLabel);
        databaseChartVbox.setAlignment(Pos.CENTER);
        tabChart = new CustomSpaceChart(
                tabChartList,
                CustomSpaceChart.ColorMode.TABLE,
                spaceLabels.unusedBarLabelI18nKey(),
                spaceLabels.unusedBarLabelFallback(),
                spaceMenuPolicy);
        Node tabChartLegend = tabChart.createLegend();
        Label tableTitleLabel = new Label();
        bindSpaceLabel(tableTitleLabel, spaceLabels.tableTitleI18nKey(), spaceLabels.tableTitle());
        VBox tabChartVbox = new VBox(tabChart,tabChartLegend,tableTitleLabel);
        tabChartVbox.setAlignment(Pos.CENTER);
        VBox charts=new VBox(50,dbspaceChartVbox,chunkChartVbox,databaseChartVbox,tabChartVbox);
        charts.setAlignment(Pos.CENTER);
        charts.getStyleClass().add("bg-content");
        ScrollPane scrollPane = new ScrollPane(charts);
        dbspaceStackPane=new StackPane(scrollPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.getStyleClass().add("instance-chart-scroll-pane");
        dbspaceStackPane.getStyleClass().add("bg-content");
        dbspaceStackPane.setAlignment(Pos.CENTER);
        Button spaceshotButton= new Button();
        spaceshotButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35));
        spaceshotButton.setFocusTraversable(false);
        spaceshotButton.setId("codearea-camera-button");
        dbspaceStackPane.getChildren().add(spaceshotButton);
        dbspaceStackPane.setAlignment(spaceshotButton, Pos.TOP_RIGHT);
        dbspaceStackPane.setMargin(spaceshotButton, new javafx.geometry.Insets(0, 15, 20, 20));
        spaceshotButton.setOnAction(e->{
            SnapshotUtil.snapshotNode(scrollPane.getContent());
        });
        spaceAdminOps = new InstanceSpaceAdminOps(
                connect,
                adminService,
                platformResolver,
                () -> dbspaceChartList,
                () -> chunkChartList,
                () -> refreshButton.fire());
        menuListener = new CustomSpaceChart.ContextMenuListener() {
            // -------------------------- onViewDetail 实现 --------------------------
            @Override
            public void onCreateDbspace(SpaceUsage spaceUsage,boolean isAddFile) {
                spaceAdminOps.onCreateDbspace(spaceUsage, isAddFile);
            }

            // -------------------------- 其他接口方法实现 --------------------------
            @Override
            public void onDropDbspace(SpaceUsage spaceUsage) {
                spaceAdminOps.onDropDbspace(spaceUsage);
            }


            @Override
            public void onDropDatafile(SpaceUsage spaceUsage) {
                // 删除数据文件
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.drop_datafile.title", "删除数据文件"), String.format(I18n.t("instance.confirm.drop_datafile.content", "确定要删除数据文件\"%s\"吗？"), spaceUsage.getName()))){
                    String dbspace=spaceUsage.getLabel().trim().split(" ")[2].trim();
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            InstanceTabCapability capability = resolveInstanceTabCapability()
                                    .orElseThrow(() -> new UnsupportedOperationException("Drop datafile is not supported"));
                            capability.dropDatafile(connect, dbspace, spaceUsage.getName());
                            return null;
                        }
                    };
                    task.setOnSucceeded(event1 -> {
                        //cancelBtn.fire();
                        NotificationUtil.showMainNotification(String.format(I18n.t("instance.notice.datafile_deleted", "数据文件\"%s\"已删除！"), spaceUsage.getName()));
                        refreshButton.fire();
                    });
                    task.setOnFailed(event1 -> {
                        String error = task.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });
                    AppExecutor.runTask(task);
                }
                // 后续可补充更多删除后的刷新逻辑
            }


            @Override
            public void onExpandDatafile(SpaceUsage spaceUsage) {
                spaceAdminOps.onExpandDatafile(spaceUsage);
            }


            @Override
            public void onUnExpandDatafile(SpaceUsage spaceUsage) {
                // 关闭数据文件自动扩展
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.unexpand_datafile.title", "数据文件关闭自动扩展"), String.format(I18n.t("instance.confirm.unexpand_datafile.content", "确定要关闭数据文件\"%s\"自动扩展吗？"), spaceUsage.getName()))){
                    int chunkId=spaceUsage.getNumber();
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            try {
                                adminService.setStorageSegmentExtendable(connect, chunkId, false);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                            return null;
                        }
                    };
                    task.setOnSucceeded(event1 -> {
                        //cancelBtn.fire();
                        NotificationUtil.showMainNotification(String.format(I18n.t("instance.notice.datafile_unexpanded", "数据文件\"%s\"已关闭自动扩展！"), spaceUsage.getName()));
                        refreshButton.fire();
                    });
                    task.setOnFailed(event1 -> {
                        String error = task.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });
                    AppExecutor.runTask(task);
                }
                // 后续可补充更多刷新逻辑
            }
            @Override
            public void onUnlimitedSpaceSize(SpaceUsage spaceUsage) {
                // 銆屽垹闄ゆ暟鎹簱绌洪棿銆嶇殑涓氬姟閫昏緫锛堢ず渚嬶細寮瑰嚭纭寮圭獥锛?
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.unlimit_space.title", "解除大小限制"), String.format(I18n.t("instance.confirm.unlimit_space.content", "确定要解除数据库空间\"%s\"的大小限制吗？"), spaceUsage.getName()))){
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            try {
                                adminService.removeStorageSpaceLimit(connect, spaceUsage.getName());
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                            return null;
                        }
                    };
                    task.setOnSucceeded(event1 -> {
                        //cancelBtn.fire();
                        NotificationUtil.showMainNotification(String.format(I18n.t("instance.notice.space_unlimited", "数据库空间\"%s\"已解除大小限制！"), spaceUsage.getName()));
                        refreshButton.fire();
                    });
                    task.setOnFailed(event1 -> {
                        String error = task.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });
                    AppExecutor.runTask(task);
                }
                // 后续可补充更多刷新逻辑
            }

            @Override
            public void onOracleCreateTablespace(SpaceUsage spaceUsage) {
                spaceAdminOps.onOracleCreateTablespace(spaceUsage);
            }

            @Override
            public void onOracleDropTablespace(SpaceUsage spaceUsage) {
                String tsName = spaceUsage.getName();
                if (InstanceMutationUtil.isProtectedTablespace(connect.getDbtype(), tsName)) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            I18n.t("instance.error.oracle_ts_protected",
                                    "不允许删除系统关键表空间（如 SYSTEM、SYSAUX、UNDO*、TEMP）。"));
                    return;
                }
                CheckBox incl = new CheckBox(I18n.t(
                        "instance.dialog.oracle.drop.including_files",
                        "同时删除数据文件 (INCLUDING DATAFILES)"));
                incl.setSelected(true);
                Label warn = new Label(String.format(
                        I18n.t("instance.dialog.oracle.drop.warning", "将删除表空间「%s」及其内容。此操作不可撤销。"),
                        tsName));
                warn.setWrapText(true);
                VBox vb = new VBox(10, warn, incl);
                vb.setPadding(new Insets(10));

                ImageView loadingIv = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);
                Label runningLabel = new Label(I18n.t("instance.dialog.oracle.executing", "正在执行…"));
                runningLabel.textProperty().bind(I18n.bind("instance.dialog.oracle.executing", "正在执行…"));
                Button dropOracleDdlStopButton = new Button("");
                dropOracleDdlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
                dropOracleDdlStopButton.setFocusTraversable(false);
                dropOracleDdlStopButton.getStyleClass().add("small");
                Tooltip dropOracleDdlStopTip = new Tooltip();
                dropOracleDdlStopTip.textProperty().bind(I18n.bind("popup.back_sql.stop.tooltip", "停止此任务"));
                dropOracleDdlStopButton.setTooltip(dropOracleDdlStopTip);
                HBox loadingRow = new HBox(8, loadingIv, runningLabel, dropOracleDdlStopButton);
                loadingRow.setAlignment(Pos.CENTER);
                loadingRow.getStyleClass().add("modal-progress-card-padded");
                loadingRow.setMaxHeight(20);
                HBox overlayBar = new HBox(loadingRow);
                overlayBar.setAlignment(Pos.CENTER);
                overlayBar.getStyleClass().add("modal-progress-overlay-strong");
                overlayBar.setVisible(false);
                StackPane root = new StackPane(vb, overlayBar);

                final AtomicReference<Task<Void>> dropOracleDdlTaskHolder = new AtomicReference<>();
                final AtomicReference<Statement> dropOracleDdlStatementHolder = new AtomicReference<>();
                Runnable stopDropOracleDdl = () -> {
                    Statement st = dropOracleDdlStatementHolder.get();
                    if (st != null) {
                        try {
                            st.cancel();
                        } catch (SQLException ignored) {
                        }
                    }
                    Task<Void> t = dropOracleDdlTaskHolder.getAndSet(null);
                    if (t != null) {
                        t.cancel(false);
                    }
                    Platform.runLater(() -> {
                        if (overlayBar.isVisible()) {
                            overlayBar.setVisible(false);
                            runningLabel.textProperty().unbind();
                        }
                    });
                };
                dropOracleDdlStopButton.setOnAction(e -> stopDropOracleDdl.run());

                ButtonType delType = new ButtonType(
                        I18n.t("instance.dialog.oracle.drop_button", "删除表空间"),
                        ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelType = new ButtonType(
                        I18n.t("common.cancel", "取消"),
                        ButtonBar.ButtonData.CANCEL_CLOSE);
                AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                        I18n.t("instance.dialog.oracle.drop_ts.title", "删除 Oracle 表空间"),
                        root,
                        460,
                        Region.USE_COMPUTED_SIZE,
                        delType,
                        cancelType);
                Button delBtn = dialog.getButton(delType);
                Button cancelBtn = dialog.getButton(cancelType);
                dialog.getStage().setOnCloseRequest((WindowEvent ev) -> {
                    if (overlayBar.isVisible()) {
                        ev.consume();
                        stopDropOracleDdl.run();
                    }
                });
                cancelBtn.addEventFilter(ActionEvent.ACTION, ev -> {
                    if (overlayBar.isVisible()) {
                        ev.consume();
                        stopDropOracleDdl.run();
                    }
                });
                delBtn.disableProperty().bind(overlayBar.visibleProperty());
                delBtn.addEventFilter(ActionEvent.ACTION, event -> {
                    event.consume();
                    boolean includingDatafiles = incl.isSelected();
                    overlayBar.setVisible(true);
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            InstanceMutationUtil.dropOracleTablespace(
                                    connect, tsName, includingDatafiles, dropOracleDdlStatementHolder);
                            return null;
                        }
                    };
                    dropOracleDdlTaskHolder.set(task);
                    task.setOnSucceeded(e1 -> Platform.runLater(() -> {
                        dropOracleDdlTaskHolder.set(null);
                        overlayBar.setVisible(false);
                        runningLabel.textProperty().unbind();
                        cancelBtn.fire();
                        NotificationUtil.showMainNotification(
                                I18n.t("instance.notice.oracle_ts_dropped", "表空间已删除"));
                        refreshButton.fire();
                    }));
                    task.setOnFailed(e1 -> Platform.runLater(() -> {
                        dropOracleDdlTaskHolder.set(null);
                        overlayBar.setVisible(false);
                        runningLabel.textProperty().unbind();
                        Throwable ex = task.getException();
                        if (task.isCancelled() || InstanceMutationUtil.isLikelyOracleStatementCancelled(ex)) {
                            NotificationUtil.showMainNotification(
                                    I18n.t("instance.notice.oracle_ddl_cancelled", "已中止执行"));
                            return;
                        }
                        String msg = ex == null ? "" : ex.getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), msg);
                    }));
                    AppExecutor.runTask(task);
                });
                dialog.showAndWait();
            }

            @Override
            public void onOracleAddDatafile(SpaceUsage spaceUsage) {
                spaceAdminOps.onOracleAddDatafile(spaceUsage);
            }

            @Override
            public void onOracleDatafileAutoextend(SpaceUsage spaceUsage, boolean enable) {
                spaceAdminOps.onOracleDatafileAutoextend(spaceUsage, enable);
            }

            @Override
            public void onOracleDatafileDrop(SpaceUsage spaceUsage) {
                String ts = InstanceMutationUtil.parseOracleTablespaceFromDatafileLabel(spaceUsage.getLabel());
                if (ts == null || ts.isBlank()) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            I18n.t("instance.error.oracle_datafile_label", "无法解析数据文件所属表空间。"));
                    return;
                }
                if (InstanceMutationUtil.isProtectedTablespace(connect.getDbtype(), ts)) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            I18n.t("instance.error.oracle_datafile_drop_protected", "不允许在系统关键表空间上删除数据文件。"));
                    return;
                }
                String path = spaceUsage.getName();
                String title = I18n.t("instance.confirm.oracle.datafile_drop.title", "删除 Oracle 数据文件");
                String content = String.format(
                        I18n.t(
                                "instance.confirm.oracle.datafile_drop.content",
                                "确定从表空间「%2$s」中删除数据文件「%1$s」吗？须满足 Oracle 对该数据文件的删除条件。"),
                        path,
                        ts);
                if (!AlertUtil.CustomAlertConfirm(title, content)) {
                    return;
                }
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        InstanceMutationUtil.dropOracleDatafile(connect, ts, path, null);
                        return null;
                    }
                };
                task.setOnSucceeded(e -> {
                    NotificationUtil.showMainNotification(
                            String.format(I18n.t("instance.notice.oracle_datafile_dropped", "数据文件「%s」已删除"), path));
                    refreshButton.fire();
                });
                task.setOnFailed(e -> AlertUtil.CustomAlert(
                        I18n.t("common.error", "错误"),
                        task.getException() == null ? "" : task.getException().getMessage()));
                AppExecutor.runTask(task);
            }

        };

        // 启停 tab 初始化变量
        startStackPane=new StackPane();
        startButton=new Button();
        startButton.getStyleClass().add("custom-button");
        startButton.setGraphic(IconFactory.group(IconPaths.SQL_RUN, 0.7, Color.valueOf("#51dd66")));
        startButton.setFocusTraversable(false);
        Tooltip startTooltip = new Tooltip();
        startTooltip.textProperty().bind(I18n.bind("instance.button.start.tooltip", "点击启动数据库"));
        startButton.setTooltip(startTooltip);
        stopButton=new Button();
        stopButton.getStyleClass().add("custom-button");
        stopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.75, IconFactory.stopColor()));
        stopButton.setFocusTraversable(false);
        Tooltip stopTooltip = new Tooltip();
        stopTooltip.textProperty().bind(I18n.bind("instance.button.stop.tooltip", "点击关闭数据库"));
        stopButton.setTooltip(stopTooltip);
        ipLabel=new Label();
        ipLabel.textProperty().bind(Bindings.createStringBinding(
                () -> String.format(I18n.t("instance.label.ip.format", "IP：%s"), instanceIpText.get()),
                instanceIpText,
                I18n.localeProperty()
        ));
        statusLabel=new Label();
        statusLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            switch (instanceStatusTextCode.get()) {
                case "online":
                    return I18n.t("instance.status.online", "实例状态：在线");
                case "offline":
                    return I18n.t("instance.status.offline", "实例状态：离线");
                default:
                    return I18n.t("instance.status.unknown", "实例状态：未知");
            }
        }, instanceStatusTextCode, I18n.localeProperty()));
        instanceStatusTextCode.set("unknown");
        StackPane startAndStopButton=new StackPane(startButton,stopButton);
        StackPane btnPane=new StackPane(startAndStopButton);
        startButton.setVisible(false);
        stopButton.setVisible(false);
        HBox hbox=new HBox(10,ipLabel,statusLabel,btnPane);
        hbox.setAlignment(Pos.CENTER);
        hbox.setMaxHeight(50);
        startStackPane.getChildren().add(hbox);

        //stopButton.disableProperty().bind(startButton.disableProperty().not());
        startButton.setOnAction(e -> {
            Task<Void> instanceInfoTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    InstanceTabCapability capability = resolveInstanceTabCapability()
                            .orElseThrow(() -> new UnsupportedOperationException("Instance start is not supported"));
                    capability.startInstance(connect);
                    return null;
                }
            };
            instanceInfoTask.setOnSucceeded(event1 -> {
                NotificationUtil.showMainNotification(I18n.t("instance.notice.started", "数据库已启动。"));
                startButton.setDisable(false);
                startButton.setVisible(false);
                stopButton.setVisible(true);
                instanceStatusTextCode.set("online");

            });
            instanceInfoTask.setOnFailed(event1 -> {
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("instance.error.start_exception", "数据库启动出现异常，请查看日志。"));

                startButton.setDisable(false);
            });
            startButton.setDisable(true);
            connect.executeSqlTask(instanceInfoTask);
        });

        stopButton.setOnAction(e -> {
            if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.stop.title", "关闭数据库"), I18n.t("instance.confirm.stop.content", "确定要关闭数据库吗？"))) {
                Task<Void> instanceInfoTask = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        InstanceTabCapability capability = resolveInstanceTabCapability()
                                .orElseThrow(() -> new UnsupportedOperationException("Instance stop is not supported"));
                        capability.stopInstance(connect);
                        return null;
                    }
                };
                instanceInfoTask.setOnSucceeded(event1 -> {
                    stopButton.setDisable(false);
                    stopButton.setVisible(false);
                    startButton.setVisible(true);
                    NotificationUtil.showMainNotification(I18n.t("instance.notice.stopped", "数据库已关闭。"));
                    instanceStatusTextCode.set("offline");

                });
                instanceInfoTask.setOnFailed(event1 -> {
                    stopButton.setDisable(false);
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("instance.error.stop_exception", "数据库停止出现异常，请查看日志。"));
                });
                stopButton.setDisable(true);
                connect.executeSqlTask(instanceInfoTask);
            }
        });
        StackPane.setAlignment(hbox, Pos.CENTER);

        //涓籺abpane鍒濆鍖?
        mainTabPane=new TabPane();
        infoTab=new CustomTab(I18n.t("metadata.menu.instance_info", "实例信息"));
        checkTab=new CustomTab(I18n.t("metadata.menu.health_check", "一键巡检"));
        //checkTab.setContent(createCheckTab());
        logTab=new CustomTab(I18n.t("metadata.menu.online_log", "运行日志"));
        spaceTab=new CustomTab(I18n.t("metadata.menu.space_manager", "容量管理"));
        paramsTab=new CustomTab(I18n.t("metadata.menu.onconfig", "参数管理"));
        startTab=new CustomTab(I18n.t("metadata.menu.instance_start_stop", "实例启停"));
        infoTab.textProperty().bind(I18n.bind("metadata.menu.instance_info", "实例信息"));
        checkTab.textProperty().bind(I18n.bind("metadata.menu.health_check", "一键巡检"));
        logTab.textProperty().bind(I18n.bind("metadata.menu.online_log", "运行日志"));
        spaceTab.textProperty().bind(I18n.bind("metadata.menu.space_manager", "容量管理"));
        paramsTab.textProperty().bind(I18n.bind("metadata.menu.onconfig", "参数管理"));
        startTab.textProperty().bind(I18n.bind("metadata.menu.instance_start_stop", "实例启停"));
        infoTab.setContextMenu(null);
        checkTab.setContextMenu(null);
        logTab.setContextMenu(null);
        spaceTab.setContextMenu(null);
        paramsTab.setContextMenu(null);
        startTab.setContextMenu(null);

        refreshButton = new Button();
        refreshButton.setId("codearea-camera-button");
        refreshButton.setGraphic(IconFactory.group(IconPaths.METADATA_REFRESH_ITEM, 0.7));
        refreshButton.setFocusTraversable(false);
        refreshButton.setOnAction(e -> {
            refreshCurrentTab();
                });



        // 给每个 Tab 绑定“首次选中加载”逻辑
        bindLazyLoadToTab(infoTab, () -> loadInfoTabContent(infoTab), () -> infoTabLoaded, (loaded) -> infoTabLoaded = loaded);
       bindLazyLoadToTab(checkTab, this::loadCheckTabThroughCapability, () -> checkTabLoaded, (loaded) -> checkTabLoaded = loaded);
        bindLazyLoadToTab(logTab, this::loadLogTabThroughCapability, () -> logTabLoaded, (loaded) -> logTabLoaded = loaded);
        bindLazyLoadToTab(spaceTab, () -> loadSpaceTabThroughLoader(spaceTab), () -> spaceTabLoaded, (loaded) -> spaceTabLoaded = loaded);
       bindLazyLoadToTab(paramsTab, this::loadParamsTabThroughCapability, () -> paramsTabLoaded, (loaded) -> paramsTabLoaded = loaded);
        bindLazyLoadToTab(startTab, this::loadStartTabThroughCapability, () -> startTabLoaded, (loaded) -> startTabLoaded = loaded);

        mainTabPane.getTabs().add(infoTab);
        if (supportsHealthCheck()) {
            mainTabPane.getTabs().add(checkTab);
        }
        if (supportsOnlineLog()) {
            mainTabPane.getTabs().add(logTab);
        }
        if (supportsSpaceManager()) {
            mainTabPane.getTabs().add(spaceTab);
        }
        if (supportsConfigManagement()) {
            mainTabPane.getTabs().add(paramsTab);
        }
        if (supportsStartStop()) {
            mainTabPane.getTabs().add(startTab);
        }
        // 实例信息子 tab 不需要右键菜单
        mainTabPane.setContextMenu(null);
        mainTabPane.getStyleClass().add("bg-content");
        StackPane  stackPane=new StackPane(mainTabPane,refreshButton,instanceInfoLabel);
        StackPane.setAlignment(refreshButton,Pos.BOTTOM_RIGHT);
        StackPane.setAlignment(instanceInfoLabel,Pos.BOTTOM_RIGHT);
        StackPane.setMargin(refreshButton, new Insets(0, 15, 20, 0));
        StackPane.setMargin(instanceInfoLabel, new Insets(0, 15, 0, 0));

        setContent(stackPane);
        mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabPane.setSide(Side.BOTTOM);

        //refreshButton.fire();
        mainTabPane.setId("instanceManagerTabPane");
        mainTabPane.getSelectionModel().select(tabNum);
        // 方式2.1：直接添加样式字符串

        // 如果是只读，禁用一些操作
        if(connect.getReadonly()) {
            configTableView.setEditable(false);
            startButton.setDisable(true);
            stopButton.setDisable(true);
        }


    }



    private void refreshCurrentTab() {
        Tab currentTab = mainTabPane.getSelectionModel().getSelectedItem();
        if (currentTab == null) return;

        // 鏄剧ず鍔犺浇鍗犱綅绗?
        Node currentContent = currentTab.getContent();
        if (currentContent != null && "loadingNode".equals(currentContent.getId())) {
            // 可选：提示用户正在加载中
            return;
        }
        currentTab.setContent(createLoadingNode());

        // 异步刷新，避免阻塞 UI
        AppExecutor.runAsync(() -> {
            try {
                updateGroupInstanceInfo();
                // 鏍规嵁褰撳墠 Tab 绫诲瀷鎵ц鍒锋柊
                if (currentTab == infoTab) {
                    loadInfoTabContent(infoTab);
                } else if (currentTab == checkTab) {
                    loadCheckTabThroughCapability();
                } else if (currentTab == logTab) {
                    loadLogTabThroughCapability();
                } else if (currentTab == spaceTab) {
                   loadSpaceTabThroughLoader(spaceTab);
                } else if (currentTab == paramsTab) {
                    loadParamsTabThroughCapability();
                } else if (currentTab == startTab) {
                    loadStartTabThroughCapability();
                }

            } catch (Exception e) {
                log.error(e.getMessage(), e);
                Platform.runLater(() -> {
                    currentTab.setContent(createErrorNode(e.getMessage()));
                });
            }
        });
    }

    private void updateGroupInstanceInfo() {
        String namedServerProp = resolveNamedServerPropertyName();
        if (namedServerProp.isEmpty() || connect.getPropByName(namedServerProp).isEmpty()) {
            return;
        }
        try {
            String primaryInstance = connectionService.refreshRuntimeConnectInfo(connect);
            if (primaryInstance == null || primaryInstance.isBlank()) {
                return;
            }
            String regex = "^" + Pattern.quote(primaryInstance) + "\\s+.*\\s+g="
                    + Pattern.quote(connect.getPropByName(namedServerProp)) + "\\s*$";
            Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
            String sqlhostsContent = Files.readString(Paths.get("extlib", connect.getDbtype(), "sqlhosts"));

            Matcher matcher = pattern.matcher(sqlhostsContent);
            while (matcher.find()) {
                String matchedLine = matcher.group();
                log.info("鍖归厤鍒扮殑sqlhosts琛? {}", matchedLine);

                String[] parts = matchedLine.split("\\s+");
                if (parts.length < 4) {
                    continue;
                }
                String instanceName = parts[0];
                String protocol = parts[1];
                String host = parts[2];
                String port = parts[3];
                connect.setIp(host);
                connect.setPort(port);
                connect.setInfo(replaceInfoValue(connect.getInfo(), namedServerProp, instanceName));
                Platform.runLater(() -> {
                    instanceNameText.set(instanceName);
                    instanceIpText.set(connect.getIp());
                    instancePortText.set(connect.getPort());
                });

                log.info("实例名: {}, 协议: {}, 主机: {}, 端口: {}",
                        instanceName, protocol, host, port);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private boolean isMysqlConnect() {
        return connect != null && "MYSQL".equalsIgnoreCase(connect.getDbtype());
    }

    private String resolveNamedServerPropertyName() {
        return platformResolver.capability(connect, NamedServerConnectionCapability.class)
                .map(NamedServerConnectionCapability::namedServerPropertyName)
                .orElse("");
    }

    private boolean supportsHealthCheck() {
        return resolveInstanceTabCapability()
                .map(cap -> cap.supportsHealthCheckTab(connect))
                .orElseGet(() -> resolveInstanceAdminRepository().supportsHealthCheck(connect));
    }

    private boolean supportsOnlineLog() {
        return resolveInstanceTabCapability()
                .map(cap -> cap.supportsLogTab(connect))
                .orElseGet(() -> resolveInstanceAdminRepository().supportsOnlineLog(connect));
    }

    private boolean supportsSpaceManager() {
        return resolveInstanceAdminRepository().supportsSpaceManager(connect);
    }

    private boolean supportsConfigManagement() {
        return resolveInstanceTabCapability()
                .map(cap -> cap.supportsConfigTab(connect))
                .orElseGet(() -> resolveInstanceAdminRepository().supportsConfigManagement(connect));
    }

    private boolean canEditConfig() {
        return resolveInstanceTabCapability()
                .map(cap -> cap.canEditConfig(connect))
                .orElseGet(this::supportsConfigManagement);
    }

    private boolean supportsStartStop() {
        return resolveInstanceTabCapability()
                .map(cap -> cap.supportsStartStopTab(connect))
                .orElseGet(() -> resolveInstanceAdminRepository().supportsStartStop(connect));
    }

    private boolean supportsSpaceMutation() {
        return resolveInstanceAdminRepository().supportsSpaceMutation(connect);
    }

    private InstanceAdminRepository resolveInstanceAdminRepository() {
        return platformResolver.admin(connect);
    }

    private String resolveRuntimeLogCommand() {
        return resolveInstanceTabCapability()
                .map(capability -> capability.runtimeLogCommand(connect))
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> resolveInstanceManagerCapability()
                        .map(InstanceManagerCapability::runtimeLogCommand)
                        .orElse("onstat -m"));
    }

    private java.util.Optional<InstanceManagerCapability> resolveInstanceManagerCapability() {
        return platformResolver.capability(connect, InstanceManagerCapability.class);
    }

    private java.util.Optional<InstanceTabCapability> resolveInstanceTabCapability() {
        return platformResolver.capability(connect, InstanceTabCapability.class);
    }

    private InstanceTabCapability.SpaceLabels resolveSpaceLabels() {
        return resolveInstanceTabCapability()
                .map(capability -> capability.spaceLabels(connect))
                .orElseGet(InstanceTabCapability::informixGbaseDefaultSpaceLabels);
    }

    private void bindSpaceLabel(Label label, String i18nKey, String fallback) {
        if (i18nKey != null && !i18nKey.isBlank()) {
            label.textProperty().bind(I18n.bind(i18nKey, fallback));
        } else {
            label.setText(fallback);
        }
    }

    private String resolveInstanceName() {
        String instanceName = resolveInstanceTabCapability()
                .map(capability -> capability.instanceName(connect))
                .filter(value -> value != null && !value.isBlank())
                .orElse("");
        if (!instanceName.isEmpty()) {
            return instanceName;
        }
        String namedServerProp = resolveNamedServerPropertyName();
        instanceName = extractInfoValue(connect.getInfo(), namedServerProp);
        if (!instanceName.isEmpty()) {
            return instanceName;
        }
        String propValue = namedServerProp.isEmpty() ? "" : connect.getPropByName(namedServerProp);
        return propValue == null ? "" : propValue.trim();
    }

    private static String extractInfoValue(String info, String key) {
        if (info == null || info.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s+(.+?)\\s*$").matcher(info);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    private static String replaceInfoValue(String info, String key, String value) {
        if (info == null || info.isBlank() || key == null || key.isBlank()) {
            return info;
        }
        return info.replaceAll(
                "(?m)^(" + Pattern.quote(key) + "\\s+)\\S+\\s*$",
                "$1" + Matcher.quoteReplacement(value == null ? "" : value));
    }
    /**
     * 通用 Tab 懒加载绑定方法
     * @param tab 目标 Tab
     * @param loadTask 加载内容的任务
     * @param isLoaded 判断是否已加载的回调
     * @param setLoaded 设置已加载状态的回调
     */
    private void bindLazyLoadToTab(Tab tab, Runnable loadTask, Supplier<Boolean> isLoaded, Consumer<Boolean> setLoaded) {
        // 初始化 Tab 内容为加载占位符
        tab.setContent(createLoadingNode());

        // 绑定选中状态监听器
        tab.selectedProperty().addListener((obs, oldVal, newVal) -> {
            // 仅当首次选中且未加载时触发加载
            if (newVal && !isLoaded.get()) {
                // 异步执行加载任务，避免阻塞 UI
                AppExecutor.runAsync(() -> {
                    try {
                        updateGroupInstanceInfo();
                        setLoaded.accept(true); // 标记为已加载
                        loadTask.run(); // 执行加载逻辑
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                        // 加载失败时显示错误信息
                        Platform.runLater(() -> {
                            tab.setContent(createErrorNode(e.getMessage()));
                        });
                    }
                });
            }
        });
    }

    // ------------------------------ 各个 Tab 的加载逻辑 ------------------------------
    /**
     * 加载“实例信息”Tab 内容
     */
    private void loadInfoTabContent(CustomTab infoTab) {

        // 信息框绑定
        Platform.runLater(() -> {
            String textArarText = resolveInstanceTabCapability()
                    .map(capability -> capability.buildInfoText(connect))
                    .orElse("");

            // 实例信息界面
            CustomInfoStackPane instance_info_codearea = new CustomInfoStackPane(new CustomInfoCodeArea());

            // 实例信息界面结束
            instance_info_codearea.codeArea.replaceText(textArarText); // 使用 replaceText，避免滚动定位失效
            infoTab.setContent(instance_info_codearea);

            instance_info_codearea.codeArea.showParagraphAtTop(0); //姝ゆ柟娉曟湁鏁?
            instance_info_codearea.codeArea.setStyleSpans(0, KeywordsHighlightUtil.applyHighlightingInfo(instance_info_codearea.codeArea.getText()));
        });

    }

    private void loadSpaceTabThroughLoader(CustomTab spaceTab) {
        InstanceTabLoaders.SpaceTabData data = InstanceTabLoaders.loadSpaceTabData(log, connect, adminService);
        dbspaceChartList = data.dbspaceList();
        chunkChartList = data.chunkChartList();
        databaseChartList = data.databaseChartList();
        tabChartList = data.tabChartList();
        InstanceTabLoaders.renderSpaceTab(
                data,
                dbspaceChart,
                chunkChart,
                databaseChart,
                tabChart,
                menuListener,
                dbspaceStackPane,
                spaceTab,
                connect.getReadonly() || !supportsSpaceMutation()
        );
    }

    private void loadCheckTabThroughCapability() {
        InstanceTabCapability.CheckTableModel model = resolveInstanceTabCapability()
                .map(capability -> {
                    try {
                        return capability.buildCheckTable(connect);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElse(new InstanceTabCapability.CheckTableModel(List.of(), List.of()));

        Platform.runLater(() -> {
            applyCheckTableColumns(model.columns());
            checkDatalist.clear();
            checkDatalist.addAll(model.rows());
            checkTableView.getItems().clear();
            checkTableView.getItems().setAll(checkDatalist);
            checkTableView.refresh();
            checkTab.setContent(checkStackPane);
        });
    }

    private void loadLogTabThroughCapability() {
        String onlineLog = resolveInstanceTabCapability()
                .map(capability -> {
                    try {
                        return capability.loadRuntimeLog(connect);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElse("");

        Platform.runLater(() -> {
            CustomInfoCodeArea logCodeArea = new CustomInfoCodeArea();
            CustomInfoStackPane customInfoStackPane = new CustomInfoStackPane(logCodeArea);
            StackPane stackPane = new StackPane(customInfoStackPane);
            logCodeArea.replaceText(onlineLog);
            logCodeArea.setStyleSpans(0, KeywordsHighlightUtil.applyHighlightingOnlinelog(logCodeArea.getText()));
            logCodeArea.showParagraphAtBottom(logCodeArea.getParagraphs().size() - 1);
            logTab.setContent(stackPane);
        });
    }

    private void loadParamsTabThroughCapability() {
        List<InstanceTabCapability.ConfigEntry> rows = resolveInstanceTabCapability()
                .map(capability -> {
                    try {
                        return capability.loadConfigEntries(connect);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElse(List.of());

        Platform.runLater(() -> {
            configDatalist.clear();
            for (InstanceTabCapability.ConfigEntry row : rows) {
                configDatalist.add(FXCollections.observableArrayList(null, row.name(), row.value()));
            }
            configTableView.getItems().setAll(configDatalist);
            configTableView.refresh();
            configTableView.setVisible(true);
            paramsTab.setContent(configStackPane);
        });
    }



    private void loadStartTabThroughCapability() {
        if (!supportsStartStop()) {
            // Dialect does not support instance start/stop, keep buttons hidden
            Platform.runLater(() -> startTab.setContent(startStackPane));
            return;
        }
        boolean online = resolveInstanceTabCapability()
                .map(capability -> {
                    try {
                        return capability.isInstanceOnline(connect);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElse(false);

        Platform.runLater(() -> {
            if (online) {
                stopButton.setVisible(true);
                startButton.setVisible(false);
                instanceStatusTextCode.set("online");
            } else {
                startButton.setVisible(true);
                stopButton.setVisible(false);
                instanceStatusTextCode.set("offline");
            }
            startTab.setContent(startStackPane);
        });
    }




    private void initCheckTableColumns() {
        applyCheckTableColumns(List.of(
                new InstanceTabCapability.CheckColumn("entry", "instance.check.column.item", "巡检项", InstanceTabCapability.CheckColumnKind.TEXT, 200),
                new InstanceTabCapability.CheckColumn("cmd", "instance.check.column.cmd", "巡检命令", InstanceTabCapability.CheckColumnKind.TEXT, 100),
                new InstanceTabCapability.CheckColumn("healthValue", "instance.check.column.expected", "正常值", InstanceTabCapability.CheckColumnKind.TEXT, 300),
                new InstanceTabCapability.CheckColumn("currentValue", "instance.check.column.current", "当前值", InstanceTabCapability.CheckColumnKind.TEXT, 300),
                new InstanceTabCapability.CheckColumn("status", "instance.check.column.result", "巡检结论", InstanceTabCapability.CheckColumnKind.STATUS, 100)
        ));
    }

    private void applyCheckTableColumns(List<InstanceTabCapability.CheckColumn> columns) {
        checkTableView.getColumns().removeIf(column -> column != null && !"".equals(column.getText()));
        if (columns == null || columns.isEmpty()) {
            return;
        }
        for (InstanceTabCapability.CheckColumn columnDef : columns) {
            TableColumn<InstanceTabCapability.CheckRow, Object> column = new TableColumn<>();
            if (columnDef.titleI18nKey() != null && !columnDef.titleI18nKey().isBlank()) {
                column.textProperty().bind(I18n.bind(columnDef.titleI18nKey(), columnDef.title()));
            } else {
                column.setText(columnDef.title());
            }
            if (columnDef.kind() == InstanceTabCapability.CheckColumnKind.STATUS) {
                column.setCellFactory(col -> new CustomCheckTableCell<InstanceTabCapability.CheckRow, Object>());
            } else {
                column.setCellFactory(col -> new CustomTableCell<InstanceTabCapability.CheckRow, Object>());
            }
            column.setCellValueFactory(data -> Bindings.createObjectBinding(() -> {
                String value = data.getValue().value(columnDef.key());
                String valueI18nKey = data.getValue().valueI18nKey(columnDef.key());
                if (valueI18nKey != null && !valueI18nKey.isBlank()) {
                    return I18n.t(valueI18nKey, value);
                }
                return value;
            }, I18n.localeProperty()));
            column.setReorderable(false);
            column.setEditable(false);
            column.setPrefWidth(columnDef.prefWidth());
            checkTableView.getColumns().add(column);
        }
        if (!checkTableView.getColumns().isEmpty()) {
            ((TableColumn<?, ?>) checkTableView.getColumns().get(0)).setMaxWidth(30);
            ((TableColumn<?, ?>) checkTableView.getColumns().get(0)).setMinWidth(30);
        }
    }

    public void initCheckDatalist(List<InstanceTabCapability.CheckRow> datalist){
        datalist.clear();
    }

    private Node createLoadingNode() {
        ImageView loadingIcon = IconFactory.loadingImageView(0.7);
        StackPane loadingPane = new StackPane(loadingIcon);
        loadingPane.setId("loadingNode");
        return loadingPane;

    }

    private  Node createErrorNode(String mesg){
        ImageView errorIcon = IconFactory.imageView(IconPaths.DIALOG_ERROR, 16, 16, true);
        Label mesgLabel = new Label(stripExceptionPrefix(mesg));
        mesgLabel.setWrapText(true);
        HBox hBox=new HBox(5,errorIcon,mesgLabel);
        hBox.setAlignment(Pos.CENTER);
        StackPane errorPane = new StackPane(hBox);
        // 限制文本宽度以触发自动换行，留出图标与两侧边距
        mesgLabel.maxWidthProperty().bind(errorPane.widthProperty().subtract(80));
        return errorPane;
    }

    /** 去掉异常链包装产生的类名前缀（如 java.lang.RuntimeException: java.lang.Exception:），只保留业务文案 */
    private static String stripExceptionPrefix(String mesg) {
        if (mesg == null) {
            return "";
        }
        String result = mesg.trim();
        Pattern prefixPattern = Pattern.compile("^(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*:\\s*");
        Matcher matcher = prefixPattern.matcher(result);
        while (matcher.find()) {
            result = result.substring(matcher.end());
            matcher = prefixPattern.matcher(result);
        }
        return result;
    }
}


