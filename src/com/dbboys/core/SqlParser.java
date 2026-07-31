package com.dbboys.core;

import com.dbboys.model.Sql;

/**
 * Dialect-aware SQL parser.
 * Each {@link DatabasePlatform} provides its own parser via {@link DatabasePlatform#parser()},
 * so statement-splitting logic, multi-line detection, and block-end rules are resolved
 * polymorphically instead of via string-based dialect routing inside {@code SqlParserUtil}.
 */
public interface SqlParser {

    /**
     * Appends {@code addSql} to the accumulating {@code sql} state, detecting statement
     * boundaries and classifying the statement type.  This is the core state machine
     * previously implemented in {@code SqlParserUtil.modifySql}.
     */
    Sql modifySql(Sql sql, String addSql);

    /**
     * Returns true when {@code remainderSql} (the text after a completed statement)
     * itself contains at least one more executable statement or multi-line start.
     */
    boolean hasMoreStatements(String remainderSql);

    /**
     * Returns true when {@code remainderSql} contains more than one executable
     * statement (used to force multi-statement mode in the UI).
     */
    boolean hasMoreThanOneStatement(String remainderSql);

    /**
     * Counts the number of executable statements in {@code sqlText}.
     */
    int countExecutableStatements(String sqlText);

    /**
     * Returns true when {@code sqlText} is empty / blank or contains at most one
     * executable statement.
     */
    boolean isSingleStatement(String sqlText);
}
