package com.dbboys.customnode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.reactfx.util.Either;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.File;
import java.io.IOException;
import javafx.stage.FileChooser;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.util.NotificationUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
/**
 * 专用于 AI 对话消息展示的区域：
 * - 不显示标题行
 * - 不自动缩进、不自动编号
 * - 简化 Markdown，仅支持 ``` 代码块样式
 * - 宽度由外部容器控制（如绑定到父 VBox 宽度）
 */
public class AiStyledArea extends CustomGenericStyledArea {
    private static final Logger log = LogManager.getLogger(AiStyledArea.class);
    public AiStyledArea() {
        super(new java.io.File("ai-inline.md"));
        getStyleClass().add("AiStyledArea");
        setEditable(false);
        setWrapText(true);
        setStyle("-fx-font-family: system; -fx-font-size: 11px;");
        setContextMenu(null);
    }

    /**
     * 简化 Markdown 解析：
     * - 支持 ``` 包裹的代码块，整体标记为 code-block
     * - 不添加文件名标题
     * - 不做标题编号和缩进
     */
    @Override
    public void parseMarkdownWithStyles(String markdown) {
        headingCounters=new int[6];
        String[] lines = markdown.split("\n");

        List<List<String>> tableRows = new ArrayList<>(); // 存储表格行数据
        boolean inTable = false; // 是否处于表格解析中

        boolean inCodeBlock = false;
        String codeLanguage = "";
        StringBuilder codeBlock = new StringBuilder();
        int currentParagraph = 0;

        for (String line : lines) {
            //去掉空行
            if(line.trim().isEmpty()){
                continue;
            }
            // 处理代码块
            if (line.startsWith("```")) {

                //结束上一个未完成的table
                if (inTable) {
                    // 表格结束，生成TableView并添加到内容中
                    inTable = false;
                    if (tableRows.size() >= 1) { // 至少需要表头行
                        Node tableNode = createTableView(tableRows);
                        append(Either.right(tableNode), "");
                        appendText("\n");
                    }
                    // 清空表格数据，准备下一个表格
                    tableRows.clear();
                }
                //上一个未完成的table结束

                if (!inCodeBlock) {
                    // 开始代码块
                    inCodeBlock = true;
                    codeLanguage = line.substring(3).trim();
                    codeBlock.setLength(0);

                    // 添加代码语言提示
                    if (!codeLanguage.isEmpty()) {
                        //append(Either.left("// " + codeLanguage.toUpperCase()), "");
                        //appendText("\n");
                    }
                } else {
                    // 结束代码块
                    inCodeBlock = false;

                    // 添加代码内容
                    if (codeBlock.length() > 0) {
                        append(Either.left(codeBlock.toString().trim()), "code-block");
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

            //表格
            if (inTable && TABLE_SEPARATOR_PATTERN.matcher(line).matches()) {
                // 识别为表头分隔线，不添加到数据行，仅作为表格结构标识
                continue;
            }else if (TABLE_LINE_PATTERN.matcher(line).matches()) {
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
            }else if (inTable) {
                // 表格结束，生成TableView并添加到内容中
                inTable = false;
                if (tableRows.size() >= 1) { // 至少需要表头行
                    Node tableNode = createTableView(tableRows);
                    append(Either.right(tableNode), "");
                    appendText("\n");
                }
                // 清空表格数据，准备下一个表格
                tableRows.clear();
            }

            // 标题计数器
            if (HEADING_PATTERN.matcher(line).matches()) {
                int level = 0;
                // 计算标题级别（#数量，最多6级）
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                level = Math.min(level, 6); // 限制为H1~H6（1~6）
                if (level == 0) {
                    level = 1; // 避免0级标题
                }

                // 关键：更新计数器
                int index = level - 1; // 转换为数组索引（0~5）
                headingCounters[index]++; // 当前级别计数器+1

                // 重置所有子级计数器（比当前级别低的级，如H2的子级是H3~H6）
                for (int i = index + 1; i < headingCounters.length; i++) {
                    headingCounters[i] = 0;
                }

                // 生成编号（如1.1.2）
                StringBuilder number = new StringBuilder();
                for (int i = 0; i <= index; i++) { // 只拼接当前级别及以上的计数器
                    if (headingCounters[i] > 0) {
                        if (number.length() > 0) {
                            number.append("."); // 各级之间加"."
                        }
                        number.append(headingCounters[i]);
                    }
                }
                number.append(".");


                // 核心修改：移除标题中的**标记
                String titleContent = line.substring(level).trim()
                        .replace("**", ""); // 过滤所有**，不保留加粗
                String numberedTitle = number + " " + titleContent;
                String styleClass = "heading-" + level;

                append(Either.left(numberedTitle ), styleClass);
                appendText("\n");
                continue;

            }

            // 处理图片
            if (line.contains("![") && line.contains("](")) {
                Matcher imgMatcher = IMG_PATTERN.matcher(line);
                if (imgMatcher.find()) {
                    String imgUrl = imgMatcher.group(1);
                    //imgUrl="docs"+imgUrl;

                    ImageView imgView = new ImageView(new Image("file:images/failed.png"));
                    //imgView.setFitHeight(Math.min(300, imgView));
                    imgView.setPreserveRatio(true);
                    imgView.setFitWidth(500);
                    StackPane pane = new StackPane(imgView);
                    pane.prefWidthProperty().bind(widthProperty());
                    try {
                        Path path=getAbsPath(markdownFile,imgUrl);

                        if(Files.exists(path)){
                            imgView.setImage(new Image("file:"+path));
                        }
                        Image image=imgView.getImage();
                        if(Files.exists(path)) {
                            pane.setOnContextMenuRequested(event -> {
                                imageSaveAsItem.setOnAction(event1 -> {
                                    FileChooser fileChooser = new FileChooser();
                                    fileChooser.setTitle(I18n.t("genericstyled.filechooser.image_save_as"));

                                    // 2. 设置默认文件名（与原始文件同名）和格式过滤
                                    String originalFileName = new File(path.toUri()).getName();
                                    fileChooser.setInitialFileName(originalFileName);
                                    fileChooser.getExtensionFilters().addAll(
                                            new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.image_files"), "*.png", "*.jpg", "*.jpeg", "*.gif"),
                                            new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.all_files"), "*.*")
                                    );

                                    // 3. 选择保存路径
                                    File targetFile = fileChooser.showSaveDialog(AppState.getWindow());
                                    if (targetFile == null) {
                                        return; // 用户取消
                                    }

                                    // 4. 用 Files.copy() 复制文件（支持覆盖已存在文件）
                                    try {
                                        // 复制原始文件到目标路径，若目标存在则覆盖
                                        Files.copy(
                                                path,
                                                targetFile.toPath(),
                                                StandardCopyOption.REPLACE_EXISTING // 覆盖选项
                                        );
                                        NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.image_saved"));
                                    } catch (IOException e) {
                                        log.error("Operation failed", e);
                                    }
                                });
                                imageCopyItem.setOnAction(event1 -> {
                                    Clipboard clipboard = Clipboard.getSystemClipboard();

                                    // 创建剪贴板内容并放入图像
                                    ClipboardContent content = new ClipboardContent();
                                    content.putImage(image); // 直接放入 Image 对象

                                    // 复制到剪贴板
                                    clipboard.setContent(content);
                                    NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.image_copied"));

                                });
                                imageContextMenu.show(pane, event.getScreenX(), event.getScreenY());
                                event.consume();
                            });
                        }
                        append(Either.right(pane), "");
                        appendText("\n");
                        //append(Either.left("\n"), "");
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                        append(Either.right(pane), "");
                            //append(Either.left("[图片加载失败: " + imgUrl + "]"), "");
                            appendText("\n");

                    }
                }
                continue;
            }

            // 处理普通文本行（包含链接、行内代码、粗体等）
            if (!line.trim().isEmpty()) {
                processTextLine(line);
               // append(Either.left("\n"), "");
                appendText("\n");
            } else {
                // 空行
                //append(Either.left("\n"), "");
                appendText("\n");

            }


        }
        // 在parseMarkdownWithStyles方法的循环结束后添加
        //在循环结束后，检查是否还有未处理的表格数据（避免表格结束在文档末尾）：
        if (inTable && tableRows.size() >= 1) {
            Node tableNode = createTableView(tableRows);
            append(Either.right(tableNode), "");
            appendText("\n");
            tableRows.clear();
        }

        // 如果代码块没有正确结束，确保添加剩余内容
        if (inCodeBlock && codeBlock.length() > 0) {
            append(Either.left(codeBlock.toString()), "");
            currentParagraph = getParagraphs().size() - 1;
            setParagraphStyle(currentParagraph, ("code-block"));
        }
        for(int i=0;i<getParagraphs().size();i++) {
            setParagraphStyle(i, "-fx-line-spacing: 10px");
        }
    }
}

