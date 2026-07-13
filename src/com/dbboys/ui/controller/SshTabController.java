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

import java.io.*;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for the SSH terminal tab (SshTab.fxml).
 * Uses raw keystroke forwarding: every keystroke is sent immediately
 * to the remote shell, and the display is driven entirely by remote
 * echo ¡ª matching mainstream SSH clients (PuTTY, SecureCRT, Termius).
 */
public class SshTabController {
    private static final Logger log = LogManager.getLogger(SshTabController.class);

    /** Strips ANSI/VT100 escape sequences from shell output. */
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\u001B\\[[\\d;]*[A-Za-z]" +           // CSI sequences  e.g. \e[32m, \e[K, \e[A
            "|\u001B\\][^\\u0007]*\\u0007" +        // OSC sequences terminated by BEL
            "|\u001B[PX^_].*?\u001B\\\\" +          // DCS / SOS / PM / APC (ST terminated)
            "|\u001B[^\\[\\]]"                      // single-char escapes  e.g. \e7, \e8
    );


    /** Font used for terminal display (must match the CSS style below). */
    private static final Font TERMINAL_FONT = Font.font("Consolas", 13);

    /** Estimated character width for PTY column calculation. */
    private static final double CHAR_WIDTH;

    /** Estimated line height for PTY row calculation. */
    private static final double LINE_HEIGHT;

    static {
        Text m = new Text("W");
        m.setFont(TERMINAL_FONT);
        CHAR_WIDTH = m.getLayoutBounds().getWidth();
        LINE_HEIGHT = 13 * 1.4;
    }
    @FXML
    public CustomInlineCssTextArea terminalArea;
    @FXML
    public Button connectButton;
    @FXML
    public Button disconnectButton;
    @FXML
    public Label connectionLabel;
    @FXML
    public VBox sshTab;

    private SshConnect sshConnect;
    private Session session;
    private ChannelShell shellChannel;
    private OutputStream shellOut;
    private final StringProperty connectStatus = new SimpleStringProperty();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readThread;

    public void initialize() {
        // Icons
        connectButton.setGraphic(IconFactory.group(IconPaths.SSH_CONNECT, 0.65, Color.GREEN));
        disconnectButton.setGraphic(IconFactory.group(IconPaths.SSH_DISCONNECT, 0.65, Color.RED));

        // Tooltips
        connectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.connect", "Connect")));
        disconnectButton.setTooltip(new Tooltip(I18n.t("ssh.tab.disconnect", "Disconnect")));
        disconnectButton.setDisable(true);

        connectButton.setOnAction(e -> doConnect());
        disconnectButton.setOnAction(e -> doDisconnect());

        // Terminal: monospace, editable so KEY_TYPED fires, but we consume events
        // so typed text is never inserted locally ¡ª only remote echo builds the display.
        terminalArea.setEditable(true);
        terminalArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");

        setupKeyHandling();
        setupMouseHandling();

        // PTY resize on container size change
        terminalArea.widthProperty().addListener((obs, o, n) -> updatePtySize());
        terminalArea.heightProperty().addListener((obs, o, n) -> updatePtySize());

        // Connection status
        connectStatus.addListener((obs, o, n) -> connectionLabel.setText(n));
        connectStatus.set(I18n.t("ssh.tab.disconnected", "Disconnected"));
    }

    // ======== Keyboard ¡ª raw keystroke forwarding ========

    private void setupKeyHandling() {
        /*
         * KEY_TYPED carries the composed (locale-aware) character for printable keys.
         * We consume it so the text area never inserts typed text locally;
         * instead the remote shell echoes it back.
         */
        terminalArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!running.get() || shellOut == null) return;
            String ch = event.getCharacter();
            if (ch == null || ch.isEmpty()) return;
            char c = ch.charAt(0);
            // Control characters (0x00-0x1F, DEL) are handled in KEY_PRESSED
            if (c < 0x20 || c == 0x7F) {
                event.consume();
                return;
            }
            sendToShell(ch);
            event.consume();
        });

        /*
         * KEY_PRESSED handles all non?printable keys: function keys, arrows,
         * control?letter combos, Enter, Backspace, Tab, etc.
         */
        terminalArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();

            // When disconnected, allow basic system shortcuts but block everything else
            if (!running.get() || shellOut == null) {
                if (event.isShortcutDown() &&
                    (code == KeyCode.C || code == KeyCode.A)) {
                    return;  // let default copy / select?all through
                }
                event.consume();
                return;
            }

            // Always lock cursor to end on any keystroke
            Platform.runLater(() -> terminalArea.moveTo(terminalArea.getLength()));

            // Ctrl+Shift+C/V  ¡ª system clipboard integration
            if (event.isShortcutDown() && event.isShiftDown()) {
                if (code == KeyCode.C) {
                    terminalArea.copy();
                    event.consume();
                    return;
                }
                if (code == KeyCode.V) {
                    pasteClipboard();
                    event.consume();
                    return;
                }
            }

            // Ctrl+letter ¡ª terminal control characters
            if (event.isControlDown() && !event.isShiftDown()
                    && !event.isAltDown() && !event.isMetaDown()) {
                // Ctrl+C  ¡ú  copy if selection exists, else SIGINT
                if (code == KeyCode.C && !terminalArea.getSelectedText().isEmpty()) {
                    terminalArea.copy();
                    event.consume();
                    return;
                }
                // Ctrl+V  ¡ú  paste from clipboard
                if (code == KeyCode.V) {
                    pasteClipboard();
                    event.consume();
                    return;
                }
                sendControlByte(code);
                event.consume();
                return;
            }

            // Special / navigation keys ¡ª send terminal escape sequences
            if (sendSpecialKey(code)) {
                event.consume();
                return;
            }

            // Printable keys (letter, digit, symbol, space) ¡ª let KEY_TYPED handle them
            if (code.isLetterKey() || code.isDigitKey()
                    || code == KeyCode.SPACE || isPunctuationKey(code)) {
                return;
            }

            // Everything else consumed (e.g. CapsLock, PrintScreen, etc.)
            event.consume();
        });
    }

    /** Send one of the well?known terminal escape sequences. */
    private boolean sendSpecialKey(KeyCode code) {
        switch (code) {
            case ENTER:       sendToShell("\n");        return true;
            case BACK_SPACE:  sendToShell("\u007F");    return true;  // DEL
            case TAB:         sendToShell("\t");        return true;
            case ESCAPE:      sendToShell("\u001B");    return true;
            case UP:          sendToShell("\u001B[A");  return true;
            case DOWN:        sendToShell("\u001B[B");  return true;
            case RIGHT:       sendToShell("\u001B[C");  return true;
            case LEFT:        sendToShell("\u001B[D");  return true;
            case HOME:        sendToShell("\u001B[H");  return true;
            case END:         sendToShell("\u001B[F");  return true;
            case DELETE:      sendToShell("\u001B[3~"); return true;
            case PAGE_UP:     sendToShell("\u001B[5~"); return true;
            case PAGE_DOWN:   sendToShell("\u001B[6~"); return true;
            case F1:          sendToShell("\u001BOP");  return true;
            case F2:          sendToShell("\u001BOQ");  return true;
            case F3:          sendToShell("\u001BOR");  return true;
            case F4:          sendToShell("\u001BOS");  return true;
            case F5:          sendToShell("\u001B[15~"); return true;
            case F6:          sendToShell("\u001B[17~"); return true;
            case F7:          sendToShell("\u001B[18~"); return true;
            case F8:          sendToShell("\u001B[19~"); return true;
            case F9:          sendToShell("\u001B[20~"); return true;
            case F10:         sendToShell("\u001B[21~"); return true;
            case F11:         sendToShell("\u001B[23~"); return true;
            case F12:         sendToShell("\u001B[24~"); return true;
            default:          return false;
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

    /** Map Ctrl+letter ¡ú ASCII control character (0x01¨C0x1A). */
    private void sendControlByte(KeyCode code) {
        int v;
        switch (code) {
            case A: v = 0x01; break; case B: v = 0x02; break;
            case C: v = 0x03; break; // SIGINT
            case D: v = 0x04; break; // EOF
            case E: v = 0x05; break; case F: v = 0x06; break;
            case G: v = 0x07; break; case H: v = 0x08; break;
            case I: v = 0x09; break; case J: v = 0x0A; break;
            case K: v = 0x0B; break; case L: v = 0x0C; break; // FF (clear screen)
            case M: v = 0x0D; break; case N: v = 0x0E; break;
            case O: v = 0x0F; break; case P: v = 0x10; break;
            case Q: v = 0x11; break; case R: v = 0x12; break;
            case S: v = 0x13; break; case T: v = 0x14; break;
            case U: v = 0x15; break; case V: v = 0x16; break;
            case W: v = 0x17; break; case X: v = 0x18; break;
            case Y: v = 0x19; break; case Z: v = 0x1A; break; // SIGTSTP
            default: return;
        }
        sendToShell(new String(new byte[]{(byte) v}));
    }

    /** Paste clipboard text directly to the remote shell (as if typed). */
    private void pasteClipboard() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasString()) {
            String text = cb.getString();
            if (text != null && !text.isEmpty()) {
                sendToShell(text);
            }
        }
    }

    /** Write bytes to the shell output stream. */
    private void sendToShell(String text) {
        if (shellOut == null || !running.get()) return;
        try {
            shellOut.write(text.getBytes("UTF-8"));
            shellOut.flush();
        } catch (IOException e) {
            log.error("Failed to send data to shell", e);
        }
    }

    // ======== Mouse & caret ========

    private void setupMouseHandling() {
        terminalArea.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            terminalArea.requestFocus();
            // Don't move caret ¡ª let the user click anywhere to select text
            // or position the cursor when reading history.
        });
    }



    /**
     * True when the caret was near the text end before the current append.
     * Used to decide whether to auto-scroll ¡ª if the user is reading history
     * (caret far from the bottom) we should not fight their scroll position.
     */
    private boolean wasNearEndBeforeAppend() {
        return terminalArea.getCaretPosition() >= terminalArea.getLength() - 8;
    }
    // ======== Connection management ========

    /**
     * Initialize with an SSH connection configuration and auto-connect.
     */
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

                // Apply initial PTY size after channel is connected
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
        if (readThread != null) {
            readThread.interrupt();
        }
        if (shellChannel != null && shellChannel.isConnected()) {
            shellChannel.disconnect();
        }
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

    // ======== Shell output ¡ª read, strip ANSI, handle \r ========

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
            // Clean-up when the read loop exits (remote closed connection)
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

    /**
     * Process raw shell output for display:
     * <ol>
     *   <li>Strip ANSI/VT100 escape sequences</li>
     *   <li>Normalize {@code \r\n} ¡ú {@code \n}</li>
     *   <li>Handle bare {@code \r} as "overwrite current line" (for progress bars,
     *       readline history navigation, etc.)</li>
     *   <li>Auto-scroll to bottom</li>
     * </ol>
     */
    private void processOutput(String raw) {
        String clean = ANSI_PATTERN.matcher(raw).replaceAll("");
        if (clean.isEmpty()) return;
        // Strip non-printable control chars (keep \n, \t, \r)
        clean = clean.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        // Treat \r\n as a plain newline (not an overwrite)
        clean = clean.replace("\r\n", "\n");

        if (!clean.contains("\r")) {
            terminalArea.appendText(clean);
            if (wasNearEndBeforeAppend()) {
                terminalArea.moveTo(terminalArea.getLength());
            }
            return;
        }

        // Walk through, handling each \r as a line-overwrite
        int pos = 0;
        int cr;
        while ((cr = clean.indexOf('\r', pos)) >= 0) {
            if (cr > pos) {
                terminalArea.appendText(clean.substring(pos, cr));
            }
            // Delete from last \n (or start) to end ¡ª simulating carriage return
            String text = terminalArea.getText();
            int lastNl = text.lastIndexOf('\n');
            int lineStart = (lastNl == -1) ? 0 : lastNl + 1;
            terminalArea.deleteText(lineStart, terminalArea.getLength());
            pos = cr + 1;
        }
        if (pos < clean.length()) {
            terminalArea.appendText(clean.substring(pos));
        }
        if (wasNearEndBeforeAppend()) {
            terminalArea.moveTo(terminalArea.getLength());
        }
    }

    // ======== PTY resize ========

    /**
     * Forward the current terminal-area pixel dimensions to the remote PTY
     * so the shell (bash, zsh, etc.) knows the correct column/row count.
     */
    private void updatePtySize() {
        if (shellChannel == null || !shellChannel.isConnected()) return;
        if (terminalArea.getWidth() <= 0 || terminalArea.getHeight() <= 0) return;
        try {
            int cols = Math.max(40, (int) (terminalArea.getWidth() / CHAR_WIDTH));
            int rows = Math.max(10, (int) (terminalArea.getHeight() / LINE_HEIGHT));
            shellChannel.setPtySize(cols, rows, cols * (int) CHAR_WIDTH, rows * (int) LINE_HEIGHT);
        } catch (Exception e) {
            log.debug("Failed to update PTY size", e);
        }
    }

    // ======== Helpers ========

    private void appendTerminal(String text) {
        Runnable op = () -> {
            terminalArea.appendText(text);
            if (wasNearEndBeforeAppend()) {
                terminalArea.moveTo(terminalArea.getLength());
            }
        };
        if (Platform.isFxApplicationThread()) {
            op.run();
        } else {
            Platform.runLater(op);
        }
    }

    public void closeSession() {
        doDisconnect();
    }
}


