package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.vo.Connect;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.User;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class UserService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public UserService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public UserService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    @Override
    public DdlFetcher ddlFetcher() {
        return null;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        return null;
    }

    public List<User> getUsers(Connect connect, Connection conn) throws SQLException {
        return metadataRepositoryProvider.metadata(connect).getUsers(conn);
    }
}


