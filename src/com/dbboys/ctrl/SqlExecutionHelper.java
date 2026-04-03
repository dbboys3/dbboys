package com.dbboys.ctrl;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.app.AppContext;
import com.dbboys.customnode.CustomResultsetTab;
import com.dbboys.impl.DialectServices;
import com.dbboys.i18n.I18n;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.util.*;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.Sql;
import com.dbboys.vo.UpdateResult;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Tab;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.CountDownLatch;

public class SqlExecutionHelper {
    private static final Logger log = LogManager.getLogger(SqlExecutionHelper.class);

    final SqlTabController ctrl;
    private final DatabasePlatformResolver platformResolver;

    public SqlExecutionHelper(SqlTabController ctrl) {
        this.ctrl = ctrl;
        this.platformResolver = resolvePlatformResolver();
    }

    public String resolveSqlText(boolean allowRefresh) {
        if (allowRefresh && ctrl.isSqlRefresh && ctrl.currentResultSetTabController != null
                && ctrl.currentResultSetTabController.lastSqlTextField.getTooltip() != null) {
            return ctrl.currentResultSetTabController.lastSqlTextField.getTooltip().getText();
        }
        if (ctrl.sqlEditCodeArea.getSelectedText().isEmpty()) {
            return ctrl.sqlEditCodeArea.getText();
        }
        return ctrl.sqlEditCodeArea.getSelectedText();
    }

    public void cancelCurrentExecution() {
        if (ctrl.sqlTask != null) {
            ctrl.sqlTask.cancel();
        }
        try {
            if (ctrl.sqlStatement != null) {
                ctrl.sqlStatement.cancel();
                ctrl.sqlStatement = null;
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        }
        if (ctrl.currentResultSetTabController != null) {
            ctrl.currentResultSetTabController.cancel();
        }
        if (ctrl.activeResultSetController != null && ctrl.activeResultSetController != ctrl.currentResultSetTabController) {
            ctrl.activeResultSetController.cancel();
        }
        if (ctrl.resultsetTabPane != null) {
            for (Tab tab : ctrl.resultsetTabPane.getTabs()) {
                if (tab instanceof CustomResultsetTab) {
                    ((CustomResultsetTab) tab).resultSetTabController.cancel();
                }
            }
        }
    }

    public int getWhitespaceLength(String str) {
        if (str == null || str.isEmpty()) {
            return 0;
        }
        int length = 0;
        for (int i = 0; i < str.length(); i++) {
            if (Character.isWhitespace(str.charAt(i))) {
                length++;
            } else {
                break;
            }
        }
        return length;
    }

    // --- createExecuteSqlTask and sub-methods ---

    public Task<Void> createExecuteSqlTask() {
        if (ctrl.sqlEditCodeArea.getSelectedText().isEmpty()) {
            ctrl.sqlSelectionRange[0] = 0;
            ctrl.sqlSelectionRange[1] = 0;
        } else {
            ctrl.sqlSelectionRange[0] = ctrl.sqlEditCodeArea.getSelection().getStart();
            ctrl.sqlSelectionRange[1] = ctrl.sqlEditCodeArea.getSelection().getEnd();
        }

        ctrl.sqlUsedTime = 0;
        ctrl.isSingleSql = SqlParserUtil.isSingleStatement(ctrl.sqlText);
        ctrl.clearUpdateResults();
        ctrl.sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                ctrl.sqlExecutionSuccess = false;
                ctrl.sqlExecutionResult = "";
                updateMessage(I18n.t("sql.exec.running"));

                Sql sql = new Sql();
                ctrl.sqlStatementCount = 0;
                for (SqlParserUtil.Segment segment : SqlParserUtil.split(ctrl.sqlText)) {
                    String sqlChunk = segment.getText();
                    if (SqlParserUtil.sqlContrainMoreThanOneCommit(sqlChunk)) {
                        ctrl.isSingleSql = false;
                    }

                    Boolean sqlContainsCommit = false;
                    do {
                        if (isCancelled()) {
                            return null;
                        }
                        sql = SqlParserUtil.modifySql(sql, sqlChunk);
                        if (sql.getSqlEnd() && SqlParserUtil.isExecutableStatement(sql.getSqlstr())) {
                            ctrl.sqlParamList.clear();
                            ctrl.sqlExe = sql.getSqlstr();
                            ctrl.sqlStatementCount++;
                            highlightCurrentSegment(segment, sql);

                            ctrl.updateResult = new UpdateResult();

                            if (ctrl.isSingleSql && sql.getSqlType().equals("SELECT")) {
                                executeSingleSelect(sdf, this);
                                if (isCancelled()) return null;
                            } else if (ctrl.sqlConnect.getReadonly()) {
                                Platform.runLater(() -> {
                                    AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("sql.error.readonly_select_only"));
                                });
                            } else {
                                executeNonSelect(sdf, sql, this);
                                if (isCancelled()) return null;
                            }

                            if (ctrl.sqlExecutionSuccess) {
                                handlePostExecutionSuccess(sql);
                            } else if (shouldClearTransactionAfterDdl(ctrl.sqlExe)) {
                                ctrl.sqlTransactionText.set("");
                            }

                            sql.setSqlStr("");
                            sql.setSqlEnd(false);
                            sql.setSqlType("");
                        }
                        sqlChunk = "";
                        sqlContainsCommit = SqlParserUtil.sqlContrainCommit(sql.getSqlRemainder());
                    } while (sqlContainsCommit);
                }
                return null;
            }
        };

        ctrl.sqlTask.setOnSucceeded(event1 -> {
            ctrl.isSqlRefresh = false;
            ctrl.finishExecution(ctrl.sqlSelectionRange[0], ctrl.sqlSelectionRange[1]);
            if (ctrl.isSingleSql && ctrl.resultSetVBox.isVisible()) {
                ctrl.currentResultSetTabController.getPrimaryKeys(ctrl.sqlExe);
            }
        });
        ctrl.sqlTask.setOnCancelled(event1 -> {
            ctrl.isSqlRefresh = false;
            ctrl.finishExecution(ctrl.sqlSelectionRange[0], ctrl.sqlSelectionRange[1]);
        });
        ctrl.sqlTask.setOnFailed(event1 -> {
            ctrl.isSqlRefresh = false;
        });

        return ctrl.sqlTask;
    }

    private void highlightCurrentSegment(SqlParserUtil.Segment segment, Sql sql) {
        String finalsqlExe = ctrl.sqlExe;
        int finalI = segment.getEndIndex();
        if (sql.getSqlRemainder() != null && !sql.getSqlRemainder().isEmpty()) {
            int remaindersize = sql.getSqlRemainder().length();
            int sqllength = finalsqlExe.length();
            int whitespacelength = getWhitespaceLength(finalsqlExe);
            if (!ctrl.isSqlRefresh) {
                ctrl.selectRangeAndFollow(
                        ctrl.sqlSelectionRange[0] + finalI + 1 - remaindersize - sqllength + whitespacelength,
                        ctrl.sqlSelectionRange[0] + finalI + 1 - remaindersize);
            }
        } else {
            int sqllength = finalsqlExe.length();
            int whitespacelength = getWhitespaceLength(finalsqlExe);
            if (!ctrl.isSqlRefresh) {
                ctrl.selectRangeAndFollow(
                        ctrl.sqlSelectionRange[0] + finalI + 1 - sqllength + whitespacelength,
                        ctrl.sqlSelectionRange[0] + finalI + 1);
            }
        }
    }

    private void executeSingleSelect(SimpleDateFormat sdf, Task<?> task) {
        try {
            ctrl.sqlStartTime = System.currentTimeMillis();
            ctrl.activeResultSetController = ctrl.currentResultSetTabController;
            ctrl.sqlUsedTime = ctrl.currentResultSetTabController.executeSelect(
                    ctrl.sqlExe, ctrl.sqlTask, true, ctrl.sqlTransactionText, ctrl.sqlCommitModeChoiceBox);
            ctrl.sqlExecutionSuccess = true;
            ctrl.sqlExecutionResult = I18n.t("sql.exec.success");
            ctrl.setResultsetVisible(true);
            if (task.isCancelled()) {
                ctrl.setResultsetVisible(false);
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            ctrl.setResultsetVisible(false);
            ctrl.sqlExecutionSuccess = false;
            ctrl.sqlExecutionResult = "[" + e.getErrorCode() + "]" + e.getMessage();
            if (e.getErrorCode() == -254) {
                try {
                    if (ctrl.currentResultSetTabController.sqlStatement != null)
                        ctrl.currentResultSetTabController.sqlStatement.close();
                    if (ctrl.currentResultSetTabController.sqlCstmt != null)
                        ctrl.currentResultSetTabController.sqlCstmt.close();
                } catch (SQLException ex) {
                    log.error(ex.getMessage(), ex);
                }
            }
            Platform.runLater(() -> {
                if (SqlErrorUtil.isDisconnectError(ctrl.sqlConnect, e)) {
                    ctrl.connectionDisconnected();
                } else {
                    AlertUtil.CustomAlert(I18n.t("common.error"), "[" + e.getErrorCode() + "]" + e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            ctrl.activeResultSetController = null;
            ctrl.sqlParamList = ctrl.currentResultSetTabController.sqlParamList;
        }
        ctrl.sqlEndTime = System.currentTimeMillis();
        if (task.isCancelled()) {
            ctrl.sqlExecutionSuccess = false;
            ctrl.sqlExecutionResult = I18n.t("sql.exec.cancelled");
        }
        ctrl.sqlTotalTime += ctrl.sqlUsedTime;
        ctrl.updateResult.setResult(ctrl.sqlExecutionResult);
        ctrl.updateResult.setDatabase(ctrl.sqlConnect.getDatabase());
        ctrl.updateResult.setUpdateSql(ctrl.sqlExe.trim());
        ctrl.updateResult.setStartTime(sdf.format(ctrl.sqlStartTime));
        ctrl.updateResult.setEndTime(sdf.format(ctrl.sqlEndTime));
        ctrl.updateResult.setElapsedTime(ctrl.i18nHelper.formatElapsedSeconds(ctrl.sqlUsedTime));
        ctrl.updateResult.setAffectedRows(0);
        ctrl.updateResult.setMark(ctrl.i18nHelper.buildExecutionMark());
        ctrl.addUpdateResult(ctrl.updateResult, true);
    }

    private void executeNonSelect(SimpleDateFormat sdf, Sql sql, Task<?> task) throws InterruptedException, java.io.IOException {
        ctrl.setResultsetVisible(false);
        ctrl.setExplainVisible(false);
        ctrl.sqlStartTime = System.currentTimeMillis();
        ctrl.sqlUsedTime = 0;
        ctrl.sqlAffect = 0;
        ctrl.sqlExecutionSuccess = true;
        ctrl.sqlExecutionResult = I18n.t("sql.exec.success");
        ctrl.updateResult.setDatabase(ctrl.sqlConnect.getDatabase());

        if (sql.getSqlType().equals("SELECT") || sql.getSqlType().equals("CALL")) {
            executeSelectOrCallInBatch(sdf, sql, task);
        } else {
            executeUpdateStatement(task);
            ctrl.sqlEndTime = System.currentTimeMillis();
        }

        if (sql.getSqlType().equals("SELECT") || sql.getSqlType().equals("CALL")) {
            // sqlUsedTime already set inside executeSelectOrCallInBatch
        } else {
            ctrl.sqlUsedTime = ctrl.sqlEndTime - ctrl.sqlStartTime;
        }
        if (task.isCancelled()) {
            ctrl.sqlExecutionSuccess = false;
            ctrl.sqlExecutionResult = I18n.t("sql.exec.cancelled");
        }
        ctrl.sqlTotalTime += ctrl.sqlUsedTime;
        ctrl.updateResult.setResult(ctrl.sqlExecutionResult);
        ctrl.updateResult.setConnectId(ctrl.sqlConnect.getId());
        ctrl.updateResult.setStartTime(sdf.format(ctrl.sqlStartTime));
        ctrl.updateResult.setEndTime(sdf.format(ctrl.sqlEndTime));
        ctrl.updateResult.setElapsedTime(ctrl.i18nHelper.formatElapsedSeconds(ctrl.sqlUsedTime));
        ctrl.updateResult.setAffectedRows(ctrl.sqlAffect);
        ctrl.updateResult.setUpdateSql(ctrl.sqlExe.trim());
        ctrl.updateResult.setMark(ctrl.i18nHelper.buildExecutionMark());

        CountDownLatch uiLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ctrl.updateResults.add(ctrl.updateResult);
            uiLatch.countDown();
        });
        try {
            uiLatch.await();
        } catch (InterruptedException ignored) {
        }
    }

    private void executeSelectOrCallInBatch(SimpleDateFormat sdf, Sql sql, Task<?> task) throws InterruptedException, java.io.IOException {
        ctrl.customResultsetTab = new CustomResultsetTab(ctrl.sqlConnect, ctrl.sqlExecuteProcessStackPane);
        ctrl.activeResultSetController = ctrl.customResultsetTab.resultSetTabController;
        ctrl.sqlStartTime = System.currentTimeMillis();
        try {
            if (sql.getSqlType().equals("SELECT")) {
                ctrl.sqlUsedTime = ctrl.customResultsetTab.resultSetTabController.executeSelect(ctrl.sqlExe, ctrl.sqlTask, false, null, null);
            } else {
                ctrl.sqlUsedTime = ctrl.customResultsetTab.resultSetTabController.executeCall(ctrl.sqlExe, ctrl.sqlTask, false);
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            ctrl.sqlExecutionSuccess = false;
            ctrl.sqlExecutionResult = "[" + e.getErrorCode() + "]" + e.getMessage();
        } finally {
            ctrl.activeResultSetController = null;
            ctrl.sqlParamList = ctrl.customResultsetTab.resultSetTabController.sqlParamList;
        }

        ctrl.sqlEndTime = System.currentTimeMillis();
        if (task.isCancelled()) {
            ctrl.sqlExecutionSuccess = false;
            ctrl.sqlExecutionResult = I18n.t("sql.exec.cancelled");
        }
        if (ctrl.sqlExecutionSuccess && (sql.getSqlType().equals("SELECT") || ctrl.customResultsetTab.resultSetTabController.callHasResultSet)) {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                String baseTitle = I18n.t("sql.tab.resultset_prefix");
                int nextNumber = 1;
                while (true) {
                    ctrl.newResultsetTabName = baseTitle + nextNumber;
                    boolean exists = ctrl.resultsetTabPane.getTabs().stream()
                            .anyMatch(tab -> tab.getText().equals(ctrl.newResultsetTabName));
                    if (!exists) break;
                    nextNumber++;
                }
                ctrl.sqlExecutionResult += (I18n.t("sql.mark.sep") + ctrl.newResultsetTabName);
                ctrl.customResultsetTab.setText(ctrl.newResultsetTabName);
                ctrl.resultsetTabPane.getTabs().add(ctrl.customResultsetTab);
                latch.countDown();
            });
            latch.await();
        }
    }

    private void executeUpdateStatement(Task<?> task) {
        try {
            ctrl.sqlStatement = ctrl.sqlConnect.getConn().prepareStatement(ctrl.sqlExe);
            ctrl.parameterMetaData = ctrl.sqlStatement.getParameterMetaData();
            int paramCount = ctrl.parameterMetaData.getParameterCount();
            if (paramCount > 0) {
                CountDownLatch latch = new CountDownLatch(1);
                Platform.runLater(() -> {
                    ctrl.sqlParamList = PopupWindowUtil.openParamWindow(paramCount);
                    latch.countDown();
                });
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ctrl.sqlStartTime = System.currentTimeMillis();
                if (ctrl.sqlParamList.size() > 0) {
                    for (int z = 1; z <= ctrl.sqlParamList.size(); z++) {
                        ctrl.sqlStatement.setObject(z, ctrl.sqlParamList.get(z - 1));
                    }
                }
            }
            ctrl.sqlAffect = ctrl.sqlStatement.executeUpdate();
            ctrl.sqlExecutionSuccess = true;
            ctrl.sqlExecutionResult = I18n.t("sql.exec.success");
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            boolean requiresRecovery = requiresSessionRecovery(e);
            if (ctrl.isSingleSql) {
                Platform.runLater(() -> {
                    if (SqlErrorUtil.isDisconnectError(ctrl.sqlConnect, e)) {
                        ctrl.connectionDisconnected();
                    } else if (requiresRecovery) {
                        try {
                            sqlexeRepository().recoverSession(ctrl.sqlConnect.getConn(), ctrl.sqlConnect.getDatabase());
                        } catch (Exception ex) {
                            log.error(ex.getMessage(), ex);
                        }
                        AlertUtil.CustomAlert(I18n.t("common.error"), "[" + e.getErrorCode() + "]" + e.getMessage());
                    } else {
                        AlertUtil.CustomAlert(I18n.t("common.error"), "[" + e.getErrorCode() + "]" + e.getMessage());
                    }
                });
            }
            ctrl.sqlExecutionSuccess = false;
            ctrl.sqlExecutionResult = "[" + e.getErrorCode() + "]" + e.getMessage();
        }
    }

    private void handlePostExecutionSuccess(Sql sql) throws SQLException {
        if (!sql.getSqlType().equals("SELECT")) {
            LocalDbRepository.saveSqlHistory(ctrl.updateResult);

            if (sql.getSqlType().startsWith("DATABASE")) {
                ctrl.sqlConnect.setDatabase(sql.getSqlType().split(" ")[1]);
                Platform.runLater(() -> {
                    Database db = new Database();
                    db.setName(ctrl.sqlConnect.getDatabase());
                    ctrl.sqlDbChoiceBox.setValue(db);
                });
            }

            String detectedSqlMode = detectSqlMode(ctrl.sqlExe);
            if (detectedSqlMode != null && ctrl.sqlSqlModeChoiceBox.getItems().contains(detectedSqlMode)) {
                Platform.runLater(() -> ctrl.sqlSqlModeChoiceBox.setValue(detectedSqlMode));
            }

            trackTransactionState(sql);
        }
    }

    private void trackTransactionState(Sql sql) throws SQLException {
        String upperSql = ctrl.sqlExe.toUpperCase().trim();
        String sqlWithSemicolon = ctrl.sqlExe.endsWith(";") ? ctrl.sqlExe.trim() + "\n" : ctrl.sqlExe.trim() + ";\n";

        if (ctrl.sqlConnect.getConn().getAutoCommit()) {
            if (upperSql.startsWith("BEGIN WORK")) {
                ctrl.sqlTransactionText.set(sqlWithSemicolon);
            } else if (upperSql.startsWith("COMMIT") || upperSql.startsWith("ROLLBACK")) {
                ctrl.sqlTransactionText.set("");
            } else if (!ctrl.sqlTransactionText.get().isEmpty()) {
                ctrl.sqlTransactionText.set(ctrl.sqlTransactionText.get() + sqlWithSemicolon);
            }
        } else if (autoCommitsDdl()) {
            ctrl.sqlTransactionText.set(ctrl.sqlTransactionText.get() + sqlWithSemicolon);
            if (isDdlStatement(upperSql)
                    || upperSql.startsWith("COMMIT") || upperSql.startsWith("ROLLBACK")) {
                ctrl.sqlTransactionText.set("");
            }
        } else {
            ctrl.sqlTransactionText.set(ctrl.sqlTransactionText.get() + sqlWithSemicolon);
            if (upperSql.startsWith("COMMIT") || upperSql.startsWith("ROLLBACK")) {
                ctrl.sqlTransactionText.set("");
            }
        }
    }

    // --- createExplainTask ---

    public Task<Void> createExplainTask() {
        ctrl.sqlExecuteProcessStackPane.setVisible(true);
        ctrl.sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (!SqlParserUtil.isSingleStatement(ctrl.sqlText)) {
                    Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("sql.explain.single_only")));
                } else {
                    try {
                        String explainText = sqlexeRepository().explain(ctrl.sqlConnect.getConn(), ctrl.sqlText);
                        Platform.runLater(() -> {
                            if (explainText != null && !"Error 0".equals(explainText)) {
                                ctrl.showExplainText(explainText);
                            } else {
                                AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("sql.explain.not_supported"));
                            }
                        });
                    } catch (UnsupportedOperationException e) {
                        Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("sql.explain.not_supported")));
                    } catch (SQLException e) {
                        if (SqlErrorUtil.isDisconnectError(ctrl.sqlConnect, e)) {
                            ctrl.connectionDisconnected();
                        } else {
                            Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), "[" + e.getErrorCode() + "]" + e.getMessage()));
                            log.error(e.getMessage(), e);
                        }
                    }
                }
                return null;
            }
        };

        ctrl.sqlTask.setOnSucceeded(event1 -> ctrl.hideExecuteProcess());
        ctrl.sqlTask.setOnCancelled(event1 -> ctrl.hideExecuteProcess());
        ctrl.sqlTask.setOnFailed(event1 -> ctrl.hideExecuteProcess());

        return ctrl.sqlTask;
    }

    // --- createSqlModeTask ---

    public Task<Void> createSqlModeTask(Connect sqlConnect, String sqlmode) {
        ctrl.sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    platformResolver.sqlexe(sqlConnect).changeSqlMode(sqlConnect.getConn(), sqlmode);
                } catch (UnsupportedOperationException e) {
                    Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("sql.explain.not_supported")));
                    throw new Exception("ERROR", e);
                } catch (SQLException e) {
                    if (SqlErrorUtil.isDisconnectError(sqlConnect, e)) {
                        ctrl.connectionDisconnected();
                    } else {
                        Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), "[" + e.getErrorCode() + "]" + e.getMessage()));
                        log.error(e.getMessage(), e);
                    }
                    throw new Exception("ERROR");
                }
                return null;
            }
        };
        return ctrl.sqlTask;
    }

    private SqlexeRepository sqlexeRepository() {
        return platformResolver.sqlexe(ctrl.sqlConnect);
    }

    private DatabasePlatformResolver resolvePlatformResolver() {
        try {
            return AppContext.get(DatabasePlatformResolver.class);
        } catch (IllegalStateException e) {
            return DialectServices.createDefault();
        }
    }

    private String detectSqlMode(String sql) {
        try {
            return sqlexeRepository().detectSqlMode(sql);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean requiresSessionRecovery(SQLException e) {
        try {
            return sqlexeRepository().requiresSessionRecovery(e);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean autoCommitsDdl() {
        try {
            return sqlexeRepository().autoCommitsDdl(ctrl.sqlSqlModeChoiceBox.getValue());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldClearTransactionAfterDdl(String sql) {
        return autoCommitsDdl() && isDdlStatement(sql == null ? "" : sql.trim().toUpperCase());
    }

    private boolean isDdlStatement(String upperSql) {
        return upperSql.startsWith("ALTER")
                || upperSql.startsWith("CREATE")
                || upperSql.startsWith("DROP")
                || upperSql.startsWith("TRUNCATE");
    }
}
