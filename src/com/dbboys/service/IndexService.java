package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepository;
import com.dbboys.impl.MetadataRepositoryImpl;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.Index;
import com.dbboys.vo.ObjectList;
import javafx.concurrent.Task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class IndexService implements MetaObjectService {
    private final MetadataRepository metadataRepository;

    public IndexService() {
        this(new MetadataRepositoryImpl());
    }

    public IndexService(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    public Index getIndex(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepository.getIndex(conn, database.getName(), objectName));
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printIndex;
    }
    public ObjectList loadObjects(Connection conn, String databaseName) throws SQLException {
        ObjectList objectList = new ObjectList();
        List<Index> result = new ArrayList<>();
        objectList.setItems(result);
        int count = metadataRepository.getIndexCount(conn);
        String size = metadataRepository.getIndexSize(conn);
        String info = count + "个";
        if (size != null) {
            info = info + "/" + size;
        }
        objectList.setInfo(info);
        result.addAll(metadataRepository.getIndexes(conn, databaseName));
        return objectList;
    }

    public void refreshIndexMeta(Connect connect,
                                 Database database,
                                 String indexName,
                                 Consumer<Index> onLoadedUi,
                                 Runnable onFinishedUi) {
        Task<Index> indexMetaTask = new Task<>() {
            @Override
            protected Index call() throws Exception {
                return getIndex(connect, database, indexName);
            }
        };
        indexMetaTask.setOnSucceeded(event -> {
            Index index = indexMetaTask.getValue();
            if (index != null && index.getName() != null && onLoadedUi != null) {
                onLoadedUi.accept(index);
            }
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        indexMetaTask.setOnFailed(event -> {
            AppErrorHandler.handle(indexMetaTask.getException());
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        connect.executeSqlTask(indexMetaTask);
    }

    public void disableIndex(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }
    public void enableIndex(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }
}



