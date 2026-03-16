package com.dbboys.customnode;

import com.dbboys.app.AppExecutor;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.customnode.CustomShortcutMenuItem;
import com.dbboys.util.KeywordsHighlightUtil;
import com.dbboys.util.MenuItemUtil;
import com.dbboys.util.SqlParserUtil;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.*;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomSqlEditCodeArea extends CodeArea {
    private static final int LOCAL_HIGHLIGHT_MAX = 4000;
    private static final int LOOKBACK_RANGE = 2000; // 上文最多回溯这么多字符尝试局部高亮

    private final int[] sqlEditCodeAreaCursorPosition = {0, 0};
    private int styleChangeFlag = 0;
    private final AtomicLong highlightSeq = new AtomicLong(0);
    private Runnable onSaveRequest = () -> {};
    private Runnable onContentDirty = () -> {};
    private Runnable onShowFindPanel = () -> {};
    private Runnable onShowReplacePanel = () -> {};
    private Runnable onExecuteRequest = () -> {};
    private BooleanSupplier saveDisabledSupplier = () -> true;
    private BooleanSupplier executeDisabledSupplier = () -> true;

    public CustomSqlEditCodeArea() {
        super();
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

        // ctrl快捷键
        setOnKeyPressed(event -> {
            if(event.isControlDown()&&event.getCode() == KeyCode.S){
                onSaveRequest.run();
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
        // 自动补全/跳过成对引号，避免重复插入（使用 EventFilter，先于默认处理）
        addEventFilter(KeyEvent.KEY_TYPED, event -> {
            String ch = event.getCharacter();
            if (ch == null || ch.length() != 1) {
                return;
            }
            char c = ch.charAt(0);
            if ((c == '"' || c == '\'' || c == '`') && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                int caret = getCaretPosition();
                int textLen = getLength();
                event.consume(); // 阻止默认插入

                // 如果光标右侧已经是同样的引号，则视为“跳过”而非再插入
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
            highlightMatchingBracket(this, newPos, sqlEditCodeAreaCursorPosition);
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

    public void highlightMatchingBracket(CodeArea codeArea, int caretPosition,int[] last_pos) {
        String text = codeArea.getText();
        int matchPos = -1;
        if (caretPosition < text.length()) {
            resetLastBracketStyle(codeArea, text, last_pos);
            char rightChar = text.charAt(caretPosition);
            if ("()[]{}".indexOf(rightChar) != -1) {
                matchPos = findMatchingBracket(text, caretPosition, rightChar);
                if (matchPos != -1) {
                    codeArea.setStyleClass(caretPosition, caretPosition + 1, "bracket-highlight");
                    codeArea.setStyleClass(matchPos, matchPos + 1, "bracket-highlight");
                    last_pos[0] = caretPosition;
                    last_pos[1] = matchPos;
                    return;
                }
            }
        }
        if (caretPosition > 0) {
            resetLastBracketStyle(codeArea, text, last_pos);
            char leftChar = text.charAt(caretPosition - 1);
            if ("()[]{}".indexOf(leftChar) != -1) {
                matchPos = findMatchingBracket(text, caretPosition - 1, leftChar);
                if (matchPos != -1) {
                    codeArea.setStyleClass(caretPosition - 1, caretPosition, "bracket-highlight");
                    codeArea.setStyleClass(matchPos, matchPos + 1, "bracket-highlight");
                    last_pos[0] = caretPosition-1;
                    last_pos[1] = matchPos;
                }
            }
        }
    }

    private void resetLastBracketStyle(CodeArea codeArea, String text, int[] last_pos) {
        if(last_pos[0]==0){
            return;
        }
        try {
            if((text.charAt(last_pos[0])=='{'&&text.charAt(last_pos[1])=='}')||(text.charAt(last_pos[0])=='}'&&text.charAt(last_pos[1])=='{')){
                codeArea.setStyleClass(last_pos[0], last_pos[0] + 1, "comment");
                codeArea.setStyleClass(last_pos[1], last_pos[1] + 1, "comment");
            }else if((text.charAt(last_pos[0])=='('&&text.charAt(last_pos[1])==')')||(text.charAt(last_pos[0])==')'&&text.charAt(last_pos[1])=='(')){
                codeArea.setStyleClass(last_pos[0], last_pos[0] + 1, "paren");
                codeArea.setStyleClass(last_pos[1], last_pos[1] + 1, "paren");
            }
        }catch (Exception ignored){
        }finally {
            last_pos[0]=0;
            last_pos[1]=0;
        }
    }

    private void fireExecute() {
        if (!executeDisabledSupplier.getAsBoolean()) {
            onExecuteRequest.run();
        }
    }

    private void applyTransform(java.util.function.Function<String, String> transform) {
        String selectedText = getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            replaceText(transform.apply(getText()));
            return;
        }
        int start = getSelection().getStart();
        int end = getSelection().getEnd();
        replaceText(start, end, transform.apply(selectedText));
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
                setStyleSpans(effectiveStart, spans);
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
                setStyleSpans(0, spans);
                styleChangeFlag = 0;
            });
        });
    }
}
