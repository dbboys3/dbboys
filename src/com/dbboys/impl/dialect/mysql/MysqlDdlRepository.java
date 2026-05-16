package com.dbboys.impl.dialect.mysql;

import com.dbboys.api.DdlRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

public final class MysqlDdlRepository implements DdlRepository {

    @Override
    public long countDatabaseExportItems(Connection conn, String databaseName) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        return count(conn, "select count(*) from information_schema.tables where table_schema = ?", db)
                + count(conn, "select count(*) from information_schema.routines where routine_schema = ?", db)
                + count(conn, "select count(*) from information_schema.triggers where trigger_schema = ?", db);
    }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws SQLException {
        return printDatabase(conn, databaseName, null);
    }

    @Override
    public String printDatabase(Connection conn, String databaseName, LongConsumer progressCallback) throws SQLException {
        String db = currentDatabase(conn, databaseName);
        StringBuilder ddl = new StringBuilder();
        long completed = 0;

        ddl.append("USE ").append(quoteIdentifier(db)).append(";\n\n");
        for (String table : objectNames(conn, db, "BASE TABLE")) {
            ddl.append(printTable(conn, qualified(db, table))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        for (String view : objectNames(conn, db, "VIEW")) {
            ddl.append(printView(conn, qualified(db, view))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        for (String routine : routineNames(conn, db, "FUNCTION")) {
            ddl.append(printFunction(conn, qualified(db, routine))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        for (String routine : routineNames(conn, db, "PROCEDURE")) {
            ddl.append(printProcedure(conn, qualified(db, routine))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        for (String trigger : triggerNames(conn, db)) {
            ddl.append(printTrigger(conn, qualified(db, trigger))).append("\n\n");
            completed = notifyProgress(progressCallback, completed);
        }
        return ddl.toString();
    }

    @Override
    public String printTable(Connection conn, String objectName) throws SQLException {
        String ddl = showCreate(conn, "SHOW CREATE TABLE " + qualifiedIdentifier(conn, objectName), "Create Table");
        return withSemicolon(ddl);
    }

    @Override
    public String printView(Connection conn, String objectName) throws SQLException {
        String ddl = showCreate(conn, "SHOW CREATE VIEW " + qualifiedIdentifier(conn, objectName), "Create View");
        return withSemicolon(ddl);
    }

    @Override
    public String printIndex(Connection conn, String objectName) throws SQLException {
        MysqlMetadataRepository metadata = new MysqlMetadataRepository();
        var index = metadata.getIndex(conn, currentDatabase(conn, null), objectName);
        if (index.getTabname() == null || index.getTabname().isBlank()) {
            return "-- Index DDL requires table name: " + objectName;
        }
        return printTable(conn, qualified(index.getDatabase(), index.getTabname()));
    }

    @Override
    public String printSequence(Connection conn, String objectName) {
        return "-- MySQL does not support standalone sequences in this dialect.";
    }

    @Override
    public String printSynonym(Connection conn, String objectName) {
        return "-- MySQL does not support synonyms.";
    }

    @Override
    public String printFunction(Connection conn, String objectName) throws SQLException {
        String ddl = showCreate(conn, "SHOW CREATE FUNCTION " + qualifiedIdentifier(conn, objectName), "Create Function");
        return delimiterBlock(ddl);
    }

    @Override
    public String printProcedure(Connection conn, String objectName) throws SQLException {
        String ddl = showCreate(conn, "SHOW CREATE PROCEDURE " + qualifiedIdentifier(conn, objectName), "Create Procedure");
        return delimiterBlock(ddl);
    }

    @Override
    public String printTrigger(Connection conn, String objectName) throws SQLException {
        String ddl = showCreate(conn, "SHOW CREATE TRIGGER " + qualifiedIdentifier(conn, objectName), "SQL Original Statement");
        return delimiterBlock(ddl);
    }

    @Override
    public String printPackage(Connection conn, String objectName) {
        return "-- MySQL does not support packages.";
    }

    private static String showCreate(Connection conn, String sql, String ddlColumn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                try {
                    return rs.getString(ddlColumn);
                } catch (SQLException ignored) {
                    return rs.getString(2);
                }
            }
        }
        return "--";
    }

    private static List<String> objectNames(Connection conn, String db, String tableType) throws SQLException {
        String sql = """
                select table_name
                from information_schema.tables
                where table_schema = ? and table_type = ?
                order by table_name
                """;
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, tableType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static List<String> routineNames(Connection conn, String db, String routineType) throws SQLException {
        String sql = """
                select routine_name
                from information_schema.routines
                where routine_schema = ? and routine_type = ?
                order by routine_name
                """;
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, routineType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static List<String> triggerNames(Connection conn, String db) throws SQLException {
        String sql = "select trigger_name from information_schema.triggers where trigger_schema = ? order by trigger_name";
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static long count(Connection conn, String sql, String db) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static long notifyProgress(LongConsumer callback, long completed) {
        long next = completed + 1;
        if (callback != null) {
            callback.accept(next);
        }
        return next;
    }

    private static String delimiterBlock(String ddl) {
        if (ddl == null || ddl.isBlank() || "--".equals(ddl.trim())) {
            return "--";
        }
        return "DELIMITER $$\n" + stripSemicolon(ddl) + "$$\nDELIMITER ;";
    }

    private static String withSemicolon(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return "--";
        }
        return stripSemicolon(ddl) + ";";
    }

    private static String stripSemicolon(String ddl) {
        String value = ddl == null ? "" : ddl.trim();
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private static String qualifiedIdentifier(Connection conn, String rawName) throws SQLException {
        List<String> parts = splitName(rawName);
        if (parts.size() >= 2) {
            return quoteIdentifier(parts.get(parts.size() - 2)) + "." + quoteIdentifier(parts.get(parts.size() - 1));
        }
        return quoteIdentifier(currentDatabase(conn, null)) + "." + quoteIdentifier(rawName);
    }

    private static String qualified(String db, String object) {
        return quoteIdentifier(db) + "." + quoteIdentifier(object);
    }

    private static String currentDatabase(Connection conn, String fallback) throws SQLException {
        if (fallback != null && !fallback.isBlank()) {
            return normalizeIdentifier(fallback);
        }
        String catalog = conn == null ? "" : conn.getCatalog();
        return catalog == null ? "" : catalog.trim();
    }

    private static String quoteIdentifier(String identifier) {
        String value = normalizeIdentifier(identifier);
        if (value.isEmpty()) {
            return "``";
        }
        return "`" + value.replace("`", "``") + "`";
    }

    private static List<String> splitName(String rawName) {
        List<String> parts = new ArrayList<>();
        if (rawName == null) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        boolean inBacktick = false;
        for (int i = 0; i < rawName.length(); i++) {
            char ch = rawName.charAt(i);
            if (ch == '`') {
                inBacktick = !inBacktick;
                current.append(ch);
            } else if (ch == '.' && !inBacktick) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String normalizeIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.startsWith("`") && value.endsWith("`") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1).replace("``", "`");
        }
        return value;
    }
}
