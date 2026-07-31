package com.dbboys.infra.util;

import com.dbboys.core.DefaultSqlParser;
import com.dbboys.core.SqlParser;
import com.dbboys.infra.util.SqlParserUtil.Segment;
import com.dbboys.model.Sql;

import java.util.*;
import java.util.regex.*;

public class SqlFormatter {
    private static final String STRING_PATTERN_TEXT = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
    private static final String DOUBLE_STRING_PATTERN_TEXT = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
    private static final String FANYINHAO_STRING_PATTERN_TEXT = "`[^`]*`" + "|" + "`[\\s\\S]*";
    private static final String COMMENT_PATTERN_TEXT = "--[^\\n]*" + "|"+"/\\*[\\s\\S]*?\\*/"+"|"+"/\\*[\\s\\S]*" +"|"+"\\{[\\s\\S]*?\\}";

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
            "create type ",
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

    private static final SqlParser FORMAT_PARSER = new DefaultSqlParser();

    private static List<String> splitStatementsForFormat(String sql) {
        List<String> statements = new ArrayList<>();
        Sql currentSql = new Sql();
        for (Segment segment : SqlParserUtil.split(sql)) {
            String remainingChunk = segment.getText();
            while (remainingChunk != null && !remainingChunk.isBlank()) {
                currentSql = FORMAT_PARSER.modifySql(currentSql, remainingChunk);
                if (!currentSql.getSqlEnd()) {
                    break;
                }
                if (SqlParserUtil.isExecutableStatement(currentSql.getSqlstr())) {
                    statements.add(currentSql.getSqlstr());
                }
                remainingChunk = currentSql.getSqlRemainder();
                currentSql = new Sql();
            }
        }
        if (SqlParserUtil.isExecutableStatement(currentSql.getSqlstr())) {
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
}
