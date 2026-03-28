package com.dbboys.customnode;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.service.AdminService;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.api.ConnectionService;
import com.dbboys.util.*;
import com.dbboys.vo.Connect;
import com.dbboys.vo.HealthCheck;
import com.jcraft.jsch.Session;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomInstanceTab extends CustomTab {
    private static final Logger log = LogManager.getLogger(CustomInstanceTab.class);
    private final AdminService adminService = new AdminService();
    private final ConnectionService connectionService = com.dbboys.app.AppContext.get(ConnectionService.class);
    private Connect connect;
    // 为每个需要懒加载的 Tab 定义「已加载」标记
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
    private String onlinelog;

    //checktab

    private CustomTableView<HealthCheck> checkTableView = new CustomTableView<>();
    private List<HealthCheck> checkDatalist = FXCollections.observableArrayList();//如果确认，返回更新后的list
    private StackPane checkStackPane;

    //configtab
    private CustomTableView<ObservableList<String>> configTableView = new CustomTableView<>();
    private List<ObservableList<String>> configDatalist = FXCollections.observableArrayList();//如果确认，返回更新后的list
    private StackPane configStackPane;

    //spacetab变量
    private CustomSpaceChart.ContextMenuListener menuListener;
    private CustomSpaceChart dbspaceChart;
    private CustomSpaceChart chunkChart;
    private CustomSpaceChart databaseChart;
    private CustomSpaceChart tabChart;
    private StackPane dbspaceStackPane;
    List<CustomSpaceChart.SpaceUsage> dbspaceChartList = new ArrayList<>();
    List<CustomSpaceChart.SpaceUsage> chunkChartList = new ArrayList<>();
    List<CustomSpaceChart.SpaceUsage> databaseChartList = new ArrayList<>();
    List<CustomSpaceChart.SpaceUsage> tabChartList = new ArrayList<>();
    List<List<CustomSpaceChart.SpaceUsage>> dataList = new ArrayList<>();
    String datafilePath="";



    //启停变量
    private String instanceStatus="";
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
        super("[instance]"+connect.getName());
        this.connect=connect;

        //实例信息tab初始化变量
        String instanceName=connect.getInfo().split("GBASEDBTSERVER")[1].split("\n")[0].trim();
        instanceNameText.set(instanceName);
        instanceIpText.set(connect.getIp());
        instancePortText.set(connect.getPort());
        instanceInfoLabel=new Label();
        instanceInfoLabel.textProperty().bind(Bindings.createStringBinding(
                () -> String.format(
                        I18n.t("instance.info.current.format", "当前实例信息 ( IP：%s   端口：%s   实例名：%s )"),
                        instanceIpText.get(),
                        instancePortText.get(),
                        instanceNameText.get()
                ),
                instanceIpText,
                instancePortText,
                instanceNameText,
                I18n.localeProperty()
        ));
        instanceInfoLabel.setStyle("-fx-font-size:9");

        if(connect.getReadonly()){
            instanceInfoLabel.setGraphic(IconFactory.group(IconPaths.SQL_READONLY, 0.45, IconFactory.dangerColor()));
            instanceInfoLabel.setContentDisplay(ContentDisplay.RIGHT);
            Tooltip readonlyTooltip = new Tooltip();
            readonlyTooltip.textProperty().bind(I18n.bind("instance.info.readonly.tooltip", "当前连接只读，管理操作已禁用！"));
            instanceInfoLabel.setTooltip(readonlyTooltip);
        }

        //UI
        checkTableView.setEditable(false);
        checkTableView.setSortPolicy((param) -> false);//禁用排序
        checkTableView.setRowFactory(tv -> {
            TableRow<HealthCheck> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY && !row.isEmpty()) {
                    HealthCheck rowData = row.getItem();
                    if("onstat -m".equals(rowData.getCmd())) {
                        mainTabPane.getSelectionModel().select(logTab);
                    }else
                    if(rowData.getCmdOutput()!=null&&!rowData.getCmdOutput().isEmpty()){
                            PopupWindowUtil.openCmdOutputPopupWindow(rowData.getCmdOutput());
                    }
                }
            });
            return row;
        });
        TableColumn<HealthCheck, Object> nameColumn = new TableColumn<>();
        nameColumn.textProperty().bind(I18n.bind("instance.check.column.item", "巡检项"));
        nameColumn.setCellFactory(col -> new CustomTableCell<HealthCheck, Object>());
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("entry"));
        nameColumn.setReorderable(false); // 禁用拖动
        nameColumn.setEditable(false);
        nameColumn.setReorderable(false);
        nameColumn.setPrefWidth(200);
        TableColumn<HealthCheck, Object> cmdColumn = new TableColumn<>();
        cmdColumn.textProperty().bind(I18n.bind("instance.check.column.cmd", "巡检命令"));
        cmdColumn.setCellFactory(col -> new CustomTableCell<HealthCheck, Object>());
        cmdColumn.setCellValueFactory(new PropertyValueFactory<>("cmd"));
        cmdColumn.setReorderable(false); // 禁用拖动
        cmdColumn.setEditable(false);
        cmdColumn.setReorderable(false);
        cmdColumn.setPrefWidth(100);
        TableColumn<HealthCheck, Object> valueColumn = new TableColumn<>();
        valueColumn.textProperty().bind(I18n.bind("instance.check.column.expected", "正常值"));
        valueColumn.setCellFactory(col -> new CustomTableCell<HealthCheck, Object>());
        valueColumn.setCellValueFactory(new PropertyValueFactory<>("healthValue"));
        valueColumn.setReorderable(false); // 禁用拖动
        valueColumn.setEditable(false);
        valueColumn.setReorderable(false);
        valueColumn.setPrefWidth(300);
        TableColumn<HealthCheck, Object> currentColumn = new TableColumn<>();
        currentColumn.textProperty().bind(I18n.bind("instance.check.column.current", "当前值"));
        currentColumn.setCellFactory(col -> new CustomTableCell<HealthCheck, Object>());
        currentColumn.setCellValueFactory(new PropertyValueFactory<>("currentValue"));
        currentColumn.setReorderable(false); // 禁用拖动
        currentColumn.setEditable(false);
        currentColumn.setReorderable(false);
        currentColumn.setPrefWidth(300);

        TableColumn<HealthCheck, Object> resultColumn = new TableColumn<>();
        resultColumn.textProperty().bind(I18n.bind("instance.check.column.result", "巡检结论"));
        resultColumn.setCellFactory(col -> new CustomCheckTableCell<HealthCheck, Object>());
        resultColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        resultColumn.setReorderable(false); // 禁用拖动
        resultColumn.setEditable(false);
        resultColumn.setReorderable(false);
        resultColumn.setPrefWidth(100);
        checkTableView.getColumns().addAll(nameColumn, cmdColumn, valueColumn, currentColumn, resultColumn);
        initDatalist(checkDatalist);
        checkTableView.getItems().addAll(checkDatalist);
        checkTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        ImageView loading_icon = IconFactory.loadingImageView(0.7);
        checkStackPane = new StackPane(loading_icon);
        checkStackPane.getChildren().add(checkTableView);
        ((TableColumn<?, ?>) checkTableView.getColumns().get(0)).setMaxWidth(30);
        ((TableColumn<?, ?>) checkTableView.getColumns().get(0)).setMinWidth(30);
        checkStackPane.setStyle("-fx-background-color: -color-bg-content;");
        Button checkshotButton= new Button();
        checkshotButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35));
        checkshotButton.setFocusTraversable(false);
        checkshotButton.getStyleClass().add("codearea-camera-button");
        checkStackPane.getChildren().add(checkshotButton);
        checkStackPane.setAlignment(checkshotButton, Pos.TOP_RIGHT);
        checkStackPane.setMargin(checkshotButton, new javafx.geometry.Insets(0, 15, 20, 20));
        checkshotButton.setOnAction(e->{
            SnapshotUtil.snapshotTableView(checkTableView);
        });

        //参数优化UI
        configTableView.setEditable(true);
        configTableView.setSortPolicy((param) -> false);//禁用排序
        TableColumn<ObservableList<String>, Object> configNameColumn = new TableColumn<>();
        configNameColumn.textProperty().bind(I18n.bind("instance.config.column.name", "参数名"));
        configNameColumn.setCellFactory(col -> new CustomTableCell<ObservableList<String>, Object>());
        configNameColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(1)));
        configNameColumn.setReorderable(false); // 禁用拖动
        configNameColumn.setEditable(false);
        configNameColumn.setReorderable(false);
        configNameColumn.setPrefWidth(300);
        TableColumn<ObservableList<String>, Object> configValueColumn = new TableColumn<>();
        configValueColumn.textProperty().bind(I18n.bind("instance.config.column.value", "参数值"));
        configValueColumn.setCellFactory(col -> new CustomTableCell<ObservableList<String>, Object>());
        configValueColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(2)));
        configValueColumn.setReorderable(false); // 禁用拖动
        configValueColumn.setEditable(true);
        configValueColumn.setReorderable(false);
        configValueColumn.setPrefWidth(500);

        configValueColumn.setOnEditCommit(event -> {
            String paramName=event.getRowValue().get(1).toString();
            Object oldvalue = event.getOldValue();
            //Object colvalue = event.getNewValue();
            //替换换行
            Object colvalue = event.getNewValue().toString();

            //替换换行后显示
            if(oldvalue.equals(colvalue)){
                return;
            }
            event.getRowValue().set(2, colvalue.toString());

            event.getTableView().refresh();
            String cmd;
            if(paramName.equals("BUFFERPOOL")||paramName.equals("VPCLASS")){
                cmd="sed -i \"s#^"+paramName+" *"+colvalue.toString().split(",")[0]+".*#"+paramName+" "+colvalue.toString().replace("$","\\$")+"#g\" $GBASEDBTDIR/etc/$ONCONFIG";
            }else{
                cmd="onmode -wf "+paramName+"=\""+colvalue.toString()+"\";sed -i \"s#^"+paramName+".*#"+paramName+" "+colvalue.toString().replace("$","\\$")+"#g\" $GBASEDBTDIR/etc/$ONCONFIG";
            }

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    try {
                        Session session=JschUtil.getConnect(connect);
                        String result = JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+ cmd,true);
                        JschUtil.disConnect(session);
                        //if (result != 0) throw new Exception("创建数据库空间失败，请检查日志错误！");
                        if(result.contains("has been changed to")){
                            Platform.runLater(()->{
                                NotificationUtil.showMainNotification("参数已修改生效！");
                            });
                        }else if(result.contains("shared memory not initialized")){
                            Platform.runLater(()->{
                                NotificationUtil.showMainNotification("配置文件已修改，数据库未启动，下次启动后生效！");
                            });
                        }else{
                            Platform.runLater(()->{
                                AlertUtil.CustomAlert("提醒", "参数已修改，请重启数据库生效！");
                            });
                        }
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);

                        //throw new Exception("ssh登录失败，请检查网络！");
                        throw new RuntimeException(e);
                    }
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
        ((TableColumn<?, ?>) configTableView.getColumns().get(0)).setMaxWidth(30);
        ((TableColumn<?, ?>) configTableView.getColumns().get(0)).setMinWidth(30);
        configStackPane.setStyle("-fx-background-color:-color-bg-content;");
        Button configshotButton= new Button();
        configshotButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35));
        configshotButton.setFocusTraversable(false);
        configshotButton.getStyleClass().add("codearea-camera-button");
        configStackPane.getChildren().add(configshotButton);
        configStackPane.setAlignment(configshotButton, Pos.TOP_RIGHT);
        configStackPane.setMargin(configshotButton, new javafx.geometry.Insets(0, 15, 20, 20));
        configshotButton.setOnAction(e->{
            SnapshotUtil.snapshotTableView(configTableView);
        });

        //初始化spacetab UI
        dbspaceChart =new CustomSpaceChart(dbspaceChartList, CustomSpaceChart.ColorMode.DBSPACE);
        Node dbspaceChartLegend = dbspaceChart.createLegend();
        Label spaceType=new Label();
        spaceType.textProperty().bind(I18n.bind("instance.space.type.legend", "[T] 临时空间   [S] 智能大对象空间   [B] 简单大对象空间   [L] 空间大小已限制   [*k] 空间页大小为*KB"));
        spaceType.setStyle("-fx-font-size: 9;-fx-padding: 0 0 5 0");
        Label dbspaceTitleLabel = new Label();
        dbspaceTitleLabel.textProperty().bind(I18n.bind("instance.space.chart.dbspace", "数据库空间使用情况图(GB)"));
        VBox dbspaceChartVbox = new VBox(dbspaceChart,dbspaceChartLegend,spaceType,dbspaceTitleLabel);
        dbspaceChartVbox.setAlignment(Pos.CENTER);
        chunkChart =new CustomSpaceChart(chunkChartList, CustomSpaceChart.ColorMode.CHUNK);
        Node chunkChartLegend = chunkChart.createLegend();
        Label chunkTitleLabel = new Label();
        chunkTitleLabel.textProperty().bind(I18n.bind("instance.space.chart.chunk", "数据文件使用情况图(GB)"));
        VBox chunkChartVbox = new VBox(chunkChart,chunkChartLegend,chunkTitleLabel);
        chunkChartVbox.setAlignment(Pos.CENTER);
        databaseChart =new CustomSpaceChart(databaseChartList, CustomSpaceChart.ColorMode.DATABASE);
        Node databaseChartLegend = databaseChart.createLegend();
        Label databaseTitleLabel = new Label();
        databaseTitleLabel.textProperty().bind(I18n.bind("instance.space.chart.database", "数据库使用空间情况(GB)"));
        VBox databaseChartVbox = new VBox(databaseChart,databaseChartLegend,databaseTitleLabel);
        databaseChartVbox.setAlignment(Pos.CENTER);
        tabChart =new CustomSpaceChart(tabChartList, CustomSpaceChart.ColorMode.TABLE);
        Node tabChartLegend = tabChart.createLegend();
        Label tableTitleLabel = new Label();
        tableTitleLabel.textProperty().bind(I18n.bind("instance.space.chart.table", "表/索引空间使用情况图TOP20(GB)"));
        VBox tabChartVbox = new VBox(tabChart,tabChartLegend,tableTitleLabel);
        tabChartVbox.setAlignment(Pos.CENTER);
        VBox charts=new VBox(50,dbspaceChartVbox,chunkChartVbox,databaseChartVbox,tabChartVbox);
        charts.setAlignment(Pos.CENTER);
        charts.setStyle("-fx-background-color: -color-bg-content;");
        ScrollPane scrollPane = new ScrollPane(charts);
        dbspaceStackPane=new StackPane(scrollPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-padding: 0;-fx-background-color:-color-bg-content;");
        dbspaceStackPane.setStyle("-fx-background-color: -color-bg-content;");
        dbspaceStackPane.setAlignment(Pos.CENTER);
        Button spaceshotButton= new Button();
        spaceshotButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, 0.35));
        spaceshotButton.setFocusTraversable(false);
        spaceshotButton.getStyleClass().add("codearea-camera-button");
        dbspaceStackPane.getChildren().add(spaceshotButton);
        dbspaceStackPane.setAlignment(spaceshotButton, Pos.TOP_RIGHT);
        dbspaceStackPane.setMargin(spaceshotButton, new javafx.geometry.Insets(0, 15, 20, 20));
        spaceshotButton.setOnAction(e->{
            SnapshotUtil.snapshotNode(scrollPane.getContent());
        });
        menuListener = new CustomSpaceChart.ContextMenuListener() {
            // -------------------------- onViewDetail 实现 --------------------------
            @Override
            public void onCreateDbspace(CustomSpaceChart.SpaceUsage spaceUsage,boolean isAddFile) {
                //加载指示器
                ImageView imageView = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);
                Button processStopButton = new Button("");
                processStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
                Label runningLabel=new Label(I18n.t("instance.dialog.init.progress", " 正在初始化...0.00%"));
                HBox imageHBox = new HBox(imageView, runningLabel, processStopButton);
                imageHBox.setStyle("-fx-background-color: rgb(58, 58, 60);-fx-background-radius: 2;-fx-padding: 0 0 0 5");
                imageHBox.setAlignment(Pos.CENTER);
                imageHBox.setMaxHeight(15);
                //imageHBox.setMaxWidth(100);
                processStopButton.setFocusTraversable(false);
                processStopButton.getStyleClass().add("small");
                HBox backgroupHbox=new HBox(imageHBox);
                backgroupHbox.setAlignment(Pos.CENTER);
                backgroupHbox.setStyle("-fx-background-color: rgba(0, 0, 0, 0.3);-fx-background-radius: 2;");
                backgroupHbox.setVisible(false);

                ButtonType commitButtonType = new ButtonType(
                        isAddFile ? I18n.t("instance.dialog.expand", "扩容") : I18n.t("instance.dialog.create", "创建"),
                        ButtonBar.ButtonData.OK_DONE
                );
                ButtonType cancelButtonType = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
                GridPane grid = new GridPane();
                grid.setHgap(10);
                grid.setVgap(5);
                grid.setPadding(new Insets(10));


                Label spaceTypeLabel=new Label(I18n.t("instance.dialog.space_type", "空间类型"));
                spaceTypeLabel.setGraphic(IconFactory.group(IconPaths.INSTANCE_SPACE_TYPE_LABEL, 0.5));
                ChoiceBox<String> spaceTypeChoiceBox = new ChoiceBox<>();
                spaceTypeChoiceBox.getItems().addAll(
                        I18n.t("instance.dialog.space_type.standard", "标准空间"),
                        I18n.t("instance.dialog.space_type.temp", "临时空间"),
                        I18n.t("instance.dialog.space_type.blob", "智能大对象空间")
                );
                spaceTypeChoiceBox.getSelectionModel().select(0);

                Label nameLabel=new Label(I18n.t("instance.dialog.space_name", "空间名称"));
                nameLabel.setGraphic(IconFactory.group(IconPaths.INSTANCE_SPACE_NAME_LABEL, 0.45));
                CustomUserTextField nameTextField = new CustomUserTextField();
                nameTextField.setMinWidth(240);
                nameTextField.setPromptText(I18n.t("instance.dialog.space_name.prompt", "字母和数字，不允许空格"));


                Label filePathLabel=new Label(I18n.t("instance.dialog.file_path", "文件路径"));
                filePathLabel.setGraphic(IconFactory.group(IconPaths.INSTANCE_SPACE_FILE_PATH_LABEL, 0.5));
                CustomUserTextField filePathTextField = new CustomUserTextField();
                for(CustomSpaceChart.SpaceUsage u:chunkChartList){
                    int lastSlashIndex = u.getName().lastIndexOf("/");
                    // 若截取后仍只有根目录（如 "/opt//" → 截取后 "/opt" → lastSlashIndex=4 → 返回 "/opt"）
                    datafilePath=u.getName().substring(0, lastSlashIndex + 1);
                    //break;
                }
                filePathTextField.setPromptText(I18n.t("instance.dialog.file_path.prompt", "根据空间名称自动填充"));

                nameTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                    nameTextField.setText(newValue.replace(" ", ""));
                    filePathTextField.setText(datafilePath+nameTextField.getText()+"chk001");

                });
                filePathTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                    filePathTextField.setText(newValue.replace(" ", ""));
                });



                Label pagesizeLabel=new Label(I18n.t("instance.dialog.page_size", "页大小"));
                pagesizeLabel.setGraphic(IconFactory.group(IconPaths.INSTANCE_SPACE_PAGESIZE_LABEL, 0.6, 0.5));
                ChoiceBox<String> pagesizeChoiceBox = new ChoiceBox<>();
                pagesizeChoiceBox.getItems().addAll("2k","4k","6k","8k","10k","12k","14k",I18n.t("instance.dialog.page_size.16k_recommended", "16k(推荐)"));
                pagesizeChoiceBox.getSelectionModel().select(7);


                Label sizeLabel=new Label(I18n.t("instance.dialog.size_kb", "大小(KB)"));
                sizeLabel.setGraphic(IconFactory.group(IconPaths.INSTANCE_SPACE_SIZE_LABEL, 0.45));
                CustomUserTextField sizeTextField = new CustomUserTextField();

                sizeTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                    if (!newValue.matches("\\d*")) {
                        sizeTextField.setText(newValue.replaceAll("[^\\d]", ""));
                    }
                });
                sizeTextField.setPromptText(I18n.t("instance.dialog.number.prompt", "数字"));


                if(isAddFile){
                    nameTextField.setText(spaceUsage.getName());
                    String chunkName=spaceUsage.getName()+"chk";

                    for (int i = 1; i <= 9999999; i++) {
                        Boolean isExists = false;
                        for(CustomSpaceChart.SpaceUsage u:chunkChartList){
                            int lastSlashIndex = u.getName().lastIndexOf("/");
                            if (u.getName().substring(lastSlashIndex + 1,u.getName().length()).equals(chunkName+String.format("%03d", i))) {
                                isExists = true;
                                break;
                            }
                        }
                        if (!isExists) {
                            chunkName +=String.format("%03d", i);
                            break;
                        }
                    }

                    int size=0;
                    for(CustomSpaceChart.SpaceUsage u:chunkChartList){
                        if(u.getLabel().trim().endsWith("[ "+spaceUsage.getName()+" ]")){
                            size=  u.getTotalPages();
                           // break;
                        }
                    }
                    sizeTextField.setText(String.valueOf(size*2));
                    filePathTextField.setText(datafilePath+chunkName);
                }

                if(isAddFile){
                    grid.add(nameLabel, 0, 0);
                    grid.add(nameTextField, 1, 0);
                    grid.add(filePathLabel, 0, 1);
                    grid.add(filePathTextField, 1, 1);
                    grid.add(sizeLabel, 0, 2);
                    grid.add(sizeTextField, 1, 2);
                }else {
                    grid.add(spaceTypeLabel, 0, 0);
                    grid.add(spaceTypeChoiceBox, 1, 0);
                    grid.add(nameLabel, 0, 1);
                    grid.add(nameTextField, 1, 1);
                    grid.add(filePathLabel, 0, 2);
                    grid.add(filePathTextField, 1, 2);
                    grid.add(pagesizeLabel, 0, 3);
                    grid.add(pagesizeChoiceBox, 1, 3);
                    grid.add(sizeLabel, 0, 4);
                    grid.add(sizeTextField, 1, 4);
                }
                StackPane stackPane = new StackPane(grid, backgroupHbox);
                AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                        isAddFile
                                ? I18n.t("instance.dialog.add_datafile.title", "增加数据文件")
                                : I18n.t("instance.dialog.create_dbspace.title", "创建数据库空间"),
                        stackPane,
                        520,
                        Region.USE_COMPUTED_SIZE,
                        commitButtonType,
                        cancelButtonType
                );
                Button commit = dialog.getButton(commitButtonType);
                Button cancelBtn = dialog.getButton(cancelButtonType);
                commit.disableProperty().bind(backgroupHbox.visibleProperty());

                commit.addEventFilter(ActionEvent.ACTION, event -> {
                    event.consume();
                    if (nameTextField.getText().trim().isEmpty()) {
                        nameTextField.requestFocus();
                    } else if (filePathTextField.getText().trim().isEmpty()) {
                        filePathTextField.requestFocus();
                    } else if (sizeTextField.getText().trim().isEmpty()) {
                        sizeTextField.requestFocus();
                    } else {
                        if(!isAddFile) {
                            for (CustomSpaceChart.SpaceUsage u : dbspaceChartList) {
                                if (u.getName().equals(nameTextField.getText())) {
                                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), String.format(I18n.t("instance.dialog.space_exists", "空间\"%s\"已存在，请使用其他空间名！"), nameTextField.getText()));
                                    return;
                                }
                            }
                        }
                        for(CustomSpaceChart.SpaceUsage u:chunkChartList){
                            if(u.getName().equals(filePathTextField.getText())){
                                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), String.format(I18n.t("instance.dialog.file_exists", "数据文件\"%s\"已存在，请使用其他数据文件路径！"), filePathTextField.getText()));
                                return;
                            }
                        }

                        String cmd="";
                        String pagesize="";
                        switch (pagesizeChoiceBox.getSelectionModel().getSelectedIndex()){
                            case 0:
                                pagesize="2";
                                break;
                            case 1:
                                pagesize="4";
                                break;
                            case 2:
                                pagesize="6";
                                break;
                            case 3:
                                pagesize="8";
                                break;
                            case 4:
                                pagesize="10";
                                break;
                            case 5:
                                pagesize="12";
                                break;
                            case 6:
                                pagesize="14";
                                break;
                            case 7:
                                pagesize="16";
                                break;
                            default:
                                break;
                        }
                        if(isAddFile){
                            cmd="onspaces -a  "+nameTextField.getText()+" -p "+filePathTextField.getText()+" -o 0 -s "+sizeTextField.getText();
                        }else{
                            switch (spaceTypeChoiceBox.getSelectionModel().getSelectedIndex()){
                                case 0:
                                    cmd="onspaces -c -d "+nameTextField.getText()+" -p "+filePathTextField.getText()+" -o 0 -s "+sizeTextField.getText()+" -k "+pagesize;
                                    break;
                                case 1:
                                    cmd="onspaces -c -d "+nameTextField.getText()+" -p "+filePathTextField.getText()+" -o 0 -s "+sizeTextField.getText() +" -k "+pagesize+" -t";
                                    break;
                                case 2:
                                    cmd="onspaces -c -S "+nameTextField.getText()+" -p "+filePathTextField.getText()+" -o 0 -s "+sizeTextField.getText()+" -Df \"LOGGING = ON, AVG_LO_SIZE=1\"";
                                default:
                                    break;
                            }
                        }

                        cmd="touch "+filePathTextField.getText()+"&&chown gbasedbt:gbasedbt "+filePathTextField.getText()+"&&chmod 660 "+filePathTextField.getText()+"&&"+cmd;
                        String finalCmd = cmd;

                        Task<Void> task = new Task<>() {
                            @Override
                            protected Void call() throws Exception {
                                try {
                                    Session session=JschUtil.getConnect(connect);
                                    String result = JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+ finalCmd);
                                    JschUtil.disConnect(session);
                                    if(!(result.contains("Space successfully added")||result.contains("Chunk successfully added"))){
                                        throw new Exception(result);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                return null;
                            }
                        };

                        Task<Void> processTask = new Task<>() {
                            @Override
                            protected Void call() throws Exception {
                                try {
                                    Session session=JschUtil.getConnect(connect);
                                    Double currentSize=0.0;
                                    while(currentSize<Double.parseDouble(sizeTextField.getText())){
                                        if(isCancelled())break;
                                        Thread.sleep(100);
                                        String result = JschUtil.executeCommand(session,"/usr/bin/du -s "+filePathTextField.getText()+" |awk '{print $1}'");
                                        try{
                                            currentSize=Double.parseDouble(result);
                                        }catch(Exception e){

                                        }
                                        Double finalResult = currentSize;
                                        Platform.runLater(()->{
                                            runningLabel.setText(I18n.t("instance.dialog.init.progress.prefix", " 正在初始化...")+String.format("%.2f",Math.min(1,finalResult/Double.parseDouble(sizeTextField.getText()))*100)+"%");
                                        });

                                    }

                                    JschUtil.disConnect(session);

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                return null;
                            }
                        };
                        processTask.setOnSucceeded(event1->{
                            runningLabel.setText(I18n.t("instance.dialog.preparing", " 正在准备，请稍等..."));
                        });
                        task.setOnSucceeded(event1 -> {
                            backgroupHbox.setVisible(false);
                            processTask.cancel();
                            cancelBtn.fire();
                            NotificationUtil.showMainNotification(I18n.t("instance.notice.space_create_success", "空间创建/扩容成功！"));
                            refreshButton.fire();
                        });
                        task.setOnFailed(event1 -> {
                            processTask.cancel();
                            backgroupHbox.setVisible(false);
                            String error = task.getException().getMessage();
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                        });
                        processStopButton.setOnAction(event1->{
                            runningLabel.setText(I18n.t("instance.dialog.init.progress", " 正在初始化...0.00%"));
                            processTask.cancel();
                            task.cancel();
                            backgroupHbox.setVisible(false);
                            Task<Void> stopTask = new Task<>() {
                                @Override
                                protected Void call() throws Exception {
                                    try {
                                        Session session=JschUtil.getConnect(connect);
                                        int result = JschUtil.executeCommandWithExitStatus(session,"ps -ef |grep onspaces|grep -v grep |awk '{print \"kill -9 \"$2}' |sh ");
                                        JschUtil.disConnect(session);
                                        if(result!=0){
                                            throw new Exception(I18n.t("instance.error.stop_create_space_failed", "停止创建空间失败！"));
                                        }
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                    return null;
                                }
                            };
                            AppExecutor.runTask(stopTask);
                        });
                        dialog.getStage().setOnCloseRequest(event1 -> processStopButton.fire());
                        cancelBtn.addEventFilter(ActionEvent.ACTION, event1 -> processStopButton.fire());
                        backgroupHbox.setVisible(true);
                        AppExecutor.runTask(task);
                        AppExecutor.runTask(processTask);
                    }
                });

                dialog.showAndWait();
            }

            // -------------------------- 其他接口方法实现 --------------------------
            @Override
            public void onDropDbspace(CustomSpaceChart.SpaceUsage spaceUsage) {
                // 「删除数据库空间」的业务逻辑（示例：弹出确认弹窗）
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.drop_space.title", "删除空间"), String.format(I18n.t("instance.confirm.drop_space.content", "确定要删除空间\"%s\"吗？"), spaceUsage.getName()))){
                    String cmd="onspaces -d "+spaceUsage.getName()+" -y ";

                    for(CustomSpaceChart.SpaceUsage u:chunkChartList){
                        if(u.getLabel().trim().endsWith("[ "+spaceUsage.getName()+" ]")){
                            cmd+="&& rm -rf "+u.getName();
                        }
                    }
                    String finalCmd = cmd;
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            try {
                                Session session=JschUtil.getConnect(connect);
                                String result = JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+ finalCmd);
                                JschUtil.disConnect(session);
                                if(!result.contains("Space successfully dropped")) {
                                        throw new Exception(result);
                                }
                            } catch (Exception e) {
                                //throw new Exception("ssh登录失败，请检查网络！");
                                throw new RuntimeException(e);
                            }
                            return null;
                        }
                    };
                    task.setOnSucceeded(event1 -> {
                        //cancelBtn.fire();
                        NotificationUtil.showMainNotification(String.format(I18n.t("instance.notice.space_deleted", "空间\"%s\"已删除！"), spaceUsage.getName()));
                        refreshButton.fire();
                    });
                    task.setOnFailed(event1 -> {
                        String error = task.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });
                    AppExecutor.runTask(task);
                }
                // 后续可添加：调用删除接口、刷新图表数据等逻辑
            }


            @Override
            public void onDropDatafile(CustomSpaceChart.SpaceUsage spaceUsage) {
                // 「删除数据库空间」的业务逻辑（示例：弹出确认弹窗）
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.drop_datafile.title", "删除数据文件"), String.format(I18n.t("instance.confirm.drop_datafile.content", "确定要删除数据文件\"%s\"吗？"), spaceUsage.getName()))){
                    String dbspace=spaceUsage.getLabel().trim().split(" ")[2].trim();
                    String cmd="onspaces -d "+dbspace+" -p "+spaceUsage.getName()+" -o 0 -y";
                    cmd+="&& rm -rf "+spaceUsage.getName();
                    String finalCmd = cmd;
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            try {
                                Session session=JschUtil.getConnect(connect);
                                String result = JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+ finalCmd);
                                JschUtil.disConnect(session);
                                if(!result.contains("Chunk successfully dropped")) {
                                    throw new Exception(result);
                                }
                            } catch (Exception e) {
                                //throw new Exception("ssh登录失败，请检查网络！");
                                throw new RuntimeException(e);
                            }
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
                // 后续可添加：调用删除接口、刷新图表数据等逻辑
            }


            @Override
            public void onExpandDatafile(CustomSpaceChart.SpaceUsage spaceUsage) {
                // 「删除数据库空间」的业务逻辑（示例：弹出确认弹窗）
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.expand_datafile.title", "数据文件自动扩展"), String.format(I18n.t("instance.confirm.expand_datafile.content", "确定要设置数据文件\"%s\"自动扩展吗？"), spaceUsage.getName()))){
                    int chunkId=spaceUsage.getNumber();
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            try {
                                adminService.modifyChunkExtendable(connect, chunkId, true);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                            return null;
                        }
                    };
                    task.setOnSucceeded(event1 -> {
                        //cancelBtn.fire();
                        NotificationUtil.showMainNotification(String.format(I18n.t("instance.notice.datafile_expanded", "数据文件\"%s\"已设置为自动扩展！"), spaceUsage.getName()));
                        refreshButton.fire();
                    });
                    task.setOnFailed(event1 -> {
                        String error = task.getException().getMessage();
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
                    });
                    AppExecutor.runTask(task);
                }
                // 后续可添加：调用删除接口、刷新图表数据等逻辑
            }


            @Override
            public void onUnExpandDatafile(CustomSpaceChart.SpaceUsage spaceUsage) {
                // 「删除数据库空间」的业务逻辑（示例：弹出确认弹窗）
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.unexpand_datafile.title", "数据文件关闭自动扩展"), String.format(I18n.t("instance.confirm.unexpand_datafile.content", "确定要关闭数据文件\"%s\"自动扩展吗？"), spaceUsage.getName()))){
                    int chunkId=spaceUsage.getNumber();
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            try {
                                adminService.modifyChunkExtendable(connect, chunkId, false);
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
                // 后续可添加：调用删除接口、刷新图表数据等逻辑
            }
            @Override
            public void onUnlimitedSpaceSize(CustomSpaceChart.SpaceUsage spaceUsage) {
                // 「删除数据库空间」的业务逻辑（示例：弹出确认弹窗）
                if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.unlimit_space.title", "解除大小限制"), String.format(I18n.t("instance.confirm.unlimit_space.content", "确定要解除数据库空间\"%s\"的大小限制吗？"), spaceUsage.getName()))){
                    Task<Void> task = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            try {
                                adminService.unLimitedSpaceSize(connect, spaceUsage.getName());
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
                // 后续可添加：调用删除接口、刷新图表数据等逻辑
            }





        };

        //启停tab初始化变量
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
                    try {
                        Session session=JschUtil.getConnect(connect);
                        int result=JschUtil.executeCommandWithExitStatus(session,JschUtil.extractEnvValue(connect.getInfo())+"oninit");
                        JschUtil.disConnect(session);
                        if(result!=0)throw new Exception(I18n.t("instance.error.start_failed", "启动数据库失败，请检查日志错误！"));
                    }catch (Exception e){
                        throw new Exception(I18n.t("instance.error.ssh_login", "ssh登录失败，请检查网络！"));
                    }
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
                        try {
                            Session session=JschUtil.getConnect(connect);
                            int result = JschUtil.executeCommandWithExitStatus(session,JschUtil.extractEnvValue(connect.getInfo())+"onmode -ky&&onclean -ky");
                            JschUtil.disConnect(session);
                            if (result != 0) throw new Exception(I18n.t("instance.error.stop_failed", "关闭数据库失败，请检查日志错误！"));
                        } catch (Exception e) {
                            throw new Exception(I18n.t("instance.error.ssh_login", "ssh登录失败，请检查网络！"));
                        }
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

        //主tabpane初始化
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
        refreshButton.getStyleClass().add("codearea-camera-button");
        refreshButton.setGraphic(IconFactory.group(IconPaths.METADATA_REFRESH_ITEM, 0.7));
        refreshButton.setFocusTraversable(false);
        refreshButton.setOnAction(e -> {
            refreshCurrentTab();
                });



        // 给每个 Tab 绑定「首次选中加载」逻辑
        bindLazyLoadToTab(infoTab, () -> loadInfoTabContent(infoTab), () -> infoTabLoaded, (loaded) -> infoTabLoaded = loaded);
       bindLazyLoadToTab(checkTab, () -> loadCheckTabContent(checkTab), () -> checkTabLoaded, (loaded) -> checkTabLoaded = loaded);
        bindLazyLoadToTab(logTab, () -> loadLogTabContent(logTab), () -> logTabLoaded, (loaded) -> logTabLoaded = loaded);
        bindLazyLoadToTab(spaceTab, () -> loadSpaceTabContent(spaceTab), () -> spaceTabLoaded, (loaded) -> spaceTabLoaded = loaded);
       bindLazyLoadToTab(paramsTab, () -> loadParamsTabContent(paramsTab), () -> paramsTabLoaded, (loaded) -> paramsTabLoaded = loaded);
        bindLazyLoadToTab(startTab, () -> loadStartTabContent(startTab), () -> startTabLoaded, (loaded) -> startTabLoaded = loaded);

        if(connect.getUsername().equals("gbasedbt")){
            mainTabPane.getTabs().addAll(infoTab,checkTab,logTab,spaceTab,paramsTab,startTab);
        }else{
            mainTabPane.getTabs().addAll(infoTab);
        }
        //实例信息子 tab 不需要右键菜单
        mainTabPane.setContextMenu(null);
        mainTabPane.setStyle("-fx-background-color: -color-bg-content;");
        StackPane  stackPane=new StackPane(mainTabPane,refreshButton,instanceInfoLabel);
        StackPane.setAlignment(refreshButton,Pos.BOTTOM_RIGHT);
        StackPane.setAlignment(instanceInfoLabel,Pos.BOTTOM_RIGHT);
        StackPane.setMargin(refreshButton, new Insets(0, 15, 20, 0));
        StackPane.setMargin(instanceInfoLabel, new Insets(0, 15, 3, 0));

        setContent(stackPane);
        mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabPane.setSide(Side.BOTTOM);

        //refreshButton.fire();
        mainTabPane.setId("instanceManagerTabPane");
        mainTabPane.getSelectionModel().select(tabNum);
        // 方式2.1：直接添加样式字符串

        //如果是只读，禁用一些东西
        if(connect.getReadonly()) {
            configTableView.setEditable(false);
            startButton.setDisable(true);
            stopButton.setDisable(true);
        }


    }



    private void refreshCurrentTab() {
        Tab currentTab = mainTabPane.getSelectionModel().getSelectedItem();
        if (currentTab == null) return;

        // 显示加载占位符
        Node currentContent = currentTab.getContent();
        if (currentContent != null && "loadingNode".equals(currentContent.getId())) {
            // 可选：提示用户正在加载中
            return;
        }
        currentTab.setContent(createLoadingNode());

        // 异步刷新（避免阻塞 UI）
        AppExecutor.runAsync(() -> {
            try {
                updateGroupInstanceInfo();
                // 根据当前 Tab 类型执行刷新
                if (currentTab == infoTab) {
                    loadInfoTabContent(infoTab);
                } else if (currentTab == checkTab) {
                    loadCheckTabContent(checkTab);
                } else if (currentTab == logTab) {
                    loadLogTabContent(logTab);
                } else if (currentTab == spaceTab) {
                   loadSpaceTabContent(spaceTab);
                } else if (currentTab == paramsTab) {
                    loadParamsTabContent(paramsTab);
                } else if (currentTab == startTab) {
                    loadStartTabContent(startTab);
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
        if(!connect.getPropByName("GBASEDBTSERVER").isEmpty()){
            try{
            String primaryInstance=connectionService.setConnectInfo(connect);
            String regex = "^" + primaryInstance + "\\s+.*\\s+g=" + connect.getPropByName("GBASEDBTSERVER") + "\\s*$";
            Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
            String sqlhostsContent = Files.readString(Paths.get("extlib/GBASE 8S/sqlhosts"));

            Matcher matcher = pattern.matcher(sqlhostsContent);
            
            // 处理匹配到的行
            while (matcher.find()) {
                String matchedLine = matcher.group();
                log.info("匹配到的sqlhosts行: {}", matchedLine);
                
                // 这里可以添加对匹配行的处理逻辑
                // 例如：解析行中的实例信息、更新UI等
                String[] parts = matchedLine.split("\\s+");
                if (parts.length >= 4) {
                    String instanceName = parts[0];
                    String protocol = parts[1];
                    String host = parts[2];
                    connect.setIp(host);
                    String port = parts[3];
                    connect.setPort(port);
                    connect.setInfo(connect.getInfo().replaceAll("(GBASEDBTSERVER\\s+)\\S+", "$1" + instanceName));
                    Platform.runLater(()->{
                        instanceNameText.set(instanceName);
                        instanceIpText.set(connect.getIp());
                        instancePortText.set(connect.getPort());
                    });

                    // 打印解析结果
                    log.info("实例名: {}, 协议: {}, 主机: {}, 端口: {}", 
                            instanceName, protocol, host, port);
                    
                    // 这里可以添加更新UI的逻辑
                    // 例如：将实例信息添加到表格中
                }
            }
        }catch(Exception e){
            log.error(e.getMessage(), e);
        
        }
    }

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
                AppExecutor.runAsync(() -> {
                    try {
                        updateGroupInstanceInfo();
                        setLoaded.accept(true); // 标记为已加载
                        loadTask.run(); // 执行加载逻辑
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                        // 加载失败：显示错误信息
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
     * 加载「实例信息」Tab 内容（示例，需根据实际业务补充）
     */
    private void loadInfoTabContent(CustomTab infoTab) {

        //信息框绑定
        Platform.runLater(() -> {
            String textArarText="";
            textArarText+=("##########################################################################################\n");
            textArarText+="Connection Information\n";
            textArarText+=("##########################################################################################\n");
            textArarText+=String.format("%-30s","Connection Name")+connect.getName()+"\n";
            textArarText+=String.format("%-30s","Database Type")+connect.getDbtype()+"\n";
            textArarText+=String.format("%-30s","JDBC Driver")+connect.getDriver()+"  (MD5:"+connect.getDrivermd5()+")\n";
            textArarText+=String.format("%-30s","IP Address")+connect.getIp()+"\n";
            textArarText+=String.format("%-30s","Port")+connect.getPort()+"\n";
            textArarText+=String.format("%-30s","Database User")+connect.getUsername()+"\n";
            String props="";
            JSONArray jsonArray=new JSONArray(connect.getProps());
            for(int i=0;i<jsonArray.length();i++){
                JSONObject jsonObject=jsonArray.getJSONObject(i);
                if(jsonObject.getString("propValue")!=null&&(!jsonObject.getString("propValue").equals(""))){
                    props+=jsonObject.getString("propName")+"="+jsonObject.getString("propValue")+";";
                }
            }
            textArarText+=String.format("%-30s","Driver Properties")+props+"\n";
            textArarText+=String.format("%-30s","Database Version")+connect.getDbversion()+"\n";
            textArarText+=(connect.getInfo()+"\n");

            //实例信息界面
            CustomInfoStackPane instance_info_codearea = new CustomInfoStackPane(new CustomInfoCodeArea());

            //实例信息界面结束
            instance_info_codearea.codeArea.replaceText(textArarText); //如果不用settext，而是appendtext追加，会导致setScrollTop无效
            infoTab.setContent(instance_info_codearea);

            instance_info_codearea.codeArea.showParagraphAtTop(0); //此方法有效
            instance_info_codearea.codeArea.setStyleSpans(0, KeywordsHighlightUtil.applyHighlightingInfo(instance_info_codearea.codeArea.getText()));
        });

    }

    private void loadCheckTabContent(CustomTab checkTab)  {
        log.info("loadCheckTabContent in,connect.getip is:"+connect.getIp());

        checkDatalist.clear();
        //initDatalist(checkDatalist);
        String instanceStatus="";
        String onstat_g_osi="";
        String onstat_g_osi_machine="";
        String onstat_g_osi_cpu="";
        String onstat_g_osi_mem="";
        String onstat_V="";
        String onstat_="";
        String onstat_g_seg_greped="";
        String onstat_g_seg="";
        String onstat_g_cluster="";
        String onstat_l="";
        String onstat_l_llog="";
        String onstat_d_greped="";
        String onstat_d="";
        double spaceTopPercent=0;
        String onstat_g_arc_greped="onstat_g_arc_greped";
        String onstat_g_arc="";
        String onstat_m="";
        String onstat_g_sql_greped="";
        String onstat_g_sql="";
        String onstat_g_act_greped="";
        String onstat_g_act="";
        String onstat_g_rea_greped="";
        String onstat_g_rea="";
        String onstat_g_wai="";
        String onstat_g_wai_logio="";
        String onstat_g_wai_lockwait="";
        String onstat_g_wai_bufwait="";
        String onstat_g_wai_iowait="";
        String onstat_x_greped="";
        String onstat_x="";
        String onstat_p="";
        String onstat_p_greped="";
        String onstat_p_deadlks="";
        try {
            Session session= JschUtil.getConnect(connect);
            onstat_g_osi=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g osi");
            onstat_g_osi_machine=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g osi |awk '/OS Machine/ {print $3}'");
            onstat_g_osi_cpu=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g osi |awk '/Number of online processors/{print $5}'");
            onstat_g_osi_mem=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g osi |grep -v 'System memory page size' |awk '/System memory/{print $3$4}'");
            onstat_V=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -V");
            onstat_=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -",true);
            onstat_g_seg_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g seg|grep -c ' V '");
            onstat_g_seg=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g seg");
            onstat_g_cluster=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g cluster");
            onstat_l=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -l");
            onstat_l_llog=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -l |grep -c 'U------'");
            onstat_d_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -d|grep -c PD");
            onstat_d=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -d");
            spaceTopPercent = adminService.getMaxDbspaceUsed(connect);
            onstat_g_arc_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g arc |grep -A 1 ' level ' |sed -n '2p' |awk '{print $4}'");
            onstat_g_arc=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g arc");
            onstat_m=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -c |awk '/^MSGPATH/ {print \"tail -1000 \"$2}' |sh|egrep -ic 'err|failed|warning|allocated|full|long|down|Died|Aborting|Abort'");
            onstat_g_sql_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g sql |egrep -v 'On-Line|Read-Only|Current|Database|^$' |wc -l");
            onstat_g_sql=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g sql");
            onstat_g_act_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g act |grep -v soctcppoll |grep -v '^$'|wc -l");
            onstat_g_act=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g act");
            onstat_g_rea_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g rea|grep -v '^$'| wc -l");
            onstat_g_rea=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g rea");
            onstat_g_wai=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g wai");
            onstat_g_wai_logio=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g wai|grep -c 'logio cond'");
            onstat_g_wai_lockwait=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g wai|grep -c 'yield lockwait'");
            onstat_g_wai_bufwait=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g wai|grep -c 'yield bufwait'");
            onstat_g_wai_iowait=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -g wai|grep -c 'IO Wait'");
            onstat_x_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -x |grep -v '^$' |grep -v ' - ' |wc -l");
            onstat_x=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -x");
            onstat_p=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -p");
            onstat_p_greped=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -p |grep -A 1 rewrite |sed -n '2p' |awk '{print $4\" \"$5\" \"$6\" \"$7\" \"$8\" \"$9}'");
            onstat_p_deadlks=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -p |grep -A 1 deadlks |sed -n '2p' |awk '{print $4}'");
            JschUtil.disConnect(session);
        }catch (SQLException e){
            log.error(e.getMessage(), e);
        }
        catch (Exception e){
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        String currentValue;
        String status ;
        currentValue="实例状态异常";
        status="2";
        if(!onstat_g_osi_machine.isEmpty()){
            currentValue=onstat_g_osi_machine;
            status="0";
        }
        addHealthCheck(checkDatalist, "系统架构","onstat -g osi", "x86_64/aarch64",currentValue,status,onstat_g_osi);

        currentValue="实例状态异常";
        status="2";
        if(!onstat_g_osi_cpu.isEmpty()){
            currentValue=onstat_g_osi_cpu;
            status="0";
        }
        addHealthCheck(checkDatalist, "CPU数量","onstat -g osi", "1核心以上",currentValue,status,onstat_g_osi);

        currentValue="实例状态异常";
        status="2";
        if(!onstat_g_osi_mem.isEmpty()){
            currentValue=onstat_g_osi_mem;
            status="0";
        }
        addHealthCheck(checkDatalist, "内存大小","onstat -g osi", "2GB以上",currentValue,status,onstat_g_osi);

        currentValue="实例状态异常";
        status="2";
        if(!onstat_V.isEmpty()){
            currentValue=onstat_V;
            status="0";
        }
        addHealthCheck(checkDatalist, "数据库版本","onstat -V", "GBase8sV8.x",currentValue,status,onstat_V);

        currentValue="实例状态异常";
        status="2";
        if(!onstat_.isEmpty()) {
            if (onstat_.contains("Your evaluation license")) {
                currentValue = onstat_.split("Your evaluation license will expire on ")[1];
                status = "1";
            } else {
                currentValue = "Permanent";
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "软件授权有效期","onstat -", "永久",currentValue,status,onstat_);

        String strictRegex = "((?:On-Line|Read-Only)(?:\\s+\\(.*\\))?)\\s+--"; // 转义\为\\（Java字符串语法）
        Pattern strictPattern = Pattern.compile(strictRegex);
        Matcher strictMatcher = strictPattern.matcher(onstat_);
        currentValue="实例状态异常";
        status="2";
        if(!onstat_.isEmpty()) {
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
                status = "0";
            } else {
                currentValue = "Off-Line";
                instanceStatus="Off-Line";
                status = "2";
            }
        }
        addHealthCheck(checkDatalist, "实例状态", "onstat -", "主节点或单机On-Line，集群备机Read-Only", currentValue, status,onstat_);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_)) {
            strictRegex = "(Blocked:.*)"; // 转义\为\\（Java字符串语法）
            strictPattern = Pattern.compile(strictRegex);
            strictMatcher = strictPattern.matcher(onstat_);
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
                status = "2";
            } else {
                currentValue = "Not Blocked";
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例是否BLOCKED", "onstat -", "正常无Blocked:显示", currentValue, status,onstat_);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_)) {
            strictRegex = "--\\s+Up\\s+(.*)\\s+--"; // 转义\为\\（Java字符串语法）
            strictPattern = Pattern.compile(strictRegex);
            strictMatcher = strictPattern.matcher(onstat_);
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例运行天数", "onstat -", "xxx Days", currentValue, status,onstat_);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_)) {
            strictRegex = "--\\s+([0-9]*\\s+Kbytes)"; // 转义\为\\（Java字符串语法）
            strictPattern = Pattern.compile(strictRegex);
            strictMatcher = strictPattern.matcher(onstat_);
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例内存总量", "onstat -", "xxx Kbytes", currentValue, status,onstat_);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_seg_greped)) {
            currentValue = onstat_g_seg_greped;
            int segments = parseIntOrDefault(currentValue, 0);
            if (segments > 3) {
                status = "2";
            }else{
                status="0";
            }
        }
        addHealthCheck(checkDatalist, "实例内存段数量", "onstat -g seg", "V段不超过3个", currentValue, status,onstat_g_seg);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_cluster)) {
            currentValue = onstat_g_cluster;
            if (currentValue.contains("Disconnected")) {
                currentValue = "Disconnected";
                status = "2";
            } else if (currentValue.contains("Connected")) {
                currentValue = "Connected";
                status = "0";
            } else {
                currentValue = "Not Clustered";
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例集群状态", "onstat -g cluster", "无集群或Connected", currentValue, status,onstat_g_cluster);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_l)) {
            String plogsize = onstat_l.split("Physical Logging")[1].split("Logical Logging")[0];
            strictRegex = "^\\s*\\d+:\\d+\\s+(\\d+)"; // 转义\为\\（Java字符串语法）
            strictPattern = Pattern.compile(strictRegex);
            strictMatcher = strictPattern.matcher(plogsize.split("\n")[4]);
            if (strictMatcher.find()) {
                // 拼接匹配结果，统一格式为 On-Line
                int psize = parseIntOrDefault(strictMatcher.group(1), 0) * 2;
                currentValue = psize + "k";
                status = "0";
                if (psize < 512000 * 2) {
                    status = "1";
                }
            }
        }
        addHealthCheck(checkDatalist, "实例物理日志", "onstat -l", "physize不小于1G", currentValue, status,onstat_l);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_l_llog)) {
            currentValue = onstat_l_llog;
            if (parseIntOrDefault(onstat_l_llog, 0) > 0) {
                status = "1";
            } else {
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例逻辑日志", "onstat -l", "U------状态日志为0", currentValue, status,onstat_l);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_d_greped)) {
            currentValue = onstat_d_greped;
            if (parseIntOrDefault(currentValue, 0) > 0) {
                status = "2";
            } else {
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例空间状态", "onstat -d", "无PD状态", currentValue, status,onstat_d);

        currentValue="实例状态异常";
        status="2";
        if(spaceTopPercent!=0) {
            currentValue = String.valueOf(spaceTopPercent) + "%";
            status = "0";
            if (spaceTopPercent > 80) {
                status = "1";
            }
            if (spaceTopPercent > 90) {
                status = "2";
            }
        }
        addHealthCheck(checkDatalist, "实例空间使用率", "onstat -d", "使用率小于80%", currentValue, status,onstat_d);

        currentValue="实例状态异常";
        status="2";
        if(!onstat_g_arc_greped.equals("onstat_g_arc_greped") && instanceStatus != null && !instanceStatus.contains("Off-Line")) {
            currentValue = onstat_g_arc_greped;
            SimpleDateFormat SDF = new SimpleDateFormat("MM/dd/yyyy.HH:mm");
            boolean isOver1Day = false;
            if (!currentValue.equals("")) {
                try {
                    Date date = SDF.parse(currentValue);
                    long oneDayMs = 24 * 60 * 60 * 1000L; // 1天的毫秒数
                    long timeDiffMs = new Date().getTime() - date.getTime();
                    isOver1Day = timeDiffMs > oneDayMs;
                } catch (ParseException e1) {
                    throw new RuntimeException(e1);
                }
            }
            status = "0";

            if (isOver1Day || currentValue.equals("")) {
                status = "1";
            }
            if (currentValue.equals("")) {
                currentValue = "Never Archived";
            }
        }
        addHealthCheck(checkDatalist, "实例空间备份", "onstat -g arc", "最后一次备份时间在24小时内", currentValue, status,onstat_g_arc);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_m)) {
            currentValue = onstat_m;
            if (parseIntOrDefault(onstat_m, 0) > 0) {
                status = "1";
            } else {
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例运行日志", "onstat -m", "err、failed关键字数量为0", currentValue, status,onstat_m);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_sql_greped)) {
            currentValue = onstat_g_sql_greped;
            int totalConnectionCount = parseIntOrDefault(currentValue, 0);
            if (totalConnectionCount >= 10000) {
                status = "1";
            }else{
                status="0";
            }
            currentValue=String.valueOf(totalConnectionCount);
        }
        addHealthCheck(checkDatalist, "实例总连接数", "onstat -g sql", "<10000", currentValue, status,onstat_g_sql);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_act_greped)) {
            currentValue = onstat_g_act_greped;
            int activeConnectionCount = parseIntOrDefault(currentValue, 0);
            if (activeConnectionCount >= 1003) {
                status = "1";
            } else {
                status = "0";
            }
            currentValue=String.valueOf(activeConnectionCount - 3);
        }
        addHealthCheck(checkDatalist, "实例活动连接数", "onstat -g act", "<1000", currentValue, status,onstat_g_act);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_rea_greped)) {
            currentValue = onstat_g_rea_greped;
            int queueCount = parseIntOrDefault(currentValue, 0);
            if (queueCount > 3) {
                status = "1";
            } else {
                status = "0";
            }
            currentValue=String.valueOf(queueCount - 3);
        }
        addHealthCheck(checkDatalist, "实例队列数量", "onstat -g rea", "0或少量", currentValue, status,onstat_g_rea);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_wai_logio)) {
            currentValue = onstat_g_wai_logio;
            if (parseIntOrDefault(currentValue, 0) > 0) {
                status = "1";
            } else {
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例逻辑日志等待logio cond", "onstat -g wai", "0或少量", currentValue, status,onstat_g_wai);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_wai_lockwait)) {
            currentValue = onstat_g_wai_lockwait;
            if (parseIntOrDefault(currentValue, 0) > 0) {
                status = "1";
            } else {
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例锁等待yield lockwait", "onstat -g wai", "0或少量", currentValue, status,onstat_g_wai);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_wai_bufwait)) {
            currentValue = onstat_g_wai_bufwait;
            if (parseIntOrDefault(currentValue, 0) > 0) {
                status = "1";
            } else {
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例buf等待yield bufwait", "onstat -g wai", "0或少量", currentValue, status,onstat_g_wai);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_g_wai_iowait)) {
            currentValue = onstat_g_wai_iowait;
            if (parseIntOrDefault(currentValue, 0) > 0) {
                status = "1";
            } else {
                status = "0";
            }
        }
        addHealthCheck(checkDatalist, "实例IO等待IO Wait", "onstat -g wai", "0或少量", currentValue, status,onstat_g_wai);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_x_greped)) {
            currentValue = String.valueOf(parseIntOrDefault(onstat_x_greped, 0) - 5);
            status = "0";
        }
        addHealthCheck(checkDatalist, "实例打开未提交事务数", "onstat -x", "少量", currentValue, status,onstat_x);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_p_greped)) {
            currentValue = tokenAt(onstat_p_greped, 4, currentValue);
            status="0";
        }
        addHealthCheck(checkDatalist, "实例已提交事务数", "onstat -p", "业务繁忙度决定", currentValue, status,onstat_p);
        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_p_greped)) {
            currentValue = tokenAt(onstat_p_greped, 5, currentValue);
            status="0";
        }
        addHealthCheck(checkDatalist, "实例回滚事务数", "onstat -p", "少量", currentValue, status,onstat_p);
        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_p_greped)) {
            currentValue = tokenAt(onstat_p_greped, 1, currentValue);
            status="0";
        }
        addHealthCheck(checkDatalist, "实例插入数量", "onstat -p", "业务繁忙度决定", currentValue, status,onstat_p);
        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_p_greped)) {
            currentValue = tokenAt(onstat_p_greped, 2, currentValue);
            status="0";
        }
        addHealthCheck(checkDatalist, "实例更新数量", "onstat -p", "业务繁忙度决定", currentValue, status,onstat_p);
        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_p_greped)) {
            currentValue = tokenAt(onstat_p_greped, 3, currentValue);
            status="0";
        }
        addHealthCheck(checkDatalist, "实例删除数量", "onstat -p", "业务繁忙度决定", currentValue, status,onstat_p);

        currentValue="实例状态异常";
        status="2";
        if (canEvaluateCheck(onstat_p_deadlks)) {
            currentValue = onstat_p_deadlks;
            status="0";
        }
        addHealthCheck(checkDatalist, "实例死锁数量", "onstat -p", "业务逻辑决定", currentValue, status,onstat_p);

        Platform.runLater(() -> {
            checkTableView.getItems().clear();
            checkTableView.getItems().setAll(checkDatalist);
            checkTableView.refresh();
            checkTab.setContent(checkStackPane);
        });


    }

    private void    loadParamsTabContent(CustomTab checkTab)  {

        configTableView.getItems().clear();
        String config="";

        try {
            Session session= JschUtil.getConnect(connect);
            config=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -c |grep -v '^$' |grep -v '^#' |sed '1,2d'");
            JschUtil.disConnect(session);
        }catch (Exception e){
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        String finalConfig = config;
        Platform.runLater(() -> {
            configDatalist.clear();
            configDatalist=parseConfigData(finalConfig);
            configTableView.getItems().setAll(configDatalist);
            configTableView.refresh();
            configTableView.setVisible(true);
            paramsTab.setContent(configStackPane);
        });


    }

    private ObservableList<ObservableList<String>> parseConfigData(String rawData) {
        ObservableList<ObservableList<String>> configItems = FXCollections.observableArrayList();

        // 按行分割数据（处理换行符兼容：\n、\r\n）
        String[] lines = rawData.replaceAll("\r\n", "\n").split("\n");

        for (String line : lines) {
            // 去除行首尾空白
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue; // 跳过空行
            }

            // 分割参数名和参数值（按第一个空格/制表符分割，兼容多空格/制表符）
            String[] parts = trimmedLine.split("\\s+", 2); // 分割为2部分，保留值中的空格
            String paramName = parts[0];
            String paramValue = parts.length > 1 ? parts[1] : ""; // 无值则为空字符串

            // 添加到列表
            configItems.add(FXCollections.observableArrayList(null, paramName,paramValue) );
        }

        return configItems;
    }

    private void loadLogTabContent(CustomTab logTab){

        try {
            Session session= JschUtil.getConnect(connect);
            onlinelog=JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -c |awk '/^MSGPATH/ {print \"tail -1000 \"$2}' |sh");
            JschUtil.disConnect(session);
        }catch (Exception e){
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        Platform.runLater(()->{
            CustomInfoCodeArea logCodeArea=new CustomInfoCodeArea();
            CustomInfoStackPane customInfoStackPane=new CustomInfoStackPane(logCodeArea);
            StackPane stackPane=new StackPane();
            stackPane.getChildren().add(customInfoStackPane);
            logCodeArea.replaceText(onlinelog);
            logCodeArea.setStyleSpans(0,KeywordsHighlightUtil.applyHighlightingOnlinelog(logCodeArea.getText()));
            logCodeArea.showParagraphAtBottom(logCodeArea.getParagraphs().size() - 1);
            logTab.setContent(stackPane);
        });

    }


    private void loadSpaceTabContent(CustomTab spaceTab) {
        try {
            dataList = adminService.getInstanceDbspaceInfo(connect);
        }
        catch (Exception e){
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }


        Platform.runLater(()->{

            dbspaceChartList=dataList.get(0);
            chunkChartList=dataList.get(1);
            databaseChartList=dataList.get(2);
            tabChartList=dataList.get(3);
            dbspaceChart.render(dbspaceChartList);
            chunkChart.render(chunkChartList);
            databaseChart.render(databaseChartList);
            tabChart.render(tabChartList);
            dbspaceChart.setContextMenuListener(menuListener);
            chunkChart.setContextMenuListener(menuListener);


            if(connect.getReadonly()){
                dbspaceChart.setMenuItemsDisabled(true);
                chunkChart.setMenuItemsDisabled(true);
            }



                        /*
            ImageView loading_icon = IconFactory.loadingImageView(0.7);
            StackPane stackPane=new StackPane(loading_icon);

             */

            // 5. 填充数据+添加百分比

            spaceTab.setContent(dbspaceStackPane);
        });
    }

    private void loadStartTabContent(CustomTab startTab) {


        try {
            Session session=JschUtil.getConnect(connect);
            instanceStatus = JschUtil.executeCommand(session,JschUtil.extractEnvValue(connect.getInfo())+"onstat -");
            JschUtil.disConnect(session);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Platform.runLater(() -> {
            if (instanceStatus.contains("On-Line")||instanceStatus.contains("Read-Only")) {
                stopButton.setVisible(true);
                startButton.setVisible(false);
                instanceStatusTextCode.set("online");
            } else  {
                startButton.setVisible(true);
                stopButton.setVisible(false);
                instanceStatusTextCode.set("offline");

            }
            startTab.setContent(startStackPane);
        });
    }
    public void initDatalist(List<HealthCheck> datalist){
        datalist.clear();

    }

    private void addHealthCheck(List<HealthCheck> datalist, String entry, String cmd, String expectedValue, String currentValue, String status, String cmdOutput) {
        HealthCheck healthCheck = new HealthCheck(
                entry,
                cmd,
                expectedValue,
                currentValue,
                status,
                cmdOutput
        );
        healthCheck.entryProperty().bind(Bindings.createStringBinding(
                () -> i18nCheckEntry(entry),
                I18n.localeProperty()
        ));
        healthCheck.healthValueProperty().bind(Bindings.createStringBinding(
                () -> i18nCheckExpected(expectedValue),
                I18n.localeProperty()
        ));
        datalist.add(healthCheck);
    }

    private boolean canEvaluateCheck(String value) {
        return value != null && !value.isEmpty() && instanceStatus != null && !instanceStatus.contains("Off-Line");
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String tokenAt(String value, int index, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String[] tokens = value.trim().split("\\s+");
        if (index < 0 || index >= tokens.length) {
            return defaultValue;
        }
        return tokens[index];
    }

    private String i18nCheckEntry(String text) {
        return switch (text) {
            case "系统架构" -> I18n.t("instance.check.item.system_arch", text);
            case "CPU数量" -> I18n.t("instance.check.item.cpu_count", text);
            case "内存大小" -> I18n.t("instance.check.item.memory_size", text);
            case "数据库版本" -> I18n.t("instance.check.item.db_version", text);
            case "软件授权有效期" -> I18n.t("instance.check.item.license_expiry", text);
            case "实例状态" -> I18n.t("instance.check.item.instance_status", text);
            case "实例是否BLOCKED" -> I18n.t("instance.check.item.instance_blocked", text);
            case "实例运行天数" -> I18n.t("instance.check.item.instance_uptime_days", text);
            case "实例内存总量" -> I18n.t("instance.check.item.instance_memory_total", text);
            case "实例内存段数量" -> I18n.t("instance.check.item.instance_memory_segments", text);
            case "实例集群状态" -> I18n.t("instance.check.item.instance_cluster_status", text);
            case "实例物理日志" -> I18n.t("instance.check.item.instance_physical_log", text);
            case "实例逻辑日志" -> I18n.t("instance.check.item.instance_logical_log", text);
            case "实例空间状态" -> I18n.t("instance.check.item.instance_space_status", text);
            case "实例空间使用率" -> I18n.t("instance.check.item.instance_space_usage", text);
            case "实例空间备份" -> I18n.t("instance.check.item.instance_space_backup", text);
            case "实例运行日志" -> I18n.t("instance.check.item.instance_runtime_log", text);
            case "实例总连接数" -> I18n.t("instance.check.item.instance_total_connections", text);
            case "实例活动连接数" -> I18n.t("instance.check.item.instance_active_connections", text);
            case "实例队列数量" -> I18n.t("instance.check.item.instance_queue_count", text);
            case "实例逻辑日志等待logio cond" -> I18n.t("instance.check.item.instance_wait_logio", text);
            case "实例锁等待yield lockwait" -> I18n.t("instance.check.item.instance_wait_lock", text);
            case "实例buf等待yield bufwait" -> I18n.t("instance.check.item.instance_wait_buf", text);
            case "实例IO等待IO Wait" -> I18n.t("instance.check.item.instance_wait_io", text);
            case "实例打开未提交事务数" -> I18n.t("instance.check.item.instance_open_txn", text);
            case "实例已提交事务数" -> I18n.t("instance.check.item.instance_committed_txn", text);
            case "实例回滚事务数" -> I18n.t("instance.check.item.instance_rollback_txn", text);
            case "实例插入数量" -> I18n.t("instance.check.item.instance_insert_count", text);
            case "实例更新数量" -> I18n.t("instance.check.item.instance_update_count", text);
            case "实例删除数量" -> I18n.t("instance.check.item.instance_delete_count", text);
            case "实例死锁数量" -> I18n.t("instance.check.item.instance_deadlock_count", text);
            default -> text;
        };
    }

    private String i18nCheckExpected(String text) {
        return switch (text) {
            case "1核心以上" -> I18n.t("instance.check.expected.cpu_above_1", text);
            case "2GB以上" -> I18n.t("instance.check.expected.memory_above_2g", text);
            case "永久" -> I18n.t("instance.check.expected.license_permanent", text);
            case "主节点或单机On-Line，集群备机Read-Only" -> I18n.t("instance.check.expected.instance_online_or_readonly", text);
            case "正常无Blocked:显示" -> I18n.t("instance.check.expected.no_blocked", text);
            case "V段不超过3个" -> I18n.t("instance.check.expected.memory_segments_le_3", text);
            case "无集群或Connected" -> I18n.t("instance.check.expected.cluster_connected", text);
            case "physize不小于1G" -> I18n.t("instance.check.expected.physize_ge_1g", text);
            case "U------状态日志为0" -> I18n.t("instance.check.expected.logical_log_zero", text);
            case "无PD状态" -> I18n.t("instance.check.expected.no_pd", text);
            case "使用率小于80%" -> I18n.t("instance.check.expected.usage_lt_80", text);
            case "最后一次备份时间在24小时内" -> I18n.t("instance.check.expected.backup_within_24h", text);
            case "err、failed关键字数量为0" -> I18n.t("instance.check.expected.log_error_zero", text);
            case "0或少量" -> I18n.t("instance.check.expected.zero_or_few", text);
            case "少量" -> I18n.t("instance.check.expected.few", text);
            case "业务繁忙度决定" -> I18n.t("instance.check.expected.depends_on_business_load", text);
            case "业务逻辑决定" -> I18n.t("instance.check.expected.depends_on_business_logic", text);
            default -> text;
        };
    }

    private Node createLoadingNode() {
        ImageView loadingIcon = IconFactory.loadingImageView(0.7);
        StackPane loadingPane = new StackPane(loadingIcon);
        loadingPane.setId("loadingNode");
        return loadingPane;

    }

    private  Node createErrorNode(String mesg){
        ImageView errorIcon = IconFactory.imageView(IconPaths.DIALOG_ERROR, 16, 16, true);
        HBox hBox=new HBox(5,errorIcon,new Label(mesg));
        hBox.setAlignment(Pos.CENTER);
        StackPane errorPane = new StackPane(hBox);
        return errorPane;
    }
}
