package com.dbboys.ui.component.terminal;

import com.dbboys.infra.config.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * ANSI/xterm escape-sequence interpreter: consumes decoded server output and
 * drives a {@link TerminalBuffer}. Also owns the convenience writers the
 * controller uses for local status/progress text. Visual side effects are
 * reported through {@link Listener}; this class has no JavaFX dependency.
 */
public class TerminalEmulator {
    private static final Logger log = LogManager.getLogger(TerminalEmulator.class);
    /** Dump raw terminal bytes (escapes shown) to the app log. Enable via
     *  -Ddbboys.term.rawlog=true or SSH_TERMINAL_RAWLOG=true in etc/config.properties. */
    private static final boolean RAW_LOG = Boolean.getBoolean("dbboys.term.rawlog")
            || Boolean.parseBoolean(ConfigManager.getProperty("SSH_TERMINAL_RAWLOG", "false"));

    /** Sink for the emulator's visual/activity side effects. */
    public interface Listener {
        /** Request a deferred draw. Coalesces rapid write() calls to prevent
         *  the blink timer rendering a partially-updated screen during full-screen
         *  program refreshes (top, nmon, etc.). */
        void onDrawRequest();
        /** Draw immediately (used by RIS full reset). */
        void onImmediateDraw();
        /** New server output arrived (not disconnect/status messages). */
        void onActivity();
    }

    final TerminalBuffer buf;
    private final Listener listener;
    private String pendingEsc;
    private boolean cursorKeysApp; // DECCKM: true=ESC OA, false=ESC [A

    public TerminalEmulator(TerminalBuffer buf, Listener listener) {
        this.buf = buf;
        this.listener = listener;
    }

    /** DECCKM cursor-key mode: true = application mode (ESC OA), false = normal (ESC [A). */
    public boolean isCursorKeysApp() { return cursorKeysApp; }

    public void write(String raw) {
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
            else if (c == '\b') { buf.wrapPendingEraseSuppress = false; if (buf.pendingWrap) { buf.pendingWrap = false; buf.curCol = buf.cols - 1; } else if (buf.curCol > 0) buf.curCol--; }
            else if (c == '\r') { buf.curCol = 0; buf.pendingWrap = false; buf.wrapPendingEraseSuppress = false; }
            else if (c == '\n') {
                // Deferred wrap: LF always moves down exactly one line, whether or
                // not the previous line filled the last column (xterm semantics)
                buf.pendingWrap = false;
                buf.wrapPendingEraseSuppress = false;
                buf.nl();
            }
            else if (c == 0x09) { buf.pendingWrap = false; buf.wrapPendingEraseSuppress = false; buf.curCol = ((buf.curCol / 8) + 1) * 8; if (buf.curCol >= buf.cols) { buf.curCol = 0; buf.pendingWrap = true; buf.wrapPendingEraseSuppress = true; } }
            else if (c == 0x0E) { buf.useG1 = true; } // SO - shift out, use G1 charset
            else if (c == 0x0F) { buf.useG1 = false; } // SI - shift in, use G0 charset
            else if (c == 0x7F) { if (buf.curCol > 0) buf.curCol--; } // DEL = backspace
            else if (c >= 0x20) buf.put(c);
        }
        if (isTopData) {
           // log.info("=== AFTER ===");
            dumpBuffer();
        }
        listener.onDrawRequest();
        if (!raw.isEmpty()) listener.onActivity();
    }

    /** Write local status text (connect/disconnect notices), then a newline. */
    public void status(String s) {
        for (char c : s.toCharArray()) {
            if (c == '\n') { buf.pendingWrap = false; buf.nl(); }
            else if (c == '\r') { buf.curCol = 0; buf.pendingWrap = false; }
            else buf.put(c);
        }
        if (!s.endsWith("\n") && !s.endsWith("\r\n")) buf.nl();
        listener.onDrawRequest();
    }

    /** Write status text in red, then restore default SGR. */
    public void statusRed(String s) {
        int saveFg = buf.sgrFg, saveBg = buf.sgrBg, saveExtFg = buf.sgrExtFg, saveExtBg = buf.sgrExtBg;
        boolean saveBold = buf.sgrBold, saveRev = buf.sgrReverse, saveUnder = buf.sgrUnderline;
        buf.sgrFg = 31; buf.sgrBg = 40; buf.sgrExtFg = -1; buf.sgrExtBg = -1;
        buf.sgrBold = false; buf.sgrReverse = false; buf.sgrUnderline = false;
        status(s);
        buf.sgrFg = saveFg; buf.sgrBg = saveBg; buf.sgrExtFg = saveExtFg; buf.sgrExtBg = saveExtBg;
        buf.sgrBold = saveBold; buf.sgrReverse = saveRev; buf.sgrUnderline = saveUnder;
    }

    /** Write status text in green, then restore default SGR. */
    public void statusGreen(String s) {
        int saveFg = buf.sgrFg, saveBg = buf.sgrBg, saveExtFg = buf.sgrExtFg, saveExtBg = buf.sgrExtBg;
        boolean saveBold = buf.sgrBold, saveRev = buf.sgrReverse, saveUnder = buf.sgrUnderline;
        buf.sgrFg = 32; buf.sgrBg = 40; buf.sgrExtFg = -1; buf.sgrExtBg = -1;
        buf.sgrBold = false; buf.sgrReverse = false; buf.sgrUnderline = false;
        status(s);
        buf.sgrFg = saveFg; buf.sgrBg = saveBg; buf.sgrExtFg = saveExtFg; buf.sgrExtBg = saveExtBg;
        buf.sgrBold = saveBold; buf.sgrReverse = saveRev; buf.sgrUnderline = saveUnder;
    }

    /** Write to terminal without trailing newline (for in-place progress). */
    public void progressStatus(String s) {
        for (char c : s.toCharArray()) {
            if (c == '\n') { buf.pendingWrap = false; buf.nl(); }
            else if (c == '\r') { buf.curCol = 0; buf.pendingWrap = false; }
            else buf.put(c);
        }
        listener.onDrawRequest();
    }

    // ---- ANSI ----
    private int esc(String s, int p, int e) {
        if (p >= e) return -1;
        char c = s.charAt(p);
        if (c == '[') { int r = csi(s, p + 1, e); return r < 0 ? -1 : r; }
        if (c == ']') { int r = osc(s, p + 1, e); return r < 0 ? -1 : r; }
        if (c == '(' || c == ')') { int r = consumeCharset(s, p + 1, e, c == '('); return r < 0 ? -1 : r; }
        // ESC 7 / ESC 8 — save/restore cursor (DECSC/DECRC)
        if (c == '7') { buf.saveCursor(); return p; }
        if (c == '8') { buf.restoreCursor(); return p; }
        // ESC M — reverse index (RI)
        if (c == 'M') { buf.reverseIndex(); return p; }
        // ESC D — index (IND, move down one line)
        if (c == 'D') { buf.indexDown(); return p; }
        // ESC E — next line (NEL)
        if (c == 'E') { buf.curCol = 0; buf.indexDown(); return p; }
        // ESC H — horizontal tab set
        if (c == 'H') return p;
        // ESC > — alternate keypad numeric; ESC = — alternate keypad application
        if (c == '>' || c == '=') return p;
        // ESC c — RIS (reset to initial state)
        // ESC O A/B/C/D -- SS3 cursor keys (when DECCKM is enabled)
        if (c == 'O' && p + 1 < e) {
            char oc = s.charAt(p + 1);
            switch (oc) {
                case 'A': buf.curRow = Math.max(buf.originMode ? buf.scrollTop : 0, buf.curRow - 1); return p + 1;
                case 'B': buf.curRow = Math.min(buf.buffer.isEmpty() ? 0 : buf.buffer.size() - 1, buf.curRow + 1); return p + 1;
                case 'C': buf.curCol = Math.min(buf.cols - 1, buf.curCol + 1); return p + 1;
                case 'D': buf.curCol = Math.max(0, buf.curCol - 1); return p + 1;
                case 'H': buf.curRow = 0; buf.curCol = 0; return p + 1;
                case 'F': buf.curRow = Math.max(0, buf.buffer.size() - 1); buf.curCol = 0; return p + 1;
            }
        }
        if (c == 'c') { resetTerminal(); return p; }
        return p;
    }

    private int consumeCharset(String s, int p, int e, boolean isG0) {
        if (p < e) { char cs = s.charAt(p); if (isG0) buf.g0Charset = cs; else buf.g1Charset = cs; return p; }
        return -1;
    }

    private void resetTerminal() {
        buf.buffer.clear(); buf.buffer.add(new TerminalBuffer.Row());
        buf.curCol = buf.curRow = buf.scrollOff = 0;
        buf.scrollTop = 0; buf.scrollBottom = -1; buf.originMode = false;
        buf.sgrFg = 37; buf.sgrBg = 40; buf.sgrReverse = buf.sgrBold = buf.sgrUnderline = false;
        buf.g0Charset = 'B'; buf.g1Charset = 'B'; buf.useG1 = false;
        buf.pendingWrap = false;
        buf.decawm = true; // RIS restores auto-wrap
        buf.selStartCol = buf.selEndCol = buf.selStartRow = buf.selEndRow = -1; // selection no longer maps to rows
        listener.onImmediateDraw();
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
                            if (ps.equals("25")) { buf.cursorShown = (c == 'h'); break; }
                            if (ps.equals("1")) { cursorKeysApp = (c == 'h'); break; }
                            if (ps.equals("7")) { buf.decawm = (c == 'h'); break; } // DECAWM auto-wrap
                            // DECSET ?1049h/?1049l -- alternate screen buffer
                            if (ps.equals("1049")) {
                                if (c == 'h') {
                                    buf.enterAltScreen();
                                } else {
                                    // Restore saved buffer
                                    buf.exitAltScreen();
                                }
                                break;
                            }
                            break;
                        case 'r': // DECSTBM — handled below in standard CSI
                            if (buf.inAltScreen && ps.isEmpty()) {
                                // ?r without params: restore default scroll region
                                buf.scrollTop = 0; buf.scrollBottom = buf.rows - 1;
                            }
                            break;
                        case 's': break; // DECSC
                        case 'u': break; // DECRC
                        case 'J':
                            if (ps.equals("2")) { buf.clearBuffer(); buf.scrollOff = 0; }
                            break;
                    }
                    return p;
                }
                switch (c) {
                    case 'K':
                        if (ps.isEmpty() || ps.equals("0")) buf.eraseEOL();
                        else if (ps.equals("1")) buf.eraseBOL();
                        else if (ps.equals("2")) buf.eraseLine();
                        break;
                    case 'J':
                        if (ps.isEmpty() || ps.equals("0")) buf.eraseEOD();
                        else if (ps.equals("1")) buf.eraseDOS();
                        else if (ps.equals("2")) {
                            if (buf.inAltScreen) {
                                buf.clearBuffer();
                                buf.scrollOff = 0;
                            } else {
                                // xterm semantics: ED 2 erases the visible page only,
                                // scrollback history above the page is preserved
                                buf.eraseVisiblePage();
                            }
                            if (!buf.inAltScreen) {
                                buf.scrollTop = 0; buf.scrollBottom = -1; buf.originMode = false;
                            }
                        }
                        break;
                    case 'm': sgr(ps); break;
                    case 'A': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; buf.curRow = Math.max(buf.originMode ? buf.scrollTop : 0, buf.curRow - n); } break;
                    case 'B': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; int maxR = buf.buffer.isEmpty() ? 0 : buf.buffer.size() - 1; buf.curRow = Math.min(maxR, buf.curRow + n); } break;
                    case 'C': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; buf.curCol = Math.min(buf.cols - 1, buf.curCol + n); } break;
                    case 'D': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; buf.curCol = Math.max(0, buf.curCol - n); } break;
                    case 'E': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; buf.curCol = 0; int maxR = buf.buffer.isEmpty() ? 0 : buf.buffer.size() - 1; buf.curRow = Math.min(maxR, buf.curRow + n); } break;
                    case 'F': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; buf.curCol = 0; buf.curRow = Math.max(buf.originMode ? buf.scrollTop : 0, buf.curRow - n); } break;
                    case 'G': case '`': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; buf.curCol = Math.min(buf.cols - 1, Math.max(0, n - 1)); } break;
                    case 'd': { int n = ps.isEmpty() ? 1 : Integer.parseInt(ps); buf.pendingWrap = false; buf.curRow = TerminalBuffer.clamp((buf.inAltScreen ? 0 : buf.pageTop()) + n - 1, 0, (buf.inAltScreen ? 0 : buf.pageTop()) + buf.rows - 1); break; }
                    case 'H': case 'f': {
                        String[] xy = ps.split(";");
                        int row = xy.length > 0 && !xy[0].isEmpty() ? Integer.parseInt(xy[0]) - 1 : 0;
                        int col = xy.length > 1 && !xy[1].isEmpty() ? Integer.parseInt(xy[1]) - 1 : 0;
                        boolean home = (row == 0 && col == 0);
                        if (buf.originMode && row >= 0) row += buf.scrollTop;
                        else if (!buf.inAltScreen) row += buf.pageTop(); // CUP addresses the visible page, not the scrollback (xterm semantics)
                        // Clamp into the visible screen: after SIGWINCH an app may still
                        // address rows of the previous geometry (e.g. nmon's exit CUP),
                        // which would otherwise land the cursor beyond the buffer
                        buf.curRow = TerminalBuffer.clamp(row, 0, (buf.inAltScreen ? 0 : buf.pageTop()) + buf.rows - 1);
                        buf.curCol = TerminalBuffer.clamp(col, 0, buf.cols - 1);
                        buf.pendingWrap = false;
                        // On home in normal screen, snap the viewport back to the page bottom
                        if (home && !buf.inAltScreen) { buf.scrollOff = Math.max(0, buf.buffer.size() - buf.rows); buf.scrollLock = false; }
                    } break;
                    case 'L': { // insert lines at current cursor row within scroll region
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int bottom = buf.scrollBottom < 0 ? Math.max(0, buf.buffer.size() - 1) : buf.scrollBottom;
                        int insAt = Math.max(buf.scrollTop, buf.curRow);
                        for (int i = 0; i < n; i++) {
                            buf.ensureBuf(bottom);
                            buf.buffer.add(insAt, new TerminalBuffer.Row());
                            if (buf.buffer.size() > bottom + 1) buf.buffer.remove(bottom + 1); // drop the line pushed past the margin
                        }
                    } break;
                    case 'M': { // delete lines from current cursor row within scroll region
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int bottom = buf.scrollBottom < 0 ? Math.max(0, buf.buffer.size() - 1) : buf.scrollBottom;
                        int delFrom = Math.max(buf.scrollTop, buf.curRow);
                        int removed = 0;
                        for (int i = 0; i < n && delFrom < buf.buffer.size() && delFrom <= bottom; i++) {
                            buf.buffer.remove(delFrom);
                            removed++;
                        }
                        // Blanks go at the region bottom: re-insert them just ahead of
                        // the first line below the region, so those lines end up back
                        // at their original rows instead of being pulled into the region
                        int insAt = Math.min(bottom + 1 - removed, buf.buffer.size());
                        for (int i = 0; i < removed; i++) buf.buffer.add(insAt + i, new TerminalBuffer.Row());
                    } break;
                    case 'P': { // delete characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        List<TerminalBuffer.Cell> ln = buf.ensureBuf(buf.curRow);
                        if (buf.curCol < ln.size()) {
                            for (int i = 0; i < n && buf.curCol < ln.size(); i++) ln.remove(buf.curCol);
                        }
                    } break;
                    case '@': { // insert characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        List<TerminalBuffer.Cell> ln = buf.ensureBuf(buf.curRow);
                        for (int i2 = 0; i2 < n; i2++) ln.add(buf.curCol, new TerminalBuffer.Cell());
                    } break;
                    case 'X': { // erase characters
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        List<TerminalBuffer.Cell> ln = buf.ensureBuf(buf.curRow);
                        int end = Math.min(ln.size(), buf.curCol + n);
                        while (ln.size() <= end) ln.add(new TerminalBuffer.Cell());
                        for (int i2 = buf.curCol; i2 < end; i2++) {
                            if (i2 < ln.size()) ln.get(i2).reset();
                        }
                    } break;
                    case 'Z': { // cursor backward tab (CBT)
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        buf.pendingWrap = false;
                        for (int i = 0; i < n; i++) buf.curCol = Math.max(0, ((buf.curCol - 1) / 8) * 8);
                    } break;
                    case 'b': { // REP: repeat the preceding graphic character n times
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int prevCol = buf.pendingWrap ? buf.cols - 1 : buf.curCol - 1;
                        char pc = (prevCol >= 0 && buf.curRow < buf.buffer.size() && prevCol < buf.buffer.get(buf.curRow).size())
                                ? buf.buffer.get(buf.curRow).get(prevCol).ch : ' ';
                        for (int i = 0; i < n; i++) buf.put(pc);
                    } break;
                    case 'S': { // scroll up (SU): region content moves up, blank lines appear at the bottom
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = buf.scrollTop, bottom = buf.scrollBottom < 0 ? Math.max(0, buf.buffer.size() - 1) : buf.scrollBottom;
                        for (int i3 = 0; i3 < n; i3++) { if (bottom >= top && top < buf.buffer.size()) { buf.buffer.remove(top); buf.buffer.add(Math.min(bottom, buf.buffer.size()), new TerminalBuffer.Row()); } }
                    } break;
                    case 'T': { // scroll down (SD): region content moves down, blank lines appear at the top
                        int n = ps.isEmpty() ? 1 : Integer.parseInt(ps);
                        int top = buf.scrollTop, bottom = buf.scrollBottom < 0 ? Math.max(0, buf.buffer.size() - 1) : buf.scrollBottom;
                        for (int i3 = 0; i3 < n; i3++) { if (bottom >= top && bottom < buf.buffer.size()) { buf.buffer.remove(bottom); buf.buffer.add(top, new TerminalBuffer.Row()); } }
                    } break;
                    case 'r': { // DECSTBM — set scroll region (page-relative, like xterm)
                        String[] sr_ = ps.split(";");
                        int base = buf.inAltScreen ? 0 : buf.pageTop();
                        int regionMax = base + buf.rows - 1; // clamp the region to the current screen
                        buf.scrollTop = Math.min(base + (sr_.length > 0 && !sr_[0].isEmpty() ? Math.max(0, Integer.parseInt(sr_[0]) - 1) : 0), regionMax);
                        buf.scrollBottom = sr_.length > 1 && !sr_[1].isEmpty() ? Math.min(base + Integer.parseInt(sr_[1]) - 1, regionMax) : -1;
                        buf.curRow = buf.scrollTop; buf.curCol = 0;
                        buf.pendingWrap = false;
                    } break;
                    case 'h': case 'l':
                        if (ps.equals("6")) buf.originMode = (c == 'h'); // DECOM
                        break;
                    case 's': buf.saveCursor(); break;
                    case 'u': buf.restoreCursor(); break;
                    case 'n': break; // DSR — ignore
                    case 'q': break; // DECSCUSR — ignore cursor style
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

    private void sgr(String ps) {
        if (ps.isEmpty()) { buf.sgrFg = 37; buf.sgrBg = 40; buf.sgrReverse = buf.sgrBold = buf.sgrUnderline = false; buf.sgrExtFg = -1; buf.sgrExtBg = -1; return; }
        String[] parts = ps.split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.equals("0")) {
                buf.sgrFg = 37; buf.sgrBg = 40; buf.sgrReverse = buf.sgrBold = buf.sgrUnderline = false; buf.sgrExtFg = -1; buf.sgrExtBg = -1;
                continue;
            }
            int n;
            try { n = Integer.parseInt(p); } catch (NumberFormatException x) { continue; }
            if (n == 38 && i + 2 < parts.length && parts[i+1].equals("5")) {
                try { buf.sgrExtFg = Integer.parseInt(parts[i+2]); }
                catch (NumberFormatException x) {}
                i += 2;
                continue;
            }
            if (n == 48 && i + 2 < parts.length && parts[i+1].equals("5")) {
                try { buf.sgrExtBg = Integer.parseInt(parts[i+2]); } catch (NumberFormatException x) {}
                i += 2;
                continue;
            }
            switch (n) {
                case 1: buf.sgrBold = true; break;
                case 2: buf.sgrBold = false; break;
                case 3: break;
                case 4: buf.sgrUnderline = true; break;
                case 5: case 6: break;
                case 7: buf.sgrReverse = true; break;
                case 22: buf.sgrBold = false; break;
                case 23: break;
                case 24: buf.sgrUnderline = false; break;
                case 25: break;
                case 27: buf.sgrReverse = false; break;
                case 30: buf.sgrFg=30; break; case 31: buf.sgrFg=31; break; case 32: buf.sgrFg=32; break; case 33: buf.sgrFg=33; break;
                case 34: buf.sgrFg=34; break; case 35: buf.sgrFg=35; break; case 36: buf.sgrFg=36; break; case 37: case 39: buf.sgrFg=37; break;
                case 90: buf.sgrFg=90; break; case 91: buf.sgrFg=91; break; case 92: buf.sgrFg=92; break; case 93: buf.sgrFg=93; break;
                case 94: buf.sgrFg=94; break; case 95: buf.sgrFg=95; break; case 96: buf.sgrFg=96; break; case 97: buf.sgrFg=97; break;
                case 40: buf.sgrBg=40; break; case 41: buf.sgrBg=41; break; case 42: buf.sgrBg=42; break; case 43: buf.sgrBg=43; break;
                case 44: buf.sgrBg=44; break; case 45: buf.sgrBg=45; break; case 46: buf.sgrBg=46; break; case 47: case 49: buf.sgrBg=40; break;
                case 100: buf.sgrBg=100; break; case 101: buf.sgrBg=101; break; case 102: buf.sgrBg=102; break; case 103: buf.sgrBg=103; break;
                case 104: buf.sgrBg=104; break; case 105: buf.sgrBg=105; break; case 106: buf.sgrBg=106; break; case 107: buf.sgrBg=107; break;
            }
        }
    }

    /** Dump the current buffer content to log. */
    private void dumpBuffer() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("cur=(%d,%d) wrap=%b scrollOff=%d rows=%d bufSize=%d\n",
                buf.curCol, buf.curRow, buf.pendingWrap, buf.scrollOff, buf.rows, buf.buffer.size()));
        int showRows = Math.min(buf.buffer.size(), buf.rows + 2);
        for (int r = Math.max(0, buf.scrollOff - 1); r < showRows; r++) {
            if (r >= buf.buffer.size()) break;
            String l = buf.line(r);
            String outline = l.length() > 80 ? l.substring(0, 80) + "..." : l;
            sb.append(String.format("  [%d] cells=%d |%s|\n", r,
                    r < buf.buffer.size() ? buf.buffer.get(r).size() : 0,
                    outline.replace(' ', '·')));
            if (r - buf.scrollOff + 1 > buf.rows + 2) break;
        }
        //log.info(sb.toString());
    }

    /** Make control chars visible for logging. */
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
}
