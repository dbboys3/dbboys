package com.dbboys.ui.controller;
import com.dbboys.app.AppExecutor;
import com.dbboys.infra.config.ConfigManagerUtil;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.SshUtil;
import com.dbboys.infra.zmodem.ZModemHandler;
import com.dbboys.infra.zmodem.ZModemSession;
import com.dbboys.model.SshConnect;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
/**
 * SSH terminal tab controller with embedded Canvas-based terminal emulator.
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);
    /** Dump raw terminal bytes (escapes shown) to the app log. Enable via
     *  -Ddbboys.term.rawlog=true or SSH_TERMINAL_RAWLOG=true in etc/config.properties. */
    private static final boolean RAW_LOG = Boolean.getBoolean("dbboys.term.rawlog")
            || Boolean.parseBoolean(ConfigManagerUtil.getProperty("SSH_TERMINAL_RAWLOG", "false"));
    // Terminal font: zoomable with Ctrl + '+' / Ctrl + '-' and Ctrl + mouse wheel,
   // persisted in etc/config.properties (SSH_TERMINAL_FONT_SIZE)
   private static final String SSH_FONT_SIZE_KEY = "SSH_TERMINAL_FONT_SIZE";
   private static final int DEFAULT_FONT_SIZE = 13;
   private static final int MIN_FONT_SIZE = 4;   // same bounds as the SQL editor
   private static final int MAX_FONT_SIZE = 40;
   private static final int FONT_SIZE_STEP = 1;
    // On Windows prefer Consolas; on other platforms fall back to the system monospace font.
    private static final String FONT_FAMILY = Font.getFamilies().contains("Consolas") ? "Consolas" : "monospace";
   private int fontSize = loadConfiguredFontSize();
   private Font FONT = Font.font(FONT_FAMILY, fontSize);
   private double CHAR_W = measureCharW(FONT);
   private double LINE_H = fontSize * 1.4;

   

    private static int loadConfiguredFontSize() {
        try {
            return clampFontSize(Integer.parseInt(ConfigManagerUtil.getProperty(
                    SSH_FONT_SIZE_KEY, String.valueOf(DEFAULT_FONT_SIZE))));
        } catch (NumberFormatException e) {
            return DEFAULT_FONT_SIZE;
        }
    }
    private static int clampFontSize(int size) { return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size)); }
    private static double measureCharW(Font f) {
        Text m = new Text("W");
        m.setFont(f);
        return m.getLayoutBounds().getWidth();
    }
    private static boolean isZoomInKey(KeyEvent e) {
        return e.getCode() == KeyCode.ADD || e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.EQUALS;
    }
    private static boolean isZoomOutKey(KeyEvent e) {
        return e.getCode() == KeyCode.SUBTRACT || e.getCode() == KeyCode.MINUS;
    }
    /** Request keyboard focus on the terminal canvas. */
    public void requestFocus() {
        canvas.requestFocus();
    }

    // ---- Terminal cell with per-character SGR attributes ----
    /** A single character cell storing both the glyph and its SGR styling. */
    private static class Cell {
        char ch = ' ';
        int fg = 37, bg = 40;   // SGR color codes (30-37/39 foreground, 40-47/49 background)
        int extFg = -1, extBg = -1; // 256-color extended colors (-1 = not set)
        boolean bold, underline, reverse;
        void reset() { ch = ' '; fg = 37; bg = 40; extFg = extBg = -1; bold = underline = reverse = false; }
        Cell copy() {
            Cell c = new Cell();
            c.ch = this.ch; c.fg = this.fg; c.bg = this.bg;
            c.extFg = this.extFg; c.extBg = this.extBg;
            c.bold = this.bold; c.underline = this.underline; c.reverse = this.reverse;
            return c;
        }
    }
    /** A buffer row. {@link #wrapped} is set when the row ends in an automatic
     *  (soft) wrap, i.e. it logically continues onto the next row. Hard newlines
     *  leave the flag false. The flag rides on the row object, so it survives the
     *  row insertions/removals done by scroll/insert/delete operations, and lets
     *  {@link #reflowBuffer(int)} re-wrap text when the terminal width changes. */
    private static class Row extends ArrayList<Cell> {
        boolean wrapped;
    }
    @FXML public StackPane terminalPane;
    @FXML public Button connectButton;
    @FXML public Button disconnectButton;
    @FXML public Label connectionLabel;
    @FXML public Label charsetLabel;
    @FXML public ChoiceBox<String> charsetChoiceBox;
    @FXML public CheckBox logCheckBox;
    @FXML public VBox sshTab;
    private SshConnect sshConnect;
    private ClientSession session;
    private ChannelShell shellChannel;
    private String charset = "UTF-8"; // terminal encoding, synced from SshConnect.charset

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
    // Terminal state
    private Canvas canvas;
    private int cols = 80, rows = 24;
    private final List<List<Cell>> buffer = new ArrayList<>();
    private int curCol, curRow;
    private int selStartCol = -1, selStartRow = -1, selEndCol = -1, selEndRow = -1;
    private boolean selecting, cursorVis = true, focused = true;
    private final Timeline blink;
    private int sgrFg = 37, sgrBg = 40;
    private boolean sgrReverse, sgrBold, sgrUnderline;
    private boolean cursorShown = true; // DECTCEM
    private boolean cursorKeysApp; // DECCKM: true=ESC OA, false=ESC [A
    private boolean decawm = true; // DECAWM (?7h/?7l): auto-wrap at the last column
    private int scrollTop, scrollBottom = -1; // DECSTBM scroll region
    private boolean originMode; // DECOM
    private boolean pendingWrap; // auto-wrap happened, skip next
    private boolean wrapPendingEraseSuppress; // suppress eraseEOL on wrap
    private int savedCurCol, savedCurRow; // DECSC/DECRC
    private char g0Charset = 'B';  // G0 charset: 'B'=ASCII, '0'=DEC Special Graphics
    private char g1Charset = 'B';  // G1 charset
    private boolean useG1;          // true when SO (^N) active, using G1
    private int savedSgrFg, savedSgrBg;
    private boolean savedSgrReverse, savedSgrBold, savedSgrUnderline;
    private Thread readThread;
    private volatile boolean connecting;
    private volatile boolean transferCancelFlag; // Ctrl+C/Ctrl+U aborts the active file transfer
    private volatile boolean zmodemActive; // true while a ZModem session owns the SSH stream
    private final StringBuilder inputBuffer = new StringBuilder(); // guards against concurrent connect attempts
    private int scrollOff, maxScroll = 5000;
    private Runnable onScrollChanged;
    private String pendingEsc;
    private boolean scrollLock;
    private int sgrExtFg = -1, sgrExtBg = -1; // 256-color extended colors
    private List<List<Cell>> altSavedBuffer;
    private int altSavedCurCol, altSavedCurRow, altSavedScrollOff, altSavedCols, altSavedRows;
    private boolean inAltScreen; // whether alternate screen buffer (?1049h) is active
    // Deferred draw: coalesce rapid write() calls into a single draw,
    // preventing the blink timer from rendering partially-updated screens.
    private volatile boolean drawPending;
    private Timeline autoScrollTimeline;
    private int autoScrollDirection = 0;
    // Logging
    private java.io.BufferedWriter logWriter;
    private boolean logging;
    public SshTabController() {
        blink = new Timeline(new KeyFrame(Duration.millis(530), e -> {
            cursorVis = !cursorVis;
            if (focused && canvas != null && !drawPending) draw();
        }));
        blink.setCycleCount(Timeline.INDEFINITE);
        blink.play();
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
        // Log checkbox
        logCheckBox.textProperty().bind(I18n.bind("ssh.label.log"));
        logCheckBox.setOnAction(e -> {
            if (logCheckBox.isSelected()) {
                startLogging();
            } else {
                stopLogging();
            }
        });
        // Canvas terminal
        buffer.add(new Row());
        canvas = new Canvas(cols * CHAR_W, rows * LINE_H);
        canvas.setFocusTraversable(true);
        canvas.focusedProperty().addListener((o, ov, n) -> { focused = n; draw(); });
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
                int maxScroll = Math.max(0, buffer.size() - rows);
                if (v != scrollOff) {
                    scrollOff = clamp(v, 0, maxScroll);
                    scrollLock = scrollOff < maxScroll; // dragging back to the bottom re-engages follow mode
                    draw();
                }
            }
        });
        onScrollChanged = () -> {
            Platform.runLater(() -> {
                int max = inAltScreen ? 0 : Math.max(0, buffer.size() - rows);
                scrollBar.setVisible(max > 0);
                updatingScrollBar = true;
                // Fixed visible amount keeps thumb at a minimum readable size
                                int visAmount = max > 0 ? Math.min(max, Math.max(rows, max / 8)) : 1;
                                scrollBar.setMax(max);
                scrollBar.setVisibleAmount(visAmount);
                scrollBar.setValue(scrollOff);
                updatingScrollBar = false;
            });
        };
        // Resize listeners
        terminalPane.widthProperty().addListener((obs, o, n) -> {
            if (n.doubleValue() > 0) {
                int newCols = Math.max(1, (int) (n.doubleValue() / CHAR_W));
                if (newCols != cols) {
                    if (inAltScreen) {
                        // Alt screen cannot be reflowed: clear it so the full-screen
                        // app repaints a fresh frame after SIGWINCH (anti-residue)
                        clearBuffer();
                    } else {
                        reflowBuffer(newCols);
                    }
                    cols = newCols;
                    canvas.setWidth(cols * CHAR_W);
                    draw();
                    fireScrollChanged();
                }
                updatePtySize();
            }
        });
        terminalPane.heightProperty().addListener((obs, o, n) -> {
            if (n.doubleValue() > 0) {
                int newRows = Math.max(1, (int) (n.doubleValue() / LINE_H));
                if (newRows != rows) {
                    rows = newRows;
                    canvas.setHeight(rows * LINE_H);
                    if (inAltScreen) {
                        // Alt screen cannot be reflowed: clear it so the full-screen
                        // app repaints a fresh frame after SIGWINCH (anti-residue)
                        clearBuffer();
                        // Alt screen is a fixed page: keep the scroll region in sync with the visible size
                        scrollTop = 0;
                        scrollBottom = rows - 1;
                    }
                    // Keep scrollOff valid after resize
                    if (scrollOff > Math.max(0, buffer.size() - rows)) {
                        scrollOff = Math.max(0, buffer.size() - rows);
                    }
                    if (!inAltScreen && !scrollLock) {
                        // Following output: keep the viewport pinned to the bottom across resizes
                        scrollOff = Math.max(0, buffer.size() - rows);
                    }
                    draw();
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
        status("Connecting to " + sshConnect.getUsername() + "@"
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
                    cursorShown = true; // restore cursor on successful connect
                    if (blink != null) blink.play(); // ensure blink is running after reconnect
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    canvas.requestFocus();
                    if (onConnectionStateChanged != null) onConnectionStateChanged.accept(true);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connected", "Connected") + "]");
                    statusGreen(I18n.t("ssh.tab.connected", "Connected") + "\r\n");
                    updatePtySize();
                });
            } catch (Exception ex) {
                log.error("SSH connect failed", ex);
                Platform.runLater(() -> {
                    connecting = false;
                    cursorShown = false;
                    draw();
                    connectButton.setDisable(false);
                    disconnectButton.setDisable(true);
                    if (onConnectionStateChanged != null) onConnectionStateChanged.accept(false);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connect_failed", "Connect Failed") + "]");
                    statusRed("[ERROR] " + ex.getMessage() + "\r\n");
                });
            }
        });
    }
    private void doDisconnect() {
        stop();
        SshUtil.disconnectSession(session);
        session = null;
        shellChannel = null;
        cursorShown = false;
        draw();
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
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
                shellChannel.sendWindowChange(cols, rows, (int) canvas.getWidth(), (int) canvas.getHeight());
            } catch (Exception ignored) {}
        }
    }
    /** Zoom the terminal font by delta steps: recompute metrics, re-tile the grid,
     *  reflow content to the new column count, and persist the size to config. */
    private void adjustFontSize(int delta) {
        int newSize = clampFontSize(fontSize + delta);
        if (newSize == fontSize || canvas == null) return;
        fontSize = newSize;
        FONT = Font.font(FONT_FAMILY, fontSize);
        CHAR_W = measureCharW(FONT);
        LINE_H = fontSize * 1.4;
        int newCols = Math.max(1, (int) (terminalPane.getWidth() / CHAR_W));
        int newRows = Math.max(1, (int) (terminalPane.getHeight() / LINE_H));
        boolean gridChanged = newCols != cols || newRows != rows;
        if (inAltScreen && gridChanged) {
            // The alt screen cannot be reflowed: clear it and let the full-screen
            // app repaint after SIGWINCH, otherwise stale cells (e.g. nmon's
            // frame) linger as residue around the new grid.
            clearBuffer();
        } else if (newCols != cols) {
            reflowBuffer(newCols);
        }
        cols = newCols;
        rows = newRows;
        canvas.setWidth(cols * CHAR_W);
        canvas.setHeight(rows * LINE_H);
        if (inAltScreen) {
            // Alt screen is a fixed page: keep the scroll region in sync with the visible size
            scrollTop = 0;
            scrollBottom = rows - 1;
        }
        // Keep scrollOff valid after the grid change
        if (scrollOff > Math.max(0, buffer.size() - rows)) {
            scrollOff = Math.max(0, buffer.size() - rows);
        }
        if (!inAltScreen && !scrollLock) {
            // Following output: keep the viewport pinned to the bottom
            scrollOff = Math.max(0, buffer.size() - rows);
        }
        draw();
        fireScrollChanged();
        updatePtySize();
        ConfigManagerUtil.setProperty(SSH_FONT_SIZE_KEY, String.valueOf(fontSize));
    }
    public void closeSession() { doDisconnect(); }
    // ==================== Terminal engine ====================
    private void start() {
        if (shellChannel == null || !shellChannel.isOpen()) return;
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
                // read returned -1 or channel disconnected ???connection lost
                Platform.runLater(this::onConnectionLost);
            } catch (Exception e) {
                // read thread interrupted or IO error ???connection likely lost
                Platform.runLater(this::onConnectionLost);
            }
        }, "term-reader");
        readThread.setDaemon(true);
        readThread.start();
    }
    private void stop() {
        blink.stop();
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
        statusRed("\r\nDisconnected\r\n");
        cursorShown = false;
        draw();
        if (onConnectionStateChanged != null) onConnectionStateChanged.accept(false);
        connectStatus.set((sshConnect != null
                ? sshConnect.getUsername() + "@" + sshConnect.getHost() + ":" + sshConnect.getPort()
                : "") + " [" + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
    }

    /** Request a deferred draw. Coalesces rapid write() calls to prevent
     *  the blink timer rendering a partially-updated screen during full-screen
     *  program refreshes (top, nmon, etc.). */
    private void requestDraw() {
        if (!drawPending) {
            drawPending = true;
            Platform.runLater(() -> {
                drawPending = false;
                draw();
                fireScrollChanged();
            });
        }
    }
    private void status(String s) {
        for (char c : s.toCharArray()) {
            if (c == '\n') { pendingWrap = false; nl(); }
            else if (c == '\r') { curCol = 0; pendingWrap = false; }
            else put(c);
        }
        if (!s.endsWith("\n") && !s.endsWith("\r\n")) nl();
        requestDraw();
    }

    /** Write status text in red, then restore default SGR. */
    private void statusRed(String s) {
        int saveFg = sgrFg, saveBg = sgrBg, saveExtFg = sgrExtFg, saveExtBg = sgrExtBg;
        boolean saveBold = sgrBold, saveRev = sgrReverse, saveUnder = sgrUnderline;
        sgrFg = 31; sgrBg = 40; sgrExtFg = -1; sgrExtBg = -1;
        sgrBold = false; sgrReverse = false; sgrUnderline = false;
        status(s);
        sgrFg = saveFg; sgrBg = saveBg; sgrExtFg = saveExtFg; sgrExtBg = saveExtBg;
        sgrBold = saveBold; sgrReverse = saveRev; sgrUnderline = saveUnder;
    }
    /** Write status text in green, then restore default SGR. */
    private void statusGreen(String s) {
        int saveFg = sgrFg, saveBg = sgrBg, saveExtFg = sgrExtFg, saveExtBg = sgrExtBg;
        boolean saveBold = sgrBold, saveRev = sgrReverse, saveUnder = sgrUnderline;
        sgrFg = 32; sgrBg = 40; sgrExtFg = -1; sgrExtBg = -1;
        sgrBold = false; sgrReverse = false; sgrUnderline = false;
        status(s);
        sgrFg = saveFg; sgrBg = saveBg; sgrExtFg = saveExtFg; sgrExtBg = saveExtBg;
        sgrBold = saveBold; sgrReverse = saveRev; sgrUnderline = saveUnder;
    }
    private void write(String raw) {
        // prepend any incomplete escape sequence from the previous chunk
        if (pendingEsc != null) {
            raw = pendingEsc + raw;
            pendingEsc = null;
        }
        if (RAW_LOG && !raw.isEmpty()) log.info("TERM <<< {}", escapeForLog(raw));
        // Log large chunks (top/nmon output) for debugging
        boolean isTopData = raw.length() > 200;
        if (isTopData) {
            // log.info("=== SSH RAW ({} chars) wrap={} cur=({},{}) ===",
            //        raw.length(), pendingWrap, curCol, curRow);
            //log.info("  {}", escapeForLog(raw.substring(0, Math.min(2000, raw.length()))));
        }
        for (int i = 0, n = raw.length(); i < n; i++) {
            char c = raw.charAt(i);
            if (c == 0x1B) {
                int ni = esc(raw, i + 1, n);
                if (ni < 0) { pendingEsc = raw.substring(i); return; }
                i = ni;
            }
            else if (c == '\b') { wrapPendingEraseSuppress = false; if (pendingWrap) { pendingWrap = false; curCol = cols - 1; } else if (curCol > 0) curCol--; }
            else if (c == '\r') { curCol = 0; pendingWrap = false; wrapPendingEraseSuppress = false; }
            else if (c == '\n') {
                // Deferred wrap: LF always moves down exactly one line, whether or
                // not the previous line filled the last column (xterm semantics)
                pendingWrap = false;
                wrapPendingEraseSuppress = false;
                nl();
            }
            else if (c == 0x09) { pendingWrap = false; wrapPendingEraseSuppress = false; curCol = ((curCol / 8) + 1) * 8; if (curCol >= cols) { curCol = 0; pendingWrap = true; wrapPendingEraseSuppress = true; } }
            else if (c == 0x0E) { useG1 = true; } // SO - shift out, use G1 charset
            else if (c == 0x0F) { useG1 = false; } // SI - shift in, use G0 charset
            else if (c == 0x7F) { if (curCol > 0) curCol--; } // DEL = backspace
            else if (c >= 0x20) put(c);
        }
        if (isTopData) {
           // log.info("=== AFTER ===");
            dumpBuffer();
        }
        requestDraw();
        if (onActivity != null && !raw.isEmpty()) onActivity.run();
    }
    // ---- Buffer ----
    /** Top buffer row of the visible page (the page sits below any scrollback history). */
    private int pageTop() { return Math.max(0, buffer.size() - rows); }
    private void nl() {
        // Bottom margin must be computed before ensureBuf() may grow the buffer
        int effectiveBottom = scrollBottom >= 0 ? scrollBottom : pageTop() + rows - 1;
        curRow++;
        ensureBuf(curRow);
        while (buffer.size() > maxScroll) { buffer.remove(0); curRow--; scrollOff = Math.max(0, scrollOff - 1); }
        if (!scrollLock && curRow > effectiveBottom) {
            if (scrollBottom >= 0 && scrollTop < effectiveBottom) {
                // DECSTBM scroll region: scroll within region, discarding top line
                scrollRegionUp(scrollTop, effectiveBottom);
                curRow = effectiveBottom;
            } else {
                // Normal mode: advance viewport, preserve history in buffer
                scrollOff = curRow - rows + 1;
            }
        } else if (!scrollLock && curRow - scrollOff >= rows) {
            scrollOff = curRow - rows + 1;
        }
    }
    private void fireScrollChanged() { if (onScrollChanged != null) onScrollChanged.run(); }
    private void put(char c) {
        if (pendingWrap) {
            // Deferred wrap (xterm semantics): the previous character filled the
            // last column, but the line wrap only happens now that the next
            // printable character arrives. This is what keeps full-screen apps
            // (top, nmon) from scrolling the first line away / growing phantom
            // rows when a frame ends on a full-width line.
            pendingWrap = false; wrapPendingEraseSuppress = false;
            curCol = 0;
            curRow++;
            markWrapped(curRow - 1); // soft wrap: the row just left continues onto the next one
            int effectiveBottom = scrollBottom >= 0 ? scrollBottom : pageTop() + rows - 1;
            if (curRow > effectiveBottom) {
                if (inAltScreen) {
                    // Alt screen has no scrollback: scroll within region, discarding the top line
                    scrollRegionUp(scrollTop, effectiveBottom);
                    curRow = effectiveBottom;
                } else if (!scrollLock) {
                    if (scrollBottom >= 0) {
                        // DECSTBM scroll region: scroll within region, discarding top line
                        int top = scrollTop;
                        int bottom = Math.max(scrollTop, effectiveBottom);
                        scrollRegionUp(top, bottom);
                        curRow = bottom;
                    } else {
                        // Normal mode: advance viewport, preserve history in buffer
                        scrollOff = curRow - rows + 1;
                    }
                }
            }
        } else {
            wrapPendingEraseSuppress = false;
        }
        List<Cell> ln = ensureBuf(curRow);
        // Extend row to accommodate curCol
        if ((g0Charset == '0' && !useG1) || (g1Charset == '0' && useG1)) { c = mapDecSpecial(c); }
        while (ln.size() <= curCol) ln.add(new Cell());
        Cell cell = ln.get(curCol);
        cell.ch = c;
        cell.fg = sgrFg; cell.bg = sgrBg; cell.extFg = sgrExtFg; cell.extBg = sgrExtBg;
        cell.bold = sgrBold; cell.underline = sgrUnderline; cell.reverse = sgrReverse;
        int w = isFullwidth(c) ? 2 : 1;
        if (w == 2 && curCol + 1 < cols) {
            while (ln.size() <= curCol + 1) ln.add(new Cell());
            Cell cont = ln.get(curCol + 1);
            cont.ch = '\0';
            cont.fg = sgrFg; cont.bg = sgrBg;
            cont.bold = sgrBold; cont.underline = sgrUnderline; cont.reverse = sgrReverse;
        }
        curCol += w;
        if (curCol >= cols) {
            if (decawm) {
                // Last column filled: only mark the wrap as pending. curRow does not
                // advance (and nothing scrolls) until the next printable character.
                curCol = 0;
                pendingWrap = true; wrapPendingEraseSuppress = true;
            } else {
                // DECAWM off (?7l): stay on the last column; the next character
                // overwrites it (nmon uses this to draw the bottom-right corner)
                curCol = cols - 1;
            }
        }
    }
    private List<Cell> ensureBuf(int r) {
        while (buffer.size() <= r) buffer.add(new Row());
        return buffer.get(r);
    }
    private String line(int r) {
        if (r >= buffer.size()) return "";
        List<Cell> row = buffer.get(r);
        StringBuilder sb = new StringBuilder(row.size());
        for (Cell c : row) {
            if (c.ch != '\0') sb.append(c.ch);
        }
        return sb.toString();
    }
    private void jumpToBottom() {
        int maxOff = Math.max(0, buffer.size() - rows);
        scrollLock = false; // typing means the user wants to follow output again
        if (scrollOff != maxOff) {
            scrollOff = maxOff;
            draw();
            fireScrollChanged();
        }
    }
    /** True if buffer row r ends in a soft (auto) wrap and continues onto row r+1. */
    private boolean isWrapped(int r) {
        List<Cell> row = buffer.get(r);
        return row instanceof Row && ((Row) row).wrapped;
    }
    /** Mark buffer row r as soft-wrapped (it continues onto the next row). */
    private void markWrapped(int r) {
        if (r >= 0 && r < buffer.size() && buffer.get(r) instanceof Row) {
            ((Row) buffer.get(r)).wrapped = true;
        }
    }
    /** Re-wrap all buffer content to a new column count. Rows linked by soft wraps
     *  are rejoined into their logical line and wrapped at the new width; hard
     *  newlines are preserved. Cursor, scroll offset and the vertical scrollbar
     *  then adapt to the resulting row count. Skipped on the alternate screen
     *  (full-screen apps redraw themselves after SIGWINCH). */
    private void reflowBuffer(int newCols) {
        if (inAltScreen || buffer.isEmpty() || newCols < 2) return;
        // Cursor position as a linear cell offset within its logical line
        int cur = Math.min(curRow, buffer.size() - 1);
        int curLogStart = cur;
        while (curLogStart > 0 && isWrapped(curLogStart - 1)) curLogStart--;
        int curOffset = 0;
        for (int r = curLogStart; r < cur; r++) curOffset += buffer.get(r).size();
        curOffset += pendingWrap ? buffer.get(cur).size() : Math.min(curCol, buffer.get(cur).size());
        // First visible logical line, to keep the viewport stable when scrolled up
        int visLogStart = Math.min(scrollOff, buffer.size() - 1);
        while (visLogStart > 0 && isWrapped(visLogStart - 1)) visLogStart--;

        List<Row> newBuf = new ArrayList<>(buffer.size());
        int newCurLogStart = -1, newVisLogStart = 0;
        int r = 0;
        while (r < buffer.size()) {
            int end = r;
            while (end < buffer.size() - 1 && isWrapped(end)) end++;
            if (r == curLogStart) newCurLogStart = newBuf.size();
            if (r == visLogStart) newVisLogStart = newBuf.size();
            Row out = new Row();
            newBuf.add(out);
            for (int i = r; i <= end; i++) {
                List<Cell> src = buffer.get(i);
                for (int k = 0; k < src.size(); k++) {
                    Cell cell = src.get(k);
                    if (cell.ch == '\0') continue; // continuation cells travel with their character
                    boolean fw = isFullwidth(cell.ch);
                    int w = fw ? 2 : 1;
                    if (out.size() + w > newCols) {
                        while (out.size() < newCols) out.add(new Cell()); // never split a fullwidth pair
                        out.wrapped = true;
                        out = new Row();
                        newBuf.add(out);
                    }
                    out.add(cell);
                    if (fw) {
                        Cell cont;
                        if (k + 1 < src.size() && src.get(k + 1).ch == '\0') { cont = src.get(k + 1); k++; }
                        else { cont = new Cell(); cont.ch = '\0'; }
                        out.add(cont);
                    }
                }
            }
            r = end + 1;
        }
        if (newCurLogStart < 0) newCurLogStart = Math.max(0, newBuf.size() - 1);
        // Map the linear cursor offset back to row/column at the new width
        int newCurRow, newCurCol;
        boolean newPendingWrap = false;
        if (curOffset > 0 && curOffset % newCols == 0) {
            // Cursor sits just past a full row: keep deferred-wrap semantics
            newCurRow = newCurLogStart + curOffset / newCols - 1;
            newCurCol = 0;
            newPendingWrap = true;
        } else {
            newCurRow = newCurLogStart + curOffset / newCols;
            newCurCol = curOffset % newCols;
        }
        buffer.clear();
        buffer.addAll(newBuf);
        curRow = newCurRow;
        curCol = newCurCol;
        pendingWrap = newPendingWrap;
        wrapPendingEraseSuppress = newPendingWrap; // pairs with pendingWrap (see put())
        while (buffer.size() > maxScroll) { buffer.remove(0); curRow--; }
        curRow = Math.max(0, curRow);
        scrollOff = scrollLock
                ? clamp(newVisLogStart, 0, Math.max(0, buffer.size() - rows)) // scrolled up: keep the same content in view
                : Math.max(0, buffer.size() - rows);                        // following output: stay pinned to the bottom
        // Selection and DECSTBM region no longer map to rows after reflow
        selStartCol = selEndCol = selStartRow = selEndRow = -1;
        scrollTop = 0;
        scrollBottom = -1;
    }
    // ---- ANSI ----
    private int esc(String s, int p, int e) {
        if (p >= e) return -1;
        char c = s.charAt(p);
        if (c == '[') { int r = csi(s, p + 1, e); return r < 0 ? -1 : r; }
        if (c == ']') { int r = osc(s, p + 1, e); return r < 0 ? -1 : r; }
        if (c == '(' || c == ')') { int r = consumeCharset(s, p + 1, e, c == '('); return r < 0 ? -1 : r; }
        // ESC 7 / ESC 8 ???save/restore cursor (DECSC/DECRC)
        if (c == '7') { saveCursor(); return p; }
        if (c == '8') { restoreCursor(); return p; }
        // ESC M ???reverse index (RI)
        if (c == 'M') { reverseIndex(); return p; }
        // ESC D ???index (IND, move down one line)
        if (c == 'D') { indexDown(); return p; }
        // ESC E ???next line (NEL)
        if (c == 'E') { curCol = 0; indexDown(); return p; }
        // ESC H ???horizontal tab set
        if (c == 'H') return p;
        // ESC > ???alternate keypad numeric; ESC = ???alternate keypad application
        if (c == '>' || c == '=') return p;
        // ESC c ???RIS (reset to initial state)
        // ESC O A/B/C/D -- SS3 cursor keys (when DECCKM is enabled)
        if (c == 'O' && p + 1 < e) {
            char oc = s.charAt(p + 1);
            switch (oc) {
                case 'A': curRow = Math.max(originMode ? scrollTop : 0, curRow - 1); return p + 1;
                case 'B': curRow = Math.min(buffer.isEmpty() ? 0 : buffer.size() - 1, curRow + 1); return p + 1;
                case 'C': curCol = Math.min(cols - 1, curCol + 1); return p + 1;
                case 'D': curCol = Math.max(0, curCol - 1); return p + 1;
                case 'H': curRow = 0; curCol = 0; return p + 1;
                case 'F': curRow = Math.max(0, buffer.size() - 1); curCol = 0; return p + 1;
            }
        }     
    if (c == 'c') { resetTerminal(); return p; }
        return p;
    }
    private int consumeCharset(String s, int p, int e, boolean isG0) {
        if (p < e) { char cs = s.charAt(p); if (isG0) g0Charset = cs; else g1Charset = cs; return p; }
        return -1;
    }
    private void resetTerminal() {
        buffer.clear(); buffer.add(new Row());
        curCol = curRow = scrollOff = 0;
        scrollTop = 0; scrollBottom = -1; originMode = false;
        sgrFg = 37; sgrBg = 40; sgrReverse = sgrBold = sgrUnderline = false;
        g0Charset = 'B'; g1Charset = 'B'; useG1 = false;
        pendingWrap = false;
        decawm = true; // RIS restores auto-wrap
        draw();
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
            try { logWriter.close(); } catch (Exception ignored) {}
            logWriter = null;
        }
        logging = false;
    }

    private void saveCursor() {
        savedCurCol = curCol; savedCurRow = curRow;
        savedSgrFg = sgrFg; savedSgrBg = sgrBg;
        savedSgrReverse = sgrReverse; savedSgrBold = sgrBold;
        savedSgrUnderline = sgrUnderline;
    }
    private void restoreCursor() {
        // Clamp to current bounds: the saved position may come from a different
        // grid (e.g. saved in the alt screen before a font/window resize)
        curCol = clamp(savedCurCol, 0, Math.max(0, cols - 1));
        curRow = clamp(savedCurRow, 0, Math.max(0, buffer.size() - 1));
        sgrFg = savedSgrFg; sgrBg = savedSgrBg;
        sgrReverse = savedSgrReverse; sgrBold = savedSgrBold;
        sgrUnderline = savedSgrUnderline;
        pendingWrap = false;
        ensureBuf(curRow);
    }
    private void reverseIndex() {
        if (curRow == scrollTop) {
            insertLine(scrollTop);
        } else {
            curRow = Math.max(0, curRow - 1);
        }
    }
    private void indexDown() {
        int bottom = scrollBottom >= 0 ? scrollBottom : pageTop() + rows - 1;
        if (curRow == bottom) {
            scrollRegionUp(scrollTop, bottom);
        } else {
            curRow = Math.min(bottom, curRow + 1);
        }
    }
    private void insertLine(int at) {
        int bottom = scrollBottom >= 0 ? scrollBottom : scrollTop + rows - 1;
        ensureBuf(bottom + 1);
        buffer.add(at, new Row());
        if (buffer.size() > bottom + 2) buffer.remove(bottom + 1);
    }
    /** Scroll [top, bottom] up by one line: discard the top line and insert a blank
     *  line at the bottom. Lines below the region are preserved (remove+clear would
     *  clobber the first line below the region, e.g. vim's status line). */
    private void scrollRegionUp(int top, int bottom) {
        if (top < 0 || bottom < top || top >= buffer.size()) return;
        buffer.remove(top);
        buffer.add(Math.min(bottom, buffer.size()), new Row());
    }
    private void deleteLines(int from, int count) {
        for (int i = 0; i < count && from < buffer.size(); i++) {
            buffer.remove(from);
        }
    }
    private int csi(String s, int p, int e) {
        int st = p;
        boolean isPrivate = false; // CSI ? prefix
        while (p < e) {
            char c = s.charAt(p);
            if ((c >= '0' && c <= '9') || c == ';' || c == ' ') p++;
            else if (c == '>' && p == st) p++; // scrolls
            else if (c == '?' && p == st) { isPrivate = true; st = ++p; }
            else if (c >= '@' && c <= '~') {
                String ps = s.substring(st, p);
                if (isPrivate) {
                    switch (c) {
                        case 'h': case 'l':
                            if (ps.equals("25")) { cursorShown = (c == 'h'); break; }
                            if (ps.equals("1")) { cursorKeysApp = (c == 'h'); break; }
                            if (ps.equals("7")) { decawm = (c == 'h'); break; } // DECAWM auto-wrap
                            // DECSET ?1049h/?1049l -- alternate screen buffer
                            if (ps.equals("1049")) {
                                if (c == 'h') {
                                    // Save current buffer and SGR state
                                    altSavedBuffer = new ArrayList<>(buffer.size());
                                    for (List<Cell> row : buffer) {
                                        Row savedRow = new Row();
                                        savedRow.wrapped = row instanceof Row && ((Row) row).wrapped;
                                        for (Cell cl : row) savedRow.add(cl.copy());
                                        altSavedBuffer.add(savedRow);
                                    }
                                    altSavedCurCol = curCol; altSavedCurRow = curRow;
                                    altSavedScrollOff = scrollOff;
                                    altSavedCols = cols; altSavedRows = rows;
                                    inAltScreen = true;
                                    buffer.clear(); buffer.add(new Row());
                                    curCol = curRow = scrollOff = 0;
                                    pendingWrap = false; // main-buffer wrap state must not leak into the alt screen
                                    wrapPendingEraseSuppress = false;
                                    // DECRC/SCORC inside the alt screen without a prior DECSC
                                    // goes to home, not to stale main-screen coordinates
                                    savedCurCol = 0; savedCurRow = 0;
                                    scrollLock = false; // alt screen has no user scrollback
                                    // Lock scroll region to the visible area in alt screen
                                    scrollTop = 0; scrollBottom = rows - 1;
                                } else {
                                    // Restore saved buffer
                                    inAltScreen = false;
                                    if (altSavedBuffer != null) {
                                        buffer.clear();
                                        buffer.addAll(altSavedBuffer);
                                        // Clamp the restored cursor: it may have been saved while
                                        // pointing beyond the buffer end (stale-geometry CUP)
                                        curCol = clamp(altSavedCurCol, 0, Math.max(0, cols - 1));
                                        curRow = clamp(altSavedCurRow, 0, Math.max(0, buffer.size() - 1));
                                        scrollOff = altSavedScrollOff;
                                        altSavedBuffer = null;
                                        pendingWrap = false; // alt-screen wrap state must not leak into the main buffer
                                        wrapPendingEraseSuppress = false;
                                        // Re-lay the restored content if the width changed in alt
                                        if (cols != altSavedCols) reflowBuffer(cols);
                                        // Always anchor the cursor to the end of the output and pin
                                        // the viewport to the bottom. The position saved before the
                                        // switch may have been moved by the app's stale-geometry
                                        // cleanup sequences (nmon emits a pre-resize CUP between
                                        // ?1049l and ?1049h on SIGWINCH), which would otherwise
                                        // strand the shell prompt mid-buffer with stale rows below.
                                        curRow = Math.max(0, buffer.size() - 1);
                                        curCol = 0;
                                        scrollLock = false;
                                        scrollOff = Math.max(0, buffer.size() - rows);
                                        // Re-anchor the DECSC/DECRC slots to the restored cursor:
                                        // a position saved before the screen switch is meaningless
                                        // after the buffer has been replaced (and possibly reflowed),
                                        // and would otherwise yank the cursor back to a stale row
                                        savedCurCol = curCol;
                                        savedCurRow = curRow;
                                    }
                                    scrollTop = 0; scrollBottom = -1;
                                }
                                break;
                            }
                            break;
                        case 'r': // DECSTBM ???handled below in standard CSI
                            if (inAltScreen && ps.isEmpty()) {
                                // ?r without params: restore default scroll region
                                scrollTop = 0; scrollBottom = rows - 1;
                            }
                            break;
                        case 's': break; // DECSC
                        case 'u': break; // DECRC
                        case 'J':
                            if (ps.equals("2")) { clearBuffer(); scrollOff = 0; }
                            break;
                    }
                    return p;
                }
                switch (c) {
                    case 'K':
                        if (ps.isEmpty() || ps.equals("0")) eraseEOL();
                        else if (ps.equals("1")) eraseBOL();
                        else if (ps.equals("2")) eraseLine();
                        break;
                    case 'J':
                        if (ps.isEmpty() || ps.equals("0")) eraseEOD();
                        else if (ps.equals("1")) eraseDOS();
                        else if (ps.equals("2")) {
                            if (inAltScreen) {
                                clearBuffer();
                                scrollOff = 0;
                            } else {
                                // xterm semantics: ED 2 erases the visible page only,
                                // scrollback history above the page is preserved
                                eraseVisiblePage();
                            }
                            if (!inAltScreen) {
                                scrollTop = 0; scrollBottom = -1; originMode = false;
                            }
                        }
                        break;
                    case 'm': sgr(ps); break;
                    case 'A': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; curRow = Math.max(originMode ? scrollTop : 0, curRow - n); } break;
                    case 'B': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; int maxR = buffer.isEmpty() ? 0 : buffer.size() - 1; curRow = Math.min(maxR, curRow + n); } break;
                    case 'C': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; curCol = Math.min(cols - 1, curCol + n); } break;
                    case 'D': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; curCol = Math.max(0, curCol - n); } break;
                    case 'E': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; curCol = 0; int maxR = buffer.isEmpty() ? 0 : buffer.size() - 1; curRow = Math.min(maxR, curRow + n); } break;
                    case 'F': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; curCol = 0; curRow = Math.max(originMode ? scrollTop : 0, curRow - n); } break;
                    case 'G': case '`': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; curCol = Math.max(0, n - 1); } break;
                    case 'd': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); pendingWrap = false; curRow = clamp((inAltScreen ? 0 : pageTop()) + n - 1, 0, (inAltScreen ? 0 : pageTop()) + rows - 1); break; }
                    case 'H': case 'f': {
                        String[] xy = ps.split(";");
                        int row = xy.length > 0 && !xy[0].isEmpty() ? Integer.parseInt(xy[0]) - 1 : 0;
                        int col = xy.length > 1 && !xy[1].isEmpty() ? Integer.parseInt(xy[1]) - 1 : 0;
                        boolean home = (row == 0 && col == 0);
                        if (originMode && row >= 0) row += scrollTop;
                        else if (!inAltScreen) row += pageTop(); // CUP addresses the visible page, not the scrollback (xterm semantics)
                        // Clamp into the visible screen: after SIGWINCH an app may still
                        // address rows of the previous geometry (e.g. nmon's exit CUP),
                        // which would otherwise land the cursor beyond the buffer
                        curRow = clamp(row, 0, (inAltScreen ? 0 : pageTop()) + rows - 1);
                        curCol = clamp(col, 0, cols - 1);
                        pendingWrap = false;
                        // On home in normal screen, snap the viewport back to the page bottom
                        if (home && !inAltScreen) { scrollOff = Math.max(0, buffer.size() - rows); scrollLock = false; }
                    } break;
                    case 'L': { // insert lines at current cursor row within scroll region
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        int insAt = Math.max(scrollTop, curRow);
                        for (int i = 0; i < n; i++) {
                            ensureBuf(bottom + 1);
                            buffer.add(insAt, new Row());
                            if (buffer.size() > bottom + 2) buffer.remove(bottom + 1);
                        }
                    } break;
                    case 'M': { // delete lines from current cursor row within scroll region
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        int delFrom = Math.max(scrollTop, curRow);
                        for (int i = 0; i < n && delFrom < buffer.size() && delFrom <= bottom; i++) {
                            buffer.remove(delFrom);
                        }
                        for (int i = buffer.size(); i <= bottom; i++) buffer.add(new Row());
                    } break;
                    case 'P': { // delete characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        List<Cell> ln = ensureBuf(curRow);
                        if (curCol < ln.size()) {
                            for (int i = 0; i < n && curCol < ln.size(); i++) ln.remove(curCol);
                        }
                    } break;
                    case '@': { // insert characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        List<Cell> ln = ensureBuf(curRow);
                        for (int i2 = 0; i2 < n; i2++) ln.add(curCol, new Cell());
                    } break;
                    case 'X': { // erase characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        List<Cell> ln = ensureBuf(curRow);
                        int end = Math.min(ln.size(), curCol + n);
                        while (ln.size() <= end) ln.add(new Cell());
                        for (int i2 = curCol; i2 < end; i2++) {
                            if (i2 < ln.size()) ln.get(i2).reset();
                        }
                    } break;
                    case 'Z': { // cursor backward tab (CBT)
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        pendingWrap = false;
                        for (int i = 0; i < n; i++) curCol = Math.max(0, ((curCol - 1) / 8) * 8);
                    } break;
                    case 'b': { // REP: repeat the preceding graphic character n times
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int prevCol = pendingWrap ? cols - 1 : curCol - 1;
                        char pc = (prevCol >= 0 && curRow < buffer.size() && prevCol < buffer.get(curRow).size())
                                ? buffer.get(curRow).get(prevCol).ch : ' ';
                        for (int i = 0; i < n; i++) put(pc);
                    } break;
                    case 'S': { // scroll up (SU): region content moves up, blank lines appear at the bottom
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = scrollTop, bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i3 = 0; i3 < n; i3++) { if (bottom >= top && top < buffer.size()) { buffer.remove(top); buffer.add(Math.min(bottom, buffer.size()), new Row()); } }
                    } break;
                    case 'T': { // scroll down (SD): region content moves down, blank lines appear at the top
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = scrollTop, bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i3 = 0; i3 < n; i3++) { if (bottom >= top && bottom < buffer.size()) { buffer.remove(bottom); buffer.add(top, new Row()); } }
                    } break;
                    case 'r': { // DECSTBM ???set scroll region (page-relative, like xterm)
                        String[] sr_ = ps.split(";");
                        int base = inAltScreen ? 0 : pageTop();
                        int regionMax = base + rows - 1; // clamp the region to the current screen
                        scrollTop = Math.min(base + (sr_.length > 0 && !sr_[0].isEmpty() ? Math.max(0, Integer.parseInt(sr_[0]) - 1) : 0), regionMax);
                        scrollBottom = sr_.length > 1 && !sr_[1].isEmpty() ? Math.min(base + Integer.parseInt(sr_[1]) - 1, regionMax) : -1;
                        curRow = scrollTop; curCol = 0;
                        pendingWrap = false;
                    } break;
                    case 'h': case 'l':
                        if (ps.equals("6")) originMode = (c == 'h'); // DECOM
                        break;
                    case 's': saveCursor(); break;
                    case 'u': restoreCursor(); break;
                    case 'n': break; // DSR ???ignore
                    case 'q': break; // DECSCUSR ???ignore cursor style
                }
                return p;
            } else {
                // Unrecognized char in CSI sequence, skip silently
                p++;
                // backtracking (which could feed garbage to put())
            }
        }
        return -1;
    }
    private int osc(String s, int p, int e) {
        while (p < e) {
            char c = s.charAt(p);
            if (c == 0x07) return p;
            if (c == 0x1B && p + 1 < e && s.charAt(p + 1) == '\\') return p + 1;
            p++;
        }
        return -1;
    }
    private void clearBuffer() {
        buffer.clear(); buffer.add(new Row());
        curCol = curRow = 0;
        pendingWrap = false;
    }
    /** Erase the visible page (the last {@code rows} lines), preserving scrollback history. */
    private void eraseVisiblePage() {
        for (int r = pageTop(); r < buffer.size(); r++) buffer.get(r).clear();
        pendingWrap = false;
    }
    private void eraseEOL() {
        if (wrapPendingEraseSuppress && curCol == 0) { wrapPendingEraseSuppress = false; return; }
        if (curRow >= buffer.size()) return; // nothing to erase beyond the buffer (never grow it here)
        List<Cell> ln = buffer.get(curRow);
        for (int i = curCol; i < ln.size(); i++) ln.get(i).reset();
    }
    private void eraseBOL() {
        if (curRow >= buffer.size()) return; // nothing to erase beyond the buffer (never grow it here)
        List<Cell> ln = buffer.get(curRow);
        int end = Math.min(curCol, ln.size() - 1);
        for (int i = 0; i <= end && !ln.isEmpty(); i++) ln.remove(0);
    }
    private void eraseLine() {
        if (curRow < buffer.size()) buffer.get(curRow).clear();
    }
    private void eraseEOD() {
        eraseEOL();
        for (int r = curRow + 1; r < buffer.size(); r++) buffer.get(r).clear();
    }
    private void eraseDOS() {
        for (int r = 0; r < curRow && r < buffer.size(); r++) buffer.get(r).clear();
        eraseBOL();
    }
    private void sgr(String ps) {
        if (ps.isEmpty()) { sgrFg = 37; sgrBg = 40; sgrReverse = sgrBold = sgrUnderline = false; sgrExtFg = -1; sgrExtBg = -1; return; }
        String[] parts = ps.split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.equals("0")) {
                sgrFg = 37; sgrBg = 40; sgrReverse = sgrBold = sgrUnderline = false; sgrExtFg = -1; sgrExtBg = -1;
                continue;
            }
            int n;
            try { n = Integer.parseInt(p); } catch (NumberFormatException x) { continue; }
            if (n == 38 && i + 2 < parts.length && parts[i+1].equals("5")) {
                try { sgrExtFg = Integer.parseInt(parts[i+2]); }
                catch (NumberFormatException x) {}
                i += 2;
                continue;
            }
            if (n == 48 && i + 2 < parts.length && parts[i+1].equals("5")) {
                try { sgrExtBg = Integer.parseInt(parts[i+2]); } catch (NumberFormatException x) {}
                i += 2;
                continue;
            }
            switch (n) {
                case 1: sgrBold = true; break;
                case 2: sgrBold = false; break;
                case 3: break;
                case 4: sgrUnderline = true; break;
                case 5: case 6: break;
                case 7: sgrReverse = true; break;
                case 22: sgrBold = false; break;
                case 23: break;
                case 24: sgrUnderline = false; break;
                case 25: break;
                case 27: sgrReverse = false; break;
                case 30: sgrFg=30; break; case 31: sgrFg=31; break; case 32: sgrFg=32; break; case 33: sgrFg=33; break;
                case 34: sgrFg=34; break; case 35: sgrFg=35; break; case 36: sgrFg=36; break; case 37: case 39: sgrFg=37; break;
                case 90: sgrFg=90; break; case 91: sgrFg=91; break; case 92: sgrFg=92; break; case 93: sgrFg=93; break;
                case 94: sgrFg=94; break; case 95: sgrFg=95; break; case 96: sgrFg=96; break; case 97: sgrFg=97; break;
                case 40: sgrBg=40; break; case 41: sgrBg=41; break; case 42: sgrBg=42; break; case 43: sgrBg=43; break;
                case 44: sgrBg=44; break; case 45: sgrBg=45; break; case 46: sgrBg=46; break; case 47: case 49: sgrBg=40; break;
                case 100: sgrBg=100; break; case 101: sgrBg=101; break; case 102: sgrBg=102; break; case 103: sgrBg=103; break;
                case 104: sgrBg=104; break; case 105: sgrBg=105; break; case 106: sgrBg=106; break; case 107: sgrBg=107; break;
            }
        }
    }
    // ---- CJK fullwidth detection ----
    /**
     * Returns true if the character is a CJK fullwidth character that
     * occupies 2 columns in a terminal.
     */
    private static boolean isFullwidth(char ch) {
        if (ch >= 0x1100 && ch <= 0x115F) return true; // Hangul Jamo
        if (ch >= 0x2E80 && ch <= 0xA4CF) {
            if (ch >= 0x2E80 && ch <= 0x303F) return true; // CJK Radicals, Symbols
            if (ch >= 0x3040 && ch <= 0x30FF) return true; // Hiragana, Katakana
            if (ch >= 0x3100 && ch <= 0xA4CF) return true; // CJK Ideographs, Yi, etc.
        }
        if (ch >= 0xA960 && ch <= 0xA97F) return true; // Hangul Jamo Extended-A
        if (ch >= 0xAC00 && ch <= 0xD7AF) return true; // Hangul Syllables
        if (ch >= 0xF900 && ch <= 0xFAFF) return true; // CJK Compatibility Ideographs
        if (ch >= 0xFE10 && ch <= 0xFE1F) return true; // Vertical Forms
        if (ch >= 0xFE30 && ch <= 0xFE6F) return true; // CJK Compatibility Forms
        if (ch >= 0xFF01 && ch <= 0xFF60) return true; // Fullwidth ASCII
        if (ch >= 0xFFE0 && ch <= 0xFFE6) return true; // Fullwidth Symbols
        return false;
    }
    // ---- Rendering ----
    private static Color c(int code) {
        switch (code) {
            case 30:case 40: return Color.BLACK;
            case 31:case 41: return Color.rgb(205,50,50);
            case 32:case 42: return Color.rgb(0,205,0);
            case 33:case 43: return Color.rgb(205,205,0);
            case 34:case 44: return Color.rgb(50,100,205);
            case 35:case 45: return Color.rgb(205,0,205);
            case 36:case 46: return Color.rgb(0,205,205);
            case 37:case 47: return Color.rgb(230,230,230);
            case 90:case 100: return Color.GRAY;
            case 91:case 101: return Color.rgb(255,80,80);
            case 92:case 102: return Color.rgb(80,255,80);
            case 93:case 103: return Color.rgb(255,255,80);
            case 94:case 104: return Color.rgb(80,120,255);
            case 95:case 105: return Color.rgb(255,80,255);
            case 96:case 106: return Color.rgb(80,255,255);
            case 97:case 107: return Color.WHITE;
            default: return Color.WHITE;
        }
    }
    private static Color xtermColor(int idx) {
        if (idx < 16) {
            int[] std = {0, 128, 0, 0, 0, 128, 0, 192, 128, 255, 80, 255, 80, 255, 255, 255};
            int[] stdG = {0, 0, 128, 0, 128, 0, 128, 128, 128, 128, 255, 128, 255, 0, 0, 192};
            int[] stdB = {0, 0, 0, 128, 128, 128, 128, 0, 128, 128, 128, 255, 255, 255, 255, 255};
            return Color.rgb(std[idx], stdG[idx], stdB[idx]);
        }
        if (idx <= 231) {
            int r = ((idx - 16) / 36) * 40 + 55;
            int g = ((idx - 16) / 6 % 6) * 40 + 55;
            int b = ((idx - 16) % 6) * 40 + 55;
            return Color.rgb(r, g, b);
        }
        int gray = (idx - 232) * 10 + 8;
        return Color.rgb(gray, gray, gray);
    }
    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setFont(FONT);
        int sr = scrollOff, er = Math.min(buffer.size(), sr + rows);
        boolean hs = selStartRow >= 0 && (selStartRow != selEndRow || selStartCol != selEndCol);
        int mr = hs ? Math.min(selStartRow, selEndRow) : -1;
        int Mr = hs ? Math.max(selStartRow, selEndRow) : -1;
        int sl = 0, sr2 = 0;
        if (hs) {
            if (selStartRow == selEndRow) {
                sl = Math.min(selStartCol, selEndCol);
                sr2 = Math.max(selStartCol, selEndCol);
            } else {
                sl = mr == selStartRow ? selStartCol : selEndCol;
                sr2 = Mr == selEndRow ? selEndCol : selStartCol;
                if (sl > sr2) { int t = sl; sl = sr2; sr2 = t; }
            }
        }
        for (int r = sr; r < er && r < buffer.size(); r++) {
            int sy = r - sr;
            double y = sy * LINE_H;
            List<Cell> rowCells = buffer.get(r);
            for (int col = 0; col < rowCells.size(); col++) {
                Cell cell = rowCells.get(col);
                if (cell.ch == '\0') continue;
                boolean isFw = isFullwidth(cell.ch);
                double cellW = isFw ? CHAR_W * 2 : CHAR_W;
                boolean in = hs && ((r > mr && r < Mr)
                        || (r == mr && r == Mr && col >= sl && col < sr2)
                        || (r == mr && r != Mr && col >= sl)
                        || (r == Mr && r != mr && col < sr2));
                double x = col * CHAR_W;
                Color fg = cell.extFg >= 0 ? xtermColor(cell.extFg) : c(cell.fg);

                Color bg = cell.extBg >= 0 ? xtermColor(cell.extBg) : c(cell.bg);
                if (cell.reverse) { Color t = fg; fg = bg; bg = t; }
                if (in) {
                    g.setFill(Color.rgb(200,200,200));
                    g.fillRect(x, y, cellW, LINE_H);
                    g.setFill(Color.BLACK);
                } else {
                    g.setFill(bg);
                    g.fillRect(x, y, cellW, LINE_H);
                    g.setFill(fg);
                }
                if (cell.bold) {
                    g.fillText(String.valueOf(cell.ch), x, y + LINE_H - 3);
                    g.fillText(String.valueOf(cell.ch), x + 0.5, y + LINE_H - 3);
                } else {
                    g.fillText(String.valueOf(cell.ch), x, y + LINE_H - 3);
                }
                if (cell.underline) {
                    g.setStroke(fg);
                    g.setLineWidth(1);
                    g.strokeLine(x, y + LINE_H - 2, x + cellW, y + LINE_H - 2);
                }
            }
        }
        if (cursorShown && cursorVis && focused) {
            int vr = curRow - scrollOff;
            if (vr >= 0 && vr < rows) {
                double cx = curCol * CHAR_W, cy = vr * LINE_H + LINE_H * 0.2;
                double cursorHeight = LINE_H * 0.8;
                char atCursor = (curRow < buffer.size() && curCol < buffer.get(curRow).size())
                        ? buffer.get(curRow).get(curCol).ch : ' ';
                double cursorW = isFullwidth(atCursor) ? CHAR_W * 2 : CHAR_W;
                g.setFill(Color.rgb(200,200,200));
                g.fillRect(cx, cy, cursorW, cursorHeight);
                if (atCursor != '\0' && atCursor != ' ') {
                    g.setFill(Color.BLACK);
                    // Render text at the normal text baseline for this row
                    double textBaseline = vr * LINE_H + LINE_H - 3;
                    g.fillText(String.valueOf(atCursor), cx, textBaseline);
                }
            }
        }
    }
    // ---- Input ----
    private void setupCanvasInput() {
        canvas.setOnMousePressed(e -> {
            canvas.requestFocus();
            if (e.getButton() == MouseButton.PRIMARY) {
                selecting = true;
                selStartCol = selEndCol = (int)(e.getX() / CHAR_W);
                selStartRow = selEndRow = clamp(scrollOff + (int)(e.getY() / LINE_H), 0, Math.max(0, buffer.size() - 1));
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (!selecting) return;
            double ey = e.getY();
            double ch = canvas.getHeight();
            int col = clamp((int)(e.getX() / CHAR_W), 0, cols - 1);
            int maxOff = Math.max(0, buffer.size() - rows);
            int row;
            if (ey < 0) {
                // Dragged above canvas: jump toward older lines, then auto-scroll
                int lines = (int)(-ey / LINE_H) + 1;
                scrollOff = clamp(scrollOff - lines, 0, maxOff);
                row = scrollOff;
                startAutoScroll(-1);
            } else if (ey > ch) {
                // Dragged below canvas: jump toward newer lines, then auto-scroll
                int lines = (int)((ey - ch) / LINE_H) + 1;
                scrollOff = clamp(scrollOff + lines, 0, maxOff);
                row = scrollOff + rows - 1;
                startAutoScroll(1);
            } else {
                stopAutoScroll();
                row = scrollOff + (int)(ey / LINE_H);
            }
            selEndCol = col;
            selEndRow = clamp(row, 0, Math.max(0, buffer.size() - 1));
            draw();
            fireScrollChanged();
        });
        canvas.setOnMouseReleased(e -> {
            canvas.requestFocus();
            selecting = false;
            stopAutoScroll();
            if (e.getButton() == MouseButton.PRIMARY) {
                selEndCol = clamp((int)(e.getX() / CHAR_W), 0, cols - 1);
                selEndRow = clamp(scrollOff + (int)(e.getY() / LINE_H), 0, Math.max(0, buffer.size() - 1));
                // Copy selection to clipboard on mouse release (drag-select)
                String sel = selectedText();
                if (!sel.isEmpty()) {
                    Clipboard.getSystemClipboard().setContent(
                            java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, sel));
                } else {
                    selStartCol = selEndCol = selStartRow = selEndRow = -1;
                }
            }
            draw();
        });
        canvas.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                int row = clamp(scrollOff + (int)(e.getY() / LINE_H), 0, Math.max(0, buffer.size() - 1));
                int col = clamp((int)(e.getX() / CHAR_W), 0, cols - 1);
                String ln = line(row);
                int start = col, end = col;
                int ll = ln.length();
                while (start > 0 && start <= ll && isWordChar(ln.charAt(start - 1))) start--;
                while (end < ll && isWordChar(ln.charAt(end))) end++;
                selStartRow = selEndRow = row;
                selStartCol = start;
                selEndCol = end;
                // Copy double-clicked word to clipboard (silent)
                String word = selectedText();
                if (!word.isEmpty()) {
                    Clipboard.getSystemClipboard().setContent(
                            java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, word));
                }
                draw();
            }
        });
        canvas.setOnContextMenuRequested(e -> {
            // Right-click: paste from clipboard (\n -> \r for terminal)
            if (shellChannel == null || !shellChannel.isOpen()) { e.consume(); return; }
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasString()) {
                String text = cb.getString();
                if (text != null && !text.isEmpty()) {
                    // Replace \n with \r as terminals expect \r for Enter
                    text = text.replace("\n", "\r");
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
                if (isZoomInKey(e)) { adjustFontSize(FONT_SIZE_STEP); e.consume(); return; }
                if (isZoomOutKey(e)) { adjustFontSize(-FONT_SIZE_STEP); e.consume(); return; }
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
            if (inAltScreen) { e.consume(); return; } // alt screen has no scrollback to scroll
            int dir = -(int)Math.signum(e.getDeltaY());
            if (dir != 0) {
                int maxOff = Math.max(0, buffer.size() - rows);
                scrollOff = clamp(scrollOff + dir, 0, maxOff);
                scrollLock = scrollOff < maxOff; // scrolling back to the bottom releases the lock
                draw();
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
                int maxOff = Math.max(0, buffer.size() - rows);
                scrollOff = clamp(scrollOff + autoScrollDirection, 0, maxOff);
                // Keep selection endpoint pinned to the edge in scroll direction
                if (autoScrollDirection < 0) {
                    selEndRow = scrollOff;
                } else {
                    selEndRow = clamp(scrollOff + rows - 1, 0, Math.max(0, buffer.size() - 1));
                }
                draw();
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
                || isFullwidth(c);
    }
    private byte[] key(KeyEvent e) {
        KeyCode k = e.getCode();
        boolean ct = e.isControlDown() && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown();
        if (ct) {
            if (k == KeyCode.C) {
                String s = selectedText();
                if (!s.isEmpty()) {
                    Clipboard.getSystemClipboard().setContent(java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, s));
                    return null;
                }
            }
            if (k == KeyCode.V) {
                Clipboard cb = Clipboard.getSystemClipboard();
                if (cb.hasString()) {
                    String t = cb.getString();
                    if (t != null && !t.isEmpty()) return t.getBytes(StandardCharsets.UTF_8);
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
            case UP:return cursorKeysApp ? "OA".getBytes(StandardCharsets.UTF_8) : "[A".getBytes(StandardCharsets.UTF_8);
            case DOWN:return cursorKeysApp ? "OB".getBytes(StandardCharsets.UTF_8) : "[B".getBytes(StandardCharsets.UTF_8);
            case RIGHT:return cursorKeysApp ? "OC".getBytes(StandardCharsets.UTF_8) : "[C".getBytes(StandardCharsets.UTF_8);
            case LEFT:return cursorKeysApp ? "OD".getBytes(StandardCharsets.UTF_8) : "[D".getBytes(StandardCharsets.UTF_8);
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
    private String selectedText() {
        if (selStartRow < 0 || selEndRow < 0) return "";
        int sr = Math.min(selStartRow, selEndRow), er = Math.max(selStartRow, selEndRow);
        int sc = selStartCol, ec = selEndCol;
        if (selStartRow > selEndRow || (selStartRow == selEndRow && selStartCol > selEndCol)) { sc = selEndCol; ec = selStartCol; }
        if (sc > ec) { int t = sc; sc = ec; ec = t; }
        if (sr == er) {
            String l = stripContinuationChars(line(sr));
            sc = Math.min(sc, l.length()); ec = Math.min(ec, l.length());
            return sc < ec ? l.substring(sc, ec) : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int r = sr; r <= er && r < buffer.size(); r++) {
            String l = stripContinuationChars(line(r));
            int a = r == sr ? sc : 0, b2 = r == er ? Math.min(ec, l.length()) : l.length();
            if (a < b2) sb.append(l, a, b2);
            if (r < er) sb.append('\n');
        }
        return sb.toString();
    }
    /** Strip continuation cells (\0) left by fullwidth characters. */
    
    /** Write to terminal without trailing newline (for in-place progress). */
    private void progressStatus(String s) {
        for (char c : s.toCharArray()) {
            if (c == '\n') { pendingWrap = false; nl(); }
            else if (c == '\r') { curCol = 0; pendingWrap = false; }
            else put(c);
        }
        requestDraw();
    }

    // ==================== ZModem (sz/rz) file transfer ====================
    // Beacon hex header: '*','*',0x18,'B','0', then the type digit —
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
        if (logging && logWriter != null) {
            try { logWriter.write(stripAnsi(new String(data, off, len, terminalCharset()))); logWriter.flush(); } catch (Exception ignored) {}
        }
        String out = new String(data, off, len, terminalCharset());
        Platform.runLater(() -> write(out));
    }

    /** Run a full ZModem session on the reader thread; binary bytes bypass the terminal renderer.
     * @param dir 1=upload (remote rz), 2=download (remote sz) */
    private void runZmodemSession(InputStream in, byte[] prefix, int dir) {
        zmodemActive = true;
        transferCancelFlag = false;
        progressStartMs = 0;
        lastProgressMs = 0;
        ZModemSession zs = new ZModemSession(in, shellChannel.getInvertedIn(), prefix, zmodemHandler);
        try {
            if (dir == 2) zs.receive(); else zs.send();
            Platform.runLater(() -> statusGreen("\r\nZModem " + (dir == 2 ? "download" : "upload") + " finished\r\n"));
        } catch (Exception ex) {
            log.warn("ZModem session ended: {}", ex.toString());
            String m = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            Platform.runLater(() -> statusRed("\r\nZModem: " + m + "\r\n"));
        } finally {
            zmodemActive = false;
        }
        // give back bytes the engine buffered past the session end (e.g. the shell prompt)
        byte[] rest = zs.drainPending();
        if (rest.length > 0) feedTerminal(rest, 0, rest.length);
    }

    private File lastTransferDir;
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
                    File f = fc.showSaveDialog(canvas.getScene().getWindow());
                    log.info("ZModem: save dialog closed, result={}", f);
                    if (f != null) {
                        lastTransferDir = f.getParentFile();
                        canvas.requestFocus();
                    }
                    ref.set(f);
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
                    List<File> files = fc.showOpenMultipleDialog(canvas.getScene().getWindow());
                    log.info("ZModem: upload dialog closed, files={}", files == null ? 0 : files.size());
                    if (files != null && !files.isEmpty()) {
                        lastTransferDir = files.get(0).getParentFile();
                        canvas.requestFocus();
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
            progressStatus(msg);
        }

        @Override
        public void onMessage(String message) {
            Platform.runLater(() -> status(message));
        }

        @Override
        public boolean isCancelled() {
            return transferCancelFlag;
        }
    };
private static String stripContinuationChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\0') sb.append(c);
        }
        return sb.toString();
    }
    /** Make control chars visible for logging. */
    /** Map DEC Special Graphics (line drawing) characters to Unicode. */
    private static char mapDecSpecial(char c) {
        switch (c) {
            case 'j': return '\u2518'; case 'k': return '\u2510'; case 'l': return '\u250C';
            case 'm': return '\u2514'; case 'n': return '\u253C'; case 'q': return '\u2500';
            case 't': return '\u251C'; case 'u': return '\u2524'; case 'v': return '\u2534';
            case 'w': return '\u252C'; case 'x': return '\u2502';
            default: return c;
        }
    }
    private static String escapeForLog(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x1B) sb.append("<ESC>");
            else if (c == '\r') sb.append("<CR>");
            else if (c == '\n') sb.append("<LF>");
            else if (c == '\b') sb.append("<BS>");
            else if (c == 0x7F) sb.append("<DEL>");
            else if (c < 0x20) sb.append(String.format("<0x%02X>", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Strip ANSI escape sequences from a string, returning clean visible text. */
    private static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x1B) {
                // ESC - skip the escape sequence
                i++;
                if (i >= s.length()) break;
                c = s.charAt(i);
                if (c == '[') {
                    // CSI sequence: ESC [ params... finalChar
                    i++;
                    while (i < s.length()) {
                        c = s.charAt(i);
                        if (c >= '@' && c <= '~') break; // final char
                        i++;
                    }
                } else if (c == ']') {
                    // OSC sequence: ESC ] ... terminated by BEL or ST
                    i++;
                    while (i < s.length()) {
                        c = s.charAt(i);
                        if (c == 0x07) break; // BEL
                        if (c == 0x1B && i + 1 < s.length() && s.charAt(i + 1) == '\\') break; // ST
                        i++;
                    }
                } else if (c == '(' || c == ')') {
                    // Charset selection: ESC ( <char>
                    if (i + 1 < s.length()) i++; // skip the charset char
                } else if (c == 'O') {
                    // SS3: ESC O <char>
                    if (i + 1 < s.length()) i++; // skip the key code
                }
                // else: single-char ESC command (7 8 M D E H c) - already consumed by i++
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    /** Dump the current buffer content to log. */
    private void dumpBuffer() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("cur=(%d,%d) wrap=%b scrollOff=%d rows=%d bufSize=%d\n",
                curCol, curRow, pendingWrap, scrollOff, rows, buffer.size()));
        int showRows = Math.min(buffer.size(), rows + 2);
        for (int r = Math.max(0, scrollOff - 1); r < showRows; r++) {
            if (r >= buffer.size()) break;
            String l = line(r);
            String outline = l.length() > 80 ? l.substring(0, 80) + "..." : l;
            sb.append(String.format("  [%d] cells=%d |%s|\n", r,
                    r < buffer.size() ? buffer.get(r).size() : 0,
                    outline.replace(' ', '\u00B7')));
            if (r - scrollOff + 1 > rows + 2) break;
        }
        //log.info(sb.toString());
    }
}

