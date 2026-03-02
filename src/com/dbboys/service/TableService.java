package com.dbboys.service;

import com.dbboys.app.AppExecutor;
import com.dbboys.impl.IMetaObjectService;
import com.dbboys.impl.IMetaObjectService.DdlFetcher;
import com.dbboys.db.MetadataRepository;
import com.dbboys.util.GlobalErrorHandlerUtil;
import com.dbboys.db.DDLRepository;
import com.dbboys.vo.*;
import javafx.concurrent.Task;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TableService implements IMetaObjectService {
    private final MetadataRepository metadataRepository;

    public TableService() {
        this(new MetadataRepository());
    }

    public TableService(MetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }
    public ObjectList loadObjects(Connection conn, String databaseName) throws SQLException {
        ObjectList objectList = new ObjectList();
        List<Table> result = new ArrayList<>();
        objectList.setItems(result);
        int count = metadataRepository.getUserTablesCount(conn);
        String size = metadataRepository.getUserTablesSize(conn, databaseName);
        String info = count + "个";
        if (size != null) {
            info = info + "/" + size;
        }
        objectList.setInfo(info);
        LOG.info("loadObjects: " + info );
        result.addAll(metadataRepository.getUserTables(conn, databaseName));
        return objectList;
    }


    @Override
    public DdlFetcher ddlFetcher() {
        return DDLRepository::printTable;
    }


    public ObjectList loadSystemTables(Connect connect, Database database) throws Exception {
        return withMetaSession(connect, database, conn -> buildSystemTables(conn, database.getName()));
    }

    private ObjectList buildSystemTables(Connection conn, String databaseName) throws SQLException {
        ObjectList objectList = new ObjectList();
        List<SysTable> result = new ArrayList<>();
        objectList.setItems(result);
        int count = metadataRepository.getSystemTablesCount(conn);
        String size = metadataRepository.getSystemTablesSize(conn, databaseName);
        objectList.setInfo(count + "个/" + size);
        result.addAll(metadataRepository.getSystemTables(conn, databaseName));
        return objectList;
    }
    public ArrayList<ColumnsInfo> getColumns(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> (ArrayList<ColumnsInfo>)DDLRepository.getColInfo(conn, objectName));
    }

    public Table getTable(Connect connect, Database database,String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepository.getTable(conn, database.getName(), objectName));
    }

    public String getTableComment(Connect connect, Database database, String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> metadataRepository.getTableComment(conn, objectName));
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
                try (Connection conn = connectionService().getGbaseModeConnection(connect)) {
                    return metadataRepository.getIndexColumnsForTable(conn, tableName);
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

        loadIndexColumnsTask.setOnFailed(event -> GlobalErrorHandlerUtil.handle(loadIndexColumnsTask.getException()));
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
            GlobalErrorHandlerUtil.handle(tableMetaTask.getException());
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



