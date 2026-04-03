package com.dbboys.service;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.MetaObjectService;
import com.dbboys.vo.Connect;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.User;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class UserService implements MetaObjectService {
    private final DatabasePlatformResolver platformResolver;

    public UserService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public UserService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    @Override
    public DdlFetcher ddlFetcher() {
        return null;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        return null;
    }

    public List<User> getUsers(Connect connect, Connection conn) throws SQLException {
        return platformResolver.metadata(connect).getUsers(conn);
    }

    public boolean supportsUsers(Connect connect) {
        return platformResolver.metadata(connect).supportsUsers(connect);
    }
}
