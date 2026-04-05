package com.dbboys.service;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.vo.Connect;
import com.dbboys.vo.MetadataType;
import com.dbboys.vo.ObjectList;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ObjectTypeService implements MetaObjectService {
    private final DatabasePlatformResolver platformResolver;

    public ObjectTypeService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public ObjectTypeService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    @Override
    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<MetadataType> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getObjectTypeCount(conn, databaseName);
        objectList.setInfo(count + "个");
        result.addAll(repo.getObjectTypes(conn, databaseName));
        return objectList;
    }

    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> "--";
    }
}
