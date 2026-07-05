package com.dbboys.dialect.sqlite;

import com.dbboys.core.MetadataRepository;
import com.dbboys.model.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class SqliteMetadataRepository implements MetadataRepository {

    @Override public List<User> getUsers(Connection conn) { return List.of(); }

    @Override public List<Catalog> getDatabases(Connection conn) {
        Catalog main = new Catalog(); main.setName("main"); main.setDbSize("");
        return List.of(main);
    }

    @Override public Catalog getDatabaseInfo(Connection conn, String databaseName) {
        Catalog info = new Catalog(); info.setName("main"); info.setDbSize("");
        return info;
    }

    @Override public int getUserTablesCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%%'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override public String getUserTablesSize(Connection conn, String databaseName) { return null; }

    @Override public int getSystemTablesCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'sqlite_%%'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override public String getSystemTablesSize(Connection conn, String databaseName) { return null; }

    @Override public List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException {
        List<SysTable> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'sqlite_%%' ORDER BY name")) {
            while (rs.next()) {
                SysTable t = new SysTable(); t.setName(rs.getString(1)); tables.add(t);
            }
        }
        return tables;
    }

    @Override public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        List<Table> tables = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%%' ORDER BY name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                names.add(name);
                Table t = new Table(); t.setName(name); t.setTableTypeCode("TABLE"); tables.add(t);
            }
        }
        for (int i = 0; i < names.size(); i++) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) FROM \"" + names.get(i).replace("\"", "\"\"") + "\"")) {
                if (rs.next()) { tables.get(i).setNrows(rs.getInt(1)); }
            } catch (SQLException ignored) { tables.get(i).setNrows(-1); }
        }
        return tables;
    }

    @Override public Table getTable(Connection conn, String databaseName, String tableName) {
        Table t = new Table(); t.setName(tableName); t.setTableTypeCode("TABLE");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM \"" + tableName.replace("\"", "\"\"") + "\"")) {
            if (rs.next()) { t.setNrows(rs.getInt(1)); }
        } catch (SQLException ignored) { t.setNrows(-1); }
        return t;
    }

    @Override public String getTableComment(Connection conn, String tableName) { return ""; }

    @Override public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        ArrayList<ColumnsInfo> columns = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                ColumnsInfo col = new ColumnsInfo();
                col.setColName(rs.getString("name"));
                col.setColType(rs.getString("type") == null ? "TEXT" : rs.getString("type"));
                col.setIsNullable(rs.getInt("notnull") == 0);
                col.setColDef(rs.getString("dflt_value"));
                columns.add(col);
            }
        }
        return columns;
    }

    @Override
    public java.util.List<String> getPrimaryKeyColumns(java.sql.Connection conn, String tableName) throws java.sql.SQLException {
        java.util.List<String> pkColumns = new java.util.ArrayList<>();
        String sql = "SELECT name FROM pragma_table_info('" + tableName.replace("'", "''") + "') WHERE pk > 0 ORDER BY pk";
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                pkColumns.add(rs.getString("name"));
            }
        }
        return pkColumns;
    }

    @Override public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        List<Index> indexes = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, tbl_name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%%' ORDER BY name")) {
            while (rs.next()) {
                Index idx = new Index(); idx.setName(rs.getString(1)); idx.setTabname(rs.getString("tbl_name")); indexes.add(idx);
            }
        }
        return indexes;
    }

    @Override public int getIndexCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%%'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override public String getIndexSize(Connection conn) { return null; }

    @Override public Index getIndex(Connection conn, String databaseName, String indexName) {
        Index idx = new Index(); idx.setName(indexName);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT tbl_name FROM sqlite_master WHERE type='index' AND name='" + indexName.replace("'", "''") + "'")) {
            if (rs.next()) { idx.setTabname(rs.getString("tbl_name")); }
        } catch (SQLException ignored) {}
        return idx;
    }

    @Override public int getSequenceCount(Connection conn) { return 0; }
    @Override public List<Sequence> getSequences(Connection conn, String databaseName) { return List.of(); }
    @Override public int getSynonymCount(Connection conn) { return 0; }
    @Override public List<Synonym> getSynonyms(Connection conn, String databaseName) { return List.of(); }

    @Override public int getTriggerCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='trigger'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        List<Trigger> triggers = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name")) {
            while (rs.next()) {
                Trigger t = new Trigger(); t.setName(rs.getString(1)); triggers.add(t);
            }
        }
        return triggers;
    }

    @Override public Trigger getTrigger(Connection conn, String databaseName, String triggerName) {
        Trigger t = new Trigger(); t.setName(triggerName); return t;
    }

    @Override public int getViewCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='view'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        List<View> views = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='view' ORDER BY name")) {
            while (rs.next()) {
                View v = new View(); v.setName(rs.getString(1)); views.add(v);
            }
        }
        return views;
    }

    @Override public int getSystemDualTabId(Connection conn) { return -1; }
    @Override public boolean hasSysProcTypeColumn(Connection conn) { return false; }

    @Override public int getFunctionCount(Connection conn, boolean filterType) { return 0; }
    @Override public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) { return List.of(); }
    @Override public int getProcedureCount(Connection conn, boolean filterType) { return 0; }
    @Override public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) { return List.of(); }
    @Override public int getPackageCount(Connection conn) { return 0; }
    @Override public List<DBPackage> getPackages(Connection conn, String databaseName) { return List.of(); }
    @Override public List<String> getStorageSpacesForCreateDatabase(Connection conn) { return List.of(); }
    @Override public void changeDatabase(Connection conn, String databaseName) {}
    @Override public void setDatabase(Connection conn, String databaseName) {}
    @Override public List<String> getIndexColumnsForTable(Connection conn, String tableName) { return List.of(); }
}
