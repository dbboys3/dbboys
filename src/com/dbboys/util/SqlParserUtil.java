package com.dbboys.util;

import com.dbboys.vo.Sql;

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
    private static final String NO_NAME_BLOCK = "(?i)^\\s*\\b(begin)(?!\\s*(;|work))|(?i)^\\s*\\b(DECLARE)(?!\\s*;)";
    private static final String MULTI_LINE_END =
            "(?i)\\bend\\s+(procedure|function)\\s*;?" + "|" +
            "(?i)\\bend\\s*;\\s*/" + "|" +
            "(?i)\\bend\\b\\s+([a-zA-Z_][a-zA-Z0-9_$.]*)?\\s*/" + "|" +
            "(?m)^\\s*/\\s*$";
    private static final String DROP_DATABASE = "(?i)(?:drop\\s+)+database\\s+(\\w+)";
    private static final String CREATE_DATABASE = "(?i)(?:create\\s+)?database\\s+(?<dbname>(\\w+))";
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
            "(?is)^\\s*create\\s+(or\\s+replace\\s+)?(?<TYPE>function|procedure)\\b"
    );
    private static final Pattern PACKAGE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(or\\s+replace\\s+)?package(\\s+body)?\\b"
    );
    private static final Pattern TRIGGER_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(or\\s+replace\\s+)?trigger\\b"
    );
    private static final Pattern BLOCK_NAME_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(or\\s+replace\\s+)?(?<TYPE>package(\\s+body)?|procedure|function|trigger)\\b"
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
    private static final Pattern FORMAT_PROTECT_PATTERN = Pattern.compile(
            "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                    + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                    + "|(?<BACKTICK>" + FANYINHAO_STRING_PATTERN_TEXT + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
    );
    private static final Pattern FORMAT_TOKEN_SPLIT_PATTERN = Pattern.compile("(?=\\()|(?<=\\))");
    private static final Pattern FORMAT_COMPACT_SPACES_PATTERN = Pattern.compile("[ \\t]+");
    private static final Pattern FORMAT_OPERATOR_SPACING_PATTERN = Pattern.compile("\\s*([=<>+*/-])\\s*");
    private static final Pattern FORMAT_OPEN_PAREN_SPACING_PATTERN = Pattern.compile("(\\()\\s+");
    private static final Pattern FORMAT_CLOSE_PAREN_SPACING_PATTERN = Pattern.compile("\\s+(\\))");
    private static final Pattern FORMAT_DOUBLE_OPERATOR_PATTERN = Pattern.compile("([=<>+*/-]) ([=<>+*/-])");
    private static final Pattern FORMAT_COMMA_SPACING_PATTERN = Pattern.compile("\\s*,\\s*");
    private static final Pattern FORMAT_COMMENT_PLACEHOLDER_PATTERN = Pattern.compile("(?i)(__PLACEHOLDER_COMMENT_[0-9]+__)\\n?\\s*");
    private static final Pattern FORMAT_CLAUSE_BREAK_PATTERN = Pattern.compile(
            "(?i)\\b(FROM|WHERE|GROUP BY|HAVING|ORDER BY|UNION(?:\\s+ALL)?|(?:(?:LEFT|RIGHT|INNER|FULL|CROSS|NATURAL)(?:\\s+OUTER)?\\s+)?JOIN)\\b"
    );
    private static final Pattern FORMAT_JOIN_ON_BREAK_PATTERN = Pattern.compile(
            "(?i)(\\b(?:(?:LEFT|RIGHT|INNER|FULL|CROSS|NATURAL)(?:\\s+OUTER)?\\s+)?JOIN\\b[^\\n]*?)\\s+\\b(ON)\\b"
    );
    private static final Pattern FORMAT_OVER_CLAUSE_BREAK_PATTERN = Pattern.compile("(?i)\\b(PARTITION BY|ORDER BY)\\b");
    private static final Pattern FORMAT_CONDITION_BREAK_PATTERN = Pattern.compile("(?i)\\b(AND|OR)\\b");
    private static final Pattern FORMAT_AS_SELECT_PATTERN = Pattern.compile("(?i)(as\\s+)(select\\s+)");
    private static final Pattern FORMAT_UNION_SELECT_PATTERN = Pattern.compile("(?i)(\\s*)(union\\s+\\w*\\s*)(select\\s+)");
    private static final Pattern FORMAT_FUNCTION_PAREN_PATTERN = Pattern.compile(
            "(?i)\\b(?!KEY\\b|CHECK\\b|VALUES\\b|UNIQUE\\b|OVER\\b|IN\\b|EXISTS\\b|USING\\b)(\\w+)\\h+\\("
    );
    private static final Pattern FORMAT_CLOSE_PAREN_SELECT_PATTERN = Pattern.compile("(?i)(\\s*)\\)\\s*(\\bselect\\b\\s+)");
    private static final Pattern FORMAT_TRAILING_SPACE_PATTERN = Pattern.compile("[ \\t]+(?=\\n)");
    private static final Pattern FORMAT_MULTI_BLANK_LINE_PATTERN = Pattern.compile("\\n\\s*\\n");
    private static final List<String> FORMATTABLE_PREFIXES = List.of(
            "select ",
            "with ",
            "create table ",
            "create view ",
            "create index ",
            "create sequence ",
            "create trigger ",
            "alter table ",
            "alter fragment ",
            "rename ",
            "comment on ",
            "delete from ",
            "drop ",
            "insert ",
            "update ",
            "delete ",
            "truncate "
    );
    private static final String FORMAT_INDENT = "  ";

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
        return null;
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
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inBrackets = false;

        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';
            buffer.append(current);

            if (!inSingleQuote && !inDoubleQuote && !inBlockComment && !inBrackets) {
                if (!inLineComment && current == '-' && next == '-') {
                    inLineComment = true;
                    buffer.append(next);
                    i++;
                    continue;
                } else if (inLineComment && current == '\n') {
                    inLineComment = false;
                }
            }

            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBrackets) {
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

            if (!inDoubleQuote && !inLineComment && !inBlockComment && !inBrackets && current == '\'') {
                inSingleQuote = !inSingleQuote;
            }

            if (!inSingleQuote && !inLineComment && !inBlockComment && !inBrackets && current == '\"') {
                inDoubleQuote = !inDoubleQuote;
            }

            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment) {
                if (current == '{') {
                    inBrackets = true;
                } else if (current == '}') {
                    inBrackets = false;
                }
            }

            if (i == sql.length() - 1
                    || (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment && !inBrackets && current == ';'
                    && !shouldKeepRoutineTerminatorWithPreviousSegment(sql, i + 1))) {
                if (!handler.handle(new Segment(buffer.toString(), i))) {
                    return false;
                }
                buffer.setLength(0);
            }
        }
        return true;
    }

    private static boolean shouldKeepRoutineTerminatorWithPreviousSegment(String sql, int nextIndex) {
        int index = skipIgnorableSql(nextIndex, sql);
        if (index >= sql.length() || !startsWithIgnoreCase(sql, index, "end")) {
            return false;
        }
        index = skipWhitespace(sql, index + 3);
        return startsWithIgnoreCase(sql, index, "procedure")
                || startsWithIgnoreCase(sql, index, "function");
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
            if (addSql.isBlank()) {
                sql.setSqlEnd(false);
                return sql;
            }
        }
        sql.setSqlEnd(false);

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
                if (isMultiLineSqlStart(addSql)) {
                    sql.setSqlType("MULTI_LINE_SQL");
                    configureBlockState(sql, addSql);
                } else if (isAnonymousBlockStart(addSql)) {
                    sql.setSqlType("CALL_BLOCK");
                    configureBlockState(sql, addSql);
                }

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

                if (!sql.getSqlEnd() && sql.getPlainBlockMode()) {
                    updateBlockDepth(sql, addSql);
                    if (sql.getBlockDepth() <= 0 && containsPlainBlockEnd(addSql)) {
                        sql.setSqlEnd(true);
                        sql.setBlockDepth(0);
                    }
                }
                if (!sql.getSqlEnd() && containsNamedBlockEnd(addSql, sql.getBlockName())) {
                    sql.setSqlEnd(true);
                    sql.setBlockDepth(0);
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
                if (!sql.getSqlEnd() && containsNamedBlockEnd(addSql, sql.getBlockName())) {
                    sql.setSqlEnd(true);
                    sql.setBlockDepth(0);
                }
                sql.setSqlStr(sql.getSqlstr() + addSql);
            }
        }

        return sql;
    }

    public static boolean sqlContrainCommit(String remainderSql) {
        boolean result = false;
        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                        + "|(?<END>" + MULTI_LINE_END + ")"
        );
        Matcher matcher = pattern.matcher(remainderSql);
        while (matcher.find()) {
            if (matcher.group("END") != null) {
                result = true;
            }
            break;
        }

        if (!result) {
            boolean containBegin = isMultiLineSqlStart(remainderSql) || isAnonymousBlockStart(remainderSql);
            if (!containBegin && isExecutableStatement(remainderSql)) {
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

    private static void updateBlockDepth(Sql sql, String addSql) {
        int blockDepth = sql.getBlockDepth();
        Matcher matcher = BLOCK_DEPTH_TOKEN_PATTERN.matcher(addSql);
        while (matcher.find()) {
            if (matcher.group("BEGIN") != null) {
                blockDepth++;
            } else if (matcher.group("PLAINEND") != null && blockDepth > 0) {
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
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }
        if (TRIGGER_DECLARATION_PATTERN.matcher(normalized).find()) {
            return true;
        }
        if (PACKAGE_DECLARATION_PATTERN.matcher(normalized).find()) {
            String lowerNormalized = normalized.toLowerCase(Locale.ROOT);
            // Same-line AS/IS: "... PACKAGE name AS ..." / "... name IS ..."
            if (lowerNormalized.contains(" as") || lowerNormalized.contains(" is")) {
                return true;
            }
            // Oracle style: AS/IS alone on the next line (no leading space before keyword on that line)
            if (Pattern.compile("(?im)^\\s*(as|is)\\b").matcher(normalized).find()) {
                return true;
            }
            return false;
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
        return NO_NAME_BLOCK_PATTERN.matcher(stripCommentsOnly(sql)).find();
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
        return normalized.startsWith("create procedure ")
                || normalized.startsWith("create function ")
                || normalized.startsWith("create trigger ")
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
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).trim().equals("*")) {
                result.remove(i);
                result.addAll(i, cols);
            }
        }
        return result;
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

    public static String formatSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql.trim();
        }

        Map<String, String> placeholders = new LinkedHashMap<>();
        String protectedSql = protectFormatSql(sql, placeholders);
        List<String> statements = splitStatementsForFormat(protectedSql);

        StringBuilder result = new StringBuilder(protectedSql.length() + 32);
        for (String statement : statements) {
            if (statement == null || statement.trim().isEmpty()) {
                continue;
            }
            String formattedStatement = formatSingleStatement(statement);
            if (formattedStatement.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(formattedStatement);
        }

        if (result.length() == 0) {
            result.append(protectedSql.trim());
        }

        String formattedSql = result.toString().replace(";", ";\n");
        formattedSql = FORMAT_MULTI_BLANK_LINE_PATTERN.matcher(formattedSql).replaceAll("\n").trim();
        return restoreProtectedSql(formattedSql, placeholders);
    }

    private static String protectFormatSql(String sql, Map<String, String> placeholders) {
        Matcher matcher = FORMAT_PROTECT_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            boolean isComment = matcher.group("COMMENT") != null;
            String placeholderPrefix = isComment ? "__PLACEHOLDER_COMMENT_" : "__PLACEHOLDER_";
            String placeholder = placeholderPrefix + placeholders.size() + "__";
            placeholders.put(placeholder, matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static List<String> splitStatementsForFormat(String sql) {
        List<String> statements = new ArrayList<>();
        Sql currentSql = new Sql();
        for (Segment segment : split(sql)) {
            String remainingChunk = segment.getText();
            while (remainingChunk != null && !remainingChunk.isBlank()) {
                currentSql = modifySql(currentSql, remainingChunk);
                if (!currentSql.getSqlEnd()) {
                    break;
                }
                if (isExecutableStatement(currentSql.getSqlstr())) {
                    statements.add(currentSql.getSqlstr());
                }
                remainingChunk = currentSql.getSqlRemainder();
                currentSql = new Sql();
            }
        }
        if (isExecutableStatement(currentSql.getSqlstr())) {
            statements.add(currentSql.getSqlstr());
        }
        return statements;
    }

    private static String formatSingleStatement(String sql) {
        String normalizedSql = normalizeFormatStatement(sql);
        if (!shouldFormatStatement(normalizedSql)) {
            return sql.trim();
        }

        String rootParenthesisStatement = formatRootParenthesisStatement(normalizedSql);
        if (rootParenthesisStatement != null) {
            return finalizeFormattedStatement(rootParenthesisStatement);
        }

        Deque<Integer> indentStack = new ArrayDeque<>();
        int currentIndent = 0;
        StringBuilder appendSql = new StringBuilder(normalizedSql.length() + 32);
        String previousToken = "";

        for (String token : FORMAT_TOKEN_SPLIT_PATTERN.split(normalizedSql)) {
            if (token.isEmpty()) {
                continue;
            }
            String rawToken = token;
            if (shouldFormatOverClause(token, previousToken)) {
                token = formatOverClause(token, currentIndent);
            } else if (shouldPreserveInlineConstraintList(token, previousToken)) {
                token = formatInlineConstraintList(token);
            } else if (token.matches("(?i)^\\(\\s*select[\\s\\S]+") || (token.contains("(") && !token.contains(")"))) {
                currentIndent++;
                indentStack.push(currentIndent);
                int innerIndent = currentIndent + 1;
                token = formatCommentPlaceholders(token, currentIndent);
                token = "\n" + FORMAT_INDENT.repeat(currentIndent) + "(\n"
                        + FORMAT_INDENT.repeat(innerIndent) + token.substring(1).trim();
                token = applyClauseBreaks(token, innerIndent, false);
                token = token.replace(";", ";\n");
                if (token.contains(")")) {
                    indentStack.pop();
                    currentIndent--;
                    token = token.replace(")", "\n" + FORMAT_INDENT.repeat(currentIndent + 1) + ")");
                    token = token.replace(";", ";\n");
                }
            } else if (token.contains(")") && !token.contains("(")) {
                token = formatCommentPlaceholders(token, currentIndent);
                token = token.replace(")", "\n" + FORMAT_INDENT.repeat(currentIndent) + ")");
                token = applyClauseBreaks(token, currentIndent, false);
                token = token.replace(";", ";\n");
                if (!indentStack.isEmpty()) {
                    currentIndent = indentStack.pop() - 1;
                }
            } else {
                token = formatCommentPlaceholders(token, currentIndent);
                token = applyClauseBreaks(token, currentIndent, token.contains("(") && token.contains(")"));
                token = token.replace(";", ";\n");
            }
            appendSql.append(token);
            previousToken = rawToken;
        }

        String formattedSql = appendSql.toString();
        return finalizeFormattedStatement(formattedSql);
    }

    private static String finalizeFormattedStatement(String formattedSql) {
        formattedSql = FORMAT_AS_SELECT_PATTERN.matcher(formattedSql).replaceAll("$1\n$2");
        formattedSql = FORMAT_UNION_SELECT_PATTERN.matcher(formattedSql).replaceAll("$1$2\n$1$3");
        formattedSql = FORMAT_FUNCTION_PAREN_PATTERN.matcher(formattedSql).replaceAll("$1(");
        formattedSql = FORMAT_CLOSE_PAREN_SELECT_PATTERN.matcher(formattedSql).replaceAll("$1)\n$2");
        formattedSql = FORMAT_TRAILING_SPACE_PATTERN.matcher(formattedSql).replaceAll("");
        return formattedSql;
    }

    private static String normalizeFormatStatement(String sql) {
        String normalizedSql = FORMAT_COMPACT_SPACES_PATTERN.matcher(sql.trim()).replaceAll(" ");
        normalizedSql = FORMAT_OPERATOR_SPACING_PATTERN.matcher(normalizedSql).replaceAll(" $1 ");
        normalizedSql = normalizedSql.replaceAll("\\s+", " ").trim();
        normalizedSql = FORMAT_OPEN_PAREN_SPACING_PATTERN.matcher(normalizedSql).replaceAll("$1");
        normalizedSql = FORMAT_CLOSE_PAREN_SPACING_PATTERN.matcher(normalizedSql).replaceAll("$1");
        return FORMAT_DOUBLE_OPERATOR_PATTERN.matcher(normalizedSql).replaceAll("$1$2");
    }

    private static boolean shouldFormatStatement(String sql) {
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        for (String prefix : FORMATTABLE_PREFIXES) {
            if (lowerSql.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String formatCommentPlaceholders(String token, int currentIndent) {
        String indent = "\n" + FORMAT_INDENT.repeat(currentIndent + 1);
        return FORMAT_COMMENT_PLACEHOLDER_PATTERN.matcher(token).replaceAll(indent + "$1" + indent);
    }

    private static String formatRootParenthesisStatement(String normalizedSql) {
        int openParenIndex = findRootParenthesisBlockStart(normalizedSql);
        if (openParenIndex < 0) {
            return null;
        }

        int closeParenIndex = findMatchingParenthesis(normalizedSql, openParenIndex);
        if (closeParenIndex < 0) {
            return null;
        }

        String inner = normalizedSql.substring(openParenIndex + 1, closeParenIndex).trim();
        if (inner.isEmpty()) {
            return null;
        }

        List<String> items = splitTopLevelCommaSegments(inner);
        if (items.size() < 2) {
            return null;
        }

        String prefix = normalizedSql.substring(0, openParenIndex).trim();
        String suffix = normalizedSql.substring(closeParenIndex + 1).trim();
        StringBuilder formatted = new StringBuilder(normalizedSql.length() + 32);
        formatted.append(prefix)
                .append('\n')
                .append(FORMAT_INDENT)
                .append("(\n");

        for (int i = 0; i < items.size(); i++) {
            String item = formatCommentPlaceholders(items.get(i).trim(), 1);
            formatted.append(FORMAT_INDENT.repeat(2))
                    .append(item.trim());
            if (i < items.size() - 1) {
                formatted.append(',');
            }
            formatted.append('\n');
        }

        formatted.append(FORMAT_INDENT).append(')');
        if (!suffix.isEmpty()) {
            if (suffix.startsWith(";")) {
                formatted.append(suffix);
            } else {
                formatted.append(' ').append(suffix);
            }
        }
        return formatted.toString();
    }

    private static int findRootParenthesisBlockStart(String sql) {
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        if (lowerSql.startsWith("create table ")) {
            return sql.indexOf('(');
        }

        if (!lowerSql.startsWith("alter table ")) {
            return -1;
        }

        int openParenIndex = sql.indexOf('(');
        if (openParenIndex < 0) {
            return -1;
        }

        String beforeParen = lowerSql.substring(0, openParenIndex);
        int addIndex = beforeParen.lastIndexOf(" add");
        if (addIndex < 0) {
            return -1;
        }

        String betweenAddAndParen = beforeParen.substring(addIndex + 4).trim();
        return betweenAddAndParen.isEmpty() ? openParenIndex : -1;
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

    private static List<String> splitTopLevelCommaSegments(String sql) {
        List<String> segments = new ArrayList<>();
        int depth = 0;
        int segmentStart = 0;
        for (int i = 0; i < sql.length(); i++) {
            char currentChar = sql.charAt(i);
            if (currentChar == '(') {
                depth++;
            } else if (currentChar == ')') {
                depth = Math.max(0, depth - 1);
            } else if (currentChar == ',' && depth == 0) {
                segments.add(sql.substring(segmentStart, i).trim());
                segmentStart = i + 1;
            }
        }

        String tail = sql.substring(segmentStart).trim();
        if (!tail.isEmpty()) {
            segments.add(tail);
        }
        return segments;
    }

    private static boolean shouldFormatOverClause(String token, String previousToken) {
        if (token == null || previousToken == null || !token.startsWith("(") || !token.contains(")")) {
            return false;
        }
        String previousLower = previousToken.trim().toLowerCase(Locale.ROOT);
        return previousLower.equals("over") || previousLower.endsWith(" over");
    }

    private static boolean shouldPreserveInlineConstraintList(String token, String previousToken) {
        if (token == null || previousToken == null || !token.startsWith("(") || !token.contains(")")) {
            return false;
        }
        String previousLower = previousToken.trim().toLowerCase(Locale.ROOT);
        return previousLower.endsWith("primary key")
                || previousLower.endsWith("foreign key")
                || previousLower.endsWith("unique");
    }

    private static String formatInlineConstraintList(String token) {
        int closeParenIndex = token.lastIndexOf(')');
        String inner = token.substring(1, closeParenIndex).trim();
        inner = FORMAT_COMMA_SPACING_PATTERN.matcher(inner).replaceAll(", ");
        return "(" + inner + ")" + token.substring(closeParenIndex + 1);
    }

    private static String formatOverClause(String token, int currentIndent) {
        String inner = token.substring(1, token.lastIndexOf(')')).trim();
        int blockIndent = currentIndent + 1;
        int innerIndent = currentIndent + 2;
        inner = FORMAT_OVER_CLAUSE_BREAK_PATTERN.matcher(inner)
                .replaceAll("\n" + FORMAT_INDENT.repeat(innerIndent) + "$1")
                .trim();
        if (inner.startsWith("\n")) {
            inner = inner.substring(1);
        }
        return "\n" + FORMAT_INDENT.repeat(blockIndent) + "(\n"
                + FORMAT_INDENT.repeat(innerIndent) + inner
                + "\n" + FORMAT_INDENT.repeat(blockIndent) + ")"
                + token.substring(token.lastIndexOf(')') + 1);
    }

    private static String applyClauseBreaks(String token, int currentIndent, boolean compactComma) {
        String commaReplacement = compactComma
                ? ","
                : ",\n" + FORMAT_INDENT.repeat(currentIndent + 1);
        String formattedToken = FORMAT_COMMA_SPACING_PATTERN.matcher(token).replaceAll(commaReplacement);
        formattedToken = FORMAT_CLAUSE_BREAK_PATTERN.matcher(formattedToken)
                .replaceAll("\n" + FORMAT_INDENT.repeat(currentIndent) + "$1");
        formattedToken = FORMAT_JOIN_ON_BREAK_PATTERN.matcher(formattedToken)
                .replaceAll("$1\n" + FORMAT_INDENT.repeat(currentIndent + 1) + "$2");
        return FORMAT_CONDITION_BREAK_PATTERN.matcher(formattedToken)
                .replaceAll("\n" + FORMAT_INDENT.repeat(currentIndent + 1) + "$1");
    }

    private static String restoreProtectedSql(String sql, Map<String, String> placeholders) {
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace(entry.getKey(), entry.getValue());
        }
        return sql;
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
