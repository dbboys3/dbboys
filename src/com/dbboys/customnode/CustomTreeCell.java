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

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;

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
            else if(item instanceof Catalog database){
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
                warnIcon.visibleProperty().unbind();
                warnIcon.visibleProperty().bind(Bindings.createBooleanBinding(
                        () -> isDangerousTableType(sysTable.getTableTypeCode()),
                        sysTable.tableTypeCodeProperty()
                ));
                descripLabel.textProperty().unbind();
                if ("view".equals(sysTable.getTableTypeCode())) {
                    nodeIcon.setContent(IconPaths.TREECELL_VIEW);
                    nodeIcon.setScaleX(0.55);
                    nodeIcon.setScaleY(0.55);
                    descripLabel.setText("VIEW");
                }else{
                    bindRowsSizeText(descripLabel, sysTable.nrowsProperty(), sysTable.totalsizeProperty());
                }
                bindMetadataTooltip(sysTable, DatabasePlatform.MetadataObjectType.SYS_TABLE);

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
                bindMetadataTooltip(index, DatabasePlatform.MetadataObjectType.INDEX);
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
                bindMetadataTooltip(sequence, DatabasePlatform.MetadataObjectType.SEQUENCE);
            }
            else if (item instanceof Type metaType) {
                nodeIcon.setContent(IconPaths.TREECELL_TYPE);
                nodeIcon.setScaleX(0.4);
                nodeIcon.setScaleY(0.4);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.textProperty().bind(
                        Bindings.createStringBinding(() -> {
                            String k = metaType.getTypeKind();
                            return k == null || k.isBlank() ? "TYPE" : k;
                        }, metaType.typeKindProperty())
                );
                bindMetadataTooltip(metaType, DatabasePlatform.MetadataObjectType.TYPE);
            }
            else if (item instanceof Queue metaQueue) {
                nodeIcon.setContent(IconPaths.RESULTSET_ROW_NUMBER);
                nodeIcon.setScaleX(0.5);
                nodeIcon.setScaleY(0.5);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.setText("QUEUE");
                bindMetadataTooltip(metaQueue, DatabasePlatform.MetadataObjectType.QUEUE);
            }
            else if (item instanceof SchedulerJob job) {
                nodeIcon.setContent(IconPaths.TREECELL_JOB);
                nodeIcon.setScaleX(0.5);
                nodeIcon.setScaleY(0.5);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.setText("JOB");
                bindTooltip(metadataCatalogTooltipLabelTight(), "JOB: ", job.nameProperty());
            }
            else if (item instanceof RecycleBinObject binObj) {
                nodeIcon.setContent(IconPaths.TREECELL_SYNONYM);
                nodeIcon.setScaleX(0.35);
                nodeIcon.setScaleY(0.35);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                descripLabel.textProperty().unbind();
                descripLabel.setText("BIN");
                bindTooltip(metadataCatalogTooltipLabelTight(), "RECYCLE: ", binObj.nameProperty());
            }
            else if(item instanceof Synonym){
                Synonym synonym=(Synonym)item;
                nodeIcon.setContent(IconPaths.TREECELL_SYNONYM);
                nodeIcon.setScaleX(0.35);
                nodeIcon.setScaleY(0.35);
                applyPrimaryIconStyle(nodeIcon);
                bindNameLabel(item);
                bindMetadataTooltip(synonym, DatabasePlatform.MetadataObjectType.SYNONYM);
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
                bindMetadataTooltip(trigger, DatabasePlatform.MetadataObjectType.TRIGGER);
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
                bindMetadataTooltip(function, DatabasePlatform.MetadataObjectType.FUNCTION);
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
                bindMetadataTooltip(procedure, DatabasePlatform.MetadataObjectType.PROCEDURE);
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
                bindMetadataTooltip(dbPackage, DatabasePlatform.MetadataObjectType.PACKAGE);
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

    private void renderDatabase(Catalog database, TreeData item, TreeItem<TreeData> treeItem) {
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
        bindMetadataTooltip(database, DatabasePlatform.MetadataObjectType.DATABASE);
    }

    private String resolveMetadataCatalogTooltipLabel() {
        TreeItem<TreeData> ti = getTreeItem();
        if (ti == null) {
            return "DATABASE";
        }
        try {
            DatabasePlatform p = TreeNavigator.resolvePlatform(ti);
            return p != null ? p.metadataTooltipCatalogLabel() : "DATABASE";
        } catch (Exception e) {
            return "DATABASE";
        }
    }

    /** Aligns with wide labels such as {@code TABLENAME : }. */
    private String metadataCatalogTooltipLabelWide() {
        return String.format("%-9s : ", resolveMetadataCatalogTooltipLabel());
    }

    /** Aligns with mid-width labels such as {@code TYPE     : }. */
    private String metadataCatalogTooltipLabelMid() {
        return String.format("%-8s: ", resolveMetadataCatalogTooltipLabel());
    }

    /** Aligns with tight labels such as {@code FUNCNAME: }. */
    private String metadataCatalogTooltipLabelTight() {
        return String.format("%-8s: ", resolveMetadataCatalogTooltipLabel());
    }

    private void bindMetadataTooltip(Object bean, DatabasePlatform.MetadataObjectType type) {
        DatabasePlatform platform = null;
        try {
            TreeItem<TreeData> treeItem = getTreeItem();
            if (treeItem != null) {
                platform = TreeNavigator.resolvePlatform(treeItem);
            }
        } catch (Exception ignored) {
        }
        java.util.List<DatabasePlatform.TooltipFieldDef> fields = platform != null
                ? platform.tooltipFields(type)
                : List.of();
        if (fields.isEmpty()) {
            setTooltip(null);
            return;
        }
        int maxLen = fields.stream().mapToInt(f -> f.label().length()).max().orElse(0);
        java.util.List<Object> parts = new java.util.ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            DatabasePlatform.TooltipFieldDef field = fields.get(i);
            String padded = String.format("%-" + maxLen + "s", field.label());
            if (i > 0) parts.add("\n");
            parts.add(padded + ": ");
            parts.add(resolveTooltipProperty(bean, field.propertyName()));
        }
        bindTooltip(parts.toArray());
    }

    private static ObservableValue<?> resolveTooltipProperty(Object bean, String propertyName) {
        if (bean == null || propertyName == null || propertyName.isBlank()) {
            return new javafx.beans.property.SimpleStringProperty("");
        }
        try {
            Method propertyMethod = bean.getClass().getMethod(propertyName + "Property");
            Object value = propertyMethod.invoke(bean);
            if (value instanceof ObservableValue<?> observableValue) {
                return observableValue;
            }
        } catch (Exception ignored) {
        }
        String suffix = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        for (String methodName : new String[]{"get" + suffix, "is" + suffix}) {
            try {
                Method getter = bean.getClass().getMethod(methodName);
                Object value = getter.invoke(bean);
                return new javafx.beans.property.SimpleStringProperty(value == null ? "" : String.valueOf(value));
            } catch (Exception ignored) {
            }
        }
        return new javafx.beans.property.SimpleStringProperty("");
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
                () -> isDangerousTableType(table.getTableTypeCode()),
                table.tableTypeCodeProperty()
        ));
        bindRowsSizeText(descripLabel, table.nrowsProperty(), table.totalsizeProperty());
        bindMetadataTooltip(table, DatabasePlatform.MetadataObjectType.TABLE);
    }

    private void renderView(View view, TreeData item) {
        nodeIcon.setContent(IconPaths.TREECELL_VIEW);
        nodeIcon.setScaleX(0.55);
        nodeIcon.setScaleY(0.55);
        applyPrimaryIconStyle(nodeIcon);
        bindNameLabel(item);
        descripLabel.textProperty().unbind();
        descripLabel.setText("VIEW");
        bindMetadataTooltip(view, DatabasePlatform.MetadataObjectType.VIEW);
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

        if (!(item instanceof ConnectFolder || item instanceof Connect || item instanceof Table || item instanceof View || item instanceof Catalog)) {
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
        if (item instanceof Catalog) {
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
        DatabasePlatform platform = resolveDragPlatform();
        if (item instanceof Table table) {
            String qual = qualifiedTableForDragSql(table);
            if (table.getIsfragment() == 1) {
                return platform != null
                        ? platform.metadataTreeDragFragmentTableSelectSql(qual)
                        : DatabasePlatform.defaultMetadataTreeDragStarFromSql(qual);
            }
            return platform != null
                    ? platform.metadataTreeDragTableSelectSql(qual)
                    : DatabasePlatform.defaultMetadataTreeDragTableSelectSql(qual);
        }
        if (item instanceof View v) {
            String qual = platform != null && platform.usesSchemaModel()
                    ? qualifiedViewForDragSql(v)
                    : v.getName();
            return platform != null
                    ? platform.metadataTreeDragViewSelectSql(qual)
                    : DatabasePlatform.defaultMetadataTreeDragStarFromSql(qual);
        }
        String name = item.getName();
        return platform != null
                ? platform.metadataTreeDragViewSelectSql(name)
                : DatabasePlatform.defaultMetadataTreeDragStarFromSql(name);
    }

    private DatabasePlatform resolveDragPlatform() {
        TreeItem<TreeData> ti = getTreeItem();
        if (ti == null) {
            return null;
        }
        try {
            return TreeNavigator.resolvePlatform(ti);
        } catch (Exception e) {
            return null;
        }
    }

    private static String qualifiedTableForDragSql(Table table) {
        String name = table.getName();
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.indexOf('.') >= 0) {
            return name.trim();
        }
        String owner = table.getTableOwner();
        if (owner != null && !owner.isBlank()) {
            return owner.trim() + "." + name.trim();
        }
        return name.trim();
    }

    private static String qualifiedViewForDragSql(View view) {
        String name = view.getName();
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.indexOf('.') >= 0) {
            return name.trim();
        }
        String owner = view.getOwner();
        if (owner != null && !owner.isBlank()) {
            return owner.trim() + "." + name.trim();
        }
        return name.trim();
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

    private boolean isDangerousTableType(String tableType) {
        if (tableType == null) {
            return false;
        }
        String normalized = tableType.trim();
        return "raw".equalsIgnoreCase(normalized)
                || "external".equalsIgnoreCase(normalized)
                || "nologging".equalsIgnoreCase(normalized);
    }

    private boolean isDefaultDatabase(TreeItem<TreeData> treeItem, Catalog database) {
        TreeItem<TreeData> parent = treeItem.getParent();
        if (parent == null || parent.getParent() == null) {
            return false;
        }
        TreeData connectData = parent.getParent().getValue();
        if (!(connectData instanceof Connect connect)) {
            return false;
        }
        return database.getName().equals(connect.getCatalog());
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
