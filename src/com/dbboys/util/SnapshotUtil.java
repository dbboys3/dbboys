package com.dbboys.util;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Transform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;

public class SnapshotUtil {
    private static final Logger log = LogManager.getLogger(SnapshotUtil.class);
    private static final double SNAPSHOT_SCALE = 2.0;
    private static final double TABLE_HEADER_HEIGHT = 21.0;
    private static final double TABLE_FIXED_CELL_SIZE = 21.0;
    private static final String TEMP_FILE_PREFIX = "dbboys_screenshot_";
    private static final String TEMP_FILE_SUFFIX = ".png";

    private SnapshotUtil() {
    }

    public static void copyToClipboard(WritableImage image, StackPane noticePane) {
        try {
            File tempFile = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX).toFile();
            tempFile.deleteOnExit();

            BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
            ImageIO.write(bufferedImage, "png", tempFile);

            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new FileListTransferable(tempFile), null);

            NotificationUtil.showNotification(
                    noticePane,
                    I18n.t("snapshot.notice.copied", "截图已保存到临时文件并复制到剪切板")
            );
        } catch (IOException e) {
            log.error("Failed to write snapshot image.", e);
            NotificationUtil.showNotification(
                    AppState.getNoticePane(),
                    I18n.t("snapshot.error.write_failed", "截图写入临时文件失败：%s").formatted(e.getMessage())
            );
        }
    }

    public static void snapshotRoot() {
        snapshotSceneRoot();
    }

    public static void snapshotSceneRoot() {
        WritableImage image = new WritableImage(
                (int) (AppState.getSceneRoot().getBoundsInParent().getWidth() * SNAPSHOT_SCALE),
                (int) (AppState.getSceneRoot().getBoundsInParent().getHeight() * SNAPSHOT_SCALE)
        );
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(SNAPSHOT_SCALE, SNAPSHOT_SCALE));
        AppState.getSceneRoot().snapshot(params, image);
        copyToClipboard(image, AppState.getNoticePane());
    }

    public static void snapshotNode(Node node) {
        WritableImage image = new WritableImage(
                (int) (node.getBoundsInParent().getWidth() * SNAPSHOT_SCALE),
                (int) (node.getBoundsInParent().getHeight() * SNAPSHOT_SCALE)
        );
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(SNAPSHOT_SCALE, SNAPSHOT_SCALE));
        node.snapshot(params, image);
        copyToClipboard(image, AppState.getNoticePane());
    }

    public static void snapshotTableView(TableView<?> tableView) {
        tableView.setFixedCellSize(TABLE_FIXED_CELL_SIZE);
        int rowCount = tableView.getItems().size();

        double originalPrefHeight = tableView.getPrefHeight();
        double originalMinHeight = tableView.getMinHeight();
        double originalMaxHeight = tableView.getMaxHeight();

        double newHeight = TABLE_HEADER_HEIGHT + rowCount * tableView.getFixedCellSize();

        try {
            tableView.setPrefHeight(newHeight);
            tableView.setMinHeight(newHeight);
            tableView.setMaxHeight(newHeight);
            tableView.applyCss();
            tableView.layout();

            WritableImage image = new WritableImage(
                    (int) (tableView.getWidth() * SNAPSHOT_SCALE),
                    (int) (newHeight * SNAPSHOT_SCALE)
            );

            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(Transform.scale(SNAPSHOT_SCALE, SNAPSHOT_SCALE));
            tableView.snapshot(params, image);
            copyToClipboard(image, AppState.getNoticePane());
        } finally {
            tableView.setPrefHeight(originalPrefHeight);
            tableView.setMinHeight(originalMinHeight);
            tableView.setMaxHeight(originalMaxHeight);
        }
    }

    private static final class FileListTransferable implements Transferable {
        private final File file;

        private FileListTransferable(File file) {
            this.file = file;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (DataFlavor.javaFileListFlavor.equals(flavor)) {
                return Collections.singletonList(file);
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }
}
