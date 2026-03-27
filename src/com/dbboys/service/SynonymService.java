package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.Synonym;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SynonymService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public SynonymService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public SynonymService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Synonym> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getSynonymCount(conn);
        objectList.setInfo(count + "个");
        result.addAll(repo.getSynonyms(conn, databaseName));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printSynonym;
    }
}


