package com.dbboys.service;

import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.Trigger;
import javafx.concurrent.Task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TriggerService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public TriggerService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public TriggerService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    public Trigger getTrigger(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepositoryProvider.metadata(connect).getTrigger(conn, database.getName(), objectName));
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Trigger> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getTriggerCount(conn);
        objectList.setInfo(count + "个");
        result.addAll(repo.getTriggers(conn, databaseName));
        return objectList;
    }
    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printTrigger;
    }

    public void refreshTriggerMeta(Connect connect,
                                   Database database,
                                   String triggerName,
                                   Consumer<Trigger> onLoadedUi,
                                   Runnable onFinishedUi) {
        Task<Trigger> triggerMetaTask = new Task<>() {
            @Override
            protected Trigger call() throws Exception {
                return getTrigger(connect, database, triggerName);
            }
        };
        triggerMetaTask.setOnSucceeded(event -> {
            Trigger trigger = triggerMetaTask.getValue();
            if (trigger != null && trigger.getName() != null && onLoadedUi != null) {
                onLoadedUi.accept(trigger);
            }
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        triggerMetaTask.setOnFailed(event -> {
            AppErrorHandler.handle(triggerMetaTask.getException());
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        connect.executeSqlTask(triggerMetaTask);
    }


    public void disableTrigger(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }
    public void enableTrigger(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }
}


