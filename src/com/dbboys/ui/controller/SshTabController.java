package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.JschUtil;
import com.dbboys.ssh.SshConnect;
import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for the SSH terminal tab (SshTab.fxml).
 * Uses a continuous shell channel (interactive terminal mode) �?
 * mimicking mainstream SSH clients. Enter key sends the current input line
 * to the shell, output is streamed back in real time.
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);

    @FXML
    public CustomInlineCssTextArea terminalArea;
    @FXML
    public Button connectButton;
    @FXML
    public Button disconnectButton;
    @FXML
    public Label connectionLabel;
    @FXML
    public VBox sshTab;

    private SshConnect sshConnect;
    private Session session;
    private ChannelShell shellChannel;
    private OutputStream shellOut;
    private final StringProperty connectStatus = new SimpleStringProperty();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readThread;
    private int inputStartPosition = 0; // position in terminalArea where current input line starts

    public void initialize() {
        // Icons
        connectButton.setGraphic(IconFactory.group(IconPaths.CONNECTION_LINK, 0.65, Color.GREEN));
        disconnectButton.setGraphic(IconFactory.group(IconPaths.CONNECTION_LINK, 0.65, Color.RED));

        // Tooltips
        connectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.connect", "Connect")));
        disconnectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.disconnect", "Disconnect")));

        disconnectButton.setDisable(true);

        // Connect / disconnect buttons
        connectButton.setOnAction(e -> doConnect());
        disconnectButton.setOnAction(e -> doDisconnect());

        // Terminal area: monospace, dark background, read-only appearance with interactive input
        terminalArea.setEditable(true);
        terminalArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");

        // Enter key sends the current input line (cursor always at end)
        terminalArea.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                sendCurrentLine();
                event.consume();
            } else if (event.getCode() == KeyCode.BACK_SPACE) {
                // Don't allow deleting before the input start position
                if (terminalArea.getCaretPosition() <= inputStartPosition) {
                    event.consume();
                }
            } else if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.UP
                    || event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.DOWN
                    || event.getCode() == KeyCode.PAGE_UP || event.getCode() == KeyCode.PAGE_DOWN) {
                // Block all arrow key navigation — keep cursor locked
                event.consume();
            } else if (event.getCode() == KeyCode.HOME) {
                terminalArea.moveTo(inputStartPosition);
                event.consume();
            } else if (event.getCode() == KeyCode.END) {
                terminalArea.moveTo(terminalArea.getLength());
                event.consume();
            }
        });

        // Disable mouse click cursor movement: always move cursor to end after any mouse click
        terminalArea.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            Platform.runLater(() -> terminalArea.moveTo(terminalArea.getLength()));
        });

        // Always keep cursor at the end (prevent arbitrary cursor movement)
        terminalArea.caretPositionProperty().addListener((obs, o, n) -> {
            if (n.intValue() < inputStartPosition) {
                Platform.runLater(() -> terminalArea.moveTo(terminalArea.getLength()));
            }
        });

        // Connection status binding
        connectStatus.addListener((obs, o, n) -> connectionLabel.setText(n));
        connectStatus.set(I18n.t("ssh.tab.disconnected", "Disconnected"));
    }

    /**
     * Initialize with an SSH connection configuration.
     * Automatically connects on open.
     */
    public void init(SshConnect sc) {
        this.sshConnect = sc;
        connectStatus.set(sc.getUsername() + "@" + sc.getHost() + ":" + sc.getPort());
        // Auto-connect
        doConnect();
    }

    // ---- Connection management ----

    private void doConnect() {
        if (sshConnect == null) return;
        connectButton.setDisable(true);
        connectStatus.set(I18n.t("ssh.tab.connecting", "Connecting..."));
        appendTerminal("Connecting to " + sshConnect.getUsername() + "@"
                + sshConnect.getHost() + ":" + sshConnect.getPort() + "...\n");

        AppExecutor.runAsync(() -> {
            try {
                session = JschUtil.getSshSession(sshConnect);
                shellChannel = (ChannelShell) session.openChannel("shell");
                shellChannel.setPty(true);

                // Connect shell and get streams
                shellChannel.connect();
                shellOut = shellChannel.getOutputStream();
                InputStream shellIn = shellChannel.getInputStream();

                running.set(true);
                inputStartPosition = terminalArea.getLength();

                // Start reading thread
                readThread = new Thread(() -> readShellOutput(shellIn), "ssh-shell-reader");
                readThread.setDaemon(true);
                readThread.start();

                Platform.runLater(() -> {
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connected", "Connected") + "]");
                    appendTerminal(I18n.t("ssh.tab.connected", "Connected") + "\n");
                });
            } catch (Exception ex) {
                log.error("SSH connect failed", ex);
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    disconnectButton.setDisable(true);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connect_failed", "Connect Failed") + "]");
                    appendTerminal("[ERROR] " + ex.getMessage() + "\n");
                });
            }
        });
    }

    private void doDisconnect() {
        running.set(false);
        if (readThread != null) {
            readThread.interrupt();
        }
        if (shellChannel != null && shellChannel.isConnected()) {
            shellChannel.disconnect();
        }
        JschUtil.disconnectSession(session);
        session = null;
        shellChannel = null;
        shellOut = null;
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
        if (sshConnect != null) {
            connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                    + ":" + sshConnect.getPort() + " ["
                    + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        }
        appendTerminal("\n--- " + I18n.t("ssh.tab.disconnected", "Disconnected") + " ---\n");
    }

    // ---- Shell communication ----

    /**
     * Continuously reads from the shell's input stream and appends to the terminal.
     */
    private void readShellOutput(InputStream shellIn) {
        byte[] buf = new byte[8192];
        try {
            int len;
            while (running.get() && (len = shellIn.read(buf)) != -1) {
                String output = new String(buf, 0, len, "UTF-8");
                Platform.runLater(() -> {
                    appendTerminal(output);
                    // Update the input start position to always be at the end
                    inputStartPosition = terminalArea.getLength();
                    // Move cursor to end
                    terminalArea.moveTo(inputStartPosition);
                });
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("Shell read error", e);
                Platform.runLater(() -> appendTerminal("\n[ERROR] " + e.getMessage() + "\n"));
            }
        }
    }

    /**
     * Sends the current input line (text after inputStartPosition) to the shell.
     */
    private void sendCurrentLine() {
        if (shellOut == null || !running.get()) {
            appendTerminal("\n[ERROR] " + I18n.t("ssh.tab.not_connected",
                    "Not connected.") + "\n");
            return;
        }

        String fullText = terminalArea.getText();
        if (fullText == null || fullText.isEmpty()) return;

        // Get the current line text (from inputStartPosition to end)
        String inputLine = "";
        if (inputStartPosition < fullText.length()) {
            inputLine = fullText.substring(inputStartPosition);
        }

        // Append newline to terminal
        appendTerminal("\n");

        try {
            // Send the input + newline to the shell
            shellOut.write((inputLine + "\n").getBytes("UTF-8"));
            shellOut.flush();
        } catch (IOException e) {
            log.error("Failed to send command", e);
            appendTerminal("[ERROR] " + e.getMessage() + "\n");
        }
    }

    // ---- Terminal helpers ----

    private void appendTerminal(String text) {
        if (Platform.isFxApplicationThread()) {
            terminalArea.appendText(text);
        } else {
            Platform.runLater(() -> terminalArea.appendText(text));
        }
    }

    // ---- Cleanup ----

    /**
     * Called when the tab is closed. Disconnects the SSH session.
     */
    public void closeSession() {
        doDisconnect();
    }
}
