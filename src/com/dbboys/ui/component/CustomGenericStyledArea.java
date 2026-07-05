package com.dbboys.ui.component;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.infra.util.DownloadManagerUtil;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.infra.util.TabpaneUtil;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.Parent;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String EMBEDDED_CONTEXT_MENU_NODE_KEY = "custom.embeddedContextMenuNode";
    public static final Pattern IMG_PATTERN = Pattern.compile("!\\[.*?]\\((.*?)\\)");
    public static final Pattern LINK_PATTERN = Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");
    public static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`(.*?)`");
    public static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    public static final Pattern TABLE_LINE_PATTERN = Pattern.compile("^\\s*\\|.*\\|\\s*$");
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
    public static final String LINK_STYLE = "-fx-fill:-color-accent-3; -fx-underline: true; -fx-cursor: hand;";
    public static final String INVALID_LINK_STYLE = "-fx-fill: -color-danger-7; -fx-underline: true;-fx-cursor: hand;-fx-strikethrough: true";
    public static final ConcurrentMap<String, Boolean> LINK_CHECK_CACHE = new ConcurrentHashMap<>();
    public static final Set<String> LINK_CHECK_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    public int[] headingCounters = new int[6]; // 索引0对应H1，1对应H2，以此类推
    public Runnable onSearchRequest = () -> {};
    public  CustomShortcutMenuItem codeAreaSearchItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.search", "Ctrl+F", IconFactory.group(IconPaths.MAIN_SEARCH, 0.6));
    public  ContextMenu contextMenu = new ContextMenu();
    public  CustomShortcutMenuItem modifyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.modify", "Ctrl+Enter", IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.65));
    public  CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy", "Ctrl+C", IconFactory.group(IconPaths.COPY, 0.7));
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
            File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
            if (desktopDir.exists()) fileChooser.setInitialDirectory(desktopDir);
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
                                if (isHttpLink && DOC_TYPES.contains(ext)) {
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
                        t.getStyleClass().add("markdown-inline-code"
                        );

                    }else if (seg.getStyle().contains("bold")) {
                        t.getStyleClass().add(".markdown-bold");
                    }else if (seg.getStyle() != null) {
                        if (seg.getStyle().contains("title")) {
                            ContextMenu contextMenu = new ContextMenu();
                            CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy_simple", IconFactory.group(IconPaths.COPY, 0.7));
                            contextMenu.getItems().addAll(copyItem);
                            CustomUserTextField customUserTextField = new CustomUserTextField();
                            // textArea.setWrapText(true);
                            customUserTextField.setEditable(false);
                            customUserTextField.setText(e.getLeft());
                            customUserTextField.getStyleClass().add("markdown-title-field");
                            customUserTextField.prefWidthProperty().bind(
                                    Bindings.subtract(AppState.getSqlTabPane().widthProperty(), 10)
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
                            t.getStyleClass().add("markdown-heading-1");
                        } else if (seg.getStyle().contains("heading-2")) {
                            t.getStyleClass().add("markdown-heading-2");
                        } else if (seg.getStyle().contains("heading-3")) {
                            t.getStyleClass().add("markdown-heading-3");
                        } else if (seg.getStyle().contains("heading-4")) {
                            t.getStyleClass().add("markdown-heading-4");
                        } else if (seg.getStyle().contains("heading-5")) {
                            t.getStyleClass().add("markdown-heading-5");
                        } else if (seg.getStyle().contains("heading-6")) {
                            t.getStyleClass().add("markdown-heading-6");
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
        getStyleClass().add("markdown-styled-area");
    
        setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                modifyItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.F){
                onSearchRequest.run();
            }
        });
        contextMenu.getItems().addAll(modifyItem,codeAreaSearchItem,copyItem);
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

    protected CustomInfoCodeArea createCodeBlockArea(String code) {
        CustomInfoCodeArea codeArea = new CustomInfoCodeArea();
        markEmbeddedContextMenuNode(codeArea);
        codeArea.replaceText(code == null ? "" : code);
        codeArea.setWrapText(false);
        codeArea.setEditable(false);
        codeArea.setParagraphGraphicFactory(null);
        codeArea.getStyleClass().add("markdown-code-block");
 
        updateCodeBlockHeight(codeArea);
        bindCodeBlockWidth(codeArea);
        bindCodeBlockScroll(codeArea);
        // 右键点击代码块时保留焦点与选中；其它失焦再清理选中
        AtomicBoolean suppressClearOnFocusLoss = new AtomicBoolean(false);
        codeArea.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                suppressClearOnFocusLoss.set(true);
            } else {
                suppressClearOnFocusLoss.set(false);
            }
        });
        codeArea.focusedProperty().addListener((obs, oldFocus, newFocus) -> {
            if (newFocus) {
                deselect();
            } else {
                if (!suppressClearOnFocusLoss.get()) {
                    codeArea.deselect();
                }
                suppressClearOnFocusLoss.set(false);
            }
        });
        return codeArea;
    }

    protected void bindCodeBlockWidth(CustomInfoCodeArea codeArea) {
        if (AppState.getSqlTabPane() != null) {
            codeArea.prefWidthProperty().bind(Bindings.subtract(AppState.getSqlTabPane().widthProperty(), 8));
        }
    }

    protected static void updateCodeBlockHeight(CustomInfoCodeArea codeArea) {
        int lines = Math.max(codeArea.getParagraphs().size(), 1);
        double lineHeight = 12;
        double height = Math.max(16, Math.ceil(lines * lineHeight + 8));
        codeArea.setPrefHeight(height);
        codeArea.setMinHeight(height);
        codeArea.setMaxHeight(height);
    }

    protected void bindCodeBlockScroll(CustomInfoCodeArea codeArea) {
        codeArea.addEventFilter(ScrollEvent.SCROLL, event -> {
            Parent parent = codeArea.getParent();
            if (parent != null) {
                parent.fireEvent(event.copyFor(parent, parent));
                event.consume();
            }
        });
    }

    protected Node createImageNode(String imgUrl) {
        ImageView imgView = new ImageView(new Image("file:images/failed.png"));
        imgView.setPreserveRatio(true);
        imgView.setFitWidth(500);
        StackPane pane = new StackPane(imgView);
        markEmbeddedContextMenuNode(pane);
        pane.prefWidthProperty().bind(widthProperty());
        try {
            if (isHttpUrl(imgUrl)) {
                Image netImage = new Image(imgUrl, true);
                imgView.setImage(netImage);
                bindImageLoadCallback(netImage);
                installNetworkImageContextMenu(pane, imgUrl);
            } else {
                Path path = getAbsPath(markdownFile, imgUrl);
                if (Files.exists(path)) {
                    Image localImage = new Image("file:" + path);
                    imgView.setImage(localImage);
                    bindImageLoadCallback(localImage);
                    installLocalImageContextMenu(pane, imgView, path);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return pane;
    }

    protected void onImageLoaded() {
    }

    private void bindImageLoadCallback(Image image) {
        if (image == null) {
            return;
        }
        if (image.getProgress() >= 1.0) {
            onImageLoaded();
            return;
        }
        image.progressProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.doubleValue() >= 1.0) {
                onImageLoaded();
            }
        });
    }

    private void installNetworkImageContextMenu(StackPane pane, String imgUrl) {
        CustomShortcutMenuItem saveAsItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.image_save_as", IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.6));
        CustomShortcutMenuItem copyLinkItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy_link", IconFactory.group(IconPaths.COPY, 0.7));
        ContextMenu contextMenu = new ContextMenu(saveAsItem, copyLinkItem);
        saveAsItem.setOnAction(event -> saveImageAs(imgUrl, true));
        copyLinkItem.setOnAction(event -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(imgUrl);
            clipboard.setContent(content);
            NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.link_copied"));
        });
        pane.setOnContextMenuRequested(event -> {
            contextMenu.show(pane, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private void installLocalImageContextMenu(StackPane pane, ImageView imgView, Path path) {
        CustomShortcutMenuItem saveAsItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.image_save_as", IconFactory.group(IconPaths.GENERIC_SAVE_AS, 0.6));
        CustomShortcutMenuItem copyImageItem = MenuItemUtil.createMenuItemI18n("genericstyled.menu.copy_image", IconFactory.group(IconPaths.COPY, 0.7));
        ContextMenu contextMenu = new ContextMenu(saveAsItem, copyImageItem);
        saveAsItem.setOnAction(event -> saveImageAs(path.toString(), false));
        copyImageItem.setOnAction(event -> {
            Image image = imgView.getImage();
            if (image == null) {
                return;
            }
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putImage(image);
            clipboard.setContent(content);
            NotificationUtil.showMainNotification(I18n.t("genericstyled.notice.image_copied"));
        });
        pane.setOnContextMenuRequested(event -> {
            contextMenu.show(pane, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private void saveImageAs(String source, boolean isHttpLink) {
        FileChooser fileChooser = new FileChooser();
            File desktopDir = new File(System.getProperty("user.home") + File.separator + "Desktop");
            if (desktopDir.exists()) fileChooser.setInitialDirectory(desktopDir);
        fileChooser.setTitle(I18n.t("genericstyled.filechooser.image_save_as"));
        try {
            fileChooser.setInitialFileName(resolveDownloadFileName(source, isHttpLink));
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            AlertUtil.CustomAlert(I18n.t("genericstyled.alert.download_error"), ex.getMessage());
            return;
        }
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.image_files"), "*.png", "*.jpg", "*.jpeg", "*.gif"),
                new FileChooser.ExtensionFilter(I18n.t("genericstyled.filechooser.all_files"), "*.*")
        );
        File file = fileChooser.showSaveDialog(AppState.getWindow());
        if (file != null) {
            if (file.exists()) {
                file.delete();
            }
            DownloadManagerUtil.addDownload(source, file, true, null);
        }
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

        for (String line : lines) {
            //去掉空行
            if(line.trim().isEmpty()){
                continue;
            }
            // 处理代码块
            if (line.trim().startsWith("```")) {

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
                    append(Either.right(createImageNode(imgUrl)), "");
                    appendText("\n");
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
            CustomInfoCodeArea codeArea = createCodeBlockArea(codeBlock.toString().trim());
            append(Either.right(codeArea), "");
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
                    }
                    if (linkUrl.startsWith("<") && linkUrl.endsWith(">") && linkUrl.length() > 1) {
                        linkUrl = linkUrl.substring(1, linkUrl.length() - 1).trim();
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
                File file = new File(url).getAbsoluteFile();
                if(file.exists()){
                    String lowerPath = file.getName().toLowerCase(Locale.ROOT);
                    if (lowerPath.endsWith(".md") || lowerPath.endsWith(".markdown")) {
                        TabpaneUtil.addCustomMarkdownTab(file, false);
                    } else {
                        java.awt.Desktop.getDesktop().open(file);
                    }
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
        CustomTableView<List<String>> tableView = new CustomTableView<>();
        markEmbeddedContextMenuNode(tableView);
        tableView.getStyleClass().add("MarkdownTableView");
        tableView.setTableMenuButtonVisible(false);

        final double fixedCellSize = 22.0;
        tableView.setFixedCellSize(fixedCellSize);

        List<String> headers = tableRows.get(0);
        Text measure = new Text();
        measure.setFont(javafx.scene.text.Font.font("System", 11));
        final int maxMeasureChars = 280;
        final double maxColWidth = 420;

        double[] naturalColWidth = new double[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            String h = headers.get(c) == null ? "" : headers.get(c);
            measure.setText(h);
            double w = Math.min(measure.getLayoutBounds().getWidth() + 20, maxColWidth);
            naturalColWidth[c] = Math.max(w, 44);
            for (int r = 1; r < tableRows.size(); r++) {
                List<String> row = tableRows.get(r);
                String v = (c < row.size() && row.get(c) != null) ? row.get(c) : "";
                if (v.length() > maxMeasureChars) {
                    v = v.substring(0, maxMeasureChars);
                }
                measure.setText(v);
                w = Math.min(measure.getLayoutBounds().getWidth() + 20, maxColWidth);
                naturalColWidth[c] = Math.max(naturalColWidth[c], Math.max(w, 44));
            }
        }

        for (int i = 0; i < headers.size(); i++) {
            final int columnIndex = i;
            TableColumn<List<String>, String> column = new TableColumn<>(headers.get(i));
            column.setCellFactory(col -> new CustomTableCell<>());
            column.setCellValueFactory(cellData -> {
                List<String> rowData = cellData.getValue();
                String value = (columnIndex < rowData.size()) ? rowData.get(columnIndex) : "";
                return new SimpleStringProperty(value);
            });
            column.setSortable(false);
            column.setReorderable(false);
            column.setResizable(false);
            tableView.getColumns().add(column);
        }

        for (int i = 1; i < tableRows.size(); i++) {
            tableView.getItems().add(tableRows.get(i));
        }

        int dataRowCount = tableView.getItems().size();
        double headerBand = 26;
        // 比表头+行高略收 5px，避免视觉/布局上偏高
        double totalHeight = headerBand + dataRowCount * fixedCellSize +4;
        tableView.setPrefHeight(totalHeight);
        tableView.setMaxHeight(totalHeight);
        tableView.setMinHeight(totalHeight);

        tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        final int colCount = headers.size();
        Runnable relayout = () -> layoutMarkdownTableView(tableView, naturalColWidth, colCount, this);
        relayout.run();
        Platform.runLater(relayout);
        widthProperty().addListener((obs, o, n) -> Platform.runLater(relayout));

        tableView.focusedProperty().addListener((obs, oldFocus, newFocus) -> {
            if (!newFocus) {
                tableView.getSelectionModel().clearSelection();
            } else {
                deselect();
            }
        });

        return tableView;
    }

    protected static void markEmbeddedContextMenuNode(Node node) {
        if (node != null) {
            node.getProperties().put(EMBEDDED_CONTEXT_MENU_NODE_KEY, Boolean.TRUE);
        }
    }

    protected static boolean isEmbeddedContextMenuTarget(Object target, Node host) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Node current = node;
        while (current != null && current != host) {
            if (Boolean.TRUE.equals(current.getProperties().get(EMBEDDED_CONTEXT_MENU_NODE_KEY))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Markdown 表格：行号列 + 数据列按内容宽度布局，超出宿主宽度时等比压缩，避免内部滚动条。 */
    private static void layoutMarkdownTableView(
            CustomTableView<List<String>> tableView,
            double[] naturalColWidth,
            int numDataCols,
            CustomGenericStyledArea host) {
        if (tableView.getColumns().size() < numDataCols + 1) {
            return;
        }
        final double rowNumW = 34;
        TableColumn<List<String>, ?> rowCol = tableView.getColumns().get(0);
        rowCol.setPrefWidth(rowNumW);
        rowCol.setMinWidth(rowNumW);
        rowCol.setMaxWidth(rowNumW);

        double sumNatural = 0;
        for (int i = 0; i < numDataCols; i++) {
            sumNatural += naturalColWidth[i];
        }

        double pw = host != null ? host.getWidth() : 0;
        if (pw <= 0) {
            pw = 640;
        }
        // 宽度贴合宿主：减少额外预留，避免左右多出几像素
        double maxTable = Math.max(200, pw - 8);
        double dataBudget = Math.max(120, maxTable - rowNumW);
        double scale = sumNatural > dataBudget && sumNatural > 0 ? dataBudget / sumNatural : 1.0;

        double dataSum = 0;
        for (int i = 0; i < numDataCols; i++) {
            double w = Math.max(naturalColWidth[i] * scale, 36);
            TableColumn<List<String>, ?> col = tableView.getColumns().get(i + 1);
            col.setPrefWidth(w);
            col.setMinWidth(32);
            dataSum += w;
        }
        // 去掉额外 +10，宽度与内容和宿主宽度更贴合
        double totalW = Math.min(rowNumW + dataSum, maxTable);
        tableView.setPrefWidth(totalW);
        tableView.setMinWidth(totalW);
        tableView.setMaxWidth(totalW);
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

        if (shouldSkipStrictHttpValidation(url)) {
            LINK_CHECK_CACHE.remove(url);
            LINK_CHECK_IN_FLIGHT.remove(url);
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
            Boolean valid = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int code = conn.getResponseCode();
                if (isRedirect(code)) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        valid = false;
                    } else {
                        String redirectUrl = buildRedirectUrl(url, location);
                        valid = validateFinalUrl(redirectUrl, 0);
                    }
                } else {
                    valid = isAcceptableHttpLinkStatus(code);
                }
            } catch (Exception ex) {
                log.warn("Link validation request failed, marking as invalid: {}", url, ex);
                valid = false;
            } finally {
                LINK_CHECK_IN_FLIGHT.remove(url);
                if (valid != null) {
                    LINK_CHECK_CACHE.put(url, valid);
                }
            }

            if (Boolean.FALSE.equals(valid)) {
                Platform.runLater(() -> textNode.setStyle(INVALID_LINK_STYLE));
            }
        });
    }

    private static boolean isAcceptableHttpLinkStatus(int code) {
        return (code >= 200 && code < 400) || code == HttpURLConnection.HTTP_UNAUTHORIZED
                || code == HttpURLConnection.HTTP_FORBIDDEN || code == HttpURLConnection.HTTP_BAD_METHOD;
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307 /* HTTP_TEMP_REDIRECT */
                || code == 308 /* HTTP_PERMANENT_REDIRECT */;
    }

    private static final int MAX_REDIRECTS = 5;

    private static Boolean validateFinalUrl(String url, int depth) {
        if (depth >= MAX_REDIRECTS) {
            return false;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (isRedirect(code)) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) {
                    return false;
                }
                String redirectUrl = buildRedirectUrl(url, location);
                return validateFinalUrl(redirectUrl, depth + 1);
            }
            return isAcceptableHttpLinkStatus(code);
        } catch (Exception ex) {
            log.warn("Redirect target validation failed: {}", url, ex);
            return false;
        }
    }

    private static String buildRedirectUrl(String baseUrl, String location) {
        try {
            URL base = new URL(baseUrl);
            return new URL(base, location).toString();
        } catch (Exception ex) {
            return location;
        }
    }

    private static boolean shouldSkipStrictHttpValidation(String url) {
        try {
            String host = new URL(url).getHost().toLowerCase(Locale.ROOT);
            return "console.volcengine.com".equals(host);
        } catch (Exception ex) {
            return false;
        }
    }

    private static String resolveDownloadFileName(String url, boolean isHttpLink) throws Exception {
        if (isHttpLink) {
            String name = DownloadManagerUtil.getRealFileNameFromRedirect(url);
            if (name != null && !name.isEmpty()) {
                return name;
            }
            try {
                URL parsedUrl = new URL(url);
                String path = parsedUrl.getPath();
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash < path.length() - 1) {
                    return path.substring(slash + 1);
                }
            } catch (Exception ignored) {
            }
            return "downloaded.file";
        }

        int slash = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
        if (slash >= 0 && slash < url.length() - 1) {
            return url.substring(slash + 1);
        }
        return "downloaded.file";
    }

    public static Path getAbsPath(File file,String url){
        Path relative  = Paths.get(url);
        if (relative.isAbsolute()) {
            return relative.normalize();
        }
        Path appDir = Path.of(System.getProperty("user.dir"));
        Path baseFile = Path.of(file.getPath());
        Path baseDir = baseFile.getParent();
        Path absolutePath = (baseDir == null ? relative : baseDir.resolve(relative)).normalize();
        if (absolutePath.startsWith(appDir) && absolutePath.getNameCount() > appDir.getNameCount()) {
            return absolutePath.subpath(
                    appDir.getNameCount(),
                    absolutePath.getNameCount()
            );
        }
        return absolutePath;
    }




}
