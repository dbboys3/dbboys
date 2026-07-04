package com.dbboys.ui.controller;


import com.dbboys.app.AppContext;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.ui.component.CustomInfoCodeArea;
import com.dbboys.ui.component.CustomLostFocusCommitTableCell;
import com.dbboys.ui.component.CustomTableView;
import com.dbboys.ui.component.CustomUserTextField;
import com.dbboys.core.ConnectionService;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.NamedServerConnectionCapability;
import com.dbboys.core.DatabasePlatforms;
import com.dbboys.dialect.genericjdbc.GeneralJdbcDialect;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.*;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.controller.tree.TreeViewUtil;
import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.model.ConnectFolder;
import com.dbboys.model.Connect;
import com.dbboys.model.TreeData;
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
    private TabPane connectTabPane;
    @FXML
    private Tab connectBasicTab;
    @FXML
    private Tab sshTab;
    @FXML
    private CustomUserTextField sshHostTextField;
    @FXML
    private CustomUserTextField sshPortTextField;
    @FXML
    private CustomUserTextField sshUserTextField;
    @FXML
    private PasswordField sshPasswordTextField;
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
    private HBox instanceHBox;
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
    private CustomUserTextField instanceNameTextField;

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
            } else {
                connect.setDriver("");
            }
        });
        //dbtype发生变化监听，变化后设置connect的dbtype属性，并改变driver驱动列表
        dbTypeChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable,oldValue,newValue)->{
            connect.setDbtype(newValue);
            refreshDriverChoices(connect, newValue);
            applyDialectDefaults(connect, oldValue, newValue);
            refreshConnectionPropertiesForDbType(newValue, initializingDbTypeSelection && treeDataParam instanceof Connect);
            refreshDriverPropertyButton(newValue);
        });
        if (!dbtypelist.isEmpty()) {
            dbTypeChoiceBox.getSelectionModel().select("MYSQL");
            if (dbTypeChoiceBox.getSelectionModel().getSelectedIndex() < 0) {
                dbTypeChoiceBox.getSelectionModel().select(0);
            }
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
                connect.setCatalog(((Connect) treeDataParam).getCatalog());
                int j=0;
                ObservableList<String> items = dbTypeChoiceBox.getItems();
                for (String item : items) {
                    if(item.equalsIgnoreCase(((Connect) treeDataParam).getDbtype())){
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
                // applyDialectDefaults runs on db type change: if saved username equals the *previous* dialect's
                // defaultUsername (e.g. gbasedbt), it is replaced with GENERAL JDBC's empty default — restore here.
                Connect persisted = (Connect) treeDataParam;
                usernameTextField.setText(Objects.toString(persisted.getUsername(), ""));
                passwordTextField.setText(Objects.toString(persisted.getPassword(), ""));
                if (isGeneralJdbcDbType(persisted.getDbtype())
                        && usernameTextField.getText().isBlank()) {
                    String fromUrl = GeneralJdbcDialect.suggestedUsernameFromJdbcUrl(ipAddressTextField.getText());
                    if (fromUrl != null && !fromUrl.isBlank()) {
                        usernameTextField.setText(fromUrl);
                    }
                }
                // Populate SSH fields if editing a connection with SSH enabled
                sshHostTextField.setText(Objects.toString(persisted.getSshHost(), ""));
                sshPortTextField.setText(Objects.toString(persisted.getSshPort(), "22"));
                sshUserTextField.setText(Objects.toString(persisted.getSshUser(), ""));
                sshPasswordTextField.setText(Objects.toString(persisted.getSshPassword(), ""));
                if (persisted.getSshEnabled() != null && persisted.getSshEnabled()) {
                    connectTabPane.getSelectionModel().select(sshTab);
                }
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
        refreshInstanceField(dbTypeChoiceBox.getValue(), connect.getCatalog());
        applyTextFormatters();

        // Bind i18n tab titles
        connectBasicTab.textProperty().bind(I18n.bind("createconnect.tab.basic", "数据库连接"));
        sshTab.textProperty().bind(I18n.bind("createconnect.tab.ssh", "SSH 隧道"));

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
            connect.setPort(isGeneralJdbcDbType(connect.getDbtype()) ? "" : portTextField.getText());
            if (connectNameTextField.getText().isEmpty()) {
                if (isGeneralJdbcDbType(connect.getDbtype())) {
                    String fromUrl = GeneralJdbcDialect.suggestedHostPortLabelFromJdbcUrl(ipAddressTextField.getText());
                    if (fromUrl != null && !fromUrl.isBlank()) {
                        connect.setName("[" + fromUrl + "]");
                    } else {
                        connect.setName("[" + connect.getIp() + "]");
                    }
                } else {
                    connect.setName("[" + connect.getIp() + "_" + connect.getPort() + "]");
                }
            } else {
                connect.setName(connectNameTextField.getText());
            }
        }

        if (isGeneralJdbcDbType(connect.getDbtype())) {
            connect.setCatalog("");
        } else if (isOracleDbType(connect.getDbtype())) {
            String serviceName = instanceNameTextField == null ? "" : instanceNameTextField.getText();
            if (serviceName == null || serviceName.isBlank()) {
                connect.setCatalog(defaultDatabaseFor(connect.getDbtype()));
            } else {
                connect.setCatalog(serviceName.trim());
            }
        } else if (connect.getCatalog() == null || connect.getCatalog().isBlank()) {
            connect.setCatalog(defaultDatabaseFor(connect.getDbtype()));
        }
        String username = usernameTextField.getText();
        if (isOracleDbType(connect.getDbtype()) && username != null) {
            username = username.toUpperCase();
        }
        connect.setUsername(username);
        connect.setPassword(passwordTextField.getText());
        connect.setReadonly(readOnlyCheckBox.isSelected());

        // SSH: enabled if any field is filled, regardless of which tab is active
        boolean hasSshConfig = !sshHostTextField.getText().isBlank()
                || !sshUserTextField.getText().isBlank()
                || !sshPasswordTextField.getText().isBlank();
        connect.setSshEnabled(hasSshConfig);
        connect.setSshHost(sshHostTextField.getText());
        connect.setSshPort(sshPortTextField.getText());
        connect.setSshUser(sshUserTextField.getText());
        connect.setSshPassword(sshPasswordTextField.getText());
    }
    public boolean checkInput(){
        if(isOracleDbType(dbTypeChoiceBox.getValue())){
            if(instanceNameTextField == null || instanceNameTextField.getText().isBlank()){
                instanceNameTextField.requestFocus();
                return false;
            }
        }
        if(driverChoiceBox.getValue()==null || driverChoiceBox.getValue().isBlank()){
            AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("createconnect.error.driver_required", "请先选择或添加驱动程序！"));
            driverChoiceBox.requestFocus();
            return false;
        }
        if(groupHbox.isVisible()){
                if(groupTextField.getText().isEmpty()){
                    groupTextField.requestFocus();
                    return false;
                }
            }else 
                {
                    if(ipAddressTextField.getText().isEmpty()){
                        ipAddressTextField.requestFocus();
                        return false;
                    }
                    else if(!isGeneralJdbcDbType(dbTypeChoiceBox.getValue()) && portTextField.getText().isEmpty()){
                        portTextField.requestFocus();
                        return false;
                    }
                }
            if(usernameTextField.getText().isEmpty()){
                usernameTextField.requestFocus();
                return false;
            }
            else if(passwordTextField.getText().isEmpty()){
                passwordTextField.requestFocus();
                return false;
            }
            return true;
    }

    private void applyDialectDefaults(Connect connect, String oldDbType, String newDbType) {
        DatabasePlatform oldDialect = resolvePlatformResolver().getPlatform(oldDbType);
        DatabasePlatform newDialect = resolvePlatformResolver().getPlatform(newDbType);
        if (newDialect == null) {
            return;
        }
        switchGroupOrIP.setVisible(supportsNamedServerConnection(newDialect));
        if (!supportsNamedServerConnection(newDialect)) {
            groupHbox.setVisible(false);
        }
        if (shouldReplaceField(portTextField.getText(), oldDialect == null ? null : oldDialect.connection().defaultPort())) {
            portTextField.setText(newDialect.connection().defaultPort());
        }
        if (shouldReplaceField(usernameTextField.getText(), oldDialect == null ? null : oldDialect.connection().defaultUsername())) {
            usernameTextField.setText(newDialect.connection().defaultUsername());
        }
        if (connect.getCatalog() == null
                || connect.getCatalog().isBlank()
                || (oldDialect != null && connect.getCatalog().equalsIgnoreCase(oldDialect.connection().defaultDatabase()))) {
            connect.setCatalog(newDialect.connection().defaultDatabase());
        }
        refreshInstanceField(newDbType, connect.getCatalog());
    }

    private void refreshConnectionPropertiesForDbType(String newDbType, boolean preserveExistingProps) {
        if (preserveExistingProps) {
            return;
        }
        props = defaultConnectionPropsFor(newDbType);
    }

    private void refreshDriverPropertyButton(String dbType) {
        DatabasePlatform dialect = resolvePlatformResolver().getPlatform(dbType);
        boolean supported = dialect != null && dialect.connection().supportsConnectionProperties();
        modifyDriverButton.setDisable(!supported);
    }

    private String defaultConnectionPropsFor(String dbType) {
        DatabasePlatform dialect = resolvePlatformResolver().getPlatform(dbType);
        if (dialect == null) {
            return EMPTY_PROPS;
        }
        String propsJson = dialect.connection().defaultConnectionProps();
        return propsJson == null || propsJson.isBlank() ? EMPTY_PROPS : propsJson;
    }

    private boolean shouldReplaceField(String currentValue, String oldDefault) {
        return currentValue == null
                || currentValue.isBlank()
                || (oldDefault != null && !oldDefault.isBlank() && currentValue.equalsIgnoreCase(oldDefault));
    }

    private String defaultDatabaseFor(String dbType) {
        DatabasePlatform dialect = resolvePlatformResolver().getPlatform(dbType);
        if (dialect == null) {
            return "";
        }
        return dialect.connection().defaultDatabase();
    }

    private DatabasePlatformResolver resolvePlatformResolver() {
        try {
            return AppContext.get(DatabasePlatformResolver.class);
        } catch (IllegalStateException e) {
            return DatabasePlatforms.createDefault();
        }
    }

    private List<String> loadAvailableDbTypes() {
        Set<String> registeredDbTypes = new HashSet<>();
        for (DatabasePlatform platform : resolvePlatformResolver().allPlatforms()) {
            if (platform != null && platform.getDbType() != null && !platform.getDbType().isBlank()) {
                registeredDbTypes.add(platform.getDbType());
            }
        }
        List<String> dbtypes = new ArrayList<>(registeredDbTypes);
        dbtypes.sort(String.CASE_INSENSITIVE_ORDER);
        return dbtypes;
    }

    private void refreshDriverChoices(Connect connect, String dbType) {
        List<String> driverList = loadDriversForDbType(dbType);
        ObservableList<String> driverItems = FXCollections.observableArrayList(driverList);
        driverChoiceBox.setItems(driverItems);
        if (!driverItems.isEmpty()) {
            driverChoiceBox.getSelectionModel().select(driverItems.size() - 1);
            if (connect != null) {
                connect.setDriver(driverChoiceBox.getValue());
            }
        } else {
            driverChoiceBox.getSelectionModel().clearSelection();
            if (connect != null) {
                connect.setDriver("");
            }
        }
    }

    private List<String> loadDriversForDbType(String dbType) {
        List<String> driverList = new ArrayList<>();
        File driverFolder = resolveDriverFolder(dbType);
        File[] driverFiles = driverFolder.listFiles();
        if (driverFiles == null) {
            return driverList;
        }
        for (File file : driverFiles) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                driverList.add(file.getName());
            }
        }
        driverList.sort(String.CASE_INSENSITIVE_ORDER);
        return driverList;
    }

    private File ensureDriverFolder(String dbType) throws IOException {
        File driverFolder = resolveDriverFolder(dbType);
        Files.createDirectories(driverFolder.toPath());
        return driverFolder;
    }

    private File resolveDriverFolder(String dbType) {
        File extlibFolder = new File("extlib");
        File exactFolder = new File(extlibFolder, dbType == null ? "" : dbType);
        if (exactFolder.exists()) {
            return exactFolder;
        }
        File[] dbTypeFolders = extlibFolder.listFiles();
        if (dbTypeFolders != null && dbType != null) {
            for (File file : dbTypeFolders) {
                if (file.isDirectory() && file.getName().equalsIgnoreCase(dbType)) {
                    return file;
                }
            }
        }
        return exactFolder;
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
        connect.setCatalog(template.getCatalog());
        refreshInstanceField(template.getDbtype(), template.getCatalog());
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
            if (value.equalsIgnoreCase(items.get(i))) {
                choiceBox.getSelectionModel().select(i);
                return true;
            }
        }
        return false;
    }

    private String namedServerPropNameFor(String dbType) {
        DatabasePlatform platform = resolvePlatformResolver().getPlatform(dbType);
        String propName = platform == null ? null : platform.capability(NamedServerConnectionCapability.class)
                .map(NamedServerConnectionCapability::namedServerPropertyName)
                .orElse(null);
        return propName == null ? "" : propName;
    }

    private boolean supportsNamedServerConnection(DatabasePlatform platform) {
        return platform != null && platform.capability(NamedServerConnectionCapability.class).isPresent();
    }

    public void initialize() throws IOException {
        setupInstanceField();
        setupIcons();
    }

    private void setupIcons() {
        // Label icons removed per user request — keep only button icons
        addDriverButton.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_ADD_DRIVER, 0.7));
        deleteDriverButton.setGraphic(IconFactory.group(IconPaths.CREATE_CONNECT_REMOVE_DRIVER, 0.6));
        modifyDriverButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.6));
        modifyGroupButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.6));
        switchGroupOrIP.setGraphic(IconFactory.group(IconPaths.MAIN_REBUILD, 0.7));

        connectingLoadingImageView.setImage(new Image(IconPaths.LOADING_GIF));
        connectingStopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.7, IconFactory.stopColor()));
    }

    private void setupInstanceField() {
        instanceNameTextField = new CustomUserTextField();
        instanceNameTextField.setPrefWidth(70);

        if (instanceHBox != null) {
            instanceHBox.getChildren().setAll(instanceNameTextField);
            instanceHBox.managedProperty().bind(instanceHBox.visibleProperty());
            instanceHBox.setVisible(false);
        }
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
        if (instanceNameTextField != null) {
            instanceNameTextField.setTextFormatter(new TextFormatter<String>(change -> {
                String text = change.getText();
                if (text != null && text.contains(" ")) {
                    change.setText(text.replace(" ", ""));
                }
                return change;
            }));
        }
        portTextField.setTextFormatter(new TextFormatter<String>(change -> {
            String newText = change.getControlNewText();
            return newText.matches("\\d*") ? change : null;
        }));
    }

    private void refreshInstanceField(String dbType, String databaseValue) {
        if (instanceHBox == null || instanceNameTextField == null) {
            return;
        }
        if (isOracleDbType(dbType)) {
            instanceNameTextField.setPromptText(I18n.t("createconnect.prompt.service_name", "Oracle服务名，例如 ORCLPDB"));
            instanceNameTextField.setText(databaseValue == null ? "" : databaseValue);
            instanceHBox.setVisible(true);
        } else {
            instanceNameTextField.setText(databaseValue == null ? "" : databaseValue);
            instanceHBox.setVisible(false);
        }
        refreshConnectionInputMode(dbType);
    }

    private boolean isOracleDbType(String dbType) {
        return "ORACLE".equalsIgnoreCase(dbType);
    }

    private boolean isGeneralJdbcDbType(String dbType) {
        return "GENERAL JDBC".equalsIgnoreCase(dbType);
    }

    private void refreshConnectionInputMode(String dbType) {
        boolean generalJdbc = isGeneralJdbcDbType(dbType);
        if (ipAddressLabel != null) {
            ipAddressLabel.setText(generalJdbc
                    ? I18n.t("createconnect.label.jdbc_url", "JDBC URL")
                    : I18n.t("createconnect.label.ip", "IP"));
        }
        if (ipAddressTextField != null) {
            ipAddressTextField.setPromptText(generalJdbc
                    ? I18n.t("createconnect.prompt.jdbc_url", "例如 jdbc:postgresql://127.0.0.1:5432/postgres")
                    : I18n.t("createconnect.prompt.ip", "IP"));
            ipAddressTextField.setPrefWidth(generalJdbc ? 315 : 200);
        }
        if (portLabel != null) {
            portLabel.setVisible(!generalJdbc);
            portLabel.setManaged(!generalJdbc);
        }
        if (portTextField != null) {
            portTextField.setVisible(!generalJdbc);
            portTextField.setManaged(!generalJdbc);
            if (generalJdbc) {
                portTextField.clear();
            }
        }
        if (switchGroupOrIP != null) {
            boolean showSwitch = !generalJdbc && supportsNamedServerConnection(resolvePlatformResolver().getPlatform(dbType));
            switchGroupOrIP.setVisible(showSwitch);
            switchGroupOrIP.setManaged(showSwitch);
        }
        if (generalJdbc && groupHbox != null) {
            groupHbox.setVisible(false);
        }
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
            File initialDirectory = resolveInitialFileChooserDirectory();
            if (initialDirectory != null) {
                fileChooser.setInitialDirectory(initialDirectory);
            }
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
                    Boolean md5same=false;
                    String sourceSamename=null;
                    try {
                        Path targetPath = ensureDriverFolder(dbTypeChoiceBox.getValue()).toPath().resolve(selectedFile.getName());
                        String sourceMd5=MD5Util.getMD5Checksum(sourcePath.toFile().getAbsolutePath());
                        String targetMd5=null;
                        ObservableList<String> drivers = driverChoiceBox.getItems();
                        for (String driver : drivers) {
                            targetMd5=MD5Util.getMD5Checksum(resolveDriverFolder(dbTypeChoiceBox.getValue()).toPath().resolve(driver).toFile().getAbsolutePath());
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
        private File resolveInitialFileChooserDirectory() {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isBlank()) {
                File desktop = new File(userHome, "Desktop");
                if (desktop.isDirectory()) {
                    return desktop;
                }
                File home = new File(userHome);
                if (home.isDirectory()) {
                    return home;
                }
            }
            File workingDirectory = new File(".");
            return workingDirectory.isDirectory() ? workingDirectory.getAbsoluteFile() : null;
        }

        public void deleteDriverClicked(){
            if(driverChoiceBox.getItems().isEmpty() || driverChoiceBox.getValue() == null){
                return;
            }
            if(driverChoiceBox.getItems().size()<=1){
                AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("createconnect.error.driver_last_one"));
            }else{
                String currItem = driverChoiceBox.getValue();
                File file = resolveDriverFolder(dbTypeChoiceBox.getValue()).toPath().resolve(currItem).toFile();
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
        nameColumn.setCellFactory(col -> new CustomLostFocusCommitTableCell<ObservableList<String>, Object>() {
            @Override
            public void startEdit() {
                TableRow<ObservableList<String>> tableRow = getTableRow();
                if (tableRow == null || tableRow.getItem() == null) {
                    return;
                }
                ObservableList<String> row = tableRow.getItem();
                if (row.size() <= 0 || !"true".equals(row.get(0))) {
                    return;
                }
                super.startEdit();
            }

            @Override
            public void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                updateCustomPropStyle(this, empty);
            }
        });
        nameColumn.setCellValueFactory(data -> Bindings.createObjectBinding(() -> data.getValue().get(1)));
        nameColumn.setReorderable(false); // 禁用拖动
        nameColumn.setEditable(true);
        nameColumn.setPrefWidth(220);
        nameColumn.setOnEditCommit(event -> {
            ObservableList<String> rowData = event.getRowValue();
            Object newValue = event.getNewValue();
            if (rowData.size() > 1 && newValue != null) {
                rowData.set(1, newValue.toString());
            }
            tableView.refresh();
        });
        TableColumn<ObservableList<String>, Object> valueColumn = new TableColumn<ObservableList<String>, Object>(I18n.t("createconnect.table.prop_value"));
        valueColumn.setCellFactory(col -> new CustomLostFocusCommitTableCell<ObservableList<String>, Object>() {
            @Override
            public void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                updateCustomPropStyle(this, empty);
            }
        });
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
        tableView.setRowFactory(tv -> {
            TableRow<ObservableList<String>> row = new TableRow<>();
            row.itemProperty().addListener((obs, oldItem, newItem) -> {
                boolean isCustom = newItem != null && newItem.size() > 0 && "true".equals(newItem.get(0));
                if (isCustom) {
                    if (!row.getStyleClass().contains("custom-prop-row")) {
                        row.getStyleClass().add("custom-prop-row");
                    }
                } else {
                    row.getStyleClass().remove("custom-prop-row");
                }
            });
            return row;
        });
        VBox contentBox = new VBox();
        contentBox.setId("modifyProps");
        contentBox.setAlignment(Pos.TOP_LEFT);
        contentBox.getChildren().add(tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);
        tableView.setMaxHeight(Double.MAX_VALUE);

        HBox addButtonBar = new HBox();
        addButtonBar.setAlignment(Pos.CENTER_LEFT);
        addButtonBar.setSpacing(6);
        addButtonBar.setPadding(new javafx.geometry.Insets(6, 0, 0, 0));
        Button addPropButton = new Button(I18n.t("createconnect.button.add_prop"));
        addPropButton.getStyleClass().add("small");
        addPropButton.setOnAction(event -> {
            ObservableList<String> newRow = FXCollections.observableArrayList();
            newRow.add("true");
            newRow.add("");
            newRow.add("");
            datalist.add(newRow);
            int newRowIndex = datalist.size() - 1;
            javafx.application.Platform.runLater(() -> {
                tableView.getSelectionModel().clearAndSelect(newRowIndex);
                tableView.scrollTo(newRowIndex);
                tableView.edit(newRowIndex, nameColumn);
            });
        });

        Button delPropButton = new Button(I18n.t("createconnect.button.del_prop"));
        delPropButton.getStyleClass().add("small");
        delPropButton.setOnAction(event -> {
            ObservableList<Integer> selectedIndices = tableView.getSelectionModel().getSelectedIndices();
            if (selectedIndices.isEmpty()) {
                return;
            }
            // 从后往前删除，避免索引偏移问题
            List<Integer> sortedIndices = new ArrayList<>(selectedIndices);
            sortedIndices.sort(Comparator.reverseOrder());
            for (int idx : sortedIndices) {
                if (idx >= 0 && idx < datalist.size()) {
                    datalist.remove(idx);
                }
            }
            tableView.refresh();
            javafx.application.Platform.runLater(() -> {
                if (datalist.size() > 0) {
                    int selectIdx = Math.min(sortedIndices.get(sortedIndices.size() - 1), datalist.size() - 1);
                    tableView.getSelectionModel().clearAndSelect(selectIdx);
                }
            });
        });
        addButtonBar.getChildren().addAll(addPropButton, delPropButton);
        contentBox.getChildren().add(addButtonBar);

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
            String propName = row.get(1);
            // 丢弃名称为空的属性（用户添加但未填写的自定义属性）
            if (propName == null || propName.isBlank()) {
                continue;
            }
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("propName", propName);
            jsonObject.put("propValue", row.get(2));
            jsonArray.put(jsonObject);
        }
        props=jsonArray.toString();
    }

    private static void updateCustomPropStyle(javafx.scene.control.TableCell<?, ?> cell, boolean empty) {
        if (empty) {
            cell.getStyleClass().remove("custom-prop-row");
            return;
        }
        TableRow<?> tableRow = cell.getTableRow();
        if (tableRow == null || tableRow.getItem() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        ObservableList<String> rowData = (ObservableList<String>) tableRow.getItem();
        boolean isCustom = rowData.size() > 0 && "true".equals(rowData.get(0));
        if (isCustom) {
            if (!cell.getStyleClass().contains("custom-prop-row")) {
                cell.getStyleClass().add("custom-prop-row");
            }
        } else {
            cell.getStyleClass().remove("custom-prop-row");
        }
    }

    private ObservableList<ObservableList<String>> buildDriverPropRows(String propsJson) {
        JSONArray jsonArray = new JSONArray(
                propsJson == null || propsJson.isBlank() ? defaultConnectionPropsFor(dbTypeChoiceBox.getValue()) : propsJson
        );
        Set<String> defaultPropNames = buildDefaultPropNameSet();
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            String propName = jsonObject.getString("propName");
            ObservableList<String> row = FXCollections.observableArrayList();
            boolean isCustom = !defaultPropNames.contains(propName);
            row.add(isCustom ? "true" : null);
            row.add(propName);
            row.add(jsonObject.getString("propValue"));
            rows.add(row);
        }
        return rows;
    }

    /** Returns the set of property names from the current dialect's defaultConnectionProps. */
    private Set<String> buildDefaultPropNameSet() {
        Set<String> names = new java.util.HashSet<>();
        JSONArray defaults = new JSONArray(defaultConnectionPropsFor(dbTypeChoiceBox.getValue()));
        for (int i = 0; i < defaults.length(); i++) {
            names.add(defaults.getJSONObject(i).getString("propName"));
        }
        return names;
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
                        if(isCancelled())return  null;

                        end=System.currentTimeMillis();
                        Long finalEnd = end;
                        Platform.runLater(()-> {
                            AlertUtil.CustomAlert(
                                    I18n.t("common.error"),
                                    String.format(I18n.t("createconnect.error.connect_failed"), "", e1.getMessage(), (finalEnd - start))
                            );
                            setConnectingVisible(false);
                        });
                        return null;
                    }finally {
                        if(connect.getConn()!=null)
                            connect.getConn().close();
                        connect.setConn(null);
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
