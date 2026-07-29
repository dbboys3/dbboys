package com.dbboys.service;

import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.MetaObjectService;
import com.dbboys.core.MetaObjectService.DdlFetcher;
import com.dbboys.model.Connect;
import com.dbboys.model.Database;
import com.dbboys.model.Function;
import com.dbboys.model.ObjectList;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class FunctionService implements MetaObjectService {
    private final DatabasePlatformResolver platformResolver;

    public FunctionService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public FunctionService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
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
        return (connect, conn, objectName) -> platformResolver.ddl(connect).printFunction(conn, objectName);
    }
}
