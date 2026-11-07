package com.dbboys.dialect.sqlserver;

import com.dbboys.dialect.common.CommonSqlParser;
import com.dbboys.model.Sql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL Server (T-SQL) parser. Handles {@code GO} batch separators and
 * CREATE PROC/FUNCTION/TRIGGER bodies terminated by a bare {@code END}.
 */
public final class SqlserverSqlParser extends CommonSqlParser {

    private static final Pattern GO_LINE_PATTERN = Pattern.compile("(?im)^[ \\t]*go([ \\t]+\\d+)?[ \\t]*$");

    @Override
    protected String resolveEffectiveDialect() {
        return "SQLSERVER";
    }

    @Override
    protected String multiLineEndPattern() {
        // T-SQL routines end with a bare END (optionally followed by ';').
        // Anchored to end of input; depth tracking handles nested blocks.
        return "(?i)\\bend\\s*;?\\s*$";
    }

    @Override
    protected boolean usesPlainBlockEnd(String sqlType, String normalizedSql) {
        return true;
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+alter\\s+)?(?<TYPE>proc|procedure|function|trigger)\\b"
        ).matcher(normalized);
        if (!matcher.find()) {
            return false;
        }
        return Pattern.compile("(?i)\\bbegin\\b").matcher(normalized).find();
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        String normalized = stripCommentsOnly(sql);
        if (normalized.isBlank()) {
            return false;
        }
        return Pattern.compile("(?i)^\\s*\\bbegin\\b(?!\\s*(;|tran|transaction|work))")
                .matcher(normalized).find();
    }

    /**
     * Splits chunks at {@code GO} batch separator lines (outside strings and
     * comments). The text before GO completes the current statement; the rest
     * becomes the remainder for the next statement.
     */
    @Override
    public Sql modifySql(Sql sql, String addSql) {
        // Pull in any pending remainder first so GO lines inside it are seen
        // (super.modifySql would only prepend it after this override ran).
        String effective = addSql == null ? "" : addSql;
        String pending = sql.getSqlRemainder();
        if (pending != null && !pending.trim().isEmpty()) {
            effective = pending + effective;
            sql.setSqlRemainder("");
        }
        int[] goLine = findGoLine(effective);
        if (goLine == null) {
            return super.modifySql(sql, effective);
        }
        String head = effective.substring(0, goLine[0]);
        String tail = effective.substring(goLine[1]);
        if (sql.getSqlstr().isEmpty() && head.isBlank()) {
            // Leading GO line — just skip it.
            return super.modifySql(sql, tail);
        }
        Sql result = super.modifySql(sql, head);
        if (!result.getSqlEnd()) {
            // GO terminates the batch even when the statement has no explicit
            // terminator (e.g. a routine body ending with a bare END).
            result.setSqlEnd(true);
        }
        String remainder = result.getSqlRemainder();
        result.setSqlRemainder(remainder == null || remainder.isBlank() ? tail : tail + remainder);
        return result;
    }

    /**
     * GO batch separators leave remainders that start a brand-new statement
     * (unlike Oracle package bodies, where a remainder beginning with
     * FUNCTION/PROCEDURE is still part of the same package). Count any
     * executable remainder as more statements.
     */
    @Override
    public boolean hasMoreStatements(String remainderSql) {
        if (remainderSql == null || remainderSql.isBlank()) {
            return false;
        }
        String cleaned = com.dbboys.infra.util.SqlParserUtil.stripLeadingSqlDelimiter(remainderSql);
        if (cleaned.isBlank()) {
            return false;
        }
        String stripped = STATEMENT_PROTECT_PATTERN.matcher(cleaned).replaceAll("").trim();
        if (stripped.isEmpty() || stripped.matches("[/\\s]+")) {
            return false;
        }
        return com.dbboys.infra.util.SqlParserUtil.isExecutableStatement(cleaned);
    }

    /** Returns [start, endExclusive] of the first GO line outside strings/comments, or null. */
    private static int[] findGoLine(String sql) {
        if (sql == null || sql.isEmpty()) {
            return null;
        }
        String masked = maskProtectedContent(sql);
        Matcher matcher = GO_LINE_PATTERN.matcher(masked);
        if (!matcher.find()) {
            return null;
        }
        int end = matcher.end();
        if (end < sql.length() && sql.charAt(end) == '\n') {
            end++;
        }
        return new int[]{matcher.start(), end};
    }

    /** Replaces string/comment spans with spaces (preserving newlines and length). */
    private static String maskProtectedContent(String sql) {
        Matcher matcher = STATEMENT_PROTECT_PATTERN.matcher(sql);
        StringBuilder masked = new StringBuilder(sql);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                char c = masked.charAt(i);
                if (c != '\n' && c != '\r') {
                    masked.setCharAt(i, ' ');
                }
            }
        }
        return masked.toString();
    }
}
