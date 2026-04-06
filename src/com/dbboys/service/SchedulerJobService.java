package com.dbboys.service;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.MetaObjectService;
import com.dbboys.vo.Connect;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.SchedulerJob;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SchedulerJobService implements MetaObjectService {

    private final DatabasePlatformResolver platformResolver;

    public SchedulerJobService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public SchedulerJobService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    @Override
    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<SchedulerJob> result = new ArrayList<>();
        objectList.setItems(result);
        List<String> names = repo.getSchedulerJobNames(conn, databaseName);
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                result.add(new SchedulerJob(n.trim()));
            }
        }
        objectList.setInfo(result.size() + "个");
        return objectList;
    }

    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> "--";
    }
}
