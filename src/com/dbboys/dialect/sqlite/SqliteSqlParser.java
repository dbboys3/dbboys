package com.dbboys.dialect.sqlite;

import com.dbboys.dialect.common.CommonSqlParser;

/**
 * SQLite SQL parser.
 * Simplest dialect — all statements are single-statement terminated by {@code ;}.
 */
public class SqliteSqlParser extends CommonSqlParser {

    @Override
    protected String resolveEffectiveDialect() {
        return "SQLITE";
    }

    @Override
    protected String multiLineEndPattern() {
        return "(?i)\\bend\\s*;";
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        // CREATE TRIGGER uses a BEGIN ... END block whose body can contain
        // multiple semicolon-separated statements.
        String normalized = stripCommentsOnly(sql).trim();
        return !normalized.isEmpty()
                && java.util.regex.Pattern.compile(
                        "(?is)^\\s*create\\s+(?:temp\\s+|temporary\\s+)?(?:or\\s+replace\\s+)?trigger\\b"
                ).matcher(normalized).find();
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        return false;
    }
}
