package com.dbboys.dialect.common;

import com.dbboys.model.Sql;

import java.util.Locale;

/**
 * Shared SQL parser for the Informix family (Informix, GBase 8S).
 * Encapsulates GBase 8S SQLMODE detection and switching logic.
 */
public abstract class InformixFamilySqlParser extends CommonSqlParser {

    @Override
    protected String resolveEffectiveDialect() {
        return "INFORMIX";
    }

    @Override
    protected String multiLineEndPattern() {
        return "(?i)\\bend\\s+(procedure|function)\\s*;?"
                + "|" + "(?i)\\bend\\s*;\\s*/"
                + "|" + "(?i)\\bend\\b\\s+([a-zA-Z_][a-zA-Z0-9_$.]*)\\s*/"
                + "|" + "(?m)^\\s*/\\s*$";
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }

        // Check CREATE [OR REPLACE] [FUNCTION|PROCEDURE]
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?(?<TYPE>function|procedure)\\b"
        ).matcher(normalized);
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
                    || remainder.startsWith("returning");
        }

        return remainder.startsWith("as")
                || remainder.startsWith("begin")
                || remainder.startsWith("define");
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        String normalized = stripCommentsOnly(sql);
        if (normalized.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile(
                "(?i)^\\s*\\b(begin)(?!\\s*(;|work))|(?i)^\\s*\\b(DECLARE)(?!\\s*;)"
        ).matcher(normalized).find();
    }

    @Override
    protected String extractBlockName(String normalizedSql) {
        String normalized = stripCommentsOnly(normalizedSql).trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?"
                        + "(?<TYPE>procedure|function)\\b"
        ).matcher(normalized);
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

    // ------------------------------------------------------------------
    // GBase 8S SQLMODE detection
    // ------------------------------------------------------------------

    /**
     * Detects a GBase 8S {@code SET ENVIRONMENT SQLMODE 'xxx'} statement
     * and returns the sqlmode value (gbase / oracle / mysql), or null.
     */
    protected static String detectGbaseSqlmodeChange(String addSql) {
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

    @Override
    protected void onNewStatementStart(Sql sql, String addSql) {
        String detectedMode = detectGbaseSqlmodeChange(addSql);
        if (detectedMode != null) {
            sql.setSqlmode(detectedMode);
        }
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
}
