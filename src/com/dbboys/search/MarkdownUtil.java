package com.dbboys.search;
import com.dbboys.ui.component.CustomContextMenu;

import com.dbboys.ui.component.*;
import com.dbboys.ui.dialog.AlertUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.MenuItemUtil;
import com.dbboys.infra.util.TabpaneUtil;
import com.dbboys.model.Markdown;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MarkdownUtil {
    private static final Logger log = LogManager.getLogger(MarkdownUtil.class);
    private static Pattern LINK_PATTERN = Pattern.compile("\\[([^]]+)\\]\\(([^)]+)\\)");
    private Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:\\w+)?\\s*\\n?(.*?)```", Pattern.DOTALL);
    private static Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s*(.+)$", Pattern.MULTILINE);

    public static String markdownText="";
    public static TreeView<Markdown> treeView=new TreeView<>() ;
    private static List<TreeItem<Markdown>> clipboardFiles; // 替换原有的 clipboardFile
    private static boolean cutOperation;
    public static TreeItem<Markdown> selectedTreeItem;
    //public static TreeItem<Markdown> sourceTreeItem;
    public static ContextMenu contextMenu=new CustomContextMenu();


    public static List<TreeItem<Markdown>> sourceTreeItems;

    static {
        File rootDir = null;
        try {
            rootDir = new File("docs").getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        treeView.setRoot(createNode(rootDir));
        treeView.setShowRoot(false);
        treeView.setCellFactory(param -> new CustomMarkdownTreeCell());
        treeView.getStyleClass().add("markdown-tree-view");
        if(!treeView.getRoot().getChildren().isEmpty())
        treeView.getSelectionModel().select(treeView.getRoot().getChildren().get(0));
        //treeView.getRoot().getChildren().forEach(child -> child.setExpanded(true));
        createContextMenu();
    }


    //创建一个TreeItem,重构ifLeaf显示箭头
    public static TreeItem<Markdown> createNode(final File file) {
        Markdown markdown = new Markdown(file);
        TreeItem<Markdown> treeItem = new TreeItem<>(markdown) {
            @Override
            public boolean isLeaf() {
                // ⚠ 不要用外部的 file 变量
                Markdown current = getValue();
                if (current == null) return true;
                File f = current.getFile();
                return f == null || !f.isDirectory();
            }
        };
        if(file.isDirectory()) {
            treeItem.expandedProperty().addListener((obs, wasExpanded, isNowExpanded) -> {
                if (isNowExpanded&& treeItem.getChildren().isEmpty()){
                    buildChildren(treeItem);
                };
            });
        }
        return treeItem;
    }



    private static void buildChildren(TreeItem<Markdown> treeItem) {
        ObservableList<TreeItem<Markdown>> children = FXCollections.observableArrayList();
        File f = treeItem.getValue().getFile();
        if (f != null && f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                Arrays.sort(files, (f1, f2) -> {
                    if (f1.isDirectory() && !f2.isDirectory()) return -1;
                    if (!f1.isDirectory() && f2.isDirectory()) return 1;
                    return f1.getName().compareToIgnoreCase(f2.getName());
                });

                for (File child : files) {
                    try {
                        children.add(createNode(child.getCanonicalFile()));
                    } catch (IOException e) {
                        children.add(createNode(child.getAbsoluteFile()));
                    }
                }
                treeItem.getChildren().setAll(children);
            }
        }
    }


    private static void createContextMenu() {
        CustomShortcutMenuItem copyItem = MenuItemUtil.createMenuItemI18n("markdown.menu.copy", "Ctrl+C", IconFactory.group(IconPaths.COPY, 0.65, 0.65));
        CustomShortcutMenuItem cutItem = MenuItemUtil.createMenuItemI18n("markdown.menu.cut", "Ctrl+X", IconFactory.group(IconPaths.CUT, 0.6, 0.6));
        CustomShortcutMenuItem pasteItem = MenuItemUtil.createMenuItemI18n("markdown.menu.paste", "Ctrl+V", IconFactory.group(IconPaths.PASTE, 0.65, 0.65));
        CustomShortcutMenuItem renameItem = MenuItemUtil.createMenuItemI18n("markdown.menu.rename", IconFactory.group(IconPaths.METADATA_RENAME_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem newFileItem = MenuItemUtil.createMenuItemI18n("markdown.menu.new_file", "Ctrl+N", IconFactory.group(IconPaths.MARKDOWN_NEW_FILE_ITEM, 0.65, 0.6));
        CustomShortcutMenuItem newFolderItem = MenuItemUtil.createMenuItemI18n("markdown.menu.new_folder", IconFactory.group(IconPaths.MARKDOWN_NEW_FOLDER_ITEM, 0.65, 0.65));
        CustomShortcutMenuItem newRootFolderItem = MenuItemUtil.createMenuItemI18n("markdown.menu.new_root_folder", IconFactory.group(IconPaths.MARKDOWN_NEW_FOLDER_ITEM, 0.7, 0.7));
        CustomShortcutMenuItem deleteItem = MenuItemUtil.createMenuItemI18n("markdown.menu.delete", "Delete", IconFactory.group(IconPaths.METADATA_DELETE_ITEM, 0.7, 0.7, IconFactory.dangerColor()));
        CustomShortcutMenuItem refreshItem = MenuItemUtil.createMenuItemI18n("markdown.menu.refresh", IconFactory.group(IconPaths.METADATA_REFRESH_ITEM, 0.7, 0.7));

        //绑定操作在第一次执行时无效，鼠标点击一次后有效，如果与按键一起设置，可能重复执行两次
        //copyItem.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
        //cutItem.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.CONTROL_DOWN));
        //pasteItem.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
        //newFileItem.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        //deleteItem.setAccelerator(new KeyCodeCombination(KeyCode.DELETE));

        //绑定操作在第一次执行时无效，鼠标点击一次后有效
        treeView.setOnKeyPressed(event -> {
            if(event.isControlDown()&&event.getCode() == KeyCode.C){
                copyItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.X){
                cutItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.V){
                pasteItem.fire();
            }
            if(event.isControlDown()&&event.getCode() == KeyCode.N){
                newFileItem.fire();
            }
            if(event.getCode() == KeyCode.DELETE){
                deleteItem.fire();
            }

        });




        // 复制到程序内部 + 系统剪贴板
        copyItem.setOnAction(e -> {
            ObservableList<TreeItem<Markdown>> selectedItems = treeView.getSelectionModel().getSelectedItems();
            copyFiles(selectedItems);
        });

        // 剪切，仅程序内部
        cutItem.setOnAction(e -> {
            ObservableList<TreeItem<Markdown>> selectedItems = treeView.getSelectionModel().getSelectedItems();
            if (selectedItems.isEmpty()) return;

            // 筛选出顶层节点（排除子节点）
            List<TreeItem<Markdown>> topLevelItems = filterTopLevelItems(selectedItems);
            if (topLevelItems.isEmpty()) return;

            for (TreeItem<Markdown> item : topLevelItems) {
                File file = item.getValue().getFile();
                if (TabpaneUtil.findCustomMarkdownTab(file.toPath()) != null) {
                    if(file.isDirectory())
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            String.format(I18n.t("markdown.tree.error.folder_opened", "文件夹【%s】中有文件正在被打开，请关闭文件后重试!"), file.getName())
                    );
                    else
                        AlertUtil.CustomAlert(
                                I18n.t("common.error", "错误"),
                                String.format(I18n.t("markdown.tree.error.file_opened", "文件【%s】正在被打开，请关闭文件后重试!"), file.getName())
                        );
                    return;
                }
            }

            clipboardFiles = new ArrayList<>(topLevelItems);
            cutOperation = true;
        });

        // 粘贴
        pasteItem.setOnAction(e -> {
            selectedTreeItem=treeView.getSelectionModel().getSelectedItem();
            pasteFiles(selectedTreeItem);
        });



        renameItem.setOnAction(e -> {
            selectedTreeItem=treeView.getSelectionModel().getSelectedItem();
            File file = selectedTreeItem.getValue().getFile();
            HBox hbox = new HBox();
            hbox.getChildren().add(new Label(I18n.t("markdown.tree.rename.input_label", "请输入重命名名称  ")));
            hbox.setAlignment(Pos.CENTER_LEFT);
            CustomUserTextField textField = new CustomUserTextField();
            textField.setPrefWidth(200);
            textField.setText(selectedTreeItem.getValue().getName());
            textField.positionCaret(textField.getText().length());
            hbox.getChildren().add(textField);

            ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
            ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
            AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                    I18n.t("markdown.tree.rename.title", "重命名"),
                    hbox,
                    430,
                    180,
                    buttonTypeOk,
                    buttonTypeCancel
            );
            Button button = dialog.getButton(buttonTypeOk);
            button.requestFocus();
            textField.requestFocus();
            ButtonType result = dialog.showAndWait();
            String newName=textField.getText();
            if (result == buttonTypeOk) {
                if (newName.equals(file.getName()) || newName.isBlank()) return;
                try {
                    Path newPath = file.toPath().resolveSibling(newName);
                    if(file.isDirectory()){
                        if(TabpaneUtil.findCustomMarkdownTab(file.toPath())!=null){
                            AlertUtil.CustomAlert(
                                    I18n.t("common.error", "错误"),
                                    String.format(I18n.t("markdown.tree.error.folder_opened", "文件夹【%s】中有文件正在被打开，请关闭文件后重试!"), file.getName())
                            );
                            return;
                        }
                    }
                    if (Files.exists(newPath)) {
                        AlertUtil.CustomAlert(I18n.t("common.error", "错误"), I18n.t("markdown.tree.error.same_name_exists", "已存在同名文件/文件夹!"));
                        return;
                    }
                    Files.move(file.toPath(), newPath);
                    selectedTreeItem.getValue().setFile(newPath.toFile());
                    selectedTreeItem.getValue().setName(newPath.toFile().getName());
                    if(newPath.toFile().isDirectory())
                    refreshNode(selectedTreeItem);
                    TabpaneUtil.renameCustomMarkdownTab(file.toPath(),newPath);

                } catch (IOException ex) {
                    log.error("Operation failed", ex);
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"),ex.getMessage());
                }
            }
        });

        newFileItem.setOnAction(e ->{
            selectedTreeItem=treeView.getSelectionModel().getSelectedItem();
            createNewFile(selectedTreeItem, false);
        } );
        newFolderItem.setOnAction(e -> {
            selectedTreeItem=treeView.getSelectionModel().getSelectedItem();
            createNewFile(selectedTreeItem, true);
        });
        newRootFolderItem.setOnAction(e -> {
            selectedTreeItem=treeView.getSelectionModel().getSelectedItem();
            createNewFile(selectedTreeItem, true);
        });
        deleteItem.setOnAction(e -> {
            ObservableList<TreeItem<Markdown>> selectedItems = treeView.getSelectionModel().getSelectedItems();
            if (selectedItems.isEmpty()) return;

            // 筛选出顶层节点（排除子节点）
            List<TreeItem<Markdown>> topLevelItems = filterTopLevelItems(selectedItems);
            if (topLevelItems.isEmpty()) return;

            // 确认删除对话框
            String msg = topLevelItems.size() > 1
                    ? String.format(I18n.t("markdown.tree.delete.confirm_multi", "确定要删除选中的 %d 个项目（含其子内容）吗？"), topLevelItems.size())
                    : String.format(I18n.t("markdown.tree.delete.confirm_single", "确定要删除【%s】（含其子内容）吗？"), topLevelItems.get(0).getValue().getName());

            if (AlertUtil.CustomAlertConfirm(I18n.t("markdown.tree.delete.title", "删除文件"), msg)) {
                try {
                    Set<TreeItem<Markdown>> parentNodes = new HashSet<>();

                    for (TreeItem<Markdown> item : topLevelItems) {
                        File file = item.getValue().getFile();
                        if(file.isDirectory()&&TabpaneUtil.findCustomMarkdownTab(file.toPath())!=null){
                            AlertUtil.CustomAlert(
                                    I18n.t("common.error", "错误"),
                                    String.format(I18n.t("markdown.tree.error.folder_opened", "文件夹【%s】中有文件正在被打开，请关闭文件后重试!"), file.getName())
                            );
                            return;
                        }
                        deleteRecursively(file.toPath()); // 物理删除（包含子内容）
                        parentNodes.add(item.getParent());
                        item.getParent().getChildren().remove(item); // 从树中移除
                    }

                    // 刷新父节点
                    parentNodes.forEach(MarkdownUtil::refreshNode);

                    // 选中第一个父节点保持焦点
                    if (!parentNodes.isEmpty()) {
                        TreeItem<Markdown> parent = parentNodes.iterator().next();
                        treeView.getSelectionModel().clearSelection();
                        /*
                        if(parent.equals(treeView.getRoot())){
                            treeView.getSelectionModel().select(parent.getChildren().get(0));
                        }else{
                            treeView.getSelectionModel().select(parent);
                        }

                         */
                        treeView.getSelectionModel().select(parent);
                    }

                } catch (IOException e1) {
                    log.error("Operation failed", e1);
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e1.getMessage());
                }
            }
        });
        refreshItem.setOnAction(e -> {
            selectedTreeItem=treeView.getSelectionModel().getSelectedItem();
            //File file = selectedTreeItem.getValue().getFile();
            refreshNode(selectedTreeItem);
        });
        contextMenu.getItems().addAll(copyItem, cutItem,renameItem);

        treeView.setContextMenu(contextMenu);
        contextMenu.setOnShowing(event -> {
            // 获取所有选中的节点
            ObservableList<TreeItem<Markdown>> selectedItems = treeView.getSelectionModel().getSelectedItems();
            if (selectedItems.isEmpty()) {
                contextMenu.getItems().clear();
                contextMenu.getItems().addAll(newFileItem,newFolderItem,pasteItem,refreshItem);
                return;
            }

            // 判断是否为多选（选中数量 > 1）
            boolean isMultiSelect = selectedItems.size() > 1;

            // 清空菜单
            contextMenu.getItems().clear();

            if (isMultiSelect) {
                // 多选场景：只保留复制、剪切、删除
                contextMenu.getItems().addAll(copyItem, cutItem, deleteItem);
            } else {
                // 单选场景：根据节点类型显示完整菜单
                TreeItem<Markdown> singleItem = selectedItems.get(0);
                boolean isDirectory = singleItem.getValue().getFile().isDirectory();

                // 基础选项：复制、剪切、重命名、删除
                List<MenuItem> baseItems = new ArrayList<>(Arrays.asList(copyItem, cutItem, renameItem, deleteItem));

                if (isDirectory) {
                    // 单选文件夹：添加粘贴和新建类选项
                    baseItems.add(0,newFileItem);
                    baseItems.add(1,newFolderItem);
                    baseItems.add(4, pasteItem); // 在删除前插入粘贴'
                    baseItems.add(5,refreshItem);
                    //baseItems.addAll(Arrays.asList(newFileItem, newFolderItem, refreshItem));
                }

                contextMenu.getItems().addAll(baseItems);
            }
        });


        /*
        if (file.isDirectory()) {
            return new CustomContextMenu(copyItem, cutItem, pasteItem, renameItem, newFileItem, newFolderItem, deleteItem, refreshItem);
        } else {
            return new CustomContextMenu(copyItem, cutItem, renameItem, deleteItem);
        }

         */
    }
    
    /**
     * 从选中的节点列表中筛选出顶层节点（排除被其他选中节点包含的子节点）
     */
    public static List<TreeItem<Markdown>> filterTopLevelItems(List<TreeItem<Markdown>> selectedItems) {
        // 先按路径长度排序（短路径可能是父节点）
        List<TreeItem<Markdown>> sorted = new ArrayList<>(selectedItems);
        sorted.sort((a, b) -> {
            int lenA = a.getValue().getFile().toPath().getNameCount();
            int lenB = b.getValue().getFile().toPath().getNameCount();
            return Integer.compare(lenA, lenB); // 短路径在前
        });

        Set<TreeItem<Markdown>> topLevel = new HashSet<>();
        for (TreeItem<Markdown> item : sorted) {
            Path itemPath = item.getValue().getFile().toPath();
            boolean isChild = false;
            // 只需要判断已加入的顶层节点是否为当前节点的父节点
            for (TreeItem<Markdown> existing : topLevel) {
                Path existingPath = existing.getValue().getFile().toPath();
                if (isPathContained(existingPath, itemPath)) {
                    isChild = true;
                    break;
                }
            }
            if (!isChild) {
                topLevel.add(item);
            }
        }
        return new ArrayList<>(topLevel);
    }


    public static void copyFiles(ObservableList<TreeItem<Markdown>> selectedItems){
        //ObservableList<TreeItem<Markdown>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) return;

        // 筛选出顶层节点（排除子节点）
        List<TreeItem<Markdown>> topLevelItems = filterTopLevelItems(selectedItems);
        if (topLevelItems.isEmpty()) return;

        // 保存所有选中的节点到程序内部剪贴板
        clipboardFiles = new ArrayList<>(topLevelItems); // 新增一个List<TreeItem<Markdown>>变量存储多选节点
        cutOperation = false;

        // 同时复制到系统剪贴板（支持文件粘贴到系统其他地方）
        List<File> files = topLevelItems.stream()
                .map(item -> item.getValue().getFile())
                .collect(Collectors.toList());
        //ClipboardContent content = new ClipboardContent();
        //content.putFiles(files);
        //Clipboard.getSystemClipboard().setContent(content);
        copyFilesToClipboard(files);
    }

    public static void copyFilesToClipboard(List<File> files) {
        java.awt.datatransfer.Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

        Transferable transferable = new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{ DataFlavor.javaFileListFlavor };
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.javaFileListFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                return files;
            }
        };

        clipboard.setContents(transferable, null);
    }

    public static void pasteFiles(TreeItem<Markdown> targetItem){
        //ObservableList<TreeItem<Markdown>> targetItems = treeView.getSelectionModel().getSelectedItems();
        //if (targetItem==null) return; // 目标目录不能为空
        if(targetItem==null) targetItem=treeView.getRoot();

        // 目标只能是一个文件夹（取第一个选中的文件夹）
        File targetDirFile = targetItem.getValue().getFile();
        if (!targetDirFile.isDirectory()) {
            //AlertUtil.CustomAlert("错误", "目标必须是文件夹");
            return;
        }
        Path targetDirPath = targetDirFile.toPath();

        // 1. 收集待粘贴的文件列表（优先内部剪贴板，其次系统剪贴板）
        List<File> filesToPaste = new ArrayList<>();

        // 检查程序内部剪贴板（剪切/复制的文件）
        if (clipboardFiles != null && !clipboardFiles.isEmpty()) {
            filesToPaste.addAll(clipboardFiles.stream()
                    .map(item -> item.getValue().getFile())
                    .collect(Collectors.toList()));
        }
        // 检查系统剪贴板（外部文件）
        else {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (clipboard.hasFiles()) {
                filesToPaste.addAll(clipboard.getFiles());
            } else {
                AlertUtil.CustomAlert(I18n.t("common.tip", "提示"), I18n.t("markdown.tree.paste.empty_clipboard", "剪贴板中没有可粘贴的文件！"));
                return;
            }
        }

        try {
            for (File srcFile : filesToPaste) {
                Path srcPath = srcFile.toPath();
                Path targetPath = targetDirPath.resolve(srcFile.getName());

                // 检测1：目标是否已存在同名文件/文件夹
                if (Files.exists(targetPath)) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            String.format(I18n.t("markdown.tree.error.target_exists", "目标已存在同名文件/文件夹【%s】"), srcFile.getName())
                    );

                    return;
                }

                // 检测2：若源是文件夹，判断目标是否是源的子目录（避免循环）
                if (srcFile.isDirectory() && isPathContained(srcPath, targetPath)) {
                    AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            String.format(I18n.t("markdown.tree.error.paste_into_child", "不能将文件夹【%s】粘贴到其自身的子目录中！"), srcFile.getName())
                    );
                        return;
                }

                // 执行操作（剪切/复制）
                if (clipboardFiles != null && cutOperation) { // 内部剪切操作
                    Files.move(srcPath, targetPath);
                    // 从原位置移除节点（仅内部文件需要）
                    clipboardFiles.stream()
                            .filter(item -> item.getValue().getFile().equals(srcFile))
                            .findFirst()
                            .ifPresent(item -> item.getParent().getChildren().remove(item));
                } else { // 复制操作（内部复制或外部文件粘贴）
                    if (srcFile.isDirectory()) {
                        copyDirectory(srcPath, targetPath);
                    } else {
                        Files.copy(srcPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            // 刷新目标文件夹节点
            refreshNode(targetItem);
            // 清空内部剪贴板状态
            if (clipboardFiles != null) {
                clipboardFiles = null;
                cutOperation = false;
            }

        } catch (IOException ex) {
            log.error("Operation failed", ex);
            AlertUtil.CustomAlert(I18n.t("markdown.tree.paste.error_title", "粘贴错误"), ex.getMessage());
        }
    }

    public static void createNewFile(TreeItem<Markdown> treeItem, boolean isFolder) {
        if(treeItem==null) treeItem=treeView.getRoot();
        File parent=treeItem.getValue().getFile();
        HBox hbox = new HBox();
        hbox.getChildren().add(new Label(
                String.format(I18n.t("markdown.tree.new.input_label", "请输入%s名称  "), isFolder ? I18n.t("markdown.tree.new.folder", "文件夹") : I18n.t("markdown.tree.new.file", "文件"))
        ));
        hbox.setAlignment(Pos.CENTER_LEFT);
        CustomUserTextField textField = new CustomUserTextField();
        textField.setPrefWidth(200);
        textField.setText(isFolder ? I18n.t("markdown.tree.new.default_folder", "新建文件夹") : I18n.t("markdown.tree.new.default_file", "新建markdown文档.md"));
        textField.positionCaret(textField.getText().length());
        hbox.getChildren().add(textField);

        ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
                isFolder ? I18n.t("markdown.tree.new_folder.title", "新建文件夹") : I18n.t("markdown.tree.new_file.title", "新建文件"),
                hbox,
                460,
                180,
                buttonTypeOk,
                buttonTypeCancel
        );
        Button button = dialog.getButton(buttonTypeOk);
        button.requestFocus();
        textField.requestFocus();
        ButtonType result = dialog.showAndWait();
        if (result == buttonTypeOk) {
            try {
                Path newPath = parent.toPath().resolve(textField.getText());
                if (Files.exists(newPath)) {
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"),I18n.t("markdown.tree.error.file_or_folder_exists", "文件/文件夹已存在！"));
                    return;
                }
                if (isFolder) Files.createDirectory(newPath);
                else Files.createFile(newPath);

                refreshNode(treeItem);
                for(TreeItem<Markdown> item : treeItem.getChildren()){
                    if(item.getValue().getFile().getAbsolutePath().equals(newPath.toString())) {
                        treeView.getSelectionModel().clearSelection();
                        treeView.getSelectionModel().select(item);
                        break;
                    }
                }
                if(!isFolder){
                    TabpaneUtil.addCustomMarkdownTab(treeView.getSelectionModel().getSelectedItem().getValue().getFile(),true);
                }
            } catch (IOException e) {
                log.error("Operation failed", e);
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),e.getMessage());
            }
        }
    }

    /**
     * 判断目标路径是否是源路径的子目录（避免循环操作）
     */
    public static boolean isPathContained(Path source, Path target) {
        try {
            // 标准化源路径：存在则用 toRealPath()，不存在则用绝对路径标准化
            Path normalizedSource = normalizePath(source);
            // 标准化目标路径：同上
            Path normalizedTarget = normalizePath(target);

            // 检查根目录是否一致（跨文件系统直接返回 false）
            if (!Objects.equals(normalizedSource.getRoot(), normalizedTarget.getRoot())) {
                return false;
            }

            // 检查目标路径是否以源路径为前缀（包含关系）
            return normalizedTarget.startsWith(normalizedSource);

        } catch (IOException e) {
            // 其他 IO 异常（如权限不足），默认返回 false
            return false;
        }
    }

    /**
     * 标准化路径：存在则用 toRealPath()，不存在则用 absolute().normalize()
     */
    private static Path normalizePath(Path path) throws IOException {
        if (Files.exists(path)) {
            // 路径存在：解析为真实路径（处理符号链接、相对路径等）
            return path.toRealPath();
        } else {
            // 路径不存在：转换为绝对路径并标准化（处理 ./ 和 ../）
            return path.toAbsolutePath().normalize();
        }
    }
    private static void deleteFile(TreeItem<Markdown> treeItem) {
        if(treeItem.getParent().equals(treeView.getRoot())&&treeView.getRoot().getChildren().size()==1){
            AlertUtil.CustomAlert(I18n.t("common.error", "错误"),I18n.t("markdown.tree.error.last_root_folder", "当前只有一个文件夹，不允许删除！"));
            return;
        }
        File file=treeItem.getValue().getFile();
        String deleteTarget = file.isFile()
                ? String.format(I18n.t("markdown.tree.delete.file_name", "文件【%s】"), file.getName())
                : String.format(I18n.t("markdown.tree.delete.folder_name", "文件夹【%s】"), file.getName());
        if (AlertUtil.CustomAlertConfirm(I18n.t("markdown.tree.delete.title", "删除文件"), String.format(I18n.t("markdown.tree.delete.confirm_target", "确定要删除%s吗？"), deleteTarget))) {
            try {
                deleteRecursively(file.toPath());
                if(treeItem.getParent().equals(treeView.getRoot())){
                    treeView.getSelectionModel().select(treeView.getRoot().getChildren().get(0));
                }else{
                    treeView.getSelectionModel().select(treeItem.getParent());
                }
                treeItem.getParent().getChildren().remove(treeItem);
            } catch (IOException e) {
                log.error("Operation failed", e);
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),e.getMessage());
            }
        }
    }

    public static void refreshNode(TreeItem<Markdown> node) {
        if(node == null) node=treeView.getRoot();
        node.getChildren().clear();
        buildChildren(node);

    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;

        Files.walkFileTree(path, new SimpleFileVisitor<>() {

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    Files.deleteIfExists(file);
                    TabpaneUtil.removeCustomMarkdownTab(path);
                } catch (AccessDeniedException e) {
                    // 尝试解除只读或延迟重试
                    File f = file.toFile();
                    if (!f.canWrite()) f.setWritable(true);
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                try {
                    Files.deleteIfExists(dir);
                } catch (AccessDeniedException e) {
                    // 防御性重试
                    try { Thread.sleep(100); } catch (InterruptedException e1) { Thread.currentThread().interrupt(); }
                    Files.deleteIfExists(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }


    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(src -> {
            try {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) Files.createDirectories(dest);
                else Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("Operation failed", e);
                AlertUtil.CustomAlert(I18n.t("common.error", "错误"),e.getMessage());
            }
        });
    }


}
