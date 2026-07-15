package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.JschUtil;
import com.dbboys.ssh.SshConnect;
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

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
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
    private final List<StringBuilder> buffer = new ArrayList<>();
    private int curCol, curRow;
    private int selStartCol = -1, selStartRow = -1, selEndCol = -1, selEndRow = -1;
    private boolean selecting, cursorVis = true, focused = true;
    private final Timeline blink;
    private int sgrFg = 37, sgrBg = 40;
    private boolean sgrReverse, sgrBold, sgrUnderline;
    private boolean cursorShown = true; // DECTCEM
    private int scrollTop, scrollBottom = -1; // DECSTBM scroll region
    private boolean originMode; // DECOM
    private boolean pendingWrap; // auto-wrap happened, skip next \n
    private int savedCurCol, savedCurRow; // DECSC/DECRC
    private int savedSgrFg, savedSgrBg;
    private boolean savedSgrReverse, savedSgrBold;

    private Thread readThread;
    private int scrollOff, maxScroll = 5000;
    private Runnable onScrollChanged;
    private String pendingEsc;

    private Timeline autoScrollTimeline;
    private int autoScrollDirection = 0;
    public SshTabController() {
        blink = new Timeline(new KeyFrame(Duration.millis(530), e -> {
            cursorVis = !cursorVis;
            if (focused && canvas != null) draw();
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
        buffer.add(new StringBuilder());
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
                    draw();
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
                shellChannel.setPtyType("linux");
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
                Reader reader = new InputStreamReader(shellChannel.getInputStream(), StandardCharsets.UTF_8);
                char[] buf = new char[8192];
                int len;
                while (shellChannel.isConnected() && (len = reader.read(buf, 0, buf.length)) != -1) {
                    String out = new String(buf, 0, len);
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

    private void status(String s) {
        for (char c : s.toCharArray()) {
            if (c == '\n') {
                if (pendingWrap) pendingWrap = false; else nl();
            }
            else if (c == '\r') curCol = 0;
            else put(c);
        }
        nl();
        draw();
        fireScrollChanged();
    }

    private void write(String raw) {
        // prepend any incomplete escape sequence from the previous chunk
        if (pendingEsc != null) {
            raw = pendingEsc + raw;
            pendingEsc = null;
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
                    pendingWrap = false; // auto-wrap already moved cursor, skip
                } else {
                    nl();
                }
            }
            else if (c >= 0x20 && c != 0x7F) put(c);
        }
        draw();
        fireScrollChanged();
    }

    // ---- Buffer ----

    private void nl() {
        curRow++;
        curCol = 0;
        ensureBuf(curRow);
        while (buffer.size() > maxScroll) { buffer.remove(0); curRow--; }
        if (curRow - scrollOff >= rows) scrollOff = curRow - rows + 1;
        fireScrollChanged();
    }

    private void fireScrollChanged() { if (onScrollChanged != null) onScrollChanged.run(); }

    private void put(char c) {
        pendingWrap = false;
        StringBuilder ln = ensureBuf(curRow);
        while (ln.length() <= curCol) ln.append(' ');
        ln.setCharAt(curCol, c);
        int w = isFullwidth(c) ? 2 : 1;
        if (w == 2 && curCol + 1 < cols) {
            while (ln.length() <= curCol + 1) ln.append(' ');
            ln.setCharAt(curCol + 1, '\0');
        }
        curCol += w;
        if (curCol >= cols) {
            curCol = 0;
            curRow++;
            pendingWrap = true;
            if (curRow > (scrollBottom < 0 ? buffer.size() - 1 : scrollBottom)) {
                // scroll region is full — scroll up one line
                int top = scrollTop;
                int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                bottom = Math.max(top, Math.min(bottom, Math.max(0, buffer.size() - 1)));
                deleteLine(top, bottom);
                ensureBuf(bottom);
                buffer.get(bottom).setLength(0);
                curRow = bottom;
            }
            ensureBuf(curRow);
        }
    }

    private StringBuilder ensureBuf(int r) {
        while (buffer.size() <= r) buffer.add(new StringBuilder());
        return buffer.get(r);
    }

    private String line(int r) {
        return r < buffer.size() ? buffer.get(r).toString() : "";
    }

    private void jumpToBottom() {
        int maxOff = Math.max(0, buffer.size() - rows);
        if (scrollOff != maxOff) {
            scrollOff = maxOff;
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
        if (c == '(' || c == ')') { int r = consumeCharset(s, p + 1, e); return r < 0 ? -1 : r; }
        // ESC 7 / ESC 8 — save/restore cursor (DECSC/DECRC)
        if (c == '7') { saveCursor(); return p; }
        if (c == '8') { restoreCursor(); return p; }
        // ESC M — reverse index (RI)
        if (c == 'M') { reverseIndex(); return p; }
        // ESC D — index (IND, move down one line)
        if (c == 'D') { indexDown(); return p; }
        // ESC E — next line (NEL)
        if (c == 'E') { curCol = 0; indexDown(); return p; }
        // ESC H — horizontal tab set
        if (c == 'H') return p;
        // ESC > — alternate keypad numeric; ESC = — alternate keypad application
        if (c == '>' || c == '=') return p;
        // ESC c — RIS (reset to initial state)
        if (c == 'c') { buffer.clear(); buffer.add(new StringBuilder()); curCol = curRow = scrollOff = 0;
                        scrollTop = 0; scrollBottom = -1; originMode = false;
                        sgrFg = 37; sgrBg = 40; sgrReverse = sgrBold = sgrUnderline = false; draw(); return p; }
        return p;
    }

    private int consumeCharset(String s, int p, int e) {
        if (p < e) return p; // skip one char
        return -1;
    }

    private void saveCursor() {
        savedCurCol = curCol; savedCurRow = curRow;
        savedSgrFg = sgrFg; savedSgrBg = sgrBg;
        savedSgrReverse = sgrReverse; savedSgrBold = sgrBold;
    }

    private void restoreCursor() {
        curCol = savedCurCol; curRow = savedCurRow;
        sgrFg = savedSgrFg; sgrBg = savedSgrBg;
        sgrReverse = savedSgrReverse; sgrBold = savedSgrBold;
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
        int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
        bottom = Math.min(bottom, Math.max(0, buffer.size() - 1));
        if (curRow == bottom) {
            deleteLine(scrollTop, bottom);
            // fill new blank line at bottom
            ensureBuf(bottom);
            buffer.get(bottom).setLength(0);
        } else {
            curRow = Math.min(bottom, curRow + 1);
        }
    }

    private void insertLine(int at) {
        int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
        bottom = Math.min(bottom, Math.max(0, buffer.size() - 1));
        ensureBuf(bottom + 1);
        buffer.add(at, new StringBuilder());
        if (buffer.size() > bottom + 2) buffer.remove(bottom + 1);
    }

    private void deleteLine(int from, int to) {
        if (from >= buffer.size()) return;
        buffer.remove(from);
        ensureBuf(to);
        if (buffer.size() > to + 1) {
            // shift was implicit in remove
        }
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
            if ((c >= '0' && c <= '9') || c == ';' || c == ' ' || c == '>') p++;
            else if (c == '?' && p == st) { isPrivate = true; st = ++p; }
            else if (c >= '@' && c <= '~') {
                String ps = s.substring(st, p);
                if (isPrivate) {
                    switch (c) {
                        case 'h': case 'l':
                            if (ps.equals("25")) cursorShown = (c == 'h'); break; // DECTCEM
                        case 'r': break; // DECSTBM — handled below
                        case 's': break; // DECSC
                        case 'u': break; // DECRC
                        case 'J':
                            if (ps.equals("2")) { buffer.clear(); buffer.add(new StringBuilder()); curCol = curRow = 0; }
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
                        else if (ps.equals("2")) { buffer.clear(); buffer.add(new StringBuilder()); curCol = curRow = 0; scrollOff = 0;
                                                  scrollTop = 0; scrollBottom = -1; originMode = false; }
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
                        if (row == 0 && col == 0) scrollOff = 0; // top refresh resets viewport
                    } break;
                    case 'L': { // insert lines
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i = 0; i < n; i++) { ensureBuf(bottom + 1); buffer.add(scrollTop, new StringBuilder()); if (buffer.size() > bottom + 2) buffer.remove(bottom + 1); }
                    } break;
                    case 'M': { // delete lines
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i = 0; i < n && buffer.size() > scrollTop; i++) { buffer.remove(scrollTop); }
                        for (int i = buffer.size(); i <= bottom; i++) buffer.add(new StringBuilder());
                    } break;
                    case 'P': { // delete characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        StringBuilder ln = ensureBuf(curRow);
                        if (curCol < ln.length()) ln.delete(curCol, Math.min(ln.length(), curCol + n));
                    } break;
                    case '@': { // insert characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        StringBuilder ln = ensureBuf(curRow);
                        for (int i2 = 0; i2 < n; i2++) ln.insert(curCol, ' ');
                    } break;
                    case 'X': { // erase characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        StringBuilder ln = ensureBuf(curRow);
                        int end = Math.min(ln.length(), curCol + n);
                        for (int i2 = curCol; i2 < end; i2++) { if (i2 < ln.length()) ln.setCharAt(i2, ' '); }
                    } break;
                    case 'S': { // scroll up
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = scrollTop, bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i3 = 0; i3 < n; i3++) { buffer.add(top, new StringBuilder()); if (buffer.size() > bottom + 2) buffer.remove(bottom + 1); }
                    } break;
                    case 'T': { // scroll down
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = scrollTop, bottom = scrollBottom < 0 ? Math.max(0, buffer.size() - 1) : scrollBottom;
                        for (int i3 = 0; i3 < n && buffer.size() > top; i3++) buffer.remove(top);
                        for (int i3 = buffer.size(); i3 <= bottom; i3++) buffer.add(new StringBuilder());
                    } break;
                    case 'r': { // DECSTBM — set scroll region
                        String[] sr_ = ps.split(";");
                        scrollTop = sr_.length > 0 && !sr_[0].isEmpty() ? Math.max(0, Integer.parseInt(sr_[0]) - 1) : 0;
                        scrollBottom = sr_.length > 1 && !sr_[1].isEmpty() ? Integer.parseInt(sr_[1]) - 1 : -1;
                        curRow = scrollTop; curCol = 0;
                    } break;
                    case 'h': case 'l':
                        if (ps.equals("6")) originMode = (c == 'h'); // DECOM
                        // DECSET/DECRST 25 (cursor visibility) handled in private case above
                        break;
                    case 's': saveCursor(); break;
                    case 'u': restoreCursor(); break;
                    case 'n': break; // DSR — ignore
                    case 'q': break; // DECSCUSR — ignore cursor style
                }
                return p;
            } else return p - 1;
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

    private void eraseEOL() {
        StringBuilder ln = ensureBuf(curRow);
        if (curCol < ln.length()) ln.delete(curCol, ln.length());
    }

    private void eraseBOL() {
        StringBuilder ln = ensureBuf(curRow);
        int end = Math.min(curCol + 1, ln.length());
        if (end > 0) ln.delete(0, end);
    }

    private void eraseLine() {
        if (curRow < buffer.size()) buffer.get(curRow).setLength(0);
    }

    private void eraseEOD() {
        eraseEOL();
        for (int r = curRow + 1; r < buffer.size(); r++) buffer.get(r).setLength(0);
    }

    private void eraseDOS() {
        for (int r = 0; r < curRow && r < buffer.size(); r++) buffer.get(r).setLength(0);
        eraseBOL();
    }

    private void sgr(String ps) {
        if (ps.isEmpty()) { sgrFg = 37; sgrBg = 40; sgrReverse = sgrBold = sgrUnderline = false; return; }
        for (String p : ps.split(";")) {
            if (p.isEmpty() || p.equals("0")) {
                sgrFg = 37; sgrBg = 40; sgrReverse = sgrBold = sgrUnderline = false;
            } else {
                int n;
                try { n = Integer.parseInt(p); } catch (NumberFormatException x) { continue; }
                switch (n) {
                    case 1: sgrBold = true; break;
                    case 2: sgrBold = false; break;  // dim/faint — treat as unbold for now
                    case 3: sgrBold = false; break;  // italic — ignore
                    case 4: sgrUnderline = true; break;
                    case 5: case 6: break; // blink — ignore
                    case 7: sgrReverse = true; break;
                    case 22: sgrBold = false; break;
                    case 23: break; // italic off
                    case 24: sgrUnderline = false; break;
                    case 25: break; // blink off
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
            String l = buffer.get(r).toString();
            for (int col = 0; col < l.length(); col++) {
                char ch = l.charAt(col);
                if (ch == '\0') continue;
                boolean isFw = isFullwidth(ch);
                double cellW = isFw ? CHAR_W * 2 : CHAR_W;

                boolean in = hs && ((r > mr && r < Mr)
                        || (r == mr && r == Mr && col >= sl && col < sr2)
                        || (r == mr && r != Mr && col >= sl)
                        || (r == Mr && r != mr && col < sr2));
                double x = col * CHAR_W;
                Color fg = c(sgrFg), bg = c(sgrBg);
                if (sgrReverse) { Color t = fg; fg = bg; bg = t; }
                if (in) {
                    g.setFill(Color.rgb(200,200,200));
                    g.fillRect(x, y, cellW, LINE_H);
                    g.setFill(Color.BLACK);
                } else {
                    g.setFill(bg);
                    g.fillRect(x, y, cellW, LINE_H);
                    g.setFill(fg);
                }
                if (sgrBold) {
                    g.fillText(String.valueOf(ch), x, y + LINE_H - 3);
                    g.fillText(String.valueOf(ch), x + 0.5, y + LINE_H - 3);
                } else {
                    g.fillText(String.valueOf(ch), x, y + LINE_H - 3);
                }
                if (sgrUnderline) {
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
                char atCursor = (curRow < buffer.size() && curCol < buffer.get(curRow).length())
                        ? buffer.get(curRow).charAt(curCol) : ' ';
                double cursorW = isFullwidth(atCursor) ? CHAR_W * 2 : CHAR_W;
                g.setFill(Color.rgb(200,200,200,0.7));
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
            // Right-click: paste from clipboard (\\n -> \\r for terminal)
            if (shellChannel == null || !shellChannel.isConnected()) { e.consume(); return; }
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasString()) {
                String text = cb.getString();
                if (text != null && !text.isEmpty()) {
                    // Replace \\n with \\r as terminals expect \\r for Enter
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
                case A:return new byte[]{0x01}; case B:return new byte[]{0x02};
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
            case ESCAPE:return "\u001B".getBytes(StandardCharsets.UTF_8);
            case UP:return "\u001B[A".getBytes(StandardCharsets.UTF_8);
            case DOWN:return "\u001B[B".getBytes(StandardCharsets.UTF_8);
            case RIGHT:return "\u001B[C".getBytes(StandardCharsets.UTF_8);
            case LEFT:return "\u001B[D".getBytes(StandardCharsets.UTF_8);
            case HOME:return "\u001B[H".getBytes(StandardCharsets.UTF_8);
            case END:return "\u001B[F".getBytes(StandardCharsets.UTF_8);
            case DELETE:return "\u001B[3~".getBytes(StandardCharsets.UTF_8);
            case PAGE_UP:return "\u001B[5~".getBytes(StandardCharsets.UTF_8);
            case PAGE_DOWN:return "\u001B[6~".getBytes(StandardCharsets.UTF_8);
            case F1:return "\u001BOP".getBytes(StandardCharsets.UTF_8);
            case F2:return "\u001BOQ".getBytes(StandardCharsets.UTF_8);
            case F3:return "\u001BOR".getBytes(StandardCharsets.UTF_8);
            case F4:return "\u001BOS".getBytes(StandardCharsets.UTF_8);
            case F5:return "\u001B[15~".getBytes(StandardCharsets.UTF_8);
            case F6:return "\u001B[17~".getBytes(StandardCharsets.UTF_8);
            case F7:return "\u001B[18~".getBytes(StandardCharsets.UTF_8);
            case F8:return "\u001B[19~".getBytes(StandardCharsets.UTF_8);
            case F9:return "\u001B[20~".getBytes(StandardCharsets.UTF_8);
            case F10:return "\u001B[21~".getBytes(StandardCharsets.UTF_8);
            case F11:return "\u001B[23~".getBytes(StandardCharsets.UTF_8);
            case F12:return "\u001B[24~".getBytes(StandardCharsets.UTF_8);
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
}
