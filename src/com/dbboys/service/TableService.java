package com.dbboys.service;

import com.dbboys.app.AppExecutor;
import com.dbboys.api.MetaObjectService;
import com.dbboys.api.MetaObjectService.DdlFetcher;
import com.dbboys.api.MetadataRepositoryProvider;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.*;
import javafx.concurrent.Task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TableService implements MetaObjectService {
    private final MetadataRepositoryProvider metadataRepositoryProvider;

    public TableService() {
        this(com.dbboys.app.AppContext.get(MetadataRepositoryProvider.class));
    }

    public TableService(MetadataRepositoryProvider metadataRepositoryProvider) {
        this.metadataRepositoryProvider = metadataRepositoryProvider;
    }

    public ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<Table> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getUserTablesCount(conn);
        String size = repo.getUserTablesSize(conn, databaseName);
        String info = count + "个";
        if (size != null) {
            info = info + "/" + size;
        }
        objectList.setInfo(info);
        LOG.info("loadObjects: " + info );
        result.addAll(repo.getUserTables(conn, databaseName));
        return objectList;
    }


    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printTable;
    }


    public ObjectList loadSystemTables(Connect connect, Database database) throws Exception {
        return withMetaSession(connect, database, conn -> buildSystemTables(connect, conn, database.getName()));
    }

    private ObjectList buildSystemTables(Connect connect, Connection conn, String databaseName) throws SQLException {
        var repo = metadataRepositoryProvider.metadata(connect);
        ObjectList objectList = new ObjectList();
        List<SysTable> result = new ArrayList<>();
        objectList.setItems(result);
        int count = repo.getSystemTablesCount(conn);
        String size = repo.getSystemTablesSize(conn, databaseName);
        objectList.setInfo(count + "个/" + size);
        result.addAll(repo.getSystemTables(conn, databaseName));
        return objectList;
    }
    public ArrayList<ColumnsInfo> getColumns(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> (ArrayList<ColumnsInfo>)DDLRepository.getColInfo(conn, objectName));
    }

    public Table getTable(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepositoryProvider.metadata(connect).getTable(conn, database.getName(), objectName));
    }

    public String getTableComment(Connect connect, Database database, String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepositoryProvider.metadata(connect).getTableComment(conn, objectName));
    }

    public void updateStatistics(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

    public void modifyTableToRaw(Connect connect, String tableName, Runnable onSucceededUi) {
        executeObjectSql(connect, "alter table " + tableName + " type(raw)", onSucceededUi);
    }

    public void modifyTableToStandard(Connect connect, String tableName, Runnable onSucceededUi) {
        executeObjectSql(connect, "alter table " + tableName + " type(standard)", onSucceededUi);
    }

    public void truncateTable(Connect connect, String tableName, Runnable onSucceededUi) {
        executeObjectSql(connect, "truncate table " + tableName, onSucceededUi);
    }

    public void updateStatisticsForTable(Connect connect, String tableName, Runnable onSucceededUi){
        Task<List<String>> loadIndexColumnsTask = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
                    return metadataRepositoryProvider.metadata(connect).getIndexColumnsForTable(conn, tableName);
                }
            }
        };

        loadIndexColumnsTask.setOnSucceeded(event -> {
            List<String> indexColumns = loadIndexColumnsTask.getValue();
            String indexColumnsStr = String.join(",", indexColumns);
            String sql="update statistics for table " + tableName;
            if (!indexColumnsStr.isEmpty()) {
                sql = "update statistics high for table " + tableName + "(" + indexColumnsStr + ")";
            }
            executeObjectSql(connect, sql, onSucceededUi);
        });

        loadIndexColumnsTask.setOnFailed(event -> AppErrorHandler.handle(loadIndexColumnsTask.getException()));
        AppExecutor.runAsync(loadIndexColumnsTask);
    }

    public void refreshTableMeta(Connect connect,
                                 Database database,
                                 String tableName,
                                 Consumer<Table> onLoadedUi,
                                 Runnable onFinishedUi) {
        Task<Table> tableMetaTask = new Task<>() {
            @Override
            protected Table call() throws Exception {
                LOG.info("刷新表元数据: {} {} {}", connect.getName(), database.getName(), tableName);
                return getTable(connect, database, tableName);
            }
        };
        tableMetaTask.setOnSucceeded(event -> {
            Table table = tableMetaTask.getValue();
            if (table != null && table.getName() != null && onLoadedUi != null) {
                onLoadedUi.accept(table);
            }
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        tableMetaTask.setOnFailed(event -> {
            AppErrorHandler.handle(tableMetaTask.getException());
            if (onFinishedUi != null) {
                onFinishedUi.run();
            }
        });
        connect.executeSqlTask(tableMetaTask);
    }


    public void modifyTableFromUi(Connect connect, List<String> sqlList, Runnable onSucceededUi, Runnable onNotSucceededUi) {
            executeObjectSqls(connect, sqlList, onSucceededUi, onNotSucceededUi);

    }


}


