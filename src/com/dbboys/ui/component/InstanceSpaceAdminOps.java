package com.dbboys.ui.component;

import com.dbboys.dialect.common.InstanceMutationUtil;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.InstanceManagerCapability;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.ssh.SshUtil;
import com.dbboys.service.AdminService;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.model.SpaceUsage;
import com.dbboys.model.Connect;
import org.apache.sshd.client.session.ClientSession;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.WindowEvent;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实例空间管理操作集：创建/删除库空间、数据文件扩容与自动扩展、Oracle 表空间与数据文件管理。
 * 从 {@link CustomInstanceTab} 抽取，行为保持不变；由 tab 传入连接/服务句柄与刷新回调。
 */
public class InstanceSpaceAdminOps {
    /** 匹配数据文件名：前缀 + 两位数字 + .dbf（用于递增 test02、test03）。 */
    private static final Pattern ORACLE_DATAFILE_BASENAME_SUFFIX = Pattern.compile(
            "(?i)^(.+?)(\\d{2})\\.dbf$");

    private final Connect connect;
    private final AdminService adminService;
    private final DatabasePlatformResolver platformResolver;
    private final Supplier<List<SpaceUsage>> dbspaceChartListSupplier;
    private final Supplier<List<SpaceUsage>> chunkChartListSupplier;
    private final Runnable refreshCallback;
    String datafilePath="";

    public InstanceSpaceAdminOps(Connect connect,
                                 AdminService adminService,
                                 DatabasePlatformResolver platformResolver,
                                 Supplier<List<SpaceUsage>> dbspaceChartListSupplier,
                                 Supplier<List<SpaceUsage>> chunkChartListSupplier,
                                 Runnable refreshCallback) {
        this.connect = connect;
        this.adminService = adminService;
        this.platformResolver = platformResolver;
        this.dbspaceChartListSupplier = dbspaceChartListSupplier;
        this.chunkChartListSupplier = chunkChartListSupplier;
        this.refreshCallback = refreshCallback;
    }

    private java.util.Optional<InstanceManagerCapability> resolveInstanceManagerCapability() {
        return platformResolver.capability(connect, InstanceManagerCapability.class);
    }

    private java.util.Optional<InstanceTabCapability> resolveInstanceTabCapability() {
        return platformResolver.capability(connect, InstanceTabCapability.class);
    }

    public void onCreateDbspace(SpaceUsage spaceUsage,boolean isAddFile) {
        List<SpaceUsage> dbspaceChartList = dbspaceChartListSupplier.get();
        List<SpaceUsage> chunkChartList = chunkChartListSupplier.get();
        //鍔犺浇鎸囩ず鍣?
        ImageView imageView = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);
        Button processStopButton = new Button("");
        processStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        Label runningLabel=new Label(I18n.t("instance.dialog.init.progress", " 正在初始化...0.00%"));
        HBox imageHBox = new HBox(imageView, runningLabel, processStopButton);
        imageHBox.getStyleClass().add("modal-progress-card");
        imageHBox.setAlignment(Pos.CENTER);
        imageHBox.setMaxHeight(15);
        //imageHBox.setMaxWidth(100);
        processStopButton.setFocusTraversable(false);
        processStopButton.getStyleClass().add("small");
        HBox backgroupHbox=new HBox(imageHBox);
        backgroupHbox.setAlignment(Pos.CENTER);
        backgroupHbox.getStyleClass().add("modal-progress-overlay");
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
        for(SpaceUsage u:chunkChartList){
            int lastSlashIndex = u.getName().lastIndexOf("/");
            // 鑻ユ埅鍙栧悗浠嶅彧鏈夋牴鐩綍锛堝 "/opt//" 鈫?鎴彇鍚?"/opt" 鈫?lastSlashIndex=4 鈫?杩斿洖 "/opt"锛?
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
                for(SpaceUsage u:chunkChartList){
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
            for(SpaceUsage u:chunkChartList){
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
                    for (SpaceUsage u : dbspaceChartList) {
                        if (u.getName().equals(nameTextField.getText())) {
                            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), String.format(I18n.t("instance.dialog.space_exists", "空间\"%s\"已存在，请使用其他空间名！"), nameTextField.getText()));
                            return;
                        }
                    }
                }
                for(SpaceUsage u:chunkChartList){
                    if(u.getName().equals(filePathTextField.getText())){
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), String.format(I18n.t("instance.dialog.file_exists", "数据文件\"%s\"已存在，请使用其他数据文件路径！"), filePathTextField.getText()));
                        return;
                    }
                }

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
                InstanceTabCapability.SpaceType spaceTypeValue = switch (spaceTypeChoiceBox.getSelectionModel().getSelectedIndex()) {
                    case 1 -> InstanceTabCapability.SpaceType.TEMP;
                    case 2 -> InstanceTabCapability.SpaceType.BLOB;
                    default -> InstanceTabCapability.SpaceType.STANDARD;
                };
                InstanceTabCapability.SpaceMutationRequest request = new InstanceTabCapability.SpaceMutationRequest(
                        isAddFile,
                        nameTextField.getText(),
                        filePathTextField.getText(),
                        sizeTextField.getText(),
                        pagesize,
                        spaceTypeValue,
                        resolveAdminOsUser()
                );

                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        InstanceTabCapability capability = resolveInstanceTabCapability()
                                .orElseThrow(() -> new UnsupportedOperationException("Space mutation is not supported"));
                        capability.createOrAddSpace(connect, request);
                        return null;
                    }
                };

                Task<Void> processTask = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        try {
                            ClientSession session=SshUtil.getConnect(connect);
                            Double currentSize=0.0;
                            while(currentSize<Double.parseDouble(sizeTextField.getText())){
                                if(isCancelled())break;
                                Thread.sleep(100);
                                String result = SshUtil.executeCommand(session,"/usr/bin/du -s "+filePathTextField.getText()+" |awk '{print $1}'");
                                try{
                                    currentSize=Double.parseDouble(result);
                                }catch(Exception e){

                                }
                                Double finalResult = currentSize;
                                Platform.runLater(()->{
                                    runningLabel.setText(I18n.t("instance.dialog.init.progress.prefix", " 姝ｅ湪鍒濆鍖?..")+String.format("%.2f",Math.min(1,finalResult/Double.parseDouble(sizeTextField.getText()))*100)+"%");
                                });

                            }

                            SshUtil.disConnect(session);

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
                    refreshCallback.run();
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
                            InstanceTabCapability capability = resolveInstanceTabCapability()
                                    .orElseThrow(() -> new UnsupportedOperationException("Space mutation abort is not supported"));
                            capability.abortCreateOrAddSpace(connect);
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

    public void onDropDbspace(SpaceUsage spaceUsage) {
        List<SpaceUsage> chunkChartList = chunkChartListSupplier.get();
        // 删除数据库空间
        if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.drop_space.title", "删除空间"), String.format(I18n.t("instance.confirm.drop_space.content", "确定要删除空间\"%s\"吗？"), spaceUsage.getName()))){
            List<String> datafilePaths = new ArrayList<>();
            for(SpaceUsage u:chunkChartList){
                if(u.getLabel().trim().endsWith("[ "+spaceUsage.getName()+" ]")){
                    datafilePaths.add(u.getName());
                }
            }
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    InstanceTabCapability capability = resolveInstanceTabCapability()
                            .orElseThrow(() -> new UnsupportedOperationException("Drop space is not supported"));
                    capability.dropSpace(connect, spaceUsage.getName(), datafilePaths);
                    return null;
                }
            };
            task.setOnSucceeded(event1 -> {
                //cancelBtn.fire();
                NotificationUtil.showMainNotification(String.format(I18n.t("instance.notice.space_deleted", "空间\"%s\"已删除！"), spaceUsage.getName()));
                refreshCallback.run();
            });
            task.setOnFailed(event1 -> {
                String error = task.getException().getMessage();
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
            });
            AppExecutor.runTask(task);
        }
        // 后续可补充更多删除后的刷新逻辑
    }

    public void onExpandDatafile(SpaceUsage spaceUsage) {
        // 设置数据文件自动扩展
        if(AlertUtil.CustomAlertConfirm(I18n.t("instance.confirm.expand_datafile.title", "数据文件自动扩展"), String.format(I18n.t("instance.confirm.expand_datafile.content", "确定要设置数据文件\"%s\"自动扩展吗？"), spaceUsage.getName()))){
            int chunkId=spaceUsage.getNumber();
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    try {
                        adminService.setStorageSegmentExtendable(connect, chunkId, true);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }
            };
            task.setOnSucceeded(event1 -> {
                //cancelBtn.fire();
                NotificationUtil.showMainNotification(String.format(I18n.t("instance.notice.datafile_expanded", "数据文件\"%s\"已设置为自动扩展！"), spaceUsage.getName()));
                refreshCallback.run();
            });
            task.setOnFailed(event1 -> {
                String error = task.getException().getMessage();
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"), error);
            });
            AppExecutor.runTask(task);
        }
        // 后续可补充更多刷新逻辑
    }

    public void onOracleCreateTablespace(SpaceUsage spaceUsage) {
        final String dirPrefix = resolveOracleDatafileDirectoryPrefixFromChunks();

        ImageView loadingIv = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);
        Label runningLabel = new Label(I18n.t("instance.dialog.oracle.executing", "正在执行…"));
        runningLabel.textProperty().bind(I18n.bind("instance.dialog.oracle.executing", "正在执行…"));
        Button oracleDdlStopButton = new Button("");
        oracleDdlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        oracleDdlStopButton.setFocusTraversable(false);
        oracleDdlStopButton.getStyleClass().add("small");
        Tooltip oracleDdlStopTip = new Tooltip();
        oracleDdlStopTip.textProperty().bind(I18n.bind("popup.back_sql.stop.tooltip", "停止此任务"));
        oracleDdlStopButton.setTooltip(oracleDdlStopTip);
        HBox loadingRow = new HBox(8, loadingIv, runningLabel, oracleDdlStopButton);
        loadingRow.setAlignment(Pos.CENTER);
        loadingRow.getStyleClass().add("modal-progress-card-padded");
        loadingRow.setMaxHeight(20);
        HBox overlayBar = new HBox(loadingRow);
        overlayBar.setAlignment(Pos.CENTER);
        overlayBar.getStyleClass().add("modal-progress-overlay-strong");
        overlayBar.setVisible(false);

        final AtomicReference<Task<Void>> oracleDdlTaskHolder = new AtomicReference<>();
        final AtomicReference<Statement> oracleDdlStatementHolder = new AtomicReference<>();
        Runnable stopOracleDdl = () -> {
            Statement st = oracleDdlStatementHolder.get();
            if (st != null) {
                try {
                    st.cancel();
                } catch (SQLException ignored) {
                }
            }
            Task<Void> t = oracleDdlTaskHolder.getAndSet(null);
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
        oracleDdlStopButton.setOnAction(e -> stopOracleDdl.run());

        Label nameLbl = new Label(I18n.t("instance.dialog.oracle.ts_name", "表空间名"));
        CustomUserTextField nameField = new CustomUserTextField();
        nameField.setMinWidth(280);
        Label pathLbl = new Label(I18n.t("instance.dialog.oracle.datafile_path", "数据文件全路径"));
        CustomUserTextField pathField = new CustomUserTextField();
        pathField.setMinWidth(360);
        pathField.setPromptText(I18n.t("instance.dialog.file_path.prompt", "根据空间名称自动填充"));
        if (!dirPrefix.isEmpty()) {
            pathField.setText(dirPrefix);
        }
        Label sizeLbl = new Label(I18n.t("instance.dialog.oracle.size_mb", "初始大小 (MB)"));
        CustomUserTextField sizeField = new CustomUserTextField();
        sizeField.setMinWidth(120);
        sizeField.setText("128");
        sizeField.textProperty().addListener((obs, ov, nv) -> {
            if (nv != null && !nv.matches("\\d*")) {
                sizeField.setText(nv.replaceAll("\\D", ""));
            }
        });
        CheckBox autoExt = new CheckBox(I18n.t("instance.dialog.oracle.autoextend",
                "数据文件自动扩展（NEXT 10M MAXSIZE UNLIMITED）"));
        autoExt.setSelected(true);

        nameField.textProperty().addListener((obs, ov, nv) -> {
            if (nv == null) {
                return;
            }
            String noSpace = nv.replace(" ", "");
            if (!noSpace.equals(nv)) {
                nameField.setText(noSpace);
                return;
            }
            if (noSpace.isEmpty()) {
                pathField.setText(dirPrefix);
            } else {
                pathField.setText(dirPrefix + noSpace.toLowerCase(Locale.ROOT) + "01.dbf");
            }
        });
        pathField.textProperty().addListener((obs, ov, nv) -> {
            if (nv != null && nv.contains(" ")) {
                pathField.setText(nv.replace(" ", ""));
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        int row = 0;
        grid.add(nameLbl, 0, row);
        grid.add(nameField, 1, row++);
        grid.add(pathLbl, 0, row);
        grid.add(pathField, 1, row++);
        grid.add(sizeLbl, 0, row);
        grid.add(sizeField, 1, row++);
        grid.add(autoExt, 0, row, 2, 1);

        StackPane root = new StackPane(grid, overlayBar);

        ButtonType commitType = new ButtonType(
                I18n.t("instance.dialog.create", "创建"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(
                I18n.t("common.cancel", "取消"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("instance.dialog.oracle.create_ts.title", "创建 Oracle 表空间"),
                root,
                600,
                Region.USE_COMPUTED_SIZE,
                commitType,
                cancelType);
        Button commitBtn = dialog.getButton(commitType);
        Button cancelBtn = dialog.getButton(cancelType);
        dialog.getStage().setOnCloseRequest((WindowEvent ev) -> {
            if (overlayBar.isVisible()) {
                ev.consume();
                stopOracleDdl.run();
            }
        });
        cancelBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            if (overlayBar.isVisible()) {
                ev.consume();
                stopOracleDdl.run();
            }
        });
        commitBtn.disableProperty().bind(overlayBar.visibleProperty());
        commitBtn.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            String ts = nameField.getText().trim();
            String path = pathField.getText().trim();
            String sz = sizeField.getText().trim();
            if (ts.isEmpty()) {
                nameField.requestFocus();
                return;
            }
            if (path.isEmpty()) {
                pathField.requestFocus();
                return;
            }
            if (sz.isEmpty()) {
                sizeField.requestFocus();
                return;
            }
            long mb;
            try {
                mb = Long.parseLong(sz);
            } catch (NumberFormatException ex) {
                return;
            }
            if (mb <= 0) {
                sizeField.requestFocus();
                return;
            }
            long finalMb = mb;
            boolean autoextend = autoExt.isSelected();
            overlayBar.setVisible(true);
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    InstanceMutationUtil.createOracleTablespace(
                            connect, ts, path, finalMb, autoextend, oracleDdlStatementHolder);
                    return null;
                }
            };
            oracleDdlTaskHolder.set(task);
            task.setOnSucceeded(e1 -> Platform.runLater(() -> {
                oracleDdlTaskHolder.set(null);
                overlayBar.setVisible(false);
                runningLabel.textProperty().unbind();
                cancelBtn.fire();
                NotificationUtil.showMainNotification(
                        I18n.t("instance.notice.oracle_ts_created", "表空间已创建"));
                refreshCallback.run();
            }));
            task.setOnFailed(e1 -> Platform.runLater(() -> {
                oracleDdlTaskHolder.set(null);
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

    public void onOracleAddDatafile(SpaceUsage spaceUsage) {
        final String tsName = spaceUsage.getName() == null ? "" : spaceUsage.getName().trim();
        if (tsName.isEmpty()) {
            return;
        }

        ImageView loadingIv = IconFactory.imageView(IconPaths.LOADING_GIF, 12, 12, true);
        Label runningLabel = new Label(I18n.t("instance.dialog.oracle.executing", "正在执行…"));
        runningLabel.textProperty().bind(I18n.bind("instance.dialog.oracle.executing", "正在执行…"));
        Button addOracleDdlStopButton = new Button("");
        addOracleDdlStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
        addOracleDdlStopButton.setFocusTraversable(false);
        addOracleDdlStopButton.getStyleClass().add("small");
        Tooltip addOracleDdlStopTip = new Tooltip();
        addOracleDdlStopTip.textProperty().bind(I18n.bind("popup.back_sql.stop.tooltip", "停止此任务"));
        addOracleDdlStopButton.setTooltip(addOracleDdlStopTip);
        HBox loadingRow = new HBox(8, loadingIv, runningLabel, addOracleDdlStopButton);
        loadingRow.setAlignment(Pos.CENTER);
        loadingRow.getStyleClass().add("modal-progress-card-padded");
        loadingRow.setMaxHeight(20);
        HBox overlayBar = new HBox(loadingRow);
        overlayBar.setAlignment(Pos.CENTER);
        overlayBar.getStyleClass().add("modal-progress-overlay-strong");
        overlayBar.setVisible(false);

        final AtomicReference<Task<Void>> addOracleDdlTaskHolder = new AtomicReference<>();
        final AtomicReference<Statement> addOracleDdlStatementHolder = new AtomicReference<>();
        Runnable stopAddOracleDdl = () -> {
            Statement st = addOracleDdlStatementHolder.get();
            if (st != null) {
                try {
                    st.cancel();
                } catch (SQLException ignored) {
                }
            }
            Task<Void> t = addOracleDdlTaskHolder.getAndSet(null);
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
        addOracleDdlStopButton.setOnAction(e -> stopAddOracleDdl.run());

        Label tsLbl = new Label(I18n.t("instance.dialog.oracle.add_datafile.ts_label", "表空间"));
        CustomUserTextField tsField = new CustomUserTextField();
        tsField.setText(tsName);
        tsField.setEditable(false);
        tsField.setMinWidth(280);
        Label pathLbl = new Label(I18n.t("instance.dialog.oracle.datafile_path", "数据文件全路径"));
        CustomUserTextField pathField = new CustomUserTextField();
        pathField.setMinWidth(360);
        pathField.setPromptText(I18n.t("instance.dialog.file_path.prompt", "根据空间名称自动填充"));
        pathField.setText(suggestNextOracleDatafilePath(tsName));
        Label sizeLbl = new Label(I18n.t("instance.dialog.oracle.size_mb", "初始大小 (MB)"));
        CustomUserTextField sizeField = new CustomUserTextField();
        sizeField.setMinWidth(120);
        sizeField.setText("128");
        sizeField.textProperty().addListener((obs, ov, nv) -> {
            if (nv != null && !nv.matches("\\d*")) {
                sizeField.setText(nv.replaceAll("\\D", ""));
            }
        });
        CheckBox autoExt = new CheckBox(I18n.t("instance.dialog.oracle.autoextend",
                "数据文件自动扩展（NEXT 10M MAXSIZE UNLIMITED）"));
        autoExt.setSelected(true);
        pathField.textProperty().addListener((obs, ov, nv) -> {
            if (nv != null && nv.contains(" ")) {
                pathField.setText(nv.replace(" ", ""));
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        int row = 0;
        grid.add(tsLbl, 0, row);
        grid.add(tsField, 1, row++);
        grid.add(pathLbl, 0, row);
        grid.add(pathField, 1, row++);
        grid.add(sizeLbl, 0, row);
        grid.add(sizeField, 1, row++);
        grid.add(autoExt, 0, row, 2, 1);

        StackPane root = new StackPane(grid, overlayBar);

        ButtonType commitType = new ButtonType(
                I18n.t("instance.dialog.oracle.add_datafile.commit", "增加"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(
                I18n.t("common.cancel", "取消"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("instance.dialog.oracle.add_datafile.title", "为表空间增加数据文件"),
                root,
                600,
                Region.USE_COMPUTED_SIZE,
                commitType,
                cancelType);
        Button commitBtn = dialog.getButton(commitType);
        Button cancelBtn = dialog.getButton(cancelType);
        dialog.getStage().setOnCloseRequest((WindowEvent ev) -> {
            if (overlayBar.isVisible()) {
                ev.consume();
                stopAddOracleDdl.run();
            }
        });
        cancelBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            if (overlayBar.isVisible()) {
                ev.consume();
                stopAddOracleDdl.run();
            }
        });
        commitBtn.disableProperty().bind(overlayBar.visibleProperty());
        commitBtn.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            String path = pathField.getText().trim();
            String sz = sizeField.getText().trim();
            if (path.isEmpty()) {
                pathField.requestFocus();
                return;
            }
            if (sz.isEmpty()) {
                sizeField.requestFocus();
                return;
            }
            long mb;
            try {
                mb = Long.parseLong(sz);
            } catch (NumberFormatException ex) {
                return;
            }
            if (mb <= 0) {
                sizeField.requestFocus();
                return;
            }
            long finalMb = mb;
            boolean autoextend = autoExt.isSelected();
            overlayBar.setVisible(true);
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    InstanceMutationUtil.addOracleDatafile(
                            connect, tsName, path, finalMb, autoextend, addOracleDdlStatementHolder);
                    return null;
                }
            };
            addOracleDdlTaskHolder.set(task);
            task.setOnSucceeded(e1 -> Platform.runLater(() -> {
                addOracleDdlTaskHolder.set(null);
                overlayBar.setVisible(false);
                runningLabel.textProperty().unbind();
                cancelBtn.fire();
                NotificationUtil.showMainNotification(
                        I18n.t("instance.notice.oracle_datafile_added", "数据文件已添加"));
                refreshCallback.run();
            }));
            task.setOnFailed(e1 -> Platform.runLater(() -> {
                addOracleDdlTaskHolder.set(null);
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

    public void onOracleDatafileAutoextend(SpaceUsage spaceUsage, boolean enable) {
        String ts = InstanceMutationUtil.parseOracleTablespaceFromDatafileLabel(spaceUsage.getLabel());
        if (ts == null || ts.isBlank()) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "错误"),
                    I18n.t("instance.error.oracle_datafile_label", "无法解析数据文件所属表空间。"));
            return;
        }
        String path = spaceUsage.getName();
        String title = enable
                ? I18n.t("instance.confirm.oracle.datafile_autoextend_on.title", "启用数据文件自动扩展")
                : I18n.t("instance.confirm.oracle.datafile_autoextend_off.title", "取消数据文件自动扩展");
        String content = enable
                ? String.format(I18n.t(
                        "instance.confirm.oracle.datafile_autoextend_on.content",
                        "确定对数据文件「%s」启用自动扩展（NEXT 10M，MAXSIZE UNLIMITED）吗？"),
                        path)
                : String.format(I18n.t(
                        "instance.confirm.oracle.datafile_autoextend_off.content",
                        "确定关闭数据文件「%s」的自动扩展吗？"),
                        path);
        if (!AlertUtil.CustomAlertConfirm(title, content)) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                InstanceMutationUtil.setOracleDatafileAutoextend(connect, ts, path, enable, null);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            NotificationUtil.showMainNotification(enable
                    ? I18n.t("instance.notice.oracle_datafile_autoextend_on", "已启用数据文件自动扩展")
                    : I18n.t("instance.notice.oracle_datafile_autoextend_off", "已关闭数据文件自动扩展"));
            refreshCallback.run();
        });
        task.setOnFailed(e -> AlertUtil.CustomAlert(
                I18n.t("common.error", "错误"),
                task.getException() == null ? "" : task.getException().getMessage()));
        AppExecutor.runTask(task);
    }

    private String resolveAdminOsUser() {
        String username = connect.getUsername();
        if (username != null && !username.isBlank()) {
            return username;
        }
        return resolveInstanceTabCapability()
                .map(capability -> capability.adminOsUser(connect))
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> resolveInstanceManagerCapability()
                        .map(capability -> capability.adminOsUser(connect))
                        .orElseGet(() -> "INFORMIX".equalsIgnoreCase(connect.getDbtype()) ? "informix" : "gbasedbt"));
    }

    /** 与 Informix/GBase 一致：从当前数据文件路径取目录前缀，供新建表空间默认路径。 */
    private String resolveOracleDatafileDirectoryPrefixFromChunks() {
        for (SpaceUsage u : chunkChartListSupplier.get()) {
            if (u == null || u.getName() == null || u.getName().isBlank()) {
                continue;
            }
            String n = u.getName();
            int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
            if (slash >= 0) {
                return n.substring(0, slash + 1);
            }
        }
        return "";
    }

    /**
     * 以该表空间下「列表中最后一个」数据文件所在目录与命名为参考，按 {@code 前缀 + 两位序号 + .dbf} 递增
     * （如已有 test01.dbf 则建议 test02.dbf）；若无匹配命名则在该目录下用表空间名小写 + 序号。
     */
    private String suggestNextOracleDatafilePath(String tablespaceName) {
        String dirPrefix = resolveOracleDatafileDirectoryPrefixFromChunks();
        if (tablespaceName == null) {
            return dirPrefix;
        }
        String ts = tablespaceName.replace(" ", "").trim();
        if (ts.isEmpty()) {
            return dirPrefix;
        }
        Set<String> existing = new HashSet<>();
        List<String> pathsForTs = new ArrayList<>();
        for (SpaceUsage u : chunkChartListSupplier.get()) {
            if (u == null || u.getName() == null || u.getName().isBlank()) {
                continue;
            }
            String path = u.getName().trim();
            existing.add(path);
            String labelTs = InstanceMutationUtil.parseOracleTablespaceFromDatafileLabel(u.getLabel());
            if (labelTs != null && labelTs.equalsIgnoreCase(ts)) {
                pathsForTs.add(path);
            }
        }
        int maxNum = -1;
        String refDir = null;
        String refNamePrefix = null;
        for (String p : pathsForTs) {
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            if (slash < 0) {
                continue;
            }
            String basename = p.substring(slash + 1);
            Matcher m = ORACLE_DATAFILE_BASENAME_SUFFIX.matcher(basename);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(2), 10);
                if (n > maxNum) {
                    maxNum = n;
                    refDir = p.substring(0, slash + 1);
                    refNamePrefix = m.group(1);
                }
            }
        }
        if (refDir != null && refNamePrefix != null && maxNum >= 0) {
            for (int next = maxNum + 1; next <= 999; next++) {
                String candidate = refDir + refNamePrefix + String.format("%02d", next) + ".dbf";
                if (!existing.contains(candidate)) {
                    return candidate;
                }
            }
            return refDir + refNamePrefix + "999.dbf";
        }
        String lastDir = dirPrefix;
        if (!pathsForTs.isEmpty()) {
            String lastPath = pathsForTs.get(pathsForTs.size() - 1);
            int slash = Math.max(lastPath.lastIndexOf('/'), lastPath.lastIndexOf('\\'));
            if (slash >= 0) {
                lastDir = lastPath.substring(0, slash + 1);
            }
        }
        String lowerTs = ts.toLowerCase(Locale.ROOT);
        for (int i = 1; i <= 999; i++) {
            String candidate = lastDir + lowerTs + String.format("%02d", i) + ".dbf";
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
        return lastDir + lowerTs + "999.dbf";
    }
}
