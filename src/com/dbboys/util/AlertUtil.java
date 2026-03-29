package com.dbboys.util;

import com.dbboys.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class AlertUtil {
    private static final double DIALOG_WIDTH = 400;
    private static final double DIALOG_MIN_HEIGHT = 120;
    private static final double CONTENT_TOP = 10;
    private static final double CONTENT_RIGHT = 10;
    private static final double CONTENT_BOTTOM = 10;
    private static final double CONTENT_LEFT = 10;
    private static final Insets CONTENT_PADDING = new Insets(CONTENT_TOP, CONTENT_RIGHT, CONTENT_BOTTOM, CONTENT_LEFT);
    private static final String ALERT_TITLE_STYLE =
            "-fx-background-color: -color-bg-default;" +
            "-fx-padding: 0 0 0 6;" +
            "-fx-min-height: 28;" +
            "-fx-pref-height: 28;" +
            "-fx-alignment: center-left;";
    private static final String ALERT_BODY_STYLE =
            "-fx-background-color: -color-bg-default;" +
            "-fx-padding: 16 20 18 20;" +
            "-fx-spacing: 16;";

    private AlertUtil() {
    }

    public static void showAlert(String title, String message) {
        ButtonType confirmButton = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        showDialog(title, message, confirmButton);
    }

    public static boolean showConfirm(String title, String message) {
        ButtonType confirmButton = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        return showDialog(title, message, confirmButton, cancelButton) == confirmButton;
    }

    public static void showAlertI18n(String titleKey, String titleFallback, String messageKey, String messageFallback, Object... args) {
        String title = I18n.t(titleKey, titleFallback);
        String message = I18n.t(messageKey, messageFallback).formatted(args);
        showAlert(title, message);
    }

    public static boolean showConfirmI18n(String titleKey, String titleFallback, String messageKey, String messageFallback, Object... args) {
        String title = I18n.t(titleKey, titleFallback);
        String message = I18n.t(messageKey, messageFallback).formatted(args);
        return showConfirm(title, message);
    }

    public static void CustomAlert(String title, String alertText) {
        showAlert(title, alertText);
    }

    public static boolean CustomAlertConfirm(String title, String contentText) {
        return showConfirm(title, contentText);
    }

    private static ButtonType showDialog(String title, String message, ButtonType... buttonTypes) {
        Label label = new Label(message == null ? "" : message);
        label.setWrapText(true);
        double textWidth = getTextContentWidth(DIALOG_WIDTH);
        label.setPrefWidth(textWidth);
        label.setMaxWidth(textWidth);
        label.setStyle("-fx-text-fill: -color-fg-default;");
        return createContentDialog(title, label, DIALOG_WIDTH, Region.USE_COMPUTED_SIZE, buttonTypes).showAndWait();
    }

    public static ContentDialog createContentDialog(String title, Node content, ButtonType... buttonTypes) {
        return createContentDialog(title, content, DIALOG_WIDTH, Region.USE_COMPUTED_SIZE, buttonTypes);
    }

    public static ContentDialog createContentDialog(String title, Node content, double width, double height, ButtonType... buttonTypes) {
        boolean autoHeight = height <= 0 || height == Region.USE_COMPUTED_SIZE;
        Stage stage = new Stage();
        stage.setTitle(title == null ? "" : title);

        AtomicReference<ButtonType> resultRef = new AtomicReference<>();
        ButtonType defaultButtonType = findDefaultButton(buttonTypes);
        ButtonType cancelButtonType = findCancelButton(buttonTypes);

        HBox buttonBar = new HBox(10);
        HBox leftButtons = new HBox(10);
        HBox rightButtons = new HBox(10);
        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        buttonBar.getChildren().addAll(leftButtons, buttonSpacer, rightButtons);
        Map<ButtonType, Button> buttonMap = new LinkedHashMap<>();
        for (ButtonType buttonType : buttonTypes) {
            Button button = new Button(buttonType.getText());
            button.setFocusTraversable(true);
            button.setDefaultButton(buttonType == defaultButtonType);
            button.setCancelButton(buttonType == cancelButtonType);
            button.setOnAction(event -> {
                resultRef.set(buttonType);
                stage.close();
            });
            buttonMap.put(buttonType, button);
            if (isLeftAlignedButton(buttonType)) {
                leftButtons.getChildren().add(button);
            } else {
                rightButtons.getChildren().add(button);
            }
        }

        VBox body = new VBox(content, buttonBar);
        body.setPadding(CONTENT_PADDING);
        body.setStyle(ALERT_BODY_STYLE);
        body.setFillWidth(true);
        VBox.setVgrow(content, Priority.ALWAYS);
        prepareContent(content, width, autoHeight);

        CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.createModalPopup(
                stage,
                stage.titleProperty(),
                body,
                width,
                autoHeight ? DIALOG_MIN_HEIGHT : height,
                false,
                null
        );

        frame.root.setMinWidth(width);
        frame.root.setStyle(
                "-fx-background-color: -color-bg-default;" +
                "-fx-border-color: -color-fg-default;" +
                "-fx-border-width: 0.5;" +
                "-fx-padding: 0;"
        );
        if (autoHeight) {
            body.setMinHeight(Region.USE_PREF_SIZE);
            body.setPrefHeight(Region.USE_COMPUTED_SIZE);
            body.setMaxHeight(Region.USE_PREF_SIZE);
            frame.root.setMinHeight(Region.USE_PREF_SIZE);
            frame.root.setPrefHeight(Region.USE_COMPUTED_SIZE);
            frame.root.setMaxHeight(Region.USE_PREF_SIZE);
        }

        frame.titleBar.setStyle(ALERT_TITLE_STYLE);
        frame.closeButton.setOnAction(event -> {
            resultRef.set(cancelButtonType != null ? cancelButtonType : defaultButtonType);
            stage.close();
        });
        frame.scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                resultRef.set(cancelButtonType != null ? cancelButtonType : defaultButtonType);
                stage.close();
            } else if (event.getCode() == KeyCode.ENTER) {
                resultRef.set(defaultButtonType);
                stage.close();
            }
        });
        stage.setOnShown(event -> {
            if (autoHeight) {
                resizeStageToContent(stage, frame, body);
            } else {
                layoutFixedHeightContent(stage, frame, body, content, buttonBar);
            }
            centerStageToOwner(stage);
            Button defaultButton = buttonMap.get(defaultButtonType);
            if (defaultButton != null) {
                defaultButton.requestFocus();
            }
        });

        return new ContentDialog(stage, frame, resultRef, buttonMap, defaultButtonType, cancelButtonType);
    }

    private static boolean isLeftAlignedButton(ButtonType buttonType) {
        return buttonType != null && buttonType.getButtonData() == ButtonBar.ButtonData.LEFT;
    }

    private static ButtonType findDefaultButton(ButtonType[] buttonTypes) {
        for (ButtonType buttonType : buttonTypes) {
            if (buttonType.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                return buttonType;
            }
        }
        return buttonTypes.length > 0 ? buttonTypes[0] : null;
    }

    private static ButtonType findCancelButton(ButtonType[] buttonTypes) {
        for (ButtonType buttonType : buttonTypes) {
            if (buttonType.getButtonData().isCancelButton()) {
                return buttonType;
            }
        }
        return null;
    }

    private static void prepareContent(Node content, double width, boolean autoHeight) {
        if (content instanceof Label label) {
            double textWidth = getTextContentWidth(width);
            label.setWrapText(true);
            label.setPrefWidth(textWidth);
            label.setMaxWidth(textWidth);
            if (autoHeight) {
                label.setMinHeight(Region.USE_PREF_SIZE);
                label.setPrefHeight(Region.USE_COMPUTED_SIZE);
                label.setMaxHeight(Region.USE_PREF_SIZE);
            } else {
                label.setMaxHeight(Double.MAX_VALUE);
            }
            return;
        }

        if (content instanceof Region region) {
            double contentWidth = getRegionContentWidth(width);
            region.setMinHeight(autoHeight ? Region.USE_PREF_SIZE : 0);
            region.setPrefHeight(autoHeight ? Region.USE_COMPUTED_SIZE : Region.USE_PREF_SIZE);
            region.setMaxHeight(Double.MAX_VALUE);
            region.setPrefWidth(contentWidth);
            region.setMaxWidth(contentWidth);
        }
    }

    private static void resizeStageToContent(Stage stage, CustomWindowFrameUtil.Frame frame, VBox body) {
        Platform.runLater(() -> {
            double rootWidth = Math.max(DIALOG_WIDTH, stage.getWidth());
            double innerWidth = Math.max(120, rootWidth - 2);
            double bodyWidth = Math.max(120, innerWidth);

            body.setPrefWidth(bodyWidth);
            body.setMaxWidth(bodyWidth);
            body.setPrefHeight(Region.USE_COMPUTED_SIZE);
            body.applyCss();
            body.layout();

            double bodyHeight = Math.ceil(body.prefHeight(bodyWidth));
            double titleHeight = Math.ceil(frame.titleBar.prefHeight(bodyWidth));
            double adjustedHeight = Math.max(DIALOG_MIN_HEIGHT, titleHeight + bodyHeight + 2);

            frame.root.setMinHeight(adjustedHeight);
            frame.root.setPrefHeight(adjustedHeight);
            stage.setHeight(adjustedHeight);
            centerStageToOwner(stage);
        });
    }

    private static void layoutFixedHeightContent(Stage stage,
                                                 CustomWindowFrameUtil.Frame frame,
                                                 VBox body,
                                                 Node content,
                                                 HBox buttonBar) {
        Platform.runLater(() -> {
            frame.root.applyCss();
            frame.root.layout();
            body.applyCss();
            body.layout();
            buttonBar.applyCss();
            buttonBar.layout();

            if (content instanceof Region region) {
                double titleHeight = Math.ceil(frame.titleBar.prefHeight(stage.getWidth()));
                double buttonHeight = Math.ceil(buttonBar.prefHeight(body.getWidth()));
                double availableHeight = Math.max(
                        80,
                        stage.getHeight()
                                - titleHeight
                                - CONTENT_TOP
                                - CONTENT_BOTTOM
                                - body.getSpacing()
                                - buttonHeight
                                - 4
                );
                region.setMinHeight(0);
                region.setPrefHeight(availableHeight);
                region.setMaxHeight(Double.MAX_VALUE);
                body.applyCss();
                body.layout();
            }
        });
    }

    private static void centerStageToOwner(Stage stage) {
        Window owner = stage.getOwner();
        if (owner == null || !owner.isShowing()) {
            return;
        }
        double x = owner.getX() + (owner.getWidth() - stage.getWidth()) / 2;
        double y = owner.getY() + (owner.getHeight() - stage.getHeight()) / 2;
        stage.setX(x);
        stage.setY(y);
    }

    private static double getTextContentWidth(double width) {
        return Math.max(120, width - CONTENT_LEFT - CONTENT_RIGHT - 2);
    }

    private static double getRegionContentWidth(double width) {
        return Math.max(120, width - CONTENT_LEFT - CONTENT_RIGHT);
    }

    public static final class ContentDialog {
        private final Stage stage;
        private final CustomWindowFrameUtil.Frame frame;
        private final AtomicReference<ButtonType> resultRef;
        private final Map<ButtonType, Button> buttons;
        private final ButtonType defaultButtonType;
        private final ButtonType cancelButtonType;

        private ContentDialog(Stage stage,
                              CustomWindowFrameUtil.Frame frame,
                              AtomicReference<ButtonType> resultRef,
                              Map<ButtonType, Button> buttons,
                              ButtonType defaultButtonType,
                              ButtonType cancelButtonType) {
            this.stage = stage;
            this.frame = frame;
            this.resultRef = resultRef;
            this.buttons = buttons;
            this.defaultButtonType = defaultButtonType;
            this.cancelButtonType = cancelButtonType;
        }

        public Button getButton(ButtonType buttonType) {
            return buttons.get(buttonType);
        }

        public Stage getStage() {
            return stage;
        }

        public CustomWindowFrameUtil.Frame getFrame() {
            return frame;
        }

        public ButtonType showAndWait() {
            stage.showAndWait();
            ButtonType result = resultRef.get();
            if (result != null) {
                return result;
            }
            return cancelButtonType != null ? cancelButtonType : defaultButtonType;
        }
    }
}
