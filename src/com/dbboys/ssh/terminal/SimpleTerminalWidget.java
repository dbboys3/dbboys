package com.dbboys.ssh.terminal;

import com.dbboys.ssh.jediterm.JSchTtyConnector;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * JediTerm-style SSH terminal on a JavaFX Canvas.
 *
 * <p>Rendering: one pass drawing text buffer then blinking block cursor.
 * Cursor, text, and selection are independent — mouse drag-to-select
 * works natively, cursor blinks independently.
 *
 * <p>Supports: basic commands, vi, nmon, top, etc.
 */
public class SimpleTerminalWidget extends Canvas {

    private static final Font FONT = Font.font("Consolas", 13);
    public static final double CHAR_W;
    public static final double LINE_H;
    static {
        Text m = new Text("W");
        m.setFont(FONT);
        CHAR_W = m.getLayoutBounds().getWidth();
        LINE_H = 13 * 1.4;
    }

    private int cols;
    private int rows;
    private final List<StringBuilder> buffer = new ArrayList<>();
    private int curCol, curRow;
    private int selStartCol = -1, selStartRow = -1, selEndCol = -1, selEndRow = -1;
    private boolean selecting, cursorVis = true, focused = true;
    private final Timeline blink;
    private int sgrFg = 37, sgrBg = 40;
    private JSchTtyConnector conn;
    private Thread readThread;
    private int scrollOff, maxScroll = 5000;
    private Runnable onScrollChanged;

    public SimpleTerminalWidget() { this(80, 24); }

    public SimpleTerminalWidget(int cols, int rows) {
        this.cols = cols; this.rows = rows;
        setWidth(cols * CHAR_W); setHeight(rows * LINE_H);
        buffer.add(new StringBuilder());

        blink = new Timeline(new KeyFrame(Duration.millis(530), e -> { cursorVis = !cursorVis; if (focused) draw(); }));
        blink.setCycleCount(Timeline.INDEFINITE); blink.play();

        focusedProperty().addListener((o, ov, n) -> { focused = n; draw(); });
        resizeCanvas(); setupInput();
    }

    // ---- Public ----

    public void setConnector(JSchTtyConnector c) { conn = c; }
    public int cols() { return cols; } public int rows() { return rows; }
    public int getScrollOffset() { return scrollOff; }
    public int getMaxScrollOffset() { return Math.max(0, buffer.size() - rows); }
    public void setScrollOffset(int off) { scrollOff = clamp(off, 0, Math.max(0, buffer.size() - rows)); draw(); }
    public void setOnScrollChanged(Runnable r) { this.onScrollChanged = r; }

    public void setTermSize(int c, int r) {
        if (c != cols || r != rows) { cols = c; rows = r; resizeCanvas(); }
    }

    public void start() {
        if (conn == null) return;
        readThread = new Thread(() -> {
            char[] buf = new char[8192];
            try {
                int len;
                while (conn.isConnected() && (len = conn.read(buf, 0, buf.length)) != -1) {
                    String out = new String(buf, 0, len);
                    Platform.runLater(() -> write(out));
                }
            } catch (Exception e) { /* closed */ }
        }, "term-reader");
        readThread.setDaemon(true); readThread.start();
    }

    public void stop() { blink.stop(); if (readThread != null) readThread.interrupt(); if (conn != null) conn.close(); }

    /** Status message (like "Connected") */
    public void status(String s) {
        for (char c : s.toCharArray()) { if (c == '\n') nl(); else if (c == '\r') curCol = 0; else put(c); }
        nl(); draw(); fireScrollChanged();
    }

    /** Process remote output */
    public void write(String raw) {
        for (int i = 0, n = raw.length(); i < n; i++) {
            char c = raw.charAt(i);
            if (c == 0x1B && i + 1 < n) i = esc(raw, i + 1, n);
            else if (c == '\b') { if (curCol > 0) curCol--; }
            else if (c == '\r') curCol = 0;
            else if (c == '\n') nl();
            else if (c >= 0x20 && c != 0x7F) put(c);
        }
        draw(); fireScrollChanged();
    }

    public String selectedText() {
        if (selStartRow < 0 || selEndRow < 0) return "";
        int sr = Math.min(selStartRow, selEndRow), er = Math.max(selStartRow, selEndRow);
        int sc = selStartCol, ec = selEndCol;
        if (selStartRow > selEndRow || (selStartRow == selEndRow && selStartCol > selEndCol)) { sc = selEndCol; ec = selStartCol; }
        if (sc > ec) { int t = sc; sc = ec; ec = t; }
        if (sr == er) { String l = line(sr); sc = Math.min(sc, l.length()); ec = Math.min(ec, l.length()); return sc < ec ? l.substring(sc, ec) : ""; }
        StringBuilder sb = new StringBuilder();
        for (int r = sr; r <= er && r < buffer.size(); r++) {
            String l = line(r); int a = r == sr ? sc : 0, b2 = r == er ? Math.min(ec, l.length()) : l.length();
            if (a < b2) sb.append(l, a, b2);
            if (r < er) sb.append('\n');
        }
        return sb.toString();
    }

    // ---- Text buffer ----

    private void nl() { curRow++; curCol = 0; ensureBuf(curRow); while (buffer.size() > maxScroll) { buffer.remove(0); curRow--; } if (curRow - scrollOff >= rows) { scrollOff = curRow - rows + 1; } fireScrollChanged(); }
    private void fireScrollChanged() { if (onScrollChanged != null) onScrollChanged.run(); }
    private void put(char c) {
        StringBuilder ln = ensureBuf(curRow); while (ln.length() <= curCol) ln.append(' '); ln.setCharAt(curCol, c);
        if (++curCol >= cols) { curCol = 0; curRow++; ensureBuf(curRow); }
    }
    private StringBuilder ensureBuf(int r) { while (buffer.size() <= r) buffer.add(new StringBuilder()); return buffer.get(r); }
    private String line(int r) { return r < buffer.size() ? buffer.get(r).toString() : ""; }

    // ---- ANSI ----

    private int esc(String s, int p, int e) {
        if (p >= e) return e - 1;
        char c = s.charAt(p);
        if (c == '[') return csi(s, p + 1, e);
        if (c == ']') return osc(s, p + 1, e);
        return p;
    }
    private int csi(String s, int p, int e) {
        int st = p;
        while (p < e) {
            char c = s.charAt(p);
            if ((c >= '0' && c <= '9') || c == ';' || c == '?' || c == ' ') p++;
            else if (c >= '@' && c <= '~') {
                String ps = s.substring(st, p);
                switch (c) {
                    case 'K': eraseEOL(); break;
                    case 'J': if (ps.isEmpty() || ps.equals("0")) eraseEOD(); else if (ps.equals("2")) { buffer.clear(); buffer.add(new StringBuilder()); curCol = curRow = 0; } break;
                    case 'm': sgr(ps); break;
                    case 'A': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curRow = Math.max(0, curRow - n); } break;
                    case 'B': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curRow = Math.min(buffer.size() - 1, curRow + n); } break;
                    case 'C': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curCol = Math.min(cols - 1, curCol + n); } break;
                    case 'D': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); curCol = Math.max(0, curCol - n); } break;
                    case 'H': case 'f': { String[] xy = ps.split(";"); curRow = xy.length > 0 && !xy[0].isEmpty() ? Math.max(0, Integer.parseInt(xy[0]) - 1) : 0; curCol = xy.length > 1 && !xy[1].isEmpty() ? Math.max(0, Integer.parseInt(xy[1]) - 1) : 0; } break;
                    case 'h': case 'l': case 'r': case 'n': break;
                }
                return p;
            } else return p - 1;
        }
        return e - 1;
    }
    private int osc(String s, int p, int e) { while (p < e) { char c = s.charAt(p); if (c == 0x07) return p; if (c == 0x1B && p + 1 < e && s.charAt(p + 1) == '\\') return p + 1; p++; } return e - 1; }
    private void eraseEOL() { StringBuilder ln = ensureBuf(curRow); if (curCol < ln.length()) ln.delete(curCol, ln.length()); }
    private void eraseEOD() { eraseEOL(); for (int r = curRow + 1; r < buffer.size(); r++) buffer.get(r).setLength(0); }

    private void sgr(String ps) {
        if (ps.isEmpty()) { sgrFg = 37; sgrBg = 40; return; }
        for (String p : ps.split(";")) {
            if (p.isEmpty() || p.equals("0")) { sgrFg = 37; sgrBg = 40; }
            else { int n; try { n = Integer.parseInt(p); } catch (NumberFormatException x) { continue; }
                switch (n) {
                    case 1: case 4: break; case 22: case 24: break;
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

    // ---- Rendering ----

    private void resizeCanvas() { setWidth(cols * CHAR_W); setHeight(rows * LINE_H); draw(); }

    private static Color c(int code) {
        switch (code) { case 30:case 40: return Color.BLACK; case 31:case 41: return Color.rgb(205,50,50); case 32:case 42: return Color.rgb(0,205,0); case 33:case 43: return Color.rgb(205,205,0); case 34:case 44: return Color.rgb(50,100,205); case 35:case 45: return Color.rgb(205,0,205); case 36:case 46: return Color.rgb(0,205,205); case 37:case 47: return Color.rgb(230,230,230); case 90:case 100: return Color.GRAY; case 91:case 101: return Color.rgb(255,80,80); case 92:case 102: return Color.rgb(80,255,80); case 93:case 103: return Color.rgb(255,255,80); case 94:case 104: return Color.rgb(80,120,255); case 95:case 105: return Color.rgb(255,80,255); case 96:case 106: return Color.rgb(80,255,255); case 97:case 107: return Color.WHITE; default: return Color.WHITE; }
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D(); double w = getWidth(), h = getHeight();
        g.setFill(Color.BLACK); g.fillRect(0, 0, w, h); g.setFont(FONT);
        int sr = scrollOff, er = Math.min(buffer.size(), sr + rows);
        boolean hs = selStartRow >= 0 && (selStartRow != selEndRow || selStartCol != selEndCol);
        int mr = hs ? Math.min(selStartRow, selEndRow) : -1, Mr = hs ? Math.max(selStartRow, selEndRow) : -1, sl = 0, sr2 = 0;
        if (hs) { if (selStartRow == selEndRow) { sl = Math.min(selStartCol, selEndCol); sr2 = Math.max(selStartCol, selEndCol); } else { sl = mr == selStartRow ? selStartCol : selEndCol; sr2 = Mr == selEndRow ? selEndCol : selStartCol; if (sl > sr2) { int t = sl; sl = sr2; sr2 = t; } } }
        for (int r = sr; r < er && r < buffer.size(); r++) {
            int sy = r - sr; double y = sy * LINE_H; String l = buffer.get(r).toString();
            for (int col = 0; col < l.length(); col++) {
                boolean in = hs && ((r > mr && r < Mr) || (r == mr && r == Mr && col >= sl && col < sr2) || (r == mr && r != Mr && col >= sl) || (r == Mr && r != mr && col < sr2));
                double x = col * CHAR_W; Color bg = c(sgrBg), fg = c(sgrFg);
                if (in) { g.setFill(Color.rgb(200,200,200)); g.fillRect(x, y, CHAR_W, LINE_H); g.setFill(Color.BLACK); }
                else { g.setFill(bg); g.fillRect(x, y, CHAR_W, LINE_H); g.setFill(fg); }
                g.fillText(String.valueOf(l.charAt(col)), x, y + LINE_H - 3);
            }
        }
        if (cursorVis && focused) { int vr = curRow - scrollOff; if (vr >= 0 && vr < rows) { double cx = curCol * CHAR_W, cy = vr * LINE_H; g.setFill(Color.rgb(200,200,200,0.7)); g.fillRect(cx, cy, CHAR_W, LINE_H); if (curRow < buffer.size() && curCol < buffer.get(curRow).length()) { g.setFill(Color.BLACK); g.fillText(String.valueOf(buffer.get(curRow).charAt(curCol)), cx, cy + LINE_H - 3); } } }
    }

    // ---- Input ----

    private void setupInput() {
        setFocusTraversable(true);
        setOnMousePressed(e -> { requestFocus(); if (e.getButton() == MouseButton.PRIMARY) { selecting = true; selStartCol = selEndCol = (int)(e.getX()/CHAR_W); selStartRow = selEndRow = clamp(scrollOff+(int)(e.getY()/LINE_H), 0, Math.max(0,buffer.size()-1)); } });
        setOnMouseDragged(e -> { if (!selecting) return; selEndCol = clamp((int)(e.getX()/CHAR_W), 0, cols-1); selEndRow = clamp(scrollOff+(int)(e.getY()/LINE_H), 0, Math.max(0,buffer.size()-1)); draw(); });
        setOnMouseReleased(e -> { requestFocus(); selecting = false; if (e.getButton() == MouseButton.PRIMARY) { selEndCol = clamp((int)(e.getX()/CHAR_W), 0, cols-1); selEndRow = clamp(scrollOff+(int)(e.getY()/LINE_H), 0, Math.max(0,buffer.size()-1)); } if (selStartRow == selEndRow && selStartCol == selEndCol) selStartCol = selEndCol = selStartRow = selEndRow = -1; draw(); });
        setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) { int row = clamp(scrollOff + (int)(e.getY()/LINE_H), 0, Math.max(0, buffer.size()-1)); int col = clamp((int)(e.getX()/CHAR_W), 0, cols-1); String ln = line(row); int start = col, end = col; int ll = ln.length(); while (start > 0 && start <= ll && isWordChar(ln.charAt(start-1))) start--; while (end < ll && isWordChar(ln.charAt(end))) end++; selStartRow = selEndRow = row; selStartCol = start; selEndCol = end; draw(); } });
        setOnContextMenuRequested(e -> { String s = selectedText(); if (!s.isEmpty()) { Clipboard.getSystemClipboard().setContent(java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, s)); selStartCol = selEndCol = selStartRow = selEndRow = -1; draw(); NotificationUtil.showMainNotification("Copied"); } e.consume(); });
        setOnKeyPressed(e -> { if (conn == null || !conn.isConnected()) { e.consume(); return; } byte[] b = key(e); if (b != null) { try { conn.write(b); } catch (Exception x) {} e.consume(); } });
        setOnKeyTyped(e -> { if (conn == null || !conn.isConnected()) return; String ch = e.getCharacter(); if (ch == null || ch.isEmpty()) return; char c = ch.charAt(0); if (c == '\r' || c == '\n') { try { conn.write(String.valueOf(c)); } catch (Exception x) {} e.consume(); } else if (c >= 0x20 && c != 0x7F) { try { conn.write(ch); } catch (Exception x) {} e.consume(); } });
        setOnScroll(e -> { scrollOff = clamp(scrollOff + (int)(e.getDeltaY()/40), 0, Math.max(0,buffer.size()-rows)); draw(); fireScrollChanged(); });
    }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private boolean isWordChar(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'; }

    private byte[] key(KeyEvent e) {
        KeyCode k = e.getCode(); boolean ct = e.isControlDown() && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown();
        if (ct) {
            if (k == KeyCode.C) { String s = selectedText(); if (!s.isEmpty()) { Clipboard.getSystemClipboard().setContent(java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, s)); return null; } }
            if (k == KeyCode.V) { Clipboard cb = Clipboard.getSystemClipboard(); if (cb.hasString()) { String t = cb.getString(); if (t != null && !t.isEmpty()) return t.getBytes(StandardCharsets.UTF_8); } return null; }
            switch (k) { case A:return new byte[]{0x01}; case B:return new byte[]{0x02}; case D:return new byte[]{0x04}; case E:return new byte[]{0x05}; case F:return new byte[]{0x06}; case G:return new byte[]{0x07}; case H:return new byte[]{0x08}; case J:return new byte[]{0x0A}; case K:return new byte[]{0x0B}; case L:return new byte[]{0x0C}; case N:return new byte[]{0x0E}; case O:return new byte[]{0x0F}; case P:return new byte[]{0x10}; case Q:return new byte[]{0x11}; case R:return new byte[]{0x12}; case S:return new byte[]{0x13}; case T:return new byte[]{0x14}; case U:return new byte[]{0x15}; case W:return new byte[]{0x17}; case X:return new byte[]{0x18}; case Y:return new byte[]{0x19}; case Z:return new byte[]{0x1A}; default:return null; }
        }
        switch (k) { case ENTER:return null; case BACK_SPACE:return new byte[]{0x7F}; case TAB:return "\t".getBytes(StandardCharsets.UTF_8); case ESCAPE:return "".getBytes(StandardCharsets.UTF_8); case UP:return "[A".getBytes(StandardCharsets.UTF_8); case DOWN:return "[B".getBytes(StandardCharsets.UTF_8); case RIGHT:return "[C".getBytes(StandardCharsets.UTF_8); case LEFT:return "[D".getBytes(StandardCharsets.UTF_8); case HOME:return "[H".getBytes(StandardCharsets.UTF_8); case END:return "[F".getBytes(StandardCharsets.UTF_8); case DELETE:return "[3~".getBytes(StandardCharsets.UTF_8); case PAGE_UP:return "[5~".getBytes(StandardCharsets.UTF_8); case PAGE_DOWN:return "[6~".getBytes(StandardCharsets.UTF_8); case F1:return "OP".getBytes(StandardCharsets.UTF_8); case F2:return "OQ".getBytes(StandardCharsets.UTF_8); case F3:return "OR".getBytes(StandardCharsets.UTF_8); case F4:return "OS".getBytes(StandardCharsets.UTF_8); case F5:return "[15~".getBytes(StandardCharsets.UTF_8); case F6:return "[17~".getBytes(StandardCharsets.UTF_8); case F7:return "[18~".getBytes(StandardCharsets.UTF_8); case F8:return "[19~".getBytes(StandardCharsets.UTF_8); case F9:return "[20~".getBytes(StandardCharsets.UTF_8); case F10:return "[21~".getBytes(StandardCharsets.UTF_8); case F11:return "[23~".getBytes(StandardCharsets.UTF_8); case F12:return "[24~".getBytes(StandardCharsets.UTF_8); default:return null; }
    }
}
