package com.dbboys.dialect.gbase8s;

import com.dbboys.dialect.common.InformixFamilySqlParser;

/**
 * GBase 8S SQL parser.
 * Inherits Informix-family parsing with SQLMODE detection via
 * {@link InformixFamilySqlParser#onNewStatementStart}.
 *
 * <p>For the SQL editor UI, the parser selection is done externally in
 * {@code SqlExecutionHelper} based on the sqlmode choicebox value,
 * mapping to {@code OracleSqlParser} / {@code MysqlSqlParser} /
 * {@code InformixSqlParser}.</p>
 *
 * <p>For SQL script import, this parser is used directly — it detects
 * {@code SET ENVIRONMENT SQLMODE 'xxx'} and updates the {@link Sql} model,
 * allowing subsequent statements to be parsed with the correct family logic.</p>
 */
public class Gbase8sSqlParser extends InformixFamilySqlParser {
}
