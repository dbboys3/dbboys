package com.dbboys.ui.component;

import com.dbboys.core.InstanceAdminRepository;
import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.service.AdminService;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.ui.dialog.PopupWindowUtil;
import com.dbboys.model.Connect;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

import java.util.List;

public class CustomLockSessionTab extends CustomTab {
    private final AdminService adminService = new AdminService();
    private final Connect connect;
    private final String databaseName;
    private final String tableName;
    private final CustomTableView<ObservableList<String>> tableView = new CustomTableView<>();
    private final Button refreshButton = new Button();
    private final ImageView loadingIcon = IconFactory.loadingImageView(0.7);
    private int ownerColumnIndex = -1;
    private volatile boolean sessionDetailLoading = false;

    public CustomLockSessionTab(Connect connect, String tabTitle, String databaseName, String tableName) {
        super(tabTitle);
        this.connect = connect;
        this.databaseName = databaseName == null ? "" : databaseName;
        this.tableName = tableName == null ? "" : tableName;

        refreshButton.getStyleClass().add("codearea-camera-button");
        refreshButton.setGraphic(IconFactory.group(IconPaths.METADATA_REFRESH_ITEM, 0.7));
        refreshButton.setFocusTraversable(false);
        refreshButton.setOnAction(event -> loadLocks());

        StackPane root = new StackPane(tableView, loadingIcon, refreshButton);
        root.setPadding(new Insets(0));
        StackPane.setAlignment(loadingIcon, Pos.CENTER);
        StackPane.setAlignment(refreshButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(refreshButton, new Insets(0, 15, 20, 0));
        setContent(root);
        tableView.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                ObservableList<String> row = resolveSelectedRow();
                if (row != null) {
                    showSessionDetailFromRow(row);
                    event.consume();
                }
            }
        });

        loadLocks();
    }

    private void loadLocks() {
        refreshButton.setDisable(true);
        loadingIcon.setVisible(true);
        tableView.setPlaceholder(new Label(""));
        AppExecutor.runAsync(() -> {
            try {
                InstanceAdminRepository.LockSessionResult result = adminService.getLockSessions(connect, databaseName, tableName);
                Platform.runLater(() -> applyResult(result));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    loadingIcon.setVisible(false);
                    tableView.setPlaceholder(new Label(e.getMessage()));
                    AlertUtil.CustomAlert(I18n.t("common.error", "Error"), e.getMessage());
                });
            }
        });
    }

    private void applyResult(InstanceAdminRepository.LockSessionResult result) {
        ownerColumnIndex = findColumnIndex(result.columns(), "owner");
        tableView.getColumns().setAll(tableView.getColumns().get(0));
        for (int i = 0; i < result.columns().size(); i++) {
            final int columnIndex = i;
            TableColumn<ObservableList<String>, Object> column = new TableColumn<>(result.columns().get(i));
            column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().get(columnIndex)));
            column.setCellFactory(col -> {
                CustomTableCell<ObservableList<String>, Object> cell = new CustomTableCell<>();
                cell.setTextOverrun(OverrunStyle.CLIP);
                cell.setContextMenu(createKillSessionMenu(cell));
                return cell;
            });
            column.setPrefWidth(Math.max(90, result.columns().get(i).length() * 12.0));
            tableView.getColumns().add(column);
        }

        ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
        for (List<String> row : result.rows()) {
            data.add(FXCollections.observableArrayList(row));
        }
        tableView.setItems(data);
        tableView.setPlaceholder(new Label(I18n.t("locksession.empty", "No lock sessions")));
        refreshButton.setDisable(false);
        loadingIcon.setVisible(false);
    }

    private ContextMenu createKillSessionMenu(CustomTableCell<ObservableList<String>, Object> cell) {
        CustomShortcutMenuItem killItem = MenuItemUtil.createMenuItemI18n(
                "locksession.menu.kill",
                null
        );
        killItem.setOnAction(event -> killSessionFromRow(cell.getTableRow() == null ? null : cell.getTableRow().getItem()));
        ContextMenu contextMenu = new CustomContextMenu(killItem);
        contextMenu.setOnShowing(event -> {
            ObservableList<String> row = cell.getTableRow() == null ? null : cell.getTableRow().getItem();
            killItem.setDisable(!canKillSession() || resolveOwner(row).isEmpty());
        });
        return contextMenu;
    }

    private void killSessionFromRow(ObservableList<String> row) {
        String owner = resolveOwner(row);
        if (owner.isEmpty()) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "Error"),
                    I18n.t("locksession.error.owner_missing", "当前行没有 owner 值，无法 KILL 会话。")
            );
            return;
        }
        if (!owner.matches("\\d+")) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "Error"),
                    I18n.t("locksession.error.owner_invalid", "owner 值不是合法会话号：%s").formatted(owner)
            );
            return;
        }
        if (!AlertUtil.CustomAlertConfirm(
                I18n.t("locksession.confirm.kill.title", "KILL会话"),
                I18n.t("locksession.confirm.kill.content", "确定要执行 %s 吗？")
                        .formatted(adminService.killLockSessionCommand(connect, owner))
        )) {
            return;
        }

        refreshButton.setDisable(true);
        loadingIcon.setVisible(true);
        AppExecutor.runAsync(() -> {
            try {
                adminService.killLockSession(connect, owner);
                Platform.runLater(() -> {
                    NotificationUtil.showMainNotification(
                            I18n.t("locksession.notice.kill_done", "KILL会话执行完成。")
                    );
                    loadLocks();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    loadingIcon.setVisible(false);
                    AlertUtil.CustomAlert(I18n.t("common.error", "Error"), e.getMessage());
                });
            }
        });
    }

    private void showSessionDetailFromRow(ObservableList<String> row) {
        if (sessionDetailLoading) {
            return;
        }
        if (!adminService.canShowLockSessionDetail(connect)) {
            return;
        }
        String sid = resolveOwner(row);
        if (sid.isEmpty()) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "Error"),
                    I18n.t("locksession.error.sid_missing", "当前行没有会话号，无法查看会话详情。")
            );
            return;
        }
        if (!sid.matches("\\d+")) {
            AlertUtil.CustomAlert(
                    I18n.t("common.error", "Error"),
                    I18n.t("locksession.error.sid_invalid", "会话号不是合法数字：%s").formatted(sid)
            );
            return;
        }

        sessionDetailLoading = true;
        refreshButton.setDisable(true);
        loadingIcon.setVisible(true);
        AppExecutor.runAsync(() -> {
            String command = adminService.lockSessionDetailCommand(connect, sid);
            try {
                String output = adminService.getLockSessionDetail(connect, sid);
                Platform.runLater(() -> {
                    sessionDetailLoading = false;
                    refreshButton.setDisable(false);
                    loadingIcon.setVisible(false);
                    PopupWindowUtil.openCmdOutputPopupWindow(command + "\n\n" + output);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sessionDetailLoading = false;
                    refreshButton.setDisable(false);
                    loadingIcon.setVisible(false);
                    AlertUtil.CustomAlert(I18n.t("common.error", "Error"), e.getMessage());
                });
            }
        });
    }

    private String resolveOwner(ObservableList<String> row) {
        if (row == null || ownerColumnIndex < 0 || ownerColumnIndex >= row.size()) {
            return "";
        }
        String owner = row.get(ownerColumnIndex);
        return owner == null ? "" : owner.trim();
    }

    private ObservableList<String> resolveSelectedRow() {
        ObservableList<TablePosition> selectedCells = tableView.getSelectionModel().getSelectedCells();
        if (!selectedCells.isEmpty()) {
            int rowIndex = selectedCells.get(0).getRow();
            if (rowIndex >= 0 && rowIndex < tableView.getItems().size()) {
                return tableView.getItems().get(rowIndex);
            }
        }
        return tableView.getSelectionModel().getSelectedItem();
    }

    private int findColumnIndex(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            if (column != null && name.equalsIgnoreCase(column.trim())) {
                return i;
            }
        }
        return -1;
    }

    private boolean canKillSession() {
        return adminService.canKillLockSession(connect);
    }
}
