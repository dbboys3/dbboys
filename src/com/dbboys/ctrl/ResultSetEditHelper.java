package com.dbboys.ctrl;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.app.AppContext;
import com.dbboys.i18n.I18n;
import com.dbboys.impl.DatabasePlatforms;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.util.*;
import com.dbboys.vo.ColumnsInfo;
import com.dbboys.vo.UpdateResult;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public class ResultSetEditHelper {
    private final ResultSetTabController ctrl;
    private final DatabasePlatformResolver platformResolver;

    public ResultSetEditHelper(ResultSetTabController ctrl) {
        this.ctrl = ctrl;
        this.platformResolver = resolvePlatformResolver();
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
        String sessionDb = ctrl.sqlConnect.getSessionDatabase();
        updateResult.setDatabase(sessionDb != null && !sessionDb.isBlank() ? sessionDb : ctrl.sqlConnect.getDatabase());
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

    public PrimaryKeyInfo fetchPrimaryKeyInfo(String sqlExe) throws SQLException {
        if (ctrl.resultFromTable == null || ctrl.resultFromTable.isBlank()) {
            return null;
        }
        var repo = platformResolver.metadata(ctrl.sqlConnect);
        List<String> primaryKeys = normalizeIdentifiers(repo.getPrimaryKeyColumns(ctrl.sqlConnect.getConn(), ctrl.resultFromTable));
        List<String> tableColumns = normalizeIdentifiers(repo.getTableColumnNames(ctrl.sqlConnect.getConn(), ctrl.resultFromTable));
        if (tableColumns.isEmpty()) {
            try {
                ArrayList<ColumnsInfo> columns = repo.getColumns(ctrl.sqlConnect.getConn(), ctrl.resultFromTable);
                for (ColumnsInfo column : columns) {
                    tableColumns.add(normalizeIdentifier(column.getColName()));
                }
            } catch (UnsupportedOperationException ignored) {
                // no-op
            }
        }
        ctrl.resultTableCols = SqlParserUtil.getSelectedCols(sqlExe, tableColumns);
        return new PrimaryKeyInfo(primaryKeys, ctrl.resultTableCols);
    }

    public void applyPrimaryKeyInfo(PrimaryKeyInfo info) {
        if (info == null) {
            ctrl.resultSetTableView.setEditable(false);
            return;
        }
        List<String> primaryKeys = info.primaryKeys;
        List<String> selectedCols = info.selectedColumns;
        ctrl.resultTableCols = selectedCols;
        boolean hasEditableKey = false;
        if (primaryKeys != null && !primaryKeys.isEmpty() && selectedCols.containsAll(primaryKeys)) {
            for (String key : primaryKeys) {
                int columnIndex = selectedCols.indexOf(key);
                ctrl.resultTablePriNum.add(columnIndex);
                markPrimaryKeyColumn(columnIndex, "PRI");
            }
            hasEditableKey = true;
        }
        if (selectedCols.contains("rowid")) {
            int columnIndex = selectedCols.indexOf("rowid");
            if (!hasEditableKey) {
                ctrl.resultTablePriNum.add(columnIndex);
                hasEditableKey = true;
            }
            markPrimaryKeyColumn(columnIndex, "ROWID");
        }
        if (!hasEditableKey) {
            ctrl.resultSetTableView.setEditable(false);
        }
    }

    private void markPrimaryKeyColumn(int columnIndex, String labelText) {
        ctrl.resultSetEditableEnabledLabel.setVisible(!ctrl.sqlConnect.getReadonly());
        ctrl.columnBuilder.markColumnKey(columnIndex, labelText);
        if (!ctrl.sqlConnect.getReadonly()) {
            ctrl.resultSetTableView.setEditable(true);
        }
    }

    public static class PrimaryKeyInfo {
        public final List<String> primaryKeys;
        public final List<String> selectedColumns;

        public PrimaryKeyInfo(List<String> primaryKeys, List<String> selectedColumns) {
            this.primaryKeys = primaryKeys;
            this.selectedColumns = selectedColumns;
        }
    }

    private static DatabasePlatformResolver resolvePlatformResolver() {
        try {
            return AppContext.get(DatabasePlatformResolver.class);
        } catch (IllegalStateException e) {
            return DatabasePlatforms.createDefault();
        }
    }

    private static List<String> normalizeIdentifiers(List<String> identifiers) {
        List<String> result = new ArrayList<>();
        if (identifiers == null) {
            return result;
        }
        for (String identifier : identifiers) {
            String normalized = normalizeIdentifier(identifier);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String normalized = identifier.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.trim().toLowerCase(Locale.ROOT);
    }
}
