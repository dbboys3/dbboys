package com.dbboys.dialect.common;

import com.dbboys.core.SqlErrorPosition;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialect-side knowledge for locating where an execution error points inside
 * the sent statement: driver exception/connection introspection (via reflection,
 * since drivers load from extlib at runtime) plus message-text heuristics.
 * Returned offsets are 0-based char indexes relative to the sent SQL, unless
 * {@link SqlErrorPosition#referenceSql()} says otherwise.
 */
public final class ErrorPositionSupport {

    private static final Logger log = LogManager.getLogger(ErrorPositionSupport.class);

    private ErrorPositionSupport() {
    }

    // --- per-dialect entry points ---------------------------------------------

    /**
     * SQLite reports error tokens or a broad error description, but not a
     * character position. Map those descriptions back to the sent statement.
     */
    public static SqlErrorPosition locateSqlite(String sentSql, String errorMessage) {
        SqlErrorPosition position = locateSqlitePatterns(sentSql, errorMessage);
        return position != null ? position : locateByMessage(sentSql, errorMessage);
    }

    /**
     * Informix/GBase: the driver stores the byte offset of the error inside the
     * sent statement (SQLCA sqlerrd[4]) for every error response, not only
     * syntax errors. Use it whenever it points inside the statement.
     */
    public static SqlErrorPosition locateInformixFamily(Connection conn, SQLException error,
                                                        String sentSql, String errorMessage) {
        Integer offset = error == null ? null : probeInformixFamilyOffset(conn, sentSql);
        if (offset != null) {
            return SqlErrorPosition.inStatement(offset);
        }
        return locateByMessage(sentSql, errorMessage);
    }

    /** Oracle positions are relative to the original SQL held by the driver. */
    public static SqlErrorPosition locateOracle(SQLException error, String sentSql, String errorMessage) {
        SqlErrorPosition position = probeOracle(error, sentSql, errorMessage);
        return position != null ? position : locateByMessage(sentSql, errorMessage);
    }

    /** PostgreSQL: ServerErrorMessage carries a 1-based character position. */
    public static SqlErrorPosition locatePostgresql(SQLException error, String sentSql, String errorMessage) {
        Integer position = queryPostgresqlErrorPosition(error);
        if (position != null && position > 0) {
            return SqlErrorPosition.inStatement(position - 1);
        }
        return locateByMessage(sentSql, errorMessage);
    }

    /**
     * SQL Server often reports only the statement start line, so quoted column,
     * object, or near-token text is more precise when available.
     */
    public static SqlErrorPosition locateSqlServer(SQLException error, String sentSql, String errorMessage) {
        int tokenIndex = quotedErrorTokenIndex(sentSql, errorMessage);
        if (tokenIndex >= 0) {
            return SqlErrorPosition.inStatement(tokenIndex);
        }
        Integer line = querySqlServerErrorLine(error);
        if (line != null && line > 0) {
            return SqlErrorPosition.inStatement(lineStartOffset(sentSql, line));
        }
        int messageLine = lineNumberFromMessage(errorMessage);
        if (messageLine > 0) {
            return SqlErrorPosition.inStatement(lineStartOffset(sentSql, messageLine));
        }
        return null;
    }

    /**
     * Generic message-text fallback (MySQL/Dameng/DB2 style): a quoted token in
     * the message mapped back to the SQL, then a "line N" number relative to
     * the start of the sent statement.
     */
    public static SqlErrorPosition locateByMessage(String sentSql, String errorMessage) {
        int tokenIndex = quotedErrorTokenIndex(sentSql, errorMessage);
        if (tokenIndex >= 0) {
            return SqlErrorPosition.inStatement(tokenIndex);
        }
        int line = lineNumberFromMessage(errorMessage);
        if (line > 0) {
            return SqlErrorPosition.inStatement(lineStartOffset(sentSql, line));
        }
        return null;
    }

    /** GENERAL JDBC: probe whatever driver is actually behind the connection. */
    public static SqlErrorPosition locateGeneralJdbc(Connection conn, SQLException error,
                                                     String sentSql, String errorMessage) {
        if (isSqliteException(error)) {
            SqlErrorPosition position = locateSqlitePatterns(sentSql, errorMessage);
            if (position != null) {
                return position;
            }
        }
        Integer offset = error == null ? null : probeInformixFamilyOffset(conn, sentSql);
        if (offset != null) {
            return SqlErrorPosition.inStatement(offset);
        }
        SqlErrorPosition oracle = probeOracle(error, sentSql, errorMessage);
        if (oracle != null) {
            return oracle;
        }
        Integer position = queryPostgresqlErrorPosition(error);
        if (position != null && position > 0) {
            return SqlErrorPosition.inStatement(position - 1);
        }
        Integer line = querySqlServerErrorLine(error);
        if (line != null && line > 0) {
            int tokenIndex = quotedErrorTokenIndex(sentSql, errorMessage);
            return SqlErrorPosition.inStatement(tokenIndex >= 0 ? tokenIndex : lineStartOffset(sentSql, line));
        }
        return locateByMessage(sentSql, errorMessage);
    }

    // --- driver probes ----------------------------------------------------------

    private static SqlErrorPosition locateSqlitePatterns(String sentSql, String errorMessage) {
        if (sentSql == null || sentSql.isEmpty()) {
            return null;
        }
        String message = errorMessage == null ? "" : errorMessage;
        Matcher nearMatcher = SQLITE_NEAR_TOKEN_PATTERN.matcher(message);
        if (nearMatcher.find()) {
            int index = lastIndexOfIgnoreCase(sentSql, nearMatcher.group(1).trim());
            return SqlErrorPosition.inStatement(index >= 0 ? index : lastNonBlankLineStart(sentSql));
        }
        Matcher missingObjectMatcher = SQLITE_MISSING_OBJECT_PATTERN.matcher(message);
        if (missingObjectMatcher.find()) {
            String object = missingObjectMatcher.group(1).trim();
            int index = indexOfIgnoreCase(sentSql, object);
            if (index < 0) {
                int dot = object.lastIndexOf('.');
                if (dot >= 0 && dot + 1 < object.length()) {
                    index = indexOfIgnoreCase(sentSql, object.substring(dot + 1));
                }
            }
            if (index >= 0) {
                return SqlErrorPosition.inStatement(index);
            }
        }
        if (message.contains("incomplete input")
                || message.contains("unrecognized token")
                || message.contains("syntax error")) {
            return SqlErrorPosition.inStatement(lastNonBlankLineStart(sentSql));
        }
        return null;
    }

    private static boolean isSqliteException(SQLException error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if ("org.sqlite.SQLiteException".equals(current.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calls {@code getSQLStatementOffset()} on the JDBC connection when the driver
     * supports it (Informix/GBase {@code IfmxConnection}), and converts the byte
     * offset to a char index. Returns {@code null} when unavailable/out of range.
     */
    private static Integer probeInformixFamilyOffset(Connection conn, String sentSql) {
        Integer byteOffset = queryDriverStatementErrorOffset(conn);
        log.info("SQL error offset: offset={}, sql={}", byteOffset, abbreviate(sentSql));
        if (byteOffset == null || byteOffset < 0 || byteOffset > utf8ByteLength(sentSql)) {
            return null;
        }
        return byteOffsetToCharIndex(sentSql, byteOffset);
    }

    private static Integer queryDriverStatementErrorOffset(Connection conn) {
        try {
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

    /**
     * Oracle exposes a 0-based error position via {@code getErrorPosition()},
     * relative to the SQL text held by the driver ({@code getOriginalSql()}).
     * A quoted token from the message is more precise when it maps back.
     */
    private static SqlErrorPosition probeOracle(SQLException error, String sentSql, String errorMessage) {
        OracleErrorInfo oracleError = queryOracleErrorInfo(error);
        if (oracleError == null || oracleError.position() <= 0) {
            return null;
        }
        int tokenIndex = quotedErrorTokenIndex(sentSql, errorMessage);
        if (tokenIndex >= 0) {
            return SqlErrorPosition.inStatement(tokenIndex);
        }
        return new SqlErrorPosition(oracleError.position(), oracleError.originalSql());
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

    private static boolean hasPublicMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // --- message-text heuristics --------------------------------------------------

    private static final Pattern SQLITE_NEAR_TOKEN_PATTERN =
            Pattern.compile("(?is)\\bnear\\s+[\"'](.+?)[\"']\\s*:");

    private static final Pattern SQLITE_MISSING_OBJECT_PATTERN =
            Pattern.compile("(?is)\\bno such\\s+(?:column|table|function|module|collation):\\s*([^\\r\\n]+)");

    private static final Pattern QUOTED_ERROR_TOKEN_PATTERN =
            Pattern.compile("(?s)[\"'`]([^\"'`\\r\\n]{1,300})[\"'`]"
                    + "|“([^”\\r\\n]{1,300})”"
                    + "|‘([^’\\r\\n]{1,300})’"
                    + "|\\[([^\\]\\r\\n]{1,300})\\]");

    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile(
            "(?i)(?:at\\s+line|line\\s*[:：]?|第)\\s*(\\d+)\\s*(?:行)?");

    /**
     * Finds a quoted column/object/token in a driver message and maps it back
     * to the sent SQL, or returns -1 when no token can be located.
     */
    private static int quotedErrorTokenIndex(String sentSql, String errorMessage) {
        if (sentSql == null || sentSql.isEmpty() || errorMessage == null) {
            return -1;
        }
        Matcher matcher = QUOTED_ERROR_TOKEN_PATTERN.matcher(errorMessage);
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
                int dot = unquoted.lastIndexOf('.');
                if (dot >= 0 && dot + 1 < unquoted.length()) {
                    String objectName = unquoted.substring(dot + 1).trim();
                    index = indexOfIgnoreCase(sentSql, objectName);
                    if (index >= 0) {
                        return index;
                    }
                }
            }
        }
        return -1;
    }

    /** Returns the 1-based error line within the statement, or 0 when unknown. */
    private static int lineNumberFromMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return 0;
        }
        Matcher matcher = ERROR_LINE_PATTERN.matcher(errorMessage);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** 0-based char index of the start of the given 1-based line, or 0 when out of range. */
    private static int lineStartOffset(String sentSql, int lineNumber) {
        if (lineNumber <= 1 || sentSql == null || sentSql.isEmpty()) {
            return 0;
        }
        int cursor = 0;
        for (int line = 1; line < lineNumber; line++) {
            int newline = sentSql.indexOf('\n', cursor);
            if (newline < 0) {
                return 0;
            }
            cursor = newline + 1;
        }
        return cursor;
    }

    // --- text utilities -----------------------------------------------------------

    private static int lastNonBlankLineStart(String sql) {
        int end = sql.length();
        while (end > 0) {
            int start = sql.lastIndexOf('\n', end - 1) + 1;
            if (!sql.substring(start, end).isBlank()) {
                return start;
            }
            end = start - 1;
        }
        return 0;
    }

    private static int lastIndexOfIgnoreCase(String text, String token) {
        if (text == null || token == null || token.isEmpty() || token.length() > text.length()) {
            return -1;
        }
        for (int i = text.length() - token.length(); i >= 0; i--) {
            if (text.regionMatches(true, i, token, 0, token.length())) {
                return i;
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
}
