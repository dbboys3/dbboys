package com.dbboys.ctrl;

import com.dbboys.i18n.I18n;
import com.dbboys.util.AlertUtil;
import com.dbboys.db.ConnectionErrorHandler;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ResultSetFetchHelper {
    private final ResultSetTabController ctrl;

    public ResultSetFetchHelper(ResultSetTabController ctrl) {
        this.ctrl = ctrl;
    }

    public static List<ObservableList<String>> fetchRows(ResultSet resultSet,
                                                         int perpage,
                                                         Task<?> sqlTask,
                                                         int columnCount,
                                                         java.util.function.IntConsumer progressCallback) throws SQLException {
        List<ObservableList<String>> rows = new ArrayList<>();
        int fetched = 0;
        while (fetched < perpage && resultSet.next()) {
            if (sqlTask != null && sqlTask.isCancelled()) {
                break;
            }
            ObservableList<String> row = FXCollections.observableArrayList();
            row.add(null);
            for (int z = 1; z <= columnCount; z++) {
                row.add(resultSet.getString(z));
            }
            rows.add(row);
            fetched++;
            if (progressCallback != null && fetched % 200 == 0) {
                progressCallback.accept(fetched);
            }
        }
        return rows;
    }

    public String formatFetchedRows(int fetched) {
        String template = I18n.t("sql.result.fetched_rows");
        return template.replace("{0}", String.valueOf(fetched));
    }

    public int getPerPageLimit() {
        String text = ctrl.resultSetPerTimeTextField.getText();
        if (text == null || text.isBlank() || "0".equals(text) || text.startsWith("-")) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    public void setFetchStatus(String message) {
        Platform.runLater(() -> ctrl.sqlExecuteProcessLabel.setText(message));
    }

    public void applyFetchedRows(String sqlExe) {
        Platform.runLater(() -> {
            ctrl.lastSqlTextField.setText(sqlExe);
            ctrl.lastSqlTextField.getTooltip().setText(sqlExe);
            ctrl.resultSetTableView.getColumns().addAll(ctrl.colList);
            ctrl.resultSetTableView.getItems().addAll(ctrl.sqlResultSetList);
            ctrl.resultSetTableView.refresh();
            ctrl.resultSetFetchedRowsLabel.setText(ctrl.sqlFetchedRows.toString());
            ctrl.sqlUsedTimeLabel.setText(String.format("%.3f", ctrl.sqlFetchedTime / 1000.0));
            ctrl.sqlResultSetList.clear();
        });
    }

    public Task<Void> createFetchTask(boolean fetchAll) {
        ctrl.sqlExecuteProcessStackPane.setVisible(true);
        ctrl.sqlFetchedRows = 0;
        ctrl.sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    int columnCount = ctrl.sqlResultSet.getMetaData().getColumnCount();
                    ctrl.sqlResultSetList.clear();
                    setFetchStatus(" " + I18n.t("sql.result.fetching"));
                    ctrl.sqlFetchStartTime = System.currentTimeMillis();
                    int perPage = fetchAll ? Integer.MAX_VALUE : getPerPageLimit();
                    List<ObservableList<String>> fetchedRows = fetchRows(
                            ctrl.sqlResultSet,
                            perPage,
                            ctrl.sqlTask,
                            columnCount,
                            fetched -> setFetchStatus(formatFetchedRows(fetched))
                    );
                    ctrl.sqlResultSetList.addAll(fetchedRows);
                    ctrl.sqlFetchedRows += fetchedRows.size();
                } catch (SQLException e) {
                    if (ConnectionErrorHandler.isDisconnectError(e)) {
                        ctrl.hiddenDisconnectedButton.fire();
                    } else {
                        Platform.runLater(() -> AlertUtil.CustomAlert("错误", "[" + e.getErrorCode() + "]" + e.getMessage()));
                    }
                }
                return null;
            }
        };
        if (fetchAll) {
            ctrl.sqlTask.setOnSucceeded(event1 -> {
                ctrl.eventEnd.run();
                Platform.runLater(() -> ctrl.resultSetTotalRowsLabel.setText(ctrl.resultSetFetchedRowsLabel.getText()));
            });
        } else {
            ctrl.sqlTask.setOnSucceeded(event1 -> ctrl.eventEnd.run());
        }
        ctrl.sqlTask.setOnCancelled(event1 -> ctrl.eventEnd.run());
        ctrl.sqlTask.setOnFailed(event1 -> ctrl.eventEnd.run());
        return ctrl.sqlTask;
    }
}
