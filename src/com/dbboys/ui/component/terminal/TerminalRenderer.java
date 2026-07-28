package com.dbboys.ui.component.terminal;

import com.dbboys.infra.config.ConfigManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;
import com.dbboys.ui.component.terminal.TerminalBuffer.Cell;

/**
 * Canvas view of a {@link TerminalBuffer}: owns the canvas, the terminal font
 * (zoomable, persisted in etc/config.properties), character measurement, SGR /
 * xterm-256 colors, the cursor-blink timer, and the actual painting.
 */
public class TerminalRenderer {
    // Terminal font: zoomable with Ctrl + '+' / Ctrl + '-' and Ctrl + mouse wheel,
    // persisted in etc/config.properties (SSH_TERMINAL_FONT_SIZE)
    private static final String SSH_FONT_SIZE_KEY = "SSH_TERMINAL_FONT_SIZE";
    private static final int DEFAULT_FONT_SIZE = 13;
    private static final int MIN_FONT_SIZE = 4;   // same bounds as the SQL editor
    private static final int MAX_FONT_SIZE = 40;
    // On Windows prefer Consolas; on other platforms fall back to the system monospace font.
    private static final String FONT_FAMILY = Font.getFamilies().contains("Consolas") ? "Consolas" : "monospace";
    private int fontSize = loadConfiguredFontSize();
    private Font FONT = Font.font(FONT_FAMILY, fontSize);
    private double CHAR_W = measureCharW(FONT);
    private double LINE_H = fontSize * 1.4;

    private final TerminalBuffer buf;
    private final Canvas canvas;
    private final Timeline blink;
    private boolean cursorVis = true, focused = true;
    // Deferred draw: coalesce rapid write() calls into a single draw,
    // preventing the blink timer from rendering partially-updated screens.
    private volatile boolean drawPending;
    /** Runs after every deferred draw (controller updates the scrollbar). */
    private Runnable afterDraw;

    public TerminalRenderer(TerminalBuffer buf) {
        this.buf = buf;
        canvas = new Canvas(buf.cols * CHAR_W, buf.rows * LINE_H);
        canvas.setFocusTraversable(true);
        canvas.focusedProperty().addListener((o, ov, n) -> { focused = n; draw(); });
        blink = new Timeline(new KeyFrame(Duration.millis(530), e -> {
            cursorVis = !cursorVis;
            if (focused && canvas != null && !drawPending) draw();
        }));
        blink.setCycleCount(Timeline.INDEFINITE);
        blink.play();
    }

    private static int loadConfiguredFontSize() {
        try {
            return clampFontSize(Integer.parseInt(ConfigManager.getProperty(
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

    public static boolean isZoomInKey(KeyEvent e) {
        return e.getCode() == KeyCode.ADD || e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.EQUALS;
    }

    public static boolean isZoomOutKey(KeyEvent e) {
        return e.getCode() == KeyCode.SUBTRACT || e.getCode() == KeyCode.MINUS;
    }

    public Canvas getCanvas() { return canvas; }
    public double getCharW() { return CHAR_W; }
    public double getLineH() { return LINE_H; }
    public void setAfterDraw(Runnable afterDraw) { this.afterDraw = afterDraw; }

    /** Request keyboard focus on the terminal canvas. */
    public void requestFocus() {
        canvas.requestFocus();
    }

    public void playBlink() { blink.play(); }
    public void stopBlink() { blink.stop(); }

    /** Apply a font zoom step: recompute the font metrics and return whether
     *  anything changed. The caller re-tiles the grid, reflows content and
     *  persists the size via {@link #persistFontSize()}. */
    public boolean applyFontSize(int delta) {
        int newSize = clampFontSize(fontSize + delta);
        if (newSize == fontSize || canvas == null) return false;
        fontSize = newSize;
        FONT = Font.font(FONT_FAMILY, fontSize);
        CHAR_W = measureCharW(FONT);
        LINE_H = fontSize * 1.4;
        return true;
    }

    /** Persist the current font size to etc/config.properties. */
    public void persistFontSize() {
        ConfigManager.setProperty(SSH_FONT_SIZE_KEY, String.valueOf(fontSize));
    }

    /** Request a deferred draw. Coalesces rapid write() calls to prevent
     *  the blink timer rendering a partially-updated screen during full-screen
     *  program refreshes (top, nmon, etc.). */
    public void requestDraw() {
        if (!drawPending) {
            drawPending = true;
            Platform.runLater(() -> {
                drawPending = false;
                draw();
                if (afterDraw != null) afterDraw.run();
            });
        }
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

    public void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setFont(FONT);
        int sr = buf.scrollOff, er = Math.min(buf.buffer.size(), sr + buf.rows);
        boolean hs = buf.selStartRow >= 0 && (buf.selStartRow != buf.selEndRow || buf.selStartCol != buf.selEndCol);
        int mr = hs ? Math.min(buf.selStartRow, buf.selEndRow) : -1;
        int Mr = hs ? Math.max(buf.selStartRow, buf.selEndRow) : -1;
        int sl = 0, sr2 = 0;
        if (hs) {
            if (buf.selStartRow == buf.selEndRow) {
                sl = Math.min(buf.selStartCol, buf.selEndCol);
                sr2 = Math.max(buf.selStartCol, buf.selEndCol);
            } else {
                sl = mr == buf.selStartRow ? buf.selStartCol : buf.selEndCol;
                sr2 = Mr == buf.selEndRow ? buf.selEndCol : buf.selStartCol;
                if (sl > sr2) { int t = sl; sl = sr2; sr2 = t; }
            }
        }
        for (int r = sr; r < er && r < buf.buffer.size(); r++) {
            int sy = r - sr;
            double y = sy * LINE_H;
            java.util.List<Cell> rowCells = buf.buffer.get(r);
            for (int col = 0; col < rowCells.size(); col++) {
                Cell cell = rowCells.get(col);
                if (cell.ch == '\0') continue;
                boolean isFw = TerminalBuffer.isFullwidth(cell.ch);
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
        if (buf.cursorShown && cursorVis && focused) {
            int vr = buf.curRow - buf.scrollOff;
            if (vr >= 0 && vr < buf.rows) {
                double cx = buf.curCol * CHAR_W, cy = vr * LINE_H + LINE_H * 0.2;
                double cursorHeight = LINE_H * 0.8;
                char atCursor = (buf.curRow < buf.buffer.size() && buf.curCol < buf.buffer.get(buf.curRow).size())
                        ? buf.buffer.get(buf.curRow).get(buf.curCol).ch : ' ';
                double cursorW = TerminalBuffer.isFullwidth(atCursor) ? CHAR_W * 2 : CHAR_W;
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
}
