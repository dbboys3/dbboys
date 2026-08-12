package com.dbboys.ui.controller;
import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.ssh.SshUtil;
import com.dbboys.infra.zmodem.ZModemHandler;
import com.dbboys.infra.zmodem.ZModemSession;
import com.dbboys.model.SshConnect;
import com.dbboys.ui.component.terminal.TerminalBuffer;
import com.dbboys.ui.component.terminal.TerminalEmulator;
import com.dbboys.ui.component.terminal.TerminalRenderer;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
/**
 * SSH terminal tab controller: session lifecycle, ZModem transfer, session
 * logging and the SFTP dialog. The terminal emulation itself (screen buffer,
 * ANSI/xterm interpreter, canvas renderer) lives in
 * {@link com.dbboys.ui.component.terminal}; this class wires those components
 * to the SSH stream and the tab UI.
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);
    private static final int FONT_SIZE_STEP = 1;

    @FXML public StackPane terminalPane;
    @FXML public Button connectButton;
    @FXML public Button disconnectButton;
    @FXML public Label connectionLabel;
    @FXML public Label charsetLabel;
    @FXML public ChoiceBox<String> charsetChoiceBox;
    @FXML public Button sftpButton;
    @FXML public CheckBox logCheckBox;
    @FXML public VBox sshTab;
    private SshConnect sshConnect;
    private ClientSession session;
    private ChannelShell shellChannel;
    private volatile String charset = "UTF-8"; // terminal encoding, synced from SshConnect.charset
    /** Stateful decoder carried across reads: a multi-byte char (GB18030/UTF-8)
     *  split by a read boundary is completed by the next chunk instead of
     *  decaying into replacement chars. Reader-thread only; recreated when the
     *  charset changes or a new session starts. */
    private volatile java.nio.charset.CharsetDecoder streamDecoder;

    /** Get the current terminal charset, fallback to UTF-8 if invalid. */
    private java.nio.charset.Charset terminalCharset() {
        try {
            return java.nio.charset.Charset.forName(charset);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
    private final StringProperty connectStatus = new SimpleStringProperty();
    /** Callback invoked with true when connected, false when disconnected. */
    public java.util.function.Consumer<Boolean> onConnectionStateChanged;
    /** Callback invoked when new server output arrives (not disconnect/status messages). */
    public Runnable onActivity;
    private ScrollBar scrollBar;
    private boolean updatingScrollBar;
    // ---- Terminal components (screen model, escape interpreter, canvas view) ----
    private final TerminalBuffer termBuffer = new TerminalBuffer();
    private final TerminalRenderer renderer = new TerminalRenderer(termBuffer);
    private final TerminalEmulator emulator = new TerminalEmulator(termBuffer, new TerminalEmulator.Listener() {
        @Override public void onDrawRequest() { renderer.requestDraw(); }
        @Override public void onImmediateDraw() { renderer.draw(); }
        @Override public void onActivity() { if (onActivity != null) onActivity.run(); }
    });
    private boolean selecting;
    private Thread readThread;
    private volatile boolean connecting;
    private volatile boolean transferCancelFlag; // Ctrl+C/Ctrl+U aborts the active file transfer
    private volatile boolean transferCancelledByUser; // file-picker dialog was cancelled
    private volatile boolean zmodemActive; // true while a ZModem session owns the SSH stream
    private final StringBuilder inputBuffer = new StringBuilder(); // guards against concurrent connect attempts
    private Runnable onScrollChanged;
    private Timeline autoScrollTimeline;
    private int autoScrollDirection = 0;
    // Logging
    private java.io.BufferedWriter logWriter;
    private boolean logging;
    /** Next buffer row to write to the session log (rows before it are logged).
     *  Logging is screen-faithful: logical lines are taken from the terminal
     *  buffer once a hard newline finalizes them, so the file shows exactly
     *  what the screen shows — echo quirks, backspace edits and wide-char
     *  (CJK) handling are already resolved by the emulator. */
    private int sessionLogRow;
    /** The buffer reports structural row events here while logging is on. */
    private final TerminalBuffer.SessionLogListener sessionLogListener = new TerminalBuffer.SessionLogListener() {
        @Override public void finalizeThrough(int endRow) { sessionLogFinalize(endRow); }
        @Override public void rowInsertedAboveMark(int at) { if (at <= sessionLogRow) sessionLogRow++; }
        @Override public void rowRemovedAboveMark(int at) { if (at < sessionLogRow) sessionLogRow--; }
        @Override public void reanchorMark(int logicalLineStartRow) { sessionLogRow = Math.max(0, logicalLineStartRow); }
    };

    public SshTabController() {
        // Every deferred draw from the emulator is followed by a scrollbar refresh
        renderer.setAfterDraw(this::fireScrollChanged);
    }

    /** Request keyboard focus on the terminal canvas. */
    public void requestFocus() {
        renderer.requestFocus();
    }

    public void initialize() {
        // Buttons
        connectButton.setGraphic(IconFactory.group(IconPaths.SSH_CONNECT, 0.65, Color.rgb(0, 205, 0)));
        disconnectButton.setGraphic(IconFactory.group(IconPaths.SSH_DISCONNECT, 0.65, Color.RED));
        connectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.connect", "Connect")));
        disconnectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.disconnect", "Disconnect")));
        disconnectButton.setDisable(true);
        connectButton.setOnAction(e -> doConnect());
        disconnectButton.setOnAction(e -> doDisconnect());
        connectStatus.addListener((obs, o, n) -> connectionLabel.setText(n));
        connectStatus.set(I18n.t("ssh.tab.disconnected", "Disconnected"));
        // Charset ComboBox
        charsetChoiceBox.getItems().addAll("UTF-8", "GB18030");
        charsetChoiceBox.setValue("UTF-8");
        charsetLabel.textProperty().bind(I18n.bind("ssh.label.charset"));
        charsetChoiceBox.setOnAction(e -> {
            String newCharset = charsetChoiceBox.getValue();
            if (newCharset != null && !newCharset.equals(charset)) {
                charset = newCharset;
                // Sync to DB
                if (sshConnect != null) {
                    sshConnect.setCharset(charset);
                    com.dbboys.infra.db.LocalDbRepository.updateSsh(sshConnect);
                }
            }
        });
        // SFTP button
        sftpButton.setGraphic(IconFactory.group(IconPaths.SFTP, 0.5));
        sftpButton.setTooltip(new Tooltip(I18n.t("ssh.tab.sftp", "SFTP File Transfer")));
        sftpButton.setDisable(true);
        sftpButton.setOnAction(e -> openSftpDialog());

        // Log checkbox
        logCheckBox.textProperty().bind(I18n.bind("ssh.label.log"));
        logCheckBox.setOnAction(e -> {
            if (logCheckBox.isSelected()) {
                startLogging();
            } else {
                stopLogging();
            }
        });
        // Canvas terminal (created and painted by the renderer)
        Canvas canvas = renderer.getCanvas();
        setupCanvasInput();
        // The canvas is sized explicitly from terminalPane's current size, and Canvas
        // is not resizable, so its size would otherwise become terminalPane's computed
        // min size. That locks the layout after maximize: on restore the pane can never
        // shrink below the canvas width, the width listener never fires and right-edge
        // content (scrollbar, toolbar controls) stays clipped. Overriding the min size
        // breaks this feedback loop so the pane (and canvas) can shrink again.
        terminalPane.setMinSize(0, 0);
        terminalPane.getChildren().add(canvas);
        // Click on the tab body focuses the terminal
        sshTab.setOnMouseClicked(e -> canvas.requestFocus());
        // ScrollBar
        scrollBar = new ScrollBar();
        scrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        scrollBar.setMin(0);
        scrollBar.setMax(0);
        scrollBar.setVisibleAmount(1);
        scrollBar.setUnitIncrement(1);
        scrollBar.setBlockIncrement(10);
        scrollBar.getStyleClass().add("ssh-scroll-bar");
        scrollBar.setVisible(false);
        scrollBar.prefHeightProperty().bind(terminalPane.heightProperty());
        StackPane.setAlignment(scrollBar, javafx.geometry.Pos.CENTER_RIGHT);
        terminalPane.getChildren().add(scrollBar);
        scrollBar.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!updatingScrollBar) {
                int v = newVal.intValue();
                int maxScroll = Math.max(0, termBuffer.size() - termBuffer.getRows());
                if (v != termBuffer.getScrollOff()) {
                    termBuffer.setScrollOff(clamp(v, 0, maxScroll));
                    termBuffer.setScrollLock(termBuffer.getScrollOff() < maxScroll); // dragging back to the bottom re-engages follow mode
                    renderer.draw();
                }
            }
        });
        onScrollChanged = () -> {
            Platform.runLater(() -> {
                int max = termBuffer.isInAltScreen() ? 0 : Math.max(0, termBuffer.size() - termBuffer.getRows());
                scrollBar.setVisible(max > 0);
                updatingScrollBar = true;
                // Fixed visible amount keeps thumb at a minimum readable size
                                int visAmount = max > 0 ? Math.min(max, Math.max(termBuffer.getRows(), max / 8)) : 1;
                                scrollBar.setMax(max);
                scrollBar.setVisibleAmount(visAmount);
                scrollBar.setValue(termBuffer.getScrollOff());
                updatingScrollBar = false;
            });
        };
        // Resize listeners
        terminalPane.widthProperty().addListener((obs, o, n) -> {
            if (n.doubleValue() > 0) {
                int newCols = Math.max(1, (int) (n.doubleValue() / renderer.getCharW()));
                if (newCols != termBuffer.getCols()) {
                    if (termBuffer.isInAltScreen()) {
                        // Alt screen cannot be reflowed: clear it so the full-screen
                        // app repaints a fresh frame after SIGWINCH (anti-residue)
                        termBuffer.clearBuffer();
                    } else {
                        termBuffer.reflowBuffer(newCols);
                    }
                    termBuffer.setCols(newCols);
                    canvas.setWidth(termBuffer.getCols() * renderer.getCharW());
                    renderer.draw();
                    fireScrollChanged();
                }
                updatePtySize();
            }
        });
        terminalPane.heightProperty().addListener((obs, o, n) -> {
            if (n.doubleValue() > 0) {
                int newRows = Math.max(1, (int) (n.doubleValue() / renderer.getLineH()));
                if (newRows != termBuffer.getRows()) {
                    termBuffer.setRows(newRows);
                    canvas.setHeight(termBuffer.getRows() * renderer.getLineH());
                    if (termBuffer.isInAltScreen()) {
                        // Alt screen cannot be reflowed: clear it so the full-screen
                        // app repaints a fresh frame after SIGWINCH (anti-residue)
                        termBuffer.clearBuffer();
                        // Alt screen is a fixed page: keep the scroll region in sync with the visible size
                        termBuffer.setScrollTop(0);
                        termBuffer.setScrollBottom(termBuffer.getRows() - 1);
                    }
                    // Keep scrollOff valid after resize
                    if (termBuffer.getScrollOff() > Math.max(0, termBuffer.size() - termBuffer.getRows())) {
                        termBuffer.setScrollOff(Math.max(0, termBuffer.size() - termBuffer.getRows()));
                    }
                    if (!termBuffer.isInAltScreen() && !termBuffer.isScrollLock()) {
                        // Following output: keep the viewport pinned to the bottom across resizes
                        termBuffer.setScrollOff(Math.max(0, termBuffer.size() - termBuffer.getRows()));
                    }
                    renderer.draw();
                    fireScrollChanged();
                }
                updatePtySize();
            }
        });
    }
    public void init(SshConnect sc) {
        this.sshConnect = sc;
        // Load charset from DB record, default UTF-8
        charset = (sc.getCharset() != null && !sc.getCharset().isBlank())
                ? sc.getCharset() : "UTF-8";
        if (!charset.equals("UTF-8") && !charset.equals("GB18030")) {
            charset = "UTF-8";
        }
        charsetChoiceBox.setValue(charset);
        connectStatus.set(sc.getUsername() + "@" + sc.getHost() + ":" + sc.getPort());
        doConnect();
    }
    private void doConnect() {
        if (sshConnect == null) return;
        if (connecting) return; // already connecting ???skip duplicate
        connecting = true;
        connectButton.setDisable(true);
        connectStatus.set(I18n.t("ssh.tab.connecting", "Connecting..."));
        emulator.status("Connecting to " + sshConnect.getUsername() + "@"
                + sshConnect.getHost() + ":" + sshConnect.getPort() + "...\r\n");
        AppExecutor.runAsync(() -> {
            try {
                session = SshUtil.getSshSession(sshConnect);
                shellChannel = session.createShellChannel();
                shellChannel.setUsePty(true);
                shellChannel.setPtyType("xterm-256color");
                shellChannel.open().verify(5000);
                start();
                Platform.runLater(() -> {
                    connecting = false;
                    termBuffer.setCursorShown(true); // restore cursor on successful connect
                    renderer.playBlink(); // ensure blink is running after reconnect
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    sftpButton.setDisable(false);
                    renderer.requestFocus();
                    if (onConnectionStateChanged != null) onConnectionStateChanged.accept(true);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connected", "Connected") + "]");
                    emulator.statusGreen(I18n.t("ssh.tab.connected", "Connected") + "\r\n");
                    updatePtySize();
                });
            } catch (Exception ex) {
                log.error("SSH connect failed", ex);
                Platform.runLater(() -> {
                    connecting = false;
                    termBuffer.setCursorShown(false);
                    renderer.draw();
                    connectButton.setDisable(false);
                    disconnectButton.setDisable(true);
                    sftpButton.setDisable(true);
                    if (onConnectionStateChanged != null) onConnectionStateChanged.accept(false);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connect_failed", "Connect Failed") + "]");
                    emulator.statusRed("[ERROR] " + ex.getMessage() + "\r\n");
                });
            }
        });
    }
    private void doDisconnect() {
        stop();
        SshUtil.disconnectSession(session);
        session = null;
        shellChannel = null;
        // Leave the alternate screen if a full-screen app (nmon, vi) was running:
        // the user should land back on the shell buffer with its scrollback and scrollbar
        termBuffer.exitAltScreen();
        termBuffer.setCursorShown(false);
        renderer.draw();
        fireScrollChanged();
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
        sftpButton.setDisable(true);
        if (onConnectionStateChanged != null) onConnectionStateChanged.accept(false);
        if (sshConnect != null) {
            connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                    + ":" + sshConnect.getPort() + " ["
                    + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        }
    }
    private void updatePtySize() {
        if (shellChannel != null && shellChannel.isOpen()) {
            try {
                shellChannel.sendWindowChange(termBuffer.getCols(), termBuffer.getRows(),
                        (int) renderer.getCanvas().getWidth(), (int) renderer.getCanvas().getHeight());
            } catch (Exception ignored) {}
        }
    }
    /** Zoom the terminal font by delta steps: recompute metrics, re-tile the grid,
     *  reflow content to the new column count, and persist the size to config. */
    private void adjustFontSize(int delta) {
        if (!renderer.applyFontSize(delta)) return;
        double charW = renderer.getCharW(), lineH = renderer.getLineH();
        int newCols = Math.max(1, (int) (terminalPane.getWidth() / charW));
        int newRows = Math.max(1, (int) (terminalPane.getHeight() / lineH));
        boolean gridChanged = newCols != termBuffer.getCols() || newRows != termBuffer.getRows();
        if (termBuffer.isInAltScreen() && gridChanged) {
            // The alt screen cannot be reflowed: clear it and let the full-screen
            // app repaint after SIGWINCH, otherwise stale cells (e.g. nmon's
            // frame) linger as residue around the new grid.
            termBuffer.clearBuffer();
        } else if (newCols != termBuffer.getCols()) {
            termBuffer.reflowBuffer(newCols);
        }
        termBuffer.setCols(newCols);
        termBuffer.setRows(newRows);
        renderer.getCanvas().setWidth(termBuffer.getCols() * charW);
        renderer.getCanvas().setHeight(termBuffer.getRows() * lineH);
        if (termBuffer.isInAltScreen()) {
            // Alt screen is a fixed page: keep the scroll region in sync with the visible size
            termBuffer.setScrollTop(0);
            termBuffer.setScrollBottom(termBuffer.getRows() - 1);
        }
        // Keep scrollOff valid after the grid change
        if (termBuffer.getScrollOff() > Math.max(0, termBuffer.size() - termBuffer.getRows())) {
            termBuffer.setScrollOff(Math.max(0, termBuffer.size() - termBuffer.getRows()));
        }
        if (!termBuffer.isInAltScreen() && !termBuffer.isScrollLock()) {
            // Following output: keep the viewport pinned to the bottom
            termBuffer.setScrollOff(Math.max(0, termBuffer.size() - termBuffer.getRows()));
        }
        renderer.draw();
        fireScrollChanged();
        updatePtySize();
        renderer.persistFontSize();
    }
    public void closeSession() { doDisconnect(); }
    // ==================== Terminal engine ====================
    private void start() {
        if (shellChannel == null || !shellChannel.isOpen()) return;
        streamDecoder = null; // drop any partial char left over from a previous session
        readThread = new Thread(() -> {
            try {
                InputStream in = shellChannel.getInvertedOut();
                byte[] buf = new byte[8192];
                byte[] carry = new byte[0]; // unscanned tail: the beacon signature may span two reads
                int len;
                while (shellChannel.isOpen() && (len = in.read(buf, 0, buf.length)) != -1) {
                    // Scan for a ZModem ZRQINIT/ZRINIT hex header; on a hit the raw stream is
                    // handed to the ZModem engine until the file transfer session ends.
                    byte[] data = buf;
                    int dlen = len;
                    if (carry.length > 0) {
                        data = new byte[carry.length + len];
                        System.arraycopy(carry, 0, data, 0, carry.length);
                        System.arraycopy(buf, 0, data, carry.length, len);
                        dlen = data.length;
                        carry = new byte[0];
                    }
                    int sig = indexOfZmodemBeacon(data, dlen);
                    if (sig < 0) {
                        // hold back only a trailing fragment that actually starts the
                        // signature (e.g. "**\x18B0"); everything else renders at once,
                        // otherwise an idle prompt would lose its last bytes
                        int keep = zmodemBeaconTail(data, dlen);
                        feedTerminal(data, 0, dlen - keep);
                        carry = new byte[keep];
                        System.arraycopy(data, dlen - keep, carry, 0, keep);
                        continue;
                    }
                    feedTerminal(data, 0, sig);
                    byte[] prefix = new byte[dlen - sig];
                    System.arraycopy(data, sig, prefix, 0, prefix.length);
                    carry = new byte[0];
                    // the beacon's type digit decides the role: '0'=ZRQINIT from sz
                    // (we download), '1'=ZRINIT from rz (we upload)
                    int dir = data[sig + ZMODEM_BEACON_SIG.length] == '0' ? 2 : 1;
                    runZmodemSession(in, prefix, dir);
                }
                // read returned -1 (clean EOF: server closed the channel) or channel died
                log.info("SSH read loop ended: clean EOF, channelOpen={}",
                        shellChannel != null && shellChannel.isOpen());
                Platform.runLater(this::onConnectionLost);
            } catch (Exception e) {
                // read thread interrupted or IO error ???connection likely lost
                log.info("SSH read loop ended with error: {}", e.toString());
                Platform.runLater(this::onConnectionLost);
            }
        }, "term-reader");
        readThread.setDaemon(true);
        readThread.start();
    }
    private void stop() {
        renderer.stopBlink();
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (shellChannel != null) {
            try { shellChannel.getInvertedIn().close(); } catch (Exception ignored) {}
            try { shellChannel.getInvertedOut().close(); } catch (Exception ignored) {}
            if (shellChannel.isOpen()) {
                shellChannel.close(false);
            }
        }
    }
    /** Called on the FX thread when the SSH read thread exits (connection lost). */
    private void onConnectionLost() {
        // Clean up stale session/channel regardless of isConnected() state
        stop();
        closeLogWriter();
        SshUtil.disconnectSession(session);
        session = null;
        shellChannel = null;
        // Leave the alternate screen first so the disconnect notice lands in the
        // restored main buffer (with scrollback/scrollbar), not the dead alt frame
        termBuffer.exitAltScreen();
        emulator.statusRed("\r\nDisconnected\r\n");
        termBuffer.setCursorShown(false);
        renderer.draw();
        if (onConnectionStateChanged != null) onConnectionStateChanged.accept(false);
        connectStatus.set((sshConnect != null
                ? sshConnect.getUsername() + "@" + sshConnect.getHost() + ":" + sshConnect.getPort()
                : "") + " [" + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
        sftpButton.setDisable(true);
    }

    private void fireScrollChanged() { if (onScrollChanged != null) onScrollChanged.run(); }

    /** Drop the current selection (e.g. because input or a screen switch made it stale). */
    private void clearSelection() {
        if (termBuffer.getSelStartRow() < 0 && termBuffer.getSelEndRow() < 0) return;
        termBuffer.clearSelection();
        renderer.draw();
    }

    private void jumpToBottom() {
        if (termBuffer.jumpToBottom()) {
            renderer.draw();
            fireScrollChanged();
        }
    }

    // ---- Logging ----
    private void startLogging() {
        try {
            String desktop = System.getProperty("user.home") + File.separator + "Desktop";
            String host = sshConnect != null ? sshConnect.getHost() : "unknown";
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String logFile = desktop + File.separator + host + "_" + ts + ".log";
            logWriter = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(logFile), terminalCharset()));
            logging = true;
            // Log from the current line on; screen history above stays out of the file
            sessionLogRow = Math.max(0, termBuffer.logicalLineStart(termBuffer.getCurRow()));
            termBuffer.setSessionLogListener(sessionLogListener);
            try { logWriter.flush(); } catch (Exception ignored) {}
            com.dbboys.ui.notification.NotificationUtil.showMainNotification(
                    I18n.t("ssh.notice.log_started", "Logging started") + ": " + new File(logFile).getName());
        } catch (Exception ex) {
            log.error("Failed to start terminal log", ex);
            logCheckBox.setSelected(false);
        }
    }

    private void stopLogging() {
        closeLogWriter();
        com.dbboys.ui.notification.NotificationUtil.showMainNotification(
                I18n.t("ssh.notice.log_stopped", "Logging stopped"));
    }

    private void closeLogWriter() {
        if (logWriter != null) {
            sessionLogDumpRest();
            try { logWriter.close(); } catch (Exception ignored) {}
            logWriter = null;
        }
        logging = false;
        termBuffer.setSessionLogListener(null);
    }

    /** Write every complete logical line in [sessionLogRow, endRow] to the session
     *  log. Text comes from the screen buffer, so it matches the display exactly.
     *  Skipped on the alternate screen, where full-screen apps (vi, top, less)
     *  constantly redraw in place and would flood the log with frames. */
    private void sessionLogFinalize(int endRow) {
        if (!logging || logWriter == null || termBuffer.isInAltScreen()) return;
        if (endRow < sessionLogRow) return; // already logged (cursor moved up and re-newlined)
        try {
            int r = sessionLogRow;
            int last = Math.min(endRow, termBuffer.size() - 1);
            while (r <= last) {
                int ge = r;
                while (ge < last && termBuffer.isWrapped(ge)) ge++; // join soft-wrapped rows into one logical line
                StringBuilder sb = new StringBuilder();
                for (int k = r; k <= ge; k++) sb.append(termBuffer.line(k));
                int len = sb.length();
                while (len > 0 && sb.charAt(len - 1) == ' ') len--; // trailing blanks are not content
                logWriter.write(sb.substring(0, len));
                logWriter.write("\r\n");
                r = ge + 1;
            }
            sessionLogRow = r;
            logWriter.flush();
        } catch (Exception ignored) {}
    }

    /** Log the remaining, possibly incomplete current logical line when logging stops. */
    private void sessionLogDumpRest() {
        if (!logging || logWriter == null || termBuffer.isInAltScreen()) return;
        int end = Math.min(termBuffer.getCurRow(), termBuffer.size() - 1);
        while (end < termBuffer.size() - 1 && termBuffer.isWrapped(end)) end++; // include the whole current logical line
        sessionLogFinalize(end);
    }

    /** Open the SFTP file transfer dialog. */
    private void openSftpDialog() {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            org.apache.sshd.sftp.client.SftpClient sftpClient = SshUtil.createSftpClient(session);
            com.dbboys.ui.controller.SftpDialogController.showDialog(
                    renderer.getCanvas().getScene().getWindow(), sftpClient, sshConnect);
        } catch (Exception ex) {
            log.error("Failed to open SFTP dialog", ex);
            com.dbboys.ui.notification.NotificationUtil.showMainNotification(
                    "SFTP: " + ex.getMessage());
        }
    }

    // ---- Input ----
    private void setupCanvasInput() {
        Canvas canvas = renderer.getCanvas();
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus();
            if (e.getButton() == MouseButton.PRIMARY) {
                selecting = true;
                int col = (int) (e.getX() / renderer.getCharW());
                int row = clamp(termBuffer.getScrollOff() + (int) (e.getY() / renderer.getLineH()), 0, Math.max(0, termBuffer.size() - 1));
                termBuffer.setSelection(col, row, col, row);
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (!selecting) return;
            double ey = e.getY();
            double ch = canvas.getHeight();
            int col = clamp((int) (e.getX() / renderer.getCharW()), 0, termBuffer.getCols() - 1);
            int maxOff = Math.max(0, termBuffer.size() - termBuffer.getRows());
            int row;
            if (ey < 0) {
                // Dragged above canvas: jump toward older lines, then auto-scroll
                int lines = (int) (-ey / renderer.getLineH()) + 1;
                termBuffer.setScrollOff(clamp(termBuffer.getScrollOff() - lines, 0, maxOff));
                row = termBuffer.getScrollOff();
                startAutoScroll(-1);
            } else if (ey > ch) {
                // Dragged below canvas: jump toward newer lines, then auto-scroll
                int lines = (int) ((ey - ch) / renderer.getLineH()) + 1;
                termBuffer.setScrollOff(clamp(termBuffer.getScrollOff() + lines, 0, maxOff));
                row = termBuffer.getScrollOff() + termBuffer.getRows() - 1;
                startAutoScroll(1);
            } else {
                stopAutoScroll();
                row = termBuffer.getScrollOff() + (int) (ey / renderer.getLineH());
            }
            termBuffer.setSelectionEnd(col, clamp(row, 0, Math.max(0, termBuffer.size() - 1)));
            renderer.draw();
            fireScrollChanged();
        });
        canvas.setOnMouseReleased(e -> {
            canvas.requestFocus();
            selecting = false;
            stopAutoScroll();
            if (e.getButton() == MouseButton.PRIMARY) {
                termBuffer.setSelectionEnd(
                        clamp((int) (e.getX() / renderer.getCharW()), 0, termBuffer.getCols() - 1),
                        clamp(termBuffer.getScrollOff() + (int) (e.getY() / renderer.getLineH()), 0, Math.max(0, termBuffer.size() - 1)));
                // Copy selection to clipboard on mouse release (drag-select)
                String sel = termBuffer.selectedText();
                if (!sel.isEmpty()) {
                    Clipboard.getSystemClipboard().setContent(
                            java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, sel));
                } else {
                    termBuffer.clearSelection();
                }
            }
            renderer.draw();
        });
        canvas.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                int row = clamp(termBuffer.getScrollOff() + (int) (e.getY() / renderer.getLineH()), 0, Math.max(0, termBuffer.size() - 1));
                int col = clamp((int) (e.getX() / renderer.getCharW()), 0, termBuffer.getCols() - 1);
                String ln = termBuffer.line(row);
                int start = col, end = col;
                int ll = ln.length();
                while (start > 0 && start <= ll && isWordChar(ln.charAt(start - 1))) start--;
                while (end < ll && isWordChar(ln.charAt(end))) end++;
                termBuffer.setSelection(start, row, end, row);
                // Copy double-clicked word to clipboard (silent)
                String word = termBuffer.selectedText();
                if (!word.isEmpty()) {
                    Clipboard.getSystemClipboard().setContent(
                            java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, word));
                }
                renderer.draw();
            }
        });
        canvas.setOnContextMenuRequested(e -> {
            // Right-click: paste from clipboard (\n -> \r for terminal)
            if (shellChannel == null || !shellChannel.isOpen()) { e.consume(); return; }
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasString()) {
                String text = cb.getString();
                if (text != null && !text.isEmpty()) {
                    // Normalize line endings to a single \r (terminals expect \r for Enter);
                    // Windows clipboard text uses \r\n, which would otherwise become \r\r
                    text = text.replace("\r\n", "\r").replace("\n", "\r");
                    clearSelection(); // pasting invalidates the stale selection
                    try {
                        OutputStream os = shellChannel.getInvertedIn();
                        os.write(text.getBytes(terminalCharset()));
                        os.flush();
                    } catch (Exception ignored) {}
                }
            }
            e.consume();
        });
        canvas.setOnKeyPressed(e -> {
            // Font zoom: Ctrl + '+' / Ctrl + '-' (works whether connected or not)
            if (e.isControlDown() && !e.isAltDown()) {
                if (TerminalRenderer.isZoomInKey(e)) { adjustFontSize(FONT_SIZE_STEP); e.consume(); return; }
                if (TerminalRenderer.isZoomOutKey(e)) { adjustFontSize(-FONT_SIZE_STEP); e.consume(); return; }
            }
            // Disconnected ???Enter triggers reconnect
            if (shellChannel == null || !shellChannel.isOpen()) {
                if (e.getCode() == KeyCode.ENTER) {
                    doConnect();
                }
                e.consume();
                return;
            }
            // During a ZModem session the stream belongs to the transfer engine;
            // swallow all keys except Ctrl+C (cancel transfer)
            if (zmodemActive) {
                if (e.isControlDown() && e.getCode() == KeyCode.C) transferCancelFlag = true;
                e.consume();
                return;
            }
            // Auto-jump to bottom before sending input
            jumpToBottom();
            byte[] b = key(e);
            if (b != null) {
                clearSelection(); // any terminal input invalidates the stale selection
                try {
                    OutputStream os = shellChannel.getInvertedIn();
                    os.write(b);
                    os.flush();
                } catch (Exception ignored) {}
                e.consume();
            }
        });
        canvas.setOnKeyTyped(e -> {
            if (shellChannel == null || !shellChannel.isOpen()) return;
            if (zmodemActive) { e.consume(); return; } // transfer in progress: no terminal input
            if (e.isControlDown() || e.isAltDown()) return; // zoom/modified keys are not text input
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty()) return;
            char c = ch.charAt(0);
            jumpToBottom();
            clearSelection(); // typed input invalidates the stale selection
            try {
                OutputStream os = shellChannel.getInvertedIn();
                if (c == '\r' || c == '\n') {
                    os.write(c);
                    os.flush();
                    inputBuffer.setLength(0);
                } else if (c >= 0x20 && c != 0x7F) {
                    inputBuffer.append(c);
                    os.write(ch.getBytes(terminalCharset()));
                    os.flush();
                }
            } catch (Exception ignored) {}
            e.consume();
        });
        canvas.setOnScroll(e -> {
            if (e.isControlDown()) { // Ctrl + wheel: zoom font
                double dy = e.getDeltaY();
                if (dy != 0) adjustFontSize(dy > 0 ? FONT_SIZE_STEP : -FONT_SIZE_STEP);
                e.consume();
                return;
            }
            if (termBuffer.isInAltScreen()) { e.consume(); return; } // alt screen has no scrollback to scroll
            int dir = -(int) Math.signum(e.getDeltaY());
            if (dir != 0) {
                int maxOff = Math.max(0, termBuffer.size() - termBuffer.getRows());
                termBuffer.setScrollOff(clamp(termBuffer.getScrollOff() + dir, 0, maxOff));
                termBuffer.setScrollLock(termBuffer.getScrollOff() < maxOff); // scrolling back to the bottom releases the lock
                renderer.draw();
                fireScrollChanged();
            }
            e.consume();
        });
    }

    private void startAutoScroll(int dir) {
        if (autoScrollDirection == dir) return;
        autoScrollDirection = dir;
        if (autoScrollTimeline == null) {
            autoScrollTimeline = new Timeline(new KeyFrame(Duration.millis(50), ev -> {
                if (autoScrollDirection == 0) return;
                int maxOff = Math.max(0, termBuffer.size() - termBuffer.getRows());
                termBuffer.setScrollOff(clamp(termBuffer.getScrollOff() + autoScrollDirection, 0, maxOff));
                // Keep selection endpoint pinned to the edge in scroll direction
                if (autoScrollDirection < 0) {
                    termBuffer.setSelectionEnd(termBuffer.getSelEndCol(), termBuffer.getScrollOff());
                } else {
                    termBuffer.setSelectionEnd(termBuffer.getSelEndCol(),
                            clamp(termBuffer.getScrollOff() + termBuffer.getRows() - 1, 0, Math.max(0, termBuffer.size() - 1)));
                }
                renderer.draw();
                fireScrollChanged();
            }));
            autoScrollTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        autoScrollTimeline.play();
    }
    private void stopAutoScroll() {
        autoScrollDirection = 0;
        if (autoScrollTimeline != null) autoScrollTimeline.stop();
    }
    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    private boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'
                || TerminalBuffer.isFullwidth(c);
    }
    private byte[] key(KeyEvent e) {
        KeyCode k = e.getCode();
        boolean ct = e.isControlDown() && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown();
        if (ct) {
            if (k == KeyCode.C) {
                String s = termBuffer.selectedText();
                if (!s.isEmpty()) {
                    Clipboard.getSystemClipboard().setContent(java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, s));
                    return null;
                }
            }
            if (k == KeyCode.V) {
                Clipboard cb = Clipboard.getSystemClipboard();
                if (cb.hasString()) {
                    String t = cb.getString();
                    if (t != null && !t.isEmpty()) {
                        // Normalize line endings so Windows CRLF doesn't paste as two Enters per line
                        t = t.replace("\r\n", "\r").replace("\n", "\r");
                        return t.getBytes(StandardCharsets.UTF_8);
                    }
                }
                return null;
            }
            switch (k) {
                case A:return new byte[]{0x01}; case B:return new byte[]{0x02}; case C:inputBuffer.setLength(0);transferCancelFlag=true;return new byte[]{0x03};
                case D:return new byte[]{0x04}; case E:return new byte[]{0x05};
                case F:return new byte[]{0x06}; case G:return new byte[]{0x07};
                case H:return new byte[]{0x08}; case J:return new byte[]{0x0A};
                case K:return new byte[]{0x0B}; case L:return new byte[]{0x0C};
                case N:return new byte[]{0x0E}; case O:return new byte[]{0x0F};
                case P:return new byte[]{0x10}; case Q:return new byte[]{0x11};
                case R:return new byte[]{0x12}; case S:return new byte[]{0x13};
                case T:return new byte[]{0x14}; case U:inputBuffer.setLength(0);transferCancelFlag=true;return new byte[]{0x15};
                case W:return new byte[]{0x17}; case X:return new byte[]{0x18};
                case Y:return new byte[]{0x19}; case Z:return new byte[]{0x1A};
                default:return null;
            }
        }
        switch (k) {
            case ENTER:return null;
            case BACK_SPACE:if (inputBuffer.length()>0) inputBuffer.setLength(inputBuffer.length()-1);return new byte[]{0x7F};
            case TAB:return "\t".getBytes(StandardCharsets.UTF_8);
            case ESCAPE:return "".getBytes(StandardCharsets.UTF_8);
            case UP:return emulator.isCursorKeysApp() ? "OA".getBytes(StandardCharsets.UTF_8) : "[A".getBytes(StandardCharsets.UTF_8);
            case DOWN:return emulator.isCursorKeysApp() ? "OB".getBytes(StandardCharsets.UTF_8) : "[B".getBytes(StandardCharsets.UTF_8);
            case RIGHT:return emulator.isCursorKeysApp() ? "OC".getBytes(StandardCharsets.UTF_8) : "[C".getBytes(StandardCharsets.UTF_8);
            case LEFT:return emulator.isCursorKeysApp() ? "OD".getBytes(StandardCharsets.UTF_8) : "[D".getBytes(StandardCharsets.UTF_8);
            case HOME:return "[H".getBytes(StandardCharsets.UTF_8);
            case END:return "[F".getBytes(StandardCharsets.UTF_8);
            case DELETE:return "[3~".getBytes(StandardCharsets.UTF_8);
            case PAGE_UP:return "[5~".getBytes(StandardCharsets.UTF_8);
            case PAGE_DOWN:return "[6~".getBytes(StandardCharsets.UTF_8);
            case F1:return "OP".getBytes(StandardCharsets.UTF_8);
            case F2:return "OQ".getBytes(StandardCharsets.UTF_8);
            case F3:return "OR".getBytes(StandardCharsets.UTF_8);
            case F4:return "OS".getBytes(StandardCharsets.UTF_8);
            case F5:return "[15~".getBytes(StandardCharsets.UTF_8);
            case F6:return "[17~".getBytes(StandardCharsets.UTF_8);
            case F7:return "[18~".getBytes(StandardCharsets.UTF_8);
            case F8:return "[19~".getBytes(StandardCharsets.UTF_8);
            case F9:return "[20~".getBytes(StandardCharsets.UTF_8);
            case F10:return "[21~".getBytes(StandardCharsets.UTF_8);
            case F11:return "[23~".getBytes(StandardCharsets.UTF_8);
            case F12:return "[24~".getBytes(StandardCharsets.UTF_8);
            default:return null;
        }
    }

    // ==================== ZModem (sz/rz) file transfer ====================
    // Beacon hex header: '*','*',0x18,'B','0', then the type digit 鈥?
    // '0' = ZRQINIT sent by sz (download), '1' = ZRINIT sent by rz (upload).
    private static final byte[] ZMODEM_BEACON_SIG = {0x2A, 0x2A, 0x18, 0x42, 0x30};

    private static int indexOfZmodemBeacon(byte[] d, int len) {
        outer:
        for (int i = 0; i + ZMODEM_BEACON_SIG.length + 1 <= len; i++) {
            if (d[i] != ZMODEM_BEACON_SIG[0]) continue;
            for (int j = 1; j < ZMODEM_BEACON_SIG.length; j++) {
                if (d[i + j] != ZMODEM_BEACON_SIG[j]) continue outer;
            }
            int typeDigit = d[i + ZMODEM_BEACON_SIG.length];
            if (typeDigit == '0' || typeDigit == '1') return i;
        }
        return -1;
    }

    /** Length of the trailing bytes that form a proper prefix of the beacon signature (0..5). */
    private static int zmodemBeaconTail(byte[] d, int len) {
        int max = Math.min(ZMODEM_BEACON_SIG.length, len);
        for (int k = max; k > 0; k--) {
            boolean match = true;
            for (int j = 0; j < k; j++) {
                if (d[len - k + j] != ZMODEM_BEACON_SIG[j]) { match = false; break; }
            }
            if (match) return k;
        }
        return 0;
    }

    /** Log + decode + render terminal output (called on the reader thread). */
    private void feedTerminal(byte[] data, int off, int len) {
        if (len <= 0) return;
        // Session logging happens on the FX thread, from the rendered screen
        // buffer (see sessionLogFinalize), not from this raw byte stream.
        String out = decodeStream(data, off, len);
        if (out.isEmpty()) return; // chunk ended inside a split multi-byte char
        Platform.runLater(() -> emulator.write(out));
    }

    /** Incrementally decode the byte stream. Bytes of an incomplete trailing
     *  multi-byte sequence stay buffered inside the decoder and are completed
     *  by the next chunk — this is what keeps GB18030/UTF-8 CJK text intact
     *  across read boundaries. */
    private String decodeStream(byte[] data, int off, int len) {
        java.nio.charset.Charset cs = terminalCharset();
        java.nio.charset.CharsetDecoder dec = streamDecoder;
        if (dec == null || !dec.charset().equals(cs)) {
            dec = cs.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            streamDecoder = dec;
        }
        StringBuilder sb = new StringBuilder(len + 8);
        char[] tmp = new char[4096];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data, off, len);
        java.nio.CharBuffer cb = java.nio.CharBuffer.wrap(tmp);
        for (;;) {
            java.nio.charset.CoderResult cr = dec.decode(bb, cb, false);
            sb.append(tmp, 0, cb.position());
            cb.clear();
            if (cr.isUnderflow()) break; // done; a trailing partial char stays in the decoder
            if (!cr.isOverflow() && !cr.isMalformed() && !cr.isUnmappable()) break; // defensive
        }
        return sb.toString();
    }

    /** Run a full ZModem session on the reader thread; binary bytes bypass the terminal renderer.
     * @param dir 1=upload (remote rz), 2=download (remote sz) */
    private void runZmodemSession(InputStream in, byte[] prefix, int dir) {
        final String dirLabel = (dir == 2) ? "download" : "upload";
        zmodemActive = true;
        transferCancelFlag = false;
        transferCancelledByUser = false;
        progressStartMs = 0;
        lastProgressMs = 0;
        ZModemSession zs = new ZModemSession(in, shellChannel.getInvertedIn(), prefix, zmodemHandler);
        boolean abnormal = false;
        try {
            if (dir == 2) zs.receive(); else zs.send();
            // user cancelled the session via Ctrl+C/Ctrl+U or file-picker dialog
            if (transferCancelFlag || transferCancelledByUser) {
                cleanTempDownloads();
                Platform.runLater(() -> emulator.statusRed("\r\nZModem " + dirLabel + " cancelled\r\n"));
            } else {
                commitTempDownloads();
                Platform.runLater(() -> emulator.statusGreen("\r\nZModem " + dirLabel + " finished\r\n"));
            }
        } catch (Exception ex) {
            abnormal = true;
            cleanTempDownloads();
            log.warn("ZModem session ended: {}", ex.toString());
            String m = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            Platform.runLater(() -> emulator.statusRed("\r\nZModem: " + m + "\r\n"));
        } finally {
            // A streaming sz never reads its stdin (and lrzsz disables ISIG), so
            // neither CAN spam nor ETX bytes can stop it: kill it out-of-band
            // before draining, otherwise sz keeps streaming garbage for the full
            // drain window and then retransmits ZEOF/ZFIN in garbage spurts.
            if (transferCancelFlag || abnormal) {
                killRemoteZmodemProcess();
                // sz is dead but its last output is still inside the SSH/pty
                // pipeline buffers; draining it can take seconds on slow boards —
                // tell the user what the pause is instead of leaving a dead screen.
                Platform.runLater(() -> emulator.status(
                        I18n.t("ssh.zmodem.draining", "\r\nZModem: cleaning up residual data...\r\n")));
            }
            // Drain residual bytes (echoed ZRINIT/ZRQINIT headers, stray protocol bytes)
            // that would otherwise be rendered as garbage or trigger a spurious
            // ZModem beacon detection on the next read.
            // drainQuiet reads and discards in-flight data until ~800 ms of silence,
            // then returns.  The shell prompt is eaten in the process, so we nudge the
            // remote shell with a newline so it reprints a clean prompt.
            zs.drainQuiet();
            try {
                OutputStream nos = shellChannel.getInvertedIn();
                if (nos != null) {
                    nos.write('\n');
                    nos.flush();
                }
            } catch (Exception ignored) {
                // channel may already be gone
            }
            zmodemActive = false;
        }
    }

    /** Best-effort remote kill of stuck sz/rz processes after a cancelled/failed
     * transfer. Uses an exec channel on the same SSH connection (no re-auth),
     * so it works even while sz owns the shell channel's foreground.
     * Runs on the reader thread; bounded to a few seconds. Note: pkill is not
     * scoped to this tab's pty, so sz/rz processes from OTHER sessions on the
     * same server would be killed too (accepted trade-off). */
    private void killRemoteZmodemProcess() {
        try {
            ClientSession s = session;
            if (s == null || !s.isOpen()) {
                return;
            }
            // pkill on procps systems, killall as busybox fallback; SIGKILL is
            // unconditional and needs no grace period for a transfer we abort.
            String cmd = "pkill -KILL sz 2>&1 || killall -KILL sz 2>&1; "
                    + "pkill -KILL rz 2>&1 || killall -KILL rz 2>&1; echo kill-done";
            try (ChannelExec exec = s.createExecChannel(cmd)) {
                exec.open().verify(5000);
                exec.waitFor(java.util.EnumSet.of(ClientChannelEvent.CLOSED), 3000);
                String out = readExecOutput(exec);
                log.info("remote sz/rz kill: exit={} output=[{}]", exec.getExitStatus(), out);
            }
        } catch (Exception e) {
            log.warn("remote sz/rz kill failed: {}", e.toString());
        }
    }

    /** Read whatever the exec command printed (bounded), for diagnosing kill failures. */
    private static String readExecOutput(ChannelExec exec) {
        try {
            InputStream is = exec.getInvertedOut();
            if (is == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1024];
            while (is.available() > 0 && sb.length() < 4096) {
                int n = is.read(buf);
                if (n < 0) {
                    break;
                }
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Rename all .temp download files to their final names (success path). */
    private void commitTempDownloads() {
        synchronized (pendingTempDownloads) {
            for (java.util.Map.Entry<File, File> e : pendingTempDownloads.entrySet()) {
                File temp = e.getKey();
                File real = e.getValue();
                if (temp.exists()) {
                    if (real.exists()) real.delete();
                    if (temp.renameTo(real)) {
                        log.info("renamed temp {} to {}", temp.getName(), real.getName());
                    } else {
                        log.warn("failed to rename temp {} to {}", temp, real);
                    }
                }
            }
            pendingTempDownloads.clear();
        }
    }

    /** Delete all temp files (cancel/error path). */
    private void cleanTempDownloads() {
        synchronized (pendingTempDownloads) {
            for (File temp : pendingTempDownloads.keySet()) {
                if (temp.exists()) {
                    if (temp.delete()) {
                        log.info("deleted temp file {}", temp);
                    } else {
                        log.warn("failed to delete temp file {}", temp);
                    }
                }
            }
            pendingTempDownloads.clear();
        }
    }

    private File lastTransferDir = new File(System.getProperty("user.home"), "Desktop");
    /** Maps .temp download files to their final names; renamed on success, deleted on cancel/error. */
    private final java.util.Map<File, File> pendingTempDownloads = new java.util.LinkedHashMap<>();
    private volatile long progressStartMs;
    private volatile long lastProgressMs;

    private final ZModemHandler zmodemHandler = new ZModemHandler() {
        @Override
        public File chooseSaveFile(String remoteName, long size) {
            java.util.concurrent.atomic.AtomicReference<File> ref = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    log.info("ZModem: showing save dialog for {}", remoteName);
                    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                    fc.setTitle("Save file");
                    fc.setInitialFileName(remoteName);
                    if (lastTransferDir != null && lastTransferDir.isDirectory()) fc.setInitialDirectory(lastTransferDir);
                    File f = fc.showSaveDialog(renderer.getCanvas().getScene().getWindow());
                    log.info("ZModem: save dialog closed, result={}", f);
                    if (f != null) {
                        lastTransferDir = f.getParentFile();
                        // write to .temp first; rename on success, delete on cancel/error
                        File tempFile = new File(f.getParentFile(), f.getName() + ".temp");
                        synchronized (pendingTempDownloads) { pendingTempDownloads.put(tempFile, f); }
                        ref.set(tempFile);
                        renderer.requestFocus();
                    } else {
                        transferCancelledByUser = true;
                        ref.set(null);
                    }
                } finally {
                    latch.countDown();
                }
            });
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ref.get();
        }

        @Override
        public List<File> chooseUploadFiles() {
            java.util.concurrent.atomic.AtomicReference<List<File>> ref = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    log.info("ZModem: showing upload file dialog");
                    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                    fc.setTitle("Select files to upload");
                    if (lastTransferDir != null && lastTransferDir.isDirectory()) fc.setInitialDirectory(lastTransferDir);
                    List<File> files = fc.showOpenMultipleDialog(renderer.getCanvas().getScene().getWindow());
                    log.info("ZModem: upload dialog closed, files={}", files == null ? 0 : files.size());
                    if (files != null && !files.isEmpty()) {
                        lastTransferDir = files.get(0).getParentFile();
                        renderer.requestFocus();
                    } else {
                        transferCancelledByUser = true;
                    }
                    ref.set(files);
                } finally {
                    latch.countDown();
                }
            });
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ref.get();
        }

        @Override
        public void onProgress(String name, long done, long total) {
            long now = System.currentTimeMillis();
            if (done == 0 || progressStartMs == 0) progressStartMs = now;
            if (done < total && now - lastProgressMs < 100) return; // throttle UI updates
            lastProgressMs = now;
            String msg;
            if (total > 0) {
                int pct = (int) Math.min(100, done * 100 / total);
                long elapsed = now - progressStartMs;
                long bps = elapsed > 0 ? done * 1000 / elapsed : 0;
                String speed = bps > 1048576 ? String.format("%.1fMB/s", bps / 1048576.0) : (bps > 1024 ? bps / 1024 + "KB/s" : bps + "B/s");
                long rem = total - done;
                String eta = bps > 0 && rem > 0 ? " ETA:" + (rem / bps) + "s" : "";
                msg = "\r" + name + ": " + pct + "% " + speed + eta;
            } else {
                msg = "\r" + name + ": " + (done / 1024) + "KB";
            }
            while (msg.length() < 80) msg += " ";
            emulator.progressStatus(msg);
        }

        @Override
        public void onMessage(String message) {
            Platform.runLater(() -> emulator.status(message));
        }

        @Override
        public boolean isCancelled() {
            return transferCancelFlag;
        }
    };
}
