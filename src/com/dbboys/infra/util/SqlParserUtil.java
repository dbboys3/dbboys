package com.dbboys.infra.util;

import com.dbboys.core.SqlParser;
import com.dbboys.model.Sql;

import java.util.*;
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
    private static final Pattern COMMENT_ONLY_PATTERN = Pattern.compile(COMMENT_PATTERN_TEXT);
    private static final Pattern STATEMENT_PROTECT_PATTERN = Pattern.compile(
            STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + FANYINHAO_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
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
        private final int startIndex;
        private final int endIndex;

        public Segment(String text, int endIndex) {
            this(text, 0, endIndex);
        }

        public Segment(String text, int startIndex, int endIndex) {
            this.text = text;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }

        public String getText() {
            return text;
        }

        public int getStartIndex() {
            return startIndex;
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
    public interface SegmentHandler {
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

    public static StatementRange findStatementRangeAtCaret(String sqlText, int caretPosition,
                                                           SqlParser parser) {
        if (sqlText == null || sqlText.isBlank()) {
            return null;
        }
        List<StatementRange> ranges = collectExecutableStatementRanges(sqlText, parser);
        if (ranges.isEmpty()) {
            return null;
        }

        int clampedCaret = Math.max(0, Math.min(caretPosition, sqlText.length()));
        StatementRange range = findContainingRange(ranges, clampedCaret);
        if (range != null) {
            return expandDelimiterBlockRange(sqlText, range);
        }
        if (clampedCaret > 0) {
            range = findContainingRange(ranges, clampedCaret - 1);
            if (range != null) {
                return expandDelimiterBlockRange(sqlText, range);
            }
        }
        range = findDelimiterBlockRangeForCaret(sqlText, clampedCaret, ranges);
        if (range != null) {
            return range;
        }
        range = findStatementRangeForCaretInOracleSlashGap(ranges, sqlText, clampedCaret);
        return expandDelimiterBlockRange(sqlText, range);
    }

    /**
     * When the caret is on a {@code DELIMITER xx} line or on the matching closing
     * delimiter, maps it to the single statement enclosed by the delimiter block.
     */
    private static StatementRange findDelimiterBlockRangeForCaret(String sqlText,
                                                                  int caret,
                                                                  List<StatementRange> ranges) {
        if (sqlText == null || ranges == null || ranges.isEmpty()) {
            return null;
        }
        for (StatementRange range : ranges) {
            StatementRange expanded = expandDelimiterBlockRange(sqlText, range);
            if (expanded != range) {
                int whitespaceEnd = expanded.getEnd();
                while (whitespaceEnd < sqlText.length()
                        && Character.isWhitespace(sqlText.charAt(whitespaceEnd))) {
                    whitespaceEnd++;
                }
                if (caret >= expanded.getStart() && caret <= whitespaceEnd) {
                    return expanded;
                }
            }
        }
        return null;
    }

    /**
     * Expands a delimiter block's content range to also cover the leading
     * {@code DELIMITER xx} line and the trailing custom delimiter, so the whole
     * block can be selected and executed as one statement. Plain statements are
     * returned unchanged.
     */
    private static StatementRange expandDelimiterBlockRange(String sqlText,
                                                            StatementRange range) {
        if (sqlText == null || range == null) {
            return range;
        }
        int lineStart = lineStartBefore(range.getStart(), sqlText);
        String line = sqlText.substring(lineStart, range.getStart()).trim();
        if (!startsWithIgnoreCase(line, 0, "delimiter")) {
            return range;
        }
        int tokenStart = "delimiter".length();
        while (tokenStart < line.length() && Character.isWhitespace(line.charAt(tokenStart))) {
            tokenStart++;
        }
        int tokenEnd = tokenStart;
        while (tokenEnd < line.length() && !Character.isWhitespace(line.charAt(tokenEnd))) {
            tokenEnd++;
        }
        if (tokenStart >= tokenEnd) {
            return range;
        }
        String token = line.substring(tokenStart, tokenEnd);
        if (";".equals(token)) {
            return range;
        }

        int closeStart = skipWhitespace(sqlText, range.getEnd());
        if (closeStart + token.length() > sqlText.length()
                || !sqlText.regionMatches(true, closeStart, token, 0, token.length())) {
            return range;
        }
        int closeEnd = closeStart + token.length();
        if (lineStart < range.getStart()) {
            return new StatementRange(lineStart, closeEnd);
        }
        return range;
    }

    private static int lineStartBefore(int end, String text) {
        int idx = end - 1;
        while (idx >= 0 && (text.charAt(idx) == '\n' || text.charAt(idx) == '\r')) {
            idx--;
        }
        while (idx >= 0 && text.charAt(idx) != '\n' && text.charAt(idx) != '\r') {
            idx--;
        }
        return idx + 1;
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

    private static List<StatementRange> collectExecutableStatementRanges(String sqlText,
                                                                           SqlParser parser) {
        List<StatementRange> ranges = new ArrayList<>();
        Sql currentSql = new Sql();
        int currentStatementStart = 0;

        for (Segment segment : split(sqlText)) {
            String sqlChunk = segment.getText();
            int segmentStart = segment.getStartIndex();
            if (currentSql.getSqlstr().isEmpty() && currentSql.getSqlRemainder().isEmpty()) {
                currentStatementStart = segmentStart + leadingDelimiterOffset(sqlChunk);
            }

            boolean sqlContainsCommit;
            do {
                currentSql = parser.modifySql(currentSql, sqlChunk);
                if (currentSql.getSqlEnd()) {
                    appendExecutableRange(sqlText, ranges, currentStatementStart, currentSql.getSqlstr());
                    String remainder = currentSql.getSqlRemainder();
                    resetSqlStatementState(currentSql);
                    if (remainder != null && !remainder.isEmpty()) {
                        int remainderStart = segment.getStartIndex() + segment.getText().length() - remainder.length();
                        currentStatementStart = remainderStart + leadingDelimiterOffset(remainder);
                    }
                }
                sqlChunk = "";
                sqlContainsCommit = parser.hasMoreStatements(currentSql.getSqlRemainder());
            } while (sqlContainsCommit);

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

    public static boolean processSegmentsPublic(String sql, SegmentHandler handler) {
        return processSegments(sql, handler);
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
        int segmentStart = 0;

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';

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
                while (dEnd < sql.length() && sql.charAt(dEnd) != '\n') {
                    dEnd++;
                }
                if (dEnd < sql.length() && sql.charAt(dEnd) == '\n') {
                    dEnd++;
                }
                buffer.setLength(0);
                segmentStart = dEnd;
                i = dEnd - 1; // -1 because for loop does i++
                continue;
            }

            buffer.append(current);

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

            // Dollar-quote detection (PostgreSQL $$...$$ and $tag$...$tag$).
            // A custom DELIMITER (e.g. "$$") wins over dollar-quote interpretation,
            // otherwise the terminator would be swallowed as an unclosed dollar quote.
            if (";".equals(currentDelimiter) && !inSingleQuote && !inDoubleQuote && !inBacktickQuote
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
                int delimiterStart = atDelimiter ? i : -1;
                // Trim trailing delimiter chars from segment for non-';' delimiters
                // so the segment text doesn't end with (e.g.) '$$'
                if (atDelimiter && currentDelimiter.length() > 1) {
                    // Only the first delimiter character was appended to the buffer,
                    // so remove exactly one char (e.g. the first '$' of "$$$").
                    if (segText.length() >= 1) {
                        segText = segText.substring(0, segText.length() - 1);
                    }
                }
                if (!handler.handle(new Segment(segText, segmentStart, i))) {
                    return false;
                }
                buffer.setLength(0);
                // Skip past multi-char delimiter
                if (atDelimiter && currentDelimiter.length() > 1) {
                    i += currentDelimiter.length() - 1;
                }
                // A custom delimiter closed on its own line terminates the block:
                // reset to ";" so SQL after the block parses normally. Delimiters
                // attached to a statement (e.g. "END$$") keep MySQL semantics and
                // stay active until an explicit "delimiter ;".
                if (atDelimiter && !";".equals(currentDelimiter)
                        && isStandaloneDelimiterLine(sql, delimiterStart, currentDelimiter)) {
                    currentDelimiter = ";";
                }
                segmentStart = i + 1;
            }
        }
        return true;
    }

    private static boolean isStandaloneDelimiterLine(String sql, int delimiterStart,
                                                     String delimiter) {
        int lineStart = delimiterStart;
        while (lineStart > 0 && sql.charAt(lineStart - 1) != '\n'
                && sql.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        int lineEnd = delimiterStart;
        while (lineEnd < sql.length() && sql.charAt(lineEnd) != '\n'
                && sql.charAt(lineEnd) != '\r') {
            lineEnd++;
        }
        return sql.substring(lineStart, lineEnd).trim().equals(delimiter);
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

    // ===== Remaining utility methods (modifySql, sqlContrainCommit, etc. moved to CommonSqlParser) =====

    private static String stripProtectedContent(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        return STATEMENT_PROTECT_PATTERN.matcher(sql).replaceAll("");
    }

    private static void resetSqlStatementState(Sql sql) {
        sql.setSqlStr("");
        sql.setSqlEnd(false);
        sql.setSqlType("");
        sql.setBlockDepth(0);
        sql.setBlockName("");
        sql.setPlainBlockMode(false);
    }

    static String stripCommentsOnly(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        return COMMENT_ONLY_PATTERN.matcher(sql).replaceAll(" ");
    }

    public static String stripLeadingSqlDelimiter(String sql) {
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
    public static String stripLeadingDelimiterDirective(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String trimmed = sql.stripLeading();
        if (trimmed.regionMatches(true, 0, "delimiter", 0, "delimiter".length())) {
            int lineEnd = trimmed.indexOf('\n');
            if (lineEnd < 0) {
                return "";
            }
            return trimmed.substring(lineEnd + 1);
        }
        return sql;
    }

    /**
     * Maps the raw dbtype + sqlmode to a standardised parser dialect key.
     * @deprecated Replaced by {@link SqlParser} resolution via {@code DatabasePlatform.parser()}.
     */
    @Deprecated
    public static String resolveEffectiveDialect(Sql sql) {
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
     * Detects a GBase 8S {@code SET ENVIRONMENT SQLMODE 'xxx'} statement.
     * @deprecated Moved to {@link com.dbboys.dialect.common.InformixFamilySqlParser#detectGbaseSqlmodeChange}.
     */
    @Deprecated
    public static String detectGbaseSqlmodeChange(String addSql) {
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

    // ===== Helper methods used by processSegments (must stay in this class) =====

    private static boolean startsWithIgnoreCase(String text, int start, String value) {
        if (start < 0 || start + value.length() > text.length()) {
            return false;
        }
        return text.regionMatches(true, start, value, 0, value.length());
    }

    private static int skipWhitespace(String sql, int index) {
        int result = index;
        while (result < sql.length() && Character.isWhitespace(sql.charAt(result))) {
            result++;
        }
        return result;
    }

    // ===== SELECT parsing utilities (dialect-agnostic) =====

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

    /**
     * @deprecated Moved to {@link com.dbboys.dialect.common.OracleFamilySqlParser#parsePackageMembers}.
     */
    @Deprecated
    public static List<PackageMember> parsePackageMembers(String packageDdl) {
        return new com.dbboys.dialect.oracle.OracleSqlParser().parsePackageMembers(packageDdl);
    }

    /**
     * @deprecated Moved to {@link com.dbboys.dialect.common.OracleFamilySqlParser#printPackageFunction}.
     */
    @Deprecated
    public static String printPackageFunction(String packagesql, String function) {
        return new com.dbboys.dialect.oracle.OracleSqlParser().printPackageFunction(packagesql, function);
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
