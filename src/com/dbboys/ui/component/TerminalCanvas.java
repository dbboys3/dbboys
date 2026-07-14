package com.dbboys.ui.component;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lightweight terminal renderer on a JavaFX {@link Canvas}.
 *
 * <p>Like easyshell/JediTerm, rendering is on a Canvas where cursor,
 * text, and selection are independent paint layers.  Mouse
 * drag-to-select and blinking block cursor coexist natively.
 */
public class TerminalCanvas extends Canvas {

    private static final Font FONT = Font.font("Consolas", 13);
    public static final double CHAR_W;
    public static final double LINE_H;
    static {
        Text m = new Text("W");
        m.setFont(FONT);
        CHAR_W = m.getLayoutBounds().getWidth();
        LINE_H = 13 * 1.4;
    }

    private int cols = 80;
    private int rows = 24;

    // ---- Autosize ----
    private boolean autoSize = true;

    // ---- Text buffer ----
    private final List<StringBuilder> buffer = new ArrayList<>();
    private int cursorCol;
    private int cursorRow;
    private int scrollOffset;

    // ---- Selection ----
    private int selStartCol, selStartRow;
    private int selEndCol, selEndRow;
    private boolean selecting;
    private int mousePressCol, mousePressRow;

    // ---- Blink ----
    private boolean cursorVisible = true;
    private final Timeline blinkTimer;

    // ---- ANSI SGR state ----
    private int sgrFg = 37;   // 37 = white
    private int sgrBg = 40;   // 40 = black
    private boolean sgrBold;
    private boolean sgrUnderline;

    // ---- External handlers ----
    private Consumer<byte[]> keyHandler;

    // ---- Focus-aware blink ----
    private boolean focused = true;

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

    public TerminalCanvas() {
        this(80, 24);
    }

    public TerminalCanvas(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        setWidth(cols * CHAR_W);
        setHeight(rows * LINE_H);
        buffer.add(new StringBuilder());
        cursorRow = 0;
        cursorCol = 0;

        blinkTimer = new Timeline(
                new KeyFrame(Duration.millis(530),  e -> { cursorVisible = !cursorVisible; if (focused) draw(); }),
                new KeyFrame(Duration.millis(1060), e -> { cursorVisible = !cursorVisible; if (focused) draw(); }));
        blinkTimer.setCycleCount(Timeline.INDEFINITE);
        blinkTimer.play();

        focusedProperty().addListener((obs, o, n) -> {
            focused = n;
            if (!n) { cursorVisible = true; draw(); }
        });

        setupInput();
    }

    // ---- Public API ----

    public void setKeyHandler(Consumer<byte[]> handler) { this.keyHandler = handler; }

    public int getColumns() { return cols; }
    public int getRows()    { return rows; }

    public void setTermSize(int columns, int rows) {
        this.cols = columns;
        this.rows = rows;
        setWidth(columns * CHAR_W);
        setHeight(rows * LINE_H);
        draw();
    }

    /** Feed raw bytes from the remote shell to the terminal. */
    public void processOutput(byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            byte b = data[i];
            if (b == 0x1B && i + 1 < off + len) {
                i = consumeEscape(data, i + 1, off + len);
            } else if (b == '\b') {
                if (cursorCol > 0) cursorCol--;
            } else if (b == '\r') {
                cursorCol = 0;
            } else if (b == '\n') {
                newLine();
            } else if (b == 0x07) {
                // BEL — ignore
            } else if (b >= 0x20 && b != 0x7F) {
                putChar((char) b);
            }
        }
        draw();
    }

    public void appendStatus(String text) {
        for (char c : text.toCharArray()) {
            if (c == '\n') newLine(); else putChar(c);
        }
        newLine();
        draw();
    }

    public void clear() {
        buffer.clear();
        buffer.add(new StringBuilder());
        cursorCol = 0; cursorRow = 0; scrollOffset = 0;
        draw();
    }

    public String getSelectedText() {
        if (selStartRow == selEndRow && selStartCol == selEndCol) return "";
        if (selStartRow < 0 || selEndRow >= buffer.size()) return "";
        int sr = Math.min(selStartRow, selEndRow);
        int er = Math.max(selStartRow, selEndRow);
        boolean swapped = selStartRow > selEndRow
                || (selStartRow == selEndRow && selStartCol > selEndCol);
        int sc = swapped ? selEndCol : selStartCol;
        int ec = swapped ? selStartCol : selEndCol;
        if (sc > ec) { int t = sc; sc = ec; ec = t; }
        if (sr == er) {
            String line = stripContinuation(buffer.get(sr).toString());
            sc = Math.min(sc, line.length());
            ec = Math.min(ec, line.length());
            if (sc >= ec) return "";
            return line.substring(sc, ec);
        }
        StringBuilder sb = new StringBuilder();
        for (int r = sr; r <= er && r < buffer.size(); r++) {
            String line = stripContinuation(buffer.get(r).toString());
            int c1 = (r == sr) ? sc : 0;
            int c2 = (r == er) ? Math.min(ec, line.length()) : line.length();
            if (c1 < c2) sb.append(line, c1, c2);
            if (r < er) sb.append('\n');
        }
        return sb.toString().replaceAll("\n$", "");
    }

    /** Strip continuation cells (\0) left by fullwidth characters. */
    private static String stripContinuation(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\0') sb.append(c);
        }
        return sb.toString();
    }

    // ---- Internal: text buffer ----

    private void newLine() {
        cursorRow++; cursorCol = 0;
        while (buffer.size() <= cursorRow) buffer.add(new StringBuilder());
        if (cursorRow - scrollOffset >= rows) scrollOffset = cursorRow - rows + 1;
    }

    private void putChar(char c) {
        while (buffer.size() <= cursorRow) buffer.add(new StringBuilder());
        StringBuilder line = buffer.get(cursorRow);
        while (line.length() <= cursorCol) line.append(' ');
        line.setCharAt(cursorCol, c);
        int w = isFullwidth(c) ? 2 : 1;
        // For fullwidth char, mark the following column as a continuation cell
        if (w == 2 && cursorCol + 1 < cols) {
            while (line.length() <= cursorCol + 1) line.append(' ');
            line.setCharAt(cursorCol + 1, '\0');
        }
        cursorCol += w;
        while (cursorCol >= cols) { cursorCol -= cols; cursorRow++; }
        while (buffer.size() <= cursorRow) buffer.add(new StringBuilder());
    }

    // ---- Internal: ANSI escape handling ----

    private int consumeEscape(byte[] data, int pos, int end) {
        if (pos >= end) return end - 1;
        byte b = data[pos];
        if (b == '[') return consumeCSI(data, pos + 1, end);
        if (b == ']') return consumeOSC(data, pos + 1, end);
        return pos; // single-char: consumed
    }

    private int consumeCSI(byte[] data, int pos, int end) {
        int start = pos;
        while (pos < end) {
            byte b = data[pos];
            if ((b >= '0' && b <= '9') || b == ';' || b == '?' || b == ' ') {
                pos++;
            } else if (b >= '@' && b <= '~') {
                String paramStr = new String(data, start, pos - start, StandardCharsets.UTF_8);
                char cmd = (char) b;
                switch (cmd) {
                    case 'K': eraseToEndOfLine(); break;
                    case 'J':
                        if (paramStr.isEmpty() || paramStr.equals("0")) eraseToEndOfDisplay();
                        else if (paramStr.equals("2")) { buffer.clear(); buffer.add(new StringBuilder()); cursorCol = 0; cursorRow = 0; }
                        break;
                    case 'm': applySGR(paramStr); break;
                    case 'H': case 'f': break; // cursor positioning — ignore in append-only model
                    case 'A': case 'B': case 'C': case 'D': break;
                    case 'h': case 'l': break; // DECSET/DECRST — ignore
                    case 'r': break; // set scroll region — ignore
                    case 'n': break; // DSR — ignore
                    default:  break;
                }
                return pos;
            } else {
                return pos - 1; // malformed
            }
        }
        return end - 1;
    }

    private int consumeOSC(byte[] data, int pos, int end) {
        while (pos < end) {
            byte b = data[pos];
            if (b == 0x07) return pos;
            if (b == 0x1B && pos + 1 < end && data[pos + 1] == '\\') return pos + 1;
            pos++;
        }
        return end - 1;
    }

    private void eraseToEndOfLine() {
        while (buffer.size() <= cursorRow) buffer.add(new StringBuilder());
        StringBuilder line = buffer.get(cursorRow);
        if (cursorCol < line.length()) line.delete(cursorCol, line.length());
    }

    private void eraseToEndOfDisplay() {
        eraseToEndOfLine();
        for (int r = cursorRow + 1; r < buffer.size(); r++) buffer.get(r).setLength(0);
    }

    private void applySGR(String params) {
        if (params.isEmpty()) { sgrFg = 37; sgrBg = 40; sgrBold = false; sgrUnderline = false; return; }
        for (String p : params.split(";")) {
            if (p.isEmpty() || p.equals("0")) { sgrFg = 37; sgrBg = 40; sgrBold = false; sgrUnderline = false; }
            else {
                int n = Integer.parseInt(p);
                switch (n) {
                    case 1: sgrBold = true; break;
                    case 22: sgrBold = false; break;
                    case 4: sgrUnderline = true; break;
                    case 24: sgrUnderline = false; break;
                    case 30: sgrFg = 30; break; case 31: sgrFg = 31; break; case 32: sgrFg = 32; break;
                    case 33: sgrFg = 33; break; case 34: sgrFg = 34; break; case 35: sgrFg = 35; break;
                    case 36: sgrFg = 36; break; case 37: sgrFg = 37; break;
                    case 39: sgrFg = 37; break;
                    case 40: sgrBg = 40; break; case 41: sgrBg = 41; break; case 42: sgrBg = 42; break;
                    case 43: sgrBg = 43; break; case 44: sgrBg = 44; break; case 45: sgrBg = 45; break;
                    case 46: sgrBg = 46; break; case 47: sgrBg = 47; break;
                    case 49: sgrBg = 40; break;
                    case 90: sgrFg = 90; break; case 91: sgrFg = 91; break; case 92: sgrFg = 92; break;
                    case 93: sgrFg = 93; break; case 94: sgrFg = 94; break; case 95: sgrFg = 95; break;
                    case 96: sgrFg = 96; break; case 97: sgrFg = 97; break;
                    case 100: sgrBg = 100; break; case 101: sgrBg = 101; break; case 102: sgrBg = 102; break;
                    case 103: sgrBg = 103; break; case 104: sgrBg = 104; break; case 105: sgrBg = 105; break;
                    case 106: sgrBg = 106; break; case 107: sgrBg = 107; break;
                }
            }
        }
    }

    // ---- Internal: rendering ----

    private Color sgrColor(int code) {
        switch (code) {
            case 30: case 40: return Color.BLACK;
            case 31: case 41: return Color.rgb(205, 50, 50);
            case 32: case 42: return Color.rgb(0, 205, 0);
            case 33: case 43: return Color.rgb(205, 205, 0);
            case 34: case 44: return Color.rgb(50, 100, 205);
            case 35: case 45: return Color.rgb(205, 0, 205);
            case 36: case 46: return Color.rgb(0, 205, 205);
            case 37: case 47: return Color.rgb(230, 230, 230);
            case 90: case 100: return Color.GRAY;
            case 91: case 101: return Color.rgb(255, 80, 80);
            case 92: case 102: return Color.rgb(80, 255, 80);
            case 93: case 103: return Color.rgb(255, 255, 80);
            case 94: case 104: return Color.rgb(80, 120, 255);
            case 95: case 105: return Color.rgb(255, 80, 255);
            case 96: case 106: return Color.rgb(80, 255, 255);
            case 97: case 107: return Color.WHITE;
            default:  return Color.WHITE;
        }
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();

        g.setFill(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setFont(FONT);

        int startRow = scrollOffset;
        int endRow = Math.min(buffer.size(), startRow + rows);

        // Compute selection range in buffer coordinates
        boolean hasSel = (selStartRow != selEndRow || selStartCol != selEndCol);
        int selMinR = Math.min(selStartRow, selEndRow);
        int selMaxR = Math.max(selStartRow, selEndRow);
        int sLeft, sRight;
        if (selStartRow == selEndRow) {
            sLeft = Math.min(selStartCol, selEndCol);
            sRight = Math.max(selStartCol, selEndCol);
        } else {
            sLeft = (selMinR == selStartRow ? selStartCol : selEndCol);
            sRight = (selMaxR == selEndRow ? selEndCol : selStartCol);
            if (sLeft > sRight) { int t = sLeft; sLeft = sRight; sRight = t; }
        }

        for (int r = startRow; r < endRow; r++) {
            int screenY = r - startRow;
            double y = screenY * LINE_H;
            if (r >= buffer.size()) break;
            String line = buffer.get(r).toString();

            for (int col = 0; col < line.length(); col++) {
                char ch = line.charAt(col);
                // Skip continuation cells of double-width characters
                if (ch == '\0') continue;
                boolean isFw = isFullwidth(ch);
                double cellW = isFw ? CHAR_W * 2 : CHAR_W;

                boolean inSel = false;
                if (hasSel) {
                    if (r > selMinR && r < selMaxR) inSel = true;
                    else if (r == selMinR && r == selMaxR) inSel = col >= sLeft && col < sRight;
                    else if (r == selMinR) inSel = col >= sLeft;
                    else if (r == selMaxR) inSel = col < sRight;
                }

                double x = col * CHAR_W;
                Color bg = sgrColor(sgrBg);
                Color fg = sgrColor(sgrFg);

                if (inSel) {
                    g.setFill(Color.rgb(200, 200, 200));
                    g.fillRect(x, y, cellW, LINE_H);
                    g.setFill(Color.BLACK);
                } else {
                    g.setFill(bg);
                    g.fillRect(x, y, cellW, LINE_H);
                    g.setFill(fg);
                }
                g.fillText(String.valueOf(ch), x, y + LINE_H - 3);

                if (inSel || sgrUnderline) {
                    g.setStroke(inSel ? Color.BLACK : fg);
                    g.setLineWidth(1);
                    g.strokeLine(x, y + LINE_H - 2, x + cellW, y + LINE_H - 2);
                }
            }
        }

        // Blinking block cursor
        if (cursorVisible && focused) {
            int visR = cursorRow - scrollOffset;
            if (visR >= 0 && visR < rows) {
                double cx = cursorCol * CHAR_W;
                double cy = visR * LINE_H + LINE_H * 0.2;
                double cursorH = LINE_H * 0.8;
                char atCursor = (cursorRow < buffer.size() && cursorCol < buffer.get(cursorRow).length())
                        ? buffer.get(cursorRow).charAt(cursorCol) : ' ';
                double cursorW = isFullwidth(atCursor) ? CHAR_W * 2 : CHAR_W;
                g.setFill(Color.rgb(200, 200, 200, 0.7));
                g.fillRect(cx, cy, cursorW, cursorH);
                if (atCursor != '\0' && atCursor != ' ') {
                    g.setFill(Color.BLACK);
                    g.fillText(String.valueOf(atCursor), cx, visR * LINE_H + LINE_H - 3);
                }
            }
        }
    }

    // ---- Internal: input handling ----

    private void setupInput() {
        setFocusTraversable(true);

        setOnMousePressed(e -> {
            requestFocus();
            selecting = true;
            mousePressCol = (int) (e.getX() / CHAR_W);
            mousePressRow = scrollOffset + (int) (e.getY() / LINE_H);
            selStartCol = mousePressCol;
            selStartRow = mousePressRow;
            selEndCol = mousePressCol;
            selEndRow = mousePressRow;
        });

        setOnMouseDragged(e -> {
            if (!selecting) return;
            selEndCol = clamp((int) (e.getX() / CHAR_W), 0, cols - 1);
            selEndRow = clamp(scrollOffset + (int) (e.getY() / LINE_H), 0, buffer.isEmpty() ? 0 : buffer.size() - 1);
            draw();
        });

        setOnMouseReleased(e -> {
            selecting = false;
            selEndCol = clamp((int) (e.getX() / CHAR_W), 0, cols - 1);
            selEndRow = clamp(scrollOffset + (int) (e.getY() / LINE_H), 0, buffer.isEmpty() ? 0 : buffer.size() - 1);
            if (selStartRow == selEndRow && selStartCol == selEndCol) {
                // Plain click — clear selection
                selStartCol = selEndCol = 0;
                selStartRow = selEndRow = -1;
                selEndRow = -1;
            }
            draw();
        });

        setOnKeyTyped(e -> {
            if (keyHandler == null) return;
            String ch = e.getCharacter();
            if (ch == null || ch.isEmpty()) return;
            char c = ch.charAt(0);
            if (c < 0x20 || c == 0x7F) return;
            keyHandler.accept(ch.getBytes(StandardCharsets.UTF_8));
        });

        setOnKeyPressed(e -> {
            if (keyHandler == null) { e.consume(); return; }
            byte[] code = mapKey(e);
            if (code != null) { keyHandler.accept(code); e.consume(); }
        });

        setOnScroll(e -> {
            int delta = (int) (e.getDeltaY() / 40);
            int maxOff = Math.max(0, buffer.size() - rows);
            scrollOffset = clamp(scrollOffset + delta, 0, maxOff);
            draw();
        });
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private byte[] mapKey(KeyEvent e) {
        KeyCode k = e.getCode();
        boolean ctrl = e.isControlDown() && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown();

        if (ctrl) {
            if (k == KeyCode.C) {
                String sel = getSelectedText();
                if (!sel.isEmpty()) Clipboard.getSystemClipboard().setContent(
                        java.util.Collections.singletonMap(DataFormat.PLAIN_TEXT, sel));
                return new byte[]{0x03};
            }
            if (k == KeyCode.V) {
                Clipboard cb = Clipboard.getSystemClipboard();
                if (cb.hasString()) {
                    String text = cb.getString();
                    if (text != null && !text.isEmpty()) return text.getBytes(StandardCharsets.UTF_8);
                }
                return null;
            }
            switch (k) {
                case A: return new byte[]{0x01}; case B: return new byte[]{0x02};
                case D: return new byte[]{0x04}; case E: return new byte[]{0x05};
                case F: return new byte[]{0x06}; case G: return new byte[]{0x07};
                case H: return new byte[]{0x08}; case J: return new byte[]{0x0A};
                case K: return new byte[]{0x0B}; case L: return new byte[]{0x0C};
                case N: return new byte[]{0x0E}; case O: return new byte[]{0x0F};
                case P: return new byte[]{0x10}; case Q: return new byte[]{0x11};
                case R: return new byte[]{0x12}; case S: return new byte[]{0x13};
                case T: return new byte[]{0x14}; case U: return new byte[]{0x15};
                case W: return new byte[]{0x17}; case X: return new byte[]{0x18};
                case Y: return new byte[]{0x19}; case Z: return new byte[]{0x1A};
                default: return null;
            }
        }

        switch (k) {
            case ENTER:      return "\r".getBytes(StandardCharsets.UTF_8);
            case BACK_SPACE: return new byte[]{0x7F};
            case TAB:        return "\t".getBytes(StandardCharsets.UTF_8);
            case ESCAPE:     return "".getBytes(StandardCharsets.UTF_8);
            case UP:         return "[A".getBytes(StandardCharsets.UTF_8);
            case DOWN:       return "[B".getBytes(StandardCharsets.UTF_8);
            case RIGHT:      return "[C".getBytes(StandardCharsets.UTF_8);
            case LEFT:       return "[D".getBytes(StandardCharsets.UTF_8);
            case HOME:       return "[H".getBytes(StandardCharsets.UTF_8);
            case END:        return "[F".getBytes(StandardCharsets.UTF_8);
            case DELETE:     return "[3~".getBytes(StandardCharsets.UTF_8);
            case PAGE_UP:    return "[5~".getBytes(StandardCharsets.UTF_8);
            case PAGE_DOWN:  return "[6~".getBytes(StandardCharsets.UTF_8);
            case F1: return "OP".getBytes(StandardCharsets.UTF_8);
            case F2: return "OQ".getBytes(StandardCharsets.UTF_8);
            case F3: return "OR".getBytes(StandardCharsets.UTF_8);
            case F4: return "OS".getBytes(StandardCharsets.UTF_8);
            case F5: return "[15~".getBytes(StandardCharsets.UTF_8);
            case F6: return "[17~".getBytes(StandardCharsets.UTF_8);
            case F7: return "[18~".getBytes(StandardCharsets.UTF_8);
            case F8: return "[19~".getBytes(StandardCharsets.UTF_8);
            case F9: return "[20~".getBytes(StandardCharsets.UTF_8);
            case F10: return "[21~".getBytes(StandardCharsets.UTF_8);
            case F11: return "[23~".getBytes(StandardCharsets.UTF_8);
            case F12: return "[24~".getBytes(StandardCharsets.UTF_8);
            default:  return null;
        }
    }
}
