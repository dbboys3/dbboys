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
        // SQLite has no multi-line routines in the traditional sense;
        // CREATE TRIGGER has BEGIN...END but is minimal
        return false;
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        return false;
    }
}
