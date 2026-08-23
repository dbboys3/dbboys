package com.dbboys.dialect.sqlite;

import com.dbboys.core.DdlRepository;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
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
    public String printTableWithDependencies(Connection conn, String objectName) throws Exception {
        StringBuilder ddl = new StringBuilder(printTable(conn, objectName));
        for (String indexSql : printDependencyDdl(conn, "index", objectName)) {
            ddl.append(";\n\n").append(indexSql);
        }
        for (String triggerSql : printDependencyDdl(conn, "trigger", objectName)) {
            ddl.append(";\n\n").append(triggerSql);
        }
        return ddl.toString();
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

    private List<String> printDependencyDdl(Connection conn, String type, String tableName) throws Exception {
        List<String> result = new ArrayList<>();
        String sql = """
                SELECT sql
                FROM sqlite_master
                WHERE type = ?
                  AND tbl_name = ?
                  AND sql IS NOT NULL
                  AND name NOT LIKE 'sqlite_%'
                ORDER BY name
                """;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, type);
            stmt.setString(2, tableName);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }
}
