package com.dbboys.ctrl;

import com.dbboys.i18n.I18n;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.util.*;
import com.dbboys.vo.UpdateResult;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class ResultSetEditHelper {
    private final ResultSetTabController ctrl;

    public ResultSetEditHelper(ResultSetTabController ctrl) {
        this.ctrl = ctrl;
    }

    public void updateCellValue(int columnIndex,
                                String newValue,
                                ObservableList<String> row,
                                Object oldValue,
                                javafx.beans.property.SimpleStringProperty sqlTransactionText,
                                ChoiceBox<?> commitmode) throws SQLException {
        String updateCol = String.valueOf(ctrl.resultTableCols.get(columnIndex - 1));
        String updateSql = "update " + ctrl.resultFromTable + " set " + updateCol + "=? where 1=1 ";
        if (ctrl.resultTablePriNum != null && !ctrl.resultTablePriNum.isEmpty()) {
            for (Integer colnum : ctrl.resultTablePriNum) {
                updateSql += " and " + ctrl.resultTableCols.get(colnum) + "=?";
            }
        }

        ctrl.sqlStatement = ctrl.sqlConnect.getConn().prepareStatement(updateSql);
        boolean isNull = "[NULL]".equals(newValue);
        ctrl.sqlStatement.setObject(1, isNull ? null : newValue);

        int prepareNum = 2;
        StringBuilder sqlParams = new StringBuilder();
        if (ctrl.resultTablePriNum != null) {
            for (Integer colnum : ctrl.resultTablePriNum) {
                Object pkVal = (columnIndex - 1 == colnum) ? oldValue : row.get(colnum + 1);
                ctrl.sqlStatement.setObject(prepareNum++, pkVal);
                if (sqlParams.length() > 0) {
                    sqlParams.append(",");
                }
                sqlParams.append(pkVal == null ? "null" : pkVal);
            }
        }

        if (isNull) {
            ctrl.resultSetTableView.refresh();
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        long sql_begin_time = System.currentTimeMillis();
        int sqlAffect = ctrl.sqlStatement.executeUpdate();
        long sql_finish_time = System.currentTimeMillis();

        UpdateResult updateResult = new UpdateResult();
        updateResult.setConnectId(ctrl.sqlConnect.getId());
        updateResult.setStartTime(sdf.format(sql_begin_time));
        updateResult.setEndTime(sdf.format(sql_finish_time));
        updateResult.setElapsedTime(String.format("%.3f", (sql_finish_time - sql_begin_time) / 1000.0) + " sec");
        updateResult.setAffectedRows(sqlAffect);
        updateResult.setDatabase(ctrl.sqlConnect.getDatabase());
        updateResult.setUpdateSql(updateSql);
        if (commitmode.getSelectionModel().getSelectedIndex() == 1) {
            updateResult.setMark(String.format(I18n.t("resultset.mark.manual_edit", "手动提交，查询结果集编辑，参数[%s,%s]"), newValue, sqlParams));
            sqlTransactionText.set(sqlTransactionText.get() + updateSql + "\n");
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.manual_commit_pending", "当前连接为手动提交，修改暂未提交，请点击提交或回滚！"));
        } else {
            updateResult.setMark(String.format(I18n.t("resultset.mark.auto_edit", "自动提交，查询结果集编辑，参数[%s,%s]"), newValue, sqlParams));
        }
        LocalDbRepository.saveSqlHistory(updateResult);
        ctrl.sqlStatement.close();
    }

    public boolean prepareParams(ParameterMetaData meta, Statement stmt) {
        if (meta == null) {
            return true;
        }
        int paramCount = 0;
        try {
            paramCount = meta.getParameterCount();
        } catch (SQLException e) {
            return true;
        }
        if (paramCount <= 0) {
            return true;
        }
        final int paramCountFinal = paramCount;
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ctrl.sqlParamList.clear();
            ctrl.sqlParamList = PopupWindowUtil.openParamWindow(paramCountFinal);
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    public PrimaryKeyInfo fetchPrimaryKeyInfo(String finalSqlGetPrimary, String sqlExe) throws SQLException {
        String pri = null;
        ctrl.sqlStatement = ctrl.sqlConnect.getConn().prepareStatement(finalSqlGetPrimary);
        ctrl.sqlStatement.setObject(1, ctrl.resultFromTable);
        ctrl.priSqlResult = ctrl.sqlStatement.executeQuery();
        if (ctrl.priSqlResult.next()) {
            pri = ctrl.priSqlResult.getString(1);
        }
        ctrl.priSqlResult.close();

        List<String> cols = new ArrayList<>();
        ctrl.sqlStatement = ctrl.sqlConnect.getConn().prepareStatement("select colname from syscolumns c,systables t where  t.tabid=c.tabid and tabname=?");
        ctrl.sqlStatement.setObject(1, ctrl.resultFromTable);
        ctrl.priSqlResult = ctrl.sqlStatement.executeQuery();
        while (ctrl.priSqlResult.next()) {
            cols.add(ctrl.priSqlResult.getString(1));
        }
        ctrl.priSqlResult.close();
        ctrl.resultTableCols = SqlParserUtil.getSelectedCols(sqlExe, cols);
        return new PrimaryKeyInfo(pri, ctrl.resultTableCols);
    }

    public void applyPrimaryKeyInfo(PrimaryKeyInfo info) {
        if (info == null) {
            ctrl.resultSetTableView.setEditable(false);
            return;
        }
        String pri = info.primaryKeys;
        List<String> selectedCols = info.selectedColumns;
        ctrl.resultTableCols = selectedCols;
        if (pri != null && selectedCols.containsAll(List.of(pri.split(",")))) {
            for (String key : pri.split(",")) {
                int columnIndex = selectedCols.indexOf(key);
                ctrl.resultTablePriNum.add(columnIndex);
                markPrimaryKeyColumn(columnIndex, "PRI", "-fx-font-size: 8;-fx-text-fill: #9f453c");
            }
            return;
        }
        if (selectedCols.contains("rowid")) {
            int columnIndex = selectedCols.indexOf("rowid");
            ctrl.resultTablePriNum.add(columnIndex);
            markPrimaryKeyColumn(columnIndex, "ROWID", "-fx-font-size: 5;-fx-text-fill: #9f453c");
            return;
        }
        ctrl.resultSetTableView.setEditable(false);
    }

    private void markPrimaryKeyColumn(int columnIndex, String labelText, String labelStyle) {
        ctrl.resultSetEditableEnabledLabel.setVisible(true);
        TableColumn<String, Object> column = (TableColumn<String, Object>) ctrl.resultSetTableView.getColumns().get(columnIndex + 1);
        StackPane sp = new StackPane();
        Label priLabel = new Label(labelText);
        priLabel.setStyle(labelStyle);
        sp.getChildren().add(priLabel);
        StackPane.setAlignment(priLabel, Pos.BOTTOM_LEFT);
        column.getGraphic().setStyle("-fx-text-fill:#9f453c ");
        sp.getChildren().add(column.getGraphic());
        column.setGraphic(sp);
        if (!ctrl.sqlConnect.getReadonly()) {
            ctrl.resultSetTableView.setEditable(true);
        }
    }

    public static class PrimaryKeyInfo {
        public final String primaryKeys;
        public final List<String> selectedColumns;

        public PrimaryKeyInfo(String primaryKeys, List<String> selectedColumns) {
            this.primaryKeys = primaryKeys;
            this.selectedColumns = selectedColumns;
        }
    }
}
