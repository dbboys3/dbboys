package com.dbboys.ctrl;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.api.ConnectionService;
import com.dbboys.service.SqlexeService;
import com.dbboys.ui.IconPaths;
import com.dbboys.db.ConnectionErrorHandler;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.TreeData;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SqlConnectionHandler {
    private static final Logger log = LogManager.getLogger(SqlConnectionHandler.class);

    private final SqlTabController ctrl;
    private final ConnectionService connectionService;
    private final SqlexeService sqlexeService;

    public SqlConnectionHandler(SqlTabController ctrl, ConnectionService connectionService, SqlexeService sqlexeService) {
        this.ctrl = ctrl;
        this.connectionService = connectionService;
        this.sqlexeService = sqlexeService;
    }

    public void setupConnectionListener() {
        ctrl.sqlConnectChoiceBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (ctrl.suppressConnectChange) {
                        return;
                    }
                    if (newVal == ctrl.defaultConnect) {
                        handleDefaultConnectSelected();
                    } else {
                        handleConnectChanged(oldVal, newVal);
                    }
                });
    }

    private void handleDefaultConnectSelected() {
        ctrl.sqlConnect.setConn(null);
        ctrl.sqlConnectIconPath.setContent(IconPaths.CONNECTION_LINK);
        ctrl.sqlConnectChoiceBoxDbIcon.setGraphic(ctrl.sqlConnectIconPath);
        ctrl.sqlConnectIconPath.setScaleX(0.6);
        ctrl.sqlConnectIconPath.setScaleY(0.6);
        ctrl.sqlConnectChoiceBoxDbIcon.setVisible(true);
        ctrl.sqlConnectChoiceBoxLoadingIcon.setVisible(false);
        try {
            ctrl.sqlDbChoiceBox.getItems().clear();
            ctrl.sqlDbChoiceBox.setValue(ctrl.defaultDatabase);
        } finally {
        }
        ctrl.sqlUserTextField.setText("N/A");
        ctrl.sqlSqlModeChoiceBox.setVisible(false);
        ctrl.sqlCommitModeChoiceBox.getSelectionModel().select(0);
        ctrl.sqlCommitModeChoiceBox.setVisible(true);
        ctrl.sqlRecordButton.setVisible(true);
        ctrl.sqlReadOnlyLabel.setVisible(false);
    }

    private void handleConnectChanged(Connect oldVal, Connect newVal) {
        AppExecutor.runAsync(() -> {
            Platform.runLater(() -> {
                ctrl.sqlConnectChoiceBoxDbIcon.setVisible(false);
                ctrl.sqlConnectChoiceBoxLoadingIcon.setVisible(true);
            });
            Connection conn = null;
            try {
                conn = connectionService.getConnection(newVal);
                connectionService.changeCommitMode(conn, ctrl.sqlCommitModeChoiceBox.getSelectionModel().getSelectedIndex());
            } catch (Exception e) {
                AppErrorHandler.handle(e);
            }
            if (conn != null) {
                if (ctrl.sqlConnect.getConn() != null)
                    try {
                        ctrl.closeResultSet();
                        ctrl.sqlConnect.getConn().close();
                    } catch (SQLException e) {
                        AppErrorHandler.handle(e);
                    }
                ctrl.sqlConnect = newVal;
                ctrl.sqlConnect.setConn(conn);
                ctrl.currentResultSetTabController.sqlConnect = ctrl.sqlConnect;

                refreshMetaTreeForConnect();
                updateConnectIcon();
                loadDatabasesForConnect();
            } else {
                if (oldVal == ctrl.defaultConnect)
                    Platform.runLater(() -> ctrl.sqlConnectChoiceBox.setValue(ctrl.defaultConnect));
                else
                    Platform.runLater(() -> ctrl.sqlConnectChoiceBox.setValue(ctrl.sqlConnect));
            }
        });
    }

    private void refreshMetaTreeForConnect() {
        for (TreeItem<TreeData> ti : AppState.getDatabaseMetaTreeView().getRoot().getChildren()) {
            for (TreeItem<TreeData> t : ti.getChildren()) {
                Connect connect = (Connect) t.getValue();
                if (t.getValue().getName().equals(ctrl.sqlConnect.getName())) {
                    try {
                        if (connect.getConn() == null || connect.getConn().isClosed()) {
                            t.setExpanded(false);
                            t.setExpanded(true);
                        } else {
                            ResultSet rs = null;
                            try {
                                rs = connect.getConn().createStatement().executeQuery("select first 1 tabid from systables");
                            } catch (SQLException e) {
                                connect.setConn(null);
                                t.setExpanded(false);
                                t.setExpanded(true);
                            } finally {
                                if (rs != null) {
                                    try {
                                        rs.close();
                                    } catch (SQLException e) {
                                        rs = null;
                                    }
                                }
                            }
                        }
                    } catch (SQLException e) {
                        AppErrorHandler.handle(e);
                    }
                }
            }
        }
    }

    private void updateConnectIcon() {
        Platform.runLater(() -> {
            if (ctrl.sqlConnect.getDbtype().equals("GBASE 8S")) {
                ctrl.sqlConnectIconPath.setContent(IconPaths.GBASE_LOGO);
                ctrl.sqlConnectChoiceBoxDbIcon.setGraphic(new Group(ctrl.sqlConnectIconPath));
                ctrl.sqlConnectIconPath.setScaleX(0.2);
                ctrl.sqlConnectIconPath.setScaleY(0.2);
            } else {
                ctrl.sqlConnectIconPath.setContent(IconPaths.CONNECTION_LINK);
                ctrl.sqlConnectChoiceBoxDbIcon.setGraphic(new Group(ctrl.sqlConnectIconPath));
                ctrl.sqlConnectIconPath.setScaleX(0.6);
                ctrl.sqlConnectIconPath.setScaleY(0.6);
            }

            if (ctrl.sqlConnect.getReadonly()) {
                ctrl.sqlRecordButton.setVisible(false);
                ctrl.sqlCommitModeChoiceBox.setVisible(false);
                ctrl.sqlReadOnlyLabel.setVisible(true);
            } else {
                ctrl.sqlRecordButton.setVisible(true);
                ctrl.sqlCommitModeChoiceBox.setVisible(true);
                ctrl.sqlReadOnlyLabel.setVisible(false);
            }
        });
    }

    private void loadDatabasesForConnect() {
        List<Database> db_names = sqlexeService.getDatabases(ctrl.sqlConnect);
        ctrl.databaseChoiceBoxList = FXCollections.observableArrayList(db_names);
        Platform.runLater(() -> {
            try {
                ctrl.sqlDbChoiceBox.setValue(ctrl.defaultDatabase);
                ctrl.sqlDbChoiceBox.setItems(ctrl.databaseChoiceBoxList);
                int i = 0;
                for (Database item : ctrl.databaseChoiceBoxList) {
                    if (item.getName().equals(ctrl.sqlConnect.getDatabase())) {
                        ctrl.sqlDbChoiceBox.getSelectionModel().select(i);
                        break;
                    }
                    i++;
                }
            } finally {
            }
            ctrl.sqlUserTextField.setText(ctrl.sqlConnect.getUsername());
            ctrl.sqlConnectChoiceBoxDbIcon.setVisible(true);
            ctrl.sqlConnectChoiceBoxLoadingIcon.setVisible(false);
        });
    }

    public void setupDatabaseListener() {
        ctrl.sqlDbChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            log.info("oldvalue is:" + oldValue);
            log.info("newValue is:" + newValue);
            if (ctrl.suppressDbChange) {
                return;
            }
            if (newValue == null) {
                return;
            }

            if (!newValue.equals(oldValue) && !newValue.equals(ctrl.defaultDatabase)) {
                String result = "success";
                if (oldValue != null && !oldValue.equals(ctrl.defaultDatabase)) {
                    result = sqlexeService.activeDatabase(ctrl.sqlConnect, newValue, ctrl);
                }
                if (result == null) {
                    Platform.runLater(() -> {
                        ctrl.suppressDbChange = true;
                        ctrl.sqlDbChoiceBox.setValue(oldValue);
                        ctrl.suppressDbChange = false;
                    });
                } else if (result.equals("success")) {
                    ctrl.updateSqlModeChoicebox(sqlexeService.getSqlMode(ctrl.sqlConnect.getConn()));
                    if (ctrl.sqlCommitModeChoiceBox.getSelectionModel().getSelectedIndex() == 1) {
                        try {
                            ctrl.sqlConnect.getConn().setAutoCommit(false);
                        } catch (SQLException e) {
                            log.error(e.getMessage(), e);
                        }
                    }
                    if (!ctrl.sqlInit.isEmpty()) {
                        Platform.runLater(() -> {
                            ctrl.sqlEditCodeArea.appendText(ctrl.sqlInit);
                            ctrl.sqlEditCodeArea.selectRange(0, ctrl.sqlEditCodeArea.getLength());
                            ctrl.sqlRunButton.fire();
                            ctrl.sqlInit = "";
                        });
                    }
                } else if (result.equals("disconnected")) {
                    ctrl.connectionDisconnected();
                }
            }
        });
    }

    public void setupCommitModeListener() {
        ctrl.sqlCommitModeChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (ctrl.suppressCommitModeChange) {
                return;
            }
            if (ctrl.sqlConnect.getConn() != null) {
                try {
                    ctrl.sqlConnect.getConn().setAutoCommit(ctrl.sqlCommitModeChoiceBox.getSelectionModel().getSelectedIndex() == 0);
                } catch (SQLException e) {
                    log.error(e.getMessage(), e);
                    if (ConnectionErrorHandler.isDisconnectError(e)) {
                        ctrl.connectionDisconnected();
                    } else {
                        AppErrorHandler.handle(e);
                        Platform.runLater(() -> {
                            ctrl.suppressCommitModeChange = true;
                            ctrl.sqlCommitModeChoiceBox.getSelectionModel().select(oldValue);
                            ctrl.suppressCommitModeChange = false;
                        });
                    }
                }
            }
        });
    }

    public void setupDefaultConnectionState() {
        ctrl.defaultConnect.setName(I18n.t("sql.connect.select_prompt"));
        ctrl.defaultDatabase.setName(I18n.t("common.na"));
        ctrl.sqlConnectChoiceBox.setValue(ctrl.defaultConnect);
        ctrl.sqlDbChoiceBox.setValue(ctrl.defaultDatabase);
        ctrl.sqlUserTextField.setText(I18n.t("common.na"));
        ctrl.sqlCommitModeChoiceBox.getSelectionModel().selectFirst();

        ctrl.sqlEditCodeArea.setOnExecuteRequest(() -> ctrl.sqlRunButton.fire());
        ctrl.sqlEditCodeArea.setExecuteDisabledSupplier(ctrl.sqlRunButton::isDisable);
        ctrl.sqlConnect = ctrl.defaultConnect;
    }

    public void loadConnectChoices() {
        List<Connect> connect_list = new ArrayList<>();
        for (TreeItem<TreeData> ti : AppState.getDatabaseMetaTreeView().getRoot().getChildren()) {
            for (TreeItem<TreeData> t : ti.getChildren()) {
                Connect newConnect = new Connect((Connect) t.getValue());
                newConnect.setConn(null);
                connect_list.add(newConnect);
            }
        }
        ObservableList<Connect> dbtypelist = FXCollections.observableArrayList(connect_list);
        ctrl.sqlConnectChoiceBox.setItems(dbtypelist);
        if (ctrl.sqlConnectChoiceBox.getValue() == null) {
            ctrl.sqlConnectChoiceBox.setValue(ctrl.defaultConnect);
        }
    }

    public void bindHeaderControls() {
        ctrl.sqlConnectChoiceBox.disableProperty().bind(Bindings.or(
                ctrl.transactionBox.visibleProperty(),
                ctrl.sqlExecuteProcessStackPane.visibleProperty()
        ));
        ctrl.transactionBox.visibleProperty().bind(
                Bindings.notEqual("", ctrl.sqlTransactionText)
        );
        ctrl.sqlDbChoiceBox.disableProperty().bind(ctrl.sqlConnectChoiceBox.disableProperty());
        ctrl.sqlSqlModeChoiceBox.disableProperty().bind(ctrl.sqlConnectChoiceBox.disableProperty());
        ctrl.sqlCommitModeChoiceBox.disableProperty().bind(ctrl.sqlConnectChoiceBox.disableProperty());
        ctrl.sqlUserTextField.disableProperty().bind(ctrl.sqlConnectChoiceBox.disableProperty());

        ctrl.sqlStopButton.disableProperty().bind(ctrl.sqlExecuteProcessStackPane.visibleProperty().not());
        ctrl.sqlRunButton.disableProperty().bind(ctrl.sqlExecuteProcessStackPane.visibleProperty());
        ctrl.sqlExplainButton.disableProperty().bind(ctrl.sqlExecuteProcessStackPane.visibleProperty());

        ctrl.resultsetTabPane.prefWidthProperty().bind(ctrl.bottomPane.widthProperty());
        ctrl.resultsetTabPane.prefHeightProperty().bind(ctrl.bottomPane.heightProperty());
        ctrl.resultsetTotalTableView.prefWidthProperty().bind(ctrl.bottomPane.widthProperty());
        ctrl.resultsetTotalTableView.prefHeightProperty().bind(ctrl.bottomPane.heightProperty());
        ctrl.resultsetTotalTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        ctrl.resultsetTotalTableView.getSelectionModel().setCellSelectionEnabled(true);
        ctrl.resultsetTotalTableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
    }

    public void initializeConnectList() {
        List<Connect> connect_list = new ArrayList<>();
        for (TreeItem<TreeData> ti : AppState.getDatabaseMetaTreeView().getRoot().getChildren()) {
            for (TreeItem<TreeData> t : ti.getChildren()) {
                if (t.getValue().getName().equals(ctrl.sqlConnectChoiceBox.getSelectionModel().getSelectedItem().getName())) {
                } else {
                    Connect newConnect = new Connect((Connect) t.getValue());
                    newConnect.setConn(null);
                    connect_list.add(newConnect);
                }
            }
        }
        ObservableList<Connect> dbtypelist = FXCollections.observableArrayList(connect_list);
        ctrl.sqlConnectChoiceBox.getItems().retainAll(ctrl.sqlConnectChoiceBox.getSelectionModel().getSelectedItem());
        ctrl.sqlConnectChoiceBox.getItems().addAll(dbtypelist);
    }
}
