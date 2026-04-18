package com.dbboys.ctrl;

import com.dbboys.api.ConnectionService;
import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.app.AppContext;
import com.dbboys.i18n.I18n;
import com.dbboys.impl.DatabasePlatforms;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.util.*;
import com.dbboys.vo.Connect;
import com.dbboys.vo.ColumnsInfo;
import com.dbboys.vo.UpdateResult;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ResultSetEditHelper {
    private static final Logger log = LogManager.getLogger(ResultSetEditHelper.class);

    private final ResultSetTabController ctrl;
    private final DatabasePlatformResolver platformResolver;
    private final ConnectionService connectionService;

    public ResultSetEditHelper(ResultSetTabController ctrl) {
        this.ctrl = ctrl;
        this.platformResolver = resolvePlatformResolver();
        this.connectionService = resolveConnectionService();
    }

    public void updateCellValue(int columnIndex,
                                String newValue,
                                ObservableList<String> row,
                                Object oldValue,
                                javafx.beans.property.SimpleStringProperty sqlTransactionText,
                                ChoiceBox<?> commitmode) throws SQLException {
        String rawUpdateCol = String.valueOf(ctrl.resultTableCols.get(columnIndex - 1));
        String updateCol = oracleBareColumnNameForUpdate(rawUpdateCol);
        ensureNotWildcardResultColumn(updateCol);
        String updateSql = "update " + ctrl.resultFromTable + " set " + updateCol + "=? where 1=1 ";
        if (ctrl.resultTablePriNum != null && !ctrl.resultTablePriNum.isEmpty()) {
            for (Integer colnum : ctrl.resultTablePriNum) {
                String rawWhereCol = ctrl.resultTableCols.get(colnum);
                String whereCol = oracleBareColumnNameForUpdate(rawWhereCol);
                ensureNotWildcardResultColumn(whereCol);
                updateSql += " and " + whereCol + "=?";
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

        log.info("Result set edit UPDATE: {} | setParam={} | whereParams=[{}]",
                updateSql,
                isNull ? "[NULL]" : newValue,
                sqlParams);

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
        updateResult.setDatabase(ctrl.sqlConnect.getEffectiveCatalog());
        updateResult.setUpdateSql(updateSql);
        if (isManualCommitMode(commitmode)) {
            updateResult.setMark(String.format(I18n.t("resultset.mark.manual_edit", "手动提交，查询结果集编辑，参数[%s,%s]"), newValue, sqlParams));
            appendPendingTransactionSql(sqlTransactionText, updateSql + "\n");
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.manual_commit_pending", "当前连接为手动提交，修改暂未提交，请点击提交或回滚！"));
        } else {
            updateResult.setMark(String.format(I18n.t("resultset.mark.auto_edit", "自动提交，查询结果集编辑，参数[%s,%s]"), newValue, sqlParams));
        }
        LocalDbRepository.saveSqlHistory(updateResult);
        ctrl.sqlStatement.close();
        ctrl.sqlStatement = null;
    }

    /**
     * INSERT for a new grid row (excluding ROWID pseudo-column from column list).
     */
    public void insertRow(ObservableList<String> row,
                          SimpleStringProperty sqlTransactionText,
                          ChoiceBox<?> commitmode) throws SQLException {
        if (ctrl.resultFromTable == null || ctrl.resultFromTable.isBlank()) {
            throw new SQLException(I18n.t("resultset.edit.no_table", "无法解析目标表，不能插入。"));
        }
        if (ctrl.resultTableCols == null || ctrl.resultTableCols.isEmpty()) {
            throw new SQLException(I18n.t("resultset.edit.no_columns", "无法解析列，不能插入。"));
        }
        List<Integer> dataColIndices = new ArrayList<>();
        List<String> insertColExprs = new ArrayList<>();
        for (int i = 0; i < ctrl.resultTableCols.size(); i++) {
            String raw = ctrl.resultTableCols.get(i);
            String bare = oracleBareColumnNameForUpdate(raw);
            if ("rowid".equalsIgnoreCase(bare != null ? bare.trim() : "")) {
                continue;
            }
            insertColExprs.add(raw);
            dataColIndices.add(i);
        }
        if (insertColExprs.isEmpty()) {
            throw new SQLException(I18n.t("resultset.edit.insert_no_physical_cols", "没有可插入的物理列。"));
        }
        StringBuilder sql = new StringBuilder("insert into ");
        sql.append(ctrl.resultFromTable).append(" (");
        for (int j = 0; j < insertColExprs.size(); j++) {
            if (j > 0) {
                sql.append(", ");
            }
            sql.append(oracleBareColumnNameForUpdate(insertColExprs.get(j)));
        }
        sql.append(") values (");
        for (int j = 0; j < insertColExprs.size(); j++) {
            if (j > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        String insertSql = sql.toString();

        ctrl.sqlStatement = ctrl.sqlConnect.getConn().prepareStatement(insertSql);
        for (int j = 0; j < dataColIndices.size(); j++) {
            int colIdx = dataColIndices.get(j);
            String cell = row.get(colIdx + 1);
            boolean isNull = cell == null || cell.isBlank() || "[NULL]".equals(cell);
            ctrl.sqlStatement.setObject(j + 1, isNull ? null : cell);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        long sqlBegin = System.currentTimeMillis();
        int sqlAffect = ctrl.sqlStatement.executeUpdate();
        long sqlFinish = System.currentTimeMillis();

        UpdateResult updateResult = new UpdateResult();
        updateResult.setConnectId(ctrl.sqlConnect.getId());
        updateResult.setStartTime(sdf.format(sqlBegin));
        updateResult.setEndTime(sdf.format(sqlFinish));
        updateResult.setElapsedTime(String.format("%.3f", (sqlFinish - sqlBegin) / 1000.0) + " sec");
        updateResult.setAffectedRows(sqlAffect);
        updateResult.setDatabase(ctrl.sqlConnect.getEffectiveCatalog());
        updateResult.setUpdateSql(insertSql);
        if (isManualCommitMode(commitmode)) {
            updateResult.setMark(I18n.t("resultset.mark.manual_insert", "手动提交，结果集插入行"));
            appendPendingTransactionSql(sqlTransactionText, insertSql + "\n");
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.manual_commit_pending", "当前连接为手动提交，修改暂未提交，请点击提交或回滚！"));
        } else {
            updateResult.setMark(I18n.t("resultset.mark.auto_insert", "自动提交，结果集插入行"));
        }
        LocalDbRepository.saveSqlHistory(updateResult);
        ctrl.sqlStatement.close();
        ctrl.sqlStatement = null;
    }

    public void deleteRow(ObservableList<String> row,
                          SimpleStringProperty sqlTransactionText,
                          ChoiceBox<?> commitmode) throws SQLException {
        if (ctrl.resultFromTable == null || ctrl.resultFromTable.isBlank()) {
            throw new SQLException(I18n.t("resultset.edit.no_table", "无法解析目标表，不能删除。"));
        }
        if (ctrl.resultTablePriNum == null || ctrl.resultTablePriNum.isEmpty()) {
            throw new SQLException(I18n.t("resultset.edit.delete_no_pk", "无主键或 ROWID，不能删除。"));
        }
        StringBuilder deleteSql = new StringBuilder("delete from ").append(ctrl.resultFromTable).append(" where 1=1 ");
        for (Integer colnum : ctrl.resultTablePriNum) {
            String rawWhereCol = ctrl.resultTableCols.get(colnum);
            deleteSql.append(" and ").append(oracleBareColumnNameForUpdate(rawWhereCol)).append("=?");
        }
        String sqlStr = deleteSql.toString();

        ctrl.sqlStatement = ctrl.sqlConnect.getConn().prepareStatement(sqlStr);
        int prepareNum = 1;
        StringBuilder sqlParams = new StringBuilder();
        for (Integer colnum : ctrl.resultTablePriNum) {
            Object pkVal = row.get(colnum + 1);
            ctrl.sqlStatement.setObject(prepareNum++, pkVal == null || "[NULL]".equals(pkVal) ? null : pkVal);
            if (sqlParams.length() > 0) {
                sqlParams.append(",");
            }
            sqlParams.append(pkVal == null ? "null" : pkVal);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        long sqlBegin = System.currentTimeMillis();
        int sqlAffect = ctrl.sqlStatement.executeUpdate();
        long sqlFinish = System.currentTimeMillis();

        UpdateResult updateResult = new UpdateResult();
        updateResult.setConnectId(ctrl.sqlConnect.getId());
        updateResult.setStartTime(sdf.format(sqlBegin));
        updateResult.setEndTime(sdf.format(sqlFinish));
        updateResult.setElapsedTime(String.format("%.3f", (sqlFinish - sqlBegin) / 1000.0) + " sec");
        updateResult.setAffectedRows(sqlAffect);
        updateResult.setDatabase(ctrl.sqlConnect.getEffectiveCatalog());
        updateResult.setUpdateSql(sqlStr);
        if (isManualCommitMode(commitmode)) {
            updateResult.setMark(String.format(I18n.t("resultset.mark.manual_delete", "手动提交，结果集删除行，参数[%s]"), sqlParams));
            appendPendingTransactionSql(sqlTransactionText, sqlStr + "\n");
            NotificationUtil.showMainNotification(I18n.t("resultset.notice.manual_commit_pending", "当前连接为手动提交，修改暂未提交，请点击提交或回滚！"));
        } else {
            updateResult.setMark(String.format(I18n.t("resultset.mark.auto_delete", "自动提交，结果集删除行，参数[%s]"), sqlParams));
        }
        LocalDbRepository.saveSqlHistory(updateResult);
        ctrl.sqlStatement.close();
        ctrl.sqlStatement = null;
    }

    /**
     * One UPDATE per changed cell vs. snapshot (used when batching with「保存」).
     */
    public void flushRowUpdates(ObservableList<String> row,
                                ObservableList<String> snapshot,
                                SimpleStringProperty sqlTransactionText,
                                ChoiceBox<?> commitmode) throws SQLException {
        if (row == null || snapshot == null || row.size() != snapshot.size()) {
            return;
        }
        for (int col = 1; col < row.size(); col++) {
            String cur = row.get(col);
            String orig = snapshot.get(col);
            if (Objects.equals(cur, orig)) {
                continue;
            }
            String newVal = (cur == null || "[NULL]".equals(cur)) ? "[NULL]" : cur;
            updateCellValue(col, newVal, row, orig, sqlTransactionText, commitmode);
        }
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
        Connect metadataConnect = new Connect(ctrl.sqlConnect);
        try (Connection metadataConn = connectionService.getConnectionWithSessionInit(metadataConnect)) {
            return loadPrimaryKeyInfo(repo, metadataConn, sqlExe);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Load primary key metadata failed.", e);
        }
    }

    private PrimaryKeyInfo loadPrimaryKeyInfo(com.dbboys.api.MetadataRepository repo,
                                              Connection metadataConn,
                                              String sqlExe) throws SQLException {
        List<String> primaryKeys = normalizeIdentifiers(repo.getPrimaryKeyColumns(metadataConn, ctrl.resultFromTable));
        List<String> tableColumns = normalizeIdentifiers(repo.getTableColumnNames(metadataConn, ctrl.resultFromTable));
        if (tableColumns.isEmpty()) {
            try {
                ArrayList<ColumnsInfo> columns = repo.getColumns(metadataConn, ctrl.resultFromTable);
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
            ctrl.resultSetEditAllowed.set(false);
            return;
        }
        List<String> primaryKeys = info.primaryKeys;
        List<String> selectedCols = info.selectedColumns;
        ctrl.resultTableCols = selectedCols;
        boolean hasEditableKey = false;
        if (primaryKeys != null && !primaryKeys.isEmpty()) {
            boolean allPkColsInSelect = primaryKeys.stream()
                    .allMatch(pk -> findSelectedColumnIndexMatchingPk(selectedCols, pk) >= 0);
            if (allPkColsInSelect) {
                for (String key : primaryKeys) {
                    int columnIndex = findSelectedColumnIndexMatchingPk(selectedCols, key);
                    if (columnIndex >= 0) {
                        ctrl.resultTablePriNum.add(columnIndex);
                        markPrimaryKeyColumn(columnIndex, "PRI");
                    }
                }
                hasEditableKey = !ctrl.resultTablePriNum.isEmpty();
            }
        }
        int rowidIdx = findRowidColumnIndex(selectedCols);
        if (rowidIdx >= 0) {
            if (!hasEditableKey) {
                ctrl.resultTablePriNum.add(rowidIdx);
                hasEditableKey = true;
            }
            markPrimaryKeyColumn(rowidIdx, "ROWID");
        }
        if (!hasEditableKey) {
            ctrl.resultSetTableView.setEditable(false);
            ctrl.resultSetEditAllowed.set(false);
        }
    }

    private void markPrimaryKeyColumn(int columnIndex, String labelText) {
        ctrl.resultSetEditAllowed.set(!ctrl.sqlConnect.getReadonly());
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

    private static ConnectionService resolveConnectionService() {
        return AppContext.get(ConnectionService.class);
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

    private static void ensureNotWildcardResultColumn(String col) throws SQLException {
        if ("*".equals(col)) {
            throw new SQLException(
                    "结果集列名无法解析为物理列（例如 SELECT 中的 t.* 未展开）；请改用显式列名或重新执行查询。");
        }
    }

    /**
     * Oracle rejects {@code SET alias.col = ?} / {@code WHERE alias.col = ?} with ORA-01747; JDBC / SELECT 解析
     * 可能得到 {@code schema.table.col} 或 {@code t11.col}，UPDATE 中需使用裸列名（最后一段）。
     * 双引号标识符保持原样。
     */
    private String oracleBareColumnNameForUpdate(String col) {
        if (!isOracleDialect()) {
            return col;
        }
        if (col == null || col.isBlank()) {
            return col;
        }
        String s = col.trim();
        if (s.startsWith("\"")) {
            return s;
        }
        int idx = s.lastIndexOf('.');
        if (idx > 0 && idx < s.length() - 1) {
            return s.substring(idx + 1);
        }
        return s;
    }

    private boolean isOracleDialect() {
        String t = ctrl.sqlConnect.getDbtype();
        return t != null && "ORACLE".equalsIgnoreCase(t.trim());
    }

    /** Match SELECT list entry to PK name (Oracle: {@code t11.id} vs {@code id}). */
    private int findSelectedColumnIndexMatchingPk(List<String> selectedCols, String pkKey) {
        if (pkKey == null || pkKey.isBlank() || selectedCols == null) {
            return -1;
        }
        String pkNorm = pkKey.toLowerCase(Locale.ROOT);
        for (int i = 0; i < selectedCols.size(); i++) {
            String c = selectedCols.get(i);
            String cand = isOracleDialect() ? oracleBareColumnNameForUpdate(c) : c;
            cand = cand.toLowerCase(Locale.ROOT);
            if (cand.equals(pkNorm)) {
                return i;
            }
        }
        return -1;
    }

    private int findRowidColumnIndex(List<String> selectedCols) {
        if (selectedCols == null) {
            return -1;
        }
        for (int i = 0; i < selectedCols.size(); i++) {
            String bare = isOracleDialect() ? oracleBareColumnNameForUpdate(selectedCols.get(i)) : selectedCols.get(i);
            if ("rowid".equalsIgnoreCase(bare != null ? bare.trim() : "")) {
                return i;
            }
        }
        return -1;
    }

    /** null 提交框视为自动提交（非手动）。 */
    private static boolean isManualCommitMode(ChoiceBox<?> commitmode) {
        return commitmode != null && commitmode.getSelectionModel().getSelectedIndex() == 1;
    }

    /** null 属性不追加（调用方可用占位 {@link SimpleStringProperty} 表示 ""）。 */
    private static void appendPendingTransactionSql(SimpleStringProperty sqlTransactionText, String fragment) {
        if (sqlTransactionText == null || fragment == null) {
            return;
        }
        String cur = sqlTransactionText.get();
        sqlTransactionText.set((cur == null ? "" : cur) + fragment);
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
