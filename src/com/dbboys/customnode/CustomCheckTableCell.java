package com.dbboys.customnode;

import com.dbboys.i18n.I18n;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import javafx.scene.Group;

//巡检结论一列自定义格式
public class CustomCheckTableCell<S,T> extends CustomTableCell<S,T> {

    private static final String STATUS_OK = "0";
    private static final String STATUS_WARN = "1";
    private static final String LABEL_OK = "正常";
    private static final String LABEL_WARN = "关注";
    private static final String LABEL_ERROR = "异常";

    private final Group groupOk;
    private final Group groupWarn;
    private final Group groupError;

    public CustomCheckTableCell(){
        groupOk = IconFactory.group(IconPaths.CHECK_OK, 0.5, Color.valueOf("#2bb740"));
        groupWarn = IconFactory.group(IconPaths.CHECK_WARN, 0.5, Color.valueOf("#ffbf00"));
        groupError = IconFactory.group(IconPaths.CHECK_ERROR, 0.05, Color.valueOf("#cf2311"));
        I18n.localeProperty().addListener((obs, oldLocale, newLocale) -> {
            if (!isEmpty() && getItem() != null) {
                Platform.runLater(() -> applyStatusView(String.valueOf(getItem())));
            }
        });
    }


    @Override
    protected void updateItem(T item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            return;
        }
        applyStatusView(String.valueOf(item));
    }

    private void applyStatusView(String value) {
        if (STATUS_OK.equals(value)) {
            setGraphic(groupOk);
            setText(I18n.t("check.status.ok", LABEL_OK));
        } else if (STATUS_WARN.equals(value)) {
            setGraphic(groupWarn);
            setText(I18n.t("check.status.warn", LABEL_WARN));
        } else {
            setGraphic(groupError);
            setText(I18n.t("check.status.error", LABEL_ERROR));
        }
    }

}
