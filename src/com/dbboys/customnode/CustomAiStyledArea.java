package com.dbboys.customnode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.reactfx.util.Either;

import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.File;
import java.io.IOException;
import java.net.URL;

import javafx.stage.FileChooser;
import javafx.beans.value.ChangeListener;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.util.NotificationUtil;
import com.dbboys.util.DownloadManagerUtil;
import com.dbboys.util.AlertUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.Parent;

/**
 * 专用于 AI 对话消息展示的区域：
 * - 显示 Markdown 标题（#、## 等），但不自动编号、不自动缩进
 * - 代码块宽度与本区域一致，不单独出现内部滚动条
 * - 其它解析逻辑参考 CustomGenericStyledArea（表格、图片等）
 */
public class CustomAiStyledArea extends CustomGenericStyledArea {
    private static final Logger log = LogManager.getLogger(CustomAiStyledArea.class);
    /** 专供 AI 区域网络图片使用的右键菜单，避免每次右键重复创建多个菜单实例 */
    private final javafx.scene.control.ContextMenu aiImageContextMenu = new javafx.scene.control.ContextMenu();
    private final javafx.scene.control.MenuItem aiImageSaveAsItem =
            new javafx.scene.control.MenuItem(I18n.t("genericstyled.menu.image_save_as"));

    public CustomAiStyledArea() {
        super(new java.io.File("ai-inline.md"));
        getStyleClass().add("CustomAiStyledArea");
        setEditable(false);
        setWrapText(true);
        setStyle("-fx-font-family: system; -fx-font-size: 11px;");
        // AI 对话不需要右键菜单，避免误操作
        setContextMenu(null);

        // 初始化 AI 图片右键菜单：仅包含“图片另存为”
        aiImageContextMenu.getItems().setAll(aiImageSaveAsItem);

        // 关键：高度随内容变化
        setMinHeight(Region.USE_PREF_SIZE);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        // 监听内容总高度估算，动态设置 prefHeight
        totalHeightEstimateProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                // +10 预留一点内边距，可根据需要调整
                setPrefHeight((double)newVal + 10);
            }
        });

        // 不在自身消费滚轮事件，将滚动转发给父容器（如外层 ScrollPane）
        addEventFilter(ScrollEvent.SCROLL, event -> {
            Parent parent = getParent();
            if (parent != null) {
                parent.fireEvent(event.copyFor(parent, parent));
            }
            event.consume();
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
            if (line.startsWith("```")) {

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
                        TextArea codeArea = createCodeBlockArea(codeBlock.toString().trim());
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

                ImageView imgView = new ImageView(new Image("file:images/failed.png"));
                imgView.setPreserveRatio(true);
                imgView.setFitWidth(500);
                StackPane pane = new StackPane(imgView);
                pane.prefWidthProperty().bind(widthProperty());
                try {
                    if (imgUrl.startsWith("http://") || imgUrl.startsWith("https://")) {
                        // 网络图片：直接使用 URL 加载，仅提供“另存为”右键菜单，参考通用下载逻辑
                        imgView.setImage(new Image(imgUrl, true));
                        pane.setOnContextMenuRequested(event -> {
                            // 复用同一个 ContextMenu，避免多次右键出现多个菜单
                            aiImageSaveAsItem.setOnAction(event1 -> {
                                FileChooser fileChooser = new FileChooser();
                                fileChooser.setTitle(I18n.t("genericstyled.filechooser.image_save_as"));

                                // 默认文件名：先尝试通过重定向获取真实文件名，失败则从 URL path 猜
                                String defaultName = "image";
                                try {
                                    String name = DownloadManagerUtil.getRealFileNameFromRedirect(imgUrl);
                                    if (name != null && !name.isEmpty()) {
                                        defaultName = name;
                                    } else {
                                        URL u = new URL(imgUrl);
                                        String path = u.getPath();
                                        int slash = path.lastIndexOf('/');
                                        if (slash >= 0 && slash < path.length() - 1) {
                                            defaultName = path.substring(slash + 1);
                                        }
                                    }
                                } catch (Exception ex) {
                                    log.error(ex.getMessage(), ex);
                                    AlertUtil.CustomAlert(I18n.t("genericstyled.alert.download_error"), ex.getMessage());
                                    return;
                                }

                                fileChooser.setInitialFileName(defaultName);
                                fileChooser.getExtensionFilters().addAll(
                                        new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.image_files"), "*.png", "*.jpg", "*.jpeg", "*.gif"),
                                        new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.all_files"), "*.*")
                                );

                                File file = fileChooser.showSaveDialog(AppState.getWindow());
                                if (file != null) {
                                    if (file.exists()) {
                                        file.delete();
                                    }
                                    // 使用下载管理器处理网络图片下载
                                    DownloadManagerUtil.addDownload(imgUrl, file, true, null);
                                }
                            });

                            aiImageContextMenu.show(pane, event.getScreenX(), event.getScreenY());
                            event.consume();
                        });
                    } else {
                        // 本地图片：按照原有逻辑解析相对路径
                        Path path = getAbsPath(markdownFile, imgUrl);

                        if (Files.exists(path)) {
                            imgView.setImage(new Image("file:" + path));
                        }
                        Image image = imgView.getImage();
                        if (Files.exists(path)) {
                            pane.setOnContextMenuRequested(event -> {
                                imageSaveAsItem.setOnAction(event1 -> {
                                    FileChooser fileChooser = new FileChooser();
                                    fileChooser.setTitle(I18n.t("genericstyled.filechooser.image_save_as"));

                                    String originalFileName = new File(path.toUri()).getName();
                                    fileChooser.setInitialFileName(originalFileName);
                                    fileChooser.getExtensionFilters().addAll(
                                            new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.image_files"), "*.png", "*.jpg", "*.jpeg", "*.gif"),
                                            new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.all_files"), "*.*")
                                    );

                                    File targetFile = fileChooser.showSaveDialog(AppState.getWindow());
                                    if (targetFile == null) {
                                        return;
                                    }

                                    try {
                                        Files.copy(
                                                path,
                                                targetFile.toPath(),
                                                StandardCopyOption.REPLACE_EXISTING
                                        );
                                        NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.image_saved"));
                                    } catch (IOException e) {
                                        log.error("Operation failed", e);
                                    }
                                });
                                imageCopyItem.setOnAction(event1 -> {
                                    Clipboard clipboard = Clipboard.getSystemClipboard();
                                    ClipboardContent content = new ClipboardContent();
                                    content.putImage(image);
                                    clipboard.setContent(content);
                                    NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.image_copied"));

                                });
                                imageContextMenu.show(pane, event.getScreenX(), event.getScreenY());
                                event.consume();
                            });
                        }
                    }
                    append(Either.right(pane), "");
                    appendText("\n");
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    append(Either.right(pane), "");
                    appendText("\n");
                }
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
            TextArea codeArea = createCodeBlockArea(codeBlock.toString().trim());
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
            setParagraphStyle(i, "-fx-line-spacing: 10px");
        }
    }

    /** 创建用于显示代码块的 TextArea，宽度随当前区域变化，高度随内容自适应。 */
    private TextArea createCodeBlockArea(String code) {
        TextArea textArea = new TextArea(code);
        textArea.setWrapText(true);
        textArea.setEditable(false);
        textArea.setMaxHeight(500);
        textArea.setMinHeight(14);

        // 先根据当前内容计算一次高度，避免初始出现多余空白
        double initHeight = computeTextHeightForCode(textArea);
        textArea.setPrefHeight(initHeight);

        // 高度根据内容/宽度变化自适应
        ChangeListener<Object> listener = (obs, oldVal, newVal) -> {
            double textHeight = computeTextHeightForCode(textArea);
            textArea.setPrefHeight(textHeight);
        };
        textArea.textProperty().addListener(listener);
        textArea.widthProperty().addListener(listener);
        installCodeBlockContextMenu(textArea, this::deselect);

        // 宽度填满 CustomAiStyledArea（预留一点内边距）
        textArea.prefWidthProperty().bind(widthProperty().subtract(27));

        return textArea;
    }

    /** 计算代码块 TextArea 的高度（简单按行数估算）。 */
    private static double computeTextHeightForCode(TextArea textArea) {
        int lines = textArea.getParagraphs().size();
        double lineHeight = 14;
        return lines * lineHeight + 8;
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

