package com.dbboys.ui.controller;
import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.JschUtil;
import com.dbboys.model.SshConnect;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
/**
 * SSH terminal tab controller with embedded Canvas-based terminal emulator.
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);
    private static final Font FONT = Font.font("Consolas", 13);
    static final double CHAR_W;
    static final double LINE_H;
    static {
        Text m = new Text("W");
        m.setFont(FONT);
        CHAR_W = m.getLayoutBounds().getWidth();
        LINE_H = 13 * 1.4;
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
    @FXML public StackPane terminalPane;
    @FXML public Button connectButton;
    @FXML public Button disconnectButton;
    @FXML public Label connectionLabel;
    @FXML public VBox sshTab;
    private SshConnect sshConnect;
    private Session session;
    private ChannelShell shellChannel;
    private final StringProperty connectStatus = new SimpleStringProperty();
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
    private int scrollOff, maxScroll = 5000;
    private Runnable onScrollChanged;
    private String pendingEsc;
    private boolean scrollLock;
    private int sgrExtFg = -1, sgrExtBg = -1; // 256-color extended colors
    private List<List<Cell>> altSavedBuffer;
    private int altSavedCurCol, altSavedCurRow, altSavedScrollOff;
    private boolean inAltScreen; // whether alternate screen buffer (?1049h) is active
    // Deferred draw: coalesce rapid write() calls into a single draw,
    // preventing the blink timer from rendering partially-updated screens.
    private volatile boolean drawPending;
    private Timeline autoScrollTimeline;
    private int autoScrollDirection = 0;
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
        connectButton.setGraphic(IconFactory.group(IconPaths.SSH_CONNECT, 0.65, Color.GREEN));
        disconnectButton.setGraphic(IconFactory.group(IconPaths.SSH_DISCONNECT, 0.65, Color.RED));
        connectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.connect", "Connect")));
        disconnectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.disconnect", "Disconnect")));
        disconnectButton.setDisable(true);
        connectButton.setOnAction(e -> doConnect());
        disconnectButton.setOnAction(e -> doDisconnect());
        connectStatus.addListener((obs, o, n) -> connectionLabel.setText(n));
        connectStatus.set(I18n.t("ssh.tab.disconnected", "Disconnected"));
        // Canvas terminal
        buffer.add(new ArrayList<>());
        canvas = new Canvas(cols * CHAR_W, rows * LINE_H);
        canvas.setFocusTraversable(true);
        canvas.focusedProperty().addListener((o, ov, n) -> { focused = n; draw(); });
        setupCanvasInput();
        terminalPane.getChildren().add(canvas);
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
                    scrollLock = true;
                    draw();
                }
            }
        });
        onScrollChanged = () -> {
            Platform.runLater(() -> {
                int max = Math.max(0, buffer.size() - rows);
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
                    cols = newCols;
                    canvas.setWidth(cols * CHAR_W);
                    draw();
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
                    // Keep scrollOff valid after resize
                    if (scrollOff > Math.max(0, buffer.size() - rows)) {
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
        connectStatus.set(sc.getUsername() + "@" + sc.getHost() + ":" + sc.getPort());
        doConnect();
    }
    private void doConnect() {
        if (sshConnect == null) return;
        connectButton.setDisable(true);
        connectStatus.set(I18n.t("ssh.tab.connecting", "Connecting..."));
        status("Connecting to " + sshConnect.getUsername() + "@"
                + sshConnect.getHost() + ":" + sshConnect.getPort() + "...\r\n");
        AppExecutor.runAsync(() -> {
            try {
                session = JschUtil.getSshSession(sshConnect);
                shellChannel = (ChannelShell) session.openChannel("shell");
                shellChannel.setPty(true);
                shellChannel.setPtyType("xterm-256color");
                shellChannel.connect();
                start();
                Platform.runLater(() -> {
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    canvas.requestFocus();
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connected", "Connected") + "]");
                    status(I18n.t("ssh.tab.connected", "Connected") + "\r\n");
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
                    status("[ERROR] " + ex.getMessage() + "\r\n");
                });
            }
        });
    }
    private void doDisconnect() {
        stop();
        JschUtil.disconnectSession(session);
        session = null;
        shellChannel = null;
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
        if (sshConnect != null) {
            connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                    + ":" + sshConnect.getPort() + " ["
                    + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        }
    }
    private void updatePtySize() {
        if (shellChannel != null && shellChannel.isConnected()) {
            shellChannel.setPtySize(cols, rows, (int) canvas.getWidth(), (int) canvas.getHeight());
        }
    }
    public void closeSession() { doDisconnect(); }
    // ==================== Terminal engine ====================
    private void start() {
        if (shellChannel == null || !shellChannel.isConnected()) return;
        readThread = new Thread(() -> {
            try {
                InputStream in = shellChannel.getInputStream();
                byte[] buf = new byte[8192];
                int len;
                while (shellChannel.isConnected() && (len = in.read(buf, 0, buf.length)) != -1) {
                    String out = new String(buf, 0, len, StandardCharsets.UTF_8);
                    Platform.runLater(() -> write(out));
                }
            } catch (Exception e) { /* closed */ }
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
        if (shellChannel != null && shellChannel.isConnected()) {
            try { shellChannel.getOutputStream().close(); } catch (Exception ignored) {}
            try { shellChannel.getInputStream().close(); } catch (Exception ignored) {}
            shellChannel.disconnect();
        }
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
            if (c == '\n') {
                if (pendingWrap) pendingWrap = false; else nl();
            }
            else if (c == '\r') curCol = 0;
            else put(c);
        }
        if (!s.endsWith("\n") && !s.endsWith("\r\n")) nl();
        requestDraw();
    }
    private void write(String raw) {
        // prepend any incomplete escape sequence from the previous chunk
        if (pendingEsc != null) {
            raw = pendingEsc + raw;
            pendingEsc = null;
        }
        // Log large chunks (top/nmon output) for debugging
        boolean isTopData = raw.length() > 200;
        if (isTopData) {
            log.info("=== SSH RAW ({} chars) wrap={} cur=({},{}) ===",
                    raw.length(), pendingWrap, curCol, curRow);
            log.info("  {}", escapeForLog(raw.substring(0, Math.min(2000, raw.length()))));
        }
        for (int i = 0, n = raw.length(); i < n; i++) {
            char c = raw.charAt(i);
            if (c == 0x1B) {
                int ni = esc(raw, i + 1, n);
                if (ni < 0) { pendingEsc = raw.substring(i); return; }
                i = ni;
            }
            else if (c == '\b') { if (curCol > 0) curCol--; }
            else if (c == '\r') curCol = 0;
            else if (c == '\n') {
                if (pendingWrap) {
                    pendingWrap = false; log.info("WRAP consumed r{} c{}", curRow, curCol); // auto-wrap
                } else {
                    nl();
                }
            }
            else if (c == 0x09) { curCol = ((curCol / 8) + 1) * 8; if (curCol >= cols) { curCol = 0; curRow++; pendingWrap = true; } }
            else if (c == 0x0E) { useG1 = true; } // SO - shift out, use G1 charset
            else if (c == 0x0F) { useG1 = false; } // SI - shift in, use G0 charset
            else if (c == 0x7F) { if (curCol > 0) curCol--; } // DEL = backspace
            else if (c >= 0x20) put(c);
        }
        if (isTopData) {
            log.info("=== AFTER ===");
            dumpBuffer();
        }
        requestDraw();
    }
    // ---- Buffer ----
    private void nl() {
        curRow++;
        ensureBuf(curRow);
        while (buffer.size() > maxScroll) { buffer.remove(0); curRow--; scrollOff = Math.max(0, scrollOff - 1); }
        int effectiveBottom = scrollBottom >= 0 ? scrollBottom : scrollTop + rows - 1;
        if (!scrollLock && curRow > effectiveBottom) {
            if (scrollBottom >= 0 && scrollTop < effectiveBottom) {
                // DECSTBM scroll region: scroll within region, discarding top line
                buffer.remove(scrollTop);
                ensureBuf(effectiveBottom);
                buffer.get(effectiveBottom).clear();
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
        pendingWrap = false; wrapPendingEraseSuppress = false;
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
            curCol = 0;
            curRow++;
            pendingWrap = true; wrapPendingEraseSuppress = true;
            int effectiveBottom = scrollBottom >= 0 ? scrollBottom : scrollTop + rows - 1;
            if (!scrollLock && !inAltScreen && curRow > effectiveBottom) {
                if (scrollBottom >= 0) {
                    // DECSTBM scroll region: scroll within region, discarding top line
                    int top = scrollTop;
                    int bottom = scrollBottom >= 0 ? scrollBottom : scrollTop + rows - 1;
                    bottom = Math.max(scrollTop, bottom);
                    deleteLine(top, bottom);
                    ensureBuf(bottom);
                    // blank the bottom line
                    List<Cell> bottomLn = buffer.get(bottom);
                    for (Cell cl : bottomLn) cl.reset();
                    curRow = bottom;
                } else {
                    // Normal mode: advance viewport, preserve history in buffer
                    scrollOff = curRow - rows + 1;
                }
            }
            ensureBuf(curRow);
        }
    }
    private List<Cell> ensureBuf(int r) {
        while (buffer.size() <= r) buffer.add(new ArrayList<>());
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
        if (scrollOff != maxOff) {
            scrollOff = maxOff;
            scrollLock = false;
            draw();
            fireScrollChanged();
        }
    }
    // ---- ANSI ----
    private int esc(String s, int p, int e) {
        if (p >= e) return -1;
        char c = s.charAt(p);
        if (c == '[') { int r = csi(s, p + 1, e); return r < 0 ? -1 : r; }
        if (c == ']') { int r = osc(s, p + 1, e); return r < 0 ? -1 : r; }
        if (c == '(' || c == ')') { int r = consumeCharset(s, p + 1, e, c == '('); return r < 0 ? -1 : r; }
        // ESC 7 / ESC 8 闂?save/restore cursor (DECSC/DECRC)
        if (c == '7') { saveCursor(); return p; }
        if (c == '8') { restoreCursor(); return p; }
        // ESC M 闂?reverse index (RI)
        if (c == 'M') { reverseIndex(); return p; }
        // ESC D 闂?index (IND, move down one line)
        if (c == 'D') { indexDown(); return p; }
        // ESC E 闂?next line (NEL)
        if (c == 'E') { curCol = 0; indexDown(); return p; }
        // ESC H 闂?horizontal tab set
        if (c == 'H') return p;
        // ESC > 闂?alternate keypad numeric; ESC = 闂?alternate keypad application
        if (c == '>' || c == '=') return p;
        // ESC c 闂?RIS (reset to initial state)
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
        buffer.clear(); buffer.add(new ArrayList<>());
        curCol = curRow = scrollOff = 0;
        scrollTop = 0; scrollBottom = -1; originMode = false;
        sgrFg = 37; sgrBg = 40; sgrReverse = sgrBold = sgrUnderline = false;
        g0Charset = 'B'; g1Charset = 'B'; useG1 = false;
        draw();
    }
    private void saveCursor() {
        savedCurCol = curCol; savedCurRow = curRow;
        savedSgrFg = sgrFg; savedSgrBg = sgrBg;
        savedSgrReverse = sgrReverse; savedSgrBold = sgrBold;
        savedSgrUnderline = sgrUnderline;
    }
    private void restoreCursor() {
        curCol = savedCurCol; curRow = savedCurRow;
        sgrFg = savedSgrFg; sgrBg = savedSgrBg;
        sgrReverse = savedSgrReverse; sgrBold = savedSgrBold;
        sgrUnderline = savedSgrUnderline;
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
        int bottom = scrollBottom >= 0 ? scrollBottom : scrollTop + rows - 1;
        if (curRow == bottom) {
            deleteLine(scrollTop, bottom);
            // fill new blank line at bottom
            ensureBuf(bottom).clear();
        } else {
            curRow = Math.min(bottom, curRow + 1);
        }
    }
    private void insertLine(int at) {
        int bottom = scrollBottom >= 0 ? scrollBottom : scrollTop + rows - 1;
        ensureBuf(bottom + 1);
        buffer.add(at, new ArrayList<>());
        if (buffer.size() > bottom + 2) buffer.remove(bottom + 1);
    }
    private void deleteLine(int from, int to) {
        if (from >= buffer.size()) return;
        buffer.remove(from);
        ensureBuf(to);
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
                            // DECSET ?1049h/?1049l -- alternate screen buffer
                            if (ps.equals("1049")) {
                                if (c == 'h') {
                                    // Save current buffer and SGR state
                                    altSavedBuffer = new ArrayList<>(buffer.size());
                                    for (List<Cell> row : buffer) {
                                        List<Cell> savedRow = new ArrayList<>(row.size());
                                        for (Cell cl : row) savedRow.add(cl.copy());
                                        altSavedBuffer.add(savedRow);
                                    }
                                    altSavedCurCol = curCol; altSavedCurRow = curRow;
                                    altSavedScrollOff = scrollOff;
                                    inAltScreen = true;
                                    buffer.clear(); buffer.add(new ArrayList<>());
                                    curCol = curRow = scrollOff = 0;
                                    // Lock scroll region to the visible area in alt screen
                                    scrollTop = 0; scrollBottom = rows - 1;
                                } else {
                                    // Restore saved buffer
                                    if (altSavedBuffer != null) {
                                        buffer.clear();
                                        buffer.addAll(altSavedBuffer);
                                        curCol = altSavedCurCol; curRow = altSavedCurRow;
                                        scrollOff = altSavedScrollOff;
                                        altSavedBuffer = null;
                                    }
                                    inAltScreen = false;
                                    scrollTop = 0; scrollBottom = -1;
                                }
                                break;
                            }
                            break;
                        case 'r': // DECSTBM 闂?handled below in standard CSI
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
                            clearBuffer();
                            scrollOff = 0;
                            if (!inAltScreen) {
                                scrollTop = 0; scrollBottom = -1; originMode = false;
                            }
                        }
                        break;
                    case 'm': sgr(ps); break;
                    case 'A': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curRow = Math.max(originMode ? scrollTop : 0, curRow - n); } break;
                    case 'B': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); int maxR = buffer.isEmpty() ? 0 : buffer.size() - 1; curRow = Math.min(maxR, curRow + n); } break;
                    case 'C': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curCol = Math.min(cols - 1, curCol + n); } break;
                    case 'D': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curCol = Math.max(0, curCol - n); } break;
                    case 'E': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curCol = 0; int maxR = buffer.isEmpty() ? 0 : buffer.size() - 1; curRow = Math.min(maxR, curRow + n); } break;
                    case 'F': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curCol = 0; curRow = Math.max(originMode ? scrollTop : 0, curRow - n); } break;
                    case 'G': case '`': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curCol = Math.max(0, n - 1); } break;
                    case 'd': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curRow = Math.max(0, n - 1); break; }
                    case 'H': case 'f': {
                        String[] xy = ps.split(";");
                        int row = xy.length > 0 && !xy[0].isEmpty() ? Integer.parseInt(xy[0]) - 1 : 0;
                        int col = xy.length > 1 && !xy[1].isEmpty() ? Integer.parseInt(xy[1]) - 1 : 0;
                        if (originMode && row >= 0) row += scrollTop;
                        curRow = Math.max(0, row);
                        curCol = Math.max(0, col);
                        // Only reset viewport on home in normal screen (not alt screen)
                        if (row == 0 && col == 0) { pendingWrap = false; }
                        if (row == 0 && col == 0 && !inAltScreen) { scrollOff = 0; scrollLock = true; }
                    } break;
                    case 'L': { // insert lines at current cursor row within scroll region
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        int insAt = Math.max(scrollTop, curRow);
                        for (int i = 0; i < n; i++) {
                            ensureBuf(bottom + 1);
                            buffer.add(insAt, new ArrayList<>());
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
                        for (int i = buffer.size(); i <= bottom; i++) buffer.add(new ArrayList<>());
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
                        for (int i = 0; i < n; i++) curCol = Math.max(0, ((curCol - 1) / 8) * 8);
                    } break;
                    case 'S': { // scroll up
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = scrollTop, bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i3 = 0; i3 < n; i3++) { buffer.add(top, new ArrayList<>()); if (buffer.size() > bottom + 2) buffer.remove(bottom + 1); }
                    } break;
                    case 'T': { // scroll down (SD)
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = scrollTop, bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i3 = 0; i3 < n; i3++) { if (bottom >= top) { buffer.remove(bottom); buffer.add(top, new ArrayList<>()); } }
                    } break;
                    case 'r': { // DECSTBM 闂?set scroll region
                        String[] sr_ = ps.split(";");
                        scrollTop = sr_.length > 0 && !sr_[0].isEmpty() ? Math.max(0, Integer.parseInt(sr_[0]) - 1) : 0;
                        scrollBottom = sr_.length > 1 && !sr_[1].isEmpty() ? Integer.parseInt(sr_[1]) - 1 : -1;
                        curRow = scrollTop; curCol = 0;
                    } break;
                    case 'h': case 'l':
                        if (ps.equals("6")) originMode = (c == 'h'); // DECOM
                        break;
                    case 's': saveCursor(); break;
                    case 'u': restoreCursor(); break;
                    case 'n': break; // DSR 闂?ignore
                    case 'q': break; // DECSCUSR 闂?ignore cursor style
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
        buffer.clear(); buffer.add(new ArrayList<>());
        curCol = curRow = 0;
    }
    private void eraseEOL() {
        if (wrapPendingEraseSuppress && curCol == 0) { wrapPendingEraseSuppress = false; return; }
        List<Cell> ln = ensureBuf(curRow);
        for (int i = curCol; i < ln.size(); i++) ln.get(i).reset();
    }
    private void eraseBOL() {
        List<Cell> ln = ensureBuf(curRow);
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
            if (shellChannel == null || !shellChannel.isConnected()) { e.consume(); return; }
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasString()) {
                String text = cb.getString();
                if (text != null && !text.isEmpty()) {
                    // Replace \n with \r as terminals expect \r for Enter
                    text = text.replace("\n", "\r");
                    try {
                        OutputStream os = shellChannel.getOutputStream();
                        os.write(text.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (Exception ignored) {}
                }
            }
            e.consume();
        });
        canvas.setOnKeyPressed(e -> {
            if (shellChannel == null || !shellChannel.isConnected()) { e.consume(); return; }
            // Auto-jump to bottom before sending input
            jumpToBottom();
            byte[] b = key(e);
            if (b != null) {
                try {
                    OutputStream os = shellChannel.getOutputStream();
                    os.write(b);
                    os.flush();
                } catch (Exception ignored) {}
                e.consume();
            }
        });
        canvas.setOnKeyTyped(e -> {
            if (shellChannel == null || !shellChannel.isConnected()) return;
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty()) return;
            char c = ch.charAt(0);
            // Auto-jump to bottom before sending input
            jumpToBottom();
            if (c == '\r' || c == '\n') {
                try {
                    OutputStream os = shellChannel.getOutputStream();
                    os.write(c);
                    os.flush();
                } catch (Exception ignored) {}
                e.consume();
            } else if (c >= 0x20 && c != 0x7F) {
                try {
                    OutputStream os = shellChannel.getOutputStream();
                    os.write(ch.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (Exception ignored) {}
                e.consume();
            }
        });
        canvas.setOnScroll(e -> {
            int dir = -(int)Math.signum(e.getDeltaY());
            if (dir != 0) {
                int maxOff = Math.max(0, buffer.size() - rows);
                scrollOff = clamp(scrollOff + dir, 0, maxOff);
                scrollLock = true;
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
                case A:return new byte[]{0x01}; case B:return new byte[]{0x02}; case C:return new byte[]{0x03};
                case D:return new byte[]{0x04}; case E:return new byte[]{0x05};
                case F:return new byte[]{0x06}; case G:return new byte[]{0x07};
                case H:return new byte[]{0x08}; case J:return new byte[]{0x0A};
                case K:return new byte[]{0x0B}; case L:return new byte[]{0x0C};
                case N:return new byte[]{0x0E}; case O:return new byte[]{0x0F};
                case P:return new byte[]{0x10}; case Q:return new byte[]{0x11};
                case R:return new byte[]{0x12}; case S:return new byte[]{0x13};
                case T:return new byte[]{0x14}; case U:return new byte[]{0x15};
                case W:return new byte[]{0x17}; case X:return new byte[]{0x18};
                case Y:return new byte[]{0x19}; case Z:return new byte[]{0x1A};
                default:return null;
            }
        }
        switch (k) {
            case ENTER:return null;
            case BACK_SPACE:return new byte[]{0x7F};
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
        log.info(sb.toString());
    }
}