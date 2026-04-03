package com.dbboys.impl.dialect.oracle;

import com.dbboys.api.MetadataRepository;
import com.dbboys.vo.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 元数据占位实现，所有方法暂抛 UnsupportedOperationException。
 */
public final class OracleMetadataRepository implements MetadataRepository {

    private static final String MSG = "Oracle metadata not implemented";

    @Override
    public List<User> getUsers(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Database> getDatabases(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public Database getDatabaseInfo(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getUserTablesCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public String getUserTablesSize(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getSystemTablesCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public String getSystemTablesSize(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public String getTableComment(Connection conn, String tableName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getIndexCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public String getIndexSize(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getSequenceCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getSynonymCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getTriggerCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getViewCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getSystemDualTabId(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public boolean hasSysProcTypeColumn(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getFunctionCount(Connection conn, boolean filterType) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public int getPackageCount(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<DBPackage> getPackages(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<String> getStorageSpacesForCreateDatabase(Connection conn) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void changeDatabase(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        throw new UnsupportedOperationException(MSG);
    }
}
