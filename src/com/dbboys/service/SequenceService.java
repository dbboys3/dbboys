package com.dbboys.service;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Catalog;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.Sequence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SequenceService implements MetaObjectService {
    private final DatabasePlatformResolver platformResolver;

    public SequenceService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public SequenceService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    @Override
    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Sequence> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getSequenceCount(conn);
        objectList.setInfo(count + "个");
        result.addAll(repo.getSequences(conn, databaseName));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> platformResolver.ddl(connect).printSequence(conn, objectName);
    }
}
