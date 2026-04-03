package com.dbboys.ctrl;


import com.dbboys.app.AppContext;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.customnode.CustomInfoCodeArea;
import com.dbboys.customnode.CustomLostFocusCommitTableCell;
import com.dbboys.customnode.CustomTableView;
import com.dbboys.customnode.CustomUserTextField;
import com.dbboys.api.ConnectionService;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.NamedServerConnectionCapability;
import com.dbboys.impl.DialectServices;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.*;
import com.dbboys.util.tree.TreeViewUtil;
import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.vo.ConnectFolder;
import com.dbboys.vo.Connect;
import com.dbboys.vo.TreeData;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.List;


public class CreateConnectController {
    private static final Logger log = LogManager.getLogger(CreateConnectController.class);
    private static final String EMPTY_PROPS = "[]";
    private final ConnectionService connectionService = com.dbboys.app.AppContext.get(ConnectionService.class);

    @FXML
    public ChoiceBox<ConnectFolder> connectFolderChoiceBox;
    @FXML
    private ChoiceBox<String> dbTypeChoiceBox;
    @FXML
    private ChoiceBox<String> driverChoiceBox;
    @FXML
    private CustomUserTextField connectNameTextField;
    @FXML
    private TextField ipAddressTextField;
    @FXML
    private TextField portTextField;
    @FXML
    private TextField usernameTextField;
    @FXML
    private PasswordField passwordTextField;
    @FXML
    private CheckBox readOnlyCheckBox;
    @FXML
    private HBox connectingHBox;
    @FXML
    private Button connectingStopButton;
    @FXML
    private Button addDriverButton;
    @FXML
    private Button deleteDriverButton;
    @FXML
    private Button modifyDriverButton;
    @FXML
    private Button modifyGroupButton;
    @FXML
    private Button switchGroupOrIP;
    @FXML
    private ButtonType commitButtonType;
    @FXML
    private ButtonType testButtonType;
    @FXML
    private ButtonType cancelButtonType;
    @FXML
    private DialogPane dialogPane;
    @FXML
    private Label connectNameLabel;
    @FXML
    private Label connectFolderLabel;
    @FXML
    private Label dbTypeLabel;
    @FXML
    private Label driverLabel;
    @FXML
    private Label ipAddressLabel;
    @FXML
    private Label portLabel;
    @FXML
    private Label groupLabel;
    @FXML
    private Label usernameLabel;
    @FXML
    private Label passwordLabel;
    @FXML
    private Label connectingStatusLabel;
    @FXML
    private ImageView connectingLoadingImageView;
    @FXML
    private HBox groupHbox;
    @FXML
    private CustomUserTextField groupTextField;
    public  String choiceName;
    public  TreeData treeDataParam;
    public  Boolean isCopy;
    public  String props;
    public  Button cancelButton;
    public  Dialog<?> dialog;
    private Stage dialogStage;
    private boolean initializingDbTypeSelection = true;

    public CreateConnectController(){


    }
    //public AddInstance(String choiceName){
    //    this.choiceName = choiceName;
    //}

    public void init(TreeData treeDataParam, Boolean isCopy, Dialog<?> dialog){
        this.dialog = dialog;
        this.dialogStage = null;
        initCommon(treeDataParam, isCopy);
        cancelButton = (Button) dialogPane.lookupButton(cancelButtonType);
    }

    public void init(TreeData treeDataParam, Boolean isCopy, Stage stageWindow){
        this.dialogStage = stageWindow;
        this.dialog = null;
        initCommon(treeDataParam, isCopy);
        cancelButton = (Button) dialogPane.lookupButton(cancelButtonType);
        cancelButton.setOnAction(e -> closeWindow());
    }

    private void initCommon(TreeData treeDataParam, Boolean isCopy){
        this.props = EMPTY_PROPS;
        this.treeDataParam = treeDataParam;
        if(treeDataParam!=null&&treeDataParam instanceof Connect){
            this.props=((Connect)treeDataParam).getProps();
        }
        this.isCopy=isCopy;
        Connect connect=new Connect();
        ObservableList<ConnectFolder> list = FXCollections.observableArrayList(LocalDbRepository.getConnectFolders());
        connectFolderChoiceBox.setItems(list);
        connectFolderChoiceBox.getValue();
        connectFolderChoiceBox.getSelectionModel().select(0);
        connect.setParentId(connectFolderChoiceBox.getSelectionModel().getSelectedItem().getId());
        connectFolderChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue)->{
            connect.setParentId(newValue.getId());
        });
        ObservableList<String> dbtypelist = FXCollections.observableArrayList(loadAvailableDbTypes());
        dbTypeChoiceBox.setItems(dbtypelist);


        //driver增加监听，发生变化重置connect.driver
        driverChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable,oldValue,newValue)->{
            //driverChoiceBox.setItems会触发此事件，此时newvalue==null，需要排除
            if(newValue!=null){
                connect.setDriver(newValue);
            }
        });
        //dbtype发生变化监听，变化后设置connect的dbtype属性，并改变driver驱动列表
        dbTypeChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable,oldValue,newValue)->{
            connect.setDbtype(newValue);
            List<String> driverList = new ArrayList<>();
            File driverfolder = new File("extlib/"+connect.getDbtype());
            File[] driverFiles = driverfolder.listFiles();
            if (driverFiles != null) {
                for (File file : driverFiles) {
                    if (file.isFile()&&file.getName().toLowerCase().endsWith(".jar")) {
                        driverList.add(file.getName());
                    }
                }
            } else {
                log.warn("Driver directory not found or not accessible: {}", driverfolder.getAbsolutePath());
            }
            Collections.sort(driverList);
            ObservableList<String> driverItems = FXCollections.observableArrayList(driverList);
            driverChoiceBox.setItems(driverItems); //触发内容变化监听
            driverChoiceBox.getSelectionModel().select(driverItems.size()-1);
            applyDialectDefaults(connect, oldValue, newValue);
            refreshConnectionPropertiesForDbType(newValue, initializingDbTypeSelection && treeDataParam instanceof Connect);
            refreshDriverPropertyButton(newValue);
        });
        if (!dbtypelist.isEmpty()) {
            dbTypeChoiceBox.getSelectionModel().select(0);
        }

        Connect installTemplate = AppState.getLastInstallConnect();
        if (treeDataParam == null && installTemplate != null) {
            applyInstallTemplate(connect, installTemplate);
        } else if (dbTypeChoiceBox.getValue() != null) {
            applyDialectDefaults(connect, null, dbTypeChoiceBox.getValue());
        }

        //如果传了参数，可能树分类上右键新建连接或编辑连接，需将已有参数填充到表单
        if(treeDataParam!=null){
            String connectFolder=null;
            //如果当前选中的元素级别为2，就是在分类上右键创建连接，默认选择对应的系统分类就行
            if(treeDataParam instanceof ConnectFolder){
                connectFolder=treeDataParam.getName();
            }else{
                //如果不是分类上右键新建，那就是编辑连接，所有信息填充到表单
                connectFolder=LocalDbRepository.getConnectType(((Connect) treeDataParam));
                connectNameTextField.setText(((Connect)treeDataParam).getName());
                usernameTextField.setText(((Connect) treeDataParam).getUsername());
                passwordTextField.setText(((Connect) treeDataParam).getPassword());
                readOnlyCheckBox.setSelected(((Connect) treeDataParam).getReadonly());
                connect.setId(((Connect) treeDataParam).getId()); //用于检查连接名称是否已存在，如果是编辑，要排除自己的名字
                connect.setDatabase(((Connect) treeDataParam).getDatabase());
                int j=0;
                ObservableList<String> items = dbTypeChoiceBox.getItems();
                for (String item : items) {
                    if(item.equals(((Connect) treeDataParam).getDbtype())){
                        dbTypeChoiceBox.getSelectionModel().select(j);
                    }
                    j++;
                }

                String namedServerPropName = namedServerPropNameFor(((Connect) treeDataParam).getDbtype());
                if(!namedServerPropName.isEmpty() && !((Connect)treeDataParam).getPropByName(namedServerPropName).isEmpty()){
                    groupTextField.setText(((Connect)treeDataParam).getPropByName(namedServerPropName));
                    groupHbox.setVisible(true);
                }else{
                    ipAddressTextField.setText(((Connect) treeDataParam).getIp());
                    portTextField.setText(((Connect) treeDataParam).getPort());
                }

                selectChoiceValue(driverChoiceBox, ((Connect) treeDataParam).getDriver());
            }

            int i=0;
            for(TreeData treeData:list){
                if(treeData.getName().equals(connectFolder)){
                    connectFolderChoiceBox.getSelectionModel().select(i);
                    break;
                }
                i++;
            }

        }

        initializingDbTypeSelection = false;
        refreshDriverPropertyButton(dbTypeChoiceBox.getValue());
        applyTextFormatters();



        Button tryConnectButton = (Button) dialogPane.lookupButton(testButtonType);
        tryConnectButton.disableProperty().bind(connectingHBox.visibleProperty());
        tryConnectButton.addEventFilter(ActionEvent.ACTION, event -> {
            if(!checkInput())
            {
                event.consume();
            }else {
                setConnect(connect);
                
                //如果连接信息可正常连接，检查连接名是否已存在
                commitConnecting(connect,false);
            }
            event.consume();
        });


        final Button commitButton = (Button) dialogPane.lookupButton(commitButtonType);
        commitButton.disableProperty().bind(connectingHBox.visibleProperty());
        commitButton.addEventFilter(ActionEvent.ACTION, event1 -> {
            if(!checkInput())
            {
                event1.consume();
            }else {
                setConnect(connect);
                //如果连接信息可正常连接，检查连接名是否已存在
                if(LocalDbRepository.checkConnectLeafNameExists(connect)){
                    AlertUtil.CustomAlert(
                            I18n.t("common.error"),
                            String.format(I18n.t("createconnect.error.name_exists"), connect.getName())
                    );
                    event1.consume();
                }else {//如果连接名不存在，增加新节点
                    commitConnecting(connect,true);
                    event1.consume();
                }
            }
        });
        //connectNameTextField.requestFocus();
    }

    private void closeWindow() {
        if (dialog != null) dialog.close();
        else if (dialogStage != null) dialogStage.close();
    }

    /** Call from commitConnecting to cancel task when window is closed. */
    private void setTaskCancelOnClose(java.lang.Runnable runnable) {
        if (dialog != null) dialog.setOnCloseRequest(e -> runnable.run());
        else if (dialogStage != null) dialogStage.setOnCloseRequest(e -> runnable.run());
    }

    public void setConnect(Connect connect){

        connect.setProps(props);
        String namedServerPropName = namedServerPropNameFor(connect.getDbtype());
        if(groupHbox.isVisible()){
            if (!namedServerPropName.isEmpty()) {
                connect.setPropByName(namedServerPropName, groupTextField.getText());
            }
            if (connectNameTextField.getText().isEmpty()) {
                connect.setName(groupTextField.getText());
            } else {
                connect.setName(connectNameTextField.getText());
            }
            props=connect.getProps();
        }else{
            if (!namedServerPropName.isEmpty()) {
                connect.setPropByName(namedServerPropName, "");
            }
            connect.setIp(ipAddressTextField.getText());
            connect.setPort(portTextField.getText());
            if (connectNameTextField.getText().isEmpty()) {
                connect.setName("[" + connect.getIp() + "_" + connect.getPort() + "]");
            } else {
                connect.setName(connectNameTextField.getText());
            }
        }

        if (connect.getDatabase() == null || connect.getDatabase().isBlank()) {
            connect.setDatabase(defaultDatabaseFor(connect.getDbtype()));
        }
        connect.setUsername(usernameTextField.getText());
        connect.setPassword(passwordTextField.getText());
        connect.setReadonly(readOnlyCheckBox.isSelected());
            
    }
    public boolean checkInput(){
        if(groupHbox.isVisible()){
                if(groupTextField.getText().isEmpty()){
                    groupTextField.requestFocus();
                    return false;
                }
            }else 
                {
                    if(ipAddressTextField.getText().isEmpty()){
                        //ipAddressTextField.setStyle("-fx-border-color: #ff0000;-fx-border-radius: 3");
                        ipAddressTextField.requestFocus();
                        return false;
                    }
                    else if(portTextField.getText().isEmpty()){
                        //portTextField.setStyle("-fx-border-color: #ff0000;-fx-border-radius: 3");
                        portTextField.requestFocus();
                        return false;
                    }
                }
            if(usernameTextField.getText().isEmpty()){
                // usernameTextField.setStyle("-fx-border-color: #ff0000;-fx-border-radius: 3");
                usernameTextField.requestFocus();
                return false;
            }
            else if(passwordTextField.getText().isEmpty()){
                //passwordTextField.setStyle("-fx-border-color: #ff0000;-fx-border-radius: 3");
                passwordTextField.requestFocus();
                return false;
            }
            return true;
    }

    private void applyDialectDefaults(Connect connect, String oldDbType, String newDbType) {
        DatabasePlatform oldDialect = resolveDialectServices().getPlatform(oldDbType);
        DatabasePlatform newDialect = resolveDialectServices().getPlatform(newDbType);
        if (newDialect == null) {
            return;
        }
        switchGroupOrIP.setVisible(supportsNamedServerConnection(newDialect));
        if (!supportsNamedServerConnection(newDialect)) {
            groupHbox.setVisible(false);
        }
        if (shouldReplaceField(portTextField.getText(), oldDialect == null ? null : oldDialect.defaultPort())) {
            portTextField.setText(newDialect.defaultPort());
        }
        if (shouldReplaceField(usernameTextField.getText(), oldDialect == null ? null : oldDialect.defaultUsername())) {
            usernameTextField.setText(newDialect.defaultUsername());
        }
        if (connect.getDatabase() == null
                || connect.getDatabase().isBlank()
                || (oldDialect != null && connect.getDatabase().equalsIgnoreCase(oldDialect.defaultDatabase()))) {
            connect.setDatabase(newDialect.defaultDatabase());
        }
    }

    private void refreshConnectionPropertiesForDbType(String newDbType, boolean preserveExistingProps) {
        if (preserveExistingProps) {
            return;
        }
        props = defaultConnectionPropsFor(newDbType);
    }

    private void refreshDriverPropertyButton(String dbType) {
        DatabasePlatform dialect = resolveDialectServices().getPlatform(dbType);
        boolean supported = dialect != null && dialect.supportsConnectionProperties();
        modifyDriverButton.setDisable(!supported);
    }

    private String defaultConnectionPropsFor(String dbType) {
        DatabasePlatform dialect = resolveDialectServices().getPlatform(dbType);
        if (dialect == null) {
            return EMPTY_PROPS;
        }
        String propsJson = dialect.defaultConnectionProps();
        return propsJson == null || propsJson.isBlank() ? EMPTY_PROPS : propsJson;
    }

    private boolean shouldReplaceField(String currentValue, String oldDefault) {
        return currentValue == null
                || currentValue.isBlank()
                || (oldDefault != null && !oldDefault.isBlank() && currentValue.equalsIgnoreCase(oldDefault));
    }

    private String defaultDatabaseFor(String dbType) {
        DatabasePlatform dialect = resolveDialectServices().getPlatform(dbType);
        if (dialect == null) {
            return "";
        }
        return dialect.defaultDatabase();
    }

    private DialectServices resolveDialectServices() {
        try {
            return AppContext.get(DialectServices.class);
        } catch (IllegalStateException e) {
            return DialectServices.createDefault();
        }
    }

    private List<String> loadAvailableDbTypes() {
        Set<String> registeredDbTypes = new HashSet<>();
        for (DatabasePlatform platform : resolveDialectServices().getPlatformRegistry().getAllPlatforms()) {
            if (platform != null && platform.getDbType() != null && !platform.getDbType().isBlank()) {
                registeredDbTypes.add(platform.getDbType());
            }
        }

        List<String> dbtypes = new ArrayList<>();
        File folder = new File("extlib");
        File[] dbTypeFolders = folder.listFiles();
        if (dbTypeFolders == null) {
            log.warn("extlib directory not found or not accessible: {}", folder.getAbsolutePath());
            return dbtypes;
        }
        for (File file : dbTypeFolders) {
            if (file.isDirectory() && registeredDbTypes.contains(file.getName()) && hasDriverJar(file)) {
                dbtypes.add(file.getName());
            }
        }
        dbtypes.sort(String.CASE_INSENSITIVE_ORDER);
        return dbtypes;
    }

    private boolean hasDriverJar(File dbTypeFolder) {
        File[] driverFiles = dbTypeFolder.listFiles();
        if (driverFiles == null) {
            return false;
        }
        for (File file : driverFiles) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return true;
            }
        }
        return false;
    }

    private void applyInstallTemplate(Connect connect, Connect template) {
        if (template == null) {
            return;
        }
        boolean matchedDbType = selectChoiceValue(dbTypeChoiceBox, template.getDbtype());
        if (matchedDbType && template.getProps() != null && !template.getProps().isBlank()) {
            props = template.getProps();
        }
        if (template.getDriver() != null && !template.getDriver().isBlank()) {
            selectChoiceValue(driverChoiceBox, template.getDriver());
        }
        connect.setDatabase(template.getDatabase());
        String namedServerPropName = namedServerPropNameFor(template.getDbtype());
        if (!namedServerPropName.isEmpty()) {
            String namedServer = template.getPropByName(namedServerPropName);
            if (namedServer != null && !namedServer.isBlank()) {
                groupTextField.setText(namedServer);
                groupHbox.setVisible(true);
            }
        }
        ipAddressTextField.setText(template.getIp());
        portTextField.setText(template.getPort());
        usernameTextField.setText(template.getUsername());
        passwordTextField.setText(template.getPassword());
        readOnlyCheckBox.setSelected(Boolean.TRUE.equals(template.getReadonly()));
    }

    private boolean selectChoiceValue(ChoiceBox<String> choiceBox, String value) {
        if (choiceBox == null || value == null || value.isBlank()) {
            return false;
        }
        ObservableList<String> items = choiceBox.getItems();
        if (items == null) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            if (value.equals(items.get(i))) {
                choiceBox.getSelectionModel().select(i);
                return true;
            }
        }
        return false;
    }

    private String namedServerPropNameFor(String dbType) {
        DatabasePlatform platform = resolveDialectServices().getPlatform(dbType);
        if (!(platform instanceof NamedServerConnectionCapability capability)) {
            return "";
        }
        String propName = capability.namedServerPropertyName();
        return propName == null ? "" : propName;
    }

    private boolean supportsNamedServerConnection(DatabasePlatform platform) {
        return platform instanceof NamedServerConnectionCapability;
    }

    public void initialize() throws IOException {
        setupIcons();
    }

    private void setupIcons() {

        connectNameLabel.setGraphic(IconFactory.group(IconPaths.CONNECTION_LINK, 0.5));
        connectFolderLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_FOLDER, 0.4));
        dbTypeLabel.setGraphic(IconFactory.group(IconPaths.SQL_DATABASE, 0.4));
        driverLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_DRIVER, 0.05));
        ipAddressLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_IP, 0.6));
        portLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PORT, 0.45));
        groupLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_GROUP, 0.55));
        usernameLabel.setGraphic(IconFactory.group(IconPaths.SQL_USER, 0.5));
        passwordLabel.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_PASSWORD, 0.5));
        readOnlyCheckBox.setGraphic(IconFactory.group(IconPaths.SQL_READONLY, 0.5));

        addDriverButton.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_ADD_DRIVER, 0.7));
        deleteDriverButton.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_REMOVE_DRIVER, 0.6));
        modifyDriverButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.6));
        modifyGroupButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.6));
        switchGroupOrIP.setGraphic(IconFactory.group(IconPaths.MAIN_REBUILD, 0.7));

        connectingLoadingImageView.setImage(new Image(IconPaths.LOADING_GIF));
        connectingStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
    }

    private void applyTextFormatters() {
        connectNameTextField.setTextFormatter(new TextFormatter<String>(change -> {
            String text = change.getText();
            if (text != null && text.contains(" ")) {
                change.setText(text.replace(" ", ""));
            }
            return change;
        }));
        ipAddressTextField.setTextFormatter(new TextFormatter<String>(change -> {
            String text = change.getText();
            if (text != null && text.contains(" ")) {
                change.setText(text.replace(" ", ""));
            }
            return change;
        }));
        usernameTextField.setTextFormatter(new TextFormatter<String>(change -> {
            String text = change.getText();
            if (text != null && text.contains(" ")) {
                change.setText(text.replace(" ", ""));
            }
            return change;
        }));
        portTextField.setTextFormatter(new TextFormatter<String>(change -> {
            String newText = change.getControlNewText();
            return newText.matches("\\d*") ? change : null;
        }));
    }

    private void setConnectingVisible(boolean visible) {
        if (Platform.isFxApplicationThread()) {
            connectingHBox.setVisible(visible);
        } else {
            Platform.runLater(() -> connectingHBox.setVisible(visible));
        }
    }


        //添加驱动包
        public void addDriverClicked(){
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("createconnect.filechooser.select_driver"));
            fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Jar Files", "*.jar")
            );
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home") + "/Desktop"));
            Window owner = dialogPane != null && dialogPane.getScene() != null
                    ? dialogPane.getScene().getWindow()
                    : AppState.getWindow();
            File selectedFile = fileChooser.showOpenDialog(owner);
            if (selectedFile != null) {
                // 处理选中的文件
                ObservableList<String> items = driverChoiceBox.getItems();
                if(items.stream().anyMatch(name -> name.equals(selectedFile.getName()))){
                    AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("createconnect.error.driver_same_name"));
                }else{

                    Path sourcePath = Paths.get(selectedFile.getAbsolutePath());
                    Path targetPath = Paths.get("extlib/"+dbTypeChoiceBox.getValue()+"/"+selectedFile.getName());
                    Boolean md5same=false;
                    String sourceSamename=null;
                    try {
                        String sourceMd5=MD5Util.getMD5Checksum(sourcePath.toFile().getAbsolutePath());
                        String targetMd5=null;
                        ObservableList<String> drivers = driverChoiceBox.getItems();
                        for (String driver : drivers) {
                            targetMd5=MD5Util.getMD5Checksum(Paths.get("extlib/"+dbTypeChoiceBox.getValue()+"/"+driver).toFile().getAbsolutePath());
                            if(targetMd5.equals(sourceMd5)){
                                md5same=true;
                                sourceSamename=driver;
                                break;
                            }
                        }
                        if(md5same){
                            AlertUtil.CustomAlert(
                                    I18n.t("common.error"),
                                    String.format(I18n.t("createconnect.error.driver_same_md5"), sourceSamename)
                            );
                        }
                        else{
                            String newItem=selectedFile.getName();
                            driverChoiceBox.getItems().add(newItem);
                            driverChoiceBox.setValue(newItem);
                            Files.copy(sourcePath, targetPath);
                        }
                    } catch (Exception e) {
                        log.error(e.getMessage(),e);
                    }
                }
            }
        }

        //删除当前驱动包
        public void deleteDriverClicked(){
            if(driverChoiceBox.getItems().size()<=1){
                AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("createconnect.error.driver_last_one"));
            }else{
                String currItem = driverChoiceBox.getValue();
                File file = new File("extlib/"+dbTypeChoiceBox.getValue()+"/"+currItem);
                if(LocalDbRepository.checkDriverInUse(currItem)){
                    //如果正在使用，提示是否确认要删除
                    ButtonType buttonTypeOk = new ButtonType(I18n.t("createconnect.button.confirm"), ButtonBar.ButtonData.OK_DONE);
                    ButtonType buttonTypeCancel = new ButtonType(I18n.t("createconnect.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                    ButtonType result = AlertUtil.createContentDialog(
                            I18n.t("createconnect.confirm.delete_driver.title"),
                            new Label(I18n.t("createconnect.confirm.delete_driver.content")),
                            430,
                            180,
                            buttonTypeOk,
                            buttonTypeCancel
                    ).showAndWait();
                    if (result == buttonTypeOk) {
                        if(file.delete()) {
                            driverChoiceBox.getItems().remove(currItem);
                            driverChoiceBox.getSelectionModel().select(0);
                        }else{
                            AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("createconnect.error.driver_delete_failed"));
                        }
                    }
                }else{
                    if(file.delete()) {
                        driverChoiceBox.getItems().remove(currItem);
                        driverChoiceBox.getSelectionModel().select(0);
                    }else{
                        AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("createconnect.error.driver_delete_failed"));
                    }
                }

            }
        }


    //编辑当前驱动属性
    public void modifyDriverProps(){
        if (modifyDriverButton.isDisabled()) {
            return;
        }
        JSONArray jsonArray =new JSONArray(props);
        List<ObservableList<String>> lastdata=null;//根据确认或取消选择，赋值给lastdata
        ObservableList<ObservableList<String>> initdata = buildDriverPropRows(props);//如果取消，返回最初list
        ObservableList<ObservableList<String>> datalist = copyDriverPropRows(initdata);//如果确认，返回更新后的list
        CustomTableView<ObservableList<String>> tableView = new CustomTableView<>();
        tableView.setEditable(true);
        tableView.setSortPolicy((param) -> false);//禁用排序

        TableColumn<ObservableList<String>, Object> nameColumn = new TableColumn<ObservableList<String>, Object>(I18n.t("createconnect.table.prop_name"));
        nameColumn.setCellFactory(col -> new CustomLostFocusCommitTableCell<ObservableList<String>, Object>());
        nameColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(1)));
        nameColumn.setReorderable(false); // 禁用拖动
        nameColumn.setEditable(false);
        nameColumn.setReorderable(false);
        nameColumn.setPrefWidth(220);
        TableColumn<ObservableList<String>, Object> valueColumn = new TableColumn<ObservableList<String>, Object>(I18n.t("createconnect.table.prop_value"));
        valueColumn.setCellFactory(col -> new CustomLostFocusCommitTableCell<ObservableList<String>, Object>());
        valueColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(2)));
        valueColumn.setReorderable(false); // 禁用拖动
        valueColumn.setEditable(true);
        valueColumn.setReorderable(false);
        valueColumn.setPrefWidth(120);

        valueColumn.setOnEditCommit(event -> {
            // 获取当前行的模型数据（ObservableList<String>）
            ObservableList<String> rowData = event.getRowValue();

            // 获取编辑后的新值
            Object newValue = event.getNewValue();
            // 更新ObservableList中索引1的位置（与cellValueFactory对应）
            if (rowData.size() > 2) {  // 确保索引有效
                // 转换为字符串（根据实际需求调整类型）
                rowData.set(2,  newValue.toString());
                tableView.refresh();
            }
        });
        tableView.getColumns().addAll(nameColumn, valueColumn);
        tableView.setItems(datalist);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox contentBox = new VBox();
        contentBox.setId("modifyProps");
        contentBox.setAlignment(Pos.TOP_LEFT);
        contentBox.getChildren().add(tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);
        tableView.setMaxHeight(Double.MAX_VALUE);

        ButtonType buttonTypeReset = new ButtonType(
                I18n.t("createconnect.button.reset", "重置"),
                ButtonBar.ButtonData.LEFT
        );
        ButtonType buttonTypeOk = new ButtonType(I18n.t("createconnect.button.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("createconnect.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                I18n.t("createconnect.dialog.edit_props.title"),
                contentBox,
                420,
                600,
                buttonTypeReset,
                buttonTypeOk,
                buttonTypeCancel
        );
        Button resetButton = dialog.getButton(buttonTypeReset);
        if (resetButton != null) {
            resetButton.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                tableView.edit(-1, null);
                datalist.setAll(buildDriverPropRows(defaultConnectionPropsFor(dbTypeChoiceBox.getValue())));
                tableView.getSelectionModel().clearSelection();
                tableView.refresh();
            });
        }
        ButtonType result = dialog.showAndWait();
        if (result == buttonTypeOk) {
            lastdata = copyDriverPropRows(datalist);
        }else {
            lastdata=initdata;
        }
        jsonArray.clear();
        for (ObservableList<String> row :lastdata) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("propName", row.get(1));
            jsonObject.put("propValue", row.get(2));
            jsonArray.put(jsonObject);
        }
        props=jsonArray.toString();
    }

    private ObservableList<ObservableList<String>> buildDriverPropRows(String propsJson) {
        JSONArray jsonArray = new JSONArray(
                propsJson == null || propsJson.isBlank() ? defaultConnectionPropsFor(dbTypeChoiceBox.getValue()) : propsJson
        );
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            ObservableList<String> row = FXCollections.observableArrayList();
            row.add(null);
            row.add(jsonObject.getString("propName"));
            row.add(jsonObject.getString("propValue"));
            rows.add(row);
        }
        return rows;
    }

    private ObservableList<ObservableList<String>> copyDriverPropRows(List<ObservableList<String>> sourceRows) {
        ObservableList<ObservableList<String>> copiedRows = FXCollections.observableArrayList();
        for (ObservableList<String> row : sourceRows) {
            copiedRows.add(FXCollections.observableArrayList(row));
        }
        return copiedRows;
    }


    //编辑组信息
    public void modifyGroupProps(){
    
        VBox contentBox = new VBox();
        contentBox.setId("modifyProps");
        contentBox.setAlignment(Pos.TOP_LEFT);
        Path file = Paths.get("extlib", dbTypeChoiceBox.getValue(), "sqlhosts");
        String content="";
        String groupName = groupTextField.getText() == null || groupTextField.getText().isBlank() ? "db_group" : groupTextField.getText().trim();
        String defaultContent = groupName + "\tgroup\t-\t-\n"
                + "node01\tonsoctcp\t192.168.1.1\t9088\tg=" + groupName + "\n"
                + "node02\tonsoctcp\t192.168.1.2\t9088\tg=" + groupName;
        try {
            // 1. 判断文件是否存在
            if (Files.exists(file)) {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } else {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, defaultContent, StandardCharsets.UTF_8);
                content = defaultContent; // 写入后返回默认内容
            }
        } catch (IOException e) {
            log.error(e.getMessage(),e);
        }
        CustomInfoCodeArea codeArea = new CustomInfoCodeArea();
        VirtualizedScrollPane<CustomInfoCodeArea> virtualizedScrollPane = new VirtualizedScrollPane<>(codeArea);
        contentBox.getChildren().add(virtualizedScrollPane);
        VBox.setVgrow(virtualizedScrollPane, Priority.ALWAYS);
        virtualizedScrollPane.setMaxHeight(Double.MAX_VALUE);
        codeArea.setPrefWidth(400);
        codeArea.setPrefHeight(Region.USE_COMPUTED_SIZE);
        codeArea.setEditable(true);
        codeArea.replaceText(content);

        ButtonType buttonTypeOk = new ButtonType(I18n.t("createconnect.button.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("createconnect.button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType result = AlertUtil.createContentDialog(
                I18n.t("createconnect.dialog.edit_group.title"),
                contentBox,
                460,
                240,
                buttonTypeOk,
                buttonTypeCancel
        ).showAndWait();
        if (result == buttonTypeOk) {
            try {
                Files.writeString(file, codeArea.getText(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error(e.getMessage(),e);
            }
        }
    }

    public void switchGroupOrIP(){
        groupHbox.setVisible(!groupHbox.isVisible());
    }


    public void commitConnecting(Connect connect,boolean isCommit){
        try{
            setConnectingVisible(true);
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    Long start = System.currentTimeMillis();
                    Long end = System.currentTimeMillis();

                    try {
                        connect.setConn(connectionService.createConnection(connect));

                        end=System.currentTimeMillis();
                        if(isCancelled()) return  null;

                    } catch (SQLException e1) {
                        log.error(e1.getMessage(),e1);
                        if(isCancelled())return  null;

                        end=System.currentTimeMillis();
                        Long finalEnd = end;
                        Platform.runLater(()-> {
                            AlertUtil.CustomAlert(
                                    I18n.t("common.error"),
                                    String.format(I18n.t("createconnect.error.connect_failed"), e1.getErrorCode(), e1.getMessage(), (finalEnd - start))
                            );
                            setConnectingVisible(false);
                        });
                        return null;
                    }catch (Exception e1){
                        log.error(e1.getMessage(),e1);
                        return null;
                    }finally {
                        if(connect.getConn()!=null)
                            connect.getConn().close();
                    }

                    Long finalEnd = end;
                    if(isCancelled())
                        return  null;

                    //确认提交连接
                    if(isCommit){

                        String result=LocalDbRepository.createConnectLeaf(connect);
                        if(result.equals("")){
                            Platform.runLater(()-> {
                               setConnectingVisible(false);

                                cancelButton.fire();
                            });

                            TreeItem<TreeData> treeItem=TreeViewUtil.createTreeItem(connect);


                            //判断是否为编辑连接，符合条件表示为编辑连接
                            if (treeDataParam != null && treeDataParam instanceof Connect &&!isCopy) {
                                Platform.runLater(()->{
                                    TreeItem<TreeData> currItem = new TreeItem<>();
                                    currItem = AppState.getDatabaseMetaTreeView().getSelectionModel().getSelectedItem();
                                    currItem.getParent().getChildren().remove(currItem);
                                    TreeViewUtil.createConnectLeaf(AppState.getDatabaseMetaTreeView(), treeItem);
                                    LocalDbRepository.deleteConnectLeaf((Connect) treeDataParam);//删除数据库中老节点

                                    //如果当前编辑的连接为空或已断开，不处理
                                    try {
                                        if (((Connect)currItem.getValue()).getConn() == null||((Connect)currItem.getValue()).getConn().isClosed()) {
                                        } else {//如果当前编辑的连接已连接，关闭原老节点连接后展开触发连接数据库
                                            ((Connect)currItem.getValue()).getConn().close();

                                        }
                                    } catch (SQLException e) {
                                        log.error(e.getMessage(),e);
                                    }
                                    Platform.runLater(()-> {
                                        //这里会自动连接数据库
                                        treeItem.setExpanded(true);
                                        treeItem.setExpanded(false);
                                    });
                                });

                            } else { //否则为新建连接或复制连接
                                Platform.runLater(()-> {
                                    TreeViewUtil.createConnectLeaf(AppState.getDatabaseMetaTreeView(), treeItem);
                                    //展开触发展开事件，展开事件会连接数据库，改变连接状态
                                    treeItem.setExpanded(true);
                                    //数据库连接后，默认折叠
                                    treeItem.setExpanded(false);

                                });
                            }
                            Platform.runLater(()-> {
                                TabpaneUtil.isRefreshConnectList();
                            });





                        }else{
                            AlertUtil.CustomAlert(I18n.t("common.error"), result);
                        }

                    //如果不是提交连接，那就是点击了测试连接,需要
                    }else{
                        Platform.runLater(()->{
                            AlertUtil.CustomAlert(
                                    I18n.t("common.hint"),
                                    String.format(I18n.t("createconnect.notice.test_success"), (finalEnd - start))
                            );
                            setConnectingVisible(false);
                        });
                        try {
                            if(connect.getConn()!=null)
                                connect.getConn().close();
                        } catch (SQLException e) {
                            log.error(e.getMessage(),e);
                            throw new RuntimeException(e);
                        }
                    }

                    return null;
                }
            };

            cancelButton.setOnAction(event -> {
                task.cancel();
                closeWindow();
            });
            connectingStopButton.setOnAction(event1 -> {
                //TreeViewUtil.testConnThread.interrupt();
                task.cancel();
                setConnectingVisible(false);
            });
            setTaskCancelOnClose(task::cancel);
            AppExecutor.runTask(task);

        } catch (Exception e) {
            log.error(e.getMessage(),e);
            throw new RuntimeException(e);
        }



    }
}
