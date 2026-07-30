package com.dbboys.core;

import com.dbboys.infra.i18n.I18n;
import com.dbboys.service.BackgroundSqlService;
import com.dbboys.ui.util.ReadOnlyGuard;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.model.BackgroundSqlTask;
import com.dbboys.model.CatalogNode;
import com.dbboys.model.Connect;
import com.dbboys.model.ObjectList;
import com.dbboys.model.UpdateResult;
import javafx.concurrent.Task;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public interface MetaObjectService {
    Logger LOG = LogManager.getLogger(MetaObjectService.class);

    @FunctionalInterface
    interface DdlFetcher {
        String fetch(Connect connect, Connection conn, String objectName) throws Exception;
    }

    DdlFetcher ddlFetcher();

    ObjectList loadObjects(Connect connect, Connection conn, String databaseName) throws Exception;

    default ConnectionService connectionService() {
        return Holder.get();
    }

    default <T> T withMetaSession(Connect connect,
                                  CatalogNode database,
                                  ConnectionService.SqlWork<T> work) throws Exception {
        return connectionService().withMetaSession(connect, database, work);
    }

    default String getDDL(Connect connect, CatalogNode database, String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> ddlFetcher().fetch(connect, conn, objectName));
    }

    default ObjectList loadObjects(Connect connect, CatalogNode database) throws Exception {
        return withMetaSession(connect, database, conn -> loadObjects(connect, conn, database.getName()));
    }

    default void renameObject(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

    default void deleteObject(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

    default void executeObjectSql(Connect connect, String sql, Runnable onSucceededUi) {
        if (ReadOnlyGuard.showBlockedAlertIfReadOnly(connect)) {
            return;
        }
        BackgroundSqlTask backSqlTask = new BackgroundSqlTask();
        Task<Void> bgTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                UpdateResult updateResult = new UpdateResult();
                backSqlTask.setConnect(connect);
                long beginTime = System.currentTimeMillis();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
                    if (conn == null) {
                        throw new Exception("ERROR");
                    }

                    String effectiveDb = connect.getEffectiveCatalog();
                    updateResult.setConnectId(connect.getId());
                    updateResult.setDatabase(effectiveDb);
                    updateResult.setUpdateSql(sql);
                    updateResult.setStartTime(sdf.format(beginTime));

                    backSqlTask.setBeginTime(sdf.format(beginTime));
                    backSqlTask.setConnectName(connect.getName());
                    backSqlTask.setDatabaseName(effectiveDb);
                    backSqlTask.setSql(sql);
                    BackgroundSqlService.backSqlTaskList.add(backSqlTask);
                    BackgroundSqlService.updateBackSqlUIOnStart();

                    String execSql = stripTrailingSemicolon(sql);
                    try (Statement stmt = conn.createStatement()) {
                        backSqlTask.setStmt(stmt);
                        int affectRows = executeMaintenanceSql(stmt, execSql);
                        updateResult.setAffectedRows(affectRows);
                    }
                    long endtime = System.currentTimeMillis();
                    updateResult.setElapsedTime(String.format("%.3f", (endtime - beginTime) / 1000.0) + " sec");
                    updateResult.setEndTime(sdf.format(endtime));
                    updateResult.setMark(I18n.t("backsql.history.mark.ui_task", "界面操作任务,独立事务"));
                    LocalDbRepository.saveSqlHistory(updateResult);
                } catch (SQLException e) {
                    BackgroundSqlService.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } catch (Exception e) {
                    BackgroundSqlService.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } finally {
                    BackgroundSqlService.backSqlTaskList.remove(backSqlTask);
                    BackgroundSqlService.updateBackSqlUIOnFinish();
                }
                return null;
            }
        };
        bgTask.setOnSucceeded(event -> {
            if (onSucceededUi != null) {
                onSucceededUi.run();
            }
        });
        backSqlTask.setFuture(BackgroundSqlService.backSqlExecutor.submit(bgTask));
    }

    default void executeObjectSqls(Connect connect, List<String> sqlList, Runnable onSucceededUi) {
        executeObjectSqls(connect, sqlList, onSucceededUi, null);
    }

    default void executeObjectSqls(Connect connect, List<String> sqlList, Runnable onSucceededUi, Runnable onNotSucceededUi) {
        if (ReadOnlyGuard.showBlockedAlertIfReadOnly(connect)) {
            if (onNotSucceededUi != null) {
                onNotSucceededUi.run();
            }
            return;
        }
        if (sqlList == null || sqlList.isEmpty()) {
            return;
        }
        List<String> orderedSqlList = new ArrayList<>();
        for (String sql : sqlList) {
            if (sql == null) {
                continue;
            }
            String trimmed = sql.trim();
            if (!trimmed.isEmpty()) {
                orderedSqlList.add(trimmed);
            }
        }
        if (orderedSqlList.isEmpty()) {
            return;
        }

        BackgroundSqlTask backSqlTask = new BackgroundSqlTask();
        Task<Void> bgTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                backSqlTask.setConnect(connect);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                try (Connection conn = connectionService().getConnectionWithSessionInit(connect)) {
                    if (conn == null) {
                        throw new Exception("ERROR");
                    }
                    for (String sql : orderedSqlList) {
                        if (backSqlTask.isCancelled() || isCancelled() || Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        String execSql = stripTrailingSemicolon(sql);
                        long beginTime = System.currentTimeMillis();
                        UpdateResult updateResult = new UpdateResult();
                        String effectiveDb2 = connect.getEffectiveCatalog();
                        updateResult.setConnectId(connect.getId());
                        updateResult.setDatabase(effectiveDb2);
                        updateResult.setUpdateSql(execSql);
                        updateResult.setStartTime(sdf.format(beginTime));

                        backSqlTask.setBeginTime(sdf.format(beginTime));
                        backSqlTask.setConnectName(connect.getName());
                        backSqlTask.setDatabaseName(effectiveDb2);
                        backSqlTask.setSql(execSql);
                        BackgroundSqlService.backSqlTaskList.add(backSqlTask);
                        BackgroundSqlService.updateBackSqlUIOnStart();

                        try (Statement stmt = conn.createStatement()) {
                            backSqlTask.setStmt(stmt);
                            int affectRows = executeMaintenanceSql(stmt, execSql);
                            updateResult.setAffectedRows(affectRows);
                        } finally {
                            backSqlTask.setStmt(null);
                            BackgroundSqlService.backSqlTaskList.remove(backSqlTask);
                            BackgroundSqlService.updateBackSqlUIOnFinish();
                        }

                        long endtime = System.currentTimeMillis();
                        updateResult.setElapsedTime(String.format("%.3f", (endtime - beginTime) / 1000.0) + " sec");
                        updateResult.setEndTime(sdf.format(endtime));
                        updateResult.setMark(I18n.t("backsql.history.mark.ui_task", "界面操作任务,独立事务"));
                        LocalDbRepository.saveSqlHistory(updateResult);
                    }
                } catch (SQLException e) {
                    BackgroundSqlService.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } catch (Exception e) {
                    BackgroundSqlService.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } finally {
                    BackgroundSqlService.backSqlTaskList.remove(backSqlTask);
                    BackgroundSqlService.updateBackSqlUIOnFinish();
                }
                return null;
            }
        };
        bgTask.setOnSucceeded(event -> {
            if (onSucceededUi != null) {
                onSucceededUi.run();
            }
        });
        bgTask.setOnFailed(event -> {
            if (onNotSucceededUi != null) {
                onNotSucceededUi.run();
            }
        });
        bgTask.setOnCancelled(event -> {
            if (onNotSucceededUi != null) {
                onNotSucceededUi.run();
            }
        });
        backSqlTask.setFuture(BackgroundSqlService.backSqlExecutor.submit(bgTask));
    }

    private static int executeMaintenanceSql(Statement stmt, String execSql) throws SQLException {
        boolean hasResultSet = stmt.execute(execSql);
        int affectedRows = stmt.getUpdateCount();
        int lastAffectedRows = affectedRows >= 0 ? affectedRows : 0;
        while (hasResultSet || affectedRows != -1) {
            if (hasResultSet) {
                try (ResultSet ignored = stmt.getResultSet()) {
                    // Some maintenance statements, such as MySQL ANALYZE TABLE, return a result set.
                }
            }
            hasResultSet = stmt.getMoreResults();
            affectedRows = stmt.getUpdateCount();
            if (affectedRows >= 0) {
                lastAffectedRows = affectedRows;
            }
        }
        return lastAffectedRows;
    }

    private static String stripTrailingSemicolon(String sql) {
        if (sql == null) return sql;
        String trimmed = sql.stripTrailing();
        String upper = trimmed.stripLeading().toUpperCase();
        if (upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) {
            return trimmed;
        }
        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    final class Holder {
        private static volatile ConnectionService CONNECTION_SERVICE;

        private Holder() {
        }

        static ConnectionService get() {
            if (CONNECTION_SERVICE == null) {
                synchronized (Holder.class) {
                    if (CONNECTION_SERVICE == null) {
                        try {
                            CONNECTION_SERVICE = com.dbboys.app.AppContext.get(ConnectionService.class);
                        } catch (IllegalStateException e) {
                            CONNECTION_SERVICE = new com.dbboys.core.ConnectionServiceImpl();
                        }
                    }
                }
            }
            return CONNECTION_SERVICE;
        }
    }
}
