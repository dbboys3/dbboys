package com.dbboys.util;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.util.Duration;

import java.util.Map;
import java.util.WeakHashMap;

public class NotificationUtil {

    // 记录每个 noticePane 当前正在播放的动画
    private static final Map<StackPane, SequentialTransition> ACTIVE_ANIMATIONS = new WeakHashMap<>();

    public static void showMainNotification(String message) {
        showNotification(com.dbboys.app.AppState.getNoticePane(), message);
    }

    public static void showNotification(StackPane noticePane, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showNotification(noticePane, message));
            return;
        }
        if (noticePane == null) {
            return;
        }
        String text = message == null ? "" : message;

        // 1️⃣ 中断上一个通知
        SequentialTransition old = ACTIVE_ANIMATIONS.remove(noticePane);
        if (old != null) {
            old.stop();
        }

        Label label = new Label(text);
        label.setStyle(
                "-fx-background-color: rgba(70,70,70,0.9);" +
                        "-fx-text-fill: white;" +
                        "-fx-padding: 6px 9px;" +
                        "-fx-background-radius: 5;"
        );
        label.setFont(Font.font(10));
        boolean hasPrevious = old != null;
        label.setOpacity(hasPrevious ? 1 : 0);

        noticePane.getChildren().setAll(label);
        noticePane.setVisible(true);

        // 2️⃣ 动画
        Transition fadeInOrDirect;
        if (hasPrevious) {
            PauseTransition directShow = new PauseTransition(Duration.ZERO);
            fadeInOrDirect = directShow;
        } else {
            FadeTransition fadeIn = new FadeTransition(Duration.millis(300), label);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeInOrDirect = fadeIn;
        }
        PauseTransition stay = new PauseTransition(Duration.seconds(1));

        FadeTransition fadeOut = new FadeTransition(Duration.millis(300), label);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(
                fadeInOrDirect, stay, fadeOut
        );

        sequence.setOnFinished(e -> {
            if (ACTIVE_ANIMATIONS.get(noticePane) != sequence) {
                return;
            }
            noticePane.setVisible(false);
            ACTIVE_ANIMATIONS.remove(noticePane);
        });

        // 3️⃣ 记录并播放
        ACTIVE_ANIMATIONS.put(noticePane, sequence);
        sequence.play();
    }
}
