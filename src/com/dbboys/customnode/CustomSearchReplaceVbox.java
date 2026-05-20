package com.dbboys.customnode;

import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.NotificationUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import org.fxmisc.richtext.GenericStyledArea;

import java.util.Locale;

public class CustomSearchReplaceVbox extends VBox {
    public static final double EDIT_PANEL_WIDTH = 328;
    public static final double SEARCH_ONLY_PANEL_WIDTH = EDIT_PANEL_WIDTH;
    private static final double PANEL_PADDING_TOP = 6;
    private static final double PANEL_PADDING_RIGHT = 6;
    private static final double PANEL_PADDING_BOTTOM = 6;
    private static final double PANEL_PADDING_LEFT = 6;
    private static final double ROW_GAP = 6;
    private static final double MODE_COLUMN_WIDTH = 24;

    private GenericStyledArea<?, ?, ?> codeArea;
    private final CustomUserTextField findField;
    private final CustomUserTextField replaceField;
    private final ToggleButton  caseToggle;
    private final Button tobottomBtn = new Button();
    private final Button totopBtn = new Button();
    private final HBox replaceBox = new HBox(5);
    private int lastFindPosition = -1;
    private boolean replaceEnabled = true;
    private boolean replaceMode = false;



    // 构造方法：接收要绑定的CodeArea
    public CustomSearchReplaceVbox(GenericStyledArea<?, ?, ?> codeArea) {
        this.codeArea = codeArea;
        this.findField = new CustomUserTextField();
        this.replaceField = new CustomUserTextField();
        this.caseToggle = new ToggleButton();


        initUI();
        initEvents();
        setVisible(false);
        managedProperty().bind(visibleProperty());

        findField.setOnKeyPressed(event -> {
            if(event.isControlDown()&&event.getCode() == KeyCode.ENTER){
                findNext();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.R){
                if (!replaceEnabled) {
                    return;
                }
                if (isFindMode()) {
                    switchToReplaceMode();
                    focusReplaceField();
                } else {
                    switchToFindMode();
                    focusFindField();
                }
            }
        });

        replaceField.setOnKeyPressed(event -> {
            if(event.isControlDown()&&event.getCode() == KeyCode.ENTER){
                //findNext();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.R){
                if (!replaceEnabled) {
                    return;
                }
                switchToFindMode();
                focusFindField();
            }
        });
    }



    // 初始化UI布局
    private void initUI() {
        setMinHeight(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(ROW_GAP);
        setPadding(new Insets(PANEL_PADDING_TOP, PANEL_PADDING_RIGHT, PANEL_PADDING_BOTTOM, PANEL_PADDING_LEFT));
        getStyleClass().add("search-replace-panel");
        setPrefWidth(EDIT_PANEL_WIDTH);
        findField.promptTextProperty().bind(I18n.bind("searchreplace.find.prompt", "查找"));
        replaceField.promptTextProperty().bind(I18n.bind("searchreplace.replace.prompt", "替换"));

        // 查找区域
        HBox findBox = new HBox(ROW_GAP);
        findBox.setAlignment(Pos.CENTER_LEFT);
        findBox.setFillHeight(false);
        HBox.setHgrow(findField, Priority.ALWAYS);

        StackPane buttonPane = new StackPane();
        setFixedWidth(buttonPane, MODE_COLUMN_WIDTH);
        tobottomBtn.setFocusTraversable(false);
        tobottomBtn.setGraphic(IconFactory.group(IconPaths.SEARCH_REPLACE_TOGGLE_DOWN, 0.6, 0.6));
        tobottomBtn.getStyleClass().add("small");
        buttonPane.getChildren().add(tobottomBtn);
        findField.getStyleClass().add("search-replace-field");
        totopBtn.setFocusTraversable(false);
        totopBtn.setGraphic(IconFactory.group(IconPaths.SEARCH_REPLACE_TOGGLE_UP, 0.6, 0.6));
        totopBtn.getStyleClass().add("small");
        buttonPane.getChildren().add(totopBtn);

        caseToggle.setFocusTraversable(false);
        caseToggle.setGraphic(IconFactory.group(IconPaths.SEARCH_REPLACE_CASE_TOGGLE, 0.9, 0.6));
        caseToggle.setTooltip(new Tooltip());
        caseToggle.getTooltip().textProperty().bind(I18n.bind("searchreplace.case.tooltip", "区分大小写"));
        caseToggle.getStyleClass().add("searchReplaceCaseToggle");

        Button findPrevBtn = new Button("");
        findPrevBtn.setFocusTraversable(false);
        findPrevBtn.setGraphic(IconFactory.group(IconPaths.SEARCH_REPLACE_PREVIOUS, 0.4, 0.4));
        findPrevBtn.getStyleClass().add("small");
        findPrevBtn.setTooltip(new Tooltip());
        findPrevBtn.getTooltip().textProperty().bind(I18n.bind("searchreplace.prev.tooltip", "上一个"));

        Button findNextBtn = new Button("");
        findNextBtn.setFocusTraversable(false);
        findNextBtn.setGraphic(IconFactory.group(IconPaths.SEARCH_REPLACE_NEXT, 0.4, 0.4));
        findNextBtn.getStyleClass().add("small");
        findNextBtn.setTooltip(new Tooltip());
        findNextBtn.getTooltip().textProperty().bind(I18n.bind("searchreplace.next.tooltip", "下一个"));

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("small");
        closeBtn.setTooltip(new Tooltip());
        closeBtn.getTooltip().textProperty().bind(I18n.bind("searchreplace.close.tooltip", "关闭"));

        HBox findActionsBox = new HBox(5, caseToggle, findPrevBtn, findNextBtn, closeBtn);
        findActionsBox.setAlignment(Pos.CENTER_LEFT);
        findActionsBox.setFillHeight(false);
        findBox.getChildren().addAll(buttonPane, findField, findActionsBox);

        // 替换区域
        replaceBox.setAlignment(Pos.CENTER_LEFT);
        replaceBox.setSpacing(ROW_GAP);
        replaceBox.setFillHeight(false);
        Region replaceLeadSpacer = new Region();
        setFixedWidth(replaceLeadSpacer, MODE_COLUMN_WIDTH);
        replaceField.getStyleClass().add("search-replace-field");
        replaceField.prefWidthProperty().bind(findField.widthProperty());
        replaceField.maxWidthProperty().bind(findField.widthProperty());
        HBox.setHgrow(replaceField, Priority.ALWAYS);



        // 按钮区域
        Button replaceBtn = new Button();
        replaceBtn.setFocusTraversable(false);
        replaceBtn.setGraphic(IconFactory.group(IconPaths.SEARCH_REPLACE_ONE, 0.6, 0.6));
        replaceBtn.getStyleClass().add("small");
        replaceBtn.setTooltip(new Tooltip());
        replaceBtn.getTooltip().textProperty().bind(I18n.bind("searchreplace.replace.tooltip", "替换"));

        Button replaceAllBtn = new Button();
        replaceAllBtn.setFocusTraversable(false);
        replaceAllBtn.setGraphic(IconFactory.group(IconPaths.SEARCH_REPLACE_ALL, 0.6, 0.6));
        replaceAllBtn.getStyleClass().add("small");
        replaceAllBtn.setTooltip(new Tooltip());
        replaceAllBtn.getTooltip().textProperty().bind(I18n.bind("searchreplace.replace_all.tooltip", "全部替换"));

        HBox replaceActionsBox = new HBox(5, replaceBtn, replaceAllBtn);
        replaceActionsBox.setAlignment(Pos.CENTER_LEFT);
        replaceActionsBox.setFillHeight(false);

        replaceBox.getChildren().addAll(replaceLeadSpacer, replaceField, replaceActionsBox);
        // 组装面板
        getChildren().addAll(findBox, replaceBox);

        // 绑定按钮事件
        findNextBtn.setOnAction(e -> findNext());
        findPrevBtn.setOnAction(e -> findPrevious());
        replaceBtn.setOnAction(e -> replaceCurrent());
        replaceAllBtn.setOnAction(e -> replaceAll());
        closeBtn.setOnAction(e -> {
            if (totopBtn.isVisible()) {
                totopBtn.fire();
            }
            setVisible(false);
        });

        tobottomBtn.setOnAction(event -> switchToReplaceMode());
        totopBtn.setOnAction(event -> switchToFindMode());
        updateModeUi();

    }

    // 初始化事件
    private void initEvents() {
        // 输入内容变化时重置查找位置
        findField.textProperty().addListener((obs, oldVal, newVal) -> {
            lastFindPosition = -1;
            findNext();
        });

        // 区分大小写选项变化时重置查找位置
        caseToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            lastFindPosition = -1;
        });

        // 按Enter键触发"下一个"
        findField.setOnAction(e -> findNext());
        replaceField.setOnAction(e -> replaceCurrent());
    }

    // 查找下一个匹配项
    private void findNext() {
        if (codeArea == null) {
            return;
        }
        String findText = findField.getText();
        if (findText.isEmpty()||codeArea.getText().isEmpty()) {
            //System.out.println("请输入查找内容");
            return;
        }
        codeArea.selectRange(codeArea.getCaretPosition(),codeArea.getCaretPosition());

        String text = codeArea.getText();
        int startPos = (lastFindPosition == -1) ? 0 : lastFindPosition + 1;

        if (startPos >= text.length()) {
            startPos = 0; // 到达末尾，从头开始
        }

        int foundPos = findInText(text, findText, startPos, true);
        if (foundPos != -1) {
            highlightAndMove(foundPos, findText.length());
            lastFindPosition = foundPos;
        } else {
            NotificationUtil.showMainNotification(
                    I18n.t("searchreplace.notice.reach_end", "已到达结尾，下一个从开头开始查找！")
            );
            lastFindPosition = -1;
        }
    }

    // 查找上一个匹配项
    private void findPrevious() {
        if (codeArea == null) {
            return;
        }
        String findText = findField.getText();
        if (findText.isEmpty()||codeArea.getText().isEmpty()) {
            return;
        }
        codeArea.selectRange(codeArea.getCaretPosition(),codeArea.getCaretPosition());

        String text = codeArea.getText();
        int startPos = (lastFindPosition == -1) ? codeArea.getText().length() - 1 : lastFindPosition - 1;


        if (startPos < 0) {
            startPos = text.length() - 1; // 到达开头，从末尾开始
        }

        int foundPos = findInText(text, findText, startPos, false);
        if (foundPos != -1) {
            highlightAndMove(foundPos, findText.length());
            lastFindPosition = foundPos;
        } else {
            NotificationUtil.showMainNotification(
                    I18n.t("searchreplace.notice.reach_start", "已到达开头，下一个从结尾开始搜索!")
            );
            lastFindPosition = -1;
        }
    }

    // 替换当前匹配项
    private void replaceCurrent() {
        if (codeArea == null) {
            return;
        }
        String findText = findField.getText();
        String replaceText = replaceField.getText();

        if (findText.isEmpty()||codeArea.getText().isEmpty()) {
            return;
        }

        // 检查是否有选中的匹配项
        int caretPos = codeArea.getCaretPosition();
        if (lastFindPosition != -1 &&
                caretPos >= lastFindPosition &&
                caretPos <= lastFindPosition + findText.length()) {

            // 执行替换
            codeArea.replaceText(lastFindPosition, lastFindPosition + findText.length(), replaceText);

            // 继续查找下一个
            lastFindPosition = lastFindPosition + replaceText.length();
            findNext();
        } else {
            // 没有选中项，先查找再替换
            findNext();
        }
    }

    // 替换所有匹配项
    private void replaceAll() {
        if (codeArea == null) {
            return;
        }
        String findText = findField.getText();
        String replaceText = replaceField.getText();
        if (findText.isEmpty()||codeArea.getText().isEmpty()) {
            return;
        }

        String text = codeArea.getText();
        int count = 0;
        int pos = 0;

        // 构建新文本
        StringBuilder newText = new StringBuilder();

        while (pos <= text.length() - findText.length()) {
            int foundPos = findInText(text, findText, pos, true);
            if (foundPos == -1) break;

            // 追加找到的位置之前的文本
            newText.append(text.substring(pos, foundPos));
            // 追加替换文本
            newText.append(replaceText);

            pos = foundPos + findText.length();
            count++;
        }

        // 追加剩余文本
        newText.append(text.substring(pos));

        // 更新文本
        if (count > 0) {
            codeArea.replaceText(0, text.length(), newText.toString());
            NotificationUtil.showMainNotification(
                    String.format(I18n.t("searchreplace.notice.replace_all_count", "已替换全部 %d 处！"), count)
            );
            lastFindPosition = -1;
        } else {
        }
    }

    // 在文本中查找（核心查找逻辑）
    private int findInText(String text, String target, int startPos, boolean forward) {
        if (target.isEmpty() || startPos < 0 || startPos >= text.length()) {
            return -1;
        }

        // 根据是否区分大小写处理
        String textToCheck = text;
        String targetToCheck = target;

        if (!caseToggle.isSelected()) {
            textToCheck = text.toLowerCase(Locale.ROOT);
            targetToCheck = target.toLowerCase(Locale.ROOT);
        }

        if (forward) {
            // 正向查找
            return textToCheck.indexOf(targetToCheck, startPos);
        } else {
            // 反向查找
            for (int i = startPos; i >= 0; i--) {
                if (i + targetToCheck.length() > textToCheck.length()) {
                    continue;
                }
                String substring = textToCheck.substring(i, i + targetToCheck.length());
                if (substring.equals(targetToCheck)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // 高亮并移动光标到找到的位置
    private void highlightAndMove(int start, int length) {
        // 清除之前的高亮
        //codeArea.getStyleSpans(0, codeArea.getText().length()).clearStyle("search-highlight");
        // 添加新的高亮
        //codeArea.setStyleClass(start, start + length, "search-highlight");
        // 移动光标并确保可见

        codeArea.requestFollowCaret();

        /*
        codeArea.moveTo(start + length);

        // 确保该段落出现在视图中
        int paragraph = codeArea.offsetToPosition(start, TwoDimensional.Bias.Forward).getMajor();
        codeArea.showParagraphInViewport(paragraph);

         */
        //codeArea.requestFocus();
        //codeArea.showParagraphInViewport(codeArea.getCurrentParagraph());
        codeArea.selectRange(start, start + length);
    }

    // 显示面板并聚焦到查找框
    public void showPanel() {
        showFindPanel();
    }

    public void setCodeArea(GenericStyledArea<?, ?, ?> codeArea) {
        this.codeArea = codeArea;
        lastFindPosition = -1;
    }

    public void showFindPanel() {
        setVisible(true);
        switchToFindMode();
        focusFindField();
        lastFindPosition = -1;
    }

    public void showReplacePanel() {
        setVisible(true);
        if (replaceEnabled) {
            switchToReplaceMode();
            focusFindField();
        } else {
            switchToFindMode();
            focusFindField();
        }
        lastFindPosition = -1;
    }

    public void focusFindField() {
        findField.requestFocus();
    }

    public void focusReplaceField() {
        replaceField.requestFocus();
    }

    public boolean isFindMode() {
        return !replaceMode;
    }

    public void switchToReplaceMode() {
        if (!replaceEnabled) {
            return;
        }
        replaceMode = true;
        updateModeUi();
    }

    public void switchToFindMode() {
        replaceMode = false;
        updateModeUi();
    }

    public void hideModeToggleButtons() {
        setReplaceEnabled(false);
    }

    public void setReplaceEnabled(boolean replaceEnabled) {
        this.replaceEnabled = replaceEnabled;
        if (!replaceEnabled) {
            replaceMode = false;
        }
        updateModeUi();
    }

    private void updateModeUi() {
        if (!replaceEnabled) {
            tobottomBtn.setVisible(true);
            tobottomBtn.setDisable(true);
            totopBtn.setVisible(false);
            totopBtn.setManaged(false);
            replaceBox.setVisible(false);
            replaceBox.setManaged(false);
            return;
        }

        tobottomBtn.setDisable(false);
        tobottomBtn.setVisible(!replaceMode);
        totopBtn.setVisible(replaceMode);
        totopBtn.setManaged(replaceMode);
        replaceBox.setVisible(replaceMode);
        replaceBox.setManaged(replaceMode);
    }

    private void setFixedWidth(Region region, double width) {
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }



}
