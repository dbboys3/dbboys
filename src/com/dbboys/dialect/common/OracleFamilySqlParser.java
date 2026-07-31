package com.dbboys.dialect.common;

import com.dbboys.infra.util.SqlParserUtil;
import com.dbboys.model.Sql;

import java.util.*;
import java.util.regex.*;

/**
 * Shared SQL parser for the Oracle family (Oracle, Dameng).
 * Encapsulates PL/SQL block detection, package parsing, Oracle-style
 * {@code END;/} termination, and SQL*Plus {@code /} handling.
 */
public abstract class OracleFamilySqlParser extends CommonSqlParser {

    // ------------------------------------------------------------------
    // Oracle-specific patterns
    // ------------------------------------------------------------------

    protected static final String ORACLE_EDITION_MODIFIER =
            "(?:editionable\\s+|editioning\\s+|noneditionable\\s+)?";

    protected static final String PACKAGE_BODY_PATTERN =
            "(?i)\\bcreate\\s+(?:or\\s+replace\\s+)?(?:editionable\\s+|editioning\\s+|noneditionable\\s+)?package\\s+body\\s+"
                    + "((?:\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$#]*)(?:\\s*\\.\\s*(?:\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$#]*))*)\\s*"
                    + "(AS|IS)\\b";

    protected static final String PACKAGE_MEMBER_PATTERN =
            "(?i)\\bfunction\\s+(?<FUNC>[a-zA-Z0-9_$.]+)\\s*(\\([\\s\\S]*?\\))?\\s+return\\s+([a-zA-Z0-9_$.]+)\\s*(PIPELINED\\s+|DETERMINISTIC\\s+|RESULT_CACHE\\s+)?(AS|IS|;)"
            + "|"
            + "(?i)\\bprocedure\\s+(?<PROC>[a-zA-Z0-9_$.]+)\\s*(\\([\\s\\S]*?\\))?\\s*(AS|IS|;)";

    protected static final Pattern ROUTINE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "(?<TYPE>function|procedure)\\b"
    );

    protected static final Pattern PACKAGE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "package(\\s+body)?\\b"
    );

    protected static final Pattern TRIGGER_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "trigger\\b"
    );

    protected static final Pattern TYPE_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER + "type(\\s+body)?\\b"
    );

    protected static final Pattern BLOCK_NAME_DECLARATION_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER
                    + "(?<TYPE>package(\\s+body)?|procedure|function|trigger|type(\\s+body)?)\\b"
    );

    protected static final Pattern ORACLE_PLAIN_BLOCK_OBJECT_HEAD_PATTERN = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?" + ORACLE_EDITION_MODIFIER
                    + "(?:procedure|function|trigger|type\\s+body)\\b"
    );

    // ------------------------------------------------------------------
    // Hook implementations
    // ------------------------------------------------------------------

    @Override
    protected String resolveEffectiveDialect() {
        return "ORACLE";
    }

    @Override
    protected String multiLineEndPattern() {
        return "(?i)\\bend\\s+(procedure|function)\\s*;?"
                + "|(?i)\\bend\\s*;\\s*/"
                + "|(?i)\\bend\\b\\s+([a-zA-Z_][a-zA-Z0-9_$.]*)\\s*/"
                + "|(?m)^\\s*/\\s*$";
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }
        if (TRIGGER_DECLARATION_PATTERN.matcher(normalized).find()) {
            return true;
        }
        if (TYPE_DECLARATION_PATTERN.matcher(normalized).find()) {
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
        ).matcher(normalized).find();
    }

    @Override
    protected boolean usesPlainBlockEnd(String sqlType, String normalizedSql) {
        if ("CALL_BLOCK".equals(sqlType)) {
            return true;
        }
        if (!"MULTI_LINE_SQL".equals(sqlType)) {
            return false;
        }
        String lower = stripProtectedContent(normalizedSql).trim().toLowerCase(Locale.ROOT);
        return ORACLE_PLAIN_BLOCK_OBJECT_HEAD_PATTERN.matcher(lower).find()
                || lower.startsWith("create procedure if not exists ")
                || lower.startsWith("create function if not exists ");
    }

    @Override
    protected String extractBlockName(String normalizedSql) {
        String normalized = stripCommentsOnly(normalizedSql).trim();
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

    // ------------------------------------------------------------------
    // Package member parsing (Oracle/Dameng specific)
    // ------------------------------------------------------------------

    public List<SqlParserUtil.PackageMember> parsePackageMembers(String packageDdl) {
        List<SqlParserUtil.PackageMember> members = new ArrayList<>();
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
        LinkedHashMap<String, SqlParserUtil.PackageMember> dedup = new LinkedHashMap<>();
        while (memberMatcher.find()) {
            if (memberMatcher.group("FUNC") != null) {
                String n = memberMatcher.group("FUNC");
                dedup.putIfAbsent("F:" + n.toLowerCase(Locale.ROOT),
                        new SqlParserUtil.PackageMember(n, "FUNC"));
            }
            if (memberMatcher.group("PROC") != null) {
                String n = memberMatcher.group("PROC");
                dedup.putIfAbsent("P:" + n.toLowerCase(Locale.ROOT),
                        new SqlParserUtil.PackageMember(n, "PROC"));
            }
        }
        members.addAll(dedup.values());
        return members;
    }

    public String printPackageFunction(String packagesql, String function) {
        if (packagesql == null || packagesql.isBlank() || function == null || function.isBlank()) {
            return "";
        }

        String functionString = "";
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
                "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                        + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                        + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
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
                "(?<STRING>" + STRING_PATTERN_TEXT + ")"
                        + "|(?<DOUBLESTRING>" + DOUBLE_STRING_PATTERN_TEXT + ")"
                        + "|(?<COMMENT>" + COMMENT_PATTERN_TEXT + ")"
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
}
