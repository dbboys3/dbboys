package com.dbboys.infra.util;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KeywordsHighlightUtil {

    private static final String STYLE_KEYWORD = "keyword";
    private static final String STYLE_FUNCTION = "function";
    private static final String STYLE_PAREN = "paren";
    private static final String STYLE_BRACKET = "bracket";
    private static final String STYLE_SEMICOLON = "semicolon";
    private static final String STYLE_STRING = "string";
    private static final String STYLE_DOUBLE_STRING = "doublestring";
    private static final String STYLE_BACKTICK_STRING = "fanyinhao";
    private static final String STYLE_COMMENT = "comment";
    private static final String STYLE_NUMBER = "number";
    private static final String STYLE_ERROR = "error";

    private static final String[] INSTANCE_INFO_KEYWORDS = {
            "Instance Boot Information", "System Information", "Connection Information"
    };

    private static final String[] INSTANCE_INFO_FIELDS = {
            "Database Type", "JDBC Driver", "IP Address", "Port", "Database User",
            "Driver Properties", "Database Version", "Connection Name"
    };

    private static final String[] ONLINE_LOG_OK_WORDS = {
            "Started", "On-Line"
    };

    private static final String[] ONLINE_LOG_ERROR_WORDS = {
            "err", "failed", "modified", "Stopped", "warning", "allocated",
            "full", "long", "down", "Died", "Aborting", "Abort"
    };

    private static final String[] SQL_KEYWORDS = {
            // DML/query
            "SELECT", "DISTINCT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC",
            "LIMIT", "OFFSET", "SKIP", "FIRST", "ROWNUM", "ROWID",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE",
            "UNION", "ALL", "INTERSECT", "EXCEPT",

            // Join
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "ON", "USING",

            // DDL/schema
            "CREATE", "ALTER", "DROP", "RENAME", "TRUNCATE",
            "DATABASE", "SCHEMA", "TABLE", "VIEW", "INDEX", "SEQUENCE", "SYNONYM",
            "FUNCTION", "PROCEDURE", "TRIGGER", "PACKAGE",
            "COLUMN", "CONSTRAINT", "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
            "UNIQUE", "CHECK", "DEFAULT", "NOT", "NULL",
            "ENGINE", "CHARSET", "BTREE", "FRAGMENT", "EXPRESSION", "PARTITION", "PAGE",
            "RAW", "STANDARD", "EXTERNAL", "TEMP", "TEMPORARY",
            "EXTENT", "SIZE", "NEXT", "DATAFILES", "SAMEAS", "CHANGE",

            // Control flow / procedural SQL
            "BEGIN", "END", "CASE", "WHEN", "THEN", "ELSE", "IF", "ELIF",
            "WHILE", "FOR", "LOOP", "LET", "RETURN", "CALL", "OUT", "INOUT",
            "DEFINE", "EXECUTE", "IMMEDIATE", "SPL", "LANGUAGE", "TYPE", "ROW",

            // Predicates/operators
            "AND", "OR", "IN", "LIKE", "EXISTS", "IS", "BETWEEN", "ANY", "SOME",

            // Transaction/locking
            "WITH", "NO", "LOG", "COMMIT", "ROLLBACK", "WORK", "LOCK", "MODE",

            // Privilege/security
            "GRANT", "REVOKE", "PUBLIC", "PRIVATE", "USAGE",

            // Runtime/session (GBase/Informix style)
            "ENVIRONMENT", "SQLMODE", "PDQPRIORITY", "IMPLICIT",

            // Object state/statistics
            "ENABLED", "DISABLED", "STATISTICS", "LOW", "MEDIUM", "HIGH", "FORCE",
            "REPLACE", "TO", "AS", "COMMENT", "AFTER", "BEFORE"
    };

    private static final String[] SQL_FUNCTIONS = {
            // Aggregate/statistics
            "AVG", "COUNT", "COUNT_BIG", "MAX", "MEDIAN", "MIN", "STDDEV", "SUM", "VARIANCE",
            "LIST", "LISTAGG", "WM_CONCAT",

            // Null/conditional/comparison
            "CASE", "COALESCE", "DECODE", "GREATEST", "IIF", "IFNULL", "ISNULL", "LEAST", "NULLIF",
            "NVL", "NVL2",

            // String
            "ASCII", "BIT_LENGTH", "CHAR_LENGTH", "CHR", "CONCAT", "INITCAP", "INSTR", "LEFT",
            "LENGTH", "LENGTHB", "LOCATE", "LOWER", "LPAD", "LTRIM", "OCTET_LENGTH", "POSITION",
            "REGEXP_INSTR", "REGEXP_LIKE", "REGEXP_REPLACE", "REGEXP_SUBSTR", "REPEAT", "REPLACE",
            "REVERSE", "RIGHT", "RPAD", "RTRIM", "SUBSTR", "SUBSTRING", "TRANSLATE", "TRIM", "UPPER",

            // Numeric
            "ABS", "ACOS", "ASIN", "ATAN", "ATAN2", "CEIL", "CEILING", "COS", "DEGREES", "EXP",
            "FLOOR", "LN", "LOG", "LOG10", "MOD", "PI", "POWER", "RADIANS", "RAND", "RANDOM",
            "ROUND", "SIGN", "SIN", "SQRT", "TAN", "TRUNC",

            // Date/time
            "ADD_MONTHS", "CURRENT", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "DATEADD",
            "DATEDIFF", "DATE_FORMAT", "DATE_TRUNC", "DBINFO", "EXTRACT", "GETDATE", "LAST_DAY",
            "MONTHS_BETWEEN", "NEXT_DAY", "NOW", "QUARTER", "SYSDATE", "SYSTIMESTAMP", "TO_CHAR",
            "TO_DATE", "TODAY", "WEEK", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "FRACTION",

            // Window/analytic
            "DENSE_RANK", "FIRST_VALUE", "LAG", "LAST_VALUE", "LEAD", "NTH_VALUE", "NTILE",
            "RANK", "ROW_NUMBER",

            // Type/conversion
            "BIGINT", "BIGSERIAL", "BLOB", "BOOLEAN", "BSON", "BYTE", "CAST", "CHAR", "CLOB",
            "COLLECTION", "CONVERT", "DATE", "DATETIME", "DECIMAL", "FLOAT", "INT", "INT8",
            "INTEGER", "INTERVAL", "JSON", "LIST", "LVARCHAR", "MONEY", "MULTISET(SENDRECEIVE)",
            "NCHAR", "NUMBER", "NUMERIC", "NVARCHAR", "NVARCHAR2", "REFSERIAL8", "SERIAL",
            "SERIAL8", "SET(LVARCHAR)", "SMALLFLOAT", "SMALLINT", "STR_TO_DATE", "TEXT",
            "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TO_MULTI_BYTE", "TO_NCHAR", "TO_NUMBER",
            "TO_SINGLE_BYTE", "TRY_CONVERT", "UUID", "SYS_GUID", "ROWREF",
            "VARCHAR", "VARCHAR2", "SYS_REFCURSOR",

            // Crypto/encoding
            "BASE64_DECODE", "BASE64_ENCODE", "DECRYPT", "ENCRYPT", "HASH", "HEX", "MD5", "SHA1",
            "SHA2", "UNHEX",

            // Session/system/bitwise
            "CURRENT_USER", "DATABASE", "HOSTNAME", "SESSION_USER", "SYSTEM_USER", "USER",
            "BITAND", "BITOR", "BITXOR"
    };

    private static final Pattern INSTANCE_INFO_PATTERN = buildInstanceInfoPattern();
    private static final Pattern ONLINE_LOG_PATTERN = buildOnlineLogPattern();
    private static final Pattern SQL_PATTERN = buildSqlPattern();

    private KeywordsHighlightUtil() {
    }

    /** @return a copy of the SQL keyword array (safe for external use). */
    public static String[] getSqlKeywords() {
        return SQL_KEYWORDS.clone();
    }

    /** @return a copy of the SQL function array (safe for external use). */
    public static String[] getSqlFunctions() {
        return SQL_FUNCTIONS.clone();
    }

    public static StyleSpans<Collection<String>> highlightInstanceInfo(String text) {
        return applyPattern(text, INSTANCE_INFO_PATTERN, matcher -> {
            if (matcher.group("KEYWORD") != null) return STYLE_KEYWORD;
            if (matcher.group("FIELD") != null) return STYLE_FUNCTION;
            if (matcher.group("PAREN") != null) return STYLE_PAREN;
            if (matcher.group("BRACE") != null) return "brace";
            if (matcher.group("BRACKET") != null) return STYLE_BRACKET;
            if (matcher.group("SEMICOLON") != null) return STYLE_SEMICOLON;
            if (matcher.group("STRING") != null) return STYLE_STRING;
            if (matcher.group("DOUBLESTRING") != null) return STYLE_DOUBLE_STRING;
            if (matcher.group("BACKTICK") != null) return STYLE_BACKTICK_STRING;
            if (matcher.group("COMMENT") != null) return STYLE_COMMENT;
            return null;
        });
    }

    public static StyleSpans<Collection<String>> highlightOnlineLog(String text) {
        return applyPattern(text, ONLINE_LOG_PATTERN, matcher -> {
            if (matcher.group("ERRORS") != null) return STYLE_ERROR;
            return null;
        });
    }

    public static StyleSpans<Collection<String>> highlightSql(String text) {
        return applyPattern(text, SQL_PATTERN, matcher -> {
            if (matcher.group("DATETIME") != null) return STYLE_FUNCTION;
            if (matcher.group("KEYWORD") != null) return STYLE_KEYWORD;
            if (matcher.group("FUNCTION") != null) return STYLE_FUNCTION;
            if (matcher.group("NUMBER") != null) return STYLE_NUMBER;
            if (matcher.group("PAREN") != null) return STYLE_PAREN;
            if (matcher.group("BRACKET") != null) return STYLE_BRACKET;
            if (matcher.group("SEMICOLON") != null) return STYLE_SEMICOLON;
            if (matcher.group("STRING") != null) return STYLE_STRING;
            if (matcher.group("DOUBLESTRING") != null) return STYLE_DOUBLE_STRING;
            if (matcher.group("BACKTICK") != null) return STYLE_BACKTICK_STRING;
            if (matcher.group("COMMENT") != null) return STYLE_COMMENT;
            return null;
        });
    }

    @Deprecated
    public static StyleSpans<Collection<String>> applyHighlightingInfo(String text) {
        return highlightInstanceInfo(text);
    }

    @Deprecated
    public static StyleSpans<Collection<String>> applyHighlightingOnlinelog(String text) {
        return highlightOnlineLog(text);
    }

    @Deprecated
    public static StyleSpans<Collection<String>> applyHighlighting(String text) {
        return highlightSql(text);
    }

    private static Pattern buildInstanceInfoPattern() {
        String keywordPattern = buildDelimitedAlternation(INSTANCE_INFO_KEYWORDS);
        String fieldPattern = buildDelimitedAlternation(INSTANCE_INFO_FIELDS);
        String parenPattern = "\\(|\\)";
        String bracePattern = "\\{|\\}";
        String bracketPattern = "\\[|\\]";
        String semicolonPattern = "\\;";
        String stringPattern = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
        String doubleStringPattern = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
        String backtickPattern = "`[^`]*`" + "|" + "`[\\s\\S]*";
        String commentPattern = "#[^\\n]*";

        return Pattern.compile(
                "(?<KEYWORD>" + keywordPattern + ")"
                        + "|(?<FIELD>" + fieldPattern + ")"
                        + "|(?<BACKTICK>" + backtickPattern + ")"
                        + "|(?<PAREN>" + parenPattern + ")"
                        + "|(?<BRACE>" + bracePattern + ")"
                        + "|(?<BRACKET>" + bracketPattern + ")"
                        + "|(?<SEMICOLON>" + semicolonPattern + ")"
                        + "|(?<STRING>" + stringPattern + ")"
                        + "|(?<DOUBLESTRING>" + doubleStringPattern + ")"
                        + "|(?<COMMENT>" + commentPattern + ")"
        );
    }

    private static Pattern buildOnlineLogPattern() {
        String keywordPattern = buildDelimitedAlternation(ONLINE_LOG_OK_WORDS);
        String errorPattern = buildDelimitedAlternation(ONLINE_LOG_ERROR_WORDS);
        return Pattern.compile(
                "(?<KEYWORD>" + keywordPattern + ")"
                        + "|(?<ERRORS>" + errorPattern + ")"
        );
    }

    private static Pattern buildSqlPattern() {
        String datetimePattern = "(?i)\\b(year|month|day|hour|minute|second|FRACTION)\\s+to\\s+(year|month|day|hour|minute|second|FRACTION)\\b";
        String keywordPattern = buildDelimitedAlternation(SQL_KEYWORDS);
        String functionPattern = buildDelimitedAlternation(SQL_FUNCTIONS);
        String numberPattern = "(?<![a-z])+\\b\\d+(\\.\\d+)?+\\b(?![a-z])";
        String parenPattern = "\\(|\\)";
        String bracketPattern = "\\[|\\]";
        String semicolonPattern = "\\;";
        String stringPattern = "'([^'\\\\]*(\\\\.[^'\\\\]*)*)'" + "|" + "'[\\s\\S]*";
        String doubleStringPattern = "\"[^\"]*\"" + "|" + "\"[\\s\\S]*";
        String backtickPattern = "`[^`]*`" + "|" + "`[\\s\\S]*";
        String commentPattern = "--[^\\n]*|/\\*[\\s\\S]*?\\*/|/\\*[\\s\\S]*|\\{[\\s\\S]*?\\}";

        return Pattern.compile(
                "(?<DATETIME>" + datetimePattern + ")"
                        + "|(?<KEYWORD>" + keywordPattern + ")"
                        + "|(?<FUNCTION>" + functionPattern + ")"
                        + "|(?<BACKTICK>" + backtickPattern + ")"
                        + "|(?<NUMBER>" + numberPattern + ")"
                        + "|(?<PAREN>" + parenPattern + ")"
                        + "|(?<BRACKET>" + bracketPattern + ")"
                        + "|(?<SEMICOLON>" + semicolonPattern + ")"
                        + "|(?<STRING>" + stringPattern + ")"
                        + "|(?<DOUBLESTRING>" + doubleStringPattern + ")"
                        + "|(?<COMMENT>" + commentPattern + ")"
        );
    }

    private static String buildDelimitedAlternation(String[] terms) {
        StringBuilder pattern = new StringBuilder("(?i)(?<![A-Za-z0-9_])(?:");
        boolean first = true;
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if (!first) {
                pattern.append("|");
            }
            pattern.append(Pattern.quote(term));
            first = false;
        }
        pattern.append(")(?![A-Za-z0-9_])");
        return pattern.toString();
    }

    private static StyleSpans<Collection<String>> applyPattern(
            String text,
            Pattern pattern,
            StyleResolver resolver
    ) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String styleClass = resolver.resolve(matcher);
            if (styleClass == null) {
                continue;
            }
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    @FunctionalInterface
    private interface StyleResolver {
        String resolve(Matcher matcher);
    }
}
