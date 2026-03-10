package com.dbboys.customnode;

import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.util.AlertUtil;
import com.dbboys.util.MarkdownUtil;
import com.dbboys.util.TabpaneUtil;
import com.dbboys.vo.Markdown;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 重构后的 CustomMarkdownTreeCell：确保事件只注册一次、在 updateItem 中正确清理并设置状态、
 * 拖放操作安全（不会依赖外部静态缓存）、并在完成后在 UI 线程安全地移除源节点。
 */
public class CustomMarkdownTreeCell extends TreeCell<Markdown> {
    private static final Logger log = LogManager.getLogger(CustomMarkdownTreeCell.class);
    private static final String TREE_CELL_DRAG_OVER_STYLE = "tree-cell-drag-over";
    private static final Color DEFAULT_ICON_COLOR = Color.valueOf("#fff");
    private static final String EXT_MD = "md";
    private static final String EXT_PDF = "pdf";
    private static final String EXT_DOC = "doc";
    private static final String EXT_DOCX = "docx";
    private static final String EXT_PPT = "ppt";
    private static final String EXT_PPTX = "pptx";
    private static final String EXT_XLS = "xls";
    private static final String EXT_XLSX = "xlsx";
    private static final String EXT_TXT = "txt";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp", "gif");


    // 用于在拖拽开始时记录被拖拽的源 TreeItems（来源于 TreeView 的 selection）
    // 注意：这个字段只在拖拽源的 cell 实例中保存，用于在 dragDetected 时组装 Dragboard 数据。
    // 在 drop 时，我们通过从 TreeView 查找对应文件路径的 TreeItem 来移除源节点（避免跨 cell 直接引用）。

    public CustomMarkdownTreeCell() {
        // 事件在构造器注册一次，处理器内部使用 getItem() / getTreeView() 获取当前上下文
        setOnMouseClicked(this::handleMouseClicked);
        setOnDragDetected(this::handleDragDetected);
        setOnDragOver(this::handleDragOver);
        setOnDragDropped(this::handleDragDropped);
        setOnDragExited(this::handleDragExited);
    }

    @Override
    protected void updateItem(Markdown markdown, boolean empty) {
        super.updateItem(markdown, empty);

        // --- 必要的清理（以防 cell 被重用） ---
        // 解除 text 绑定，清除图标、文本、拖拽样式
        try {
            textProperty().unbind();
        } catch (Exception ignored) {
        }
        setText(null);
        setGraphic(null);
        getStyleClass().remove(TREE_CELL_DRAG_OVER_STYLE);
        // 清除 context-sensitive handlers（在构造器里已绑定持久 handler，updateItem 只需要清理基于 item 的状态）
        // 不要在这里移除事件处理器（已在构造器注册），否则会失去持久行为

        if (empty || markdown == null) {
            // 保证空状态下没有残留
            return;
        }

        // 绑定文本属性（采用绑定可随 markdown name 变化实时更新）
        textProperty().bind(markdown.nameProperty());

        // 设置图标
        setGraphic(createNodeIcon(markdown));

        // （可选）设置 context menu：如果你需要，可以恢复 setContextMenu(...) 调用
        // setContextMenu(MarkdownUtil.createContextMenu(markdown));
    }

    // ============ 事件处理器 ============

    private void handleMouseClicked(javafx.scene.input.MouseEvent event)  {
        if (getItem() == null) return;
        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            Markdown markdown = getItem();
            File f = markdown.getFile();
            if (f.isFile()) {
                if (f.getPath().toLowerCase().endsWith(".md")) {
                    TabpaneUtil.addCustomMarkdownTab(new File(f.getAbsolutePath()), false);
                } else {
                    try {
                        Desktop.getDesktop().open(f);
                    }catch (Exception e){
                        log.error(e.getMessage(), e);
                    }
                }
            }
        }
    }

    private void handleDragDetected(MouseEvent event) {
        // 仅当有有效 item 且为可拖拽项时触发
        if (getItem() == null) return;

        TreeView<Markdown> tree = getTreeView();
        if (tree == null) return;

        // 获取所有选中的顶层 TreeItems（排除被包含在选中其他节点下的子节点）
        ObservableList<TreeItem<Markdown>> selected = tree.getSelectionModel().getSelectedItems();
        List<TreeItem<Markdown>> topLevel = MarkdownUtil.filterTopLevelItems(selected); // 仍使用你现有的工具来筛选顶层
        if (topLevel.isEmpty()) return;

        MarkdownUtil.sourceTreeItems = topLevel; // 当前节点

        // 组装要放入 Dragboard 的文件列表与路径字符串（作为备用）
        List<File> files = topLevel.stream()
                .map(ti -> ti.getValue().getFile())
                .collect(Collectors.toList());

        String pathsJoined = files.stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.joining(";")); // 仅作为备用字符串

        // 创建拖拽视图（snapshot）
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        javafx.scene.image.Image dragImage = this.snapshot(params, null);

        Dragboard db = startDragAndDrop(TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();

        // 使用 putFiles 以便目标能直接读取文件集合
        content.putFiles(files);
        content.putString(pathsJoined);

        db.setContent(content);
        db.setDragView(dragImage, event.getX(), event.getY());
        event.consume();
    }

    private void handleDragOver(DragEvent event) {
        // 如果拖拽源不是自己，且 Dragboard 有文件，且目标是目录，则允许 move
        if (event.getGestureSource() == this) {
            event.consume();
            return;
        }

        Dragboard db = event.getDragboard();
        if (!db.hasFiles() && !db.hasString()) {
            event.consume();
            return;
        }

        TreeItem<Markdown> targetItem = getTreeItem();
        if (targetItem == null) {
            event.consume();
            return;
        }

        File targetFile;
        try {
            targetFile = targetItem.getValue().getFile().getCanonicalFile();
        } catch (IOException e) {
            event.consume();
            return;
        }

        if (!targetFile.isDirectory()) {
            // 只能拖到目录上
            event.consume();
            return;
        }

        // 验证每个源文件/目录与目标的关系：不能将目录拖入其子目录，也不能拖入自身所在的父目录（即已存在）
        List<Path> sourcePaths = extractPathsFromDragboard(db);
        boolean isValid = true;
        for (Path src : sourcePaths) {
            if (src == null) continue;
            Path targetPath = targetFile.toPath();
            if (src.equals(targetPath)
                    || targetPath.startsWith(src)
                    || Objects.equals(src.getParent(), targetPath)) {
                isValid = false;
                break;
            }
        }

        if (isValid) {
            event.acceptTransferModes(TransferMode.MOVE);
            if (!getStyleClass().contains(TREE_CELL_DRAG_OVER_STYLE)) {
                getStyleClass().add(TREE_CELL_DRAG_OVER_STYLE);
            }
        } else {
            getStyleClass().remove(TREE_CELL_DRAG_OVER_STYLE);
        }
        event.consume();
    }

    private void handleDragDropped(DragEvent event) {
        boolean success = false;
        Dragboard db = event.getDragboard();
        if (!db.hasFiles() && !db.hasString()) {
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        TreeItem<Markdown> dropTargetItem = getTreeItem();
        if (dropTargetItem == null) {
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        File targetDir;
        try {
            targetDir = dropTargetItem.getValue().getFile().getCanonicalFile();
        } catch (IOException e) {
            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("markdown.tree.error.access_target_dir", "无法访问目标目录: ") + e.getMessage());
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        if (!targetDir.isDirectory()) {
            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("markdown.tree.error.target_must_be_dir", "目标必须是目录"));
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        // 检查打开状态（避免正在编辑的文件被移动）
        List<Path> sourcePaths = extractPathsFromDragboard(db);
        try {
            for (Path p : sourcePaths) {
                File f = p.toFile();
                if (TabpaneUtil.findCustomMarkdownTab(f.toPath()) != null) {
                    if (f.isDirectory())
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), String.format(I18n.t("markdown.tree.error.folder_opened", "文件夹【%s】中有文件正在被打开，请关闭文件后重试!"), f.getName()));
                    else
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), String.format(I18n.t("markdown.tree.error.file_opened", "文件【%s】正在被打开，请关闭文件后重试!"), f.getName()));
                    event.setDropCompleted(false);
                    event.consume();
                    return;
                }
            }
        } catch (Exception ex) {
            // 继续处理，但记录异常
            log.error(ex.getMessage(), ex);
        }

        if (!AlertUtil.CustomAlertConfirm(
                I18n.t("markdown.tree.confirm.drag_title", "拖动确认"),
                I18n.t("markdown.tree.confirm.drag_message", "确定要拖动对象吗？")
        )) {
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        try {
            Path targetPath = targetDir.toPath();
            // 执行移动
            for (Path src : sourcePaths) {
                Path destination = targetPath.resolve(src.getFileName());
                if (Files.exists(destination)) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            I18n.t("markdown.tree.error.drag_target_exists", "目标已存在同名文件/文件夹: ") + src.getFileName()
                    );
                    event.setDropCompleted(false);
                    event.consume();
                    return;
                }
                Files.move(src, destination);
            }

            // 移动成功后：刷新目标节点并删除源节点（在 UI 线程执行）
            Platform.runLater(() -> {
                    // 刷新目标目录节点
                getTreeView().getSelectionModel().clearSelection();
                getTreeView().getSelectionModel().select(getTreeItem());
                    MarkdownUtil.refreshNode(dropTargetItem);


                    // 删除源节点
                if (MarkdownUtil.sourceTreeItems != null) {
                    for (TreeItem<Markdown> sourceItem : MarkdownUtil.sourceTreeItems) {
                        sourceItem.getParent().getChildren().remove(sourceItem);
                    }
                    MarkdownUtil.sourceTreeItems = null; // 清空缓存
                }
            });

            success = true;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("markdown.tree.error.move_failed", "移动失败: ") + e.getMessage());
            success = false;
        }

        event.setDropCompleted(success);
        getStyleClass().remove(TREE_CELL_DRAG_OVER_STYLE);
        event.consume();
    }

    private void handleDragExited(DragEvent event) {
        getStyleClass().remove(TREE_CELL_DRAG_OVER_STYLE);
        event.consume();
    }

    // ============ 辅助方法 ============

    /**
     * 从 Dragboard 中提取路径列表（优先使用 getFiles()，否则解析字符串）。
     */
    private List<Path> extractPathsFromDragboard(Dragboard db) {
        List<Path> result = new ArrayList<>();
        try {
            if (db.hasFiles()) {
                for (File f : db.getFiles()) {
                    try {
                        result.add(f.getCanonicalFile().toPath());
                    } catch (IOException e) {
                        result.add(f.toPath());
                    }
                }
                return result;
            }
            if (db.hasString()) {
                String s = db.getString();
                if (s != null && !s.isEmpty()) {
                    String[] parts = s.split(";");
                    for (String p : parts) {
                        if (!p.trim().isEmpty()) {
                            try {
                                result.add(new File(p).getCanonicalFile().toPath());
                            } catch (IOException e) {
                                result.add(new File(p).toPath());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }


    /**
     * 根据 Markdown 创建图标节点（避免直接用 Group 导致尺寸不可控；这里用 Group 保持和你原始逻辑一致，但可以替换成 HBox/StackPane）
     */
    private Group createNodeIcon(Markdown markdown) {
        var nodeIcon = IconFactory.create("", 0.6, 0.6, DEFAULT_ICON_COLOR);

        File f = markdown.getFile();
        if (f.isDirectory()) {

            if(getTreeItem().isExpanded()) {
                nodeIcon.setScaleX(0.5);
                nodeIcon.setScaleY(0.5);
                nodeIcon.setContent("M23.8594 13.3203 Q23.8594 12.8516 23.1406 12.8516 L8.5781 12.8516 Q8.0312 12.8516 7.4219 13.1484 Q6.8125 13.4297 6.4688 13.8516 L2.5312 18.7109 Q2.2969 19.0391 2.2969 19.2422 Q2.2969 19.7109 3 19.7109 L17.5781 19.7109 Q18.1094 19.7109 18.7188 19.4297 Q19.3438 19.1328 19.6719 18.7109 L23.6094 13.8516 Q23.8594 13.5547 23.8594 13.3203 ZM8.5781 11.1484 L18.8594 11.1484 L18.8594 9.0078 Q18.8594 8.4609 18.4844 8.0859 Q18.1094 7.7109 17.5781 7.7109 L9.8594 7.7109 Q9.3281 7.7109 8.9531 7.3359 Q8.5781 6.9609 8.5781 6.4297 L8.5781 5.5703 Q8.5781 5.0391 8.2031 4.6641 Q7.8281 4.2891 7.2812 4.2891 L3 4.2891 Q2.4688 4.2891 2.0938 4.6641 Q1.7188 5.0391 1.7188 5.5703 L1.7188 16.9922 L5.1406 12.7734 Q5.7344 12.0703 6.6875 11.6172 Q7.6562 11.1484 8.5781 11.1484 ZM25.5625 13.3203 Q25.5625 14.1641 24.9531 14.9297 L21 19.7891 Q20.4219 20.5078 19.4375 20.9766 Q18.4688 21.4297 17.5781 21.4297 L3 21.4297 Q1.7656 21.4297 0.875 20.5547 Q0 19.6641 0 18.4297 L0 5.5703 Q0 4.3359 0.875 3.4609 Q1.7656 2.5703 3 2.5703 L7.2812 2.5703 Q8.5156 2.5703 9.3906 3.4609 Q10.2812 4.3359 10.2812 5.5703 L10.2812 6.0078 L17.5781 6.0078 Q18.7969 6.0078 19.6875 6.8984 Q20.5781 7.7734 20.5781 9.0078 L20.5781 11.1484 L23.1406 11.1484 Q23.8594 11.1484 24.4688 11.4766 Q25.0781 11.8047 25.3594 12.4141 Q25.5625 12.8516 25.5625 13.3203 Z");
            }else {
                nodeIcon.setScaleX(0.55);
                nodeIcon.setScaleY(0.55);
                nodeIcon.setContent("M1.5156 5.2869 Q1.5156 4.3181 2.1562 3.6463 Q2.7969 2.9588 3.7656 3.0525 L7.9219 3.0525 Q9.7656 3.0525 11.3594 4.8025 Q12.4844 6.0056 13.5156 6.0056 L20.2344 6.0056 Q21.2031 6.0056 21.8438 6.6462 Q22.4844 7.2869 22.4844 8.24 L22.4844 18.7244 Q22.4844 19.6931 21.8438 20.3337 Q21.2031 20.9587 20.2344 20.9587 L3.7656 20.9587 Q2.7969 20.9587 2.1562 20.3337 Q1.5156 19.6931 1.5156 18.7244 L1.5156 5.2869 ZM3.7656 4.49 Q3.4375 4.49 3.2344 4.7244 Q3.0469 4.9587 3.0469 5.2869 L3.0469 8.9587 L21.0469 8.9587 L21.0469 8.24 Q21.0469 7.9275 20.7969 7.7244 Q20.5625 7.5212 20.2344 7.5212 L13.5156 7.5212 Q11.7656 7.5212 10.2344 5.7712 Q9.125 4.49 7.9219 4.49 L3.7656 4.49 ZM21.0469 10.49 L3.0469 10.49 L3.0469 18.7244 Q3.0469 19.0525 3.2344 19.2869 Q3.4375 19.5212 3.7656 19.5212 L20.2344 19.5212 Q20.5625 19.5212 20.7969 19.2869 Q21.0469 19.0525 21.0469 18.7244 L21.0469 10.49 Z");
            }
                nodeIcon.setFill(DEFAULT_ICON_COLOR);
        } else if (hasExtension(f, EXT_MD)) {
            nodeIcon.setContent("M20.0469 6.7656 Q20.1406 6.8594 20.1875 7 Q20.25 7.1406 20.25 7.2812 L20.25 21.75 Q20.25 22.0469 20.0312 22.2812 Q19.8125 22.5 19.5 22.5 L4.5 22.5 Q4.2031 22.5 3.9688 22.2812 Q3.75 22.0469 3.75 21.75 L3.75 2.25 Q3.75 1.9375 3.9688 1.7188 Q4.2031 1.5 4.5 1.5 L14.4688 1.5 Q14.6094 1.5 14.75 1.5625 Q14.8906 1.6094 14.9844 1.7031 L20.0469 6.7656 L20.0469 6.7656 ZM18.5156 7.6406 L14.1094 3.2344 L14.1094 7.6406 L18.5156 7.6406 ZM9.9844 14.0781 L11.375 17.2031 Q11.4219 17.2969 11.5156 17.3594 Q11.6094 17.4062 11.7188 17.4062 L12.2812 17.4062 Q12.2812 17.4062 12.2812 17.4062 Q12.2812 17.4062 12.2812 17.4062 Q12.4062 17.4062 12.5 17.3594 Q12.5938 17.2969 12.6406 17.2031 L12.6406 17.2031 L14.0156 14.0781 L14.0156 17.7656 Q14.0156 17.9219 14.125 18.0312 Q14.2344 18.1406 14.3906 18.1406 Q14.3906 18.1406 14.3906 18.1406 Q14.3906 18.1406 14.3906 18.1406 L15.0312 18.1406 Q15.1875 18.1406 15.2969 18.0312 Q15.4062 17.9219 15.4062 17.7656 L15.4062 17.7656 L15.4062 11.3906 Q15.4062 11.2188 15.2969 11.125 Q15.1875 11.0156 15.0312 11.0156 L15.0312 11.0156 L14.2031 11.0156 Q14.0938 11.0156 14 11.0781 Q13.9062 11.125 13.875 11.25 L13.875 11.25 L12 15.5156 L10.1562 11.25 Q10.1094 11.125 10.0156 11.0781 Q9.9219 11.0156 9.7969 11.0156 Q9.7969 11.0156 9.7969 11.0156 Q9.7969 11.0156 9.7969 11.0156 L8.9844 11.0156 Q8.8125 11.0156 8.7031 11.125 Q8.6094 11.2188 8.6094 11.3906 L8.6094 11.3906 L8.6094 17.7656 Q8.6094 17.9219 8.7031 18.0312 Q8.8125 18.1406 8.9844 18.1406 L8.9844 18.1406 L9.6094 18.1406 Q9.7812 18.1406 9.875 18.0312 Q9.9844 17.9219 9.9844 17.7656 L9.9844 17.7656 L9.9844 14.0781 L9.9844 14.0781 Z");
        } else if (isImageFile(f)) {
            nodeIcon.setContent("M20.0469 6.7656 L14.9844 1.7031 Q14.8906 1.6094 14.75 1.5625 Q14.6094 1.5 14.4688 1.5 L4.5 1.5 Q4.2031 1.5 3.9688 1.7188 Q3.75 1.9375 3.75 2.25 L3.75 21.75 Q3.75 22.0469 3.9688 22.2812 Q4.2031 22.5 4.5 22.5 L19.5 22.5 Q19.8125 22.5 20.0312 22.2812 Q20.25 22.0469 20.25 21.75 L20.25 7.2812 Q20.25 7.1406 20.1875 7 Q20.1406 6.8594 20.0469 6.7656 L20.0469 6.7656 ZM9.375 9.4219 Q9.7812 9.4219 10.0469 9.6875 Q10.3125 9.9531 10.3125 10.3594 Q10.3125 10.75 10.0469 11.0312 Q9.7812 11.2969 9.375 11.2969 Q8.9844 11.2969 8.7031 11.0312 Q8.4375 10.75 8.4375 10.3594 Q8.4375 9.9531 8.7031 9.6875 Q8.9844 9.4219 9.375 9.4219 ZM16.3125 16.3125 L7.6875 16.3125 Q7.5781 16.3125 7.5312 16.2031 Q7.4844 16.0938 7.5469 16 L9.875 13.0312 Q9.9219 12.9844 9.9531 12.9688 Q9.9844 12.9531 10.0312 12.9531 Q10.0781 12.9531 10.1094 12.9688 Q10.1562 12.9844 10.1719 13.0312 L10.1719 13.0312 L11.1406 14.25 L12.9688 11.9219 Q12.9844 11.9062 13.0156 11.8906 Q13.0625 11.8594 13.1094 11.8594 Q13.1562 11.8594 13.1875 11.8906 Q13.2188 11.9062 13.2656 11.9219 L13.2656 11.9219 L16.4531 16 Q16.5312 16.0938 16.4844 16.2031 Q16.4375 16.3125 16.3125 16.3125 L16.3125 16.3125 ZM14.1094 7.6406 L14.1094 3.2344 L18.5156 7.6406 L14.1094 7.6406 Z");
        } else if (hasExtension(f, EXT_PDF)) {
            nodeIcon.setContent("M20.0469 6.7656 Q20.1406 6.8594 20.1875 7 Q20.25 7.1406 20.25 7.2812 L20.25 21.75 Q20.25 22.0469 20.0312 22.2812 Q19.8125 22.5 19.5 22.5 L4.5 22.5 Q4.2031 22.5 3.9688 22.2812 Q3.75 22.0469 3.75 21.75 L3.75 2.25 Q3.75 1.9375 3.9688 1.7188 Q4.2031 1.5 4.5 1.5 L14.4688 1.5 Q14.6094 1.5 14.75 1.5625 Q14.8906 1.6094 14.9844 1.7031 L20.0469 6.7656 L20.0469 6.7656 ZM18.5156 7.6406 L14.1094 3.2344 L14.1094 7.6406 L18.5156 7.6406 ZM14.8438 14.9219 Q14.5781 14.9219 14.2812 14.9531 Q14 14.9688 13.6875 15 Q13.25 14.7344 12.9531 14.3594 Q12.6562 13.9844 12.4531 13.4531 L12.4688 13.3594 L12.5156 13.2344 Q12.5938 12.9062 12.625 12.6719 Q12.6562 12.4219 12.6875 12.1875 Q12.6875 12.0156 12.6719 11.8594 Q12.6562 11.6875 12.6406 11.5312 Q12.5938 11.2031 12.3594 11.0312 Q12.1406 10.8438 11.8594 10.8281 Q11.5781 10.8281 11.3594 10.9531 Q11.1406 11.0781 11.0938 11.3125 Q10.9688 11.7188 11.0312 12.2656 Q11.0938 12.7969 11.3281 13.6406 Q11.0469 14.3125 10.7031 15.0156 Q10.3594 15.7188 10.125 16.1719 Q9.7812 16.3281 9.5156 16.5 Q9.2656 16.6562 9.0469 16.8281 Q8.75 17.0625 8.5781 17.2969 Q8.4219 17.5312 8.3438 17.7656 Q8.3281 17.875 8.3594 18.0156 Q8.3906 18.1562 8.4844 18.2812 Q8.5781 18.4219 8.7031 18.5 Q8.8438 18.5781 9.0312 18.6094 Q9.4531 18.625 9.9688 18.1875 Q10.4844 17.7344 11.0469 16.75 Q11.1094 16.7344 11.1719 16.7188 Q11.2344 16.6875 11.2969 16.6562 L11.5781 16.5625 Q11.7188 16.5156 11.8125 16.4844 Q11.9062 16.4531 12.0312 16.4219 Q12.4219 16.2812 12.75 16.2031 Q13.0781 16.125 13.3594 16.0781 Q13.8594 16.3281 14.375 16.4844 Q14.8906 16.6406 15.2812 16.6406 Q15.5938 16.6406 15.7969 16.4844 Q16.0156 16.3281 16.0781 16.0781 Q16.1562 15.8594 16.1094 15.625 Q16.0625 15.3906 15.9219 15.2344 Q15.75 15.0938 15.4844 15.0312 Q15.2188 14.9531 14.8438 14.9219 L14.8438 14.9219 ZM9.0312 17.9531 L9.0312 17.9219 L9.0312 17.9219 Q9.0781 17.8594 9.0938 17.7969 Q9.125 17.7188 9.1719 17.6719 L9.1719 17.6719 Q9.2344 17.5469 9.3438 17.4375 Q9.4531 17.3125 9.5625 17.1719 Q9.6406 17.1094 9.7188 17.0469 Q9.7969 16.9688 9.875 16.8906 Q9.8906 16.8906 9.9688 16.8125 Q10.0625 16.7344 10.0781 16.7031 L10.3594 16.4688 L10.1562 16.7812 Q9.9375 17.1094 9.75 17.375 Q9.5625 17.625 9.375 17.7812 Q9.3281 17.8281 9.2656 17.875 Q9.2188 17.9219 9.1719 17.9531 Q9.1719 17.9531 9.1562 17.9688 Q9.1406 17.9688 9.125 17.9688 L9.125 17.9688 Q9.0938 18 9.0938 18 Q9.0938 18 9.0938 18 Q9.0938 18 9.0781 18 Q9.0781 18 9.0781 18 Q9.0781 18 9.0781 18 Q9.0781 18 9.0469 18 L9.0469 18 Q9.0469 17.9688 9.0312 17.9688 Q9.0312 17.9531 9.0312 17.9531 Q9.0312 17.9531 9.0312 17.9531 Q9.0312 17.9531 9.0312 17.9531 L9.0312 17.9531 L9.0312 17.9531 ZM11.9844 12.8125 L11.9375 12.9062 L11.9062 12.8125 Q11.8438 12.6562 11.7969 12.4062 Q11.7656 12.1562 11.7656 11.9219 Q11.75 11.6719 11.7656 11.5156 Q11.7969 11.3594 11.8906 11.3594 Q12 11.3594 12.0625 11.5469 Q12.125 11.7188 12.125 12 Q12.125 12.2344 12.0781 12.4844 Q12.0469 12.7188 11.9844 12.8125 L11.9844 12.8125 ZM11.8438 14.2031 L11.8906 14.1094 L11.9375 14.2031 Q12.1406 14.5781 12.3906 14.8906 Q12.6562 15.1875 12.9688 15.3906 L13.0312 15.4688 L12.9375 15.4688 Q12.6406 15.5312 12.3594 15.625 Q12.0781 15.7188 11.7031 15.8594 Q11.75 15.8594 11.4531 15.9844 Q11.1562 16.0938 11.0625 16.125 L10.9531 16.1875 L11 16.0781 Q11.2344 15.7031 11.4219 15.2344 Q11.625 14.7656 11.8438 14.2031 L11.8438 14.2031 ZM15.5469 15.9844 Q15.4062 16.0312 15.0938 15.9688 Q14.7969 15.9062 14.25 15.7031 L14.0938 15.625 L14.2812 15.6094 Q14.7031 15.5781 14.9844 15.5938 Q15.2656 15.6094 15.4219 15.6719 Q15.5156 15.7031 15.5625 15.7344 Q15.6094 15.7656 15.6406 15.8125 Q15.6406 15.8125 15.6406 15.8281 Q15.6406 15.8438 15.6406 15.8594 Q15.6406 15.8906 15.625 15.9219 Q15.6094 15.9375 15.5938 15.9531 L15.5938 15.9531 Q15.5938 15.9531 15.5781 15.9688 Q15.5625 15.9844 15.5469 15.9844 L15.5469 15.9844 L15.5469 15.9844 Z");
        } else if (hasExtension(f, EXT_DOC) || hasExtension(f, EXT_DOCX)) {
            nodeIcon.setContent("M20.0469 6.7656 Q20.1406 6.8594 20.1875 7 Q20.25 7.1406 20.25 7.2812 L20.25 21.75 Q20.25 22.0469 20.0312 22.2812 Q19.8125 22.5 19.5 22.5 L4.5 22.5 Q4.2031 22.5 3.9688 22.2812 Q3.75 22.0469 3.75 21.75 L3.75 2.25 Q3.75 1.9375 3.9688 1.7188 Q4.2031 1.5 4.5 1.5 L14.4688 1.5 Q14.6094 1.5 14.75 1.5625 Q14.8906 1.6094 14.9844 1.7031 L20.0469 6.7656 L20.0469 6.7656 ZM18.5156 7.6406 L14.1094 3.2344 L14.1094 7.6406 L18.5156 7.6406 ZM12 13.2656 L13.25 17.875 Q13.2656 17.9688 13.3281 18.0312 Q13.4062 18.0938 13.5 18.0938 Q13.5 18.0938 13.5 18.0938 Q13.5 18.0938 13.5 18.0938 L14.25 18.0938 Q14.25 18.0938 14.25 18.0938 Q14.25 18.0938 14.25 18.0938 Q14.3438 18.0938 14.4219 18.0312 Q14.5156 17.9688 14.5312 17.875 L14.5312 17.875 L16.2656 11.4062 Q16.2656 11.3906 16.2812 11.375 Q16.2969 11.3594 16.2969 11.3438 Q16.2969 11.3438 16.2969 11.3438 Q16.2969 11.3438 16.2969 11.3438 L16.2969 11.3438 Q16.2969 11.2188 16.2031 11.1406 Q16.125 11.0625 16.0156 11.0625 L16.0156 11.0625 L15.1719 11.0625 Q15.1719 11.0625 15.1719 11.0625 Q15.1719 11.0625 15.1719 11.0625 Q15.0781 11.0625 14.9844 11.125 Q14.9062 11.1719 14.8906 11.2656 L14.8906 11.2656 L13.8281 15.9375 L12.6562 11.2656 Q12.6406 11.1719 12.5469 11.125 Q12.4688 11.0625 12.375 11.0625 L11.625 11.0625 Q11.5312 11.0625 11.4531 11.125 Q11.375 11.1719 11.3438 11.2656 L11.3438 11.2656 L10.2031 15.9375 L9.125 11.2656 Q9.0938 11.1719 9.0156 11.125 Q8.9375 11.0625 8.8438 11.0625 L8 11.0625 Q8 11.0625 8 11.0625 Q8 11.0625 8 11.0625 Q8 11.0625 7.9688 11.0625 Q7.9531 11.0625 7.9219 11.0625 L7.9219 11.0625 Q7.8281 11.0781 7.7656 11.1719 Q7.7188 11.25 7.7188 11.3438 Q7.7188 11.3594 7.7188 11.375 Q7.7344 11.3906 7.7344 11.4062 L7.7344 11.4062 L9.4688 17.875 Q9.5 17.9688 9.5625 18.0312 Q9.6406 18.0938 9.75 18.0938 L10.5 18.0938 Q10.5938 18.0938 10.6562 18.0312 Q10.7344 17.9688 10.7656 17.875 L10.7656 17.875 L12 13.2656 L12 13.2656 Z");
        } else if (hasExtension(f, EXT_PPT) || hasExtension(f, EXT_PPTX)) {
            nodeIcon.setContent("M20.0469 6.7656 Q20.1406 6.8594 20.1875 7 Q20.25 7.1406 20.25 7.2812 L20.25 21.75 Q20.25 22.0469 20.0312 22.2812 Q19.8125 22.5 19.5 22.5 L4.5 22.5 Q4.2031 22.5 3.9688 22.2812 Q3.75 22.0469 3.75 21.75 L3.75 2.25 Q3.75 1.9375 3.9688 1.7188 Q4.2031 1.5 4.5 1.5 L14.4688 1.5 Q14.6094 1.5 14.75 1.5625 Q14.8906 1.6094 14.9844 1.7031 L20.0469 6.7656 L20.0469 6.7656 ZM18.5156 7.6406 L14.1094 3.2344 L14.1094 7.6406 L18.5156 7.6406 ZM11 17.8125 L11 15.6562 L12.375 15.6562 Q13.4375 15.6562 14.0781 15.0312 Q14.7188 14.3906 14.7188 13.3594 Q14.7188 12.3438 14.0781 11.7031 Q13.4375 11.0625 12.375 11.0625 L9.9375 11.0625 Q9.8281 11.0625 9.7344 11.1406 Q9.6562 11.2188 9.6562 11.3438 L9.6562 11.3438 L9.6562 17.8125 Q9.6562 17.9219 9.7344 18.0156 Q9.8281 18.0938 9.9375 18.0938 L9.9375 18.0938 L10.7188 18.0938 Q10.8281 18.0938 10.9062 18.0156 Q11 17.9219 11 17.8125 L11 17.8125 ZM11 14.5469 L11.7969 14.5469 Q12.6406 14.5469 13.0156 14.2812 Q13.3906 14.0156 13.3906 13.3594 Q13.3906 12.7969 13.0781 12.5 Q12.7812 12.1875 12.2188 12.1875 L11 12.1875 L11 14.5469 L11 14.5469 Z");
        } else if (hasExtension(f, EXT_XLS) || hasExtension(f, EXT_XLSX)) {
            nodeIcon.setContent("M20.0469 6.7656 Q20.1406 6.8594 20.1875 7 Q20.25 7.1406 20.25 7.2812 L20.25 21.75 Q20.25 22.0469 20.0312 22.2812 Q19.8125 22.5 19.5 22.5 L4.5 22.5 Q4.2031 22.5 3.9688 22.2812 Q3.75 22.0469 3.75 21.75 L3.75 2.25 Q3.75 1.9375 3.9688 1.7188 Q4.2031 1.5 4.5 1.5 L14.4688 1.5 Q14.6094 1.5 14.75 1.5625 Q14.8906 1.6094 14.9844 1.7031 L20.0469 6.7656 L20.0469 6.7656 ZM18.5156 7.6406 L14.1094 3.2344 L14.1094 7.6406 L18.5156 7.6406 ZM13.4844 11.2031 L12.0469 13.5938 L10.5938 11.2031 Q10.5781 11.125 10.5 11.0938 Q10.4375 11.0625 10.3594 11.0625 Q10.3594 11.0625 10.3594 11.0625 Q10.3594 11.0625 10.3594 11.0625 L9.4688 11.0625 Q9.4219 11.0625 9.3906 11.0781 Q9.3594 11.0781 9.3125 11.1094 L9.3125 11.1094 Q9.2656 11.125 9.2188 11.2031 Q9.1875 11.2656 9.1875 11.3438 Q9.1875 11.3906 9.2031 11.4219 Q9.2188 11.4531 9.2188 11.4844 L9.2188 11.4844 L11.1562 14.5469 L9.1875 17.6719 Q9.1719 17.6875 9.1562 17.7344 Q9.1406 17.7656 9.1406 17.8125 Q9.1406 17.8125 9.1406 17.8125 Q9.1406 17.8125 9.1406 17.8125 L9.1406 17.8125 Q9.1406 17.9219 9.2344 18.0156 Q9.3281 18.0938 9.4219 18.0938 L9.4219 18.0938 L10.25 18.0938 Q10.3125 18.0938 10.375 18.0625 Q10.4531 18.0156 10.4844 17.9531 L10.4844 17.9531 L11.9531 15.5781 L13.4062 17.9531 Q13.4531 18.0156 13.5156 18.0625 Q13.5781 18.0938 13.6406 18.0938 Q13.6406 18.0938 13.6406 18.0938 Q13.6406 18.0938 13.6406 18.0938 L14.5312 18.0938 Q14.5312 18.0938 14.5312 18.0938 Q14.5312 18.0938 14.5312 18.0938 Q14.5781 18.0938 14.6094 18.0781 Q14.6562 18.0625 14.6719 18.0469 L14.6719 18.0469 Q14.75 18 14.7812 17.9375 Q14.8125 17.875 14.8125 17.8125 Q14.8125 17.7656 14.7969 17.7344 Q14.7969 17.6875 14.7656 17.6406 L14.7656 17.6719 L12.7969 14.5938 L14.7969 11.5 Q14.8125 11.4531 14.8281 11.4219 Q14.8438 11.3906 14.8438 11.3438 Q14.8438 11.3438 14.8438 11.3438 Q14.8438 11.3438 14.8438 11.3438 L14.8438 11.3438 Q14.8438 11.2188 14.75 11.1406 Q14.6719 11.0625 14.5625 11.0625 L14.5625 11.0625 L13.7344 11.0625 Q13.6406 11.0625 13.5781 11.0938 Q13.5312 11.125 13.4844 11.2031 L13.4844 11.2031 L13.4844 11.2031 Z");
        } else if (hasExtension(f, EXT_TXT)) {
            nodeIcon.setContent("M20.0469 6.7656 Q20.1406 6.8594 20.1875 7 Q20.25 7.1406 20.25 7.2812 L20.25 21.75 Q20.25 22.0469 20.0312 22.2812 Q19.8125 22.5 19.5 22.5 L4.5 22.5 Q4.2031 22.5 3.9688 22.2812 Q3.75 22.0469 3.75 21.75 L3.75 2.25 Q3.75 1.9375 3.9688 1.7188 Q4.2031 1.5 4.5 1.5 L14.4688 1.5 Q14.6094 1.5 14.75 1.5625 Q14.8906 1.6094 14.9844 1.7031 L20.0469 6.7656 L20.0469 6.7656 ZM18.5156 7.6406 L14.1094 3.2344 L14.1094 7.6406 L18.5156 7.6406 ZM7.5 11.2969 Q7.4375 11.2969 7.375 11.3594 Q7.3125 11.4062 7.3125 11.4844 L7.3125 11.4844 L7.3125 12.6094 Q7.3125 12.6719 7.375 12.7344 Q7.4375 12.7969 7.5 12.7969 L7.5 12.7969 L16.5 12.7969 Q16.5781 12.7969 16.625 12.7344 Q16.6875 12.6719 16.6875 12.6094 L16.6875 12.6094 L16.6875 11.4844 Q16.6875 11.4062 16.625 11.3594 Q16.5781 11.2969 16.5 11.2969 L16.5 11.2969 L7.5 11.2969 ZM7.5 14.4844 Q7.4375 14.4844 7.375 14.5469 Q7.3125 14.5938 7.3125 14.6719 L7.3125 14.6719 L7.3125 15.7969 Q7.3125 15.8594 7.375 15.9219 Q7.4375 15.9844 7.5 15.9844 L7.5 15.9844 L11.8125 15.9844 Q11.8906 15.9844 11.9375 15.9219 Q12 15.8594 12 15.7969 L12 15.7969 L12 14.6719 Q12 14.5938 11.9375 14.5469 Q11.8906 14.4844 11.8125 14.4844 L11.8125 14.4844 L7.5 14.4844 Z");
        }else{
            nodeIcon.setContent("M20.0469 6.7656 Q20.1406 6.8594 20.1875 7 Q20.25 7.1406 20.25 7.2812 L20.25 21.75 Q20.25 22.0469 20.0312 22.2812 Q19.8125 22.5 19.5 22.5 L4.5 22.5 Q4.2031 22.5 3.9688 22.2812 Q3.75 22.0469 3.75 21.75 L3.75 2.25 Q3.75 1.9375 3.9688 1.7188 Q4.2031 1.5 4.5 1.5 L14.4688 1.5 Q14.6094 1.5 14.75 1.5625 Q14.8906 1.6094 14.9844 1.7031 L20.0469 6.7656 L20.0469 6.7656 ZM18.5156 7.6406 L14.1094 3.2344 L14.1094 7.6406 L18.5156 7.6406 ZM9.4219 12.8594 Q9.4219 12.9531 9.4844 13.0156 Q9.5625 13.0781 9.6562 13.0781 L10.4062 13.0781 Q10.5 13.0781 10.5625 13.0156 Q10.6406 12.9531 10.6406 12.8594 Q10.6406 12.375 11.0312 12.0312 Q11.4375 11.6719 12 11.6719 Q12.5625 11.6719 12.9531 12.0312 Q13.3594 12.375 13.3594 12.8594 Q13.3594 13.3125 13.0312 13.6562 Q12.7031 13.9844 12.2188 14.0625 Q11.8594 14.1094 11.625 14.375 Q11.3906 14.6406 11.3906 15 L11.3906 15.75 Q11.3906 15.8438 11.4531 15.9219 Q11.5312 15.9844 11.625 15.9844 L12.375 15.9844 Q12.4688 15.9844 12.5312 15.9219 Q12.6094 15.8438 12.6094 15.75 L12.6094 15.4688 Q12.6094 15.3438 12.6719 15.2656 Q12.7344 15.1875 12.8438 15.1562 Q13.625 14.9062 14.1094 14.2656 Q14.6094 13.6094 14.5781 12.8438 Q14.5625 11.8594 13.8125 11.1719 Q13.0781 10.4688 12.0469 10.4531 Q10.9531 10.4219 10.1875 11.1406 Q9.4219 11.8594 9.4219 12.8594 ZM12 18.1875 Q12.3125 18.1875 12.5312 17.9688 Q12.75 17.7344 12.75 17.4375 Q12.75 17.125 12.5312 16.9062 Q12.3125 16.6875 12 16.6875 L12 16.6875 Q11.7031 16.6875 11.4688 16.9062 Q11.25 17.125 11.25 17.4375 Q11.25 17.7344 11.4688 17.9688 Q11.7031 18.1875 12 18.1875 L12 18.1875 Z");

        }

        return new Group(nodeIcon);
    }

    private static boolean hasExtension(File file, String extension) {
        String fileName = file.getName().toLowerCase(Locale.ROOT);
        return fileName.endsWith("." + extension);
    }

    private static boolean isImageFile(File file) {
        String fileName = file.getName().toLowerCase(Locale.ROOT);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(fileName.substring(dotIndex + 1));
    }
}

