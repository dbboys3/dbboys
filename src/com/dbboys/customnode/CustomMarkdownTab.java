package com.dbboys.customnode;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.util.AlterUtil;
import com.dbboys.util.TabpaneUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fxmisc.flowless.VirtualizedScrollPane;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class CustomMarkdownTab extends CustomTab{
    private static final Logger log = LogManager.getLogger(CustomMarkdownTab.class);

    private final CustomMarkdownEditCodeArea customMarkdownEditCodeArea;
    private final CustomGenericStyledArea customGenericStyledArea;
    private final CustomSearchReplaceVbox searchReplaceBox = new CustomSearchReplaceVbox(null);

    //sql编辑框以上控件
    public CustomMarkdownTab(File file,boolean modifiable) {
        super(file.getName());
        filePath = file.getAbsolutePath();
        setTooltip(new Tooltip(filePath.isBlank() ? I18n.t("markdown.tab.unsaved_path_tip", "新建脚本未保存到磁盘") : filePath));




        customGenericStyledArea = new CustomGenericStyledArea(file);

        CustomInfoStackPane markdown = new CustomInfoStackPane(customGenericStyledArea);
        customMarkdownEditCodeArea = new CustomMarkdownEditCodeArea();
        VirtualizedScrollPane virtualizedScrollPane = new VirtualizedScrollPane(customMarkdownEditCodeArea);
        markdown.getChildren().add(0,virtualizedScrollPane);
        virtualizedScrollPane.visibleProperty().bind(customGenericStyledArea.getParent().visibleProperty().not());
        Button editButton = new Button("");
        //customMarkdownEditCodeArea.setStyle("-fx-font-family: system;");


        // 统一搜索面板（编辑可替换，预览仅查找）
        searchReplaceBox.setMaxWidth(300);
        searchReplaceBox.setMaxHeight(26);
        searchReplaceBox.setCodeArea(customMarkdownEditCodeArea);
        searchReplaceBox.setReplaceEnabled(true);
        StackPane.setAlignment(searchReplaceBox,Pos.TOP_RIGHT);
        markdown.getChildren().add(2,searchReplaceBox);
        customMarkdownEditCodeArea.setOnSaveRequest(this::requestSave);
        customMarkdownEditCodeArea.setOnContentDirty(this::markDirty);
        customMarkdownEditCodeArea.setOnSearchRequest(() -> {
            searchReplaceBox.setCodeArea(customMarkdownEditCodeArea);
            searchReplaceBox.setReplaceEnabled(true);
            searchReplaceBox.showFindPanel();
        });
        customMarkdownEditCodeArea.setOnReplaceRequest(() -> {
            searchReplaceBox.setCodeArea(customMarkdownEditCodeArea);
            searchReplaceBox.setReplaceEnabled(true);
            searchReplaceBox.showReplacePanel();
        });
        customMarkdownEditCodeArea.setSaveDisabledSupplier(() -> !isDirty());
        StackPane.setMargin(searchReplaceBox, new Insets(2, 17, 0, 0));

        customGenericStyledArea.setOnSearchRequest(() -> {
            searchReplaceBox.setCodeArea(customGenericStyledArea);
            searchReplaceBox.setReplaceEnabled(false);
            searchReplaceBox.setMaxWidth(280);
            searchReplaceBox.showFindPanel();
        });

        editButton.setGraphic(IconFactory.group(IconPaths.RESULTSET_EDITABLE, 0.6));
        editButton.setFocusTraversable(false);
        editButton.getStyleClass().add("codearea-camera-button");
        Tooltip editTooltip = new Tooltip();
        editTooltip.textProperty().bind(I18n.bind("markdown.tab.edit_tooltip", "编辑"));
        editButton.setTooltip(editTooltip);
        markdown.getChildren().add(editButton);
        StackPane.setMargin(editButton, new Insets(0, 15, 15, 0));
        StackPane.setAlignment(editButton, Pos.BOTTOM_RIGHT);
        markdown.codeAreaSnapshotButton.visibleProperty().bind(editButton.visibleProperty());

                /*
                Tooltip modifytooltip = new Tooltip();
                modifytooltip.setText("编辑");
                modifyBtn.setTooltip(modifytooltip);

                 */

        Button previewButton = new Button("");
        previewButton.setGraphic(IconFactory.group(IconPaths.MARKDOWN_SAVE_PREVIEW, 0.5));
        previewButton.setFocusTraversable(false);
        previewButton.getStyleClass().add("codearea-camera-button");
        Tooltip previewTooltip = new Tooltip();
        previewTooltip.textProperty().bind(I18n.bind("markdown.tab.preview_tooltip", "预览"));
        previewButton.setTooltip(previewTooltip);


        customGenericStyledArea.modifyItem.setOnAction(event -> {
            editButton.fire();
        });


        customMarkdownEditCodeArea.viewItem.setOnAction(event -> {
            previewButton.fire();
        });
        markdown.getChildren().add(previewButton);
        StackPane.setMargin(previewButton, new Insets(0, 15, 15, 0));
        StackPane.setAlignment(previewButton, Pos.BOTTOM_RIGHT);
        previewButton.visibleProperty().bind(editButton.visibleProperty().not());
        searchReplaceBox.toFront();


        editButton.setOnAction(event -> switchToEditMode(editButton));

        previewButton.setOnAction(event -> {
            if (hasUnsavedChanges()) {
                requestSave();
                if (hasUnsavedChanges()) {
                    return;
                }
            }
            switchToPreviewMode(editButton);
            renderPreview(readCurrentMarkdownContent());
        });
        String content = readCurrentMarkdownContent();
        customMarkdownEditCodeArea.replaceTextWithoutDirty(content);
        customMarkdownEditCodeArea.showParagraphAtTop(0);
        renderPreview(content);
        setContent(markdown);

        //关闭窗口事件响应
        setOnCloseRequest(event1 -> {
            /*避免关闭后双击无响应*/
            if(AppState.getSqlTabPane().getTabs().size()==1){
                AppState.getSqlTabPane().setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY &&event.getClickCount() == 2) {
                        TabpaneUtil.addCustomSqlTab(null);
                    }
                });
            }

            if (hasUnsavedChanges()) {
                boolean confirmClose = AlterUtil.CustomAlertConfirm(
                        I18n.t("markdown.tab.close_title", "关闭文件"),
                        String.format(I18n.t("markdown.tab.close_confirm", "文件【%s】未保存，确定要关闭吗？"), getTitle().replace("*", ""))
                );
                if (!confirmClose) { // 若用户取消，则阻止关闭
                    event1.consume();
                }
            }

        });
        setOnClosed(event -> markdown.dispose());

        if(modifiable){
            editButton.fire();
            customMarkdownEditCodeArea.requestFocus();
        }

        customMarkdownEditCodeArea.codeAreaPasteItem.setOnAction(event -> {
            Clipboard fxClipboard = Clipboard.getSystemClipboard();

            try {
                // 关键：只要剪贴板里有“文字语义”，就不要当图片
                boolean hasText =
                        fxClipboard.hasString()
                                || fxClipboard.hasHtml();

                // 只有“纯图片”才处理为截图
                if (!hasText && handleAwtImagePaste()) {
                    return;
                }

                // 系统复制的图片文件（不影响 Word）
                if (handleFilePaste()) {
                    return;
                }

                // URL 图片兜底
                if (handleUrlImagePaste()) {
                    return;
                }

                // 兜底：普通粘贴（Word / 文本 / HTML）
                customMarkdownEditCodeArea.paste();

            } catch (Exception e) {
                log.error(e.getMessage(), e);
                customMarkdownEditCodeArea.paste();
            }
        });


    }

    private static int getNextIndex(File folder) {
        int maxIndex = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("img")) {
                    String numPart = name.substring(3); // 去掉 "img"
                    int dotIndex = numPart.lastIndexOf('.');
                    if (dotIndex > 0) {
                        numPart = numPart.substring(0, dotIndex); // 去掉扩展名
                    }
                    try {
                        int num = Integer.parseInt(numPart);
                        if (num > maxIndex) maxIndex = num;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return maxIndex + 1;
    }


    private boolean handleAwtImagePaste() throws Exception {
        java.awt.datatransfer.Clipboard awtClipboard =
                Toolkit.getDefaultToolkit().getSystemClipboard();

        if (!awtClipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            return false;
        }

        java.awt.Image awtImage = (java.awt.Image) awtClipboard.getData(DataFlavor.imageFlavor);
        if (awtImage == null) return false;

        BufferedImage bufferedImage = new BufferedImage(
                awtImage.getWidth(null),
                awtImage.getHeight(null),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = bufferedImage.createGraphics();
        g.drawImage(awtImage, 0, 0, null);
        g.dispose();

        File targetFile = createNextImageFile("png");
        ImageIO.write(bufferedImage, "png", targetFile);
        insertMarkdownImage(targetFile.getName());
        return true;
    }

    private boolean handleFilePaste() throws IOException {
        Clipboard clipboard = Clipboard.getSystemClipboard();

        if (!clipboard.hasFiles()) return false;

        java.util.List<File> files = clipboard.getFiles();
        if (files.size() != 1) return false;

        File src = files.get(0);
        if (!isImageFile(src)) return false;

        File target = createNextImageFile(getExt(src.getName()));
        Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        insertMarkdownImage(target.getName());
        return true;
    }

    private boolean handleUrlImagePaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasUrl()) return false;

        try {
            URI uri = new URI(clipboard.getUrl());
            if (!"file".equalsIgnoreCase(uri.getScheme())) return false;

            File file = new File(uri);
            if (!file.exists() || !isImageFile(file)) return false;

            File target = createNextImageFile(getExt(file.getName()));
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            insertMarkdownImage(target.getName());
            return true;

        } catch (Exception e) {
            return false;
        }
    }
    private File createNextImageFile(String ext) {
        File imgFolder = new File(new File(filePath).getParent(), "img");
        if (!imgFolder.exists()) imgFolder.mkdirs();

        String name = "img" + getNextIndex(imgFolder) + "." + ext;
        return new File(imgFolder, name);
    }

    private void insertMarkdownImage(String fileName) {
        IndexRange sel = customMarkdownEditCodeArea.getSelection();
        if(sel != null){
            customMarkdownEditCodeArea.replaceSelection(
                    "![" + fileName + "](img/" + fileName + ")"
            );
        }else{
            int pos = customMarkdownEditCodeArea.getCaretPosition();
            customMarkdownEditCodeArea.insertText(
                    pos,
                    "![" + fileName + "](img/" + fileName + ")"
            );
        }
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".gif")
                || name.endsWith(".bmp");
    }

    private String getExt(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private boolean hasUnsavedChanges() {
        return isDirty();
    }

    private String readCurrentMarkdownContent() {
        if (filePath.isBlank()) {
            return customMarkdownEditCodeArea.getText();
        }
        try {
            return Files.readString(Paths.get(filePath));
        } catch (IOException e) {
            showErrorAlert(e);
            return customMarkdownEditCodeArea.getText();
        }
    }

    private void renderPreview(String content) {
        customGenericStyledArea.clear();
        customGenericStyledArea.parseMarkdownWithStyles(content);
        customGenericStyledArea.showParagraphAtTop(0);
    }

    private void showErrorAlert(IOException e) {
        AlterUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
    }

    private void switchToEditMode(Button editButton) {
        customGenericStyledArea.getParent().setVisible(false);
        searchReplaceBox.setCodeArea(customMarkdownEditCodeArea);
        searchReplaceBox.setReplaceEnabled(true);
        searchReplaceBox.setMaxWidth(300);
        searchReplaceBox.toFront();
        editButton.setVisible(false);
        customMarkdownEditCodeArea.requestFocus();
    }

    private void switchToPreviewMode(Button editButton) {
        customGenericStyledArea.getParent().setVisible(true);
        searchReplaceBox.setCodeArea(customGenericStyledArea);
        searchReplaceBox.setReplaceEnabled(false);
        searchReplaceBox.setMaxWidth(280);
        searchReplaceBox.toFront();
        editButton.setVisible(true);
        customGenericStyledArea.requestFocus();
    }

    @Override
    public void requestSave() {
        String codeAreaText=customMarkdownEditCodeArea.getText();

        if (filePath.isBlank()) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.t("markdown.tab.save_dialog_title", "保存文件"));
            fileChooser.setInitialFileName(getTitle().replace("*", ""));
            File filename = fileChooser.showSaveDialog(AppState.getWindow());
            if (filename != null) { //用户选择了确认
                try (FileWriter writer = new FileWriter(filename)) {
                    writer.write(codeAreaText);
                    setTitle(filename.getName());
                    filePath = filename.getAbsolutePath();
                    markSaved();
                    setTooltip(new Tooltip(filePath));
                } catch (IOException e) {
                    showErrorAlert(e);
                }
            }
        }else{
            try {
                Files.writeString(Paths.get(filePath), codeAreaText);
                markSaved();
            } catch (IOException e) {
                showErrorAlert(e);
            }
        }
    }
}

