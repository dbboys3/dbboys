package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.JschUtil;
import com.dbboys.ssh.SshConnect;
import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for the SSH terminal tab (SshTab.fxml).
 * Uses raw keystroke forwarding: every keystroke is sent immediately
 * to the remote shell, and the display is driven entirely by remote
 * echo — matching mainstream SSH clients (PuTTY, SecureCRT, Termius).
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);

    // ---- ANSI / VT100 handling ----

    private static final Font TERMINAL_FONT = Font.font("Consolas", 13);
    private static final double CHAR_WIDTH;
    private static final double LINE_HEIGHT;
    static {
        Text m = new Text("W");
        m.setFont(TERMINAL_FONT);
        CHAR_WIDTH = m.getLayoutBounds().getWidth();
        LINE_HEIGHT = 13 * 1.4;
    }

    // ---- FXML injections ----

    @FXML public CustomInlineCssTextArea terminalArea;
    @FXML public Button connectButton;
    @FXML public Button disconnectButton;
    @FXML public Label connectionLabel;
    @FXML public VBox sshTab;
    @FXML public VirtualizedScrollPane<CustomInlineCssTextArea> terminalScrollPane;

    // ---- State ----

    private SshConnect sshConnect;
    private Session session;
    private ChannelShell shellChannel;
    private OutputStream shellOut;
    private final StringProperty connectStatus = new SimpleStringProperty();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readThread;

    // ---- Initialisation ----

    public void initialize() {
        connectButton.setGraphic(IconFactory.group(IconPaths.SSH_CONNECT, 0.65, Color.GREEN));
        disconnectButton.setGraphic(IconFactory.group(IconPaths.SSH_DISCONNECT, 0.65, Color.RED));
        connectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.connect", "Connect")));
        disconnectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.disconnect", "Disconnect")));
        disconnectButton.setDisable(true);

        connectButton.setOnAction(e -> doConnect());
        disconnectButton.setOnAction(e -> doDisconnect());

        terminalArea.setEditable(true);
        terminalArea.setStyle(
                "-fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 13px;");

        setupKeyHandling();
        setupMouseHandling();

        terminalArea.widthProperty().addListener((obs, o, n) -> updatePtySize());
        terminalArea.heightProperty().addListener((obs, o, n) -> updatePtySize());

        connectStatus.addListener((obs, o, n) -> connectionLabel.setText(n));
        connectStatus.set(I18n.t("ssh.tab.disconnected", "Disconnected"));
    }

    // ---- Keyboard — raw keystroke forwarding ----

    private void setupKeyHandling() {
        /*
         * KEY_TYPED — printable characters are sent to the remote shell and
         * never inserted locally. The remote echo builds the display.
         */
        terminalArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!running.get() || shellOut == null) return;
            String ch = event.getCharacter();
            if (ch == null || ch.isEmpty()) return;
            char c = ch.charAt(0);
            if (c < 0x20 || c == 0x7F) {
                event.consume();
                return;
            }
            // Snap caret to end before sending — user is typing now
            terminalArea.moveTo(terminalArea.getLength());
            sendToShell(ch);
            event.consume();
        });

        /*
         * KEY_PRESSED — non‑printable keys: arrows, Enter, Backspace, Ctrl‑X, etc.
         */
        terminalArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();

            if (!running.get() || shellOut == null) {
                if (event.isShortcutDown()
                        && (code == KeyCode.C || code == KeyCode.A)) {
                    return;   // allow copy / select‑all when disconnected
                }
                event.consume();
                return;
            }

            // Keep caret at end on every keystroke
            Platform.runLater(() -> terminalArea.moveTo(terminalArea.getLength()));

            // Ctrl+Shift+C/V — system clipboard
            if (event.isShortcutDown() && event.isShiftDown()) {
                if (code == KeyCode.C) { terminalArea.copy();   event.consume(); return; }
                if (code == KeyCode.V) { pasteClipboard();      event.consume(); return; }
            }

            // Ctrl+letter — terminal control chars
            if (event.isControlDown() && !event.isShiftDown()
                    && !event.isAltDown() && !event.isMetaDown()) {
                if (code == KeyCode.C && !terminalArea.getSelectedText().isEmpty()) {
                    terminalArea.copy(); event.consume(); return;
                }
                if (code == KeyCode.V) { pasteClipboard(); event.consume(); return; }
                sendControlByte(code);
                event.consume();
                return;
            }

            // Special / navigation keys
            if (sendSpecialKey(code)) { event.consume(); return; }

            // Printable keys (let KEY_TYPED handle them)
            if (code.isLetterKey() || code.isDigitKey()
                    || code == KeyCode.SPACE || isPunctuationKey(code)) {
                return;
            }

            event.consume();
        });
    }

    private boolean sendSpecialKey(KeyCode code) {
        switch (code) {
            case ENTER:      sendToShell("\n");         return true;
            case BACK_SPACE: sendToShell("");     return true;  // DEL
            case TAB:        sendToShell("\t");         return true;
            case ESCAPE:     sendToShell("");     return true;
            case UP:         sendToShell("[A");   return true;
            case DOWN:       sendToShell("[B");   return true;
            case RIGHT:      sendToShell("[C");   return true;
            case LEFT:       sendToShell("[D");   return true;
            case HOME:       sendToShell("[H");   return true;
            case END:        sendToShell("[F");   return true;
            case DELETE:     sendToShell("[3~");  return true;
            case PAGE_UP:    sendToShell("[5~");  return true;
            case PAGE_DOWN:  sendToShell("[6~");  return true;
            case F1:  sendToShell("OP");   return true;
            case F2:  sendToShell("OQ");   return true;
            case F3:  sendToShell("OR");   return true;
            case F4:  sendToShell("OS");   return true;
            case F5:  sendToShell("[15~"); return true;
            case F6:  sendToShell("[17~"); return true;
            case F7:  sendToShell("[18~"); return true;
            case F8:  sendToShell("[19~"); return true;
            case F9:  sendToShell("[20~"); return true;
            case F10: sendToShell("[21~"); return true;
            case F11: sendToShell("[23~"); return true;
            case F12: sendToShell("[24~"); return true;
            default:   return false;
        }
    }

    private boolean isPunctuationKey(KeyCode code) {
        return code == KeyCode.QUOTE || code == KeyCode.BACK_QUOTE
            || code == KeyCode.MINUS || code == KeyCode.EQUALS
            || code == KeyCode.SLASH || code == KeyCode.BACK_SLASH
            || code == KeyCode.SEMICOLON || code == KeyCode.PERIOD
            || code == KeyCode.COMMA || code == KeyCode.OPEN_BRACKET
            || code == KeyCode.CLOSE_BRACKET;
    }

    private void sendControlByte(KeyCode code) {
        int v;
        switch (code) {
            // @formatter:off
            case A: v=0x01; break; case B: v=0x02; break; case C: v=0x03; break;
            case D: v=0x04; break; case E: v=0x05; break; case F: v=0x06; break;
            case G: v=0x07; break; case H: v=0x08; break; case I: v=0x09; break;
            case J: v=0x0A; break; case K: v=0x0B; break; case L: v=0x0C; break;
            case M: v=0x0D; break; case N: v=0x0E; break; case O: v=0x0F; break;
            case P: v=0x10; break; case Q: v=0x11; break; case R: v=0x12; break;
            case S: v=0x13; break; case T: v=0x14; break; case U: v=0x15; break;
            case V: v=0x16; break; case W: v=0x17; break; case X: v=0x18; break;
            case Y: v=0x19; break; case Z: v=0x1A; break;
            // @formatter:on
            default: return;
        }
        sendToShell(new String(new byte[]{(byte) v}));
    }

    private void pasteClipboard() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasString()) {
            String text = cb.getString();
            if (text != null && !text.isEmpty()) sendToShell(text);
        }
    }

    private void sendToShell(String text) {
        if (shellOut == null || !running.get()) return;
        try {
            shellOut.write(text.getBytes("UTF-8"));
            shellOut.flush();
        } catch (IOException e) {
            log.error("Failed to send data to shell", e);
        }
    }

    // ---- Mouse ----

    /**
     * Record caret position on press to distinguish click (no movement)
     * from drag-select (movement). A plain click is undone so the caret
     * stays pinned at the live prompt; a drag keeps the selection.
     */
    private int mousePressCaretPos = -1;

    private void setupMouseHandling() {
        terminalArea.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            terminalArea.requestFocus();
            mousePressCaretPos = terminalArea.getCaretPosition();
        });

        terminalArea.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
            int newPos = terminalArea.getCaretPosition();
            if (mousePressCaretPos >= 0 && newPos == mousePressCaretPos) {
                // Plain click, no drag — snap back to end
                Platform.runLater(() -> terminalArea.moveTo(terminalArea.getLength()));
            }
            mousePressCaretPos = -1;
        });
    }

    // ---- Connection management ----

    public void init(SshConnect sc) {
        this.sshConnect = sc;
        connectStatus.set(sc.getUsername() + "@" + sc.getHost() + ":" + sc.getPort());
        doConnect();
    }

    private void doConnect() {
        if (sshConnect == null) return;
        connectButton.setDisable(true);
        connectStatus.set(I18n.t("ssh.tab.connecting", "Connecting..."));
        appendTerminal("Connecting to " + sshConnect.getUsername() + "@"
                + sshConnect.getHost() + ":" + sshConnect.getPort() + "...\n");

        AppExecutor.runAsync(() -> {
            try {
                session = JschUtil.getSshSession(sshConnect);
                shellChannel = (ChannelShell) session.openChannel("shell");
                shellChannel.setPty(true);
                shellChannel.setPtyType("xterm-256color");

                shellChannel.connect();
                shellOut = shellChannel.getOutputStream();
                InputStream shellIn = shellChannel.getInputStream();

                Platform.runLater(() -> updatePtySize());

                running.set(true);
                readThread = new Thread(() -> readShellOutput(shellIn), "ssh-shell-reader");
                readThread.setDaemon(true);
                readThread.start();

                Platform.runLater(() -> {
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    terminalArea.requestFocus();
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connected", "Connected") + "]");
                    appendTerminal(I18n.t("ssh.tab.connected", "Connected") + "\n");
                });
            } catch (Exception ex) {
                log.error("SSH connect failed", ex);
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    disconnectButton.setDisable(true);
                    connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                            + ":" + sshConnect.getPort() + " ["
                            + I18n.t("ssh.tab.connect_failed", "Connect Failed") + "]");
                    appendTerminal("[ERROR] " + ex.getMessage() + "\n");
                });
            }
        });
    }

    private void doDisconnect() {
        running.set(false);
        if (readThread != null) readThread.interrupt();
        if (shellChannel != null && shellChannel.isConnected()) shellChannel.disconnect();
        JschUtil.disconnectSession(session);
        session = null;
        shellChannel = null;
        shellOut = null;
        connectButton.setDisable(false);
        disconnectButton.setDisable(true);
        if (sshConnect != null) {
            connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                    + ":" + sshConnect.getPort() + " ["
                    + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
        }
        appendTerminal("\n--- " + I18n.t("ssh.tab.disconnected", "Disconnected") + " ---\n");
    }

    // ---- Shell output — streaming read ----

    private void readShellOutput(InputStream shellIn) {
        byte[] buf = new byte[8192];
        try {
            int len;
            while (running.get() && (len = shellIn.read(buf)) != -1) {
                String output = new String(buf, 0, len, "UTF-8");
                Platform.runLater(() -> processOutput(output));
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("Shell read error", e);
                Platform.runLater(() -> appendTerminal("\n[ERROR] " + e.getMessage() + "\n"));
            }
        } finally {
            if (running.getAndSet(false)) {
                Platform.runLater(() -> {
                    disconnectButton.setDisable(true);
                    connectButton.setDisable(false);
                    if (sshConnect != null) {
                        connectStatus.set(sshConnect.getUsername() + "@" + sshConnect.getHost()
                                + ":" + sshConnect.getPort() + " ["
                                + I18n.t("ssh.tab.disconnected", "Disconnected") + "]");
                    }
                });
            }
            JschUtil.disconnectSession(session);
            session = null;
            shellChannel = null;
            shellOut = null;
        }
    }

    // ---- Process output — state-machine walker operating directly on text area ----

    /**
     * Process raw shell output as a stream, applying VT100 editing commands
     * directly against {@code terminalArea} so that {@code \e[K}, {@code \b},
     * and {@code \r} have the correct visual effect.
     */
    private void processOutput(String raw) {
        // Append-mode: write printable characters directly, but intercept each
        // control byte / escape sequence and mutate the text area accordingly.
        StringBuilder accum = new StringBuilder(128);
        for (int i = 0, n = raw.length(); i < n; i++) {
            char c = raw.charAt(i);
            if (c == 0x1B) {                 // ESC — starts a control sequence
                flushAccum(accum);
                i = consumeEscape(raw, i + 1);
            } else if (c == '\b') {         // BS
                flushAccum(accum);
                doBackspace();
            } else if (c == '\r') {         // CR
                flushAccum(accum);
                // \r\n is handled by the \n path (next char)
                if (i + 1 < n && raw.charAt(i + 1) == '\n') {
                    accum.append('\n');
                    i++;  // skip the \n — write both as a single newline
                } else {
                    doCarriageReturn();
                }
            } else if (c == '\n') {         // LF (standalone — rare from PTY)
                accum.append('\n');
            } else if (c < 0x20 || c == 0x7F) {
                // Other control chars: drop silently
                flushAccum(accum);
            } else {
                accum.append(c);
            }
        }
        flushAccum(accum);
        scrollToBottom();
    }

    /** Flush accumulated printable text to the text area. */
    private void flushAccum(StringBuilder accum) {
        if (accum.length() > 0) {
            terminalArea.appendText(accum.toString());
            accum.setLength(0);
        }
    }

    /** Consume one ESC-prefixed VT100 sequence starting at raw[pos]. */
    private int consumeEscape(String raw, int pos) {
        if (pos >= raw.length()) return pos;
        char c = raw.charAt(pos);
        if (c == '[') {                     // CSI:  ESC [ ... final
            return consumeCSI(raw, pos + 1);
        } else if (c == ']') {              // OSC — skip until BEL or ST
            return skipOSC(raw, pos + 1);
        } else if (c == 'P' || c == 'X' || c == '^' || c == '_') {
            // DCS / SOS / PM / APC — skip until ST  ESC \
            return skipUntilST(raw, pos + 1);
        } else {
            // Single-char escape (e.g. ESC 7, ESC 8) — ignore
            return pos;  // consumed the single char
        }
    }

    /** Consume CSI sequence: raw[pos] is the first char after ESC[. */
    private int consumeCSI(String raw, int pos) {
        // Scan parameter bytes (digits, semicolons)
        while (pos < raw.length()) {
            char c = raw.charAt(pos);
            if (c >= '0' && c <= '9' || c == ';') {
                pos++;
            } else if (c >= '@' && c <= '~') {
                // Final byte — check command
                if (c == 'K') {
                    doEraseToEndOfLine();
                } else if (c == 'J') {
                    doEraseToEndOfDisplay();
                }
                // All other CSI commands (SGR colors, cursor movement, etc.)
                // are ignored — the text area renders them as nothing.
                return pos; // consumed the final byte
            } else {
                // Malformed — treat the whole thing as consumed
                return pos;
            }
        }
        return pos;
    }

    /** Skip OSC sequence (ESC ] ... BEL or ST). */
    private int skipOSC(String raw, int pos) {
        while (pos < raw.length()) {
            char c = raw.charAt(pos);
            if (c == 0x07) return pos;             // BEL — terminates
            if (c == 0x1B && pos + 1 < raw.length() && raw.charAt(pos + 1) == '\\')
                return pos + 1;                     // ST — terminates
            pos++;
        }
        return pos;
    }

    /** Skip DCS/SOS/PM/APC sequence (ESC P/X/^/_ ... ESC \) */
    private int skipUntilST(String raw, int pos) {
        while (pos < raw.length() - 1) {
            if (raw.charAt(pos) == 0x1B && raw.charAt(pos + 1) == '\\')
                return pos + 1;
            pos++;
        }
        return pos;
    }

    // ---- Text-area editing primitives ----

    /** Delete one character to the left of the cursor on the current line. */
    private void doBackspace() {
        int len = terminalArea.getLength();
        if (len <= 0) return;
        // Don't backspace past a newline
        String text = terminalArea.getText();
        if (len > 0 && text.charAt(len - 1) == '\n') return;
        terminalArea.deleteText(len - 1, len);
    }

    /** Erase from cursor (end of text) to end of current line. */
    private void doEraseToEndOfLine() {
        // In our simplified model where cursor == end of text, \e[K means
        // "erase from end to end" — a no-op. The real line-erase happens
        // in doCarriageReturn(), which deletes the current line before
        // new text is appended.
    }

    /** Erase from cursor to end of display. */
    private void doEraseToEndOfDisplay() {
        // Equivalent to deleting everything after the current line.
        int len = terminalArea.getLength();
        if (len <= 0) return;
        String text = terminalArea.getText();
        int lastNl = text.lastIndexOf('\n', len - 1);
        int lineStart = (lastNl == -1) ? 0 : lastNl + 1;
        if (lineStart < len) {
            terminalArea.deleteText(lineStart, len);
        }
    }

    /**
     * Handle bare CR (not part of \r\n):
     * rewind to start of current line so subsequent output overwrites it.
     *
     * <p>readline uses: {@code \r} (go to col 0), then new text, then
     * {@code \e[K} (erase any leftover chars from previous longer line).
     * We simulate this by deleting the current line's content so the text
     * area walks backwards, then appending flows as usual.
     */
    private void doCarriageReturn() {
        int len = terminalArea.getLength();
        if (len <= 0) return;
        String text = terminalArea.getText();
        int lastNl = text.lastIndexOf('\n', len - 1);
        int lineStart = (lastNl == -1) ? 0 : lastNl + 1;
        // Delete current line content — the new text (after \r) will
        // be appended by subsequent accumulate/flush steps.
        if (lineStart < len) {
            terminalArea.deleteText(lineStart, len);
        }
    }

    /** Always scroll to the very bottom. */
    private void scrollToBottom() {
        Platform.runLater(() -> {
            terminalArea.moveTo(terminalArea.getLength());
            if (terminalScrollPane != null) {
                terminalScrollPane.scrollYToPixel(Double.MAX_VALUE);
            }
        });
    }

    // ---- PTY resize ----

    private void updatePtySize() {
        if (shellChannel == null || !shellChannel.isConnected()) return;
        if (terminalArea.getWidth() <= 0 || terminalArea.getHeight() <= 0) return;
        try {
            int cols = Math.max(40, (int) (terminalArea.getWidth() / CHAR_WIDTH));
            int rows = Math.max(10, (int) (terminalArea.getHeight() / LINE_HEIGHT));
            shellChannel.setPtySize(cols, rows,
                    cols * (int) CHAR_WIDTH, rows * (int) LINE_HEIGHT);
        } catch (Exception e) {
            log.debug("Failed to update PTY size", e);
        }
    }

    // ---- Helpers ----

    private void appendTerminal(String text) {
        if (Platform.isFxApplicationThread()) {
            terminalArea.appendText(text);
            terminalArea.moveTo(terminalArea.getLength());
        } else {
            Platform.runLater(() -> {
                terminalArea.appendText(text);
                terminalArea.moveTo(terminalArea.getLength());
            });
        }
    }

    public void closeSession() {
        doDisconnect();
    }
}
