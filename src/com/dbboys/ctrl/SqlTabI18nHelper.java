package com.dbboys.ctrl;

import com.dbboys.i18n.I18n;
import com.dbboys.vo.*;
import javafx.scene.control.*;

import java.util.ArrayList;
import java.util.List;

public class SqlTabI18nHelper {
    private static final String COMMIT_AUTO = "AUTO";
    private static final String COMMIT_MANUAL = "MANUAL";

    private final SqlTabController ctrl;

    public SqlTabI18nHelper(SqlTabController ctrl) {
        this.ctrl = ctrl;
    }

    public void bindText(Labeled labeled, String key) {
        if (labeled != null) {
            labeled.textProperty().bind(I18n.bind(key));
        }
    }

    public void bindTabText(Tab tab, String key) {
        if (tab != null) {
            tab.textProperty().bind(I18n.bind(key));
        }
    }

    public void bindColumnText(TableColumn<?, ?> column, String key) {
        if (column != null) {
            column.textProperty().bind(I18n.bind(key));
        }
    }

    public void bindTooltip(Control control, String key) {
        if (control == null) {
            return;
        }
        Tooltip tooltip = control.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip();
            control.setTooltip(tooltip);
        }
        tooltip.textProperty().bind(I18n.bind(key));
    }

    public String formatExecuteTime(double seconds) {
        String value = String.valueOf(Math.round(seconds * 10.0) / 10.0);
        return String.format(I18n.t("sql.exec.time"), value);
    }

    public String formatElapsedSeconds(long millis) {
        String value = String.format("%.3f", millis / 1000.0);
        return String.format(I18n.t("sql.exec.elapsed"), value);
    }

    public String buildExecutionMark() {
        String mark = getCommitModeLabel();
        if (ctrl.sqlSqlModeChoiceBox.isVisible()) {
            mark += I18n.t("sql.mark.sep") + ctrl.sqlSqlModeChoiceBox.getValue();
        }
        if (!ctrl.sqlParamList.isEmpty()) {
            mark += I18n.t("sql.mark.params") + ctrl.sqlParamList;
        }
        return mark;
    }

    public String getCommitModeLabel() {
        String value = ctrl.sqlCommitModeChoiceBox.getValue();
        if (COMMIT_MANUAL.equals(value)) {
            return I18n.t("sql.commit.manual");
        }
        return I18n.t("sql.commit.auto");
    }

    public void initI18nBindings() {
        bindTooltip(ctrl.sqlRunButton, "sql.tooltip.run");
        bindTooltip(ctrl.sqlExplainButton, "sql.tooltip.explain");
        bindTooltip(ctrl.sqlStopButton, "sql.tooltip.stop");
        bindTooltip(ctrl.sqlRecordButton, "sql.tooltip.history");

        bindText(ctrl.transactionCommitButton, "sql.transaction.commit");
        bindText(ctrl.transactionRollbackButton, "sql.transaction.rollback");
        bindText(ctrl.sqlReadOnlyLabel, "sql.label.readonly");
        bindTabText(ctrl.resultsetSummaryTab, "sql.tab.result");

        setupConnectChoiceBoxI18n();
        setupChoiceBoxConverter(ctrl.sqlDbChoiceBox);
        ctrl.sqlCommitModeChoiceBox.setConverter(new javafx.util.StringConverter<String>() {
            @Override
            public String toString(String object) {
                if (COMMIT_MANUAL.equals(object)) {
                    return I18n.t("sql.commit.manual");
                }
                if (COMMIT_AUTO.equals(object)) {
                    return I18n.t("sql.commit.auto");
                }
                return object == null ? "" : object;
            }

            @Override
            public String fromString(String string) {
                return string;
            }
        });
        refreshCommitModeItems();

        ctrl.sqlExecuteTimeInfo.setText(formatExecuteTime(0));

        I18n.localeProperty().addListener((obs, oldVal, newVal) -> {
            ctrl.defaultConnect.setName(I18n.t("sql.connect.select_prompt"));
            ctrl.defaultDatabase.setName(I18n.t("common.na"));
            refreshConnectChoiceBoxItems();
            refreshDefaultConnectDisplay();
            refreshDbChoiceBoxDisplay();
            ctrl.sqlExecuteTimeInfo.setText(formatExecuteTime(0));
            refreshCommitModeItems();
            ctrl.sqlCommitModeChoiceBox.setValue(ctrl.sqlCommitModeChoiceBox.getValue());
        });
    }

    private void setupConnectChoiceBoxI18n() {
        setupChoiceBoxConverter(ctrl.sqlConnectChoiceBox);
        refreshConnectChoiceBoxItems();
    }

    <T extends TreeData> void setupChoiceBoxConverter(ChoiceBox<T> choiceBox) {
        choiceBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(T object) {
                return object == null ? "" : object.getName();
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        });
    }

    public void refreshConnectChoiceBoxItems() {
        if (ctrl.sqlConnectChoiceBox.getItems() == null || ctrl.sqlConnectChoiceBox.getItems().isEmpty()) {
            return;
        }
        ctrl.suppressConnectChange = true;
        int selectedIndex = ctrl.sqlConnectChoiceBox.getSelectionModel().getSelectedIndex();
        Connect selectedValue = ctrl.sqlConnectChoiceBox.getValue();
        List<Connect> snapshot = new ArrayList<>(ctrl.sqlConnectChoiceBox.getItems());
        ctrl.sqlConnectChoiceBox.getItems().setAll(snapshot);
        if (selectedIndex >= 0 && selectedIndex < ctrl.sqlConnectChoiceBox.getItems().size()) {
            ctrl.sqlConnectChoiceBox.getSelectionModel().select(selectedIndex);
        } else {
            if (selectedValue != null && ctrl.sqlConnectChoiceBox.getItems().contains(selectedValue)) {
                ctrl.sqlConnectChoiceBox.getSelectionModel().select(selectedValue);
            } else if (selectedValue != null) {
                ctrl.sqlConnectChoiceBox.setValue(selectedValue);
            } else {
                ctrl.sqlConnectChoiceBox.setValue(ctrl.defaultConnect);
            }
        }
        ctrl.suppressConnectChange = false;
    }

    public void refreshDefaultConnectDisplay() {
        if (ctrl.sqlConnectChoiceBox.getValue() != ctrl.defaultConnect) {
            return;
        }
        ctrl.suppressConnectChange = true;
        ctrl.sqlConnectChoiceBox.setValue(null);
        ctrl.sqlConnectChoiceBox.setValue(ctrl.defaultConnect);
        ctrl.suppressConnectChange = false;
    }

    public void refreshDbChoiceBoxDisplay() {
        if (ctrl.sqlDbChoiceBox.getValue() == null) {
            return;
        }
        ctrl.suppressDbChange = true;
        Database selected = ctrl.sqlDbChoiceBox.getValue();
        ctrl.sqlDbChoiceBox.getSelectionModel().clearSelection();
        ctrl.sqlDbChoiceBox.setValue(selected);
        ctrl.suppressDbChange = false;
    }

    public void refreshCommitModeItems() {
        String selected = ctrl.sqlCommitModeChoiceBox.getValue();
        ctrl.sqlCommitModeChoiceBox.getItems().clear();
        ctrl.sqlCommitModeChoiceBox.getItems().add(COMMIT_AUTO);
        ctrl.sqlCommitModeChoiceBox.getItems().add(COMMIT_MANUAL);
        if (selected == null || !ctrl.sqlCommitModeChoiceBox.getItems().contains(selected)) {
            selected = COMMIT_AUTO;
        }
        ctrl.sqlCommitModeChoiceBox.setValue(selected);
    }
}
