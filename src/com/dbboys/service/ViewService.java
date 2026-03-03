package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepository;
import com.dbboys.impl.MetadataRepositoryImpl;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.View;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ViewService implements MetaObjectService {
    private final MetadataRepository metadataRepository;

    public ViewService() {
        this(new MetadataRepositoryImpl());
    }

    public ViewService(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    public ObjectList loadObjects(Connection conn, String databaseName) throws SQLException {
        ObjectList objectList = new ObjectList();
        List<View> result = new ArrayList<>();
        objectList.setItems(result);
        int count = metadataRepository.getViewCount(conn);
        objectList.setInfo(count + "个");
        result.addAll(metadataRepository.getViews(conn, databaseName));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printView;
    }

}



