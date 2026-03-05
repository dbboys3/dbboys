package com.dbboys.customnode;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.AlertUtil;
import com.dbboys.util.DownloadManagerUtil;
import com.dbboys.util.MenuItemUtil;
import com.dbboys.util.NotificationUtil;
import com.dbboys.util.TabpaneUtil;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fxmisc.richtext.GenericStyledArea;
import org.fxmisc.richtext.model.SegmentOps;
import org.fxmisc.richtext.model.StyledSegment;
import org.fxmisc.richtext.model.TextOps;
import org.reactfx.util.Either;

import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomGenericStyledArea extends GenericStyledArea {
    private static final Logger log = LogManager.getLogger(CustomGenericStyledArea.class);
    public static final Pattern IMG_PATTERN = Pattern.compile("!\\[.*?]\\((.*?)\\)");
    public static final Pattern LINK_PATTERN = Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");
    public static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`(.*?)`");
    public static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    public static final Pattern TABLE_LINE_PATTERN = Pattern.compile("^\\|.*\\|$");
    public static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\s*\\|(\\s*-+\\s*\\|)+\\s*$");
    // 同时匹配 Markdown 链接、纯 http 链接和粗体
    public static final Pattern COMBINED_PATTERN = Pattern.compile(
            "(\\[.*?]\\(.*?\\)|https?://[^\\s\\\"]+|\\*\\*[^*]+\\*\\*)"
    );
    public static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6} .*$");
    public static final Set<String> DOC_TYPES = Set.of(
            "zip", "rar", "7z", "exe", "pdf", "doc", "docx", "xls", "xlsx",
            "png", "jpg", "jpeg", "gif", "bmp", "mp3", "mp4", "avi",
            "mkv", "txt", "csv", "json", "xml", "iso", "tar", "gz", "tar.gz",
            "sh", "chm", "jar", "yml"
    );
    public static final String LINK_STYLE = "-fx-fill: #0066cc; -fx-underline: true; -fx-cursor: hand;";
    public static final String INVALID_LINK_STYLE = "-fx-fill: #f00; -fx-underline: true;-fx-cursor: hand;-fx-strikethrough: true";
    public static final ConcurrentMap<String, Boolean> LINK_CHECK_CACHE = new ConcurrentHashMap<>();
    public static final Set<String> LINK_CHECK_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    public int[] headingCounters = new int[6]; // 索引0对应H1，1对应H2，以此类推
    public Runnable onSearchRequest = () -> {};
    public  CustomShortcutMenuItem codeAreaSearchItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.search", "Ctrl+F", IconFactory.group(IconPaths.MAIN_SEARCH, 0.6));
    public  ContextMenu contextMenu = new ContextMenu();
    public  CustomShortcutMenuItem modifyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.modify", "Ctrl+Enter", IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.65));
    public  CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy", "Ctrl+C", IconFactory.group(IconPaths.COPY, 0.7));
    public  CustomShortcutMenuItem imageCopyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy_image", IconFactory.group(IconPaths.COPY, 0.7));
    public  ContextMenu imageContextMenu = new ContextMenu();
    public CustomShortcutMenuItem imageSaveAsItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.image_save_as", IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.6));
    public File markdownFile;

    /** 便捷构造：无文件上下文时使用临时占位文件名。 */
    public CustomGenericStyledArea() {
        this(new File("inline.md"));
    }

    public static class NodeSegmentOps implements SegmentOps<Node, String> {
        @Override
        public int length(javafx.scene.Node seg) {
            return 1;
        }

        @Override
        public char charAt(javafx.scene.Node seg, int index) {
            return '\ufffc';
        }

        @Override
        public String getText(javafx.scene.Node seg) {
            return "\ufffc";
        }

        @Override
        public javafx.scene.Node subSequence(javafx.scene.Node seg, int start, int end) {
            return seg;
        }

        @Override
        public javafx.scene.Node subSequence(javafx.scene.Node seg, int index) {
            return seg;
        }

        @Override
        public Optional<Node> joinSeg(javafx.scene.Node left, javafx.scene.Node right) {
            return Optional.empty();
        }

        @Override
        public javafx.scene.Node createEmptySeg() {
            return new Text("");
        }
    }


    public CustomGenericStyledArea(File markdownFile){
        this.markdownFile=markdownFile;

        TextOps<String, String> textOps = SegmentOps.styledTextOps();
        NodeSegmentOps nodeOps = new CustomGenericStyledArea.NodeSegmentOps();
        TextOps<Either<String, javafx.scene.Node>, String> segmentOps = textOps._or(nodeOps, (node, style) -> Optional.empty());

        // 使用集合来存储段落样式
        BiConsumer<TextFlow, String> paragraphStyler = (textFlow, style) -> {
            if (style != null && !style.isEmpty()) {
                textFlow.setStyle(style); // 例如行间距
            }
        };
        Function<StyledSegment<Either<String, Node>, String>, Node> nodeFactory = seg -> {
            Either<String, javafx.scene.Node> e = seg.getSegment();
            if (e.isLeft()) {
                Text t = new Text(e.getLeft());

                // 应用内联样式
                if (seg.getStyle() != null && !seg.getStyle().isEmpty()) {
                    if (seg.getStyle().contains("link")) {
                        String urlInit = extractLinkUrl(seg.getStyle());
                        String tmpUrl=urlInit;
                        if(!isHttpUrl(urlInit)){
                            try {
                                Path path = getAbsPath(markdownFile, urlInit);
                                tmpUrl=path.toString();
                            } catch (Exception ex) {
                                log.error(ex.getMessage(), ex);
                            }
                        }
                        String url=tmpUrl;
                        boolean isHttpLink = isHttpUrl(url);
                        String ext = getFileExtension(url);
                        t.setStyle(LINK_STYLE);


                        applyLinkValidation(t, url, isHttpLink);

                        CustomShortcutMenuItem saveAsItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.save_as", IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.6));
                        CustomShortcutMenuItem copyLinkItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy_link", IconFactory.group(IconPaths.COPY, 0.7));
                        ContextMenu linkContextMenu = new ContextMenu();
                        linkContextMenu.getItems().addAll(saveAsItem,copyLinkItem);
                        copyLinkItem.setOnAction(ev -> {
                            Clipboard clipboard = Clipboard.getSystemClipboard();
                            ClipboardContent content = new ClipboardContent();
                            content.putString(url);
                            clipboard.setContent(content);
                            NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.link_copied"));
                        });
                        if(DOC_TYPES.contains(ext)||ext.equals("md")){
                            saveAsItem.setOnAction(ev -> {
                                FileChooser fileChooser = new FileChooser();
                                fileChooser.setTitle(I18n.t("genericstyled.filechooser.save_link_content"));
                                String defaultName;
                                try {
                                    defaultName = resolveDownloadFileName(url, isHttpLink);
                                } catch (Exception ex) {
                                    log.error(ex.getMessage(),ex);
                                    AlertUtil.CustomAlert(I18n.t("genericstyled.alert.download_error"), ex.getMessage());
                                    return;
                                }
                                fileChooser.setInitialFileName(defaultName);
                                File file = fileChooser.showSaveDialog(AppState.getWindow());

                                if (file != null) {
                                    if(file.exists()){
                                        file.delete();
                                    }
                                    DownloadManagerUtil.addDownload(url, file, true,null);
                                }
                            });

                        } else {
                            saveAsItem.setDisable(true);
                        }
                        t.setOnContextMenuRequested(event -> {
                            linkContextMenu.show(t, event.getScreenX(), event.getScreenY());
                            event.consume();
                        });
                        t.setOnMouseClicked(event -> {
                            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                                // 常见文件扩展名集合
                                if (DOC_TYPES.contains(ext)) {
                                    File desktopDir = FileSystemView.getFileSystemView().getHomeDirectory();
                                    String defaultName;
                                    try {
                                        defaultName = resolveDownloadFileName(url, isHttpLink);
                                    } catch (Exception ex) {
                                        log.error(ex.getMessage(),ex);
                                        AlertUtil.CustomAlert(I18n.t("genericstyled.alert.download_error"), ex.getMessage());
                                        return;
                                    }

                                    File saveFile = new File(desktopDir, defaultName);  // 自动拼接路径

                                    //这里增加判断是避免弹出通知 “文件将下载到桌面”后又报错！
                                    File saveFileTemp = new File(desktopDir, defaultName + ".download");
                                    if (saveFile.exists()) {
                                        AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("genericstyled.error.desktop_file_exists"));
                                    } else if (saveFileTemp.exists()) {
                                        AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("genericstyled.error.file_downloading"));
                                    } else {
                                        NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.download_to_desktop"));
                                        DownloadManagerUtil.addDownload(url, saveFile, true, null);
                                    }


                                } else {
                                    openUrl(url);
                                }
                                event.consume();
                            }
                        });


                        /*


                        t.setOnMouseClicked(evt -> {
                            try {
                                Desktop.getDesktop().browse(new URI(url));
                            } catch (Exception ignored) {
                            }
                        });

                         */
                    } else if (seg.getStyle().contains("code-inline")) {
                        t.setStyle(
                                "-fx-fill: #9f453c; " +
                                        "-fx-font-family: 'SimSun'; "

                        );

                    } else if (seg.getStyle().contains("code-block")) {
                        ContextMenu textAreaContextMenu = new ContextMenu();
                        CustomShortcutMenuItem textAreaCopyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy", "Ctrl+C", IconFactory.group(IconPaths.COPY, 0.7));
                        textAreaContextMenu.getItems().addAll(textAreaCopyItem);
                        if (e.getLeft().trim().isEmpty()) {
                            return new Text(""); // appendtext("\n")会继承上一个style，空段落不生成 TextArea，解决最后一个如果是```出现一个空白text
                        }
                        TextArea textArea = new TextArea();
                        textArea.setWrapText(true);
                        textArea.setMaxHeight(500);
                        textArea.setMinHeight(14);
                        textArea.setEditable(false);
                        ChangeListener<Object> listener = (obs, oldVal, newVal) -> {
                            double textHeight = computeTextHeight(textArea);
                            textArea.setPrefHeight(textHeight);
                        };
                        textArea.textProperty().addListener(listener);
                        textArea.widthProperty().addListener(listener);
                        textArea.setText(e.getLeft());
                        textArea.prefWidthProperty().bind(
                                //Bindings.subtract(customGenericStyledArea.widthProperty(), 27)
                                Bindings.subtract(AppState.getSqlTabPane().widthProperty(), 37)
                        );

                        textArea.focusedProperty().addListener((obs, oldFocus, newFocus) -> {
                            if (!newFocus) {
                                //textAreaContextMenu.hide();
                                // TextArea 获取焦点时，取消 GenericStyledArea 的选择
                                textArea.deselect();
                                //customGenericStyledArea.deselect(); // 清除选区
                            }else{
                                ((CustomGenericStyledArea)textArea.getParent().getParent().getParent().getParent().getParent()).deselect();
                            }
                        });

                        textArea.setContextMenu(textAreaContextMenu);
                        textAreaCopyItem.setOnAction(event1->{
                            if(!textArea.getSelectedText().isEmpty()){
                                textArea.copy();
                            }else  {
                                Clipboard clipboard = Clipboard.getSystemClipboard();
                                ClipboardContent content = new ClipboardContent();
                                content.putString(textArea.getText());
                                clipboard.setContent(content);
                                NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.code_block_copied"));
                            }
                        });


                        return textArea;

                    }else if (seg.getStyle().contains("bold")) {
                        t.setStyle("-fx-font-family: system;-fx-font-weight: bold;-fx-fill: #9f453c");
                    }else if (seg.getStyle() != null) {
                        if (seg.getStyle().contains("title")) {
                            ContextMenu contextMenu = new ContextMenu();
                            CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy_simple", IconFactory.group(IconPaths.COPY, 0.7));
                            contextMenu.getItems().addAll(copyItem);
                            CustomUserTextField customUserTextField = new CustomUserTextField();
                            // textArea.setWrapText(true);
                            customUserTextField.setEditable(false);
                            customUserTextField.setText(e.getLeft());
                            customUserTextField.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;-fx-background-color:none;-fx-border-width: 0;-fx-effect:none");
                            customUserTextField.prefWidthProperty().bind(
                                    Bindings.subtract(AppState.getSqlTabPane().widthProperty(), 36)
                            );
                            customUserTextField.focusedProperty().addListener((obs, oldFocus, newFocus) -> {
                                if (!newFocus) {
                                    //textAreaContextMenu.hide();
                                    // TextArea 获取焦点时，取消 GenericStyledArea 的选择
                                    customUserTextField.deselect();
                                    //customGenericStyledArea.deselect(); // 清除选区
                                }else{
                                    ((CustomGenericStyledArea)customUserTextField.getParent().getParent().getParent().getParent().getParent()).deselect();
                                }
                            });
                            copyItem.setOnAction(event1->{
                                if(!customUserTextField.getSelectedText().isEmpty()){
                                    customUserTextField.copy();
                                }else  {
                                    Clipboard clipboard = Clipboard.getSystemClipboard();
                                    ClipboardContent content = new ClipboardContent();
                                    content.putString(customUserTextField.getText());
                                    clipboard.setContent(content);
                                    NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.title_copied"));
                                }
                            });

                            customUserTextField.setAlignment(Pos.CENTER);
                            customUserTextField.setContextMenu(contextMenu);

                            return customUserTextField;

                        }
                        if (seg.getStyle().contains("heading-1")) {
                            t.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-padding: 10 0 5 0;");
                        } else if (seg.getStyle().contains("heading-2")) {
                            t.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 8 0 4 0;");
                        } else if (seg.getStyle().contains("heading-3")) {
                            t.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 6 0 3 0;");
                        } else if (seg.getStyle().contains("heading-4")) {
                            t.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 0 2 0;");
                        } else if (seg.getStyle().contains("heading-5")) {
                            t.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 2 0 1 0;");
                        } else if (seg.getStyle().contains("heading-6")) {
                            t.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 1 0 1 0;");
                        }
                    }

                }
                return t;
            } else {
                return e.getRight();
            }
        };
        super("",// 默认段落样式
                paragraphStyler,
                "",
                segmentOps,
                nodeFactory);
        //setParagraphGraphicFactory(LineNumberFactory.get(this));
        getStyleClass().add("CustomGenericStyledArea");
        setEditable(false);
        setWrapText(true);
        setStyle("-fx-font-family: system; -fx-font-size: 11px;");
        setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                modifyItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.F){
                onSearchRequest.run();
            }
        });
        contextMenu.getItems().addAll(modifyItem,codeAreaSearchItem,copyItem);
        imageContextMenu.getItems().addAll(imageSaveAsItem,imageCopyItem);
        //正文内容邮件后，在textarea右键，正文右键菜单不会自动隐藏，加此监听
        focusedProperty().addListener((obs, oldFocus, newFocus) -> {
            if (!newFocus) {
                contextMenu.hide();
            }
        });

        copyItem.setOnAction(event -> {
            if(!getSelectedText().isEmpty()){
                copy();
            }
        });
        setContextMenu(contextMenu);

        contextMenu.setOnShowing(event -> {
            if(getSelectedText().isEmpty()){
                copyItem.setDisable(true);
            }else{
                copyItem.setDisable(false);
            }
        });
        codeAreaSearchItem.setOnAction(event -> onSearchRequest.run());

    }

    public void setOnSearchRequest(Runnable onSearchRequest) {
        this.onSearchRequest = onSearchRequest == null ? () -> {} : onSearchRequest;
    }

    public void parseMarkdownWithStyles(String markdown) {
        headingCounters=new int[6];
        String[] lines = markdown.split("\n");

        List<List<String>> tableRows = new ArrayList<>(); // 存储表格行数据
        boolean inTable = false; // 是否处于表格解析中

        String fileName=markdownFile.getName();
        int dotIndex = fileName.lastIndexOf('.');
        String title = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        //添加标题
        if(title!=null&&!title.isEmpty()){
            append(Either.left(title), "title");
            appendText("\n");
        }

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
                            // 网络图片：直接使用 URL 加载
                            imgView.setImage(new Image(imgUrl, true));
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

    public void processTextLine(String line) {
        if(!line.startsWith("\t")){
            line = "\t" + line;
        }
        // 首先处理行内代码，因为它们不应该包含其他格式
        List<TextSegment> segments = new ArrayList<>();
        int lastIndex = 0;

        // 查找所有行内代码
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

    public void processFormattedText(String text) {

        int lastIndex = 0;
        // 同时匹配链接和粗体（优先匹配链接，避免冲突）
        Matcher matcher = COMBINED_PATTERN.matcher(text);

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                String normalText = text.substring(lastIndex, matcher.start());
                append(Either.left(normalText), "");
            }

            String matched = matcher.group();
            if (matched.startsWith("[")) {
                // 链接处理：支持 [text](url "title")，忽略括号内引号里的提示
                Matcher linkMatcher = LINK_PATTERN.matcher(matched);
                if (linkMatcher.find()) {
                    String linkText = linkMatcher.group(1);
                    String rawTarget = linkMatcher.group(2);
                    String linkUrl = rawTarget == null ? "" : rawTarget.trim();

                    // 去掉后面的 "提示" 或多余说明，只保留真正的 URL
                    int quoteIdx = linkUrl.indexOf('"');
                    if (quoteIdx >= 0) {
                        linkUrl = linkUrl.substring(0, quoteIdx).trim();
                    } else {
                        int spaceIdx = linkUrl.indexOf(' ');
                        if (spaceIdx >= 0) {
                            linkUrl = linkUrl.substring(0, spaceIdx).trim();
                        }
                    }

                    append(Either.left(linkText), "link:" + linkUrl + ";");
                }
            } else if (matched.startsWith("http://") || matched.startsWith("https://")) {
                // 纯 http/https 链接，例如：https://markdown.com.cn "点击跳转官方指南"
                // 这里只匹配 URL 本身，后面的 "提示" 会作为普通文本保留
                String linkUrl = matched.trim();
                append(Either.left(linkUrl), "link:" + linkUrl + ";");
            } else if (matched.startsWith("**")) {
                // 加粗处理：确保正确提取内容
                Matcher boldMatcher = BOLD_PATTERN.matcher(matched);
                if (boldMatcher.find()) {
                    String boldText = boldMatcher.group(1); // 提取** 之间的内容
                    append(Either.left(boldText), "bold"); // 应用bold样式
                }
            }

            lastIndex = matcher.end();
        }

        if (lastIndex < text.length()) {
            append(Either.left(text.substring(lastIndex)), "");
        }
    }


    private static double computeTextHeight(TextArea textArea) {
        int lines = textArea.getParagraphs().size();
        //Insets padding = textArea.getInsets();
        //System.out.println("textArea.getInsets():"+padding.getTop()+" "+padding.getBottom());
        double lineHeight = 14; // 1.2 行高系数
        //Insets padding = textArea.getInsets();
        //double height = lines * lineHeight + padding.getTop() + padding.getBottom();
        //double minHeight = lineHeight + padding.getTop() + padding.getBottom();
        //double maxHeight = 300; // 最大高度，可自定义
        //return Math.min(Math.max(height, minHeight), maxHeight);
        return lines * lineHeight+8;
    }

    // 辅助类，用于存储文本段及其样式
    public static class TextSegment {
        String text;
        String style;

        TextSegment(String text, String style) {
            this.text = text;
            this.style = style;
        }
    }

    public static void openUrl(String url) {
        try {
            if (url == null || url.isBlank()) return;

            // 清理掉前后空格、换行符、隐藏字符

            url = url.strip();
            url = url.replaceAll("[\\r\\n\\t]", "");

            if(isHttpUrl(url))
            {
                URI uri = new URI(url);
                java.awt.Desktop.getDesktop().browse(uri);
            }else{
                if(Files.exists(Paths.get(url))){
                    //markdown里点击的链接打开的文件是相对路径，比对文档里的相对路径会出现问题，这里需要转换为使用绝对路径
                    TabpaneUtil.addCustomMarkdownTab(new File(new File(url).getAbsolutePath()),false);
                }else{
                    String finalUrl = url;
                    Platform.runLater(() -> {
                        AlertUtil.CustomAlert(I18n.t("common.error"), String.format(I18n.t("genericstyled.error.file_not_found"), finalUrl));
                    });
                }


            }


        } catch (Exception e) {
            log.error("Operation failed", e);
        }
    }


    /**
     * 根据表格数据生成JavaFX TableView组件
     */
    public Node createTableView(List<List<String>> tableRows) {
        // 创建表格视图
        CustomResultsetTableView<List<String>> tableView = new CustomResultsetTableView<>();
        tableView.setStyle("-fx-border-color: #dddddd; -fx-border-width: 1px;");
        tableView.prefWidthProperty().bind(widthProperty().subtract(20)); // 自适应宽度

        // 获取表头行（第一行）
        List<String> headers = tableRows.get(0);

        //如果只有一列，宽度计算不对单独处理
        if(headers.size()==1){

        }
        // 创建表格列（根据表头）
        for (int i = 0; i < headers.size(); i++) {
            final int columnIndex = i;
            TableColumn<List<String>, String> column = new TableColumn<>(headers.get(i));
            column.setCellFactory(col -> new CustomTableCell<>());
            column.setCellValueFactory(cellData -> {
                // 获取当前行数据，若索引越界则返回空字符串
                List<String> rowData = cellData.getValue();
                String value = (columnIndex < rowData.size()) ? rowData.get(columnIndex) : "";
                return new SimpleStringProperty(value);
            });
            // 列宽自适应内容（也可设置固定宽度）
            column.prefWidthProperty().bind(tableView.widthProperty().subtract(33).divide(headers.size()));
           // double avgColWidth = (tableView.getWidth() - 30) / headers.size();
            //Sstem.out.println("tableView.getWidth():"+tableView.getWidth()
            // ;

            //System.out.println("avgColWidth:"+avgColWidth);
            //column.setPrefWidth(Math.max(80,avgColWidth));
            tableView.getColumns().add(column);
            column.setSortable(false);
            column.setReorderable(false);
        }

        // 添加表格内容行（跳过表头行）
        for (int i = 1; i < tableRows.size(); i++) {
            tableView.getItems().add(tableRows.get(i));
        }

        // 关键：计算表格总高度（表头高度 + 内容行高度 + 边框）
        double rowHeight = 21; // 单行高度（可根据字体调整）
        double headerHeight = 18; // 表头高度
        int dataRowCount = tableView.getItems().size(); // 实际数据行数
        double totalHeight = headerHeight + (dataRowCount * rowHeight) +2; // +4是边框高度

        // 限制最大高度（避免表格过高超出容器）
        double maxHeight = 300; // 最大高度阈值
        totalHeight = Math.min(totalHeight, maxHeight);

        // 设置表格固定高度（根据内容计算）
        tableView.setPrefHeight(totalHeight);
        tableView.setMaxHeight(totalHeight);
        tableView.setMinHeight(totalHeight);
        tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tableView.getStyleClass().add("MarkdownTableView");
        tableView.focusedProperty().addListener((obs, oldFocus, newFocus) -> {
            if (!newFocus) {
                //textAreaContextMenu.hide();
                // TextArea 获取焦点时，取消 GenericStyledArea 的选择
                tableView.getSelectionModel().clearSelection();
                //customGenericStyledArea.deselect(); // 清除选区
            }else{
                ((CustomGenericStyledArea)tableView.getParent().getParent().getParent().getParent().getParent()).deselect();
            }
        });

        return tableView;
    }

    private static String getFileExtension(String url) {
        int dot = url.lastIndexOf('.');
        if (dot < 0 || dot == url.length() - 1) {
            return "";
        }
        return url.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extractLinkUrl(String style) {
        if (style == null || style.isEmpty()) {
            return "";
        }
        int start = style.indexOf("link:");
        if (start < 0) {
            return "";
        }
        start += "link:".length();
        int end = style.indexOf(';', start);
        return end >= 0 ? style.substring(start, end) : style.substring(start);
    }

    private static boolean isHttpUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("http");
    }

    private static void applyLinkValidation(Text textNode, String url, boolean isHttpLink) {
        if (!isHttpLink) {
            if (!new File(url).exists()) {
                textNode.setStyle(INVALID_LINK_STYLE);
            }
            return;
        }

        Boolean cached = LINK_CHECK_CACHE.get(url);
        if (cached != null) {
            if (!cached) {
                textNode.setStyle(INVALID_LINK_STYLE);
            }
            return;
        }

        if (!LINK_CHECK_IN_FLIGHT.add(url)) {
            return;
        }

        AppExecutor.runAsync(() -> {
            boolean valid = false;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int code = conn.getResponseCode();
                valid = (code >= 200 && code < 400);
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            } finally {
                LINK_CHECK_CACHE.put(url, valid);
                LINK_CHECK_IN_FLIGHT.remove(url);
            }

            if (!valid) {
                Platform.runLater(() -> textNode.setStyle(INVALID_LINK_STYLE));
            }
        });
    }

    private static String resolveDownloadFileName(String url, boolean isHttpLink) throws Exception {
        if (isHttpLink) {
            String name = DownloadManagerUtil.getRealFileNameFromRedirect(url);
            return (name == null || name.isEmpty()) ? "downloaded.file" : name;
        }

        int slash = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
        if (slash >= 0 && slash < url.length() - 1) {
            return url.substring(slash + 1);
        }
        return "downloaded.file";
    }

    public static Path getAbsPath(File file,String url){
        Path appDir = Path.of(System.getProperty("user.dir"));
        Path baseFile = Path.of(file.getPath());
        Path relative  = Paths.get(url);
        Path absolutePath = baseFile
                .getParent()      // 以“文件所在目录”为基准
                .resolve(relative)
                .normalize();
        if (absolutePath.startsWith(appDir) && absolutePath.getNameCount() > appDir.getNameCount()) {
            return absolutePath.subpath(
                    appDir.getNameCount(),
                    absolutePath.getNameCount()
            );
        }
        return absolutePath;
    }




}
