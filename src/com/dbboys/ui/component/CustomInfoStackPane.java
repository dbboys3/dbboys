package com.dbboys.ui.component;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.infra.util.SnapshotUtil;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import com.dbboys.ui.notification.NotificationUtil;
import javafx.scene.transform.Transform;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.GenericStyledArea;

import java.util.ArrayList;
import java.util.List;

public class CustomInfoStackPane extends StackPane {
    private static final Logger log = LogManager.getLogger(CustomInfoStackPane.class);
    private static final double SNAPSHOT_SCALE = 2.0;
    private static final double SNAPSHOT_BUTTON_ICON_SCALE = 0.35;
    private static final int NOTICE_MAX_WIDTH = 360;
    private static final int NOTICE_MAX_HEIGHT = 25;
    private static final int SNAPSHOT_BUTTON_MARGIN_TOP = 0;
    private static final int SNAPSHOT_BUTTON_MARGIN_RIGHT = 15;
    private static final int SNAPSHOT_BUTTON_MARGIN_BOTTOM = 20;
    private static final int SNAPSHOT_BUTTON_MARGIN_LEFT = 20;
    private static final int SCROLL_STEP_PX = 10;
    private static final int SCROLL_STEP_SLEEP_MS = 10;
    private static final int SCROLL_SETTLE_DELAY_MS = 150;
    private static final int PAGE_CAPTURE_DELAY_MS = 150;

    public final GenericStyledArea codeArea;
    public final VirtualizedScrollPane codeAreaScrollPane;
    public final Button codeAreaSnapshotButton = new Button();
    public final StackPane noticePane = new StackPane();
    private volatile boolean snapshotInProgress = false;
    private volatile boolean disposed = false;
    public boolean showNoticeInMain = true;

    @Deprecated public final VirtualizedScrollPane codearea_scollpane;
    @Deprecated public final Button codearea_snap_button;
    @Deprecated public final StackPane notice_pane;

    public CustomInfoStackPane(GenericStyledArea styledTextArea) {
        super();
        codeArea = styledTextArea;
        codeArea.setWrapText(true);
        codeAreaScrollPane = new VirtualizedScrollPane(codeArea);
        codearea_scollpane = codeAreaScrollPane;
        codearea_snap_button = codeAreaSnapshotButton;
        notice_pane = noticePane;
        getChildren().add(codeAreaScrollPane);

        noticePane.getStyleClass().add("notice-pane");
        noticePane.setMaxWidth(NOTICE_MAX_WIDTH);
        noticePane.setMaxHeight(NOTICE_MAX_HEIGHT);
        noticePane.setVisible(false);

        codeAreaSnapshotButton.setGraphic(IconFactory.group(IconPaths.MAIN_SNAPSHOT, SNAPSHOT_BUTTON_ICON_SCALE));
        codeAreaSnapshotButton.setFocusTraversable(false);
        codeAreaSnapshotButton.setId("codearea-camera-button");
        Tooltip snapshotTooltip = new Tooltip();
        snapshotTooltip.textProperty().bind(I18n.bind("main.tooltip.snapshot_to_clipboard"));
        codeAreaSnapshotButton.setTooltip(snapshotTooltip);

        getChildren().add(codeAreaSnapshotButton);
        setAlignment(codeAreaSnapshotButton, Pos.TOP_RIGHT);
        getChildren().add(noticePane);
        setAlignment(noticePane, Pos.CENTER);
        setMargin(codeAreaSnapshotButton, new javafx.geometry.Insets(
                SNAPSHOT_BUTTON_MARGIN_TOP,
                SNAPSHOT_BUTTON_MARGIN_RIGHT,
                SNAPSHOT_BUTTON_MARGIN_BOTTOM,
                SNAPSHOT_BUTTON_MARGIN_LEFT
        ));
        codeAreaSnapshotButton.setOnAction(e -> startSnapshot());

    }

    private void startSnapshot() {
        if (disposed) {
            return;
        }
        if (snapshotInProgress) {
            return;
        }
        snapshotInProgress = true;
        codeAreaSnapshotButton.setDisable(true);
        Task<Void> snapshotTask = createSnapshotTask();
        snapshotTask.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED, event -> {
            Throwable ex = snapshotTask.getException();
            if (ex != null) {
                log.error("Failed to create info snapshot.", ex);
            }
            finishSnapshot();
        });
        snapshotTask.addEventHandler(WorkerStateEvent.WORKER_STATE_CANCELLED, event -> finishSnapshot());
        try {
            AppExecutor.runTask(snapshotTask);
        } catch (RuntimeException ex) {
            log.error("Unable to schedule info snapshot task.", ex);
            finishSnapshot();
        }
    }

    private void finishSnapshot() {
        snapshotInProgress = false;
        codeAreaSnapshotButton.setDisable(false);
    }

    public Task<Void> createSnapshotTask() {
        final int estimatedTotalHeight = (int) codeArea.getTotalHeightEstimate();
        Task<Void> scrollTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (estimatedTotalHeight <= 0) {
                    return null;
                }
                for(int y = 0; y < estimatedTotalHeight; y += SCROLL_STEP_PX) {
                    int finalY = y;
                    Platform.runLater(() -> {
                        codeArea.scrollYToPixel(finalY);
                    });
                    Thread.sleep(SCROLL_STEP_SLEEP_MS);
                }

                Platform.runLater(() -> {
                    codeArea.scrollYToPixel(Double.MAX_VALUE);
                });
                Thread.sleep(SCROLL_SETTLE_DELAY_MS);
                Platform.runLater(() -> {
                    codeArea.scrollYToPixel(Double.MAX_VALUE);
                });
                Thread.sleep(SCROLL_SETTLE_DELAY_MS);
                Platform.runLater(() -> {  //似乎这一步是关键，需要执行才能正确获取高度
                    codeArea.scrollYToPixel(0);
                });
                Thread.sleep(SCROLL_SETTLE_DELAY_MS);

                return null;
            }

        };

        scrollTask.setOnSucceeded(event -> {
            captureFullCodeArea(codeArea, SNAPSHOT_SCALE, image -> {
                if (image == null) {
                    finishSnapshot();
                    return;
                }
                /*复制到剪切板，会占用很大内存，改为复制到文件
                try {
                    Clipboard clipboard = Clipboard.getSystemClipboard();
                    ClipboardContent content = new ClipboardContent();
                    content.putImage(image);
                    clipboard.setContent(content);
                    if(showNoticeInMain){
                        NotificationUtil.showNotification(Main.mainController.noticePane, "截图成功复制到剪切板！可在微信、word、画图等软件粘贴！");
                    }else{
                        NotificationUtil.showNotification(notice_pane, "截图成功复制到剪切板！");
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                */
                SnapshotUtil.copyToClipboard(image, showNoticeInMain ? AppState.getNoticePane() : noticePane);
                finishSnapshot();
            });
        });
        return scrollTask;

    }

    @Deprecated
    public Task<Void> createSnapshotInfoCodeAreaTask() {
        return createSnapshotTask();
    }

    private void captureFullCodeArea(GenericStyledArea codeArea, double scale, java.util.function.Consumer<WritableImage> callback) {
        Platform.runLater(() -> {
            int totalContentHeight = (int) codeArea.getTotalHeightEstimate();
            if (totalContentHeight <= 0 || codeArea.getHeight() <= 0) {
                callback.accept(null);
                return;
            }
            double viewportHeight = codeArea.getHeight();
            int pages = (int) Math.ceil(totalContentHeight / viewportHeight);

            List<WritableImage> images = new ArrayList<>();
            capturePages(codeArea, pages, totalContentHeight, scale, images, callback);
        });
    }

    private void capturePages(GenericStyledArea codeArea,
                              int totalPages, int totalContentHeight, double scale,
                              List<WritableImage> images,
                              java.util.function.Consumer<WritableImage> onFinish) {

        final int[] page = {0};
        final boolean[] readyToSnapshot = {false};
        PauseTransition delay = new PauseTransition(Duration.millis(PAGE_CAPTURE_DELAY_MS));
        delay.setOnFinished(e -> {
            if (page[0] >= totalPages) {
                WritableImage finalImage = mergeImagesUsingBottomOfLast(images, (int) (totalContentHeight * scale));
                onFinish.accept(finalImage);
                return;
            }

            if (!readyToSnapshot[0]) {
                double y = page[0] * codeArea.getHeight();
                codeArea.scrollYToPixel(y);
                readyToSnapshot[0] = true;
                delay.playFromStart();
                return;
            }

            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(Transform.scale(scale, scale));
            images.add(codeArea.snapshot(params, null));
            page[0]++;
            readyToSnapshot[0] = false;
            delay.playFromStart();
        });
        delay.play();
    }

    WritableImage mergeImagesUsingBottomOfLast(List<WritableImage> images, int totalContentHeight) {
        if (images == null || images.isEmpty()) return null;

        int width = (int) images.get(0).getWidth();
        int mergedHeight = 0;
        int imageCount = images.size();

        for (int i = 0; i < imageCount; i++) {
            WritableImage img = images.get(i);
            int height = (int) img.getHeight();

            // 最后一张只保留底部剩余部分，避免重叠
            if (i == imageCount - 1 && i > 0) {
                int remaining = totalContentHeight - mergedHeight;
                height = Math.min(height, remaining);
            }
            mergedHeight += Math.max(height, 0);
        }

        WritableImage result = new WritableImage(width, mergedHeight);
        PixelWriter writer = result.getPixelWriter();

        int yOffset = 0;
        for (int i = 0; i < imageCount; i++) {
            WritableImage img = images.get(i);
            int copyHeight = (int) img.getHeight();
            if (i == imageCount - 1 && i > 0) {
                int remaining = totalContentHeight - yOffset;
                copyHeight = Math.min(copyHeight, Math.max(remaining, 0));
                if (copyHeight < (int) img.getHeight()) {
                    img = cropBottom(img, copyHeight);
                }
            }
            if (copyHeight <= 0) {
                continue;
            }
            writer.setPixels(0, yOffset, (int) img.getWidth(), copyHeight, img.getPixelReader(), 0, 0);
            yOffset += copyHeight;
        }

        return result;
    }

    @Deprecated
    WritableImage mergeImagesUseBottomOfLast(List<WritableImage> images, int totalContentHeight) {
        return mergeImagesUsingBottomOfLast(images, totalContentHeight);
    }


    private WritableImage cropBottom(WritableImage src, int cropHeight) {
        int width = (int) src.getWidth();
        int srcHeight = (int) src.getHeight();
        cropHeight = Math.min(srcHeight, cropHeight);

        PixelReader reader = src.getPixelReader();
        WritableImage cropped = new WritableImage(width, cropHeight);
        cropped.getPixelWriter().setPixels(
                0, 0,                           // 目标图起点
                width, cropHeight,             // 裁剪宽高
                reader,
                0, srcHeight - cropHeight      // 从原图底部开始复制
        );
        return cropped;
    }

    public void dispose() {
        disposed = true;
        snapshotInProgress = false;
        codeAreaSnapshotButton.setDisable(true);
    }

}
