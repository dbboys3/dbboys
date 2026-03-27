package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.Sequence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SequenceService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public SequenceService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public SequenceService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    @Override
    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
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
        return DDLRepository::printSequence;
    }
}


