package com.dbboys.ui.component;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.app.AppContext;
import com.dbboys.core.DatabasePlatforms;
import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.infra.util.TabpaneUtil;
import com.dbboys.model.Connect;
import com.dbboys.model.SpaceUsage;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseButton;

public class CustomInstanceInfoTableView extends CustomTableView {
    private static final int TAB_INSTANCE_INFO = 0;
    private static final int TAB_HEALTH_CHECK = 1;
    private static final int TAB_ONLINE_LOG = 2;
    private static final int TAB_SPACE_MANAGER = 3;
    private static final int TAB_ONCONFIG = 4;
    private static final int TAB_INSTANCE_START_STOP = 5;

    public CustomInstanceInfoTableView() {
        super();
        configureColumns();
        configureContextMenu();
        configureRowAction();
    }

    private void configureColumns() {
        TableColumn<Connect, String> nameColumn = createColumn(
                "createconnect.label.name",
                "连接名称",
                "name",
                IconPaths.CONNECTION_LINK,
                0.4
        );
        TableColumn<Connect, String> dbTypeColumn = createColumn(
                "createconnect.label.db_type",
                "数据库类型",
                "dbtype",
                IconPaths.TREECELL_DATABASE,
                0.3
        );
        TableColumn<Connect, String> dbVersionColumn = createColumn(
                "instance.table.column.db_version",
                "数据库版本",
                "dbversion",
                IconPaths.METADATA_UPDATE_STATISTICS_ITEM,
                0.45
        );
        TableColumn<Connect, String> ipColumn = createColumn(
                "createconnect.label.ip",
                "地址",
                "ip",
                IconPaths.CREATE_CONNECT_IP,
                0.5
        );
        TableColumn<Connect, String> databaseColumn = createColumn(
                "sql.table.sample.database",
                "库名",
                "catalog",
                IconPaths.TREECELL_DATABASE_FOLDER,
                0.09
        );
        TableColumn<Connect, String> portColumn = createColumn(
                "createconnect.label.port",
                "端口",
                "port",
                IconPaths.CREATE_CONNECT_PORT,
                0.35
        );
        TableColumn<Connect, String> usernameColumn = createColumn(
                "createconnect.label.username",
                "用户名",
                "username",
                IconPaths.TREECELL_USER_FOLDER,
                0.45
        );
        TableColumn<Connect, String> readonlyColumn = createColumn(
                "createconnect.label.read_only",
                "只读",
                "readonly",
                IconPaths.TREECELL_LOCK,
                0.4
        );

        ((TableColumn<?, ?>) getColumns().get(0)).setMinWidth(30);
        ((TableColumn<?, ?>) getColumns().get(0)).setMaxWidth(30);

        getColumns().addAll(
                nameColumn,
                dbTypeColumn,
                dbVersionColumn,
                ipColumn,
                portColumn,
                databaseColumn,
                usernameColumn,
                readonlyColumn
        );

        setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    private void configureContextMenu() {
        ContextMenu contextMenu = getContextMenu();
        MenuItem inheritedCopyItem = contextMenu.getItems().isEmpty() ? null : contextMenu.getItems().get(0);
        contextMenu.getItems().clear();

        CustomShortcutMenuItem newSqlItem = MenuItemUtil.createMenuItemI18n(
                "metadata.menu.new_sql",
                IconFactory.group(IconPaths.MAIN_MENU_NEW_SQL, 0.6)
        );
        CustomShortcutMenuItem instanceInfoItem = MenuItemUtil.createMenuItemI18n(
                "metadata.menu.instance_info",
                IconFactory.group(IconPaths.METADATA_CONNECT_INFO_ITEM, 0.55)
        );
        CustomShortcutMenuItem healthCheckItem = MenuItemUtil.createMenuItemI18n(
                "metadata.menu.health_check",
                IconFactory.group(IconPaths.METADATA_HEALTH_CHECK_ITEM, 0.65)
        );
        CustomShortcutMenuItem onlineLogItem = MenuItemUtil.createMenuItemI18n(
                "metadata.menu.online_log",
                IconFactory.group(IconPaths.METADATA_ONLINE_LOG_ITEM, 0.5)
        );
        CustomShortcutMenuItem spaceManagerItem = MenuItemUtil.createMenuItemI18n(
                "metadata.menu.space_manager",
                IconFactory.group(IconPaths.METADATA_SPACE_MANAGER_ITEM, 0.55)
        );
        CustomShortcutMenuItem onconfigItem = MenuItemUtil.createMenuItemI18n(
                "metadata.menu.onconfig",
                IconFactory.group(IconPaths.METADATA_ONCONFIG_ITEM, 0.55)
        );
        CustomShortcutMenuItem instanceStartStopItem = MenuItemUtil.createMenuItemI18n(
                "metadata.menu.instance_start_stop",
                IconFactory.groupFixedColor(IconPaths.METADATA_INSTANCE_STOP_ITEM, 0.65, IconFactory.stopColor())
        );
        CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n(
                "genericstyled.menu.copy",
                "Ctrl+C",
                IconFactory.group(IconPaths.COPY, 0.7)
        );

        newSqlItem.setOnAction(event -> {
            Connect selectedConnect = getSelectedConnect();
            if (selectedConnect != null) {
                TabpaneUtil.addCustomSqlTab(selectedConnect);
            }
        });
        instanceInfoItem.setOnAction(event -> openInstanceTab(TAB_INSTANCE_INFO));
        healthCheckItem.setOnAction(event -> openInstanceTab(TAB_HEALTH_CHECK));
        onlineLogItem.setOnAction(event -> openInstanceTab(TAB_ONLINE_LOG));
        spaceManagerItem.setOnAction(event -> openInstanceTab(TAB_SPACE_MANAGER));
        onconfigItem.setOnAction(event -> openInstanceTab(TAB_ONCONFIG));
        instanceStartStopItem.setOnAction(event -> openInstanceTab(TAB_INSTANCE_START_STOP));
        copyItem.setOnAction(event -> {
            if (inheritedCopyItem != null) {
                inheritedCopyItem.fire();
            }
        });

        contextMenu.getItems().addAll(
                newSqlItem,
                instanceInfoItem,
                healthCheckItem,
                onlineLogItem,
                spaceManagerItem,
                onconfigItem,
                instanceStartStopItem,
                copyItem
        );
        contextMenu.setOnShowing(event -> {
            Connect selectedConnect = getSelectedConnect();
            InstanceAdminRepository admin = resolveAdminRepository(selectedConnect);
            boolean showInstanceMenu = selectedConnect != null && admin.supportsAdminFeatures(selectedConnect);
            instanceInfoItem.setVisible(showInstanceMenu);
            healthCheckItem.setVisible(showInstanceMenu);
            onlineLogItem.setVisible(showInstanceMenu);
            spaceManagerItem.setVisible(showInstanceMenu);
            onconfigItem.setVisible(showInstanceMenu);
            instanceStartStopItem.setVisible(showInstanceMenu && admin.supportsStartStop(selectedConnect));
            if (showInstanceMenu) {
                healthCheckItem.setDisable(!admin.supportsHealthCheck(selectedConnect));
                onlineLogItem.setDisable(!admin.supportsOnlineLog(selectedConnect));
                spaceManagerItem.setDisable(!admin.supportsSpaceManager(selectedConnect));
                onconfigItem.setDisable(!admin.supportsConfigManagement(selectedConnect));
            }
            copyItem.setDisable(getSelectionModel().getSelectedCells().isEmpty());
        });

        setContextMenu(contextMenu);
    }

    private void configureRowAction() {
        setRowFactory(tableView -> {
            TableRow<Connect> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY && !row.isEmpty()) {
                    Connect c = row.getItem();
                    if (c != null && c.getDbtype() != null
                            && "GENERAL JDBC".equalsIgnoreCase(c.getDbtype().trim())) {
                        TabpaneUtil.addCustomSqlTab(new Connect(c));
                    } else {
                        TabpaneUtil.addCustomInstanceTab(row.getItem(), TAB_INSTANCE_INFO);
                    }
                }
            });
            return row;
        });
    }

    private TableColumn<Connect, String> createColumn(
            String i18nKey,
            String fallback,
            String propertyName,
            String iconPath,
            double iconScale
    ) {
        TableColumn<Connect, String> column = new TableColumn<>();
        column.textProperty().bind(I18n.bind(i18nKey, fallback));
       // column.setGraphic(IconFactory.group(iconPath, iconScale));
        column.setCellValueFactory(new PropertyValueFactory<>(propertyName));
        column.setCellFactory(col -> {
            CustomTableCell<Connect, String> cell = new CustomTableCell<>();
            cell.setContextMenu(null);
            return cell;
        });
        column.setSortable(false);
        column.setReorderable(false);
        return column;
    }

    private Connect getSelectedConnect() {
        return (Connect) getSelectionModel().getSelectedItem();
    }

    private void openInstanceTab(int tabIndex) {
        Connect selectedConnect = getSelectedConnect();
        if (selectedConnect != null) {
            TabpaneUtil.addCustomInstanceTab(selectedConnect, tabIndex);
        }
    }

    private InstanceAdminRepository resolveAdminRepository(Connect connect) {
        if (connect == null) {
            return new InstanceAdminRepository() {
                @Override
                public void setStorageSegmentExtendable(java.sql.Connection conn, int segmentId, boolean extendable) {
                }

                @Override
                public void resizeStorageSpace(java.sql.Connection conn, String storageSpaceName, int size1, int size2, int size3) {
                }

                @Override
                public java.util.List<java.util.List<SpaceUsage>> getStorageSpaceUsage(java.sql.Connection conn) {
                    return java.util.List.of();
                }

                @Override
                public double getMaxStorageSpaceUsage(java.sql.Connection conn) {
                    return 0;
                }
            };
        }
        return resolvePlatformResolver().admin(connect);
    }

    private DatabasePlatformResolver resolvePlatformResolver() {
        try {
            return AppContext.get(DatabasePlatformResolver.class);
        } catch (IllegalStateException e) {
            return DatabasePlatforms.createDefault();
        }
    }
}
