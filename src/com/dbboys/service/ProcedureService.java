package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepository;
import com.dbboys.impl.MetadataRepositoryImpl;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.Procedure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ProcedureService implements MetaObjectService {
    private final MetadataRepository metadataRepository;

    public ProcedureService() {
        this(new MetadataRepositoryImpl());
    }

    public ProcedureService(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    public ObjectList loadObjects(Connection conn, String databaseName) throws SQLException {
        ObjectList objectList = new ObjectList();
        List<Procedure> result = new ArrayList<>();
        objectList.setItems(result);
        boolean filterType = metadataRepository.hasSysProcTypeColumn(conn);
        int count = metadataRepository.getProcedureCount(conn, filterType);
        objectList.setInfo(count + "个");
        result.addAll(metadataRepository.getProcedures(conn, databaseName, filterType));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printProcedure;
    }
    public void updateStatistics(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

}



