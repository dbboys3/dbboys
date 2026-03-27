package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
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
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public IndexService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public IndexService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    public Index getIndex(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepositoryProvider.metadata(connect).getIndex(conn, database.getName(), objectName));
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printIndex;
    }
    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Index> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getIndexCount(conn);
        String size = repo.getIndexSize(conn);
        String info = count + "个";
        if (size != null) {
            info = info + "/" + size;
        }
        objectList.setInfo(info);
        result.addAll(repo.getIndexes(conn, databaseName));
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


