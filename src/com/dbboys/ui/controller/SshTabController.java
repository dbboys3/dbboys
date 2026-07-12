package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.JschUtil;
import com.dbboys.ssh.SshConnect;
import com.dbboys.ui.component.CustomSqlEditCodeArea;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.jcraft.jsch.Session;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Future;

/**
 * Controller for the SSH terminal tab (SshTab.fxml).
 * Manages SSH session lifecycle and command execution.
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);

    @FXML
    public CustomSqlEditCodeArea commandInput;
    @FXML
    public CustomSqlEditCodeArea outputArea;
    @FXML
    public Button connectButton;
    @FXML
    public Button disconnectButton;
    @FXML
    public Button runButton;
    @FXML
    public Button stopButton;
    @FXML
    public Label connectionLabel;
    @FXML
    public SplitPane splitPane;
    @FXML
    public VBox sshTab;

    private SshConnect sshConnect;
    private Session session;
    private Future<?> currentExecution;
    private final StringProperty connectStatus = new SimpleStringProperty();

    public void initialize() {
        // Icons
        connectButton.setGraphic(IconFactory.group(IconPaths.CONNECTION_LINK, 0.65, Color.GREEN));
        disconnectButton.setGraphic(IconFactory.group(IconPaths.CONNECTION_LINK, 0.65, Color.RED));
        runButton.setGraphic(IconFactory.group(IconPaths.SQL_RUN, 0.6, Color.GREEN));
        stopButton.setGraphic(IconFactory.group(IconPaths.SQL_STOP, 0.6, Color.RED));

        // Tooltips
        connectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.connect", "Connect")));
        disconnectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.disconnect", "Disconnect")));
        runButton.setTooltip(new Tooltip(I18n.t("ssh.tab.execute", "Execute (Ctrl+Enter)")));
        stopButton.setTooltip(new Tooltip(I18n.t("ssh.tab.stop", "Stop")));

        disconnectButton.setDisable(true);
        stopButton.setDisable(true);

        // Connect button
        connectButton.setOnAction(e -> doConnect());

        // Disconnect button
        disconnectButton.setOnAction(e -> doDisconnect());

        // Run button
        runButton.setOnAction(e -> executeCommand());

        // Stop button
        stopButton.setOnAction(e -> stopExecution());

        // Output area: read-only, monospace-like styling
        outputArea.setEditable(false);

        // Ctrl+Enter handler on command input
        commandInput.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                executeCommand();
                event.consume();
            }
        });

        // Connection status binding
        connectStatus.addListener((obs, o, n) -> connectionLabel.setText(n));
        connectStatus.set(I18n.t("ssh.tab.disconnected", "Disconnected"));

        // Default split pane divider
        splitPane.setDividerPositions(0.6);

        // Output area context menu for clearing
        ContextMenu outputMenu = new ContextMenu();
        MenuItem clearItem = new MenuItem(I18n.t("ssh.tab.clear_output", "Clear Output"));
        clearItem.setOnAction(e -> outputArea.clear());
        outputMenu.getItems().add(clearItem);
        outputArea.setContextMenu(outputMenu);
    }

    /**
     * Initialize with an SSH connection configuration.
     */
    public void init(SshConnect sc) {
        this.sshConnect = sc;
        connectStatus.set(sc.getUsername() + "@" + sc.getHost() + ":" + sc.getPort());
    }

    // ---- Connection management ----

    private void doConnect() {
        if (sshConnect == null) return;
        connectButton.setDisable(true);
        connectStatus.set(I18n.t("ssh.tab.connecting", "Connecting..."));
        AppExecutor.runAsync(() -> {
            try {
                session = JschUtil.getSshSession(sshConnect);
                Platform.runLater(() -> {
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connected", "Connected") + "]");
                    appendOutput("[" + I18n.t("ssh.tab.connected", "Connected") + " to "
                            + sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + "]\n");
                });
            } catch (Exception ex) {
                log.error("SSH connect failed", ex);
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    disconnectButton.setDisable(true);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connect_failed", "Connect Failed") + "]");
                    appendOutput("[ERROR] " + ex.getMessage() + "\n");
                });
            }
        });
    }

    private void doDisconnect() {
        JschUtil.disconnectSession(session);
        session = null;
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
        if (sshConnect != null) {
            connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                    + ":" + sshConnect.getPort() + " ["
                    + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        }
        appendOutput("[" + I18n.t("ssh.tab.disconnected", "Disconnected") + "]\n");
    }

    // ---- Command execution ----

    private void executeCommand() {
        if (session == null || !session.isConnected()) {
            appendOutput("[ERROR] " + I18n.t("ssh.tab.not_connected",
                    "Not connected to SSH server.") + "\n");
            return;
        }

        // Get text: selected text, or current line
        String command = commandInput.getSelectedText();
        if (command == null || command.isEmpty()) {
            // Get current line at caret
            int caretPos = commandInput.getCaretPosition();
            String text = commandInput.getText();
            if (text == null || text.isEmpty()) return;

            // Find line boundaries
            int lineStart = text.lastIndexOf('\n', caretPos - 1) + 1;
            int lineEnd = text.indexOf('\n', caretPos);
            if (lineEnd == -1) lineEnd = text.length();
            command = text.substring(lineStart, lineEnd).trim();
        } else {
            command = command.trim();
        }

        if (command.isEmpty()) return;

        final String cmd = command;
        appendOutput("$ " + cmd + "\n");

        runButton.setDisable(true);
        stopButton.setDisable(false);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return JschUtil.executeCommand(session, cmd, true);
            }
        };

        task.setOnSucceeded(e -> {
            String result = task.getValue();
            if (result != null && !result.isEmpty()) {
                appendOutput(result + "\n");
            }
            runButton.setDisable(false);
            stopButton.setDisable(true);
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            log.error("SSH command failed", ex);
            appendOutput("[ERROR] " + ex.getMessage() + "\n");
            runButton.setDisable(false);
            stopButton.setDisable(true);
        });

        task.setOnCancelled(e -> {
            appendOutput("[STOPPED]\n");
            runButton.setDisable(false);
            stopButton.setDisable(true);
        });

        currentExecution = AppExecutor.runTask(task);
    }

    private void stopExecution() {
        if (currentExecution != null && !currentExecution.isDone()) {
            currentExecution.cancel(true);
            // Force disconnect and reconnect to kill the running command
            if (session != null && session.isConnected()) {
                try {
                    session.disconnect();
                } catch (Exception ignored) {}
                session = null;
            }
            appendOutput("[" + I18n.t("ssh.tab.stopped", "Stopped") + "]\n");
        }
        runButton.setDisable(false);
        stopButton.setDisable(true);
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
    }

    // ---- Output helpers ----

    private void appendOutput(String text) {
        Platform.runLater(() -> outputArea.appendText(text));
    }

    // ---- Cleanup ----

    /**
     * Called when the tab is closed. Disconnects the SSH session.
     */
    public void closeSession() {
        stopExecution();
        JschUtil.disconnectSession(session);
    }
}
