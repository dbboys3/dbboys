package com.dbboys.service;

import com.dbboys.core.DatabasePlatformResolver;
import com.dbboys.core.MetaObjectService;
import com.dbboys.core.MetaObjectService.DdlFetcher;
import com.dbboys.model.Connect;
import com.dbboys.model.Database;
import com.dbboys.model.ObjectList;
import com.dbboys.model.View;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ViewService implements MetaObjectService {
    private final DatabasePlatformResolver platformResolver;

    public ViewService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public ViewService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<View> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getViewCount(conn);
        objectList.setInfo(count + "个");
        result.addAll(repo.getViews(conn, databaseName));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> platformResolver.ddl(connect).printView(conn, objectName);
    }

}
