package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.JschUtil;
import com.dbboys.ssh.SshConnect;
import com.dbboys.ssh.jediterm.JSchTtyConnector;
import com.dbboys.ssh.terminal.SimpleTerminalWidget;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SSH terminal tab controller using {@link SimpleTerminalWidget} for
 * Canvas-based terminal rendering (JediTerm-style: cursor, text,
 * selection are independent paint layers on a JavaFX Canvas).
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);

    @FXML public StackPane terminalPane;
    @FXML public Button connectButton;
    @FXML public Button disconnectButton;
    @FXML public Label connectionLabel;
    @FXML public VBox sshTab;

    private SshConnect sshConnect;
    private Session session;
    private ChannelShell shellChannel;
    private JSchTtyConnector connector;
    private final StringProperty connectStatus = new SimpleStringProperty();
    private SimpleTerminalWidget terminal;
    private ScrollBar scrollBar;
    private boolean updatingScrollBar;

    public void initialize() {
        connectButton.setGraphic(IconFactory.group(IconPaths.SSH_CONNECT, 0.65, Color.GREEN));
        disconnectButton.setGraphic(IconFactory.group(IconPaths.SSH_DISCONNECT, 0.65, Color.RED));
        connectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.connect", "Connect")));
        disconnectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.disconnect", "Disconnect")));
        disconnectButton.setDisable(true);
        connectButton.setOnAction(e -> doConnect());
        disconnectButton.setOnAction(e -> doDisconnect());

        connectStatus.addListener((obs, o, n) -> connectionLabel.setText(n));
        connectStatus.set(I18n.t("ssh.tab.disconnected", "Disconnected"));

        terminal = new SimpleTerminalWidget(80, 24);
        terminalPane.getChildren().add(terminal);

        scrollBar = new ScrollBar();
        scrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        scrollBar.setMin(0);
        scrollBar.setMax(0);
        scrollBar.setVisibleAmount(1);
        scrollBar.setUnitIncrement(1);
        scrollBar.setBlockIncrement(10);
        scrollBar.getStyleClass().add("ssh-scroll-bar");
        scrollBar.prefHeightProperty().bind(terminalPane.heightProperty());
        StackPane.setAlignment(scrollBar, javafx.geometry.Pos.CENTER_RIGHT);
        terminalPane.getChildren().add(scrollBar);

        scrollBar.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!updatingScrollBar) {
                int v = newVal.intValue();
                if (v != terminal.getScrollOffset()) {
                    terminal.setScrollOffset(v);
                }
            }
        });

        terminal.setOnScrollChanged(() -> {
            Platform.runLater(() -> {
                int max = terminal.getMaxScrollOffset();
                int val = terminal.getScrollOffset();
                updatingScrollBar = true;
                scrollBar.setMax(max);
                if (max > 0) {
                    scrollBar.setVisibleAmount(1);
                }
                scrollBar.setValue(val);
                updatingScrollBar = false;
            });
        });
        terminalPane.widthProperty().addListener((obs, o, n) -> {
            if (n.doubleValue() > 0) {
                terminal.setTermSize((int) (n.doubleValue() / SimpleTerminalWidget.CHAR_W), terminal.rows());
                updatePtySize();
            }
        });
        terminalPane.heightProperty().addListener((obs, o, n) -> {
            if (n.doubleValue() > 0) {
                terminal.setTermSize(terminal.cols(), (int) (n.doubleValue() / SimpleTerminalWidget.LINE_H));
                updatePtySize();
            }
        });
    }

    public void init(SshConnect sc) {
        this.sshConnect = sc;
        connectStatus.set(sc.getUsername() + "@" + sc.getHost() + ":" + sc.getPort());
        doConnect();
    }

    private void doConnect() {
        if (sshConnect == null) return;
        connectButton.setDisable(true);
        connectStatus.set(I18n.t("ssh.tab.connecting", "Connecting..."));
        terminal.status("Connecting to " + sshConnect.getUsername() + "@"
                + sshConnect.getHost() + ":" + sshConnect.getPort() + "...\r\n");

        AppExecutor.runAsync(() -> {
            try {
                session = JschUtil.getSshSession(sshConnect);
                shellChannel = (ChannelShell) session.openChannel("shell");
                shellChannel.setPty(true);
                shellChannel.setPtyType("xterm-256color");
                shellChannel.connect();

                connector = new JSchTtyConnector(shellChannel);
                terminal.setConnector(connector);
                terminal.start();

                Platform.runLater(() -> {
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    terminal.requestFocus();
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connected", "Connected") + "]");
                    terminal.status(I18n.t("ssh.tab.connected", "Connected") + "\r\n");
                    updatePtySize();
                });
            } catch (Exception ex) {
                log.error("SSH connect failed", ex);
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    disconnectButton.setDisable(true);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connect_failed", "Connect Failed") + "]");
                    terminal.status("[ERROR] " + ex.getMessage() + "\r\n");
                });
            }
        });
    }

    private void doDisconnect() {
        if (terminal != null) terminal.stop();
        if (connector != null) connector.close();
        if (shellChannel != null && shellChannel.isConnected()) shellChannel.disconnect();
        JschUtil.disconnectSession(session);
        session = null; shellChannel = null; connector = null;
        connectButton.setDisable(false); disconnectButton.setDisable(true);
        if (sshConnect != null) {
            connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                    + ":" + sshConnect.getPort() + " ["
                    + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        }
    }

    private void updatePtySize() {
        if (connector == null || !connector.isConnected()) return;
        connector.resize(terminal.cols(), terminal.rows(),
                (int) terminal.getWidth(), (int) terminal.getHeight());
    }

    public void closeSession() { doDisconnect(); }
}
