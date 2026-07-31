package com.dbboys.core;

import com.dbboys.dialect.common.CommonSqlParser;

/**
 * Minimal SQL parser used as the default when a {@link DatabasePlatform} does not
 * provide a dialect-specific parser.  All statements are treated as single-statement
 * terminated by {@code ;}.
 */
public final class DefaultSqlParser extends CommonSqlParser {

    @Override
    protected String resolveEffectiveDialect() {
        return "";
    }

    @Override
    protected String multiLineEndPattern() {
        return "(?i)\\bend\\s*;";
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        return false;
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        return false;
    }
}
