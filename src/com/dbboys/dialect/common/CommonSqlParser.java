package com.dbboys.dialect.common;

import com.dbboys.core.SqlParser;
import com.dbboys.infra.util.SqlParserUtil;
import com.dbboys.model.Sql;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.*;

/**
 * Abstract base for all dialect SQL parsers.
 * Houses the {@link #modifySql} state machine and statement-counting logic
 * that is common across dialects, exposing a small set of hook methods
 * that each dialect or dialect family overrides.
 *
 * <p>Hooks to override in subclasses:</p>
 * <ul>
 *   <li>{@link #isMultiLineSqlStart(String)} — CREATE PROCEDURE/FUNCTION/TRIGGER/PACKAGE/TYPE BODY detection</li>
 *   <li>{@link #isAnonymousBlockStart(String)} — BEGIN/DECLARE/DO $$ detection</li>
 *   <li>{@link #multiLineEndPattern()} — regex alternation for routine / block terminators</li>
 *   <li>{@link #usesPlainBlockEnd(String, String)} — whether the routine uses bare {@code END;} termination</li>
 *   <li>{@link #extractBlockName(String)} — extract routine name from CREATE header for named-END matching</li>
 *   <li>{@link #resolveEffectiveDialect()} — parser dialect key for conditional logic</li>
 *   <li>{@link #onNewStatementStart(Sql, String)} — hook fired at the beginning of each new statement</li>
 * </ul>
 */
public abstract class CommonSqlParser implements SqlParser {

    // ------------------------------------------------------------------
    // Shared pattern texts (same as SqlParserUtil)
    // ------------------------------------------------------------------

    protected static final String STRING_PATTERN_TEXT = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
    protected static final String DOUBLE_STRING_PATTERN_TEXT = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
    protected static final String FANYINHAO_STRING_PATTERN_TEXT = "`[^`]*`" + "|" + "`[\\s\\S]*";
    protected static final String COMMENT_PATTERN_TEXT = "--[^\\n]*" + "|" + "/\\*[\\s\\S]*?\\*/" + "|" + "/\\*[\\s\\S]*" + "|" + "\\{[\\s\\S]*?\\}";
    protected static final String DROP_DATABASE = "(?i)(?:drop\\s+)+database\\s+(\\w+)";
    protected static final String CREATE_DATABASE = "(?i)(?:create\\s+)?database\\s+(?<dbname>(\\w+))";

    protected static final Pattern COMMENT_ONLY_PATTERN = Pattern.compile(COMMENT_PATTERN_TEXT);
    protected static final Pattern STATEMENT_PROTECT_PATTERN = Pattern.compile(
            STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + FANYINHAO_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
    );

    protected static final Pattern BLOCK_DEPTH_TOKEN_PATTERN = Pattern.compile(
            "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                    + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                    + "|(?<BACKTICK>" + FANYINHAO_STRING_PATTERN_TEXT + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                    + "|(?<BEGIN>(?i)\\bbegin\\b(?!\\s*work\\b))"
                    + "|(?<PLAINEND>(?i)\\bend\\s*;)"
                    + "|(?<NAMEDEND>(?i)\\bend\\b\\s+[a-zA-Z_][a-zA-Z0-9_$#\"]*\\s*;)"
    );

    protected static final Pattern PLAIN_BLOCK_END_PATTERN = Pattern.compile(
            "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                    + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                    + "|(?<BACKTICK>" + FANYINHAO_STRING_PATTERN_TEXT + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                    + "|(?<PLAINEND>(?i)\\bend\\s*;)"
    );

    protected static final Pattern NAMED_BLOCK_END_PATTERN = Pattern.compile(
            "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                    + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                    + "|(?<BACKTICK>" + FANYINHAO_STRING_PATTERN_TEXT + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
                    + "|(?<NAMEDEND>(?i)\\bend\\s+(?<ENDNAME>[a-zA-Z_][a-zA-Z0-9_$.]*|\"[^\"]+\")\\s*;)"
    );

    // ------------------------------------------------------------------
    // Abstract hooks — subclasses MUST implement
    // ------------------------------------------------------------------

    /** Detect whether the normalized first line starts a multi-line routine/block. */
    protected abstract boolean isMultiLineSqlStart(String normalizedSql);

    /** Detect whether the normalized first line starts an anonymous block. */
    protected abstract boolean isAnonymousBlockStart(String normalizedSql);

    /**
     * Regex alternation for multi-line terminators (e.g. {@code END procedure;},
     * {@code $$language plpgsql;}, Oracle {@code END;/}, etc.).
     */
    protected abstract String multiLineEndPattern();

    /** Parser dialect key (e.g. {@code "ORACLE"}, {@code "POSTGRESQL"}, {@code "MYSQL"}, {@code "INFORMIX"}). */
    protected abstract String resolveEffectiveDialect();

    // ------------------------------------------------------------------
    // Optional hooks — defaults are reasonable for most dialects
    // ------------------------------------------------------------------

    /**
     * Whether a multi-line routine uses plain {@code END;} termination
     * (no named end label required). Oracle-family overrides this.
     */
    protected boolean usesPlainBlockEnd(String sqlType, String normalizedSql) {
        if ("CALL_BLOCK".equals(sqlType)) {
            return true;
        }
        return false;
    }

    /**
     * Extract the block/routine name from the CREATE header.
     * Used for matching named ENDs like {@code END procname;}.
     */
    protected String extractBlockName(String normalizedSql) {
        return "";
    }

    /**
     * Hook fired at the beginning of each new statement (when sqlstr is empty and
     * we're about to classify). Subclasses can inject sqlmode detection etc.
     */
    protected void onNewStatementStart(Sql sql, String addSql) {
        // no-op by default
    }

    /**
     * Current statement delimiter, set by {@code DELIMITER xx} directive.
     * When set to a non-standard value (e.g. {@code $$}), multi-line end patterns
     * should also match {@code END keyword + delimiter}.
     */
    protected String currentDelimiter = ";";

    /**
     * Detects and strips a leading {@code DELIMITER xx} directive from {@code addSql}.
     * If found, updates {@link #currentDelimiter}.
     * Returns the text with the directive line removed.
     */
    protected String detectDelimiterDirective(String addSql) {
        if (addSql == null || addSql.isEmpty()) return addSql;
        String trimmed = addSql.stripLeading();
        if (!trimmed.regionMatches(true, 0, "delimiter", 0, "delimiter".length())) {
            return addSql;
        }
        int tokenStart = "delimiter".length();
        tokenStart = skipWhitespace(trimmed, tokenStart);
        int tokenEnd = tokenStart;
        while (tokenEnd < trimmed.length() && !Character.isWhitespace(trimmed.charAt(tokenEnd))) {
            tokenEnd++;
        }
        if (tokenEnd > tokenStart) {
            this.currentDelimiter = trimmed.substring(tokenStart, tokenEnd);
        }
        int lineEnd = trimmed.indexOf('\n');
        if (lineEnd < 0) return "";
        return trimmed.substring(lineEnd + 1);
    }

    // remove the delimitResetWouldResetToSemicolon method

    private static boolean delimitResetWouldResetToSemicolon(String text, int idx) {
        String remaining = text.substring(idx).stripLeading();
        return remaining.regionMatches(true, 0, "delimiter ;", 0, "delimiter ;".length())
                || remaining.equalsIgnoreCase("delimiter ;");
    }

    /**
     * Returns the effective multi-line end pattern, incorporating the current
     * delimiter. When a custom delimiter is active, also matches
     * {@code END FUNCTION <delim>} / {@code END PROCEDURE <delim>}.
     */
    protected String effectiveMultiLineEndPattern() {
        String base = multiLineEndPattern();
        if (!";".equals(currentDelimiter)) {
            String delim = Pattern.quote(currentDelimiter);
            base = base + "|" + "(?i)\\bend\\s+(procedure|function)\\s*" + delim;
        }
        return base;
    }

    /**
     * After a statement completes, if a custom delimiter is active, strip any
     * trailing "delimiter ;" line and trailing custom delimiter from the remainder.
     * Returns the cleaned remainder.
     */
    protected String cleanRemainder(String remainder) {
        if (remainder == null || remainder.isEmpty()) return "";
        if (!";".equals(currentDelimiter)) {
            // Find "delimiter ;" line within the remainder
            int delimResetIdx = findDelimiterResetLine(remainder);
            if (delimResetIdx >= 0) {
                this.currentDelimiter = ";";
                String before = remainder.substring(0, delimResetIdx);
                // Also strip any trailing custom delimiter from "before"
                String trimmed = before.stripTrailing();
                if (!trimmed.isEmpty() && trimmed.endsWith(currentDelimiter)) {
                    trimmed = trimmed.substring(0, trimmed.length() - currentDelimiter.length()).stripTrailing();
                }
                // Return empty string if nothing meaningful remains
                return trimmed.isEmpty() ? "" : trimmed;
            }
            // Strip trailing custom delimiter
            String trimmed = remainder.stripTrailing();
            if (trimmed.endsWith(currentDelimiter)) {
                trimmed = trimmed.substring(0, trimmed.length() - currentDelimiter.length()).stripTrailing();
            }
            // If the whole thing is just the delimiter, return empty
            String justDelim = trimmed.strip();
            if (justDelim.equals(currentDelimiter)) {
                return "";
            }
            return trimmed.isEmpty() ? "" : trimmed;
        }
        return remainder;
    }

    /**
     * Finds "delimiter ;" (case-insensitive) anywhere in text.
     */
    private static int findDelimiterResetLine(String text) {
        // Look for "delimiter ;" as a standalone line (only whitespace around it)
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equalsIgnoreCase("delimiter ;")) {
                // Return position at the start of this line
                int pos = 0;
                for (int j = 0; j < i; j++) {
                    pos += lines[j].length() + 1; // +1 for the \n
                }
                return Math.min(pos, text.length());
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Template method: modifySql
    // ------------------------------------------------------------------

    @Override
    public Sql modifySql(Sql sql, String addSql) {
        if (!sql.getSqlRemainder().trim().isEmpty()) {
            addSql = sql.getSqlRemainder() + addSql;
            sql.setSqlRemainder("");
        }
        if (sql.getSqlstr().isEmpty()) {
            sql.setBlockDepth(0);
            sql.setBlockName("");
            sql.setPlainBlockMode(false);
            addSql = SqlParserUtil.stripLeadingSqlDelimiter(addSql);
            addSql = detectDelimiterDirective(addSql);
            addSql = SqlParserUtil.stripLeadingDelimiterDirective(addSql);
            onNewStatementStart(sql, addSql);

            // If addSql is the trailing custom delimiter (e.g. "$$" left from
            // "END FUNCTION$$\ndelimiter ;"), consume it silently.
            // BUT only if cleanRemainder already reset the delimiter to ";".
            // Check: if currentDelimiter is already ";" (was just reset), skip $$.
            // If currentDelimiter is still $$, this means the statement ended with
            // END FUNCTION$$ and the remainder $$ didn't have "delimiter ;" after it.
            boolean isJustDelim = addSql.stripLeading().equals(currentDelimiter)
                    || (!";".equals(currentDelimiter) && addSql.stripLeading().equals(currentDelimiter));
            if (isJustDelim) {
                sql.setSqlType("");
                sql.setSqlStr(addSql);
                sql.setSqlEnd(true);
                return sql;
            }

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
            } else if (!";".equals(currentDelimiter) && checkText.isBlank()) {
                // Only the custom delimiter itself remains (after stripping)
                // This is the trailing "$$" from "END FUNCTION$$\ndelimiter ;"
                // cleanRemainder already handled the "delimiter ;" part
                sql.setSqlType("");
                sql.setSqlStr("");
                sql.setSqlEnd(true);
                return sql;
            } else {
                Matcher matcher;
                boolean isMultiLine = isMultiLineSqlStart(addSql);
                boolean isAnonBlock = isAnonymousBlockStart(addSql);
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
                                    + "|(?<END>" + effectiveMultiLineEndPattern() + ")"
                    );
                    matcher = pattern.matcher(addSql);
                    while (matcher.find()) {
                        if (matcher.group("END") != null) {
                            String rawRemainder = addSql.substring(matcher.end("END"));
                            sql.setSqlRemainder(cleanRemainder(rawRemainder));
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
                            STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                                    + "|" + DROP_DATABASE + "|" + CREATE_DATABASE
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
                                + "|(?<END>" + effectiveMultiLineEndPattern() + ")"
                );
                Matcher matcher = pattern.matcher(addSql);
                while (matcher.find()) {
                    if (matcher.group("END") != null) {
                        String rawRemainder = addSql.substring(matcher.end("END"));
                        sql.setSqlRemainder(cleanRemainder(rawRemainder));
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

    // ------------------------------------------------------------------
    // hasMoreStatements / hasMoreThanOneStatement
    // ------------------------------------------------------------------

    @Override
    public boolean hasMoreStatements(String remainderSql) {
        if (remainderSql == null || remainderSql.isBlank()) {
            return false;
        }
        // Quick check: is the raw remainder just a custom delimiter?
        if (!";".equals(currentDelimiter)) {
            String raw = remainderSql.strip();
            if (raw.equals(currentDelimiter)) {
                return false;
            }
        }
        String cleaned = SqlParserUtil.stripLeadingSqlDelimiter(remainderSql);
        if (cleaned.isBlank()) {
            return false;
        }
        if (!";".equals(currentDelimiter)) {
            String trimmed = cleaned.strip();
            if (trimmed.equals(currentDelimiter)) {
                return false;
            }
        }
        String stripped = STATEMENT_PROTECT_PATTERN.matcher(cleaned).replaceAll("").trim();
        if (stripped.isEmpty() || stripped.matches("[/\\s]+")) {
            return false;
        }

        boolean result = false;
        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                        + "|(?<END>" + effectiveMultiLineEndPattern() + ")"
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
            if (!containBegin && SqlParserUtil.isExecutableStatement(cleaned)) {
                result = true;
            }
        }

        return result;
    }

    @Override
    public boolean hasMoreThanOneStatement(String remainderSql) {
        if (remainderSql == null || remainderSql.isBlank()) return false;
        if (!";".equals(currentDelimiter) && remainderSql.stripLeading().equals(currentDelimiter)) return false;
        boolean result = false;
        Pattern pattern = Pattern.compile(
                STRING_PATTERN_TEXT + "|" + DOUBLE_STRING_PATTERN_TEXT + "|" + COMMENT_PATTERN_TEXT
                        + "|(?<END>" + effectiveMultiLineEndPattern() + ")"
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

    // ------------------------------------------------------------------
    // countExecutableStatements / isSingleStatement
    // ------------------------------------------------------------------

    @Override
    public int countExecutableStatements(String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            return 0;
        }
        return countExecutableStatements(sqlText, Integer.MAX_VALUE);
    }

    private int countExecutableStatements(String sqlText, int stopAfterCount) {
        Sql[] currentSql = {new Sql()};
        int[] statementCount = {0};
        boolean[] stoppedEarly = {false};

        SqlParserUtil.processSegmentsPublic(sqlText, segment -> {
            checkCountInterrupted();
            String sqlChunk = segment.getText();
            boolean sqlContainsCommit;
            do {
                checkCountInterrupted();
                currentSql[0] = modifySql(currentSql[0], sqlChunk);
                if (currentSql[0].getSqlEnd() && SqlParserUtil.isExecutableStatement(currentSql[0].getSqlstr())) {
                    statementCount[0]++;
                    if (statementCount[0] >= stopAfterCount) {
                        stoppedEarly[0] = true;
                        return false;
                    }
                    // Use cleanRemainder to strip delimiter cruft & reset delimiter
                    String rawRem = currentSql[0].getSqlRemainder();
                    if (!rawRem.isBlank()) {
                        currentSql[0].setSqlRemainder(cleanRemainder(rawRem));
                    }
                    resetSqlStatementState(currentSql[0]);
                }
                sqlChunk = "";
                sqlContainsCommit = hasMoreStatements(currentSql[0].getSqlRemainder());
            } while (sqlContainsCommit);
            return true;
        });

        checkCountInterrupted();
        if (!stoppedEarly[0] && SqlParserUtil.isExecutableStatement(currentSql[0].getSqlstr())) {
            statementCount[0]++;
        }
        return statementCount[0];
    }

    @Override
    public boolean isSingleStatement(String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            return true;
        }
        return countExecutableStatements(sqlText, 2) <= 1;
    }

    // ------------------------------------------------------------------
    // Protected helpers
    // ------------------------------------------------------------------

    protected void configureBlockState(Sql sql, String addSql) {
        sql.setBlockName(extractBlockName(addSql));
        sql.setPlainBlockMode(usesPlainBlockEnd(sql.getSqlType(), addSql));
    }

    protected void updateBlockDepth(Sql sql, String addSql) {
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

    protected boolean containsPlainBlockEnd(String addSql) {
        Matcher matcher = PLAIN_BLOCK_END_PATTERN.matcher(addSql);
        while (matcher.find()) {
            if (matcher.group("PLAINEND") != null) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsNamedBlockEnd(String addSql, String blockName) {
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

    protected int getRoutineStatementEndIndex(Matcher matcher) {
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

    // ------------------------------------------------------------------
    // Static utility helpers
    // ------------------------------------------------------------------

    protected static String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String normalized = identifier.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    protected static String stripCommentsOnly(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        return COMMENT_ONLY_PATTERN.matcher(sql).replaceAll(" ");
    }

    protected static String stripProtectedContent(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        return STATEMENT_PROTECT_PATTERN.matcher(sql).replaceAll("");
    }

    protected static int skipWhitespace(String sql, int index) {
        int result = index;
        while (result < sql.length() && Character.isWhitespace(sql.charAt(result))) {
            result++;
        }
        return result;
    }

    protected static boolean startsWithIgnoreCase(String text, int start, String value) {
        if (start < 0 || start + value.length() > text.length()) {
            return false;
        }
        return text.regionMatches(true, start, value, 0, value.length());
    }

    protected static int skipQualifiedName(String sql, int index) {
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

    protected static String extractLastIdentifier(String identifierChain) {
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

    static int skipIgnorableSql(int index, String sql) {
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

    static boolean shouldKeepRoutineTerminatorWithPreviousSegment(String sql, int nextIndex) {
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
            return true;
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

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

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
}
