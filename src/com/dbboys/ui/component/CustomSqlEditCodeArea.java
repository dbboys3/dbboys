package com.dbboys.ui.component;

import com.dbboys.app.AppExecutor;
import com.dbboys.ui.component.CustomShortcutMenuItem;
import com.dbboys.ui.component.completion.CompletionEngine;
import com.dbboys.ui.component.completion.CompletionItem;
import com.dbboys.ui.component.completion.CompletionPopup;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.config.ConfigManagerUtil;
import com.dbboys.infra.util.KeywordsHighlightUtil;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.infra.util.SqlParserUtil;
import com.dbboys.model.Catalog;
import com.dbboys.model.Connect;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.*;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

public class CustomSqlEditCodeArea extends CodeArea {
    private static final int LOCAL_HIGHLIGHT_MAX = 4000;
    private static final int LOOKBACK_RANGE = 2000; // 上文最多回溯这么多字符尝试局部高亮
    private static final int DEFAULT_FONT_SIZE = 12;
    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 40;
    private static final int FONT_SIZE_STEP = 1;
    private static final String SQL_EDITOR_FONT_SIZE_KEY = "SQL_EDITOR_FONT_SIZE";
    private static final String SQL_EDITOR_FONT_SIZE_CLASS_PREFIX = "sql-editor-font-size-";
    private static final String AUTOCOMPLETE_ENABLED_KEY = "AUTOCOMPLETE_ENABLED";
    private static final String AUTOCOMPLETE_TRIGGER_DELAY_MS_KEY = "AUTOCOMPLETE_TRIGGER_DELAY_MS";
    private static final int DEFAULT_TRIGGER_DELAY_MS = 50;
    private static int sharedFontSize = loadConfiguredFontSize();

    private final int[] sqlEditCodeAreaCursorPosition = {-1, -1};
    @SuppressWarnings("unchecked")
    private final Collection<String>[] sqlEditCodeAreaCursorStyles = new Collection[]{
            Collections.emptyList(),
            Collections.emptyList()
    };
    private int styleChangeFlag = 0;
    private boolean completeSelectionApplied = false;
    private final AtomicLong highlightSeq = new AtomicLong(0);
    private final AtomicLong completionSeq = new AtomicLong(0);
    private final CompletionEngine completionEngine = new CompletionEngine();
    private final CompletionPopup completionPopup = new CompletionPopup();
    private int fontSize = sharedFontSize;
    private Connect activeConnect;
    private Catalog activeDatabase;
    private Runnable onSaveRequest = () -> {};
    private Runnable onContentDirty = () -> {};
    private Runnable onShowFindPanel = () -> {};
    private Runnable onShowReplacePanel = () -> {};
    private Runnable onExecuteRequest = () -> {};
    private BooleanSupplier saveDisabledSupplier = () -> true;
    private BooleanSupplier executeDisabledSupplier = () -> true;

    public CustomSqlEditCodeArea() {
        super();
        applyEditorFontSize(fontSize);
        CustomShortcutMenuItem codeAreaExecuteItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.execute", "Ctrl+Enter", IconFactory.group(IconPaths.SQL_RUN, 0.8,Color.valueOf("#51dd66")));
        CustomShortcutMenuItem codeAreaFormatItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.format", "Ctrl+M", IconFactory.group(IconPaths.SQL_FORMAT, 0.6));
        CustomShortcutMenuItem codeAreaUpperItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.upper", "Ctrl+U", IconFactory.group(IconPaths.SQL_UPPER, 0.6));
        CustomShortcutMenuItem codeAreaLowerItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.lower", "Ctrl+L", IconFactory.group(IconPaths.SQL_LOWER, 0.6, 0.7));
        CustomShortcutMenuItem codeAreaCommRowItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.comment_row", "Ctrl+/", IconFactory.group(IconPaths.SQL_COMMENT_ROW, 0.6, 0.8));
        CustomShortcutMenuItem codeAreaCommRowsItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.comment_rows", "Ctrl+|", IconFactory.group(IconPaths.SQL_COMMENT_ROWS, 0.6));
        CustomShortcutMenuItem codeAreaSearchItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.search", "Ctrl+F/R", IconFactory.group(IconPaths.MAIN_SEARCH, 0.6));
        CustomShortcutMenuItem codeAreaCopyItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.copy", "Ctrl+C", IconFactory.group(IconPaths.COPY, 0.7));
        CustomShortcutMenuItem codeAreaCutItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.cut", "Ctrl+X", IconFactory.group(IconPaths.CUT, 0.6));
        CustomShortcutMenuItem codeAreaPasteItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.paste", "Ctrl+V", IconFactory.group(IconPaths.PASTE, 0.6));
        CustomShortcutMenuItem codeAreaUndoItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.undo", "Ctrl+Z", IconFactory.group(IconPaths.UNDO, 0.6));
        CustomShortcutMenuItem codeAreaRedoItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.redo", "Ctrl+Y", IconFactory.group(IconPaths.REDO, 0.6));
        CustomShortcutMenuItem codeAreaSaveItem = MenuItemUtil.createMenuItemI18n("sql.editor.menu.save", "Ctrl+S", IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.6));

        ContextMenu codeAreaMenu = new ContextMenu();
        codeAreaMenu.getItems().addAll(
                codeAreaExecuteItem, codeAreaFormatItem, codeAreaUpperItem, codeAreaLowerItem,
                codeAreaCommRowItem, codeAreaCommRowsItem, codeAreaSearchItem, codeAreaCopyItem,
                codeAreaCutItem, codeAreaPasteItem, codeAreaUndoItem, codeAreaRedoItem, codeAreaSaveItem
        );

        // 补全弹窗导航 — 用 EventFilter（捕获阶段）拦截，必须在 RichTextFX 行为层处理之前
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (completionPopup.isShowing()) {
                if (event.getCode() == KeyCode.UP) {
                    completionPopup.selectPrevious();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.DOWN) {
                    completionPopup.selectNext();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.ENTER) {
                    completeSelectionApplied = true;
                    completionPopup.applySelection();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.TAB) {
                    // Tab confirms selection, but do NOT consume the event
                    // so the RichTextFX editor still receives it for indent.
                    completeSelectionApplied = true;
                    completionPopup.applySelection();
                    return;
                }
                if (event.getCode() == KeyCode.ESCAPE) {
                    completionPopup.hide();
                    event.consume();
                    return;
                }
            }
        });

        // ctrl快捷键
        setOnKeyPressed(event -> {
            if (completionPopup.isShowing()) {
                // 补全弹窗显示时的按键已经在上面的 EventFilter 处理了
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.SPACE) {
                triggerCompletionNow();
                event.consume();
                return;
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.S){
                onSaveRequest.run();
            }
            if (event.isControlDown() && isZoomInKey(event)) {
                adjustFontSize(FONT_SIZE_STEP);
                event.consume();
            }
            if (event.isControlDown() && isZoomOutKey(event)) {
                adjustFontSize(-FONT_SIZE_STEP);
                event.consume();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.ENTER){
                fireExecute();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.M){
                codeAreaFormatItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.U){
                codeAreaUpperItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.L){
                codeAreaLowerItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.SLASH){
                codeAreaCommRowItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.BACK_SLASH){
                codeAreaCommRowsItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.F){
                onShowFindPanel.run();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.R){
                onShowReplacePanel.run();
            }
        });
        // Ctrl + 鼠标滚轮：与 Ctrl++/Ctrl+- 相同，调整 SQL 编辑器字号
        addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown()) {
                return;
            }
            double dy = event.getDeltaY();
            if (dy == 0) {
                return;
            }
            adjustFontSize(dy > 0 ? FONT_SIZE_STEP : -FONT_SIZE_STEP);
            event.consume();
        });
        // 自动补全/跳过成对引号，避免重复插入（使用 EventFilter，先于默认处理）
        addEventFilter(KeyEvent.KEY_TYPED, event -> {
            // 补全选中后跳过紧接着的按键字符（回车/制表符），防止追加到替换文本后面
            if (completeSelectionApplied) {
                completeSelectionApplied = false;
                String ch = event.getCharacter();
                if ("\r".equals(ch) || "\n".equals(ch) || "\t".equals(ch)) {
                    event.consume();
                    return;
                }
            }
            String ch = event.getCharacter();
            if (ch == null || ch.length() != 1) {
                return;
            }
            char c = ch.charAt(0);
            if ((c == '"' || c == '\'' || c == '`') && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                int caret = getCaretPosition();
                int textLen = getLength();
                event.consume(); // 阻止默认插入

                // 如果光标右侧已经是同样的引号，则视为"跳过"而非再插入
                if (caret < textLen && getText(caret, caret + 1).charAt(0) == c) {
                    moveTo(caret + 1);
                    return;
                }

                String selected = getSelectedText();
                if (selected != null && !selected.isEmpty()) {
                    replaceSelection(ch + selected + ch);
                    selectRange(caret + 1 + selected.length(), caret + 1 + selected.length());
                } else {
                    insertText(caret, ch + ch); // 总共两个字符
                    moveTo(caret + 1);
                }
            }
            // 空格 -> 关闭补全
            if (c == ' ') {
                completionPopup.hide();
                return;
            }
            // 智能补全触发：字母、数字、下划线、点号 -> 50ms 防抖后弹出
            if (c == '.' || Character.isLetterOrDigit(c) || c == '_') {
                scheduleCompletion();
            }
        });

        // Backspace / Delete -> 更新或关闭补全
        addEventFilter(KeyEvent.KEY_RELEASED, event2 -> {
            if (event2.getCode() == KeyCode.BACK_SPACE || event2.getCode() == KeyCode.DELETE) {
                if (!isAutocompleteEnabled()) return;
                Platform.runLater(() -> {
                    if (completionPopup.isShowing()) {
                        completionSeq.incrementAndGet(); // cancel pending
                        int caret = getCaretPosition();
                        doComplete(caret, false);
                    }
                });
            }
        });

        setContextMenu(codeAreaMenu);
        codeAreaFormatItem.setOnAction(event-> applyTransform(SqlParserUtil::formatSql));
        codeAreaUpperItem.setOnAction(event-> applyTransform(SqlParserUtil::upperSql));
        codeAreaLowerItem.setOnAction(event-> applyTransform(SqlParserUtil::lowerSql));

        codeAreaCommRowItem.setOnAction(event-> {
            int currentLine = getCurrentParagraph();
            String lineText = getParagraph(currentLine).getText();
            String trimmed = lineText.trim();
            int lineStart = getAbsolutePosition(currentLine, 0);
            if (trimmed.startsWith("--")) {
                int commentIndex = lineText.indexOf("--");
                deleteText(lineStart + commentIndex, lineStart + commentIndex + 2);
            } else {
                int firstNonSpace = lineText.indexOf(trimmed);
                insertText(lineStart + firstNonSpace, "--");
            }
        });

        codeAreaCommRowsItem.setOnAction(event-> {
            int start = getSelection().getStart();
            int end = getSelection().getEnd();
            String selectedText = getSelectedText();
            if(selectedText==null ||selectedText.isEmpty()){
                return;
            }
            if (selectedText.trim().startsWith("/*") && selectedText.endsWith("*/")) {
                String uncommented = selectedText.replaceAll("/\\*|\\*/","");
                replaceText(start, end, uncommented);
            } else {
                String commented = "/*" + selectedText + "*/";
                replaceText(start, end, commented);
            }
        });

        codeAreaSearchItem.setOnAction(event-> onShowFindPanel.run());
        codeAreaExecuteItem.setOnAction(event -> fireExecute());
        codeAreaCopyItem.setDisable(true);
        codeAreaCopyItem.setOnAction(event -> copy());
        codeAreaCutItem.setOnAction(event -> cut());
        codeAreaPasteItem.setOnAction(event -> paste());
        codeAreaUndoItem.setOnAction(event -> undo());
        codeAreaRedoItem.setOnAction(event -> redo());

        codeAreaMenu.setOnShowing(event -> {
            codeAreaSaveItem.setDisable(saveDisabledSupplier.getAsBoolean());
            boolean hasSelection = !getSelectedText().isEmpty();
            codeAreaCopyItem.setDisable(!hasSelection);
            codeAreaCutItem.setDisable(!hasSelection);
            codeAreaCommRowsItem.setDisable(!hasSelection);
            Clipboard clipboard = Clipboard.getSystemClipboard();
            codeAreaPasteItem.setDisable(!clipboard.hasString());
            codeAreaExecuteItem.setDisable(executeDisabledSupplier.getAsBoolean());
        });

        setParagraphGraphicFactory(LineNumberFactory.get(this));
        codeAreaSaveItem.setOnAction(event-> onSaveRequest.run());

        caretPositionProperty().addListener((obs, oldPos, newPos) -> Platform.runLater(() -> {
            styleChangeFlag=1;
            updateMatchingBracketHighlight();
            styleChangeFlag=0;
        }));

        plainTextChanges()
                .filter(change -> styleChangeFlag==0)
                .successionEnds(Duration.ofMillis(20))
                .subscribe(change -> {
                    scheduleIncrementalHighlight(change);
                    onContentDirty.run();
                });
    }

    private static int loadConfiguredFontSize() {
        try {
            int configured = Integer.parseInt(
                    ConfigManagerUtil.getProperty(SQL_EDITOR_FONT_SIZE_KEY, String.valueOf(DEFAULT_FONT_SIZE))
            );
            return clampFontSize(configured);
        } catch (NumberFormatException ignored) {
            return DEFAULT_FONT_SIZE;
        }
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }

    private static boolean isZoomInKey(KeyEvent event) {
        return event.getCode() == KeyCode.ADD
                || event.getCode() == KeyCode.PLUS
                || event.getCode() == KeyCode.EQUALS;
    }

    private static boolean isZoomOutKey(KeyEvent event) {
        return event.getCode() == KeyCode.SUBTRACT
                || event.getCode() == KeyCode.MINUS;
    }

    private void adjustFontSize(int delta) {
        int newFontSize = clampFontSize(fontSize + delta);
        if (newFontSize == fontSize) {
            return;
        }
        fontSize = newFontSize;
        sharedFontSize = newFontSize;
        applyEditorFontSize(newFontSize);
        ConfigManagerUtil.setProperty(SQL_EDITOR_FONT_SIZE_KEY, String.valueOf(newFontSize));
    }

    private void applyEditorFontSize(int size) {
        for (int candidate = MIN_FONT_SIZE; candidate <= MAX_FONT_SIZE; candidate++) {
            getStyleClass().remove(SQL_EDITOR_FONT_SIZE_CLASS_PREFIX + candidate);
        }
        getStyleClass().add(SQL_EDITOR_FONT_SIZE_CLASS_PREFIX + size);
    }

    public void setOnSaveRequest(Runnable onSaveRequest) {
        this.onSaveRequest = onSaveRequest == null ? () -> {} : onSaveRequest;
    }

    public void setOnContentDirty(Runnable onContentDirty) {
        this.onContentDirty = onContentDirty == null ? () -> {} : onContentDirty;
    }

    public void setOnShowFindPanel(Runnable onShowFindPanel) {
        this.onShowFindPanel = onShowFindPanel == null ? () -> {} : onShowFindPanel;
    }

    public void setOnShowReplacePanel(Runnable onShowReplacePanel) {
        this.onShowReplacePanel = onShowReplacePanel == null ? () -> {} : onShowReplacePanel;
    }

    public void setOnExecuteRequest(Runnable onExecuteRequest) {
        this.onExecuteRequest = onExecuteRequest == null ? () -> {} : onExecuteRequest;
    }

    public void setSaveDisabledSupplier(BooleanSupplier saveDisabledSupplier) {
        this.saveDisabledSupplier = saveDisabledSupplier == null ? () -> true : saveDisabledSupplier;
    }

    public void setExecuteDisabledSupplier(BooleanSupplier executeDisabledSupplier) {
        this.executeDisabledSupplier = executeDisabledSupplier == null ? () -> true : executeDisabledSupplier;
    }

    private void updateMatchingBracketHighlight() {
        resetLastBracketStyle(this);
        applyMatchingBracketHighlight(getCaretPosition());
    }

    private void applyMatchingBracketHighlight(int caretPosition) {
        String text = getText();
        int matchPos = -1;
        if (caretPosition < text.length()) {
            char rightChar = text.charAt(caretPosition);
            if ("()[]{}".indexOf(rightChar) != -1) {
                matchPos = findMatchingBracket(text, caretPosition, rightChar);
                if (matchPos != -1) {
                    highlightBracketPair(caretPosition, matchPos);
                    return;
                }
            }
        }
        if (caretPosition > 0) {
            char leftChar = text.charAt(caretPosition - 1);
            if ("()[]{}".indexOf(leftChar) != -1) {
                matchPos = findMatchingBracket(text, caretPosition - 1, leftChar);
                if (matchPos != -1) {
                    highlightBracketPair(caretPosition - 1, matchPos);
                }
            }
        }
    }

    private void highlightBracketPair(int firstPos, int secondPos) {
        sqlEditCodeAreaCursorStyles[0] = snapshotStyle(firstPos);
        sqlEditCodeAreaCursorStyles[1] = snapshotStyle(secondPos);
        setStyleClass(firstPos, firstPos + 1, "bracket-highlight");
        setStyleClass(secondPos, secondPos + 1, "bracket-highlight");
        sqlEditCodeAreaCursorPosition[0] = firstPos;
        sqlEditCodeAreaCursorPosition[1] = secondPos;
    }

    private Collection<String> snapshotStyle(int pos) {
        Collection<String> style = getStyleOfChar(pos);
        return style == null ? Collections.emptyList() : List.copyOf(style);
    }

    private void resetLastBracketStyle(CodeArea codeArea) {
        if (sqlEditCodeAreaCursorPosition[0] < 0 || sqlEditCodeAreaCursorPosition[1] < 0) {
            return;
        }
        try {
            restoreTrackedStyle(codeArea, sqlEditCodeAreaCursorPosition[0], sqlEditCodeAreaCursorStyles[0]);
            restoreTrackedStyle(codeArea, sqlEditCodeAreaCursorPosition[1], sqlEditCodeAreaCursorStyles[1]);
        }catch (Exception ignored){
        }finally {
            clearLastBracketTracking();
        }
    }

    private void restoreTrackedStyle(CodeArea codeArea, int pos, Collection<String> style) {
        if (pos < 0 || pos >= codeArea.getLength()) {
            return;
        }
        codeArea.setStyle(pos, pos + 1, style == null ? Collections.emptyList() : style);
    }

    private void clearLastBracketTracking() {
        sqlEditCodeAreaCursorPosition[0] = -1;
        sqlEditCodeAreaCursorPosition[1] = -1;
        sqlEditCodeAreaCursorStyles[0] = Collections.emptyList();
        sqlEditCodeAreaCursorStyles[1] = Collections.emptyList();
    }

    private void fireExecute() {
        if (executeDisabledSupplier.getAsBoolean()) {
            return;
        }
        if (!ensureExecuteTargetSelected()) {
            return;
        }
        onExecuteRequest.run();
    }

    public boolean ensureExecuteTargetSelected() {
        return !getSelectedText().isEmpty() || selectCurrentStatementAtCaret();
    }

    private boolean selectCurrentStatementAtCaret() {
        SqlParserUtil.StatementRange range = SqlParserUtil.findStatementRangeAtCaret(getText(), getCaretPosition());
        if (range == null) {
            return false;
        }
        selectRange(range.getStart(), range.getEnd());
        return true;
    }

    private void applyTransform(java.util.function.Function<String, String> transform) {
        String selectedText = getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            String currentText = getText();
            String transformedText = transform.apply(currentText);
            if (Objects.equals(currentText, transformedText)) {
                scheduleHighlighting();
                return;
            }
            replaceText(transformedText);
            return;
        }
        int start = getSelection().getStart();
        int end = getSelection().getEnd();
        String transformedSelection = transform.apply(selectedText);
        if (Objects.equals(selectedText, transformedSelection)) {
            scheduleHighlighting();
            return;
        }
        replaceText(start, end, transformedSelection);
    }


    public int findMatchingBracket(String text, int pos, char currentChar) {
        char matchChar;
        int direction;

        switch (currentChar) {
            case '(':
                matchChar = ')';
                direction = 1;
                break;
            case ')':
                matchChar = '(';
                direction = -1;
                break;
            case '[':
                matchChar = ']';
                direction = 1;
                break;
            case ']':
                matchChar = '[';
                direction = -1;
                break;
            case '{':
                matchChar = '}';
                direction = 1;
                break;
            case '}':
                matchChar = '{';
                direction = -1;
                break;
            default:
                return -1;
        }

        int balance = 0;
        for (int i = pos + direction; i >= 0 && i < text.length(); i += direction) {
            char c = text.charAt(i);
            if (c == currentChar) {
                balance++;
            } else if (c == matchChar) {
                if (balance == 0) {
                    return i;
                }
                balance--;
            }
        }
        return -1; // 未找到匹配括号
    }

    /**
     * Incremental highlight only the paragraphs around the change; fallback to full when the slice is large.
     */
    private void scheduleIncrementalHighlight(PlainTextChange change) {
        // 删除引号时直接做全量高亮，防止字符串状态错乱
        if (change.getRemoved() != null && (change.getRemoved().contains("'") || change.getRemoved().contains("\"") || change.getRemoved().contains("`"))) {
            scheduleHighlighting();
            return;
        }
        int start = Math.max(0, change.getPosition());
        int changeExtent = Math.max(change.getInserted().length(), change.getRemoved().length());
        int end = Math.min(getLength(), start + changeExtent);

        int startPara = Math.max(0, offsetToPosition(start, Bias.Backward).getMajor() - 1);
        int endPara = Math.min(getParagraphs().size() - 1, offsetToPosition(end, Bias.Forward).getMajor() + 1);
        int regionStart = getAbsolutePosition(startPara, 0);
        int regionEnd = getAbsolutePosition(endPara, getParagraph(endPara).length());
        int regionLen = regionEnd - regionStart;

        if (regionLen <= 0) {
            return;
        }

        // 尝试在局部范围内回溯，减少因未闭合引号/块注释导致的全量回退。
        int lookbackStart = Math.max(0, regionStart - LOOKBACK_RANGE);
        boolean unsafePrefix = hasOpenDelimiterBefore(regionStart, lookbackStart);
        int effectiveStart = unsafePrefix ? lookbackStart : regionStart;
        int effectiveLen = regionEnd - effectiveStart;

        if (effectiveLen > LOCAL_HIGHLIGHT_MAX) {
            scheduleHighlighting();
            return;
        }

        long seq = highlightSeq.incrementAndGet();
        String slice = getText(effectiveStart, regionEnd);
        AppExecutor.runAsync(() -> {
            var spans = KeywordsHighlightUtil.highlightSql(slice);
            Platform.runLater(() -> {
                if (highlightSeq.get() != seq) {
                    return; // outdated result
                }
                styleChangeFlag = 1;
                resetLastBracketStyle(this);
                setStyleSpans(effectiveStart, spans);
                applyMatchingBracketHighlight(getCaretPosition());
                styleChangeFlag = 0;
            });
        });
    }

    /**
     * 判断 regionStart 之前是否有未闭合的字符串/块注释，若有则需要全量重算。
     */
    private boolean hasOpenDelimiterBefore(int regionStart, int scanStart) {
        String prefix = regionStart <= 0 ? "" : getText(scanStart, regionStart);
        // 简单奇偶计数，针对 SQL 的 '' / "" / ``。若为奇数视为未闭合。
        if ((countChar(prefix, '\'') & 1) == 1) return true;
        if ((countChar(prefix, '\"') & 1) == 1) return true;
        if ((countChar(prefix, '`') & 1) == 1) return true;

        // 块注释未闭合：最后出现的 /* 在最后一个 */ 之后
        int lastOpen = prefix.lastIndexOf("/*");
        int lastClose = prefix.lastIndexOf("*/");
        return lastOpen > lastClose;
    }

    private int countChar(String text, char ch) {
        int cnt = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ch) {
                // 对 SQL 来说，使用两个相邻引号 '' 作为转义，这里仍然按出现次数计数，奇偶即可。
                cnt++;
            }
        }
        return cnt;
    }

    /**
     * Run syntax highlighting off the FX thread and ignore stale runs.
     */
    private void scheduleHighlighting() {
        long seq = highlightSeq.incrementAndGet();
        String snapshot = getText();
        AppExecutor.runAsync(() -> {
            var spans = KeywordsHighlightUtil.highlightSql(snapshot);
            Platform.runLater(() -> {
                if (highlightSeq.get() != seq) {
                    return; // outdated result
                }
                styleChangeFlag = 1;
                resetLastBracketStyle(this);
                setStyleSpans(0, spans);
                applyMatchingBracketHighlight(getCaretPosition());
                styleChangeFlag = 0;
            });
        });
    }

    // ---- completion ----

    /**
     * Set the active connection/database context for metadata-aware completion.
     * Call from the owning controller when connection or database selection changes.
     */
    public void setCompletionContext(Connect connect, Catalog database) {
        this.activeConnect = connect;
        this.activeDatabase = database;
    }

    /** Debounced trigger invoked from the KEY_TYPED event filter. */
    private void scheduleCompletion() {
        if (!isAutocompleteEnabled()) {
            return;
        }
        int delayMs = getAutocompleteTriggerDelayMs();
        long seq = completionSeq.incrementAndGet();
        // KEY_TYPED fires before the character is inserted; by the time
        // doComplete runs (50ms+ later on FX thread) the char will be in
        // the text and the caret will have advanced by one.
        int caret = getCaretPosition() + 1;
        AppExecutor.runAsync(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            if (completionSeq.get() != seq) {
                return; // newer keystroke arrived
            }
            Platform.runLater(() -> doComplete(caret, false));
        });
    }

    /** Immediate (Ctrl+Space) trigger — bypasses min-prefix check. */
    private void triggerCompletionNow() {
        long seq = completionSeq.incrementAndGet();
        int caret = getCaretPosition();
        Platform.runLater(() -> {
            if (completionSeq.get() != seq) return;
            doComplete(caret, true);
        });
    }

    private void doComplete(int caretPos, boolean forceShow) {
        if (!isAutocompleteEnabled()) {
            return;
        }
        String sql = getText();
        CompletionEngine.CompletionResult result = completionEngine.complete(
                sql, caretPos, activeConnect, activeDatabase);
        // Auto-popup requires >=2 char prefix; Ctrl+Space bypasses
        if (result.shouldShow() && (forceShow || result.minPrefixMet())) {
            completionPopup.show(this, result.getItems());
        } else {
            completionPopup.hide();
        }
    }

    private static boolean isAutocompleteEnabled() {
        String val = ConfigManagerUtil.getProperty(AUTOCOMPLETE_ENABLED_KEY, "true");
        return !"false".equalsIgnoreCase(val);
    }

    private static int getAutocompleteTriggerDelayMs() {
        String val = ConfigManagerUtil.getProperty(AUTOCOMPLETE_TRIGGER_DELAY_MS_KEY,
                String.valueOf(DEFAULT_TRIGGER_DELAY_MS));
        try {
            int ms = Integer.parseInt(val.trim());
            return Math.max(10, Math.min(ms, 1000));
        } catch (NumberFormatException e) {
            return DEFAULT_TRIGGER_DELAY_MS;
        }
    }
}
