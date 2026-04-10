package com.dbboys.service;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.util.SqlParserUtil;
import com.dbboys.vo.Connect;
import com.dbboys.vo.DBPackage;
import com.dbboys.vo.Catalog;
import com.dbboys.vo.ObjectList;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PackageService implements MetaObjectService {
    private final DatabasePlatformResolver platformResolver;

    public PackageService() {
        this(com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public PackageService(DatabasePlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = platformResolver.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<DBPackage> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getPackageCount(conn);
        objectList.setInfo(count + "个");
        result.addAll(repo.getPackages(conn, databaseName));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return (connect, conn, objectName) -> platformResolver.ddl(connect).printPackage(conn, objectName);
    }

    public String getChildrenDDL(String packageDDL,String objectName) throws SQLException {
        return SqlParserUtil.printPackageFunction(packageDDL, objectName);
    }

}
