package com.dbboys.dialect.db2;

import com.dbboys.dialect.common.CommonSqlParser;

import java.util.regex.Pattern;

/**
 * Db2 SQL parser. Handles SQL PL routines (CREATE PROCEDURE/FUNCTION/TRIGGER)
 * whose bodies are plain BEGIN...END blocks terminated by a bare {@code END;}.
 */
public final class Db2SqlParser extends CommonSqlParser {

    @Override
    protected String resolveEffectiveDialect() {
        return "DB2";
    }

    @Override
    protected String multiLineEndPattern() {
        // Routines end with a bare END; — matched at end of input so inner
        // BEGIN...END; blocks are not mistaken for the routine terminator.
        // Depth tracking (plain block mode) handles the nested cases.
        return "(?i)\\bend\\s*;\\s*$";
    }

    @Override
    protected boolean usesPlainBlockEnd(String sqlType, String normalizedSql) {
        return true;
    }

    @Override
    protected boolean isMultiLineSqlStart(String sql) {
        String normalized = stripCommentsOnly(sql).trim();
        if (normalized.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?is)^\\s*create\\s+(?:or\\s+replace\\s+)?(?<TYPE>procedure|function|trigger)\\b"
        ).matcher(normalized);
        if (!matcher.find()) {
            return false;
        }
        // Only routines with a body are multi-line; a forward declaration or a
        // single-expression function without BEGIN stays a plain statement.
        return Pattern.compile("(?i)\\bbegin\\b").matcher(normalized).find();
    }

    @Override
    protected boolean isAnonymousBlockStart(String sql) {
        String normalized = stripCommentsOnly(sql);
        if (normalized.isBlank()) {
            return false;
        }
        // Db2 anonymous compound statement: BEGIN ATOMIC ... END
        return Pattern.compile("(?i)^\\s*\\bbegin\\s+atomic\\b").matcher(normalized).find();
    }
}
