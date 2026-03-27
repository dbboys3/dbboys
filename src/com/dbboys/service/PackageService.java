package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.db.DDLRepository;
import com.dbboys.util.SqlParserUtil;
import com.dbboys.vo.Connect;
import com.dbboys.vo.DBPackage;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PackageService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public PackageService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public PackageService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
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
        return DDLRepository::printPackage;
    }

    public String getChildrenDDL(String packageDDL,String objectName) throws SQLException {
        return SqlParserUtil.printPackageFunction(packageDDL, objectName);
    }

}


