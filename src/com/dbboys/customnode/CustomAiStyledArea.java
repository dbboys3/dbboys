package com.dbboys.customnode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.reactfx.util.Either;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import java.nio.file.Files;
import java.nio.file.Path;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.util.AlertUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.Parent;
import javafx.application.Platform;

/**
 * 专用于 AI 对话消息展示的区域：
 * - 显示 Markdown 标题（#、## 等），但不自动编号、不自动缩进
 * - 代码块宽度与本区域一致，不单独出现内部滚动条
 * - 其它解析逻辑参考 CustomGenericStyledArea（表格、图片等）
 */
public class CustomAiStyledArea extends CustomGenericStyledArea {
    private static final Logger log = LogManager.getLogger(CustomAiStyledArea.class);

    private volatile boolean heightUpdateScheduled = false;

    public CustomAiStyledArea() {
        super(new java.io.File("ai-inline.md"));
        getStyleClass().add("CustomAiStyledArea");
        setEditable(false);
        setWrapText(true);
        setStyle("-fx-font-family: system; -fx-font-size: 10px;");
        contextMenu.getItems().setAll(copyItem);
        setContextMenu(contextMenu);
        addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (getSelectedText().isEmpty()) {
                contextMenu.hide();
                event.consume();
            }
        });

        // 关键：高度随内容变化
        setMinHeight(Region.USE_PREF_SIZE);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        // 监听内容总高度估算，动态设置 prefHeight
        totalHeightEstimateProperty().addListener((obs, oldVal, newVal) -> {
            scheduleHeightUpdate();
        });

        // 关键：宽度变化会触发自动换行，高度需要二次刷新
        widthProperty().addListener((obs, oldVal, newVal) -> scheduleHeightUpdate());

        // 不在自身消费滚轮事件，将滚动转发给父容器（如外层 ScrollPane）
        addEventFilter(ScrollEvent.SCROLL, event -> {
            Parent parent = getParent();
            if (parent != null) {
                parent.fireEvent(event.copyFor(parent, parent));
            }
            event.consume();
        });
    }

    private void scheduleHeightUpdate() {
        if (heightUpdateScheduled) {
            return;
        }
        heightUpdateScheduled = true;
        Platform.runLater(() -> {
            try {
                Object est = totalHeightEstimateProperty().getValue();
                if (est instanceof Number n) {
                    setPrefHeight(n.doubleValue() + 10);
                }
            } finally {
                heightUpdateScheduled = false;
            }
        });
    }

    /**
     * 简化 Markdown 解析：
     * - 支持 ``` 包裹的代码块，整体作为 AI 代码块显示（宽度跟随区域）
     * - 标题使用 Markdown 的 # 前缀，显示为 heading-1/2/... 样式，但不自动编号
     * - 继承表格、图片等处理逻辑
     */
    @Override
    public void parseMarkdownWithStyles(String markdown) {
        String[] lines = markdown.split("\n");

        List<List<String>> tableRows = new ArrayList<>(); // 存储表格行数据
        boolean inTable = false; // 是否处于表格解析中

        boolean inCodeBlock = false;
        String codeLanguage = "";
        StringBuilder codeBlock = new StringBuilder();

        for (String line : lines) {
            // 去掉空行（保持行为与原来一致）
            if (line.trim().isEmpty()) {
                continue;
            }

            // 处理代码块
            if (line.trim().startsWith("```")) {

                // 结束上一个未完成的 table
                if (inTable) {
                    inTable = false;
                    if (tableRows.size() >= 1) {
                        Node tableNode = createTableView(tableRows);
                        append(Either.right(tableNode), "");
                        appendText("\n");
                    }
                    tableRows.clear();
                }

                if (!inCodeBlock) {
                    // 开始代码块
                    inCodeBlock = true;
                    codeLanguage = line.substring(3).trim();
                    codeBlock.setLength(0);
                } else {
                    // 结束代码块
                    inCodeBlock = false;

                    if (codeBlock.length() > 0) {
                        CustomInfoCodeArea codeArea = createCodeBlockArea(codeBlock.toString().trim());
                        append(Either.right(codeArea), "");
                        appendText("\n");
                    }
                    codeBlock.setLength(0);
                }
                continue;
            }

            if (inCodeBlock) {
                codeBlock.append(line).append("\n");
                continue;
            }

            // 表格
            if (inTable && TABLE_SEPARATOR_PATTERN.matcher(line).matches()) {
                // 识别为表头分隔线，不添加到数据行，仅作为表格结构标识
                continue;
            } else if (TABLE_LINE_PATTERN.matcher(line).matches()) {
                // 识别为表格行
                inTable = true;
                // 分割单元格（去除首尾|，再按|分割）
                String[] cells = line.trim().replaceAll("^\\||\\|$", "").split("\\|");
                // 处理单元格内容（ trim 空格）
                List<String> row = new ArrayList<>();
                for (String cell : cells) {
                    row.add(cell.trim());
                }
                tableRows.add(row);
                continue;
            } else if (inTable) {
                // 表格结束，生成TableView并添加到内容中
                inTable = false;
                if (tableRows.size() >= 1) { // 至少需要表头行
                    Node tableNode = createTableView(tableRows);
                    append(Either.right(tableNode), "");
                    appendText("\n");
                }
                tableRows.clear();
            }

            // 标题：仅根据 # 级别应用 heading-X 样式，不自动编号
            if (HEADING_PATTERN.matcher(line).matches()) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                level = Math.min(Math.max(level, 1), 6);

                String titleContent = line.substring(level).trim()
                        .replace("**", ""); // 去掉粗体标记，交由样式控制
                String styleClass = "heading-" + level;

                append(Either.left(titleContent), styleClass);
                appendText("\n");
                continue;
            }

        // 处理图片（本地文件或网络图片）
        if (line.contains("![") && line.contains("](")) {
            Matcher imgMatcher = IMG_PATTERN.matcher(line);
            if (imgMatcher.find()) {
                String imgUrl = imgMatcher.group(1).trim();
                append(Either.right(createImageNode(imgUrl)), "");
                appendText("\n");
                scheduleHeightUpdate();
            }
            continue;
        }

            // 处理普通文本行（包含链接、行内代码、粗体等）
            if (!line.trim().isEmpty()) {
                processTextLine(line);
                appendText("\n");
            } else {
                appendText("\n");
            }
        }

        // 收尾：末尾未结束的表格
        if (inTable && tableRows.size() >= 1) {
            Node tableNode = createTableView(tableRows);
            append(Either.right(tableNode), "");
            appendText("\n");
            tableRows.clear();
        }

        // 收尾：末尾未结束的代码块
        if (inCodeBlock && codeBlock.length() > 0) {
            CustomInfoCodeArea codeArea = createCodeBlockArea(codeBlock.toString().trim());
            append(Either.right(codeArea), "");
        }

        // 去掉结尾多余的空行（避免只有一行内容却显示两行）
        int len = getLength();
        if (len > 0) {
            String tail = getText(len - 1, len);
            if ("\n".equals(tail)) {
                replaceText(len - 1, len, "");
            }
        }

        // 统一设置段落行距
        for (int i = 0; i < getParagraphs().size(); i++) {
            setParagraphStyle(i, "-fx-line-spacing: 6px");
        }

        // 解析完成后，延迟一帧做最终高度对齐（避免布局顺序导致高度偶发不匹配）
        scheduleHeightUpdate();
    }

    @Override
    protected void bindCodeBlockWidth(CustomInfoCodeArea codeArea) {
        codeArea.prefWidthProperty().bind(widthProperty().subtract(22));
    }

    @Override
    protected void onImageLoaded() {
        scheduleHeightUpdate();
    }

    @Override
    public void processTextLine(String line) {
        
        // 首先处理行内代码，因为它们不应该包含其他格式
        List<TextSegment> segments = new ArrayList<>();
        int lastIndex = 0;
        
        // 忽略行内代码解析，比如mysql的反引号，解析后就丢失了
        /* 
        Matcher codeMatcher = INLINE_CODE_PATTERN.matcher(line);
        while (codeMatcher.find()) {
            // 添加代码前的普通文本
            if (codeMatcher.start() > lastIndex) {
                String normalText = line.substring(lastIndex, codeMatcher.start());
                segments.add(new TextSegment(normalText, ""));
            }

            // 添加代码文本
            String codeText = codeMatcher.group(1);
            segments.add(new TextSegment(codeText, "code-inline"));

            lastIndex = codeMatcher.end();
        }
        */

        // 添加剩余文本
        if (lastIndex < line.length()) {
            String remainingText = line.substring(lastIndex);
            segments.add(new TextSegment(remainingText, ""));
        }

        // 处理每个段落的链接和粗体
        for (TextSegment segment : segments) {
            if ("code-inline".equals(segment.style)) {
                // 代码段不处理链接和粗体
                append(Either.left(segment.text), segment.style);
            } else {
                // 普通文本段处理链接和粗体
                processFormattedText(segment.text);
            }
        }
    }
}

