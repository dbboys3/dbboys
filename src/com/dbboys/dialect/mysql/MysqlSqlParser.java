package com.dbboys.dialect.mysql;

import com.dbboys.dialect.common.CommonSqlParser;

import java.util.regex.Pattern;

/**
 * MySQL SQL parser.
 * Single-statement for DDL/DML; multi-line for CREATE PROCEDURE/FUNCTION/TRIGGER
 * with MySQL-specific DELIMITER handling handled by the common segmenter.
 */
public class MysqlSqlParser extends CommonSqlParser {

    @Override
    protected String resolveEffectiveDialect() {
        return "MYSQL";
    }

    @Override
    protected String multiLineEndPattern() {
        return "(?i)\\bend\\s+(procedure|function)\\s*;?"
                + "|" + "(?i)\\bend\\s*;\\s*/";
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }

        // MySQL triggers are multi-line (CREATE TRIGGER ... BEGIN ... END)
        if (Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?trigger\\b"
        ).matcher(normalized).find()) {
            return true;
        }

        java.util.regex.Matcher matcher = Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?(?<TYPE>function|procedure)\\b"
        ).matcher(normalized);
        if (!matcher.find()) {
            return false;
        }

        String routineType = matcher.group("TYPE").toLowerCase(java.util.Locale.ROOT);
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

        String remainder = normalized.substring(remainderStart).trim().toLowerCase(java.util.Locale.ROOT);
        if (remainder.isEmpty()) {
            return false;
        }

        if ("function".equals(routineType)) {
            return remainder.startsWith("return")
                    || remainder.startsWith("returns");
        }

        return remainder.startsWith("begin");
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        String normalized = stripCommentsOnly(sql);
        if (normalized.isBlank()) {
            return false;
        }
        return Pattern.compile(
                "(?i)^\\s*\\b(begin)(?!\\s*(;|work))"
        ).matcher(normalized).find();
    }

    @Override
    protected String extractBlockName(String normalizedSql) {
        String normalized = stripCommentsOnly(normalizedSql).trim();
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?"
                        + "(?<TYPE>procedure|function|trigger)\\b"
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
