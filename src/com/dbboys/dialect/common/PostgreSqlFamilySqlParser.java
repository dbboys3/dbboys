package com.dbboys.dialect.common;

import java.util.regex.Pattern;

/**
 * Shared SQL parser for the PostgreSQL family.
 * Encapsulates dollar-quote block detection, {@code DO $$} anonymous blocks,
 * and {@code EXECUTE FUNCTION} trigger style detection.
 */
public abstract class PostgreSqlFamilySqlParser extends CommonSqlParser {

    @Override
    protected String resolveEffectiveDialect() {
        return "POSTGRESQL";
    }

    @Override
    protected String multiLineEndPattern() {
        return "(?i)\\bend\\s+(procedure|function)\\s*;?"
                + "|" + "\\$\\$(\\s*language\\s+\\w+)?\\s*;"
                + "|" + "\\$[a-zA-Z_]\\w*\\$(\\s*language\\s+\\w+)?\\s*;"
                + "|" + "\\'\\s*language\\s+\\w+\\s*;";
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }

        // PostgreSQL triggers with EXECUTE FUNCTION are single-statement
        if (Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?trigger\\b"
        ).matcher(normalized).find()) {
            if (isPostgreSqlTriggerStyle(normalized)) {
                return false;
            }
            return true;
        }

        // TYPE declarations without BODY are single-statement (e.g. CREATE TYPE ... AS ENUM)
        if (Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?type\\b"
        ).matcher(normalized).find()) {
            String lowerNormalized = normalized.toLowerCase(java.util.Locale.ROOT);
            if (!lowerNormalized.contains(" as") && !lowerNormalized.contains(" is")
                    && !Pattern.compile("(?im)^\\s*(as|is)\\b").matcher(normalized).find()) {
                return false;
            }
            return true;
        }

        // Check CREATE [OR REPLACE] [FUNCTION|PROCEDURE]
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

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        String normalized = stripCommentsOnly(sql);
        if (normalized.isBlank()) {
            return false;
        }
        return Pattern.compile(
                "(?i)^\\s*\\b(begin)(?!\\s*(;|work))|(?i)^\\s*\\b(DECLARE)(?!\\s*;)"
                        + "|(?i)^\\s*\\bdo\\s+\\$\\$"
                        + "|(?i)^\\s*\\bdo\\s+\\$[a-zA-Z_]\\w*\\$"
        ).matcher(normalized).find();
    }

    @Override
    protected String extractBlockName(String normalizedSql) {
        String normalized = stripCommentsOnly(normalizedSql).trim();
        java.util.regex.Matcher matcher = Pattern.compile(
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
    // Helpers
    // ------------------------------------------------------------------

    /**
     * PostgreSQL-style triggers use {@code EXECUTE FUNCTION procname()} or
     * {@code EXECUTE PROCEDURE procname()} instead of a PL/SQL BEGIN...END body.
     * They are single-statement, not multi-line.
     */
    protected static boolean isPostgreSqlTriggerStyle(String normalized) {
        return Pattern.compile(
                "(?i)\\bfor\\s+each\\s+(row|statement)\\b[\\s\\S]*\\bexecute\\s+(function|procedure)\\b"
        ).matcher(normalized).find();
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
