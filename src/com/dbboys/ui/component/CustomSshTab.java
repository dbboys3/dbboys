package com.dbboys.ui.component;

import com.dbboys.app.AppState;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.SshConnect;
import com.dbboys.ui.controller.SshTabController;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;

import java.io.IOException;

/**
 * SSH terminal tab that opens in the SQL TabPane when double-clicking
 * an SSH connection in the tree. Extends CustomTab and loads SshTab.fxml
 * (same pattern as {@link CustomSqlTab}).
 */
public class CustomSshTab extends CustomTab {

    private final SshConnect sshConnect;
    public SshTabController controller;

    public CustomSshTab(SshConnect sshConnect) {
        super(sshConnect.getName());
        this.sshConnect = sshConnect;
        setUserData("ssh:" + sshConnect.getId());
        setTabIcon(com.dbboys.ui.icon.IconPaths.SSH_TAB_TOGGLE, 0.6);

        // Load FXML
        VBox contentVBox;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/dbboys/ui/fxml/SshTab.fxml"));
        try {
            contentVBox = loader.load();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        setContent(contentVBox);

        // Get controller and initialize with SSH connection
        controller = loader.getController();
        controller.init(sshConnect);

        // Wire connection state → tab icon color
        controller.onConnectionStateChanged = connected -> {
            javafx.application.Platform.runLater(() -> setTabIconColor(connected));
        };
        // Start with disconnected (red)
        setTabIconColor(false);

        I18n.localeProperty().addListener((obs, oldLocale, newLocale) -> refreshTooltip());
        refreshTooltip();

        // Close handler: disconnect SSH session
        setOnCloseRequest(event -> {
            // Restore double-click handler when this is the last tab
            if (AppState.getSqlTabPane().getTabs().size() == 1) {
                AppState.getSqlTabPane().setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                        com.dbboys.infra.util.TabpaneUtil.addCustomSqlTab(null);
                    }
                });
            }
            controller.closeSession();
        });
    }

    @Override
    protected void toggleMaximize() {
        super.toggleMaximize();
    }

    @Override
    public void requestSave() {
        // SSH tabs don't support file save, but we override to avoid super behavior
    }

    private void refreshTooltip() {
        setTooltip(new Tooltip(sshConnect.getUsername() + "@"
                + sshConnect.getHost() + ":" + sshConnect.getPort()));
    }

    /** Toggle tab icon between default (connected) and red (disconnected). */
    private void setTabIconColor(boolean connected) {
        if (getGraphic() instanceof javafx.scene.layout.StackPane) {
            javafx.scene.layout.StackPane stack = (javafx.scene.layout.StackPane) getGraphic();
            if (!stack.getChildren().isEmpty() && stack.getChildren().get(0) instanceof javafx.scene.shape.SVGPath) {
                javafx.scene.shape.SVGPath svg = (javafx.scene.shape.SVGPath) stack.getChildren().get(0);
                svg.getStyleClass().removeAll("icon-primary", "icon-success", "icon-danger");
                svg.getStyleClass().add(connected ? "icon-primary" : "icon-danger");
            }
        }
    }
}
