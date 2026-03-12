package com.dbboys.util;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconPaths;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class AlertUtil {
    private static final double DIALOG_WIDTH = 400;
    private static final Insets CONTENT_PADDING = new Insets(10, 20, 10, 20);
    private static final String ALERT_TITLE_STYLE =
            "-fx-background-color: #b33029;" +
            "-fx-padding: 0 0 0 6;" +
            "-fx-min-height: 28;" +
            "-fx-pref-height: 28;" +
            "-fx-alignment: center-left;";
    private static final String BODY_STYLE =
            "-fx-background-color: #161616;" +
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

    // Backward-compatible wrappers for existing call sites.
    public static void CustomAlert(String title, String alertText) {
        showAlert(title, alertText);
    }

    // Backward-compatible wrappers for existing call sites.
    public static boolean CustomAlertConfirm(String title, String contentText) {
        return showConfirm(title, contentText);
    }

    private static ButtonType showDialog(String title, String message, ButtonType... buttonTypes) {
        Text text = new Text(message == null ? "" : message);
        text.setStyle("-fx-fill: -color-fg-default;");
        text.setWrappingWidth(DIALOG_WIDTH - 40);
        return createContentDialog(title, text, DIALOG_WIDTH, 120, buttonTypes).showAndWait();
    }

    public static ContentDialog createContentDialog(String title, Node content, ButtonType... buttonTypes) {
        return createContentDialog(title, content, DIALOG_WIDTH, 120, buttonTypes);
    }

    public static ContentDialog createContentDialog(String title, Node content, double width, double height, ButtonType... buttonTypes) {
        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle(title == null ? "" : title);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setResizable(false);
        Window owner = AppState.getWindow();
        if (owner != null) {
            stage.initOwner(owner);
        }
        if (stage.getIcons().isEmpty()) {
            stage.getIcons().add(new javafx.scene.image.Image(IconPaths.MAIN_LOGO));
        }

        AtomicReference<ButtonType> resultRef = new AtomicReference<>();
        ButtonType defaultButtonType = findDefaultButton(buttonTypes);
        ButtonType cancelButtonType = findCancelButton(buttonTypes);

        HBox buttonBar = new HBox(10);
        buttonBar.setStyle("-fx-alignment: center-right;");
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
            buttonBar.getChildren().add(button);
        }

        VBox body = new VBox(content, buttonBar);
        body.setPadding(CONTENT_PADDING);
        body.setStyle(BODY_STYLE);
        VBox.setVgrow(content, Priority.ALWAYS);

        CustomWindowFrameUtil.Frame frame = CustomWindowFrameUtil.create(
                stage,
                stage.titleProperty(),
                body,
                width,
                height,
                null,
                false,
                false,
                false
        );
        frame.root.setMinWidth(width);
        frame.root.setStyle(frame.root.getStyle() +
                "-fx-border-color: -color-fg-default;" +
                "-fx-border-width: 1;");
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

        stage.setScene(frame.scene);
        stage.setOnShown(event -> {
            Button defaultButton = buttonMap.get(defaultButtonType);
            if (defaultButton != null) {
                defaultButton.requestFocus();
            }
        });
        return new ContentDialog(stage, frame, resultRef, buttonMap, defaultButtonType, cancelButtonType);
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
