package com.dbboys.ui.component.completion;

import com.dbboys.infra.i18n.I18n;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.stage.Popup;
import org.fxmisc.richtext.CodeArea;

import java.util.List;

/**
 * Floating autocomplete popup positioned near the caret.
 *
 * <p>Uses {@link javafx.stage.Popup} (like {@code MarkdownSearchUtil}) to float
 * above the editor without interfering with its layout.  Listens for outside
 * clicks and Escape to auto-dismiss.
 */
public class CompletionPopup {

    // ---- layout constants ----
    private static final double MAX_LIST_HEIGHT = 180;
    private static final double MIN_LIST_WIDTH = 240;
    private static final double MAX_LIST_WIDTH = 420;
    private static final double CELL_HEIGHT = 24;
    private static final int MAX_VISIBLE_ROWS = 10;
    private static final String STYLE_CLASS = "completion-popup";

    private final Popup popup;
    private final ListView<CompletionItem> listView;
    private final VBox root;

    private CodeArea codeArea;
    private List<CompletionItem> currentItems = List.of();
    private int prefixLen;
    private boolean showing;
    private EventHandler<javafx.event.Event> outsideClickHandler;

    public CompletionPopup() {
        popup = new Popup();
        popup.setAutoHide(false); // we manage hide ourselves for finer control
        popup.setAutoFix(false);

        listView = new ListView<>();
        listView.setFocusTraversable(false);
        listView.getStyleClass().add("completion-list");
        listView.setFixedCellSize(CELL_HEIGHT);
        listView.setCellFactory(lv -> new CompletionCell());

        root = new VBox(0);
        root.getStyleClass().add(STYLE_CLASS);
        root.getChildren().add(listView);
        VBox.setVgrow(listView, Priority.ALWAYS);

        popup.getContent().add(root);

        // Keyboard navigation within the popup
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPress);
    }

    // ---- public API ----

    public void show(CodeArea codeArea, List<CompletionItem> items) {
        if (items == null || items.isEmpty()) {
            hide();
            return;
        }

        this.codeArea = codeArea;
        this.currentItems = items;
        // Stash prefix length at show time so applyCompletionToEditor
        // knows how much to replace regardless of intervening keystrokes.
        this.prefixLen = computePrefixLen(codeArea);

        listView.getItems().setAll(items);
        listView.getSelectionModel().select(0);
        if (items.size() > MAX_VISIBLE_ROWS) {
            listView.scrollTo(0);
        }

        // Mouse click on an item applies it (same as Enter/Tab)
        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                CompletionItem item = listView.getSelectionModel().getSelectedItem();
                if (item != null) {
                    applyCompletionToEditor(item);
                    hide();
                }
            }
        });

        // Size the list - total height capped at MAX_LIST_HEIGHT for scrollbar
        int rows = Math.min(items.size(), MAX_VISIBLE_ROWS);
        double listHeight = rows * CELL_HEIGHT + 2;
        double prefWidth = computeListWidth(items);

        listView.setPrefHeight(listHeight);
        listView.setMinWidth(prefWidth);
        listView.setPrefWidth(prefWidth);

        // Position near caret - clamp to screen bounds
        var caret = codeArea.getCaretSelectionBind().getUnderlyingCaret();
        Bounds caretBounds = codeArea.getCaretBoundsOnScreen(caret).orElse(null);

        double x, y;
        if (caretBounds == null) {
            Bounds areaBounds = codeArea.localToScreen(codeArea.getBoundsInLocal());
            if (areaBounds != null) {
                x = areaBounds.getMinX();
                y = areaBounds.getMinY();
            } else {
                x = 0;
                y = 0;
            }
        } else {
            x = caretBounds.getMinX();
            y = caretBounds.getMaxY() + 2;
        }

        // Clamp to screen: avoid popup going off the right/bottom edge
        javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
        if (screen != null) {
            Rectangle2D screenBounds = screen.getVisualBounds();
            if (x + prefWidth > screenBounds.getMaxX()) {
                x = screenBounds.getMaxX() - prefWidth - 4;
            }
            if (x < screenBounds.getMinX()) {
                x = screenBounds.getMinX() + 4;
            }
            // If popup would overflow bottom, flip above the caret
            if (y + listHeight > screenBounds.getMaxY()) {
                y = (caretBounds != null ? caretBounds.getMinY() : y) - listHeight - 4;
            }
            if (y < screenBounds.getMinY()) {
                y = screenBounds.getMinY() + 4;
            }
        }

        popup.show(codeArea, x, y);

        showing = true;

        // Install scene-level filter to hide popup on click outside
        javafx.scene.Scene scene = codeArea.getScene();
        if (scene != null) {
            outsideClickHandler = event -> {
                if (!showing) return;
                if (event.getTarget() instanceof Node clicked) {
                    // Check if click is inside the popup or its content
                    Node popupNode = popup.getScene() == null ? null
                            : popup.getScene().getRoot();
                    // Walk up the scene graph from clicked node
                    for (Node n = clicked; n != null; n = n.getParent()) {
                        if (n == root || (popupNode != null && n == popupNode)) {
                            return; // click inside popup - don't hide
                        }
                    }
                    // Only hide if clicking in the same scene (not another window)
                    if (clicked.getScene() == scene) {
                        hide();
                    }
                }
            };
            scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, outsideClickHandler);
        }
    }

    public void hide() {
        // Remove the scene-level click filter
        if (outsideClickHandler != null && codeArea != null) {
            javafx.scene.Scene scene = codeArea.getScene();
            if (scene != null) {
                scene.removeEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, outsideClickHandler);
            }
            outsideClickHandler = null;
        }
        popup.hide();
        listView.getItems().clear();
        currentItems = List.of();
        codeArea = null;
        prefixLen = 0;
        showing = false;
    }

    public boolean isShowing() {
        return showing && popup.isShowing();
    }

    public void selectNext() {
        int idx = listView.getSelectionModel().getSelectedIndex();
        int size = currentItems.size();
        if (size == 0) return;
        int next = (idx + 1) % size;
        listView.getSelectionModel().select(next);
        scrollIfNeeded(next, 1);
    }

    public void selectPrevious() {
        int idx = listView.getSelectionModel().getSelectedIndex();
        int size = currentItems.size();
        if (size == 0) return;
        int prev = idx <= 0 ? size - 1 : idx - 1;
        listView.getSelectionModel().select(prev);
        scrollIfNeeded(prev, -1);
    }

    /**
     * Lazy scroll: only scroll when the selection reaches the edge of the
     * visible area, so the user can see relevant context above/below.
     */
    private void scrollIfNeeded(int index, int direction) {
        int size = listView.getItems().size();
        if (size <= MAX_VISIBLE_ROWS) {
            return; // all fit, no scrollbar
        }
        // determine first and last visible cell indices
        int firstVis = -1, lastVis = -1;
        for (var n : listView.lookupAll(".list-cell")) {
            if (n instanceof ListCell<?> c && c.getItem() != null) {
                int i = c.getIndex();
                if (firstVis < 0 || i < firstVis) firstVis = i;
                if (lastVis < 0 || i > lastVis) lastVis = i;
            }
        }
        if (firstVis < 0) return;
        // scroll only when the selection would leave the visible window
        int margin = 1; // keep 1 item of context
        if (direction < 0 && index <= firstVis + margin) {
            listView.scrollTo(Math.max(0, index - margin));
        } else if (direction > 0 && index >= lastVis - margin) {
            listView.scrollTo(Math.min(size - 1, index + margin));
        }
    }

    public CompletionItem getSelectedItem() {
        return listView.getSelectionModel().getSelectedItem();
    }

    public void applySelection() {
        CompletionItem item = getSelectedItem();
        if (item == null || codeArea == null) return;
        applyCompletionToEditor(item);
        hide();
    }

    // ---- internal ----

    private void handleKeyPress(KeyEvent event) {
        switch (event.getCode()) {
            case UP:
                selectPrevious();
                event.consume();
                break;
            case DOWN:
                selectNext();
                event.consume();
                break;
            case ENTER:
                applySelection();
                event.consume();
                break;
            case TAB:
                applySelection();
                // don't consume — let the editor see TAB for indent
                break;
            case ESCAPE:
                hide();
                event.consume();
                break;
            default:
                break;
        }
    }

    /**
     * Replaces the partial word before the caret with the completed text.
     *
     * <p>Uses {@link #prefixLen} captured when the popup was shown,
     * so replacement is correct regardless of intervening keystrokes
     * (e.g. ENTER/TAB inserted by RichTextFX's internal handlers between
     * KEY_PRESSED and KEY_TYPED).
     */
    private void applyCompletionToEditor(CompletionItem item) {
        int caret = codeArea.getCaretPosition();

        // Walk left past any non-word junk (e.g. \n inserted by the editor)
        // to find the end of the original prefix.
        int effectiveEnd = caret;
        String text = codeArea.getText();
        while (effectiveEnd > 0 && !isWordChar(text.charAt(effectiveEnd - 1))) {
            effectiveEnd--;
        }

        String insertText = item.getInsertText();
        if (insertText == null || insertText.isEmpty()) {
            insertText = item.getLabel();
        }

        int replaceStart = Math.max(0, effectiveEnd - prefixLen);
        if (prefixLen > 0) {
            // When the prefix is a completed FROM/JOIN clause keyword,
            // insert after the keyword with a space instead of replacing it.
            String prefix = text.substring(replaceStart, effectiveEnd);
            if (isClauseKeyword(prefix)) {
                codeArea.insertText(effectiveEnd, " " + insertText);
            } else {
                codeArea.replaceText(replaceStart, effectiveEnd, insertText);
            }
        } else {
            codeArea.insertText(caret, insertText);
        }
        codeArea.requestFocus();
    }

    /** Measure how many word characters precede the caret (the "prefix" being completed). */
    private static int computePrefixLen(CodeArea codeArea) {
        int caret = codeArea.getCaretPosition();
        if (caret <= 0) return 0;
        String text = codeArea.getText();
        int pos = caret;
        while (pos > 0 && isWordChar(text.charAt(pos - 1))) {
            pos--;
        }
        return caret - pos;
    }

    private double computeListWidth(List<CompletionItem> items) {
        // Simple heuristic: measure longest label + detail
        double maxChars = 0;
        for (CompletionItem item : items) {
            double chars = item.getLabel().length() + 2; // icon width
            if (!item.getDetail().isEmpty()) {
                chars += item.getDetail().length() + 3;
            }
            maxChars = Math.max(maxChars, chars);
        }
        double width = maxChars * 8 + 20; // ~8px per char + padding
        if (items.size() > MAX_VISIBLE_ROWS) { width += 14; } // room for scrollbar
        return Math.max(MIN_LIST_WIDTH, Math.min(MAX_LIST_WIDTH, width));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

        /** True when ${@code s} is a SQL FROM/JOIN clause keyword whose text
     *  should not be replaced by a completion item
     *  (the item is inserted after the keyword with a space prefix).
     */
    private static boolean isClauseKeyword(String s) {
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return "from".equals(lower) || "join".equals(lower)
            || "into".equals(lower) || "update".equals(lower)
            || "table".equals(lower) || "view".equals(lower);
    }

// ---- cell factory ----

    private static class CompletionCell extends ListCell<CompletionItem> {
        private final HBox row;
        private final Label iconLabel;
        private final Label textLabel;
        private final Label kindBadge;

        CompletionCell() {
            iconLabel = new Label();
            iconLabel.getStyleClass().add("completion-icon");
            iconLabel.setMinWidth(20);
            iconLabel.setPrefWidth(20);
            iconLabel.setMaxWidth(20);
            iconLabel.setAlignment(Pos.CENTER);

            textLabel = new Label();
            textLabel.getStyleClass().add("completion-label");
            HBox.setHgrow(textLabel, Priority.ALWAYS);

            kindBadge = new Label();
            kindBadge.getStyleClass().add("completion-kind-badge");
            kindBadge.setAlignment(Pos.CENTER_RIGHT);

            row = new HBox(4);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(iconLabel, textLabel, kindBadge);
            row.setPadding(new Insets(1, 6, 1, 4));
            setPrefHeight(CELL_HEIGHT);
        }

        @Override
        protected void updateItem(CompletionItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            textLabel.setText(item.getLabel());
            iconLabel.setText(kindIcon(item.getKind()));
            kindBadge.setText(kindShortLabel(item.getKind()));

            // Only touch style classes when the kind changes, to avoid CSS flicker
            String kindClass = "kind-" + item.getKind().name().toLowerCase(java.util.Locale.ROOT);
            if (!kindBadge.getStyleClass().contains(kindClass)) {
                kindBadge.getStyleClass().removeAll(
                        "kind-keyword", "kind-function", "kind-table",
                        "kind-view", "kind-column", "kind-schema",
                        "kind-alias", "kind-snippet", "kind-synonym", "kind-systable"
                );
                kindBadge.getStyleClass().add(kindClass);
            }
            setGraphic(row);
        }
    }

    // ---- display helpers ----

    static String kindIcon(CompletionKind kind) {
        return switch (kind) {
            case KEYWORD -> "≣";
            case FUNCTION -> "ƒ";
            case TABLE -> "■";
            case VIEW -> "◇";
            case COLUMN -> "→";
            case SCHEMA -> "□";
            case ALIAS -> "↔";
            case SNIPPET -> "§";
            case SYNONYM -> "≈";
            case SYSTABLE -> "\u2630";
        };
    }

    static String kindShortLabel(CompletionKind kind) {
        return switch (kind) {
            case KEYWORD -> I18n.t("completion.kind.keyword", "KW");
            case FUNCTION -> I18n.t("completion.kind.function", "FN");
            case TABLE -> I18n.t("completion.kind.table", "TB");
            case VIEW -> I18n.t("completion.kind.view", "VW");
            case COLUMN -> I18n.t("completion.kind.column", "COL");
            case SCHEMA -> I18n.t("completion.kind.schema", "SCH");
            case ALIAS -> I18n.t("completion.kind.alias", "AL");
            case SNIPPET -> I18n.t("completion.kind.snippet", "SN");
            case SYNONYM -> I18n.t("completion.kind.synonym", "SY");
            case SYSTABLE -> I18n.t("completion.kind.systable", "ST");
        };
    }

    static String kindLabel(CompletionKind kind) {
        return switch (kind) {
            case KEYWORD -> I18n.t("completion.detail.keyword", "keyword");
            case FUNCTION -> I18n.t("completion.detail.function", "function");
            case TABLE -> I18n.t("completion.detail.table", "table");
            case VIEW -> I18n.t("completion.detail.view", "view");
            case COLUMN -> I18n.t("completion.detail.column", "column");
            case SCHEMA -> I18n.t("completion.detail.schema", "schema");
            case ALIAS -> I18n.t("completion.detail.alias", "alias");
            case SNIPPET -> I18n.t("completion.detail.snippet", "snippet");
            case SYNONYM -> I18n.t("completion.detail.synonym", "synonym");
            case SYSTABLE -> I18n.t("completion.detail.systable", "system table");
        };
    }

    // ---- cleanup ----

    /** Remove all listeners and hide. Call when the editor is disposed. */
    public void dispose() {
        hide();
        outsideClickHandler = null;
        popup.getContent().clear();
    }
}
