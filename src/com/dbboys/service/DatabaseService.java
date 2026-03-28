package com.dbboys.service;

import com.dbboys.api.DdlRepositoryProvider;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;
    private final DdlRepositoryProvider ddlRepositoryProvider;

    public DatabaseService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class),
                com.dbboys.app.AppContext.get(DdlRepositoryProvider.class));
    }

    public DatabaseService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this(metadataRepositoryProvider, com.dbboys.app.AppContext.get(DdlRepositoryProvider.class));
    }

    public DatabaseService(MetadataRepositoryProvider metadataRepositoryProvider,
                           DdlRepositoryProvider ddlRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
        this.ddlRepositoryProvider = ddlRepositoryProvider;
    }

    public List<String> getDBspaceForCreateDatabase(Connect connect) throws SQLException {
        return metadataRepositoryProvider.metadata(connect).getDBspaceForCreateDatabase(connect.getConn());
    }

    public List<Database> getDatabases(Connect connect) throws SQLException {
        return metadataRepositoryProvider.metadata(connect).getDatabases(connect.getConn());
    }

    public List<Database> getDatabases(Connect connect, boolean useOracleSyntax) throws SQLException {
        return metadataRepositoryProvider.metadata(connect).getDatabases(connect.getConn(), useOracleSyntax);
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> ddlRepositoryProvider.ddl(connect).printDatabase(conn, objectName);
    }

    public String exportDatabaseDdl(Connect connect, Database database) throws Exception {
        return withMetaSession(connect, database,
                conn -> ddlRepositoryProvider.ddl(connect).printDatabase(conn, database.getName()));
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<String> result = new ArrayList<>();
        objectList.setItems(result);
        objectList.setSuccess(false);

        Database database = repo.getDatabaseInfo(conn, databaseName);
        objectList.setInfo(database);

        boolean filterType = repo.hasSysProcTypeColumn(conn);

        int sysCount = repo.getSystemTablesCount(conn);
        String sysSize = repo.getSystemTablesSize(conn, databaseName);
        result.add(sysSize == null ? (sysCount + "个") : (sysCount + "个/" + sysSize));

        int tableCount = repo.getUserTablesCount(conn);
        String tableSize = repo.getUserTablesSize(conn, databaseName);
        result.add(tableSize == null ? (tableCount + "个") : (tableCount + "个/" + tableSize));

        int viewCount = repo.getViewCount(conn);
        result.add(viewCount + "个");

        int indexCount = repo.getIndexCount(conn);
        String indexSize = repo.getIndexSize(conn);
        result.add(indexSize == null ? (indexCount + "个") : (indexCount + "个/" + indexSize));

        int seqCount = repo.getSequenceCount(conn);
        result.add(seqCount + "个");

        int synCount = repo.getSynonymCount(conn);
        result.add(synCount + "个");

        int triggerCount = repo.getTriggerCount(conn);
        result.add(triggerCount + "个");

        int funcCount = repo.getFunctionCount(conn, filterType);
        result.add(funcCount + "个");

        int procCount = repo.getProcedureCount(conn, filterType);
        result.add(procCount + "个");

        int pkgCount = repo.getPackageCount(conn);
        result.add(pkgCount + "个");

        objectList.setSuccess(true);
        return objectList;
    }

    public void updateStatistics(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }
}
