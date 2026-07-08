package com.dbboys.ui.component;

import com.dbboys.app.AppExecutor;
import com.dbboys.ui.component.CustomShortcutMenuItem;
import com.dbboys.ui.component.completion.CompletionEngine;
import com.dbboys.ui.component.completion.CompletionItem;
import com.dbboys.ui.component.completion.CompletionPopup;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.config.ConfigManagerUtil;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.KeywordsHighlightUtil;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.infra.util.SqlParserUtil;
import com.dbboys.model.Catalog;
import com.dbboys.model.Connect;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.SeparatorMenuItem;
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
    private static final int LOOKBACK_RANGE = 2000; // 娑撳﹥鏋冮張鈧径姘礀濠ь垵绻栨稊鍫濐樋鐎涙顑佺亸婵婄槸鐏炩偓闁劑鐝敓?
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
    // AI submenu and items (accessible from SqlTabController for action wiring)
    public final CustomMenu aiMenu;
    public final CustomShortcutMenuItem aiFormatSqlItem;
    public final CustomShortcutMenuItem aiOptimizeSqlItem;
    public final CustomShortcutMenuItem aiConvertOracleItem;
    public final CustomShortcutMenuItem aiConvertMysqlItem;
    public final CustomShortcutMenuItem aiConvertInformixItem;
    public final CustomShortcutMenuItem aiConvertPostgresqlItem;
    public final CustomShortcutMenuItem aiConvertSqlserverItem;
    public final CustomShortcutMenuItem aiConvertSqliteItem;
    public final CustomShortcutMenuItem aiFixSqlItem;
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

        // AI submenu
        aiMenu = new CustomMenu();
        aiMenu.textProperty().bind(I18n.bind("sql.ai.menu", "AI"));
        aiMenu.setGraphic(IconFactory.group(IconPaths.AI_TAB_TOGGLE, 0.55));

        aiFormatSqlItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.formatSql", null);
        aiOptimizeSqlItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.optimizeSql", null);
        aiConvertOracleItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.convertOracle", null);
        aiConvertMysqlItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.convertMysql", null);
        aiConvertInformixItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.convertInformix", null);
        aiConvertPostgresqlItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.convertPostgresql", null);
        aiConvertSqlserverItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.convertSqlserver", null);
        aiConvertSqliteItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.convertSqlite", null);
        aiFixSqlItem = MenuItemUtil.createMenuItemI18n("sql.ai.menu.fixSql", null);
        aiMenu.getItems().addAll(
                aiFormatSqlItem, aiOptimizeSqlItem, aiFixSqlItem,
                new SeparatorMenuItem(),
                aiConvertOracleItem, aiConvertMysqlItem, aiConvertInformixItem,
                aiConvertPostgresqlItem, aiConvertSqlserverItem, aiConvertSqliteItem
        );

        ContextMenu codeAreaMenu = new CustomContextMenu();
        codeAreaMenu.getItems().addAll(
                codeAreaExecuteItem, codeAreaFormatItem, codeAreaUpperItem, codeAreaLowerItem,
                codeAreaCommRowItem, codeAreaCommRowsItem, codeAreaSearchItem, codeAreaCopyItem,
                codeAreaCutItem, codeAreaPasteItem, codeAreaUndoItem, codeAreaRedoItem, codeAreaSaveItem,
                aiMenu
        );

        // 鐞涖儱鍙忓鍦崶鐎佃壈鍩?閿?閿?EventFilter閿涘牊宕熼懢鐑芥▉濞堢绱氶幏锔藉焻閿涘苯绻€妞よ婀?RichTextFX 鐞涘奔璐熺仦鍌氼槱閻炲棔绠ｉ敓?
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
                    completeSelectionApplied = true;
                    completionPopup.applySelection();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.ESCAPE) {
                    completionPopup.hide();
                    event.consume();
                    return;
                }
            }
        });

        // ctrl韫囶偅宓庨敓?
        setOnKeyPressed(event -> {
            if (completionPopup.isShowing()) {
                // 鐞涖儱鍙忓鍦崶閺勫墽銇氶弮鍓佹畱閹稿鏁鑼病閸︺劋绗傞棃銏㈡畱 EventFilter 婢跺嫮鎮婇敓?
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
        // Ctrl + 姒х姵鐖ｅ姘崇枂閿涙矮绗?Ctrl++/Ctrl+- 閻╃鎮撻敍宀冪殶閿?SQL 缂傛牞绶崳銊ョ摟閿?
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
        // 閼奉亜濮╃悰銉ュ弿/鐠哄疇绻冮幋鎰嚠瀵洖褰块敍宀勪缉閸忓秹鍣告径宥嗗絻閸忋儻绱欐担璺ㄦ暏 EventFilter閿涘苯鍘涙禍搴ㄧ帛鐠併倕顦╅悶鍡礆
        addEventFilter(KeyEvent.KEY_TYPED, event -> {
            // 鐞涖儱鍙忛柅澶夎厬閸氬氦鐑︽潻鍥╂彛閹恒儳娼冮惃鍕瘻闁款喖鐡х粭锔肩礄閸ョ偠婧?閸掓儼銆冪粭锔肩礆閿涘矂妲诲銏ｆ嫹閸旂姴鍩岄弴鎸庡床閺傚洦婀伴崥搴ㄦ桨
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
                event.consume(); // 闂冪粯顒涙妯款吇閹绘帒鍙?

                // 婵″倹鐏夐崗澶嬬垼閸欏厖鏅跺鑼病閺勵垰鎮撻弽椋庢畱瀵洖褰块敍灞藉灟鐟欏棔璐?鐠哄疇绻?閼板矂娼崘宥嗗絻閿?
                if (caret < textLen && getText(caret, caret + 1).charAt(0) == c) {
                    moveTo(caret + 1);
                    return;
                }

                String selected = getSelectedText();
                if (selected != null && !selected.isEmpty()) {
                    replaceSelection(ch + selected + ch);
                    selectRange(caret + 1 + selected.length(), caret + 1 + selected.length());
                } else {
                    insertText(caret, ch + ch); // 閹鍙℃稉銈勯嚋鐎涙顑?
                    moveTo(caret + 1);
                }
            }
            // 缁岀儤鐗?-> 閸忔娊妫寸悰銉ュ弿閿涘牓娅庨棃鐐插帨閺嶅洨鎻ｉ敓?FROM/JOIN 閸忔娊鏁€涙ぞ绠ｉ崥搴礆
                        if (c == ' ') {
                completionPopup.hide();
                // After a space in FROM/JOIN/UPDATE context, eagerly show tables.
                // For all other spaces, just hide without triggering completion.
                if (isAutocompleteEnabled()) {
                    int caret = getCaretPosition();
                    if (isAfterTableKeyword(getText(), caret)) {
                        long seq = completionSeq.get();
                        Platform.runLater(() -> {
                            if (completionSeq.get() != seq) return;
                            doComplete(caret + 1, false);
                        });
                    }
                }
                return;
            }
            // 閺呴缚鍏樼悰銉ュ弿鐟欙箑褰傞敍姘摟濮ｅ秲鈧焦鏆熺€涙ぜ鈧椒绗呴崚鎺斿殠閵嗕胶鍋ｉ敓?-> 50ms 闂冨弶濮堥崥搴¤剨閿?
            if (c == '.' || Character.isLetterOrDigit(c) || c == '_') {
                scheduleCompletion();
            } else {
                completionPopup.hide();
            }
        });

        // Backspace / Delete -> 閺囧瓨鏌婇幋鏍у彠闂傤叀藟閿?
        addEventFilter(KeyEvent.KEY_RELEASED, event2 -> {
            if (event2.getCode() == KeyCode.BACK_SPACE || event2.getCode() == KeyCode.DELETE) {
                if (!isAutocompleteEnabled()) return;
                Platform.runLater(() -> {
                    completionSeq.incrementAndGet(); // cancel pending
                    int caret = getCaretPosition();
                    doComplete(caret, false);
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
            aiMenu.setDisable(!hasSelection);
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


    @Override
    public void paste() {
        super.paste();
        scheduleHighlighting();
    }
    @Override
    public void replaceText(int start, int end, String text) {
        String oldText = getText(start, end);
        super.replaceText(start, end, text);
        if (Objects.equals(oldText, text)) {
            scheduleHighlighting();
        }
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
        return -1; // 閺堫亝澹橀崚鏉垮爱闁板秵瀚敓?
    }

    /**
     * Incremental highlight only the paragraphs around the change; fallback to full when the slice is large.
     */
    private void scheduleIncrementalHighlight(PlainTextChange change) {
        // 閸掔娀娅庡鏇炲娇閺冨墎娲块幒銉ヤ粵閸忋劑鍣烘妯瑰瘨閿涘矂妲诲銏犵摟缁楋缚瑕嗛悩鑸碘偓渚€鏁婇敓?
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

        // 鐏忔繆鐦崷銊ョ湰闁劏瀵栭崶鏉戝敶閸ョ偞鍑介敍灞藉櫤鐏忔垵娲滈張顏堟４閸氬牆绱╅敓?閸ф鏁為柌濠傤嚤閼峰娈戦崗銊╁櫤閸ョ偤鈧偓閿?
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
     * 閸掋倖鏌?regionStart 娑斿澧犻弰顖氭儊閺堝婀梻顓炴値閻ㄥ嫬鐡х粭锔胯/閸ф鏁為柌濠忕礉閼汇儲婀侀崚娆撴付鐟曚礁鍙忛柌蹇涘櫢缁犳鎷?
     */
    private boolean hasOpenDelimiterBefore(int regionStart, int scanStart) {
        String prefix = regionStart <= 0 ? "" : getText(scanStart, regionStart);
        // 缁犫偓閸楁洖顨岄崑鎯邦吀閺佸府绱濋柦鍫濐嚠 SQL 閿?'' / "" / ``閵嗗倽瀚㈡稉鍝勵殞閺佹媽顫嬫稉鐑樻弓闂傤厼鎮庨敓?
        if ((countChar(prefix, '\'') & 1) == 1) return true;
        if ((countChar(prefix, '\"') & 1) == 1) return true;
        if ((countChar(prefix, '`') & 1) == 1) return true;

        // 閸ф鏁為柌濠冩弓闂傤厼鎮庨敍姘付閸氬骸鍤悳鎵畱 /* 閸︺劍娓堕崥搴濈閿?*/ 娑斿鎮?
        int lastOpen = prefix.lastIndexOf("/*");
        int lastClose = prefix.lastIndexOf("*/");
        return lastOpen > lastClose;
    }

    private int countChar(String text, char ch) {
        int cnt = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ch) {
                // 閿?SQL 閺夈儴顕╅敍灞煎▏閻劋琚辨稉顏嗘祲闁绱╅敓?'' 娴ｆ粈璐熸潪顑跨疅閿涘矁绻栭柌灞肩矝閻掕埖瀵滈崙铏瑰箛濞嗏剝鏆熺拋鈩冩殶閿涘苯顨岄崑璺哄祮閸欘垽鎷?
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

    /**
     * Trigger a background fetch of schema objects (tables/views/synonyms/system tables)
     * for the active connection+database.  Results populate the cache and will be
     * available for the next autocomplete query.
     */
    public void refreshSchemaObjects(Connect connect, Catalog database) {
        completionEngine.refreshSchemaObjects(connect, database);
    }

    /** Debounced trigger invoked from the KEY_TYPED event filter. */
    private void scheduleCompletion() {
        if (!isAutocompleteEnabled()) {
            return;
        }
        int delayMs = getAutocompleteTriggerDelayMs();
        long seq = completionSeq.get(); // don't increment 閳?let pending scheduleCompletion still fire
        AppExecutor.runAsync(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            Platform.runLater(() -> {
                if (completionSeq.get() != seq) {
                    return; // newer keystroke arrived
                }
                doComplete(getCaretPosition(), false);
            });
        });
    }

    /** Immediate (Ctrl+Space) trigger 閿?bypasses min-prefix check. */
    private void triggerCompletionNow() {
        long seq = completionSeq.get(); // don't increment 閳?let pending scheduleCompletion still fire
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
    /** True when the caret follows a FROM/JOIN clause keyword ("from", "join",
     *  "into", "update", "table", "view") that expects a table name next.
     *  Duplicates the set in SchemaObjectProvider#CLAUSE_KEYWORDS.
     */
    private static boolean isAfterTableKeyword(String text, int caret) {
        if (caret <= 0 || caret > text.length()) return false;
        // Walk back past trailing spaces to find the last word
        int end = caret;
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        if (end <= 0) return false;
        // Only trigger for the first space after the keyword (not subsequent spaces)
        if (end < caret) return false;
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)))) start--;
        String word = text.substring(start, end).toLowerCase(java.util.Locale.ROOT);
        return "from".equals(word) || "join".equals(word)
            || "into".equals(word) || "update".equals(word)
            || "table".equals(word) || "view".equals(word);
    }

}
