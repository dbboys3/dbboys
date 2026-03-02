package com.dbboys.util;

import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconPaths;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.util.Optional;

public final class AlterUtil {
    private static final Insets CONTENT_PADDING = new Insets(10, 20, 10, 20);

    private AlterUtil() {
    }

    public static void showAlert(String title, String message) {
        Alert alert = createAlert(Alert.AlertType.NONE, title, message);
        ButtonType confirmButton = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(confirmButton);
        alert.showAndWait();
    }

    public static boolean showConfirm(String title, String message) {
        Alert confirmAlert = createAlert(Alert.AlertType.CONFIRMATION, title, message);
        ButtonType confirmButton = new ButtonType(I18n.t("common.confirm", "确认"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(I18n.t("common.cancel", "取消"), ButtonBar.ButtonData.CANCEL_CLOSE);
        confirmAlert.getButtonTypes().setAll(confirmButton, cancelButton);
        Optional<ButtonType> result = confirmAlert.showAndWait();
        return result.isPresent() && result.get() == confirmButton;
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

    private static Alert createAlert(Alert.AlertType alertType, String title, String message) {
        Alert alert = new Alert(alertType);
        alert.setHeaderText("");
        alert.setTitle(title);
        alert.setGraphic(null);

        Text text = new Text(message == null ? "" : message);
        text.wrappingWidthProperty().bind(alert.getDialogPane().widthProperty().subtract(40));
        alert.getDialogPane().setContent(new javafx.scene.layout.VBox(text));
        ((javafx.scene.layout.VBox) alert.getDialogPane().getContent()).setPadding(CONTENT_PADDING);

        AppState.applyAppStylesheet(alert.getDialogPane().getScene());

        Stage stage = (Stage) alert.getDialogPane().getScene().getWindow();
        if (stage.getIcons().isEmpty()) {
            stage.getIcons().add(new javafx.scene.image.Image(IconPaths.MAIN_LOGO));
        }
        return alert;
    }
}
