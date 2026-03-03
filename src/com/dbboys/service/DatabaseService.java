package com.dbboys.service;

import com.dbboys.api.MetadataRepository;
import com.dbboys.impl.MetadataRepositoryImpl;
import com.dbboys.api.MetaObjectService;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

public class DatabaseService implements MetaObjectService {
    //private static final Logger log = LogManager.getLogger(DatabaseService.class);
    private final MetadataRepository metadataRepository;

    public DatabaseService() {
        this(new MetadataRepositoryImpl());
    }

    public DatabaseService(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    public List<String> getDBspaceForCreateDatabase(Connection conn) throws SQLException {
        return metadataRepository.getDBspaceForCreateDatabase(conn);
    }

    public List<Database> getDatabases(Connection conn, boolean useOracleSyntax) throws SQLException {
        return metadataRepository.getDatabases(conn, useOracleSyntax);
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return null;
    }

    public ObjectList loadObjects(Connection conn, String databaseName) throws SQLException {
        ObjectList objectList = new ObjectList();
        List<String> result = new ArrayList<>();
        objectList.setItems(result);
        objectList.setSuccess(false);

        Database database = metadataRepository.getDatabaseInfo(conn, databaseName);
        objectList.setInfo(database);

        boolean filterType = metadataRepository.hasSysProcTypeColumn(conn);

        int sysCount = metadataRepository.getSystemTablesCount(conn);
        String sysSize = metadataRepository.getSystemTablesSize(conn, databaseName);
        result.add(sysSize == null ? (sysCount + "个") : (sysCount + "个/" + sysSize));

        int tableCount = metadataRepository.getUserTablesCount(conn);
        String tableSize = metadataRepository.getUserTablesSize(conn, databaseName);
        result.add(tableSize == null ? (tableCount + "个") : (tableCount + "个/" + tableSize));

        int viewCount = metadataRepository.getViewCount(conn);
        result.add(viewCount + "个");

        int indexCount = metadataRepository.getIndexCount(conn);
        String indexSize = metadataRepository.getIndexSize(conn);
        result.add(indexSize == null ? (indexCount + "个") : (indexCount + "个/" + indexSize));

        int seqCount = metadataRepository.getSequenceCount(conn);
        result.add(seqCount + "个");

        int synCount = metadataRepository.getSynonymCount(conn);
        result.add(synCount + "个");

        int triggerCount = metadataRepository.getTriggerCount(conn);
        result.add(triggerCount + "个");

        int funcCount = metadataRepository.getFunctionCount(conn, filterType);
        result.add(funcCount + "个");

        int procCount = metadataRepository.getProcedureCount(conn, filterType);
        result.add(procCount + "个");

        int pkgCount = metadataRepository.getPackageCount(conn);
        result.add(pkgCount + "个");

        objectList.setSuccess(true);
        return objectList;
    }

    public void updateStatistics(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }
}

