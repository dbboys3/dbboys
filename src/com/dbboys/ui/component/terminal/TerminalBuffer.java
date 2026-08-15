package com.dbboys.ui.component.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal screen model: the cell buffer with scrollback history, cursor and
 * SGR state, soft-wrap tracking, DECSTBM scroll region, the alternate screen
 * and the viewport (scroll offset). Pure model — escape-sequence parsing lives
 * in {@link TerminalEmulator}, canvas painting in {@link TerminalRenderer}.
 */
public class TerminalBuffer {

    // ---- Terminal cell with per-character SGR attributes ----
    /** A single character cell storing both the glyph and its SGR styling. */
    static class Cell {
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
    static class Row extends ArrayList<Cell> {
        boolean wrapped;
    }

    /** Hook into the controller's session logging: the buffer reports structural
     *  events (hard newlines, row insertions/removals above the log mark, reflow
     *  re-anchors) so the log file can mirror the screen. Set only while logging. */
    public interface SessionLogListener {
        /** A hard newline finalized every logical line up to (and including) endRow. */
        void finalizeThrough(int endRow);
        /** A row was inserted at index at; the log mark shifts down when it is at/above it. */
        void rowInsertedAboveMark(int at);
        /** The row at index at was removed; the log mark shifts up when it is above it. */
        void rowRemovedAboveMark(int at);
        /** Re-anchor the log mark to a logical-line start (reflow / alt-screen exit). */
        void reanchorMark(int logicalLineStartRow);
    }

    final List<List<Cell>> buffer = new ArrayList<>();
    int cols = 80, rows = 24;
    int curCol, curRow;
    int selStartCol = -1, selStartRow = -1, selEndCol = -1, selEndRow = -1;
    int sgrFg = 37, sgrBg = 40;
    boolean sgrReverse, sgrBold, sgrUnderline;
    int sgrExtFg = -1, sgrExtBg = -1; // 256-color extended colors
    boolean cursorShown = true; // DECTCEM
    boolean decawm = true; // DECAWM (?7h/?7l): auto-wrap at the last column
    int scrollTop, scrollBottom = -1; // DECSTBM scroll region
    boolean originMode; // DECOM
    boolean pendingWrap; // auto-wrap happened, skip next
    boolean wrapPendingEraseSuppress; // suppress eraseEOL on wrap
    int savedCurCol, savedCurRow; // DECSC/DECRC
    char g0Charset = 'B';  // G0 charset: 'B'=ASCII, '0'=DEC Special Graphics
    char g1Charset = 'B';  // G1 charset
    boolean useG1;          // true when SO (^N) active, using G1
    int savedSgrFg, savedSgrBg;
    boolean savedSgrReverse, savedSgrBold, savedSgrUnderline;
    int scrollOff, maxScroll = 5000;
    boolean scrollLock;
    List<List<Cell>> altSavedBuffer;
    int altSavedCurCol, altSavedCurRow, altSavedScrollOff, altSavedCols, altSavedRows;
    boolean inAltScreen; // whether alternate screen buffer (?1049h) is active
    private SessionLogListener logListener;

    public TerminalBuffer() {
        buffer.add(new Row());
    }

    /** Attach/detach the session-log hook (null while logging is off). */
    public void setSessionLogListener(SessionLogListener listener) {
        this.logListener = listener;
    }

    // ---- Accessors for controller/renderer wiring ----
    public int size() { return buffer.size(); }
    public int getCols() { return cols; }
    public void setCols(int cols) { this.cols = cols; }
    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }
    public int getCurRow() { return curRow; }
    public int getScrollOff() { return scrollOff; }
    public void setScrollOff(int scrollOff) { this.scrollOff = scrollOff; }
    public boolean isScrollLock() { return scrollLock; }
    public void setScrollLock(boolean scrollLock) { this.scrollLock = scrollLock; }
    public int getScrollTop() { return scrollTop; }
    public void setScrollTop(int scrollTop) { this.scrollTop = scrollTop; }
    public int getScrollBottom() { return scrollBottom; }
    public void setScrollBottom(int scrollBottom) { this.scrollBottom = scrollBottom; }
    public boolean isInAltScreen() { return inAltScreen; }
    public boolean isCursorShown() { return cursorShown; }
    public void setCursorShown(boolean cursorShown) { this.cursorShown = cursorShown; }
    public int getSelStartCol() { return selStartCol; }
    public int getSelStartRow() { return selStartRow; }
    public int getSelEndCol() { return selEndCol; }
    public int getSelEndRow() { return selEndRow; }
    public void setSelection(int startCol, int startRow, int endCol, int endRow) {
        selStartCol = startCol; selStartRow = startRow;
        selEndCol = endCol; selEndRow = endRow;
    }
    public void setSelectionEnd(int endCol, int endRow) {
        selEndCol = endCol; selEndRow = endRow;
    }
    /** Drop the current selection (e.g. because input or a screen switch made it stale). */
    public void clearSelection() {
        selStartCol = selEndCol = selStartRow = selEndRow = -1;
    }

    /** Top buffer row of the visible page (the page sits below any scrollback history). */
    int pageTop() { return Math.max(0, buffer.size() - rows); }

    void nl() {
        // Bottom margin must be computed before ensureBuf() may grow the buffer
        int effectiveBottom = scrollBottom >= 0 ? scrollBottom : pageTop() + rows - 1;
        if (!scrollLock && curRow == effectiveBottom && scrollBottom >= 0 && scrollTop < effectiveBottom) {
            // DECSTBM scroll region: scroll within region, discarding top line.
            // No buffer growth — a row past the screen bottom would become
            // reachable by jumpToBottom() and show up as a stray blank line.
            scrollRegionUp(scrollTop, effectiveBottom);
            return;
        }
        if (logListener != null) logListener.finalizeThrough(curRow); // hard newline: the logical line ending here is final
        curRow++;
        ensureBuf(curRow);
        while (buffer.size() > maxScroll) { buffer.remove(0); curRow--; scrollOff = Math.max(0, scrollOff - 1); if (logListener != null) logListener.rowRemovedAboveMark(0); }
        if (inAltScreen) {
            // Alt screen is a fixed page: never grow the cursor or buffer past the bottom row
            if (curRow > rows - 1) curRow = rows - 1;
            while (buffer.size() > rows) buffer.remove(buffer.size() - 1);
        } else if (!scrollLock && curRow - scrollOff >= rows) {
            // Normal mode: advance viewport, preserve history in buffer
            scrollOff = curRow - rows + 1;
        }
    }

    /** Drop the selection when the content of buffer row r is about to change
     *  under it (overwrite or erase). Row-level granularity: full-screen apps
     *  (top, nmon) repaint whole lines on every refresh, so a hit on the row
     *  is enough to know the highlight would go stale. */
    private void dropSelectionIfRowHit(int r) {
        if (selStartRow < 0) return;
        if (r >= Math.min(selStartRow, selEndRow) && r <= Math.max(selStartRow, selEndRow))
            selStartCol = selEndCol = selStartRow = selEndRow = -1;
    }

    /** Drop the selection when any buffer row in [lo, hi] is about to change. */
    private void dropSelectionIfRangeHit(int lo, int hi) {
        if (selStartRow < 0) return;
        if (Math.min(selStartRow, selEndRow) <= hi && Math.max(selStartRow, selEndRow) >= lo)
            selStartCol = selEndCol = selStartRow = selEndRow = -1;
    }

    void put(char c) {
        if (pendingWrap) {
            // Deferred wrap (xterm semantics): the previous character filled the
            // last column, but the line wrap only happens now that the next
            // printable character arrives. This is what keeps full-screen apps
            // (top, nmon) from scrolling the first line away / growing phantom
            // rows when a frame ends on a full-width line.
            pendingWrap = false; wrapPendingEraseSuppress = false;
            curCol = 0;
            int effectiveBottom = scrollBottom >= 0 ? scrollBottom : pageTop() + rows - 1;
            if (!scrollLock && curRow == effectiveBottom && scrollBottom >= 0 && scrollTop < effectiveBottom) {
                // Wrap at the region's bottom margin: scroll within the region,
                // discarding the top line; the buffer does not grow
                markWrapped(curRow); // the wrapped row continues onto the fresh bottom line
                scrollRegionUp(scrollTop, effectiveBottom);
            } else {
                boolean atMargin = curRow == effectiveBottom;
                curRow++;
                markWrapped(curRow - 1); // soft wrap: the row just left continues onto the next one
                if (inAltScreen) {
                    // Alt screen is a fixed page: never grow the cursor or buffer past the bottom row
                    if (curRow > rows - 1) curRow = rows - 1;
                    while (buffer.size() > rows) buffer.remove(buffer.size() - 1);
                } else if (!scrollLock && atMargin && curRow > effectiveBottom) {
                    // Normal mode: advance viewport, preserve history in buffer
                    scrollOff = curRow - rows + 1;
                } else if (!scrollLock && curRow - scrollOff >= rows) {
                    scrollOff = curRow - rows + 1;
                }
            }
        } else {
            wrapPendingEraseSuppress = false;
        }
        dropSelectionIfRowHit(curRow); // overwriting a selected cell stale the highlight
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

    List<Cell> ensureBuf(int r) {
        while (buffer.size() <= r) buffer.add(new Row());
        return buffer.get(r);
    }

    public String line(int r) {
        if (r >= buffer.size()) return "";
        List<Cell> row = buffer.get(r);
        StringBuilder sb = new StringBuilder(row.size());
        for (Cell c : row) {
            if (c.ch != '\0') sb.append(c.ch);
        }
        return sb.toString();
    }

    /** Pin the viewport to the bottom and release the scroll lock (typing means
     *  the user wants to follow output again).
     *  @return true when the scroll offset actually changed (caller redraws). */
    public boolean jumpToBottom() {
        int maxOff = inAltScreen ? 0 : Math.max(0, buffer.size() - rows); // alt screen has no scrollback
        scrollLock = false;
        if (scrollOff != maxOff) {
            scrollOff = maxOff;
            return true;
        }
        return false;
    }

    /** True if buffer row r ends in a soft (auto) wrap and continues onto row r+1. */
    public boolean isWrapped(int r) {
        List<Cell> row = buffer.get(r);
        return row instanceof Row && ((Row) row).wrapped;
    }

    /** Mark buffer row r as soft-wrapped (it continues onto the next row). */
    void markWrapped(int r) {
        if (r >= 0 && r < buffer.size() && buffer.get(r) instanceof Row) {
            ((Row) buffer.get(r)).wrapped = true;
        }
    }

    /** Re-wrap all buffer content to a new column count. Rows linked by soft wraps
     *  are rejoined into their logical line and wrapped at the new width; hard
     *  newlines are preserved. Cursor, scroll offset and the vertical scrollbar
     *  then adapt to the resulting row count. Skipped on the alternate screen
     *  (full-screen apps redraw themselves after SIGWINCH). */
    public void reflowBuffer(int newCols) {
        if (inAltScreen || buffer.isEmpty() || newCols < 2) return;
        // Cursor position as a linear cell offset within its logical line
        int cur = Math.min(curRow, buffer.size() - 1);
        int curLogStart = cur;
        while (curLogStart > 0 && isWrapped(curLogStart - 1)) curLogStart--;
        // Rows above the cursor's logical line are final: log them before the
        // rebuild, then re-anchor the mark to that line's new index below
        if (logListener != null) logListener.finalizeThrough(curLogStart - 1);
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
        // Re-anchor the session-log mark to the cursor's logical line (rows above
        // it were logged before the rebuild)
        if (logListener != null) logListener.reanchorMark(logicalLineStart(curRow));
    }

    /** First buffer row of the logical line containing row r (follows soft wraps up). */
    public int logicalLineStart(int r) {
        r = Math.min(r, buffer.size() - 1);
        while (r > 0 && isWrapped(r - 1)) r--;
        return r;
    }

    void saveCursor() {
        savedCurCol = curCol; savedCurRow = curRow;
        savedSgrFg = sgrFg; savedSgrBg = sgrBg;
        savedSgrReverse = sgrReverse; savedSgrBold = sgrBold;
        savedSgrUnderline = sgrUnderline;
    }

    void restoreCursor() {
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

    void reverseIndex() {
        if (curRow == scrollTop) {
            insertLine(scrollTop);
        } else {
            curRow = Math.max(0, curRow - 1);
        }
    }

    void indexDown() {
        int bottom = scrollBottom >= 0 ? scrollBottom : pageTop() + rows - 1;
        if (curRow == bottom) {
            scrollRegionUp(scrollTop, bottom);
        } else {
            curRow = Math.min(bottom, curRow + 1);
        }
    }

    void insertLine(int at) {
        int bottom = scrollBottom >= 0 ? scrollBottom : scrollTop + rows - 1;
        ensureBuf(bottom);
        buffer.add(at, new Row());
        if (logListener != null && !inAltScreen) logListener.rowInsertedAboveMark(at);
        if (buffer.size() > bottom + 1) { buffer.remove(bottom + 1); if (logListener != null && !inAltScreen) logListener.rowRemovedAboveMark(bottom + 1); } // drop the line pushed past the margin
    }

    /** Scroll [top, bottom] up by one line: discard the top line and insert a blank
     *  line at the bottom. Lines below the region are preserved (remove+clear would
     *  clobber the first line below the region, e.g. vim's status line). */
    void scrollRegionUp(int top, int bottom) {
        if (top < 0 || bottom < top || top >= buffer.size()) return;
        buffer.remove(top);
        if (logListener != null && !inAltScreen) logListener.rowRemovedAboveMark(top);
        int addAt = Math.min(bottom, buffer.size());
        buffer.add(addAt, new Row());
        if (logListener != null && !inAltScreen) logListener.rowInsertedAboveMark(addAt);
    }

    void deleteLines(int from, int count) {
        for (int i = 0; i < count && from < buffer.size(); i++) {
            buffer.remove(from);
            if (logListener != null && !inAltScreen) logListener.rowRemovedAboveMark(from);
        }
    }

    /** Enter the alternate screen (?1049h): save the main buffer, cursor, SGR
     *  state and viewport, then switch to a fresh fixed-size page. */
    void enterAltScreen() {
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
        // Selection rows refer to the main screen, not the new alt page
        selStartCol = selEndCol = selStartRow = selEndRow = -1;
    }

    /** Exit the alternate screen (?1049l): restore the saved main buffer, cursor
     *  and viewport. Also invoked on disconnect, so a full-screen app (nmon, vi)
     *  still running when the connection drops does not hide the shell scrollback
     *  — and its scrollbar — behind a stale alt-screen frame. */
    public void exitAltScreen() {
        if (!inAltScreen) return;
        inAltScreen = false;
        // Selection rows refer to the alt screen just discarded
        selStartCol = selEndCol = selStartRow = selEndRow = -1;
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
        // The main buffer was replaced wholesale while away: re-anchor the
        // session-log mark to the restored cursor line instead of a stale index
        if (logListener != null) logListener.reanchorMark(logicalLineStart(curRow));
    }

    public void clearBuffer() {
        buffer.clear(); buffer.add(new Row());
        curCol = curRow = 0;
        pendingWrap = false;
        selStartCol = selEndCol = selStartRow = selEndRow = -1; // selection no longer maps to rows
    }

    /** Erase the visible page (the last {@code rows} lines), preserving scrollback history. */
    void eraseVisiblePage() {
        dropSelectionIfRangeHit(pageTop(), buffer.size() - 1);
        for (int r = pageTop(); r < buffer.size(); r++) buffer.get(r).clear();
        pendingWrap = false;
    }

    void eraseEOL() {
        if (wrapPendingEraseSuppress && curCol == 0) { wrapPendingEraseSuppress = false; return; }
        if (curRow >= buffer.size()) return; // nothing to erase beyond the buffer (never grow it here)
        dropSelectionIfRowHit(curRow);
        List<Cell> ln = buffer.get(curRow);
        for (int i = curCol; i < ln.size(); i++) ln.get(i).reset();
    }

    void eraseBOL() {
        if (curRow >= buffer.size()) return; // nothing to erase beyond the buffer (never grow it here)
        dropSelectionIfRowHit(curRow);
        List<Cell> ln = buffer.get(curRow);
        // Blank the cells in place: removing them would shift the rest of the line left
        int end = Math.min(curCol, ln.size() - 1);
        for (int i = 0; i <= end; i++) ln.get(i).reset();
    }

    void eraseLine() {
        if (curRow < buffer.size()) {
            dropSelectionIfRowHit(curRow);
            buffer.get(curRow).clear();
        }
    }

    void eraseEOD() {
        dropSelectionIfRangeHit(curRow, buffer.size() - 1);
        eraseEOL();
        for (int r = curRow + 1; r < buffer.size(); r++) buffer.get(r).clear();
    }

    void eraseDOS() {
        dropSelectionIfRangeHit(0, curRow);
        for (int r = 0; r < curRow && r < buffer.size(); r++) buffer.get(r).clear();
        eraseBOL();
    }

    public String selectedText() {
        if (selStartRow < 0 || selEndRow < 0) return "";
        int sr = Math.min(selStartRow, selEndRow), er = Math.max(selStartRow, selEndRow);
        int sc = selStartCol, ec = selEndCol;
        if (selStartRow > selEndRow || (selStartRow == selEndRow && selStartCol > selEndCol)) { sc = selEndCol; ec = selStartCol; }
        // Multi-row: sc is the column on row sr, ec the column on row er —
        // columns on different rows are independent, do NOT order them.
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

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- CJK fullwidth detection ----
    /**
     * Returns true if the character is a CJK fullwidth character that
     * occupies 2 columns in a terminal.
     */
    public static boolean isFullwidth(char ch) {
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

    /** Map DEC Special Graphics (line drawing) characters to Unicode. */
    static char mapDecSpecial(char c) {
        switch (c) {
            case 'j': return '┘'; case 'k': return '┐'; case 'l': return '┌';
            case 'm': return '└'; case 'n': return '┼'; case 'q': return '─';
            case 't': return '├'; case 'u': return '┤'; case 'v': return '┴';
            case 'w': return '┬'; case 'x': return '│';
            default: return c;
        }
    }
}
