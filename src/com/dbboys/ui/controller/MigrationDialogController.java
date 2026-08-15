package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.MetadataRepository;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.model.Database;
import com.dbboys.model.MigrationObjectRef;
import com.dbboys.model.MigrationTask;
import com.dbboys.model.Schema;
import com.dbboys.model.TreeData;
import com.dbboys.service.BackgroundSqlService;
import com.dbboys.service.migration.MigrationConnectInfo;
import com.dbboys.service.migration.TableMapping;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.dialog.CustomWindowFrameUtil;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据迁移任务编辑器对话框（纯代码范式，参照 {@link SshConnectDialogController}）。
 * <p>
 * 一个任务只迁一个源库/模式：三层结构（DATABASE_SCHEMA）选一个库下的一个模式，
 * 两层结构选一个库（DATABASE）或一个模式（SCHEMA，源库位置显示模式列表）。
 * 源范围选定后下方按对象类型列出数量，用户勾选要迁移的类型；
 * 每种类型默认"全部"，可"自定义"挑选具体对象。
 * "迁移数据"旁的"编辑"按钮弹出自定义数据映射对话框（见 {@link MigrationMappingDialogController}）。
 * <p>
 * 编辑结果只写回传入（或新建的）{@link MigrationTask}，不落库，由调用方持久化。
 * 对象 ref 定位约定：DATABASE 模型 catalog=库名、schema=null；SCHEMA 模型 catalog=模式名、
 * schema=null；DATABASE_SCHEMA 模型 catalog=库名、schema=模式名。
 * 元数据读取全部走独立复制 Connect 会话，不用主树连接。
 */
public class MigrationDialogController {
    private static final Logger log = LogManager.getLogger(MigrationDialogController.class);
    private static final double DIALOG_W = 680;
    private static final double DIALOG_H = 535;

    private Stage dialogStage;
    private TextField nameField;
    private ChoiceBox<Connect> sourceChoiceBox;
    private ChoiceBox<Connect> targetChoiceBox;
    private ChoiceBox<String> sourceDbChoiceBox;
    private ChoiceBox<String> sourceSchemaChoiceBox;
    private ChoiceBox<String> targetDbChoiceBox;
    private ChoiceBox<String> targetSchemaChoiceBox;
    private Label sourceDbLabel;
    private Label sourceSchemaLabel;
    private Label targetDbLabel;
    private Label targetSchemaLabel;
    // 源/目标数据库类型（+sqlmode）显示：dbtype 即时上屏，sqlmode 后台探测补齐
    private Label sourceDbTypeValue;
    private Label targetDbTypeValue;
    private int sourceDbTypeProbeGeneration;
    private int targetDbTypeProbeGeneration;
    private CheckBox ddlCheckBox;
    private CheckBox dataCheckBox;
    private CheckBox truncateCheckBox;
    private CheckBox overwriteCheckBox;
    private CheckBox tableCheckBox;
    private TextField readThreadCountField;
    private TextField writeThreadCountField;
    private Button editMappingButton;
    private VBox typeRowsBox;

    /** 各支持对象类型的选择状态（随源连接/平台重建）。 */
    private final List<TypeSelection> typeSelections = new ArrayList<>();
    /** 自定义数据映射（表名→映射），编辑回显自 task，保存时写回。 */
    private Map<String, TableMapping> mappings = new LinkedHashMap<>();

    /** 正在编辑的任务；null 表示新建。 */
    private MigrationTask editingTask;
    /** 保存结果（startRequested 区分 保存/保存并启动）；取消为 null。 */
    private MigrationTask resultTask;
    private boolean startRequested;
    /** 初始回显期间不清理 echo 状态。 */
    private boolean applyingEcho;
    /** 编辑回显的源/目标库模式（优先于"与连接同名"的默认选中）。 */
    private String echoSourceCatalog;
    private String echoSourceSchema;
    private String echoTargetDatabase;
    private String echoTargetSchema;
    /** 当前自定义挑选所属的源范围 key（scope 变更后旧挑选失效）。 */
    private String picksScopeKey;
    /** 数量加载代数：丢弃过期的后台回调。 */
    private int countGeneration;

    /**
     * 打开任务编辑器。taskOrNull=null 为新建；返回配置好的任务（不落库，由调用方持久化），
     * 取消/关闭返回 null。点了"保存并启动"时 {@link #isStartRequested()} 为 true。
     */
    public MigrationTask showAndWait(MigrationTask taskOrNull, List<Connect> connects) {
        editingTask = taskOrNull;
        resultTask = null;
        startRequested = false;
        mappings = new LinkedHashMap<>();
        picksScopeKey = null;

        dialogStage = new Stage();
        DialogPane dialogPane = buildDialogPane(connects == null ? List.of() : connects);
        int titleBarHeight = 28;
        int contentH = (int) DIALOG_H - titleBarHeight;
        dialogPane.setMinSize(DIALOG_W, contentH);
        dialogPane.setPrefSize(DIALOG_W, contentH);
        dialogPane.setMaxSize(DIALOG_W, contentH);
        CustomWindowFrameUtil.createModalPopup(
                dialogStage,
                I18n.bind("migration.title", "Data Migration"),
                dialogPane,
                DIALOG_W,
                DIALOG_H,
                false);
        dialogStage.setResizable(false);
        dialogStage.sizeToScene();

        Window owner = AppState.getWindow();
        if (owner != null && owner.isShowing()) {
            dialogStage.setX(owner.getX() + (owner.getWidth() - DIALOG_W) / 2);
            dialogStage.setY(owner.getY() + (owner.getHeight() - DIALOG_H) / 2);
        }
        dialogStage.showAndWait();
        return resultTask;
    }

    /** 是否点了"保存并启动"（仅在 {@link #showAndWait} 返回非 null 时有意义）。 */
    public boolean isStartRequested() {
        return startRequested;
    }

    /** 是否新建任务（未持久化过）：新建入口传入的是 id=0 的新对象，编辑入口为已持久化任务。 */
    private boolean isNewTask() {
        return editingTask == null || editingTask.getId() == 0;
    }

    // ==================================================================
    // UI 构建
    // ==================================================================

    private DialogPane buildDialogPane(List<Connect> connects) {
        DialogPane dialogPane = new DialogPane();
        dialogPane.setHeader(null);
        VBox content = new VBox(8);
        content.setPadding(new Insets(10, 14, 10, 14));

        // ---- 顶部表单 ----
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(6);

        nameField = new TextField();
        nameField.setPrefWidth(260);
        sourceChoiceBox = connectChoiceBox();
        targetChoiceBox = connectChoiceBox();
        sourceDbChoiceBox = stringChoiceBox();
        sourceSchemaChoiceBox = stringChoiceBox();
        targetDbChoiceBox = stringChoiceBox();
        targetSchemaChoiceBox = stringChoiceBox();

        sourceChoiceBox.setItems(FXCollections.observableArrayList(connects));
        targetChoiceBox.setItems(FXCollections.observableArrayList(connects));

        Label nameLabel = boundLabel("migration.task.name", "Task Name");
        Label sourceLabel = boundLabel("migration.label.source_connection", "Source Connection");
        Label targetLabel = boundLabel("migration.label.target_connection", "Target Connection");
        // 源连接/目标连接的说明 label 加宽 10px（输入框相应收窄 10px，见 connectChoiceBox）
        sourceLabel.setPadding(new Insets(0, 10, 0, 0));
        targetLabel.setPadding(new Insets(0, 10, 0, 0));
        sourceDbLabel = boundLabel("migration.label.source_database", "Source Database");
        sourceSchemaLabel = boundLabel("migration.label.source_schema", "Source Schema");
        targetDbLabel = boundLabel("migration.label.target_database", "Target Database");
        targetSchemaLabel = boundLabel("migration.label.target_schema", "Target Schema");

        form.add(nameLabel, 0, 0);
        form.add(nameField, 1, 0, 3, 1);
        form.add(sourceLabel, 0, 1);
        form.add(sourceChoiceBox, 1, 1);
        form.add(targetLabel, 2, 1);
        form.add(targetChoiceBox, 3, 1);
        form.add(sourceDbLabel, 0, 2);
        form.add(sourceDbChoiceBox, 1, 2);
        form.add(targetDbLabel, 2, 2);
        form.add(targetDbChoiceBox, 3, 2);
        form.add(sourceSchemaLabel, 0, 3);
        form.add(sourceSchemaChoiceBox, 1, 3);
        form.add(targetSchemaLabel, 2, 3);
        form.add(targetSchemaChoiceBox, 3, 3);

        // ---- 源/目标数据库类型（模式行下方；有 sqlmode 的平台探测后一并显示）----
        Label sourceDbTypeLabel = boundLabel("migration.label.source_dbtype", "Source DB Type");
        Label targetDbTypeLabel = boundLabel("migration.label.target_dbtype", "Target DB Type");
        sourceDbTypeValue = new Label();
        targetDbTypeValue = new Label();
        form.add(sourceDbTypeLabel, 0, 4);
        form.add(sourceDbTypeValue, 1, 4);
        form.add(targetDbTypeLabel, 2, 4);
        form.add(targetDbTypeValue, 3, 4);

        // ---- 中部：对象类型选择 ----
        Label objectsLabel = boundLabel("migration.label.objects", "Objects to Migrate");
        typeRowsBox = new VBox(6);
        typeRowsBox.setPadding(new Insets(4, 0, 0, 12));

        // ---- 选项（对象列表下方）----
        Label optionsLabel = boundLabel("migration.label.options", "Options");
        ddlCheckBox = boundCheckBox("migration.check.ddl", "Migrate table structure (DDL)", true);
        dataCheckBox = boundCheckBox("migration.check.data", "Migrate data", true);
        // 默认勾选：清空表、覆盖目标已存在的对象
        truncateCheckBox = boundCheckBox("migration.check.truncate", "Clear table", true);
        overwriteCheckBox = boundCheckBox("migration.check.overwrite", "Overwrite existing tables", true);
        editMappingButton = new Button();
        editMappingButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.7));
        editMappingButton.getStyleClass().add("small");
        Tooltip mappingTip = new Tooltip();
        mappingTip.textProperty().bind(I18n.bind("migration.mapping.edit", "Edit"));
        editMappingButton.setTooltip(mappingTip);
        editMappingButton.setOnAction(e -> openMappingDialog());
        Label readThreadCountLabel = boundLabel("migration.label.read_threads", "Read threads");
        Label writeThreadCountLabel = boundLabel("migration.label.write_threads", "Write threads");
        // 读/写线程数用普通输入框：默认 10，仅允许数字，保存时解析（非法输入回退 10）
        readThreadCountField = new TextField("10");
        readThreadCountField.setPrefWidth(80);
        readThreadCountField.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d*") ? change : null));
        writeThreadCountField = new TextField("10");
        writeThreadCountField.setPrefWidth(80);
        writeThreadCountField.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d*") ? change : null));
        HBox optionsRow = new HBox(14, overwriteCheckBox, readThreadCountLabel, readThreadCountField,
                writeThreadCountLabel, writeThreadCountField);
        optionsRow.setPadding(new Insets(0, 0, 0, 12));
        optionsRow.setAlignment(Pos.CENTER_LEFT);
        VBox optionsBox = new VBox(6, optionsLabel, optionsRow);
        optionsBox.setPadding(new Insets(6, 0, 0, 0));

        VBox middleBox = new VBox(6, objectsLabel, typeRowsBox, optionsBox);
        VBox.setVgrow(middleBox, Priority.ALWAYS);
        // 类型行与"迁移对象"之间多加 5px 间距
        VBox.setMargin(middleBox, new Insets(5, 0, 0, 0));

        // ---- 底部按钮：保存(accent) / 保存并启动 / 关闭 ----
        content.getChildren().addAll(form, middleBox);
        dialogPane.setContent(content);

        // ---- 联动 ----
        sourceChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (!applyingEcho) {
                echoSourceCatalog = null;
                echoSourceSchema = null;
            }
            reloadSourceCatalogs();
        });
        sourceDbChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            Connect source = sourceChoiceBox.getValue();
            if (source != null && n != null && isCatalogSchemaModel(source)) {
                loadSourceSchemas(n);
            }
            reloadTypeCounts();
        });
        sourceSchemaChoiceBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> reloadTypeCounts());
        targetChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (!applyingEcho) {
                echoTargetDatabase = null;
                echoTargetSchema = null;
            }
            reloadTargetCatalogs();
            refreshMappingEditButton();
        });
        targetDbChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            Connect target = targetChoiceBox.getValue();
            if (target != null && n != null && isCatalogSchemaModel(target)) {
                loadTargetSchemas(n);
            }
        });
        dataCheckBox.selectedProperty().addListener((obs, o, n) -> refreshMappingEditButton());
        sourceChoiceBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> refreshSourceDbTypeLabel());
        targetChoiceBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> refreshTargetDbTypeLabel());

        rebuildTypeRows(null);
        applyInitialState(connects);
        refreshMappingEditButton();
        wireDialogButtons(dialogPane);
        return dialogPane;
    }

    private void wireDialogButtons(DialogPane dialogPane) {
        ButtonType saveType = new ButtonType(
                I18n.t("migration.button.save", "Save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType closeType = new ButtonType(
                I18n.t("migration.button.close", "Close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(saveType, closeType);

        Button saveButton = (Button) dialogPane.lookupButton(saveType);
        saveButton.getStyleClass().add("accent");
        saveButton.setOnAction(e -> save(false));

        Button closeButton = (Button) dialogPane.lookupButton(closeType);
        closeButton.setCancelButton(true);
        closeButton.setOnAction(e -> dialogStage.close());
    }

    /** 新建默认选中 / 编辑回显。 */
    private void applyInitialState(List<Connect> connects) {
        if (editingTask == null) {
            if (!connects.isEmpty()) {
                sourceChoiceBox.getSelectionModel().select(0);
                targetChoiceBox.getSelectionModel().select(connects.size() > 1 ? 1 : 0);
            }
            return;
        }
        applyingEcho = true;
        try {
            nameField.setText(editingTask.getName());
            // 先解析 refs 得出源范围/类型回显值，再选连接（选择会同步重建类型行、异步加载库列表）
            List<MigrationObjectRef> refs = editingTask.getObjectRefs();
            MigrationObjectRef first = refs.isEmpty() ? null : refs.get(0);
            echoSourceCatalog = first == null ? null : first.catalog();
            echoSourceSchema = first == null ? null : first.schema();
            picksScopeKey = scopeKey(echoSourceCatalog, echoSourceSchema);
            echoTargetDatabase = editingTask.getTargetDatabase();
            echoTargetSchema = editingTask.getTargetSchema();
            mappings = new LinkedHashMap<>(editingTask.getMappings());
            ddlCheckBox.setSelected(editingTask.isMigrateDdl());
            dataCheckBox.setSelected(editingTask.isMigrateData());
            truncateCheckBox.setSelected(editingTask.isTruncateTable());
            overwriteCheckBox.setSelected(editingTask.isOverwrite());
            readThreadCountField.setText(String.valueOf(Math.max(1, editingTask.getReadThreadCount())));
            writeThreadCountField.setText(String.valueOf(Math.max(1, editingTask.getWriteThreadCount())));
            selectConnectById(sourceChoiceBox, editingTask.getSourceId());
            selectConnectById(targetChoiceBox, editingTask.getTargetId());
            // 类型行已在源连接选择监听器里同步重建，这里按 refs 勾选/恢复自定义集
            for (MigrationObjectRef ref : refs) {
                if (ref == null) {
                    continue;
                }
                if (ref.kind() == MigrationObjectRef.Kind.ALL) {
                    // 旧版整节点通配：升级为全部类型勾选 + 全部范围
                    for (TypeSelection ts : typeSelections) {
                        ts.checkBox.setSelected(true);
                        ts.custom = false;
                        ts.customObjects.clear();
                    }
                    continue;
                }
                TypeSelection ts = typeSelectionFor(ref.kind());
                if (ts == null) {
                    continue;
                }
                ts.checkBox.setSelected(true);
                if (ref.name() == null) {
                    ts.custom = false;
                    ts.customObjects.clear();
                } else {
                    ts.custom = true;
                    ts.customObjects.put(ref.name(), slotValue(ref));
                }
            }
        } finally {
            applyingEcho = false;
        }
    }

    private static void selectConnectById(ChoiceBox<Connect> choiceBox, int connectId) {
        for (Connect connect : choiceBox.getItems()) {
            if (connect != null && connect.getId() == connectId) {
                choiceBox.getSelectionModel().select(connect);
                return;
            }
        }
    }

    private static ChoiceBox<Connect> connectChoiceBox() {
        ChoiceBox<Connect> choiceBox = new ChoiceBox<>();
        choiceBox.setPrefWidth(255);
        choiceBox.setFocusTraversable(false);
        choiceBox.setStyle("-fx-pref-width: 255px;");   

        // 与 SQL 编辑面板连接选择器一致（SqlTab.fxml）
        choiceBox.getStyleClass().add("custom-connectname-choicebox");
        choiceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Connect object) {
                return object == null ? "" : object.toString();
            }

            @Override
            public Connect fromString(String string) {
                return null;
            }
        });
        return choiceBox;
    }

    private static ChoiceBox<String> stringChoiceBox() {
        ChoiceBox<String> choiceBox = new ChoiceBox<>();
        choiceBox.setPrefWidth(255);
        choiceBox.setStyle("-fx-pref-width: 255px;");   
        choiceBox.setFocusTraversable(false);
        // 与 SQL 编辑面板库/模式选择器一致（SqlTab.fxml）
        choiceBox.getStyleClass().add("custom-dbname-choicebox");
        return choiceBox;
    }

    private static Label boundLabel(String key, String fallback) {
        Label label = new Label();
        label.textProperty().bind(I18n.bind(key, fallback));
        return label;
    }

    private static CheckBox boundCheckBox(String key, String fallback, boolean selected) {
        CheckBox checkBox = new CheckBox();
        checkBox.textProperty().bind(I18n.bind(key, fallback));
        checkBox.setSelected(selected);
        checkBox.setFocusTraversable(false);
        return checkBox;
    }

    // ==================================================================
    // 源库/模式（一个任务只迁一个库/模式）
    // ==================================================================

    private static boolean isCatalogSchemaModel(Connect connect) {
        try {
            return PlatformResolvers.get().requirePlatform(connect).catalogModel()
                    == DatabasePlatform.CatalogModel.DATABASE_SCHEMA;
        } catch (Exception e) {
            return false;
        }
    }

    private static void setNoDataState(ChoiceBox<String> choiceBox) {
        choiceBox.getItems().setAll("N/A");
        choiceBox.getSelectionModel().select(0);
        choiceBox.setDisable(true);
    }

    private static void setDataState(ChoiceBox<String> choiceBox, List<String> names, String preferred) {
        choiceBox.setDisable(false);
        if (names == null || names.isEmpty()) {
            setNoDataState(choiceBox);
            return;
        }
        choiceBox.getItems().setAll(names);
        selectMatching(choiceBox, preferred);
    }

    private void reloadSourceCatalogs() {
        Connect source = sourceChoiceBox.getValue();
        sourceDbChoiceBox.getItems().clear();
        sourceSchemaChoiceBox.getItems().clear();
        if (source == null) {
            setSourceCatalogRowsVisible(true, false);
            setNoDataState(sourceDbChoiceBox);
            setNoDataState(sourceSchemaChoiceBox);
            rebuildTypeRows(null);
            return;
        }
        DatabasePlatform platform;
        try {
            platform = PlatformResolvers.get().requirePlatform(source);
        } catch (Exception e) {
            setSourceCatalogRowsVisible(true, false);
            setNoDataState(sourceDbChoiceBox);
            setNoDataState(sourceSchemaChoiceBox);
            rebuildTypeRows(null);
            return;
        }
        DatabasePlatform.CatalogModel model = platform.catalogModel();
        boolean schemaModel = model == DatabasePlatform.CatalogModel.SCHEMA;
        setSourceCatalogRowsVisible(true, model == DatabasePlatform.CatalogModel.DATABASE_SCHEMA);
        if (model != DatabasePlatform.CatalogModel.DATABASE_SCHEMA) {
            setNoDataState(sourceSchemaChoiceBox);
        }
        // SCHEMA 模型（Oracle/Dameng）：源库位置显示模式列表，标签用源模式键
        sourceDbLabel.textProperty().unbind();
        sourceDbLabel.textProperty().bind(I18n.bind(
                schemaModel ? "migration.label.source_schema" : "migration.label.source_database",
                schemaModel ? "Source Schema" : "Source Database"));
        rebuildTypeRows(platform);

        String preferred = firstNonBlank(echoSourceCatalog, preferredCatalogName());
        if (preferred != null && !preferred.isBlank()) {
            sourceDbChoiceBox.setDisable(false);
            sourceDbChoiceBox.getItems().setAll(preferred);
            sourceDbChoiceBox.getSelectionModel().select(0);
        } else {
            setNoDataState(sourceDbChoiceBox);
        }
        AppExecutor.runAsync(() -> {
            List<String> names = new ArrayList<>();
            try (Connection conn = BackgroundSqlService.getConnectionService().getConnectionWithSessionInit(new Connect(source))) {
                // SCHEMA 模型：一层模式经 getDatabases 适配返回
                for (Database db : PlatformResolvers.get().metadata(source).getDatabases(conn)) {
                    if (!platform.isSystemDatabase(db.getName())) {
                        names.add(db.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("load source catalogs failed", e);
            }
            Platform.runLater(() -> {
                if (sourceChoiceBox.getValue() != source) {
                    return;
                }
                setDataState(sourceDbChoiceBox, names, preferred);
            });
        });
    }

    /** DATABASE_SCHEMA：选源库后联动加载该库下的模式。 */
    private void loadSourceSchemas(String dbName) {
        Connect source = sourceChoiceBox.getValue();
        if (source == null || dbName == null || dbName.isBlank()) {
            setNoDataState(sourceSchemaChoiceBox);
            return;
        }
        String preferred = firstNonBlank(echoSourceSchema, null);
        if (preferred != null && !preferred.isBlank()) {
            sourceSchemaChoiceBox.setDisable(false);
            sourceSchemaChoiceBox.getItems().setAll(preferred);
            sourceSchemaChoiceBox.getSelectionModel().select(0);
        } else {
            setNoDataState(sourceSchemaChoiceBox);
        }
        AppExecutor.runAsync(() -> {
            List<String> names = new ArrayList<>();
            try {
                Connect sessionConnect = new Connect(source);
                sessionConnect.setCatalog(dbName);
                sessionConnect.setSessionCatalog("");
                try (Connection conn = BackgroundSqlService.getConnectionService().getConnectionWithSessionInit(sessionConnect)) {
                    for (Schema schema : PlatformResolvers.get().metadata(sessionConnect).getSchemas(conn)) {
                        names.add(schema.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("load source schemas failed", e);
            }
            Platform.runLater(() -> {
                if (sourceChoiceBox.getValue() != source
                        || !dbName.equals(sourceDbChoiceBox.getValue())) {
                    return;
                }
                setDataState(sourceSchemaChoiceBox, names, preferred);
            });
        });
    }

    private void setSourceCatalogRowsVisible(boolean showDb, boolean showSchema) {
        sourceDbLabel.setVisible(true);
        sourceDbLabel.setManaged(true);
        sourceDbChoiceBox.setVisible(true);
        sourceDbChoiceBox.setManaged(true);
        sourceDbChoiceBox.setDisable(!showDb);
        sourceSchemaLabel.setVisible(true);
        sourceSchemaLabel.setManaged(true);
        sourceSchemaChoiceBox.setVisible(true);
        sourceSchemaChoiceBox.setManaged(true);
        sourceSchemaChoiceBox.setDisable(!showSchema);
    }

    /** 当前源范围 catalog（DATABASE=库名；SCHEMA=模式名；DATABASE_SCHEMA=库名），未选为 null。 */
    private String currentSourceCatalog() {
        return blankToNull(sourceDbChoiceBox.getValue());
    }

    /** 当前源范围 schema（仅 DATABASE_SCHEMA），其余模型为 null。 */
    private String currentSourceSchema() {
        Connect source = sourceChoiceBox.getValue();
        if (source == null || !isCatalogSchemaModel(source)) {
            return null;
        }
        return blankToNull(sourceSchemaChoiceBox.getValue());
    }

    /** 源/目标库与模式是否都一致（N/A 视为空，忽略大小写）。 */
    private boolean sameSourceTargetScope() {
        return sameScopeValue(sourceDbChoiceBox.getValue(), targetDbChoiceBox.getValue())
                && sameScopeValue(sourceSchemaChoiceBox.getValue(), targetSchemaChoiceBox.getValue());
    }

    private static boolean sameScopeValue(String left, String right) {
        left = blankToNull(left);
        right = blankToNull(right);
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equalsIgnoreCase(right);
    }

    /** 源范围是否已选完整（DATABASE_SCHEMA 需库+模式，其余只需库/模式一项）。 */
    private boolean sourceScopeComplete() {
        if (currentSourceCatalog() == null) {
            return false;
        }
        Connect source = sourceChoiceBox.getValue();
        return source == null || !isCatalogSchemaModel(source) || currentSourceSchema() != null;
    }

    private static String scopeKey(String catalog, String schema) {
        return (catalog == null ? "" : catalog) + '\n' + (schema == null ? "" : schema);
    }

    // ==================================================================
    // 目标库/模式
    // ==================================================================

    private void reloadTargetCatalogs() {
        Connect target = targetChoiceBox.getValue();
        targetDbChoiceBox.getItems().clear();
        targetSchemaChoiceBox.getItems().clear();
        if (target == null) {
            setCatalogRowsVisible(true, true);
            setNoDataState(targetDbChoiceBox);
            setNoDataState(targetSchemaChoiceBox);
            return;
        }
        DatabasePlatform platform;
        try {
            platform = PlatformResolvers.get().requirePlatform(target);
        } catch (Exception e) {
            setCatalogRowsVisible(true, true);
            setNoDataState(targetDbChoiceBox);
            setNoDataState(targetSchemaChoiceBox);
            return;
        }
        DatabasePlatform.CatalogModel model = platform.catalogModel();
        boolean showDb = model != DatabasePlatform.CatalogModel.SCHEMA;
        boolean showSchema = model != DatabasePlatform.CatalogModel.DATABASE;
        setCatalogRowsVisible(showDb, showSchema);
        if (!showDb) {
            setNoDataState(targetDbChoiceBox);
        }
        if (!showSchema) {
            setNoDataState(targetSchemaChoiceBox);
        }

        String preferredDb = firstNonBlank(echoTargetDatabase, preferredCatalogName());
        String preferredSchemaImmediate = firstNonBlank(echoTargetSchema, preferredCatalogName());
        if (showDb && preferredDb != null && !preferredDb.isBlank()) {
            targetDbChoiceBox.setDisable(false);
            targetDbChoiceBox.getItems().setAll(preferredDb);
            targetDbChoiceBox.getSelectionModel().select(0);
        } else if (showSchema && preferredSchemaImmediate != null && !preferredSchemaImmediate.isBlank()) {
            targetSchemaChoiceBox.setDisable(false);
            targetSchemaChoiceBox.getItems().setAll(preferredSchemaImmediate);
            targetSchemaChoiceBox.getSelectionModel().select(0);
        } else if (showDb) {
            setNoDataState(targetDbChoiceBox);
        } else if (showSchema) {
            setNoDataState(targetSchemaChoiceBox);
        }
        // SCHEMA 模型（Oracle/Dameng）一层是模式：回显用任务保存的目标模式
        String preferredSchema = firstNonBlank(echoTargetSchema, preferredCatalogName());
        AppExecutor.runAsync(() -> {
            List<String> names = new ArrayList<>();
            try (Connection conn = BackgroundSqlService.getConnectionService().getConnectionWithSessionInit(new Connect(target))) {
                MetadataRepository meta = PlatformResolvers.get().metadata(target);
                for (Database db : meta.getDatabases(conn)) {
                    if (!platform.isSystemDatabase(db.getName())) {
                        names.add(db.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("load target catalogs failed", e);
            }
            Platform.runLater(() -> {
                if (targetChoiceBox.getValue() != target) {
                    return;
                }
                if (showDb) {
                    setDataState(targetDbChoiceBox, names, preferredDb);
                } else if (showSchema) {
                    setDataState(targetSchemaChoiceBox, names, preferredSchema);
                }
            });
        });
    }

    /** DATABASE_SCHEMA：选库后联动加载该库下的模式。 */
    private void loadTargetSchemas(String dbName) {
        Connect target = targetChoiceBox.getValue();
        if (target == null || dbName == null || dbName.isBlank()) {
            setNoDataState(targetSchemaChoiceBox);
            return;
        }
        String preferred = firstNonBlank(echoTargetSchema, preferredCatalogName());
        if (preferred != null && !preferred.isBlank()) {
            targetSchemaChoiceBox.setDisable(false);
            targetSchemaChoiceBox.getItems().setAll(preferred);
            targetSchemaChoiceBox.getSelectionModel().select(0);
        } else {
            setNoDataState(targetSchemaChoiceBox);
        }
        AppExecutor.runAsync(() -> {
            List<String> names = new ArrayList<>();
            try {
                Connect sessionConnect = new Connect(target);
                sessionConnect.setCatalog(dbName);
                sessionConnect.setSessionCatalog("");
                try (Connection conn = BackgroundSqlService.getConnectionService().getConnectionWithSessionInit(sessionConnect)) {
                    for (Schema schema : PlatformResolvers.get().metadata(sessionConnect).getSchemas(conn)) {
                        names.add(schema.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("load target schemas failed", e);
            }
            Platform.runLater(() -> {
                if (targetChoiceBox.getValue() != target
                        || !dbName.equals(targetDbChoiceBox.getValue())) {
                    return;
                }
                setDataState(targetSchemaChoiceBox, names, preferred);
            });
        });
    }

    private void setCatalogRowsVisible(boolean showDb, boolean showSchema) {
        targetDbLabel.setVisible(true);
        targetDbLabel.setManaged(true);
        targetDbChoiceBox.setVisible(true);
        targetDbChoiceBox.setManaged(true);
        targetDbChoiceBox.setDisable(!showDb);
        targetSchemaLabel.setVisible(true);
        targetSchemaLabel.setManaged(true);
        targetSchemaChoiceBox.setVisible(true);
        targetSchemaChoiceBox.setManaged(true);
        targetSchemaChoiceBox.setDisable(!showSchema);
    }

    /** 源/目标库模式默认选中与源连接同名项。 */
    private String preferredCatalogName() {
        Connect source = sourceChoiceBox.getValue();
        if (source == null) {
            return null;
        }
        String sessionCatalog = source.getSessionCatalog();
        if (sessionCatalog != null && !sessionCatalog.isBlank()) {
            return sessionCatalog;
        }
        String catalog = source.getCatalog();
        return catalog == null || catalog.isBlank() ? null : catalog;
    }

    private static void selectMatching(ChoiceBox<String> choiceBox, String preferred) {
        if (preferred == null) {
            if (!choiceBox.getItems().isEmpty()) {
                choiceBox.getSelectionModel().select(0);
            }
            return;
        }
        for (String item : choiceBox.getItems()) {
            if (item != null && item.equalsIgnoreCase(preferred)) {
                choiceBox.getSelectionModel().select(item);
                return;
            }
        }
        if (!choiceBox.getItems().isEmpty()) {
            choiceBox.getSelectionModel().select(0);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    // ==================================================================
    // 对象类型选择区（勾选类型 + 数量 + 全部/自定义范围）
    // ==================================================================

    /** 单个对象类型的选择状态与行控件。 */
    private static final class TypeSelection {
        final MigrationObjectRef.Kind kind;
        CheckBox checkBox;
        Label countLabel;
        Label selectedLabel;
        int count;
        Button editButton;
        /** false=全部（类型级通配），true=自定义挑选。 */
        boolean custom;
        /** 自定义挑选的对象：对象名 → WHERE 条件（可为空串）。 */
        final java.util.Map<String, String> customObjects = new java.util.LinkedHashMap<>();

        TypeSelection(MigrationObjectRef.Kind kind) {
            this.kind = kind;
        }
    }

    /** 源连接/平台变化时重建类型行（表/视图/触发器恒有，其余按平台支持度）。 */
    private void rebuildTypeRows(DatabasePlatform platform) {
        typeSelections.clear();
        typeRowsBox.getChildren().clear();
        for (MigrationObjectRef.Kind kind : allKinds()) {
            TypeSelection ts = new TypeSelection(kind);
            ts.checkBox = new CheckBox();
            // 新建任务默认勾选"表/索引/外键"；编辑已有任务时由 refs 回显覆盖
            boolean defaultSelected = isNewTask() && (kind == MigrationObjectRef.Kind.TABLE
                    || kind == MigrationObjectRef.Kind.INDEX
                    || kind == MigrationObjectRef.Kind.FOREIGN_KEY);
            ts.checkBox.setSelected(defaultSelected);
            ts.checkBox.textProperty().bind(I18n.bind(kindKey(kind), kindFallback(kind)));
            ts.checkBox.setFocusTraversable(false);
            ts.checkBox.selectedProperty().addListener(
                    (obs, o, n) -> {
                        refreshMappingEditButton();
                        updateSelectedLabel(ts);
                    });
            // 数量未加载前显示 0，源范围选定后后台刷新真实数量
            ts.countLabel = new Label("(0)");
            ts.editButton = new Button();
            ts.editButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.7));
            ts.editButton.getStyleClass().add("small");
            Tooltip editTip = new Tooltip();
            editTip.textProperty().bind(I18n.bind("migration.editor.type_scope_edit", "Edit"));
            ts.editButton.setTooltip(editTip);
            ts.editButton.setOnAction(e -> openPicker(ts));
            ts.selectedLabel = new Label("0/0");
            ts.count = 0;
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, ts.checkBox, ts.countLabel, ts.selectedLabel, spacer, ts.editButton);
            if (kind == MigrationObjectRef.Kind.TABLE) {
                tableCheckBox = ts.checkBox;
                ts.editButton.disableProperty().bind(tableCheckBox.selectedProperty().not());
                ddlCheckBox.disableProperty().bind(tableCheckBox.selectedProperty().not());
                dataCheckBox.disableProperty().bind(tableCheckBox.selectedProperty().not());
                // 表未勾选或未迁移数据时，"清空表"都无意义
                truncateCheckBox.disableProperty().bind(
                        tableCheckBox.selectedProperty().not().or(dataCheckBox.selectedProperty().not()));
                row.getChildren().addAll(ddlCheckBox, dataCheckBox, truncateCheckBox, editMappingButton);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            typeSelections.add(ts);
            typeRowsBox.getChildren().add(row);
        }
        reloadTypeCounts();
        refreshMappingEditButton();
    }

    private TypeSelection typeSelectionFor(MigrationObjectRef.Kind kind) {
        for (TypeSelection ts : typeSelections) {
            if (ts.kind == kind) {
                return ts;
            }
        }
        return null;
    }

    /** 源范围变化后后台加载各类型对象数量；scope 变更会使旧的自定义挑选失效。 */
    private void reloadTypeCounts() {
        String catalog = currentSourceCatalog();
        String schema = currentSourceSchema();
        boolean complete = sourceScopeComplete();
        if (complete) {
            String scopeKey = scopeKey(catalog, schema);
            if (!Objects.equals(scopeKey, picksScopeKey)) {
                for (TypeSelection ts : typeSelections) {
                    ts.custom = false;
                    ts.customObjects.clear();
                }
                picksScopeKey = scopeKey;
            }
        }
        Connect source = sourceChoiceBox.getValue();
        if (source == null || !complete || typeSelections.isEmpty()) {
            // 类型行保持可见可点，数量维持未加载的 (0)
            for (TypeSelection ts : typeSelections) {
                ts.countLabel.setText("(0)");
                ts.count = 0;
                updateSelectedLabel(ts);
            }
            refreshMappingEditButton();
            return;
        }
        int generation = ++countGeneration;
        DatabasePlatform platform = null;
        try {
            platform = PlatformResolvers.get().requirePlatform(source);
        } catch (Exception ignored) {
            // keep unsupported kinds at (0)
        }
        List<MigrationObjectRef.Kind> kinds = new ArrayList<>();
        for (TypeSelection ts : typeSelections) {
            if (isKindSupported(platform, ts.kind)) {
                kinds.add(ts.kind);
            }
        }
        AppExecutor.runAsync(() -> {
            Map<MigrationObjectRef.Kind, Integer> counts = new EnumMap<>(MigrationObjectRef.Kind.class);
            try {
                Connect sessionConnect = buildSessionConnect(source, catalog, schema);
                String dbName = schema != null && !schema.isBlank() ? schema : catalog;
                try (Connection conn = BackgroundSqlService.getConnectionService().getConnectionWithSessionInit(sessionConnect)) {
                    MetadataRepository meta = PlatformResolvers.get().metadata(sessionConnect);
                    for (MigrationObjectRef.Kind kind : kinds) {
                        counts.put(kind, countObjects(meta, conn, dbName, kind));
                    }
                }
            } catch (Exception e) {
                log.warn("load migration object counts failed", e);
            }
            Platform.runLater(() -> {
                if (generation != countGeneration) {
                    return;
                }
                for (TypeSelection ts : typeSelections) {
                    Integer count = counts.get(ts.kind);
                    ts.count = count == null ? 0 : count;
                    ts.countLabel.setText(count == null ? "(0)" : "(" + count + ")");
                    updateSelectedLabel(ts);
                }
            });
        });
    }

    /** 优先用 count 方法，失败回退到清单大小。 */
    private static int countObjects(MetadataRepository meta, Connection conn, String dbName,
                                    MigrationObjectRef.Kind kind) throws Exception {
        try {
            return switch (kind) {
                case TABLE -> meta.getUserTablesCount(conn);
                case VIEW -> meta.getViewCount(conn);
                case SEQUENCE -> meta.getSequenceCount(conn);
                case SYNONYM -> meta.getSynonymCount(conn);
                case TRIGGER -> meta.getTriggerCount(conn);
                case FUNCTION -> meta.getFunctionCount(conn, false);
                case PROCEDURE -> meta.getProcedureCount(conn, false);
                case PACKAGE -> meta.getPackageCount(conn);
                case INDEX -> meta.getIndexCount(conn);
                case FOREIGN_KEY -> {
                    // 无外键计数方法：直接按清单大小（逐表读 JDBC 元数据，表多时偏慢）
                    List<? extends TreeData> fks = fetchObjects(meta, conn, dbName, kind);
                    yield fks == null ? 0 : fks.size();
                }
                case ALL -> 0;
            };
        } catch (Exception e) {
            log.debug("count method failed for {}, fallback to list size", kind, e);
            List<? extends TreeData> objects = fetchObjects(meta, conn, dbName, kind);
            return objects == null ? 0 : objects.size();
        }
    }

    /** 对象挑选子对话框：确认返回选中的对象 ref（含 WHERE 条件），取消返回 null。 */
    private void openPicker(TypeSelection ts) {
        Connect source = sourceChoiceBox.getValue();
        if (source == null) {
            return;
        }
        // 范围按钮任何时候可点：源范围缺失时打开空列表（显示 (0)）
        boolean complete = sourceScopeComplete();
        List<MigrationObjectRef> picked = ObjectPickerDialog.showAndWait(dialogStage, ts.kind,
                source,
                complete ? currentSourceCatalog() : null,
                complete ? currentSourceSchema() : null,
                ts.customObjects);
        if (picked == null) {
            return;
        }
        ts.customObjects.clear();
        for (MigrationObjectRef ref : picked) {
            ts.customObjects.put(ref.name(), slotValue(ref));
        }
        ts.custom = true;
        if (!picked.isEmpty()) {
            ts.checkBox.setSelected(true);
        }
        updateSelectedLabel(ts);
        refreshMappingEditButton();
    }

    private void updateSelectedLabel(TypeSelection ts) {
        if (ts == null || ts.selectedLabel == null) {
            return;
        }
        int selected = ts.checkBox.isSelected()
                ? (ts.custom ? ts.customObjects.size() : ts.count)
                : 0;
        ts.selectedLabel.setText(String.format(
                I18n.t("migration.editor.selected_count", "Selected %d/%d"),
                selected, ts.count));
    }

    // ==================================================================
    // 自定义数据映射（全局类型映射，对所有表生效）
    // ==================================================================

    private void openMappingDialog() {
        Connect source = sourceChoiceBox.getValue();
        Connect target = targetChoiceBox.getValue();
        if (source == null || target == null) {
            return;
        }
        String taskName = nameField.getText() == null ? "" : nameField.getText().trim();
        // 类型列表/默认映射直接使用源/目标连接自身的数据库类型
        AppExecutor.runAsync(() -> {
            String sourceDbType = source.getDbtype();
            String targetDbType = target.getDbtype();
            Platform.runLater(() -> {
                Map<String, TableMapping> result = new MigrationMappingDialogController().showAndWait(
                        dialogStage, taskName, sourceDbType, targetDbType, mappings);
                if (result != null) {
                    mappings = result;
                }
            });
        });
    }

    /** "编辑"映射按钮在源/目标连接已选时可用（全局映射不再要求表类型勾选/源范围）。 */
    private void refreshMappingEditButton() {
        if (editMappingButton == null) {
            return;
        }
        boolean enabled = sourceChoiceBox != null && sourceChoiceBox.getValue() != null
                && targetChoiceBox != null && targetChoiceBox.getValue() != null;
        boolean tableSelected = tableCheckBox == null || tableCheckBox.isSelected();
        editMappingButton.setDisable(!enabled || !tableSelected);
    }

    /** 源数据库类型显示：dbtype 即时上屏；支持 sqlmode 的平台后台探测后补齐显示。 */
    private void refreshSourceDbTypeLabel() {
        Connect source = sourceChoiceBox.getValue();
        sourceDbTypeValue.setText(source == null || source.getDbtype() == null ? "" : source.getDbtype());
        if (source == null) {
            return;
        }
        int generation = ++sourceDbTypeProbeGeneration;
        AppExecutor.runAsync(() -> {
            String text = MigrationConnectInfo.dbTypeWithSqlMode(source);
            Platform.runLater(() -> {
                if (generation != sourceDbTypeProbeGeneration) {
                    return;
                }
                sourceDbTypeValue.setText(text);
            });
        });
    }

    /** 目标数据库类型显示：dbtype 即时上屏；支持 sqlmode 的平台后台探测后补齐显示。 */
    private void refreshTargetDbTypeLabel() {
        Connect target = targetChoiceBox.getValue();
        targetDbTypeValue.setText(target == null || target.getDbtype() == null ? "" : target.getDbtype());
        if (target == null) {
            return;
        }
        int generation = ++targetDbTypeProbeGeneration;
        AppExecutor.runAsync(() -> {
            String text = MigrationConnectInfo.dbTypeWithSqlMode(target);
            Platform.runLater(() -> {
                if (generation != targetDbTypeProbeGeneration) {
                    return;
                }
                targetDbTypeValue.setText(text);
            });
        });
    }

    // ==================================================================
    // 元数据读取（独立复制 Connect 会话，不用主树连接）
    // ==================================================================

    /** 各平台支持的对象类型：表/视图/触发器恒有，其余看平台能力；索引/外键恒有（JDBC 通用元数据读取）。 */
    private static List<MigrationObjectRef.Kind> allKinds() {
        return List.of(
                MigrationObjectRef.Kind.TABLE,
                MigrationObjectRef.Kind.INDEX,
                MigrationObjectRef.Kind.FOREIGN_KEY,
                MigrationObjectRef.Kind.VIEW,
                MigrationObjectRef.Kind.SEQUENCE,
                MigrationObjectRef.Kind.SYNONYM,
                MigrationObjectRef.Kind.TRIGGER,
                MigrationObjectRef.Kind.FUNCTION,
                MigrationObjectRef.Kind.PROCEDURE,
                MigrationObjectRef.Kind.PACKAGE);
    }

    private static boolean isKindSupported(DatabasePlatform platform, MigrationObjectRef.Kind kind) {
        if (platform == null) {
            return false;
        }
        return switch (kind) {
            case TABLE, VIEW, TRIGGER -> true;
            case INDEX, FOREIGN_KEY -> true;
            case SEQUENCE -> platform.supportsSequencesFolder();
            case SYNONYM -> platform.supportsSynonymsFolder();
            case FUNCTION -> platform.supportsFunctionsFolder();
            case PROCEDURE -> platform.supportsProceduresFolder();
            case PACKAGE -> platform.supportsPackages();
            case ALL -> false;
        };
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

    private static List<? extends TreeData> fetchObjects(MetadataRepository meta, Connection conn,
                                                         String dbName,
                                                         MigrationObjectRef.Kind kind) throws Exception {
        return switch (kind) {
            case TABLE -> meta.getUserTables(conn, dbName);
            case VIEW -> meta.getViews(conn, dbName);
            case SEQUENCE -> meta.getSequences(conn, dbName);
            case SYNONYM -> meta.getSynonyms(conn, dbName);
            case TRIGGER -> meta.getTriggers(conn, dbName);
            case FUNCTION -> meta.getFunctions(conn, dbName, false);
            case PROCEDURE -> meta.getProcedures(conn, dbName, false);
            case PACKAGE -> meta.getPackages(conn, dbName);
            case INDEX -> meta.getIndexes(conn, dbName);
            case FOREIGN_KEY -> meta.getForeignKeys(conn, dbName);
            case ALL -> List.of();
        };
    }

    /** 后台线程用：列出指定范围下某类型的全部对象名。 */
    private static List<String> loadObjectNames(Connect source, String catalog, String schema,
                                                MigrationObjectRef.Kind kind) throws Exception {
        Connect sessionConnect = buildSessionConnect(source, catalog, schema);
        String dbName = schema != null && !schema.isBlank() ? schema : catalog;
        try (Connection conn = BackgroundSqlService.getConnectionService().getConnectionWithSessionInit(sessionConnect)) {
            List<? extends TreeData> objects = fetchObjects(
                    PlatformResolvers.get().metadata(sessionConnect), conn, dbName, kind);
            List<String> names = new ArrayList<>();
            if (objects != null) {
                for (TreeData object : objects) {
                    if (object != null && object.getName() != null && !object.getName().isBlank()) {
                        names.add(object.getName());
                    }
                }
            }
            return names;
        }
    }

    /** 后台线程用：索引/外键/触发器的 对象名→宿主表 映射（一次元数据读取同时得到清单与宿主表）。 */
    private static java.util.Map<String, String> loadObjectParents(Connect source, String catalog,
                                                                   String schema,
                                                                   MigrationObjectRef.Kind kind) throws Exception {
        Connect sessionConnect = buildSessionConnect(source, catalog, schema);
        String dbName = schema != null && !schema.isBlank() ? schema : catalog;
        try (Connection conn = BackgroundSqlService.getConnectionService().getConnectionWithSessionInit(sessionConnect)) {
            List<? extends TreeData> objects = fetchObjects(
                    PlatformResolvers.get().metadata(sessionConnect), conn, dbName, kind);
            java.util.Map<String, String> parents = new LinkedHashMap<>();
            if (objects != null) {
                for (TreeData object : objects) {
                    if (object == null || object.getName() == null || object.getName().isBlank()) {
                        continue;
                    }
                    if (object instanceof com.dbboys.model.Index index) {
                        parents.put(object.getName(), index.getTableName());
                    } else if (object instanceof com.dbboys.model.ForeignKey foreignKey) {
                        parents.put(object.getName(), foreignKey.getTableName());
                    } else if (object instanceof com.dbboys.model.Trigger trigger) {
                        parents.put(object.getName(), trigger.getTableName());
                    }
                }
            }
            return parents;
        }
    }

    private static String kindKey(MigrationObjectRef.Kind kind) {
        return "migration.kind." + kind.name().toLowerCase(Locale.ROOT);
    }

    private static String kindFallback(MigrationObjectRef.Kind kind) {
        return switch (kind) {
            case TABLE -> "Tables";
            case VIEW -> "Views";
            case SEQUENCE -> "Sequences";
            case SYNONYM -> "Synonyms";
            case TRIGGER -> "Triggers";
            case FUNCTION -> "Functions";
            case PROCEDURE -> "Procedures";
            case PACKAGE -> "Packages";
            case INDEX -> "Indexes";
            case FOREIGN_KEY -> "Foreign Keys";
            case ALL -> "All";
        };
    }

    /** 索引/外键/触发器没有 WHERE 条件，refs/自定义挑选的 value 槽借存宿主表名（parent）。 */
    private static boolean isParentedKind(MigrationObjectRef.Kind kind) {
        return kind == MigrationObjectRef.Kind.INDEX
                || kind == MigrationObjectRef.Kind.FOREIGN_KEY
                || kind == MigrationObjectRef.Kind.TRIGGER;
    }

    /** ref → customObjects 的 value：索引/外键/触发器取宿主表名，其余取 WHERE 条件。 */
    private static String slotValue(MigrationObjectRef ref) {
        if (isParentedKind(ref.kind())) {
            return ref.parent() == null ? "" : ref.parent();
        }
        return ref.where() == null ? "" : ref.where();
    }

    // ==================================================================
    // 勾选编码为 List<MigrationObjectRef>
    // ==================================================================

    /**
     * 勾选且范围为全部 → 类型级通配 ref；自定义 → 每个选中对象一条显式 ref；
     * 未勾选类型不产生 ref。源范围未选完整时返回空（校验会报"至少一个对象"）。
     */
    private List<MigrationObjectRef> collectObjectRefs() {
        List<MigrationObjectRef> refs = new ArrayList<>();
        if (!sourceScopeComplete()) {
            return refs;
        }
        String catalog = currentSourceCatalog();
        String schema = currentSourceSchema();
        for (TypeSelection ts : typeSelections) {
            if (!ts.checkBox.isSelected()) {
                continue;
            }
            if (ts.custom) {
                for (java.util.Map.Entry<String, String> entry : ts.customObjects.entrySet()) {
                    if (isParentedKind(ts.kind)) {
                        // value 槽存的是宿主表名
                        refs.add(new MigrationObjectRef(catalog, schema, ts.kind,
                                entry.getKey(), null, entry.getValue()));
                    } else {
                        refs.add(new MigrationObjectRef(catalog, schema, ts.kind,
                                entry.getKey(), entry.getValue()));
                    }
                }
            } else {
                refs.add(MigrationObjectRef.kindWildcard(catalog, schema, ts.kind));
            }
        }
        return refs;
    }

    /** 勾选类型下实际可迁移对象数量：自定义按已选对象数，全量按已加载数量。 */
    private int selectedObjectCount() {
        int total = 0;
        for (TypeSelection ts : typeSelections) {
            if (!ts.checkBox.isSelected()) {
                continue;
            }
            total += ts.custom ? ts.customObjects.size() : Math.max(0, ts.count);
        }
        return total;
    }

    // ==================================================================
    // 校验与保存
    // ==================================================================

    private boolean validateInput() {
        if (nameField.getText() == null || nameField.getText().isBlank()) {
            showError(I18n.t("migration.error.no_name", "Please enter a task name"));
            return false;
        }
        // 新建任务：任务名不能与现有任务重复（大小写不敏感）
        if (isNewTask()) {
            String newName = nameField.getText().trim();
            for (MigrationTask existing : LocalDbRepository.getAllMigrationTasks()) {
                if (existing != null && existing.getName() != null
                        && existing.getName().equalsIgnoreCase(newName)) {
                    showError(String.format(I18n.t("migration.error.name_exists",
                            "Task name \"%s\" is already in use"), newName));
                    return false;
                }
            }
        }
        Connect source = sourceChoiceBox.getValue();
        Connect target = targetChoiceBox.getValue();
        if (source == null || target == null
                || (source.getId() == target.getId() && sameSourceTargetScope())) {
            showError(I18n.t("migration.error.same_connection",
                    "Source and target connections must be different"));
            return false;
        }
        if (collectObjectRefs().isEmpty() || selectedObjectCount() < 1) {
            showError(I18n.t("migration.error.no_objects",
                    "Please select at least one object"));
            return false;
        }
        if (!ddlCheckBox.isSelected() && !dataCheckBox.isSelected()) {
            showError(I18n.t("migration.error.no_mode",
                    "Please select structure (DDL) and/or data to migrate"));
            return false;
        }
        try {
            DatabasePlatform.CatalogModel model =
                    PlatformResolvers.get().requirePlatform(target).catalogModel();
            boolean needDb = model != DatabasePlatform.CatalogModel.SCHEMA;
            boolean needSchema = model != DatabasePlatform.CatalogModel.DATABASE;
            if ((needDb && isBlank(targetDbChoiceBox.getValue()))
                    || (needSchema && isBlank(targetSchemaChoiceBox.getValue()))) {
                showError(I18n.t("migration.error.no_target_catalog",
                        "Please select target database/schema"));
                return false;
            }
        } catch (Exception e) {
            showError(I18n.t("migration.error.no_target_catalog",
                    "Please select target database/schema"));
            return false;
        }
        return true;
    }

    /** 校验通过后把表单写回任务（新建则 new），不落库，由调用方持久化。 */
    private void save(boolean startAfter) {
        if (!validateInput()) {
            return;
        }
        MigrationTask task = editingTask == null ? new MigrationTask() : editingTask;
        task.setName(nameField.getText().trim());
        task.setSourceId(sourceChoiceBox.getValue().getId());
        task.setTargetId(targetChoiceBox.getValue().getId());
        task.setTargetDatabase(targetDbChoiceBox.getValue());
        task.setTargetSchema(targetSchemaChoiceBox.getValue());
        task.setMigrateDdl(ddlCheckBox.isSelected());
        task.setMigrateData(dataCheckBox.isSelected());
        task.setTruncateTable(truncateCheckBox.isSelected());
        task.setOverwrite(overwriteCheckBox.isSelected());
        // 读/写线程数输入框：非法输入/小于 1 回退为默认值 10
        task.setReadThreadCount(parseThreadCount(readThreadCountField));
        task.setWriteThreadCount(parseThreadCount(writeThreadCountField));
        task.setObjectRefs(collectObjectRefs());
        task.setMappings(mappings);
        resultTask = task;
        startRequested = startAfter;
        dialogStage.close();
    }

    private void showError(String message) {
        AlertUtil.CustomAlert(I18n.t("common.error", "Error"), message);
    }

    /** 解析线程数输入框：空白/非数字/小于 1 一律回退默认值 10（输入框已用 TextFormatter 限数字）。 */
    private static int parseThreadCount(TextField field) {
        try {
            return Math.max(1, Integer.parseInt(field.getText().trim()));
        } catch (NumberFormatException ignored) {
            return 10;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "N/A".equalsIgnoreCase(value.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "N/A".equalsIgnoreCase(value.trim()) ? null : value;
    }

    // ==================================================================
    // 对象挑选子对话框（嵌套静态类）
    // ==================================================================

    /** 模态对象挑选：搜索过滤 + 全选/清空 + CheckBox 列表（后台加载）+ 确定/取消。 */
    private static final class ObjectPickerDialog {
        private static final double PICKER_W = 660;
        private static final double PICKER_H = 480;

        private static final class PickItem {
            final String name;
            final BooleanProperty selected;
            final javafx.beans.property.StringProperty where;
            /** 索引/外键的宿主表名（其余类型为 null）。 */
            final String parent;

            PickItem(String name, boolean selected, String where, String parent) {
                this.name = name;
                this.selected = new SimpleBooleanProperty(selected);
                this.where = new javafx.beans.property.SimpleStringProperty(
                        where == null ? "" : where);
                this.parent = parent;
            }
        }

        /** 返回选中对象的 ref 列表（master 列表顺序，含 WHERE 条件）；取消返回 null。 */
        static List<MigrationObjectRef> showAndWait(Window owner, MigrationObjectRef.Kind kind,
                                        Connect source, String catalog, String schema,
                                        java.util.Map<String, String> initialWhereByName) {
            Stage stage = new Stage();

            TextField searchField = new TextField();
            searchField.promptTextProperty().bind(
                    I18n.bind("migration.editor.pick_search", "Search object name"));
            Button selectAllButton = new Button();
            selectAllButton.textProperty().bind(I18n.bind("migration.editor.select_all", "Select All"));
            Button selectNoneButton = new Button();
            selectNoneButton.textProperty().bind(I18n.bind("migration.editor.select_none", "Clear"));
            HBox topBar = new HBox(8, searchField, selectAllButton, selectNoneButton);
            topBar.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(searchField, Priority.ALWAYS);

            Label placeholder = new Label();
            placeholder.textProperty().bind(I18n.bind("migration.status.loading", "Loading..."));
            ObservableList<PickItem> master = FXCollections.observableArrayList();
            FilteredList<PickItem> filtered = new FilteredList<>(master, item -> true);
            java.util.concurrent.atomic.AtomicBoolean itemsLoaded =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            ListView<PickItem> listView = new ListView<>(filtered);
            // picker-list：行高比默认少 20%，点击选中不改变行底色（样式见 cupertino-common.css 末尾）
            listView.getStyleClass().add("picker-list");
            listView.setPlaceholder(placeholder);
            listView.setCellFactory(lv -> new PickCell(kind == MigrationObjectRef.Kind.TABLE));
            VBox.setVgrow(listView, Priority.ALWAYS);

            searchField.textProperty().addListener((obs, o, n) -> {
                String needle = n == null ? "" : n.trim().toLowerCase(Locale.ROOT);
                filtered.setPredicate(item -> needle.isEmpty()
                        || item.name.toLowerCase(Locale.ROOT).contains(needle)
                        || (item.parent != null
                                && item.parent.toLowerCase(Locale.ROOT).contains(needle)));
                if (itemsLoaded.get() && filtered.isEmpty()) {
                    placeholder.textProperty().unbind();
                    placeholder.setText("");
                }
            });
            selectAllButton.setOnAction(e -> {
                for (PickItem item : filtered) {
                    item.selected.set(true);
                }
            });
            selectNoneButton.setOnAction(e -> {
                for (PickItem item : filtered) {
                    item.selected.set(false);
                }
            });

            VBox content = new VBox(8, topBar, listView);

            List<MigrationObjectRef> result = new ArrayList<>();

            // 后台加载对象清单（索引/外键顺带加载宿主表映射）
            AppExecutor.runAsync(() -> {
                List<String> names;
                java.util.Map<String, String> parents = java.util.Map.of();
                String error = null;
                try {
                    if (isParentedKind(kind)) {
                        parents = loadObjectParents(source, catalog, schema, kind);
                        names = new ArrayList<>(parents.keySet());
                    } else {
                        names = loadObjectNames(source, catalog, schema, kind);
                    }
                } catch (Exception e) {
                    log.warn("load picker objects failed", e);
                    names = List.of();
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                String loadError = error;
                List<PickItem> items = new ArrayList<>();
                for (String name : names) {
                    String slot = initialWhereByName == null ? null : initialWhereByName.get(name);
                    // 索引/外键/触发器：slot 槽与新鲜元数据都是宿主表名，优先用新鲜的
                    String parent = isParentedKind(kind)
                            ? parents.getOrDefault(name, slot == null ? "" : slot)
                            : null;
                    items.add(new PickItem(name,
                            initialWhereByName != null && initialWhereByName.containsKey(name),
                            slot, parent));
                }
                Platform.runLater(() -> {
                    master.setAll(items);
                    itemsLoaded.set(true);
                    placeholder.textProperty().unbind();
                    if (loadError != null) {
                        placeholder.setText(loadError);
                    } else if (items.isEmpty()) {
                        placeholder.setText("(0)");
                    } else if (filtered.isEmpty()) {
                        placeholder.setText("");
                    }
                });
            });

            String kindLabel = I18n.t(kindKey(kind), kindFallback(kind));
            ButtonType okType = new ButtonType(
                    I18n.t("common.confirm", "Confirm"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType(
                    I18n.t("common.cancel", "Cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                    String.format(I18n.t("migration.editor.pick_title", "Select %s objects"), kindLabel),
                    content,
                    PICKER_W,
                    PICKER_H,
                    okType,
                    cancelType);
            Button okButton = dialog.getButton(okType);
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                for (PickItem item : master) {
                    if (item.selected.get()) {
                        result.add(isParentedKind(kind)
                                ? new MigrationObjectRef(catalog, schema, kind, item.name, null, item.parent)
                                : new MigrationObjectRef(catalog, schema, kind,
                                        item.name, item.where.get()));
                    }
                }
            });
            ButtonType pickedType = dialog.showAndWait();
            return pickedType == okType ? result : null;
        }

        /** 行单元格：CheckBox 勾选 + TABLE 时的 WHERE 条件输入框。 */
        private static final class PickCell extends ListCell<PickItem> {
            private final boolean showWhere;
            private final CheckBox checkBox = new CheckBox();
            private final TextField whereField = new TextField();
            private final HBox rowBox = new HBox(8);
            private PickItem bound;

            PickCell(boolean showWhere) {
                this.showWhere = showWhere;
                whereField.promptTextProperty().bind(
                        I18n.bind("migration.editor.where_hint", "WHERE 条件（可选）"));
                whereField.setPrefWidth(220);
                whereField.setFocusTraversable(false);
                rowBox.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(checkBox, Priority.ALWAYS);
            }

            @Override
            protected void updateItem(PickItem item, boolean empty) {
                super.updateItem(item, empty);
                if (bound != null) {
                    checkBox.selectedProperty().unbindBidirectional(bound.selected);
                    whereField.textProperty().unbindBidirectional(bound.where);
                    bound = null;
                }
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (item.parent != null && !item.parent.isBlank()) {
                    checkBox.setText(item.name + "  (" + item.parent + ")");
                } else {
                    checkBox.setText(item.name);
                }
                checkBox.selectedProperty().bindBidirectional(item.selected);
                rowBox.getChildren().setAll(checkBox);
                if (showWhere) {
                    whereField.textProperty().bindBidirectional(item.where);
                    rowBox.getChildren().add(whereField);
                }
                bound = item;
                setGraphic(rowBox);
            }
        }
    }
}
