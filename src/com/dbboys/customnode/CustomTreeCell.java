package com.dbboys.customnode;

import com.dbboys.app.AppContext;
import com.dbboys.app.AppState;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.i18n.I18n;
import com.dbboys.impl.DatabasePlatforms;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.NotificationUtil;
import com.dbboys.util.PopupWindowUtil;
import com.dbboys.util.TabpaneUtil;
import com.dbboys.util.tree.TreeNavigator;
import com.dbboys.util.tree.TreeViewUtil;
import com.dbboys.vo.*;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.sql.SQLException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomTreeCell extends TreeCell<TreeData> {
    private static final String DRAG_PAYLOAD = "DATABASEOBJECTDRAG";
    private static final String CONNECTED_TEXT_STYLE = "-fx-fill: -color-fg-default;";
    private static final String DISCONNECTED_TEXT_STYLE = "-fx-text-fill:#666;";
    private static final String PRIMARY_ICON_STYLE = "-fx-fill: -color-fg-default;";
    private static final String WARN_ICON_STYLE = "-fx-fill: -color-danger-7;";
    private static final String INACTIVE_ICON_STYLE = "-fx-fill: #666;";
    private static final Pattern COUNT_INFO_PATTERN = Pattern.compile("^\\s*(\\d+)\\s*[个個](?:\\s*/\\s*(.+))?\\s*$");
    private static final double ICON_SLOT_SIZE = 16.0;

    private final int iconSize = 11;
    private final Label nameLabel = new Label();
    private final ImageView loadingIcon = IconFactory.loadingImageView(1);
    private final ImageView runningIcon = IconFactory.loadingImageView(1);
    private final SVGPath nodeIcon = new SVGPath();
    private final SVGPath lockIcon = new SVGPath();
    private final SVGPath warnIcon = new SVGPath();
    private final SVGPath defaultDbIcon = new SVGPath();
    private final Group nodeIconGroup = new Group(nodeIcon);
    private final Group defaultDbIconGroup = new Group(defaultDbIcon);
    private final Group warnIconGroup = new Group(warnIcon);
    private final Group lockIconGroup = new Group(lockIcon);

    private final StackPane nodeIconStackpane = new StackPane(nodeIconGroup);
    private final HBox graphicHbox = new HBox();
    private final Region spacer = new Region();
    private final Label descripLabel = new Label();
    private final Tooltip tooltip = new Tooltip();
 
    public CustomTreeCell() {
        loadingIcon.setFitWidth(iconSize);
        loadingIcon.setFitHeight(iconSize);
        runningIcon.setFitWidth(iconSize);
        runningIcon.setFitHeight(iconSize);
        applyPrimaryIconStyle(nodeIcon);
        lockIcon.setScaleX(0.45);
        lockIcon.setScaleY(0.45);
        lockIcon.setContent(IconPaths.TREECELL_LOCK);

        graphicHbox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        tooltip.setShowDelay(Duration.millis(100));
        descripLabel.setStyle("-fx-alignment: CENTER-RIGHT; -fx-text-fill:#aaa;-fx-font-family:'Courier New';");

        warnIcon.setContent(IconPaths.CHECK_WARN);
        warnIcon.setScaleX(0.4);
        warnIcon.setScaleY(0.4);
        applyWarnIconStyle(warnIcon);

        defaultDbIcon.setScaleX(0.5);
        defaultDbIcon.setScaleY(0.5);
        defaultDbIcon.setContent(IconPaths.METADATA_SET_DEFAULT_DATABASE_ITEM);
        applyPrimaryIconStyle(defaultDbIcon);


    }

    @Override
    protected void updateItem(TreeData item, boolean empty) {
        super.updateItem(item, empty);
        resetCellVisualState();
        if (item == null || empty) {
            return;
        }
        TreeItem<TreeData> treeItem = getTreeItem();
        if (treeItem == null) {
            return;
        }
        if(item instanceof Loading){
                renderLoading();
            }else if(item instanceof Connecting){
                renderConnecting();
            }
            else if(item instanceof ConnectFolder){
                renderConnectFolder(item, treeItem);
            }
            else if(item instanceof Connect connect){
                renderConnect(connect, item);
            }
            else if(item instanceof DatabaseFolder){
                DatabasePlatform dbFolderPlatform = TreeNavigator.resolvePlatform(treeItem);
                if (dbFolderPlatform != null && dbFolderPlatform.usesSchemaModel()) {
                    nodeIcon.setContent(IconPaths.TREECELL_USER_FOLDER);
                    nodeIcon.setScaleX(0.65);
                    nodeIcon.setScaleY(0.65);
                } else {
                    nodeIcon.setContent(IconPaths.TREECELL_DATABASE_FOLDER);
                    nodeIcon.setScaleX(0.13);
                    nodeIcon.setScaleY(0.13);
                }
                applyPrimaryIconStyle(nodeIcon);
                bindCellText(item);
                setGraphic(nodeIconStackpane);
            }
            else if(item instanceof Database database){
                renderDatabase(database, item, treeItem);
            }
            else if(item instanceof ObjectFolder objectFolder){
                nodeIcon.setContent(IconPaths.TREECELL_OBJECT_FOLDER);
                nodeIcon.setScaleX(0.5);
                nodeIcon.setScaleY(0.5);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                bindLocalizedCountInfo(descripLabel, objectFolder.descriptionProperty());
                graphicHbox.getChildren().clear();
                graphicHbox.getChildren().addAll(nodeIconStackpane,nameLabel, spacer,descripLabel);
                setGraphic(graphicHbox);
            }
            else if(item instanceof SysTable){
                SysTable sysTable=(SysTable)item;
                nodeIcon.setContent(IconPaths.TREECELL_TABLE);
                nodeIcon.setScaleX(0.38);
                nodeIcon.setScaleY(0.38);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                if (isRawOrExternal(sysTable.getTableTypeCode())) {
                    warnIcon.setVisible(true);
                }
                descripLabel.textProperty().unbind();
                if ("view".equals(sysTable.getTableTypeCode())) {
                    nodeIcon.setContent(IconPaths.TREECELL_VIEW);
                    nodeIcon.setScaleX(0.55);
                    nodeIcon.setScaleY(0.55);
                    descripLabel.setText("VIEW");
                }else{
                    bindRowsSizeText(descripLabel, sysTable.nrowsProperty(), sysTable.totalsizeProperty());
                }
                bindTooltip(
                        "DATABASE  : " , sysTable.getTableCatalog() , "\n" ,
                        "TABLENAME : " , sysTable.nameProperty() , "\n" ,
                        "OWNER     : " , sysTable.tableOwnerProperty() , "\n" ,
                        "CREATED   : " , sysTable.createTimeProperty() , "\n" ,
                        "TYPE      : " , sysTable.tableTypeCodeProperty() , "\n" ,
                        "LOCKMODE  : " , sysTable.lockTypeProperty() , "\n" ,
                        "FRAGMENTED: " , sysTable.isfragmentProperty() , "\n" ,
                        "EXTENTS   : " , sysTable.extentsProperty() , "\n" ,
                        "NROWS     : " , sysTable.nrowsProperty() , "\n" ,
                        "PAGESIZE  : " , sysTable.pagesizeProperty() , "\n" ,
                        "TOTALPAGES: " , sysTable.nptotalProperty() , "\n" ,
                        "TOTALSIZE : " , sysTable.totalsizeProperty() , "\n" ,
                        "DATAPAGES : " , sysTable.npdataProperty() , "\n" ,
                        "DATASIZE  : " , sysTable.usedsizeProperty() , "\n"
                );

            }
            else if(item instanceof Table table){
                renderTable(table, item);
            }
            else if(item instanceof View view){
                renderView(view, item);
            }
            else if(item instanceof Index){
                Index index=(Index)item;
                nodeIcon.setContent(IconPaths.TREECELL_INDEX);
                nodeIcon.setScaleX(0.5);
                nodeIcon.setScaleY(0.5);
                applyPrimaryIconStyle(nodeIcon);
                warnIcon.visibleProperty().unbind();
                warnIcon.visibleProperty().bind(index.isdisabledProperty());
                nameLabel.textProperty().unbind();
                nameLabel.textProperty().bind(
                        Bindings.createStringBinding(() ->
                                (index.tabnameProperty().get()+"("+index.nameProperty().get()+")"),index.tabnameProperty(),index.nameProperty()
                        ));
                descripLabel.textProperty().unbind();
                descripLabel.textProperty().bind(
                        Bindings.when(index.isdisabledProperty())
                                .then("DISABLED")
                                .otherwise(index.totalsizeProperty())
                );
                bindTooltip(
                        "DATABASE  : ", index.databaseProperty(), "\n",
                        "INDEXNAME : ", index.nameProperty(), "\n",
                        "TABLENAME : ", index.tabnameProperty(), "\n",
                        "COLS      : ", index.colsProperty(), "\n",
                        "IDXTYPE   : ", index.idxtypeProperty(), "\n",
                        "LEVELS    : ", index.levelsProperty(), "\n",
                        "UNIQVALES : ", index.uniqvaluesProperty(), "\n",
                        "PAGESIZE  : ", index.pagesizeProperty(), "\n",
                        "TOTALPAGES: ", index.totalpagesProperty(), "\n",
                        "TOTALSIZE : ", index.totalsizeProperty(), "\n",
                        "DISABLED  : ", index.isdisabledProperty()
                );
            }
            else if(item instanceof Sequence){
                Sequence sequence=(Sequence)item;
                nodeIcon.setContent(IconPaths.RESULTSET_ROW_NUMBER);
                nodeIcon.setScaleX(0.58);
                nodeIcon.setScaleY(0.6);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.setText("SEQ");
                bindTooltip(
                        "DATABASE : " , sequence.databaseProperty() , "\n" ,
                        "SEQNAME  : " , sequence.nameProperty() , "\n" ,
                        "MINVALUE : " , sequence.minValueProperty() , "\n" ,
                        "MAXVALUE : " , sequence.maxValueProperty() , "\n" ,
                        "INCVALUE : " , sequence.incValueProperty() , "\n" ,
                        "CACHE    : " , sequence.cacheProperty() , "\n" ,
                        "NEXTCACHE: " , sequence.nextValProperty() , "\n" ,
                        "CREATED  : " , sequence.createdProperty()
                );
            }
            else if (item instanceof MetadataType metaType) {
                nodeIcon.setContent(IconPaths.TREECELL_TABLE);
                nodeIcon.setScaleX(0.38);
                nodeIcon.setScaleY(0.38);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.textProperty().bind(
                        Bindings.createStringBinding(() -> {
                            String k = metaType.getTypeKind();
                            return k == null || k.isBlank() ? "TYPE" : k;
                        }, metaType.typeKindProperty())
                );
                bindTooltip(
                        "DATABASE : ", metaType.databaseProperty(), "\n",
                        "TYPE     : ", metaType.nameProperty(), "\n",
                        "OWNER    : ", metaType.ownerProperty(), "\n",
                        "KIND     : ", metaType.typeKindProperty()
                );
            }
            else if (item instanceof MetadataQueue metaQueue) {
                nodeIcon.setContent(IconPaths.RESULTSET_ROW_NUMBER);
                nodeIcon.setScaleX(0.5);
                nodeIcon.setScaleY(0.5);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.setText("QUEUE");
                bindTooltip(
                        "DATABASE : ", metaQueue.databaseProperty(), "\n",
                        "QUEUE    : ", metaQueue.nameProperty(), "\n",
                        "OWNER    : ", metaQueue.ownerProperty()
                );
            }
            else if(item instanceof Synonym){
                Synonym synonym=(Synonym)item;
                nodeIcon.setContent(IconPaths.TREECELL_SYNONYM);
                nodeIcon.setScaleX(0.35);
                nodeIcon.setScaleY(0.35);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                bindTooltip(
                        "DATABASE: " , synonym.databaseProperty() , "\n" ,
                        "SYNNAME : " , synonym.nameProperty() , "\n" ,
                        "SYNTYPE : " , synonym.synonymTypeProperty() , "\n" ,
                        "CREATED : " , synonym.createdProperty()
                );
                descripLabel.textProperty().unbind();
                descripLabel.textProperty().bind(synonym.synonymTypeProperty());
            }
            else if(item instanceof Trigger){
                Trigger trigger=(Trigger)item;
                nodeIcon.setContent(IconPaths.TREECELL_TRIGGER);
                nodeIcon.setScaleX(0.5);
                nodeIcon.setScaleY(0.5);
                applyPrimaryIconStyle(nodeIcon);
                warnIcon.visibleProperty().unbind();
                warnIcon.visibleProperty().bind(trigger.isdisabledProperty());
                bindNameLabel(item);
                bindTooltip(
                        "DATABASE: " , trigger.databaseProperty() , "\n" ,
                        "TABNAME : " , trigger.tableNameProperty() , "\n" ,
                        "TRINAME : " , trigger.nameProperty() , "\n" ,
                        "TRITYPE : " , trigger.triggerTypeProperty() , "\n" ,
                        "DISABLED: " , trigger.isdisabledProperty()
                );
                descripLabel.textProperty().unbind();
                descripLabel.textProperty().bind(
                        Bindings.when(trigger.isdisabledProperty())
                                .then("DISABLED")
                                .otherwise("ENABLED")
                );
            }
            else if(item instanceof Function){
                Function function=(Function)item;
                nodeIcon.setContent(IconPaths.TREECELL_FUNCTION);
                nodeIcon.setScaleX(0.7);
                nodeIcon.setScaleY(0.6);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                bindTooltip(
                        "DATABASE: ",function.databaseProperty(),"\n",
                        "OWNER   : ",function.ownerProperty(),"\n",
                        "FUNCNAME: ",function.nameProperty()
                );
                descripLabel.textProperty().unbind();
                descripLabel.setText("FUNC");
            }
            else if(item instanceof Procedure){
                Procedure procedure=(Procedure)item;
                nodeIcon.setContent(IconPaths.TREECELL_PROCEDURE);
                nodeIcon.setScaleX(0.55);
                nodeIcon.setScaleY(0.55);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                bindTooltip(
                        "DATABASE: ",procedure.databaseProperty(),"\n",
                        "OWNER   : ",procedure.ownerProperty(),"\n",
                        "PROCNAME: ",procedure.nameProperty()
                );
                descripLabel.textProperty().unbind();
                descripLabel.setText("PROC");
            }
            else if(item instanceof DBPackage){
                DBPackage dbPackage = (DBPackage)item;
                nodeIcon.setContent(IconPaths.TREECELL_PACKAGE);
                nodeIcon.setScaleX(0.45);
                nodeIcon.setScaleY(0.45);
                applyPrimaryIconStyle(nodeIcon);
                warnIcon.visibleProperty().unbind();
                warnIcon.visibleProperty().bind(dbPackage.isEmptyProperty());
                bindNameLabel(item);
                bindTooltip(
                        "DATABASE: ",dbPackage.databaseProperty(),"\n",
                        "OWNER   : ",dbPackage.ownerProperty(),"\n",
                        "PKGNAME : ",dbPackage.nameProperty()
                );
                descripLabel.textProperty().unbind();
                descripLabel.setText("PACKAGE");
                graphicHbox.getChildren().clear();
                graphicHbox.getChildren().addAll(nodeIconStackpane,nameLabel, spacer,warnIconGroup,descripLabel);
                setGraphic(graphicHbox);
            }
            else if(item instanceof PackageFunction){
                PackageFunction packageFunction = (PackageFunction)item;
                nodeIcon.setContent(IconPaths.TREECELL_FUNCTION);
                nodeIcon.setScaleX(0.7);
                nodeIcon.setScaleY(0.6);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.textProperty().bind(packageFunction.descriptionProperty());
            }
            else if(item instanceof PackageProcedure){
                PackageProcedure packageProcedure = (PackageProcedure)item;
                nodeIcon.setContent(IconPaths.TREECELL_PROCEDURE);
                nodeIcon.setScaleX(0.55);
                nodeIcon.setScaleY(0.55);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.textProperty().bind(packageProcedure.descriptionProperty());
            }
            else if(item instanceof UserFolder){
                nodeIcon.setContent(IconPaths.TREECELL_USER_FOLDER);
                //鍗曚釜鐢ㄦ埛鍥炬爣
                //userIcon.setContent(IconPaths.METADATA_NAME_LABEL);
                nodeIcon.setScaleX(0.65);
                nodeIcon.setScaleY(0.65);
                applyPrimaryIconStyle(nodeIcon);
                bindCellText(item);
                setGraphic(nodeIconStackpane);
            }
            else if(item instanceof User){
                //nodeIcon.setContent(IconPaths.TREECELL_USER_FOLDER);
                //鍗曚釜鐢ㄦ埛鍥炬爣
                nodeIcon.setContent(IconPaths.METADATA_NAME_LABEL);
                nodeIcon.setScaleX(0.55);
                nodeIcon.setScaleY(0.55);
                applyPrimaryIconStyle(nodeIcon);
                bindCellText(item);
                setGraphic(nodeIconStackpane);
            }
            else if(item instanceof CheckFolder){
                nodeIcon.setContent(IconPaths.TREECELL_CHECK_FOLDER);
                nodeIcon.setScaleX(0.75);
                nodeIcon.setScaleY(0.75);
                applyPrimaryIconStyle(nodeIcon);
                bindCellText(item);
                setGraphic(nodeIconStackpane);
            }
            else if(item instanceof MonFolder){
                nodeIcon.setContent(IconPaths.TREECELL_MON_FOLDER);
                nodeIcon.setScaleX(0.6);
                nodeIcon.setScaleY(0.6);
                applyPrimaryIconStyle(nodeIcon);
                bindCellText(item);
                setGraphic(nodeIconStackpane);
            }

        configureLeafNodeActions(item);
        configureDragActions(item);
    }

    private void renderLoading() {
        setGraphic(loadingIcon);
        textProperty().unbind();
        textProperty().bind(I18n.bind("treecell.status.loading", "Loading"));
    }

    private void renderConnecting() {
        setGraphic(loadingIcon);
        textProperty().unbind();
        textProperty().bind(I18n.bind("treecell.status.connecting", "Connecting"));
    }

    private void renderConnectFolder(TreeData item, TreeItem<TreeData> treeItem) {
        if (treeItem.isExpanded()) {
            nodeIcon.setContent(IconPaths.TREECELL_CONNECT_FOLDER_OPEN);
            nodeIcon.setScaleX(0.62);
            nodeIcon.setScaleY(0.62);
        } else {
            nodeIcon.setContent(IconPaths.CREATE_CONNECT_FOLDER);
            nodeIcon.setScaleX(0.52);
            nodeIcon.setScaleY(0.52);
        }
                applyPrimaryIconStyle(nodeIcon);
        bindCellText(item);
        setGraphic(nodeIconStackpane);
    }

    private void renderConnect(Connect connect, TreeData item) {
        applyConnectIconSlot(true);
        applyDatabaseTypeIcon(connect.getDbtype());
        applyConnectedVisualStyle();
        String status = I18n.t("treecell.status.connected", "Connected");
        try {
            if (connect.getConn() == null || connect.getConn().isClosed()) {
                applyDisconnectedVisualStyle();
                status = I18n.t("treecell.status.disconnected", "Disconnected");
            } else {
                applyConnectedVisualStyle();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        bindNameLabel(item);
        graphicHbox.getChildren().clear();
        if (connect.getReadonly()) {
            graphicHbox.getChildren().addAll(nodeIconStackpane, nameLabel, spacer, lockIconGroup);
        } else {
            graphicHbox.getChildren().addAll(nodeIconStackpane, nameLabel);
        }
        setGraphic(graphicHbox);
        bindTooltip("DB TYPE: ", connect.dbtypeProperty(), "\nIP ADDR: ", connect.ipProperty(), "\nPORT   : ", connect.portProperty(), "\nUSER   : ", connect.usernameProperty(), "\nSTATUS : ", status);
    }

    private void renderDatabase(Database database, TreeData item, TreeItem<TreeData> treeItem) {
        applyConnectIconSlot(false);
        DatabasePlatform platform = TreeNavigator.resolvePlatform(treeItem);
        if (platform != null && platform.usesSchemaModel()) {
            nodeIcon.setContent(IconPaths.METADATA_NAME_LABEL);
            nodeIcon.setScaleX(0.55);
            nodeIcon.setScaleY(0.55);
        } else {
            nodeIcon.setContent(IconPaths.TREECELL_DATABASE);
            nodeIcon.setScaleX(0.4);
            nodeIcon.setScaleY(0.4);
        }
        applyPrimaryIconStyle(nodeIcon);
        bindNameLabel(item);
        String sysTag = isSystemDatabase(platform, database.getName()) ? "(SYS)" : "";
        warnIcon.setVisible("nolog".equals(database.getDbLog()));
        descripLabel.textProperty().unbind();
        descripLabel.setText(sysTag + database.getDbSize());
        graphicHbox.getChildren().clear();
        if (isDefaultDatabase(treeItem, database)) {
            graphicHbox.getChildren().addAll(nodeIconStackpane, nameLabel, spacer, defaultDbIconGroup, warnIconGroup, descripLabel);
        } else {
            graphicHbox.getChildren().addAll(nodeIconStackpane, nameLabel, spacer, warnIconGroup, descripLabel);
        }
        setGraphic(graphicHbox);
        bindDatabaseTooltip(database, platform);
    }

    private void bindDatabaseTooltip(Database database, DatabasePlatform platform) {
        java.util.List<DatabasePlatform.TooltipField> fields = platform != null
                ? platform.databaseTooltipFields()
                : DatabasePlatform.DEFAULT_DATABASE_TOOLTIP_FIELDS;
        int maxLen = fields.stream().mapToInt(f -> f.label().length()).max().orElse(0);
        java.util.List<Object> parts = new java.util.ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            DatabasePlatform.TooltipField field = fields.get(i);
            String padded = String.format("%-" + maxLen + "s", field.label());
            if (i > 0) parts.add("\n");
            parts.add(padded + ": ");
            parts.add(resolveDatabaseProperty(database, field.propertyName()));
        }
        bindTooltip(parts.toArray());
    }

    private static ObservableValue<?> resolveDatabaseProperty(Database db, String propertyName) {
        return switch (propertyName) {
            case "name"      -> db.nameProperty();
            case "dbOwner"   -> db.dbOwnerProperty();
            case "dbLog"     -> db.dbLogProperty();
            case "dbSpace"   -> db.dbSpaceProperty();
            case "dbSize"    -> db.dbSizeProperty();
            case "dbCreated" -> db.dbCreatedProperty();
            case "dbLocale"  -> db.dbLocaleProperty();
            case "dbUseGLU"  -> db.dbUseGLUProperty();
            default          -> new javafx.beans.property.SimpleStringProperty("");
        };
    }

    private void applyDatabaseTypeIcon(String dbType) {
        DatabasePlatform.IconInfo info = resolveIconInfo(dbType);
        if (info != null) {
            nodeIcon.setContent(info.svgPath());
            nodeIcon.setScaleX(info.scaleX());
            nodeIcon.setScaleY(info.scaleY());
        } else {
            nodeIcon.setContent(IconPaths.CONNECTION_LINK);
            nodeIcon.setScaleX(0.55);
            nodeIcon.setScaleY(0.55);
        }
        applyPrimaryIconStyle(nodeIcon);
    }

    private void renderTable(Table table, TreeData item) {
        nodeIcon.setContent(IconPaths.TREECELL_TABLE);
        nodeIcon.setScaleX(0.38);
        nodeIcon.setScaleY(0.38);
        applyPrimaryIconStyle(nodeIcon);
        bindNameLabel(item);
        warnIcon.visibleProperty().unbind();
        warnIcon.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> isRawOrExternal(table.getTableTypeCode()),
                table.tableTypeCodeProperty()
        ));
        bindRowsSizeText(descripLabel, table.nrowsProperty(), table.totalsizeProperty());
        bindTooltip("DATABASE  : ", table.getTableCatalog(), "\n",
                "TABLENAME : ", table.nameProperty(), "\n",
                "OWNER     : ", table.tableOwnerProperty(), "\n",
                "CREATED   : ", table.createTimeProperty(), "\n",
                "TYPE      : ", table.tableTypeCodeProperty(), "\n",
                "LOCKMODE  : ", table.lockTypeProperty(), "\n",
                "FRAGMENTED: ", table.isfragmentProperty(), "\n",
                "EXTENTS   : ", table.extentsProperty(), "\n",
                "NROWS     : ", table.nrowsProperty(), "\n",
                "PAGESIZE  : ", table.pagesizeProperty(), "\n",
                "TOTALPAGES: ", table.nptotalProperty(), "\n",
                "TOTALSIZE : ", table.totalsizeProperty(), "\n",
                "DATAPAGES : ", table.npdataProperty(), "\n",
                "DATASIZE  : ", table.usedsizeProperty(), "\n");
    }

    private void renderView(View view, TreeData item) {
        nodeIcon.setContent(IconPaths.TREECELL_VIEW);
        nodeIcon.setScaleX(0.55);
        nodeIcon.setScaleY(0.55);
        applyPrimaryIconStyle(nodeIcon);
        bindNameLabel(item);
        descripLabel.textProperty().unbind();
        descripLabel.setText("VIEW");
        bindTooltip("DATABASE: ", view.dbnameProperty(), "\n",
                "VIEWNAME: ", view.nameProperty(), "\n",
                "OWNER   : ", view.ownerProperty(), "\n",
                "CREATED : ", view.createTimeProperty(), "\n");
    }

    private void resetCellVisualState() {
        setOnMouseClicked(null);
        setTooltip(null); //娓呯悊鍏冪礌鎻愮ず锛岄伩鍏嶅嚭鐜伴敊璇殑鎻愮ず
        setGraphic(null);
        textProperty().unbind();
        setText(null);
        setStyle(null);
        nameLabel.setStyle("");
        descripLabel.textProperty().unbind();
        applyPrimaryIconStyle(nodeIcon);
        applyWarnIconStyle(lockIcon);
        warnIcon.visibleProperty().unbind();
        warnIcon.setVisible(false);
        nodeIconGroup.visibleProperty().unbind();
        nodeIconGroup.setVisible(true);
        applyConnectIconSlot(false);
        nodeIconStackpane.getChildren().remove(runningIcon);
        graphicHbox.getChildren().clear();
    }

    private void applyConnectIconSlot(boolean fixed) {
        if (fixed) {
            nodeIconStackpane.setMinSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
            nodeIconStackpane.setPrefSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
            nodeIconStackpane.setMaxSize(ICON_SLOT_SIZE, ICON_SLOT_SIZE);
        } else {
            nodeIconStackpane.setMinSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            nodeIconStackpane.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            nodeIconStackpane.setMaxSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        }
        nodeIconStackpane.setAlignment(Pos.CENTER);
    }

    private void configureLeafNodeActions(TreeData item) {
        TreeItem<TreeData> treeItem = getTreeItem();
        if (treeItem == null || !treeItem.isLeaf() || item instanceof Loading || item instanceof Connecting || item instanceof User) {
            return;
        }

        nodeIconStackpane.getChildren().remove(runningIcon);
        nodeIconStackpane.getChildren().add(runningIcon);
        runningIcon.visibleProperty().unbind();
        runningIcon.visibleProperty().bind(item.runningProperty());
        nodeIconGroup.visibleProperty().unbind();
        nodeIconGroup.visibleProperty().bind(runningIcon.visibleProperty().not());
        graphicHbox.getChildren().clear();
        graphicHbox.getChildren().addAll(nodeIconStackpane, nameLabel, spacer, warnIconGroup, descripLabel);
        setGraphic(graphicHbox);

        setOnMouseClicked(null);
        if (item instanceof Table) {
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    TabpaneUtil.addCustomTableInfoTab(treeItem);
                }
            });
            return;
        }

        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeViewUtil.handleDdlAction(getTreeView(), (treeData, ddlText) -> {
                    PopupWindowUtil.openDDLWindow(ddlText);
                 });
            }
        });
    }

    private void configureDragActions(TreeData item) {
        setOnDragDetected(null);
        setOnDragDone(null);

        if (!(item instanceof ConnectFolder || item instanceof Connect || item instanceof Table || item instanceof View || item instanceof Database)) {
            return;
        }

        setOnDragDetected(event -> {
            if (getItem() == null) {
                return;
            }

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            Image dragImage = this.snapshot(params, null);

            Dragboard db = startDragAndDrop(TransferMode.COPY_OR_MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(DRAG_PAYLOAD);
            db.setContent(content);

            db.setDragView(dragImage, event.getX(), event.getY());
            event.consume();
        });

        setOnDragDone(event -> {
            if (event.getTransferMode() != TransferMode.MOVE) {
                return;
            }
            handleDragMoveAction(item);
        });
    }


    

    private void handleDragMoveAction(TreeData item) {
        if (item instanceof ConnectFolder) {
            TreeItem<TreeData> treeItem = getTreeItem();
            if (treeItem != null && !treeItem.getChildren().isEmpty()) {
                TreeViewUtil.connectFolderInfoItem.fire();
            } else {
                NotificationUtil.showMainNotification(I18n.t("treecell.notice.no_connection", "当前分类无连接!"));
            }
            return;
        }
        if (item instanceof Connect) {
            TreeViewUtil.connectInfoItem.fire();
            return;
        }
        if (item instanceof Database) {
            TreeViewUtil.databaseOpenFileItem.fire();
            return;
        }

        String sql = buildSelectSql(item);
        if (!appendAndRunInCurrentSqlTab(sql)) {
            TreeViewUtil.databaseOpenFileItem.fire();
            if (AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab) {
                currentSqlTab.sqlTabController.sqlInit = sql;
            }
        }
    }

    private String buildSelectSql(TreeData item) {
        if (item instanceof Table table) {
            if (table.getIsfragment() == 1) {
                return "select * from " + item.getName() + ";";
            }
            return "select rowid,* from " + item.getName() + ";";
        }
        return "select * from " + item.getName() + ";";
    }

    private boolean appendAndRunInCurrentSqlTab(String sql) {
        if (!(AppState.getSqlTabPane().getSelectionModel().getSelectedItem() instanceof CustomSqlTab currentSqlTab)) {
            return false;
        }
        TreeItem<TreeData> treeItem = getTreeItem();
        if (treeItem == null
                || currentSqlTab.sqlTabController.sqlConnectChoiceBox.getValue() == null
                || currentSqlTab.sqlTabController.sqlDbChoiceBox.getValue() == null) {
            return false;
        }
        boolean sameConnect = currentSqlTab.sqlTabController.sqlConnectChoiceBox.getValue().getName()
                .equals(TreeViewUtil.getMetaConnect(treeItem).getName());
        boolean sameDatabase = currentSqlTab.sqlTabController.sqlDbChoiceBox.getValue().getName()
                .equals(TreeViewUtil.getCurrentDatabase(treeItem).getName());
        boolean runnable = !currentSqlTab.sqlTabController.sqlRunButton.isDisable();
        if (!(sameConnect && sameDatabase && runnable)) {
            return false;
        }

        if (!currentSqlTab.sqlTabController.sqlEditCodeArea.getText().isEmpty()) {
            sql = "\n" + sql;
        }
        int start = currentSqlTab.sqlTabController.sqlEditCodeArea.getLength();
        currentSqlTab.sqlTabController.sqlEditCodeArea.appendText(sql);
        currentSqlTab.sqlTabController.sqlEditCodeArea.moveTo(start);
        currentSqlTab.sqlTabController.sqlEditCodeArea.requestFollowCaret();
        currentSqlTab.sqlTabController.sqlEditCodeArea.selectRange(
                currentSqlTab.sqlTabController.sqlEditCodeArea.getLength() - (sql.startsWith("\n") ? (sql.length() - 1) : sql.length()),
                currentSqlTab.sqlTabController.sqlEditCodeArea.getLength()
        );
        currentSqlTab.sqlTabController.sqlRunButton.fire();
        return true;
    }

    private boolean isSystemDatabase(DatabasePlatform platform, String dbName) {
        if (platform != null) {
            return platform.isSystemDatabase(dbName);
        }
        return false;
    }

    private static DatabasePlatform.IconInfo resolveIconInfo(String dbType) {
        if (dbType == null) return null;
        try {
            DatabasePlatformResolver resolver = AppContext.get(DatabasePlatformResolver.class);
            if (resolver == null) resolver = DatabasePlatforms.createDefault();
            DatabasePlatform platform = resolver.getPlatform(dbType);
            return platform != null ? platform.iconInfo() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String formatRowsSize(Object nrows, Object totalSize) {
        String separator = I18n.t("treecell.row_size_separator", "行/");
        String sizeText = totalSize == null ? "" : totalSize.toString();
        if (sizeText.isBlank()) {
            return (String.valueOf(nrows) + separator).replaceFirst("[/\\s]+$", "");
        }
        return nrows + separator + sizeText;
    }

    private void bindRowsSizeText(Label label, ObservableValue<?> nrows, ObservableValue<?> totalSize) {
        label.textProperty().unbind();
        label.textProperty().bind(Bindings.createStringBinding(
                () -> formatRowsSize(nrows.getValue(), totalSize.getValue()),
                nrows, totalSize, I18n.localeProperty()
        ));
    }

    private void bindLocalizedCountInfo(Label label, ObservableValue<String> source) {
        label.textProperty().unbind();
        label.textProperty().bind(Bindings.createStringBinding(
                () -> localizeCountInfo(source.getValue()),
                source, I18n.localeProperty()
        ));
    }

    private String localizeCountInfo(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Matcher matcher = COUNT_INFO_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            return raw;
        }
        String count = matcher.group(1);
        String size = matcher.group(2);
        String unit = I18n.t("treecell.count_unit", "个");
        String separator = I18n.t("treecell.count_size_separator", "/");
        return (size == null || size.isBlank()) ? (count + unit) : (count + unit + separator + size);
    }

    private boolean isRawOrExternal(String tableType) {
        if (tableType == null) {
            return false;
        }
        String normalized = tableType.trim();
        return "raw".equalsIgnoreCase(normalized)
                || "external".equalsIgnoreCase(normalized)
                || "nologging".equalsIgnoreCase(normalized);
    }

    private boolean isDefaultDatabase(TreeItem<TreeData> treeItem, Database database) {
        TreeItem<TreeData> parent = treeItem.getParent();
        if (parent == null || parent.getParent() == null) {
            return false;
        }
        TreeData connectData = parent.getParent().getValue();
        if (!(connectData instanceof Connect connect)) {
            return false;
        }
        return database.getName().equals(connect.getDatabase());
    }

    private void bindNameLabel(TreeData item) {
        nameLabel.textProperty().unbind();
        nameLabel.textProperty().bind(item.nameProperty());
    }

    private void bindCellText(TreeData item) {
        textProperty().unbind();
        textProperty().bind(item.nameProperty());
    }

    private void bindTooltip(Object... parts) {
        tooltip.textProperty().unbind();
        tooltip.textProperty().bind(Bindings.concat(parts));
        setTooltip(tooltip);
    }

    private void applyPrimaryIconStyle(SVGPath icon) {
        icon.setStyle(PRIMARY_ICON_STYLE);
    }

    private void applyWarnIconStyle(SVGPath icon) {
        icon.setStyle(WARN_ICON_STYLE);
    }

    private void applyInactiveIconStyle(SVGPath icon) {
        icon.setStyle(INACTIVE_ICON_STYLE);
    }

    private void applyConnectedVisualStyle() {
        nameLabel.setStyle(CONNECTED_TEXT_STYLE);
        applyPrimaryIconStyle(nodeIcon);
        applyWarnIconStyle(lockIcon);
    }

    private void applyDisconnectedVisualStyle() {
        nameLabel.setStyle(DISCONNECTED_TEXT_STYLE);
        applyInactiveIconStyle(nodeIcon);
        applyInactiveIconStyle(lockIcon);
    }
}



