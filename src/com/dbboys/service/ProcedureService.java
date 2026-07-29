package com.dbboys.service;

import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.MetaObjectService;
import com.dbboys.core.MetaObjectService.DdlFetcher;
import com.dbboys.model.Connect;
import com.dbboys.model.Database;
import com.dbboys.model.ObjectList;
import com.dbboys.model.Procedure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ProcedureService implements MetaObjectService {
    private final DatabasePlatformResolver platformResolver;

    public ProcedureService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public ProcedureService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
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
        return (connect, conn, objectName) -> platformResolver.ddl(connect).printProcedure(conn, objectName);
    }
    public void updateStatistics(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

}
