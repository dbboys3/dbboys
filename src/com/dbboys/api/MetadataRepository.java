package com.dbboys.api;

import com.dbboys.vo.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public interface MetadataRepository {

    List<User> getUsers(Connection conn) throws SQLException;

    List<Database> getDatabases(Connection conn, boolean useOracleSyntax) throws SQLException;

    Database getDatabaseInfo(Connection conn, String databaseName) throws SQLException;

    int getUserTablesCount(Connection conn) throws SQLException;

    String getUserTablesSize(Connection conn, String databaseName) throws SQLException;

    int getSystemTablesCount(Connection conn) throws SQLException;

    String getSystemTablesSize(Connection conn, String databaseName) throws SQLException;

    List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException;

    List<Table> getUserTables(Connection conn, String databaseName) throws SQLException;

    Table getTable(Connection conn, String databaseName, String tableName) throws SQLException;

    String getTableComment(Connection conn, String tableName) throws SQLException;

    ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException;

    List<Index> getIndexes(Connection conn, String databaseName) throws SQLException;

    int getIndexCount(Connection conn) throws SQLException;

    String getIndexSize(Connection conn) throws SQLException;

    Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException;

    int getSequenceCount(Connection conn) throws SQLException;

    List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException;

    int getSynonymCount(Connection conn) throws SQLException;

    List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException;

    int getTriggerCount(Connection conn) throws SQLException;

    List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException;

    Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException;

    int getViewCount(Connection conn) throws SQLException;

    List<View> getViews(Connection conn, String databaseName) throws SQLException;

    int getSystemDualTabId(Connection conn) throws SQLException;

    boolean hasSysProcTypeColumn(Connection conn) throws SQLException;

    int getFunctionCount(Connection conn, boolean filterType) throws SQLException;

    List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException;

    int getProcedureCount(Connection conn, boolean filterType) throws SQLException;

    List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException;

    int getPackageCount(Connection conn) throws SQLException;

    List<DBPackage> getPackages(Connection conn, String databaseName) throws SQLException;

    List<String> getDBspaceForCreateDatabase(Connection conn) throws SQLException;

    void changeDatabase(Connection conn, String databaseName) throws SQLException;

    void setDatabase(Connection conn, String databaseName) throws SQLException;

    List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException;
}
