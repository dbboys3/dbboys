package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
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
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public ProcedureService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public ProcedureService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Procedure> result = new ArrayList<>();
        objectList.setItems(result);
        boolean filterType = repo.hasSysProcTypeColumn(conn);
        int count = repo.getProcedureCount(conn, filterType);
        objectList.setInfo(count + "个");
        result.addAll(repo.getProcedures(conn, databaseName, filterType));
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


