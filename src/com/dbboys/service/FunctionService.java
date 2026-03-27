package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.Function;
import com.dbboys.vo.ObjectList;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class FunctionService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public FunctionService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public FunctionService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Function> result = new ArrayList<>();
        objectList.setItems(result);
        boolean filterType = repo.hasSysProcTypeColumn(conn);
        int count = repo.getFunctionCount(conn, filterType);
        objectList.setInfo(count + "个");
        result.addAll(repo.getFunctions(conn, databaseName, filterType));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printProcedure;
    }
}


