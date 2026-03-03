package com.dbboys.service;

import com.dbboys.api.MetadataRepository;
import com.dbboys.impl.MetadataRepositoryImpl;
import com.dbboys.api.MetaObjectService;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.User;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class UserService implements MetaObjectService {
    private final MetadataRepository metadataRepository;

    public UserService() {
        this(new MetadataRepositoryImpl());
    }

    public UserService(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }


    @Override
    public DdlFetcher ddlFetcher() {
        return null;
    }

    public ObjectList loadObjects(Connection conn, String databaseName) throws SQLException {
        return null;
    }

    public List<User> getUsers(Connection conn) throws SQLException {
        return metadataRepository.getUsers(conn);
    }
}



