package com.dbboys.ui.controller;
import com.dbboys.ui.util.ReadOnlyGuard;

import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.SqlModeCapability;
import com.dbboys.core.SqlParser;
import com.dbboys.core.SqlexeRepository;
import com.dbboys.app.AppContext;
import com.dbboys.ui.component.CustomResultsetTab;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.util.*;
import com.dbboys.ui.dialog.PopupWindowUtil;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.model.Connect;
import com.dbboys.model.Database;
import com.dbboys.model.Sql;
import com.dbboys.model.UpdateResult;
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
        if (!ctrl.sqlEditCodeArea.ensureExecuteTargetSelected()) {
            return "";
        }
        return ctrl.sqlEditCodeArea.getSelectedText();
    }

    public void cancelCurrentExecution() {
        if (ctrl.sqlTask != null) {
            //必须设置为false，否则oracle会中断连接
            ctrl.sqlTask.cancel(false);
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
        ctrl.sqlEditCodeArea.clearErrorLines();
        SqlParser platformParser = platformResolver.requirePlatform(ctrl.sqlConnect).parser();
        ctrl.isSingleSql = platformParser.isSingleStatement(ctrl.sqlText);
        ctrl.clearUpdateResults();
        ctrl.sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // For GBase 8S, resolve parser per-statement based on current sqlmode
                SqlParser parser = resolveGbaseParser(platformParser, ctrl.sqlSqlModeChoiceBox.getValue());
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                ctrl.sqlExecutionSuccess = false;
                ctrl.sqlExecutionResult = "";
                updateMessage(I18n.t("sql.exec.running"));

                final Sql[] currentSql = {new Sql()};
                ctrl.sqlStatementCount = 0;
                SqlParserUtil.forEachSegment(ctrl.sqlText, segment -> {
                    String sqlChunk = segment.getText();
                    if (parser.hasMoreThanOneStatement(sqlChunk)) {
                        ctrl.isSingleSql = false;
                    }

                    Boolean sqlContainsCommit = false;
                    do {
                        if (isCancelled()) {
                            return false;
                        }
                        currentSql[0] = parser.modifySql(currentSql[0], sqlChunk);
                        if (currentSql[0].getSqlEnd() && SqlParserUtil.isExecutableStatement(currentSql[0].getSqlstr())) {
                            ctrl.sqlParamList.clear();
                            // Skip executing the naked custom delimiter (e.g. "$$" left from
                            // "DELIMITER $$\nselect * from t;$$\nDELIMITER ;")
                            if (isJustDelimiter(currentSql[0].getSqlstr())) {
                                currentSql[0].setSqlStr("");
                                currentSql[0].setSqlEnd(false);
                                currentSql[0].setSqlType("");
                                sqlChunk = "";
                                sqlContainsCommit = parser.hasMoreStatements(currentSql[0].getSqlRemainder());
                                continue;
                            }
                            ctrl.sqlExe = stripTrailingSemicolon(currentSql[0].getSqlstr());
                            ctrl.sqlStatementCount++;
                            int[] statementRange = highlightCurrentSegment(segment, currentSql[0]);

                            ctrl.updateResult = new UpdateResult();
                            ctrl.lastSqlException = null;

                            if (!ReadOnlyGuard.canExecuteSql(ctrl.sqlConnect, currentSql[0].getSqlstr())) {
                                blockReadOnlyStatement(sdf);
                            } else if (ctrl.isSingleSql && currentSql[0].getSqlType().equals("SELECT")) {
                                executeSingleSelect(sdf, this);
                                if (isCancelled()) return false;
                            } else {
                                executeNonSelect(sdf, currentSql[0], this);
                                if (isCancelled()) return false;
                            }

                            if (ctrl.sqlExecutionSuccess) {
                                handlePostExecutionSuccess(currentSql[0]);
                            } else {
                                if (!isCancelled()) {
                                    markStatementErrorLines(statementRange);
                                }
                                if (shouldClearTransactionAfterDdl(ctrl.sqlExe)) {
                                    ctrl.sqlTransactionText.set("");
                                }
                            }

                            currentSql[0].setSqlStr("");
                            currentSql[0].setSqlEnd(false);
                            currentSql[0].setSqlType("");
                        }
                        sqlChunk = "";
                        sqlContainsCommit = parser.hasMoreStatements(currentSql[0].getSqlRemainder());
                    } while (sqlContainsCommit);
                    return true;
                });
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

    private void blockReadOnlyStatement(SimpleDateFormat sdf) {
        ctrl.setResultsetVisible(false);
        ctrl.setExplainVisible(false);
        ctrl.sqlStartTime = System.currentTimeMillis();
        ctrl.sqlEndTime = ctrl.sqlStartTime;
        ctrl.sqlUsedTime = 0;
        ctrl.sqlExecutionSuccess = false;
        ctrl.sqlExecutionResult = ReadOnlyGuard.message();
        ctrl.updateResult.setResult(ctrl.sqlExecutionResult);
        ctrl.updateResult.setConnectId(ctrl.sqlConnect.getId());
        ctrl.updateResult.setDatabase(resolveEffectiveDatabase());
        ctrl.updateResult.setUpdateSql(ctrl.sqlExe == null ? "" : ctrl.sqlExe.trim());
        ctrl.updateResult.setStartTime(sdf.format(ctrl.sqlStartTime));
        ctrl.updateResult.setEndTime(sdf.format(ctrl.sqlEndTime));
        ctrl.updateResult.setElapsedTime(ctrl.i18nHelper.formatElapsedSeconds(0));
        ctrl.updateResult.setAffectedRows(0);
        ctrl.updateResult.setMark(ctrl.i18nHelper.buildExecutionMark());
        ctrl.addUpdateResult(ctrl.updateResult, ctrl.isSingleSql);
        Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), ctrl.sqlExecutionResult));
    }

    private void markStatementErrorLines(int[] range) {
        if (range == null) {
            return;
        }
        int errorOffset = resolveErrorOffset(range);
        Platform.runLater(() -> ctrl.sqlEditCodeArea.markErrorLine(errorOffset));
    }

    /**
     * Locates the editor offset of the failing line inside the statement range.
     * Prefers the exact position reported by the driver/database, then line
     * numbers parsed from the message; falls back to the statement's first line.
     */
    private int resolveErrorOffset(int[] range) {
        String executedText = ctrl.sqlText == null ? "" : ctrl.sqlText;
        String sentSql = ctrl.sqlExe == null ? "" : ctrl.sqlExe;
        // The sent statement (ctrl.sqlExe) may be trimmed relative to the raw
        // parsed text, so locate its exact start inside the executed text.
        int base = range[0];
        if (!sentSql.isEmpty()) {
            int relBase = Math.max(0, range[0] - ctrl.sqlSelectionRange[0]);
            int idx = executedText.indexOf(sentSql, relBase);
            if (idx >= 0) {
                base = ctrl.sqlSelectionRange[0] + idx;
            }
        }
        int maxTarget = Math.max(range[1] - 1, base);
        SQLException error = ctrl.lastSqlException;

        // Informix/GBase: the driver stores the byte offset of the error inside
        // the sent statement (SQLCA sqlerrd[4]) for every error response, not
        // only syntax errors. Use it whenever it points inside the statement.
        if (error != null) {
            Integer byteOffset = queryDriverStatementErrorOffset();
            log.info("SQL error offset: code={}, offset={}, sql={}",
                    error.getErrorCode(), byteOffset, abbreviate(sentSql));
            if (byteOffset != null && byteOffset >= 0 && byteOffset <= utf8ByteLength(sentSql)) {
                int charIndex = byteOffsetToCharIndex(sentSql, byteOffset);
                return clamp(base + charIndex, base, maxTarget);
            }
        }

        // Oracle positions are relative to the original SQL held by the driver.
        OracleErrorInfo oracleError = queryOracleErrorInfo(error);
        if (oracleError != null && oracleError.position() > 0) {
            int oracleTokenIndex = extractErrorTokenIndex(sentSql, ctrl.sqlExecutionResult);
            if (oracleTokenIndex >= 0) {
                return clamp(base + oracleTokenIndex, base, maxTarget);
            }
            int oracleBase = locateSqlSourceStart(
                    executedText,
                    oracleError.originalSql(),
                    base,
                    ctrl.sqlSelectionRange[0]
            );
            return clamp(oracleBase + oracleError.position(), base, maxTarget);
        }

        // PostgreSQL: ServerErrorMessage carries a 1-based character position.
        Integer charPosition = queryPostgresqlErrorPosition(error);
        if (charPosition != null && charPosition > 0) {
            return clamp(base + charPosition - 1, base, maxTarget);
        }

        // SQL Server often reports the statement start line, so quoted column,
        // object, or near-token text is more precise when available.
        int errorTokenIndex = extractErrorTokenIndex(sentSql, ctrl.sqlExecutionResult);
        if (errorTokenIndex >= 0) {
            return clamp(base + errorTokenIndex, base, maxTarget);
        }
        Integer sqlServerLine = querySqlServerErrorLine(error);
        if (sqlServerLine != null && sqlServerLine > 0) {
            return offsetForLineInStatement(sentSql, base, sqlServerLine, maxTarget);
        }

        // MySQL/Dameng style messages carry a line number relative to the
        // start of the sent statement.
        int lineInStatement = extractErrorLineInStatement(ctrl.sqlExecutionResult);
        int relTarget = Math.max(0, base - ctrl.sqlSelectionRange[0]);
        int relEnd = Math.max(0, range[1] - ctrl.sqlSelectionRange[0]);
        for (int i = 1; i < lineInStatement; i++) {
            int nl = executedText.indexOf('\n', relTarget);
            if (nl < 0 || nl >= relEnd) {
                return base;
            }
            relTarget = nl + 1;
        }
        if (lineInStatement > 0) {
            return clamp(ctrl.sqlSelectionRange[0] + relTarget, base, maxTarget);
        }

        return base;
    }

    private static int offsetForLineInStatement(String sentSql,
                                                int base,
                                                int lineNumber,
                                                int maxTarget) {
        if (lineNumber <= 1 || sentSql == null || sentSql.isEmpty()) {
            return base;
        }
        int cursor = 0;
        for (int line = 1; line < lineNumber; line++) {
            int newline = sentSql.indexOf('\n', cursor);
            if (newline < 0) {
                return base;
            }
            cursor = newline + 1;
        }
        return clamp(base + cursor, base, maxTarget);
    }

    /**
     * Finds a quoted column/object/token in a driver message and maps it back
     * to the sent SQL, or returns -1 when no token can be located.
     */
    private static int extractErrorTokenIndex(String sentSql, String executionResult) {
        if (sentSql == null || sentSql.isEmpty() || executionResult == null) {
            return -1;
        }
        java.util.regex.Matcher matcher = QUOTED_ERROR_TOKEN_PATTERN.matcher(executionResult);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String token = matcher.group(group);
                if (token == null || token.isBlank()) {
                    continue;
                }
                int index = indexOfIgnoreCase(sentSql, token.trim());
                if (index >= 0) {
                    return index;
                }
                String unquoted = token.replace("[", "").replace("]", "").trim();
                index = indexOfIgnoreCase(sentSql, unquoted);
                if (index >= 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String text, String token) {
        if (text == null || token == null || token.isEmpty() || token.length() > text.length()) {
            return -1;
        }
        int limit = text.length() - token.length();
        for (int i = 0; i <= limit; i++) {
            if (text.regionMatches(true, i, token, 0, token.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int locateSqlSourceStart(String executedText,
                                            String originalSql,
                                            int fallbackBase,
                                            int searchStart) {
        if (executedText == null || originalSql == null || originalSql.isEmpty()) {
            return fallbackBase;
        }
        int start = Math.max(0, Math.min(searchStart, executedText.length()));
        int index = executedText.indexOf(originalSql, start);
        if (index >= 0) {
            return index;
        }
        String trimmed = originalSql.strip();
        if (!trimmed.isEmpty()) {
            index = executedText.indexOf(trimmed, start);
            if (index >= 0) {
                return index;
            }
        }
        return fallbackBase;
    }

    private static final java.util.regex.Pattern QUOTED_ERROR_TOKEN_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?s)[\"'`]([^\"'`\\r\\n]{1,300})[\"'`]"
                            + "|“([^”\\r\\n]{1,300})”"
                            + "|‘([^’\\r\\n]{1,300})’"
                            + "|\\[([^\\]\\r\\n]{1,300})\\]");

    /**
     * Calls {@code getSQLStatementOffset()} on the JDBC connection when the driver
     * supports it (Informix/GBase {@code IfmxConnection}). Reflection keeps the
     * compile classpath free of driver classes loaded from extlib at runtime.
     */
    private Integer queryDriverStatementErrorOffset() {
        try {
            java.sql.Connection conn = ctrl.sqlConnect.getConn();
            Object target = findOffsetCapableConnection(conn);
            if (target == null) {
                return null;
            }
            Object value = target.getClass().getMethod("getSQLStatementOffset").invoke(target);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object findOffsetCapableConnection(Object conn) {
        if (conn == null) {
            return null;
        }
        if (hasPublicMethod(conn.getClass(), "getSQLStatementOffset")) {
            return conn;
        }
        // SSH tunnel connections are wrapped; reach the underlying driver connection.
        try {
            java.lang.reflect.Field delegate = conn.getClass().getDeclaredField("delegate");
            delegate.setAccessible(true);
            Object inner = delegate.get(conn);
            if (inner != null && hasPublicMethod(inner.getClass(), "getSQLStatementOffset")) {
                return inner;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Reads the 1-based character position from a PostgreSQL PSQLException, if any. */
    private static Integer queryPostgresqlErrorPosition(SQLException error) {
        if (error == null) {
            return null;
        }
        try {
            Object serverError = error.getClass().getMethod("getServerErrorMessage").invoke(error);
            if (serverError == null) {
                return null;
            }
            Object position = serverError.getClass().getMethod("getPosition").invoke(serverError);
            return position instanceof Integer ? (Integer) position : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Reads the 1-based error line exposed by SQL Server JDBC, if available. */
    private static Integer querySqlServerErrorLine(SQLException error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            try {
                Object serverError = current.getClass()
                        .getMethod("getSQLServerError")
                        .invoke(current);
                if (serverError == null) {
                    continue;
                }
                Object lineNumber = serverError.getClass()
                        .getMethod("getLineNumber")
                        .invoke(serverError);
                if (lineNumber instanceof Number number && number.longValue() > 0) {
                    return number.intValue();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Reads Oracle's zero-based character position and the exact SQL text used
     * as its reference.
     */
    private static OracleErrorInfo queryOracleErrorInfo(SQLException error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            try {
                Object position = current.getClass()
                        .getMethod("getErrorPosition")
                        .invoke(current);
                if (position instanceof Number number && number.intValue() > 0) {
                    String originalSql = null;
                    try {
                        Object value = current.getClass()
                                .getMethod("getOriginalSql")
                                .invoke(current);
                        originalSql = value instanceof String text ? text : null;
                    } catch (Exception ignored) {
                    }
                    return new OracleErrorInfo(number.intValue(), originalSql);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private record OracleErrorInfo(int position, String originalSql) {
    }

    private static boolean hasPublicMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Maps a UTF-8 byte offset to a char index inside {@code text}. */
    private static int byteOffsetToCharIndex(String text, int byteOffset) {
        if (text == null || byteOffset <= 0) {
            return 0;
        }
        int bytes = 0;
        int i = 0;
        while (i < text.length() && bytes < byteOffset) {
            int cp = text.codePointAt(i);
            bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            i += Character.charCount(cp);
        }
        return i;
    }

    private static int utf8ByteLength(String text) {
        return text == null ? 0 : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "...";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Returns the 1-based error line within the statement, or 0 when unknown. */
    private static int extractErrorLineInStatement(String executionResult) {
        if (executionResult == null || executionResult.isEmpty()) {
            return 0;
        }
        java.util.regex.Matcher matcher = ERROR_LINE_PATTERN.matcher(executionResult);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static final java.util.regex.Pattern ERROR_LINE_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:at\\s+line|line\\s*[:：]?|第)\\s*(\\d+)\\s*(?:行)?");

    private int[] highlightCurrentSegment(SqlParserUtil.Segment segment, Sql sql) {
        int[] range = computeSegmentEditorRange(segment, sql);
        if (!ctrl.isSqlRefresh) {
            ctrl.selectRangeAndFollow(range[0], range[1]);
        }
        return range;
    }

    private int[] computeSegmentEditorRange(SqlParserUtil.Segment segment, Sql sql) {
        // A DELIMITER block is executed as one whole statement. Its segment text
        // starts after the consumed "DELIMITER xx" line, so the normal offset math
        // would shift the selection right. Highlight the whole enclosing block.
        SqlParserUtil.StatementRange blockRange = findDelimiterBlockRange(ctrl.sqlText, segment);
        if (blockRange != null) {
            // blockRange is relative to the executed selection (ctrl.sqlText);
            // convert it to absolute editor coordinates before highlighting.
            return new int[]{
                    ctrl.sqlSelectionRange[0] + blockRange.getStart(),
                    ctrl.sqlSelectionRange[0] + blockRange.getEnd()};
        }
        int finalI = segment.getEndIndex();
        // IMPORTANT: selection/highlight must be aligned with the editor text, not the executable SQL.
        // ctrl.sqlExe is stripped of trailing ';' (and may be trimmed), so using its length here
        // would shift the highlight range by 1 (or more). Use raw parsed statement text instead.
        String rawStatement = sql == null ? null : sql.getSqlstr();
        int rawStatementLength = rawStatement == null ? 0 : rawStatement.length();
        int rawLeadingWhitespace = getWhitespaceLength(rawStatement);
        if (sql.getSqlRemainder() != null && !sql.getSqlRemainder().isEmpty()) {
            int remaindersize = sql.getSqlRemainder().length();
            return new int[]{
                    ctrl.sqlSelectionRange[0] + finalI + 1 - remaindersize - rawStatementLength + rawLeadingWhitespace,
                    ctrl.sqlSelectionRange[0] + finalI + 1 - remaindersize};
        }
        return new int[]{
                ctrl.sqlSelectionRange[0] + finalI + 1 - rawStatementLength + rawLeadingWhitespace,
                ctrl.sqlSelectionRange[0] + finalI + 1};
    }

    /**
     * Returns the full editor range of the DELIMITER block owning {@code segment}
     * (directive line start to closing delimiter end), or {@code null} when the
     * segment is not inside a DELIMITER block.
     */
    private static SqlParserUtil.StatementRange findDelimiterBlockRange(
            String sqlText, SqlParserUtil.Segment segment) {
        if (sqlText == null || segment == null || segment.getStartIndex() <= 0) {
            return null;
        }
        int lineStart = segment.getStartIndex();
        while (lineStart > 0 && (sqlText.charAt(lineStart - 1) == '\n'
                || sqlText.charAt(lineStart - 1) == '\r')) {
            lineStart--;
        }
        while (lineStart > 0 && sqlText.charAt(lineStart - 1) != '\n'
                && sqlText.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        String line = sqlText.substring(lineStart, segment.getStartIndex()).trim();
        if (!line.regionMatches(true, 0, "delimiter", 0, "delimiter".length())) {
            return null;
        }
        int idx = "delimiter".length();
        while (idx < line.length() && Character.isWhitespace(line.charAt(idx))) {
            idx++;
        }
        int tokenEnd = idx;
        while (tokenEnd < line.length() && !Character.isWhitespace(line.charAt(tokenEnd))) {
            tokenEnd++;
        }
        if (tokenEnd <= idx) {
            return null;
        }
        String token = line.substring(idx, tokenEnd);
        int closeEnd = segment.getEndIndex() + token.length();
        if (closeEnd > sqlText.length()) {
            return null;
        }
        return new SqlParserUtil.StatementRange(lineStart, closeEnd);
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
            ctrl.lastSqlException = e;
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
        ctrl.updateResult.setDatabase(resolveEffectiveDatabase());
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
        ctrl.updateResult.setDatabase(resolveEffectiveDatabase());

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
            ctrl.refreshResultFilterButtons();
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
            ctrl.lastSqlException = e;
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
            ctrl.lastSqlException = e;
            boolean requiresRecovery = requiresSessionRecovery(e);
            if (ctrl.isSingleSql) {
                Platform.runLater(() -> {
                    if (SqlErrorUtil.isDisconnectError(ctrl.sqlConnect, e)) {
                        ctrl.connectionDisconnected();
                    } else if (requiresRecovery) {
                        try {
                            sqlexeRepository().recoverSession(ctrl.sqlConnect.getConn(), ctrl.sqlConnect.getCatalog());
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
                ctrl.sqlConnect.setCatalog(sql.getSqlType().split(" ")[1]);
                Platform.runLater(() -> {
                    Database db = new Database();
                    db.setName(ctrl.sqlConnect.getCatalog());
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
                String explainValidationError = validateExplainSql(ctrl.sqlText);
                if (explainValidationError != null) {
                    Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t(explainValidationError)));
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

    private String validateExplainSql(String sqlText) {
        SqlParser parser = platformResolver.requirePlatform(ctrl.sqlConnect).parser();
        if (!parser.isSingleStatement(sqlText)) {
            return "sql.explain.single_only";
        }
        String normalizedSql = sqlText == null ? "" : stripTrailingExplainDelimiter(sqlText.trim());
        if (!SqlParserUtil.isExecutableStatement(normalizedSql)) {
            return "sql.explain.not_supported";
        }
        if (isDdlStatement(normalizedSql.toUpperCase())) {
            return "sql.explain.not_supported";
        }
        return null;
    }

    // --- createSqlModeTask ---

    public Task<Void> createSqlModeTask(Connect sqlConnect, String sqlmode) {
        ctrl.sqlTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                SqlModeCapability capability = sqlModeCapability();
                if (capability == null) {
                    Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("common.error"), I18n.t("sql.explain.not_supported")));
                    throw new Exception("ERROR");
                }
                try {
                    capability.changeSqlMode(sqlConnect.getConn(), sqlmode);
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

    private SqlModeCapability sqlModeCapability() {
        SqlexeRepository repository = sqlexeRepository();
        if (repository instanceof SqlModeCapability capability) {
            return capability;
        }
        return null;
    }

    private DatabasePlatformResolver resolvePlatformResolver() {
        return DatabasePlatformResolver.getInstance();
    }

    private String detectSqlMode(String sql) {
        try {
            SqlModeCapability capability = sqlModeCapability();
            return capability != null ? capability.detectSqlMode(sql) : null;
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
            SqlModeCapability capability = sqlModeCapability();
            if (capability != null) {
                return capability.autoCommitsDdl(ctrl.sqlSqlModeChoiceBox.getValue());
            }
            return sqlexeRepository().autoCommitsDdl();
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

    private String resolveEffectiveDatabase() {
        return ctrl.sqlConnect.getEffectiveCatalog();
    }

    private static String stripTrailingSemicolon(String sql) {
        if (sql == null) return sql;
        String upper = sql.stripLeading().toUpperCase();
        if (upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) {
            return sql;
        }
        if (sql.endsWith(";")) {
            return sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    private static String stripTrailingExplainDelimiter(String sql) {
        if (sql == null) {
            return "";
        }
        String normalized = sql.trim();
        while (normalized.endsWith(";") || normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    /**
     * For GBase 8S, maps the UI sqlmode choice to the correct family parser.
     * For all other dialects, returns the platform parser unchanged.
     */
    private static SqlParser resolveGbaseParser(SqlParser platformParser, String sqlmode) {
        if (!(platformParser instanceof com.dbboys.dialect.gbase8s.Gbase8sSqlParser gbase)) {
            return platformParser;
        }
        if (sqlmode == null || sqlmode.isBlank()) {
            return platformParser; // default → Informix family
        }
        return switch (sqlmode) {
            case "sqlmode=oracle" -> new com.dbboys.dialect.oracle.OracleSqlParser();
            case "sqlmode=mysql"  -> new com.dbboys.dialect.mysql.MysqlSqlParser();
            default               -> platformParser; // sqlmode=gbase or unknown → Informix family
        };
    }

    /**
     * Returns true if the sql string is just a custom delimiter token
     * (e.g. "$$", "//") that should not be executed.
     */
    private static boolean isJustDelimiter(String sql) {
        if (sql == null || sql.isBlank()) return true;
        String t = sql.trim();
        if (";".equals(t)) return true;
        // Common custom delimiters that are NOT valid SQL
        return t.matches("\\$\\$|//|!!|@@");
    }
}
