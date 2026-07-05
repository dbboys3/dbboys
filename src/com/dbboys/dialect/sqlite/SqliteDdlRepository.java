package com.dbboys.dialect.sqlite;

import com.dbboys.core.DdlRepository;
import java.sql.Connection;
import java.util.function.LongConsumer;

public final class SqliteDdlRepository implements DdlRepository {

    private String printObjectDdl(Connection conn, String type, String name) throws Exception {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='" + type + "' AND name='" + name.replace("'", "''") + "'")) {
            return rs.next() ? rs.getString("sql") : "-- " + type + " " + name;
        }
    }

    @Override
    public String printTable(Connection conn, String objectName) throws Exception {
        return printObjectDdl(conn, "table", objectName);
    }

    @Override
    public String printView(Connection conn, String objectName) throws Exception {
        return printObjectDdl(conn, "view", objectName);
    }

    @Override
    public String printIndex(Connection conn, String objectName) throws Exception {
        return printObjectDdl(conn, "index", objectName);
    }

    @Override
    public String printTrigger(Connection conn, String objectName) throws Exception {
        return printObjectDdl(conn, "trigger", objectName);
    }

    @Override public String printSequence(Connection conn, String objectName) throws Exception { return "--"; }
    @Override public String printSynonym(Connection conn, String objectName) throws Exception { return "--"; }
    @Override public String printFunction(Connection conn, String objectName) throws Exception { return "--"; }
    @Override public String printProcedure(Connection conn, String objectName) throws Exception { return "--"; }
    @Override public String printPackage(Connection conn, String objectName) throws Exception { return "--"; }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                 "ORDER BY CASE type WHEN 'table' THEN 1 WHEN 'view' THEN 2 " +
                 "WHEN 'index' THEN 3 WHEN 'trigger' THEN 4 ELSE 5 END, name")) {
            while (rs.next()) {
                String sql = rs.getString("sql");
                if (sql != null && !sql.isBlank()) {
                    sb.append(sql).append(";\n\n");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public DdlRepository.DatabaseDdlParts exportDatabaseDdlParts(Connection conn,
            String databaseName, LongConsumer progressCallback) throws Exception {
        String preSql = buildDdl(conn, "'table', 'view'");
        String postSql = buildDdl(conn, "'index', 'trigger'");
        return new DatabaseDdlParts(preSql, postSql);
    }

    private String buildDdl(Connection conn, String typeFilter) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL " +
                 "AND name NOT LIKE 'sqlite_%' AND type IN (" + typeFilter + ") " +
                 "ORDER BY type, name")) {
            while (rs.next()) {
                String sql = rs.getString("sql");
                if (sql != null && !sql.isBlank()) {
                    sb.append(sql).append(";\n\n");
                }
            }
        }
        return sb.toString();
    }
}
