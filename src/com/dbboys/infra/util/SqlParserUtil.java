package com.dbboys.infra.util;

import com.dbboys.model.Sql;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.*;

public class SqlParserUtil {
    public static final Pattern STRING_PATTERN = Pattern.compile("'([^'\\\\]*(\\\\.[^'\\\\]*)*)'"+"|" + "'[\\s\\S]*");
    public static final Pattern DOUBLE_STRING_PATTERN = Pattern.compile("\"[^\"]*\""+"|" + "\"[\\s\\S]*");
    public static final Pattern FANYINHAO_STRING_PATTERN = Pattern.compile("`[^`]*`"+"|" + "`[\\s\\S]*");
    public static final Pattern COMMENT_PATTERN = Pattern.compile("--[^\n]*" + "|"+"/\\*[\\s\\S]*?\\*/"+"|"+"/\\*[\\s\\S]*" +"|"+"\\{[\\s\\S]*?\\}");
    private static final String STRING_PATTERN_TEXT = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
    private static final String DOUBLE_STRING_PATTERN_TEXT = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
    private static final String FANYINHAO_STRING_PATTERN_TEXT = "`[^`]*`" + "|" + "`[\\s\\S]*";
    private static final String COMMENT_PATTERN_TEXT = "--[^\\n]*" + "|"+"/\\*[\\s\\S]*?\\*/"+"|"+"/\\*[\\s\\S]*" +"|"+"\\{[\\s\\S]*?\\}";
    private static final String NO_NAME_BLOCK =
            "(?i)^\\s*\\b(begin)(?!\\s*(;|work))|(?i)^\\s*\\b(DECLARE)(?!\\s*;)" +
            "|(?i)^\\s*\\bdo\\s+\\$\\$" +       // PostgreSQL DO $$ blocks
            "|(?i)^\\s*\\bdo\\s+\\$[a-zA-Z_]\\w*\\$";  // PostgreSQL DO $tag$ blocks
    private static final String MULTI_LINE_END =
            "(?i)\\bend\\s+(procedure|function)\\s*;?" + "|" +
            "(?i)\\bend\\s*;\\s*/" + "|" +
            "(?i)\\bend\\b\\s+([a-zA-Z_][a-zA-Z0-9_$.]*)?\\s*/" + "|" +
            "(?m)^\\s*/\\s*$" + "|" +
            // PostgreSQL: dollar-quote closing + optional language clause + ;
            "\\$\\$(\\s*language\\s+\\w+)?\\s*;" + "|" +
            "\\$[a-zA-Z_]\\w*\\$(\\s*language\\s+\\w+)?\\s*;" + "|" +
            // Legacy PostgreSQL: single-quote closing + language clause + ;
            "\\'\\s*language\\s+\\w+\\s*;";
    private static final String DROP_DATABASE = "(?i)(?:drop\\s+)+database\\s+(\\w+)";
    private static final String CREATE_DATABASE = "(?i)(?:create\\s+)?database\\s+(?<dbname>(\\w+))";
    /** Optional Oracle clause after {@code CREATE [OR REPLACE]} before object kind (trigger, package, procedure, …). */
    private static final String ORACLE_EDITION_MODIFIER = "(?:editionable\\s+|editioning\\s+|noneditionable\\s+)?";
    /** Matches {@code CREATE [OR REPLACE] [EDITIONABLE] PACKAGE BODY} with quoted or schema-qualified names. */
    private static final String PACKAGE_BODY_PATTERN =
            "(?i)\\bcreate\\s+(?:or\\s+replace\\s+)?(?:editionable\\s+|editioning\\s+|noneditionable\\s+)?package\\s+body\\s+"
                    + "((?:\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$#]*)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$#]*))*)\\s*"
                    + "(AS|IS)\\b";
    private static final String PACKAGE_MEMBER_PATTERN =
            "(?i)\\bfunction\\s+(?<FUNC>[a-zA-Z0-9_$.]+)\\s*(\\([\\s\\S]*?\\))?\\s+return\\s+([a-zA-Z0-9_$.]+)\\s*(PIPELINED\\s+|DETERMINISTIC\\s+|RESULT_CACHE\\s+)?(AS|IS|;)"
            + "|"
            + "(?i)\\bprocedure\\s+(?<PROC>[a-zA-Z0-9_$.]+)\\s*(\\([\\s\\S]*?\\))?\\s*(AS|IS|;)";
    private static final Pattern COMMENT_ONLY_PATTERN = Pattern.compile(COMMENT_PATTERN_TEXT);
    private static final Pattern NO_NAME_BLOCK_PATTERN = Pattern.compile(NO_NAME_BLOCK);
    private static final Pattern ROUTINE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "(?<TYPE>function|procedure)\\b"
    );
    private static final Pattern PACKAGE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "package(\\s+body)?\\b"
    );
    private static final Pattern TRIGGER_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "trigger\\b"
    );
    /** Oracle object / collection {@code TYPE} or {@code TYPE BODY}. */
    private static final Pattern TYPE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "type(\\s+body)?\\b"
    );
    private static final Pattern BLOCK_NAME_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER
                    + "(?<TYPE>package(\\s+body)?|procedure|function|trigger|type(\\s+body)?)\\b"
    );
    /** {@code CREATE [OR REPLACE] [EDITIONABLE|…] (PROCEDURE|FUNCTION|TRIGGER|TYPE BODY)} for plain {@code END;} termination. */
    private static final Pattern ORACLE_PLAIN_BLOCK_OBJECT_HEAD_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER
                    + "(?:procedure|function|trigger|type\\s+body)\\b"
    );
    private static final Pattern STATEMENT_PROTECT_PATTERN = Pattern.compile(
            STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + FANYINHAO_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
    );
    private static final Pattern BLOCK_DEPTH_TOKEN_PATTERN = Pattern.compile(
            "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                    + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                    + "|(?<BACKTICK>" + FANYINHAO_STRING_PATTERN_TEXT + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                    + "|(?<BEGIN>(?i)\\bbegin\\b(?!\\s*work\\b))"
                    + "|(?<PLAINEND>(?i)\\bend\\s*;)"
                    + "|(?<NAMEDEND>(?i)\\bend\\b\\s+[a-zA-Z_][a-zA-Z0-9_$#\"]*\\s*;)"
    );
    private static final Pattern PLAIN_BLOCK_END_PATTERN = Pattern.compile(
            "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                    + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                    + "|(?<BACKTICK>" + FANYINHAO_STRING_PATTERN_TEXT + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                    + "|(?<PLAINEND>(?i)\\bend\\s*;)"
    );
    private static final Pattern NAMED_BLOCK_END_PATTERN = Pattern.compile(
            "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                    + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                    + "|(?<BACKTICK>" + FANYINHAO_STRING_PATTERN_TEXT + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                    + "|(?<NAMEDEND>(?i)\\bend\\s+(?<ENDNAME>[a-zA-Z_][a-zA-Z0-9_$.]*|\"[^\"]+\")\\s*;)"
    );
    public static class PackageMember {
        private final String name;
        private final String type;

        public PackageMember(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }
    }

    public static class Segment {
        private final String text;
        private final int endIndex;

        public Segment(String text, int endIndex) {
            this.text = text;
            this.endIndex = endIndex;
        }

        public String getText() {
            return text;
        }

        public int getEndIndex() {
            return endIndex;
        }
    }

    public static final class StatementRange {
        private final int start;
        private final int end;

        public StatementRange(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }
    }

    @FunctionalInterface
    private interface SegmentHandler {
        boolean handle(Segment segment);
    }

    @FunctionalInterface
    public interface ThrowingSegmentHandler {
        boolean handle(Segment segment) throws Exception;
    }

    private static final class SegmentProcessingRuntimeException extends RuntimeException {
        private SegmentProcessingRuntimeException(Exception cause) {
            super(cause);
        }
    }

    public static boolean isSingleStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            return true;
        }
        return countExecutableStatements(sql, 2) <= 1;
    }

    public static int countExecutableStatements(String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            return 0;
        }
        return countExecutableStatements(sqlText, Integer.MAX_VALUE);
    }

    public static boolean isExecutableStatement(String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            return false;
        }
        String normalized = stripLeadingSqlDelimiter(sqlText);
        if (normalized.isBlank()) {
            return false;
        }
        return !stripProtectedContent(normalized).trim().isEmpty();
    }

    public static List<Segment> split(String sql) {
        List<Segment> segments = new ArrayList<>();
        processSegments(sql, segment -> {
            segments.add(segment);
            return true;
        });
        return segments;
    }

    public static boolean forEachSegment(String sql, ThrowingSegmentHandler handler) throws Exception {
        try {
            return processSegments(sql, segment -> {
                try {
                    return handler.handle(segment);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new SegmentProcessingRuntimeException(e);
                }
            });
        } catch (SegmentProcessingRuntimeException e) {
            throw (Exception) e.getCause();
        }
    }

    public static StatementRange findStatementRangeAtCaret(String sqlText, int caretPosition) {
        if (sqlText == null || sqlText.isBlank()) {
            return null;
        }
        List<StatementRange> ranges = collectExecutableStatementRanges(sqlText);
        if (ranges.isEmpty()) {
            return null;
        }

        int clampedCaret = Math.max(0, Math.min(caretPosition, sqlText.length()));
        StatementRange range = findContainingRange(ranges, clampedCaret);
        if (range != null) {
            return range;
        }
        if (clampedCaret > 0) {
            range = findContainingRange(ranges, clampedCaret - 1);
            if (range != null) {
                return range;
            }
        }
        range = findStatementRangeForCaretInOracleSlashGap(ranges, sqlText, clampedCaret);
        return range;
    }

    /**
     * Executable ranges end at {@code END;} and do not include a following SQL*Plus {@code /} line.
     * Caret on that slash line (or whitespace-only gap before it) is not inside {@code [start, end)},
     * so Ctrl+Enter would not select any statement. Map such positions to the preceding statement.
     */
    private static StatementRange findStatementRangeForCaretInOracleSlashGap(List<StatementRange> ranges,
                                                                             String sqlText,
                                                                             int pos) {
        if (ranges.isEmpty()) {
            return null;
        }
        for (int i = 0; i < ranges.size(); i++) {
            StatementRange r = ranges.get(i);
            if (pos < r.getEnd()) {
                continue;
            }
            if (i + 1 < ranges.size()) {
                int nextStart = ranges.get(i + 1).getStart();
                if (pos >= nextStart) {
                    continue;
                }
                String gap = sqlText.substring(r.getEnd(), nextStart);
                if (gapIsWhitespaceOrOracleSlashLinesOnly(gap)) {
                    return r;
                }
            } else {
                /*
                 * Last statement: gap runs to EOF. Caret after the final '/' is pos == sqlText.length();
                 * previously pos >= nextStart with nextStart == length incorrectly skipped this case.
                 */
                String gap = sqlText.substring(r.getEnd(), sqlText.length());
                if (pos >= r.getEnd() && gapIsWhitespaceOrOracleSlashLinesOnly(gap)) {
                    return r;
                }
            }
        }
        return null;
    }

    private static boolean gapIsWhitespaceOrOracleSlashLinesOnly(String gap) {
        if (gap.isEmpty()) {
            return true;
        }
        for (String line : gap.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.equals("/")) {
                return false;
            }
        }
        return true;
    }

    private static StatementRange findContainingRange(List<StatementRange> ranges, int position) {
        for (StatementRange range : ranges) {
            if (position >= range.start && position < range.end) {
                return range;
            }
        }
        return null;
    }

    private static List<StatementRange> collectExecutableStatementRanges(String sqlText) {
        List<StatementRange> ranges = new ArrayList<>();
        Sql currentSql = new Sql();
        int currentStatementStart = 0;
        int previousSegmentEnd = -1;

        for (Segment segment : split(sqlText)) {
            String sqlChunk = segment.getText();
            int segmentStart = previousSegmentEnd + 1;
            if (currentSql.getSqlstr().isEmpty() && currentSql.getSqlRemainder().isEmpty()) {
                currentStatementStart = segmentStart + leadingDelimiterOffset(sqlChunk);
            }

            boolean sqlContainsCommit;
            do {
                currentSql = modifySql(currentSql, sqlChunk);
                if (currentSql.getSqlEnd()) {
                    appendExecutableRange(sqlText, ranges, currentStatementStart, currentSql.getSqlstr());
                    String remainder = currentSql.getSqlRemainder();
                    resetSqlStatementState(currentSql);
                    if (remainder != null && !remainder.isEmpty()) {
                        int remainderStart = segment.getEndIndex() + 1 - remainder.length();
                        currentStatementStart = remainderStart + leadingDelimiterOffset(remainder);
                    }
                }
                sqlChunk = "";
                sqlContainsCommit = sqlContrainCommit(currentSql.getSqlRemainder());
            } while (sqlContainsCommit);

            previousSegmentEnd = segment.getEndIndex();
        }

        appendExecutableRange(sqlText, ranges, currentStatementStart, currentSql.getSqlstr());
        return ranges;
    }

    private static void appendExecutableRange(String sqlText,
                                              List<StatementRange> ranges,
                                              int statementStart,
                                              String statementText) {
        if (!isExecutableStatement(statementText)) {
            return;
        }
        int statementEnd = Math.min(sqlText.length(), statementStart + statementText.length());
        StatementRange trimmedRange = trimWhitespaceRange(sqlText, statementStart, statementEnd);
        if (trimmedRange != null) {
            ranges.add(trimmedRange);
        }
    }

    private static int leadingDelimiterOffset(String sql) {
        if (sql == null || sql.isEmpty()) {
            return 0;
        }
        return sql.length() - stripLeadingSqlDelimiter(sql).length();
    }

    private static StatementRange trimWhitespaceRange(String sqlText, int start, int end) {
        int trimmedStart = Math.max(0, start);
        int trimmedEnd = Math.max(trimmedStart, Math.min(end, sqlText.length()));
        while (trimmedStart < trimmedEnd && Character.isWhitespace(sqlText.charAt(trimmedStart))) {
            trimmedStart++;
        }
        while (trimmedEnd > trimmedStart && Character.isWhitespace(sqlText.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }
        return trimmedStart < trimmedEnd ? new StatementRange(trimmedStart, trimmedEnd) : null;
    }

    private static boolean processSegments(String sql, SegmentHandler handler) {
        if (sql == null || sql.isEmpty()) {
            return true;
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktickQuote = false;
        boolean inDollarQuote = false;
        String dollarQuoteTag = null;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inBrackets = false;
        String currentDelimiter = ";";

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';
            buffer.append(current);

            // Only check DELIMITER directive at potential statement start
            boolean atStatementStart = buffer.toString().trim().isEmpty()
                    || buffer.toString().trim().equals(";")
                    || buffer.toString().trim().equals(currentDelimiter);
            if (atStatementStart && !inSingleQuote && !inDoubleQuote && !inBacktickQuote
                    && !inDollarQuote && !inLineComment && !inBlockComment && !inBrackets
                    && startsWithIgnoreCase(sql, i, "delimiter")) {
                int dEnd = i + "delimiter".length();
                dEnd = skipWhitespace(sql, dEnd);
                int tokenStart = dEnd;
                while (dEnd < sql.length() && !Character.isWhitespace(sql.charAt(dEnd))) {
                    dEnd++;
                }
                currentDelimiter = dEnd > tokenStart ? sql.substring(tokenStart, dEnd) : ";";
                // Only accept single-char delimiters and "$$" (MySQL standard alternative).
                // Multi-char delimiters like "//" conflict with Oracle SQL*Plus slash.
                if (currentDelimiter.length() == 1 || "$$".equals(currentDelimiter)) {
                    // accepted
                } else {
                    currentDelimiter = ";";
                }
                while (dEnd < sql.length() && sql.charAt(dEnd) != '\n') {
                    dEnd++;
                }
                if (dEnd < sql.length() && sql.charAt(dEnd) == '\n') {
                    dEnd++;
                }
                buffer.setLength(0);
                i = dEnd - 1; // -1 because for loop does i++
                continue;
            }

            // Line comment detection
            if (!inSingleQuote && !inDoubleQuote && !inBacktickQuote && !inDollarQuote
                    && !inBlockComment && !inBrackets) {
                if (!inLineComment && current == '-' && next == '-') {
                    inLineComment = true;
                    buffer.append(next);
                    i++;
                    continue;
                } else if (inLineComment && current == '\n') {
                    inLineComment = false;
                }
            }

            // Block comment detection
            if (!inSingleQuote && !inDoubleQuote && !inBacktickQuote && !inDollarQuote
                    && !inLineComment && !inBrackets) {
                if (!inBlockComment && current == '/' && next == '*') {
                    inBlockComment = true;
                    buffer.append(next);
                    i++;
                    continue;
                } else if (inBlockComment && current == '*' && next == '/') {
                    inBlockComment = false;
                    buffer.append(next);
                    i++;
                    continue;
                }
            }

            // Single-quote toggle
            if (!inDoubleQuote && !inBacktickQuote && !inDollarQuote
                    && !inLineComment && !inBlockComment && !inBrackets && current == '\'') {
                inSingleQuote = !inSingleQuote;
            }

            // Double-quote toggle
            if (!inSingleQuote && !inBacktickQuote && !inDollarQuote
                    && !inLineComment && !inBlockComment && !inBrackets && current == '\"') {
                inDoubleQuote = !inDoubleQuote;
            }

            // Backtick-quote toggle (MySQL identifiers)
            if (!inSingleQuote && !inDoubleQuote && !inDollarQuote
                    && !inLineComment && !inBlockComment && !inBrackets && current == '`') {
                inBacktickQuote = !inBacktickQuote;
            }

            // Dollar-quote detection (PostgreSQL $$...$$ and $tag$...$tag$)
            if (!inSingleQuote && !inDoubleQuote && !inBacktickQuote
                    && !inLineComment && !inBlockComment && !inBrackets) {
                if (!inDollarQuote && current == '$') {
                    int tagEnd = scanDollarQuoteEnd(sql, i);
                    if (tagEnd >= 0) {
                        inDollarQuote = true;
                        dollarQuoteTag = sql.substring(i, tagEnd + 1);
                        for (int k = i + 1; k <= tagEnd; k++) {
                            buffer.append(sql.charAt(k));
                        }
                        i = tagEnd;
                        continue;
                    }
                } else if (inDollarQuote && current == '$'
                        && sql.regionMatches(i, dollarQuoteTag, 0, dollarQuoteTag.length())) {
                    // Closing tag: consume all chars of the closing tag
                    for (int k = 1; k < dollarQuoteTag.length(); k++) {
                        if (i + k < sql.length()) {
                            buffer.append(sql.charAt(i + k));
                        }
                    }
                    i += dollarQuoteTag.length() - 1;
                    inDollarQuote = false;
                    dollarQuoteTag = null;
                    continue;
                }
            }

            // Informix bracket-comment tracking
            if (!inSingleQuote && !inDoubleQuote && !inBacktickQuote && !inDollarQuote
                    && !inLineComment && !inBlockComment) {
                if (current == '{') {
                    inBrackets = true;
                } else if (current == '}') {
                    inBrackets = false;
                }
            }

            // Segment boundary: end of input or delimiter match
            boolean atDelimiter = !inSingleQuote && !inDoubleQuote && !inBacktickQuote && !inDollarQuote
                    && !inLineComment && !inBlockComment && !inBrackets
                    && matchesCurrentDelimiter(sql, i, currentDelimiter);

            // Don't let the multi-char delimiter become part of the segment text
            // Only the first char of the delimiter was appended; trim off the rest
            if (atDelimiter && currentDelimiter.length() > 1) {
                int segEnd = i - (currentDelimiter.length() - 1);
                if (segEnd >= 0 && i == sql.length() - 1) {
                    // End-of-input with multi-char delimiter
                }
            }

            if (i == sql.length() - 1
                    || (atDelimiter
                        && !shouldKeepRoutineTerminatorWithPreviousSegment(sql, i + currentDelimiter.length()))) {
                String segText = buffer.toString();
                // Trim trailing delimiter chars from segment for non-';' delimiters
                // so the segment text doesn't end with (e.g.) '$$'
                if (atDelimiter && currentDelimiter.length() > 1) {
                    int trimLen = currentDelimiter.length() - 1;
                    if (segText.length() >= trimLen) {
                        segText = segText.substring(0, segText.length() - trimLen);
                    }
                }
                if (!handler.handle(new Segment(segText, i))) {
                    return false;
                }
                buffer.setLength(0);
                // Skip past multi-char delimiter
                if (atDelimiter && currentDelimiter.length() > 1) {
                    i += currentDelimiter.length() - 1;
                }
            }
        }
        return true;
    }

    /**
     * From position {@code dollarPos} (pointing at '$'), scan for the closing '$' of a
     * dollar-quote tag. Returns the index of the closing '$', or -1 if this is not a
     * dollar-quote opening.
     * Recognized: {@code $$} → returns dollarPos+1; {@code $tag$} → returns position of second '$'.
     */
    private static int scanDollarQuoteEnd(String sql, int dollarPos) {
        int scan = dollarPos + 1;
        if (scan >= sql.length()) {
            return -1;
        }
        // Bare $$: next char is also $
        if (sql.charAt(scan) == '$') {
            return scan;
        }
        // $tag$: scan alphanumeric/underscore tag, then closing $
        if (Character.isLetter(sql.charAt(scan)) || sql.charAt(scan) == '_') {
            while (scan < sql.length()
                    && (Character.isLetterOrDigit(sql.charAt(scan)) || sql.charAt(scan) == '_')) {
                scan++;
            }
            if (scan < sql.length() && sql.charAt(scan) == '$') {
                return scan;
            }
        }
        return -1;
    }

    /**
     * Checks whether {@code sql} at position {@code pos} matches the given {@code delimiter} string.
     */
    private static boolean matchesCurrentDelimiter(String sql, int pos, String delimiter) {
        if (delimiter == null || delimiter.isEmpty() || pos + delimiter.length() > sql.length()) {
            return false;
        }
        return sql.substring(pos, pos + delimiter.length()).equals(delimiter);
    }

    private static boolean shouldKeepRoutineTerminatorWithPreviousSegment(String sql, int nextIndex) {
        int index = skipIgnorableSql(nextIndex, sql);
        if (index >= sql.length() || !startsWithIgnoreCase(sql, index, "end")) {
            return false;
        }
        index = skipWhitespace(sql, index + 3);
        if (index >= sql.length()) {
            return false;
        }
        char c = sql.charAt(index);
        if (c == ';') {
            return true;
        }
        if (c == '$' && sql.regionMatches(index, "$$", 0, 2)) {
            return true; // MySQL DELIMITER $$: END$$ is the terminator
        }
        if (startsWithIgnoreCase(sql, index, "procedure")
                || startsWithIgnoreCase(sql, index, "function")) {
            return true;
        }
        if (startsWithIgnoreCase(sql, index, "if")
                || startsWithIgnoreCase(sql, index, "loop")
                || startsWithIgnoreCase(sql, index, "case")) {
            return false;
        }
        return Character.isJavaIdentifierStart(c) || c == '"';
    }

    private static int skipIgnorableSql(int index, String sql) {
        int currentIndex = index;
        while (currentIndex < sql.length()) {
            currentIndex = skipWhitespace(sql, currentIndex);
            if (currentIndex + 1 < sql.length() && sql.charAt(currentIndex) == '-' && sql.charAt(currentIndex + 1) == '-') {
                currentIndex += 2;
                while (currentIndex < sql.length() && sql.charAt(currentIndex) != '\n' && sql.charAt(currentIndex) != '\r') {
                    currentIndex++;
                }
                continue;
            }
            if (currentIndex + 1 < sql.length() && sql.charAt(currentIndex) == '/' && sql.charAt(currentIndex + 1) == '*') {
                currentIndex += 2;
                while (currentIndex + 1 < sql.length()
                        && !(sql.charAt(currentIndex) == '*' && sql.charAt(currentIndex + 1) == '/')) {
                    currentIndex++;
                }
                if (currentIndex + 1 < sql.length()) {
                    currentIndex += 2;
                }
                continue;
            }
            break;
        }
        return currentIndex;
    }

    private static int getRoutineStatementEndIndex(Matcher matcher) {
        int endIndex = matcher.end("END");
        String matchedEnd = matcher.group("END");
        if (matchedEnd == null || matchedEnd.isEmpty()) {
            return endIndex;
        }

        int offset = matchedEnd.length();
        while (offset > 0 && Character.isWhitespace(matchedEnd.charAt(offset - 1))) {
            offset--;
        }
        if (offset > 0 && matchedEnd.charAt(offset - 1) == '/') {
            offset--;
            while (offset > 0 && Character.isWhitespace(matchedEnd.charAt(offset - 1))) {
                offset--;
            }
            return matcher.start("END") + offset;
        }
        return endIndex;
    }

    public static Sql modifySql(Sql sql, String addSql) {
        if (!sql.getSqlRemainder().trim().isEmpty()) {
            addSql = sql.getSqlRemainder() + addSql;
            sql.setSqlRemainder("");
        }
        if (sql.getSqlstr().isEmpty()) {
            sql.setBlockDepth(0);
            sql.setBlockName("");
            sql.setPlainBlockMode(false);
            addSql = stripLeadingSqlDelimiter(addSql);
            // Strip DELIMITER directive at the start of a statement — it's a meta-command,
            // not SQL. processSegments already handles delimiter changes; here we just
            // remove the DELIMITER line so it doesn't confuse statement classification.
            addSql = stripLeadingDelimiterDirective(addSql);
            // Detect GBase 8S SET ENVIRONMENT SQLMODE change
            String detectedMode = detectGbaseSqlmodeChange(addSql);
            if (detectedMode != null) {
                sql.setSqlmode(detectedMode);
            }
            if (addSql.isBlank()) {
                sql.setSqlEnd(false);
                return sql;
            }
        }
        sql.setSqlEnd(false);

        String effectiveDialect = resolveEffectiveDialect(sql);

        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
        );
        Matcher matcherAll = pattern.matcher(addSql);
        String checkText = addSql.trim();

        if (matcherAll.find()) {
            checkText = matcherAll.replaceAll("").trim();
        }

        if (sql.getSqlstr().isEmpty() && checkText.isEmpty()) {
            sql.setSqlType("");
            sql.setSqlStr("");
            sql.setSqlEnd(true);
            return sql;
        }

        if (sql.getSqlstr().isEmpty()) {
            if (checkText.toUpperCase().startsWith("SELECT") || checkText.toUpperCase().startsWith("WITH")) {
                pattern = Pattern.compile("(?i)\\binto\\b");
                Matcher matcher = pattern.matcher(checkText);
                if (matcher.find()) {
                    sql.setSqlType("SELECT_INTO");
                } else {
                    sql.setSqlType("SELECT");
                }
                sql.setSqlEnd(true);
            } else if (checkText.toUpperCase().startsWith("CALL") || checkText.toUpperCase().startsWith("EXECUTE")) {
                sql.setSqlType("CALL");
                sql.setSqlEnd(true);
            } else {
                Matcher matcher;
                boolean isMultiLine = isMultiLineSqlStart(addSql, effectiveDialect);
                boolean isAnonBlock = isAnonymousBlockStart(addSql, effectiveDialect);
                if (isMultiLine) {
                    sql.setSqlType("MULTI_LINE_SQL");
                    configureBlockState(sql, addSql);
                } else if (isAnonBlock) {
                    sql.setSqlType("CALL_BLOCK");
                    configureBlockState(sql, addSql);
                }

                if (isMultiLine || isAnonBlock) {
                    pattern = Pattern.compile(
                            STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                                    + "|(?<END>" + MULTI_LINE_END + ")"
                    );
                    matcher = pattern.matcher(addSql);
                    while (matcher.find()) {
                        if (matcher.group("END") != null) {
                            sql.setSqlRemainder(addSql.substring(matcher.end("END")));
                            addSql = addSql.substring(0, getRoutineStatementEndIndex(matcher));
                            sql.setSqlEnd(true);
                            break;
                        }
                    }
                }

                if (!sql.getSqlEnd() && sql.getPlainBlockMode()) {
                    updateBlockDepth(sql, addSql);
                    if (sql.getBlockDepth() <= 0 && containsPlainBlockEnd(addSql)) {
                        sql.setSqlEnd(true);
                        sql.setBlockDepth(0);
                    }
                }
                if (!sql.getSqlEnd() && !sql.getPlainBlockMode()) {
                    // Track block depth for named routines to prevent premature end on nested blocks
                    updateBlockDepth(sql, addSql);
                }
                if (!sql.getSqlEnd() && containsNamedBlockEnd(addSql, sql.getBlockName())) {
                    if (sql.getPlainBlockMode() || sql.getBlockDepth() <= 0) {
                        sql.setSqlEnd(true);
                        sql.setBlockDepth(0);
                    }
                }

                if (!sql.getSqlType().equals("MULTI_LINE_SQL") && !sql.getSqlType().equals("CALL_BLOCK")) {
                    sql.setSqlEnd(true);
                    pattern = Pattern.compile(
                            STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT + "|" + DROP_DATABASE
                                    + "|" + CREATE_DATABASE
                    );
                    matcher = pattern.matcher(addSql);
                    while (matcher.find()) {
                        if (matcher.group("dbname") != null) {
                            sql.setSqlType("DATABASE " + matcher.group("dbname").toLowerCase());
                        }
                    }
                }
            }
            sql.setSqlStr(addSql);
        } else {
            if (sql.getSqlType().equals("MULTI_LINE_SQL") || sql.getSqlType().equals("CALL_BLOCK")) {
                pattern = Pattern.compile(
                        STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                                + "|(?<END>" + MULTI_LINE_END + ")"
                );
                Matcher matcher = pattern.matcher(addSql);
                while (matcher.find()) {
                    if (matcher.group("END") != null) {
                        sql.setSqlRemainder(addSql.substring(matcher.end("END")));
                        addSql = addSql.substring(0, getRoutineStatementEndIndex(matcher));
                        sql.setSqlEnd(true);
                        break;
                    }
                }
                if (!sql.getSqlEnd() && sql.getPlainBlockMode()) {
                    updateBlockDepth(sql, addSql);
                    if (sql.getBlockDepth() <= 0 && containsPlainBlockEnd(addSql)) {
                        sql.setSqlEnd(true);
                        sql.setBlockDepth(0);
                    }
                }
                if (!sql.getSqlEnd() && !sql.getPlainBlockMode()) {
                    // Track block depth for named routines to prevent premature end on nested blocks
                    updateBlockDepth(sql, addSql);
                }
                if (!sql.getSqlEnd() && containsNamedBlockEnd(addSql, sql.getBlockName())) {
                    if (sql.getPlainBlockMode() || sql.getBlockDepth() <= 0) {
                        sql.setSqlEnd(true);
                        sql.setBlockDepth(0);
                    }
                }
                sql.setSqlStr(sql.getSqlstr() + addSql);
            }
        }

        return sql;
    }

    public static boolean sqlContrainCommit(String remainderSql) {
        if (remainderSql == null || remainderSql.isBlank()) {
            return false;
        }
        // Strip leading delimiters and protected content to avoid false positives
        // from whitespace-only, comment-only, or slash-only remainders
        String cleaned = stripLeadingSqlDelimiter(remainderSql);
        if (cleaned.isBlank()) {
            return false;
        }
        String stripped = STATEMENT_PROTECT_PATTERN.matcher(cleaned).replaceAll("").trim();
        if (stripped.isEmpty() || stripped.matches("[/\\s]+")) {
            return false;
        }

        boolean result = false;
        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                        + "|(?<END>" + MULTI_LINE_END + ")"
        );
        Matcher matcher = pattern.matcher(cleaned);
        while (matcher.find()) {
            if (matcher.group("END") != null) {
                result = true;
            }
            break;
        }

        if (!result) {
            boolean containBegin = isMultiLineSqlStart(cleaned) || isAnonymousBlockStart(cleaned);
            if (!containBegin && isExecutableStatement(cleaned)) {
                result = true;
            }
        }

        return result;
    }

    public static boolean sqlContrainMoreThanOneCommit(String remainderSql) {
        boolean result = false;
        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                        + "|(?<END>" + MULTI_LINE_END + ")"
        );
        int count = 0;
        Matcher matcher = pattern.matcher(remainderSql);
        while (matcher.find()) {
            if (matcher.group("END") != null) {
                count++;
            }
            if (count > 1) {
                result = true;
            }
        }
        return result;
    }

    private static int countExecutableStatements(String sqlText, int stopAfterCount) {
        Sql[] currentSql = {new Sql()};
        int[] statementCount = {0};
        boolean[] stoppedEarly = {false};

        processSegments(sqlText, segment -> {
            checkCountInterrupted();
            String sqlChunk = segment.getText();
            boolean sqlContainsCommit;
            do {
                checkCountInterrupted();
                currentSql[0] = modifySql(currentSql[0], sqlChunk);
                if (currentSql[0].getSqlEnd() && isExecutableStatement(currentSql[0].getSqlstr())) {
                    statementCount[0]++;
                    if (statementCount[0] >= stopAfterCount) {
                        stoppedEarly[0] = true;
                        return false;
                    }
                    resetSqlStatementState(currentSql[0]);
                }
                sqlChunk = "";
                sqlContainsCommit = sqlContrainCommit(currentSql[0].getSqlRemainder());
            } while (sqlContainsCommit);
            return true;
        });

        checkCountInterrupted();
        if (!stoppedEarly[0] && isExecutableStatement(currentSql[0].getSqlstr())) {
            statementCount[0]++;
        }
        return statementCount[0];
    }

    private static void checkCountInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("sql statement counting cancelled");
        }
    }

    private static void resetSqlStatementState(Sql sql) {
        sql.setSqlStr("");
        sql.setSqlEnd(false);
        sql.setSqlType("");
        sql.setBlockDepth(0);
        sql.setBlockName("");
        sql.setPlainBlockMode(false);
    }

    private static String stripProtectedContent(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        return STATEMENT_PROTECT_PATTERN.matcher(sql).replaceAll("");
    }

    private static String stripCommentsOnly(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        return COMMENT_ONLY_PATTERN.matcher(sql).replaceAll(" ");
    }

    private static String stripLeadingSqlDelimiter(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        int offset = 0;
        while (offset < sql.length()) {
            int lineEnd = offset;
            while (lineEnd < sql.length() && sql.charAt(lineEnd) != '\n' && sql.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            String line = sql.substring(offset, lineEnd);
            String trimmedLine = line.trim();
            int nextLineStart = lineEnd;
            if (nextLineStart < sql.length() && sql.charAt(nextLineStart) == '\r') {
                nextLineStart++;
            }
            if (nextLineStart < sql.length() && sql.charAt(nextLineStart) == '\n') {
                nextLineStart++;
            }
            if (trimmedLine.isEmpty() || trimmedLine.equals("/")) {
                offset = nextLineStart;
                continue;
            }
            break;
        }
        return sql.substring(offset);
    }

    /**
     * Strips a leading MySQL {@code DELIMITER xxx} directive line.
     * The delimiter change is already handled by {@code processSegments()};
     * this just removes the directive line so it doesn't interfere with
     * statement classification in {@code modifySql()}.
     */
    private static String stripLeadingDelimiterDirective(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String trimmed = sql.stripLeading();
        if (trimmed.regionMatches(true, 0, "delimiter", 0, "delimiter".length())) {
            int lineEnd = trimmed.indexOf('\n');
            if (lineEnd < 0) {
                return ""; // entire text is a delimiter directive
            }
            return trimmed.substring(lineEnd + 1);
        }
        return sql;
    }

    /**
     * Maps the raw dbtype + sqlmode to a standardised parser dialect key.
     * GBase 8S with sqlmode=gbase (default) → INFORMIX,
     * sqlmode=oracle → ORACLE, sqlmode=mysql → MYSQL.
     */
    private static String resolveEffectiveDialect(Sql sql) {
        if (sql == null) return "";
        String sqlmode = sql.getSqlmode();
        if (sqlmode != null && !sqlmode.isBlank()) {
            switch (sqlmode.toLowerCase()) {
                case "oracle": return "ORACLE";
                case "mysql":  return "MYSQL";
                case "gbase":  return "INFORMIX";
                default:       break;
            }
        }
        String raw = sql.getDialect();
        if (raw == null || raw.isEmpty()) return "";
        String upper = raw.toUpperCase().trim();
        if ("GBASE 8S".equalsIgnoreCase(upper)) return "INFORMIX";
        if ("ORACLE".equalsIgnoreCase(upper)) return "ORACLE";
        if ("DAMENG".equalsIgnoreCase(upper)) return "ORACLE";
        if ("MYSQL".equalsIgnoreCase(upper)) return "MYSQL";
        if ("POSTGRESQL".equalsIgnoreCase(upper)) return "POSTGRESQL";
        if ("INFORMIX".equalsIgnoreCase(upper)) return "INFORMIX";
        if ("SQLITE".equalsIgnoreCase(upper)) return "SQLITE";
        return upper;
    }

    /**
     * Detects a GBase 8S {@code SET ENVIRONMENT SQLMODE 'xxx'} statement
     * and returns the sqlmode value (gbase / oracle / mysql), or null.
     */
    private static String detectGbaseSqlmodeChange(String addSql) {
        if (addSql == null || addSql.isBlank()) return null;
        String normalized = addSql.toLowerCase().replaceAll("[ \\t\\r\\n]+", "");
        if (normalized.startsWith("setenvironmentsqlmode'gbase'")) return "gbase";
        if (normalized.startsWith("setenvironmentsqlmode'oracle'")) return "oracle";
        if (normalized.startsWith("setenvironmentsqlmode'mysql'")) return "mysql";
        if (normalized.startsWith("setenvironmentsqlmode\"gbase\"")) return "gbase";
        if (normalized.startsWith("setenvironmentsqlmode\"oracle\"")) return "oracle";
        if (normalized.startsWith("setenvironmentsqlmode\"mysql\"")) return "mysql";
        return null;
    }

    private static void updateBlockDepth(Sql sql, String addSql) {
        int blockDepth = sql.getBlockDepth();
        Matcher matcher = BLOCK_DEPTH_TOKEN_PATTERN.matcher(addSql);
        while (matcher.find()) {
            if (matcher.group("BEGIN") != null) {
                blockDepth++;
            } else if ((matcher.group("PLAINEND") != null || matcher.group("NAMEDEND") != null)
                    && blockDepth > 0) {
                blockDepth--;
            }
        }
        sql.setBlockDepth(blockDepth);
    }

    private static void configureBlockState(Sql sql, String addSql) {
        sql.setBlockName(extractBlockName(addSql));
        sql.setPlainBlockMode(usesPlainBlockEnd(sql.getSqlType(), addSql));
    }

    private static boolean isMultiLineSqlStart(String sql) {
        return isMultiLineSqlStart(sql, "");
    }

    private static boolean isMultiLineSqlStart(String sql, String effectiveDialect) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }
        if (TRIGGER_DECLARATION_PATTERN.matcher(normalized).find()) {
            // Oracle-style triggers have BEGIN...END body → multi-line.
            // PostgreSQL-style triggers use EXECUTE FUNCTION/PROCEDURE → single-statement.
            if (isPostgreSqlTriggerStyle(normalized)) {
                return false;
            }
            return true;
        }
        if (TYPE_DECLARATION_PATTERN.matcher(normalized).find()) {
            // Only TYPE BODY needs multi-line handling (Oracle/Dameng style).
            // Simple CREATE TYPE ... AS (...) / CREATE TYPE ... AS ENUM (...)
            // (PostgreSQL) end with ); — treat as single-statement.
            Matcher typeMatcher = TYPE_DECLARATION_PATTERN.matcher(normalized);
            if (typeMatcher.find() && !" body".equalsIgnoreCase(
                    typeMatcher.group(1) != null ? typeMatcher.group(1).trim() : "")) {
                return false;
            }
            String lowerNormalized = normalized.toLowerCase(Locale.ROOT);
            if (lowerNormalized.contains(" as") || lowerNormalized.contains(" is")) {
                return true;
            }
            if (Pattern.compile("(?im)^\\s*(as|is)\\b").matcher(normalized).find()) {
                return true;
            }
            return false;
        }
        // PACKAGE only exists in Oracle/Dameng — skip for other dialects
        if (!"POSTGRESQL".equals(effectiveDialect) && !"MYSQL".equals(effectiveDialect)
                && !"SQLITE".equals(effectiveDialect) && !"INFORMIX".equals(effectiveDialect)) {
            if (PACKAGE_DECLARATION_PATTERN.matcher(normalized).find()) {
                String lowerNormalized = normalized.toLowerCase(Locale.ROOT);
                if (lowerNormalized.contains(" as") || lowerNormalized.contains(" is")) {
                    return true;
                }
                if (Pattern.compile("(?im)^\\s*(as|is)\\b").matcher(normalized).find()) {
                    return true;
                }
                return false;
            }
        }

        Matcher matcher = ROUTINE_DECLARATION_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return false;
        }

        String routineType = matcher.group("TYPE").toLowerCase(Locale.ROOT);
        int index = skipWhitespace(normalized, matcher.end());
        if (startsWithIgnoreCase(normalized, index, "if not exists")) {
            index = skipWhitespace(normalized, index + "if not exists".length());
        }

        int nameEnd = skipQualifiedName(normalized, index);
        if (nameEnd <= index) {
            return false;
        }

        int remainderStart = skipWhitespace(normalized, nameEnd);
        if (remainderStart < normalized.length() && normalized.charAt(remainderStart) == '(') {
            int closeParenIndex = findMatchingParenthesis(normalized, remainderStart);
            if (closeParenIndex < 0) {
                return false;
            }
            remainderStart = skipWhitespace(normalized, closeParenIndex + 1);
        }

        String remainder = normalized.substring(remainderStart).trim().toLowerCase(Locale.ROOT);
        if (remainder.isEmpty()) {
            return false;
        }

        if ("function".equals(routineType)) {
            return remainder.startsWith("return")
                    || remainder.startsWith("returns")
                    || remainder.startsWith("returning")
                    || remainder.startsWith("as")
                    || remainder.startsWith("is");
        }

        return remainder.startsWith("as")
                || remainder.startsWith("is")
                || remainder.startsWith("begin")
                || remainder.startsWith("define");
    }

    private static boolean isAnonymousBlockStart(String sql) {
        return isAnonymousBlockStart(sql, "");
    }

    private static boolean isAnonymousBlockStart(String sql, String effectiveDialect) {
        return NO_NAME_BLOCK_PATTERN.matcher(stripCommentsOnly(sql)).find();
    }

    /**
     * PostgreSQL-style triggers use {@code EXECUTE FUNCTION procname()} or
     * {@code EXECUTE PROCEDURE procname()} instead of a PL/SQL BEGIN...END body.
     * They are single-statement, not multi-line.
     */
    private static boolean isPostgreSqlTriggerStyle(String normalized) {
        // Look for EXECUTE FUNCTION or EXECUTE PROCEDURE after FOR EACH ROW/STATEMENT
        return Pattern.compile(
                "(?i)\\bfor\\s+each\\s+(row|statement)\\b[\\s\\S]*\\bexecute\\s+(function|procedure)\\b"
        ).matcher(normalized).find();
    }

    private static int skipWhitespace(String sql, int index) {
        int result = index;
        while (result < sql.length() && Character.isWhitespace(sql.charAt(result))) {
            result++;
        }
        return result;
    }

    private static boolean startsWithIgnoreCase(String text, int start, String value) {
        if (start < 0 || start + value.length() > text.length()) {
            return false;
        }
        return text.regionMatches(true, start, value, 0, value.length());
    }

    private static int skipQualifiedName(String sql, int index) {
        int result = index;
        while (result < sql.length()) {
            char current = sql.charAt(result);
            if (Character.isWhitespace(current) || current == '(') {
                break;
            }
            if (current == '"') {
                result++;
                while (result < sql.length() && sql.charAt(result) != '"') {
                    result++;
                }
                if (result < sql.length()) {
                    result++;
                }
                continue;
            }
            result++;
        }
        return result;
    }

    private static boolean usesPlainBlockEnd(String sqlType, String addSql) {
        if ("CALL_BLOCK".equals(sqlType)) {
            return true;
        }
        if (!"MULTI_LINE_SQL".equals(sqlType)) {
            return false;
        }
        String normalized = stripProtectedContent(addSql).trim().toLowerCase(Locale.ROOT);
        return ORACLE_PLAIN_BLOCK_OBJECT_HEAD_PATTERN.matcher(normalized).find()
                || normalized.startsWith("create procedure if not exists ")
                || normalized.startsWith("create function if not exists ");
    }

    private static String extractBlockName(String addSql) {
        String normalized = stripCommentsOnly(addSql).trim();
        Matcher matcher = BLOCK_NAME_DECLARATION_PATTERN.matcher(normalized);
        if (matcher.find()) {
            int index = skipWhitespace(normalized, matcher.end());
            if (startsWithIgnoreCase(normalized, index, "if not exists")) {
                index = skipWhitespace(normalized, index + "if not exists".length());
            }
            int nameEnd = skipQualifiedName(normalized, index);
            if (nameEnd > index) {
                return extractLastIdentifier(normalized.substring(index, nameEnd));
            }
        }
        return "";
    }

    private static String extractLastIdentifier(String identifierChain) {
        if (identifierChain == null || identifierChain.isBlank()) {
            return "";
        }
        String value = identifierChain.trim();
        int lastDot = -1;
        boolean inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '"') {
                inQuotes = !inQuotes;
            } else if (current == '.' && !inQuotes) {
                lastDot = i;
            }
        }
        String lastPart = lastDot >= 0 ? value.substring(lastDot + 1) : value;
        return normalizeIdentifier(lastPart);
    }

    private static String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String normalized = identifier.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean containsPlainBlockEnd(String addSql) {
        Matcher matcher = PLAIN_BLOCK_END_PATTERN.matcher(addSql);
        while (matcher.find()) {
            if (matcher.group("PLAINEND") != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNamedBlockEnd(String addSql, String blockName) {
        if (blockName == null || blockName.isBlank()) {
            return false;
        }
        Matcher matcher = NAMED_BLOCK_END_PATTERN.matcher(addSql);
        while (matcher.find()) {
            if (matcher.group("NAMEDEND") != null) {
                String matchedName = normalizeIdentifier(matcher.group("ENDNAME"));
                if (blockName.equals(matchedName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String getFromTable(String sql) {
        String fromTable = null;
        Pattern pattern = Pattern.compile(
                "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                        + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                        + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
        );
        Matcher matcherAll = pattern.matcher(sql);
        String checkText = sql.trim();

        if (matcherAll.find()) {
            checkText = matcherAll.replaceAll("").trim();
        }

        while (checkText.contains("(")) {
            checkText = checkText.replaceAll("\\([^()]*\\)", "");
        }
        pattern = Pattern.compile("(?i)\\b(WHERE|ORDER|GROUP)\\b.*", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(checkText);
        if (matcher.find()) {
            checkText = matcher.replaceAll("").trim();
        }

        pattern = Pattern.compile("(?i)\\bfrom\\b\\s+(\\S+)(?!.*,|.*\\bjoin\\b.*)", Pattern.DOTALL);
        matcher = pattern.matcher(checkText);
        if (matcher.find()) {
            fromTable = matcher.group(1).replaceAll(";", "");
        }
        return fromTable;
    }

    public static List<String> getSelectedCols(String sql, List<String> cols) {
        String regex = "(?i)SELECT\\s+(.*?)\\s+FROM(?![^(]*\\))";

        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        String selectContent = null;
        if (matcher.find()) {
            selectContent = matcher.group(1).trim();
        }

        String input = selectContent;
        List<String> result = new ArrayList<>();
        StringBuilder currentPart = new StringBuilder();
        int parenthesesLevel = 0;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (ch == '(') {
                parenthesesLevel++;
                currentPart.append(ch);
            } else if (ch == ')') {
                parenthesesLevel--;
                currentPart.append(ch);
            } else if (ch == ',' && parenthesesLevel == 0) {
                result.add(currentPart.toString().replaceAll("(?i)\\b+AS\\b(?s).*", "").trim().replaceAll("\\s[^\\s]*$", "").toLowerCase());
                currentPart.setLength(0);
            } else {
                currentPart.append(ch);
            }
        }

        if (currentPart.length() > 0) {
            result.add(currentPart.toString().replaceAll("(?i)\\b+AS\\b(?s).*", "").trim().replaceAll("\\s[^\\s]*$", "").toLowerCase());
        }
        /*
         * Expand * and alias.* (e.g. SELECT ROWID, t.* FROM schema.t t) so result-set edit
         * gets real column names, not "t.*" -> bare "*" -> invalid SET *=? on Oracle.
         */
        for (int i = 0; i < result.size(); i++) {
            String token = result.get(i).trim();
            if (isSelectListWildcardToken(token)) {
                result.remove(i);
                result.addAll(i, cols);
                i += Math.max(0, cols.size() - 1);
            }
        }
        return result;
    }

    /**
     * True for {@code *} or table-alias wildcard {@code t.*} / {@code alias.*} (single-segment alias).
     */
    public static boolean isSelectListWildcardToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String t = token.trim();
        if ("*".equals(t)) {
            return true;
        }
        return t.matches("(?i)^[a-z0-9_$#]+\\.\\*$");
    }

    public static List<PackageMember> parsePackageMembers(String packageDdl) {
        List<PackageMember> members = new ArrayList<>();
        if (packageDdl == null || packageDdl.isEmpty()) {
            return members;
        }

        Pattern bodyPattern = Pattern.compile(
                "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                        + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                        + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                        + "|(?<BODY>" + PACKAGE_BODY_PATTERN + ")"
        );
        Matcher bodyMatcher = bodyPattern.matcher(packageDdl);
        String bodySql = "";
        while (bodyMatcher.find()) {
            if (bodyMatcher.group("BODY") != null) {
                bodySql = packageDdl.substring(bodyMatcher.start("BODY"));
            }
        }
        if (bodySql.isEmpty()) {
            bodySql = extractPackageBodyAfterSqlPlusSlash(packageDdl);
        }
        if (bodySql.isEmpty()) {
            bodySql = packageDdl;
        }

        Pattern memberPattern = Pattern.compile(
                STRING_PATTERN_TEXT
                        + "|" + DOUBLE_STRING_PATTERN_TEXT
                        + "|" + COMMENT_PATTERN_TEXT
                        + "|" + PACKAGE_MEMBER_PATTERN
        );
        Matcher memberMatcher = memberPattern.matcher(bodySql);
        LinkedHashMap<String, PackageMember> dedup = new LinkedHashMap<>();
        while (memberMatcher.find()) {
            if (memberMatcher.group("FUNC") != null) {
                String n = memberMatcher.group("FUNC");
                dedup.putIfAbsent("F:" + n.toLowerCase(Locale.ROOT), new PackageMember(n, "FUNC"));
            }
            if (memberMatcher.group("PROC") != null) {
                String n = memberMatcher.group("PROC");
                dedup.putIfAbsent("P:" + n.toLowerCase(Locale.ROOT), new PackageMember(n, "PROC"));
            }
        }
        members.addAll(dedup.values());

        return members;
    }

    /**
     * When spec+body are concatenated with a SQL*Plus {@code /} line, prefer scanning only the body so forward
     * declarations in the spec are not merged with implementations (which produced duplicate tree nodes).
     */
    private static String extractPackageBodyAfterSqlPlusSlash(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return "";
        }
        String[] chunks = ddl.split("\\R\\s*/\\s*\\R");
        Pattern bodyStart = Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?(?:editionable\\s+|editioning\\s+|noneditionable\\s+)?package\\s+body\\b");
        for (String chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            if (bodyStart.matcher(chunk.stripLeading()).find()) {
                return chunk;
            }
        }
        return "";
    }

    public static String printPackageFunction(String packagesql, String function) {
        if (packagesql == null || packagesql.isBlank() || function == null || function.isBlank()) {
            return "";
        }

        String functionString = "";
        String stringPattern = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
        String doubleStringPattern = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
        String commentPattern = "--[^\\n]*" + "|" + "/\\*[\\s\\S]*?\\*/" + "|" + "/\\*[\\s\\S]*" + "|" + "\\{[\\s\\S]*?\\}";
        String bodyPattern = "(?i)\\bcreate\\s+(OR\\s+REPLACE\\s+)?package\\s+body\\b[\\s\\S]*?\\b(AS|IS)\\b";

        String normalizedName = function.trim().replace("\"", "");
        String escapedName = Pattern.quote(normalizedName);
        String namePattern = "\\\"?" + escapedName + "\\\"?";
        String functionPattern =
                "(?is)\\bfunction\\s+" + namePattern +
                        "\\s*(\\([\\s\\S]*?\\))?\\s+return\\s+([a-zA-Z0-9_$.\\\"]+)" +
                        "\\s*(PIPELINED\\s+|DETERMINISTIC\\s+|RESULT_CACHE\\s+)?(AS|IS)\\b" +
                        "[\\s\\S]*?\\bend\\s*(" + namePattern + ")?\\s*;"
                        + "|"
                        + "(?is)\\bprocedure\\s+" + namePattern +
                        "\\s*(\\([\\s\\S]*?\\))?\\s*(AS|IS)\\b" +
                        "[\\s\\S]*?\\bend\\s*(" + namePattern + ")?\\s*;";

        Pattern pattern = Pattern.compile(
                "(?<STRING>" + stringPattern + ")"
                        + "|(?<DOUBLESTRING>" + doubleStringPattern + ")"
                        + "|(?<COMMENT>" + commentPattern + ")"
                        + "|(?<BODY>" + bodyPattern + ")"
        );
        Matcher matcher = pattern.matcher(packagesql);
        String bodySql = "";
        while (matcher.find()) {
            if (matcher.group("BODY") != null) {
                bodySql = packagesql.substring(matcher.start("BODY"));
            }
        }
        if (bodySql.isEmpty()) {
            bodySql = packagesql;
        }

        pattern = Pattern.compile(
                "(?<STRING>" + stringPattern + ")"
                        + "|(?<DOUBLESTRING>" + doubleStringPattern + ")"
                        + "|(?<COMMENT>" + commentPattern + ")"
                        + "|(?<FUNC>" + functionPattern + ")"
        );
        matcher = pattern.matcher(bodySql);
        while (matcher.find()) {
            if (matcher.group("FUNC") != null) {
                functionString = matcher.group("FUNC");
                break;
            }
        }
        return functionString;
    }

    private static int findMatchingParenthesis(String sql, int openParenIndex) {
        int depth = 0;
        for (int i = openParenIndex; i < sql.length(); i++) {
            char currentChar = sql.charAt(i);
            if (currentChar == '(') {
                depth++;
            } else if (currentChar == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static String upperSql(String sql) {
        Map<String, String> placeholders = new HashMap<>();
        int[] index = {0};
        sql = protectPattern(sql, STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, DOUBLE_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, FANYINHAO_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, COMMENT_PATTERN, placeholders, index);
        sql=sql.toUpperCase();
        //恢复注释和字符串
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace(entry.getKey(), entry.getValue());
        }
        return sql;
    }

    public static String lowerSql(String sql) {
        Map<String, String> placeholders = new HashMap<>();
        int[] index = {0};
        sql = protectPattern(sql, STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, DOUBLE_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, FANYINHAO_STRING_PATTERN, placeholders, index);
        sql = protectPattern(sql, COMMENT_PATTERN, placeholders, index);
        sql=sql.toLowerCase().replaceAll("__placeholder_","__PLACEHOLDER_");
        //恢复注释和字符串
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace(entry.getKey(), entry.getValue());
        }
        return sql;
    }
    private static String protectPattern(String sql, Pattern pattern, Map<String, String> placeholders, int[] index) {
        Matcher matcher = pattern.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder="";
            String match="";
            if(pattern.equals(COMMENT_PATTERN)){
                placeholder = "__PLACEHOLDER_COMMENT_" + index[0] + "__";
                match = matcher.group();
            }else{
                placeholder = "__PLACEHOLDER_" + index[0] + "__";
                match = matcher.group();
            }
            placeholders.put(placeholder, match);
            matcher.appendReplacement(sb, placeholder);
            index[0]++;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    public static void main(String[] args) {
    }
}
