package com.dbboys.dialect.genericjdbc;

import com.dbboys.dialect.common.CommonSqlParser;

/**
 * Generic JDBC SQL parser.
 * Minimal dialect — supports standard CREATE PROCEDURE/FUNCTION multi-line blocks.
 */
public class GeneralJdbcSqlParser extends CommonSqlParser {

    @Override
    protected String resolveEffectiveDialect() {
        return "";
    }

    @Override
    protected String multiLineEndPattern() {
        return "(?i)\\bend\\s+(procedure|function)\\s*;?";
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?(?<TYPE>function|procedure)\\b"
        ).matcher(normalized);
        if (!matcher.find()) {
            return false;
        }

        String routineType = matcher.group("TYPE").toLowerCase(java.util.Locale.ROOT);
        int index = skipWhitespace(normalized, matcher.end());

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
                    || remainder.startsWith("returns")
                    || remainder.startsWith("returning")
                    || remainder.startsWith("as")
                    || remainder.startsWith("is");
        }

        return remainder.startsWith("as")
                || remainder.startsWith("is")
                || remainder.startsWith("begin");
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        return false;
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
