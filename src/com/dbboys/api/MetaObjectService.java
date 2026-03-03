package com.dbboys.api;

import com.dbboys.i18n.I18n;
import com.dbboys.util.BackgroundSqlUtil;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.vo.BackgroundSqlTask;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.vo.ObjectList;
import com.dbboys.vo.UpdateResult;
import javafx.concurrent.Task;

import java.sql.Connection;
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
        String fetch(Connection conn, String objectName) throws Exception;
    }

    DdlFetcher ddlFetcher();

    ObjectList loadObjects(Connection conn, String databaseName) throws Exception;

    default ConnectionService connectionService() {
        return Holder.get();
    }

    default <T> T withMetaSession(Connect connect,
                                  Database database,
                                  ConnectionService.SqlWork<T> work) throws Exception {
        return connectionService().withMetaSession(connect, database, work);
    }

    default String getDDL(Connect connect, Database database, String objectName) throws Exception {
        return withMetaSession(connect, database, conn -> ddlFetcher().fetch(conn, objectName));
    }

    default ObjectList loadObjects(Connect connect, Database database) throws Exception {
        return withMetaSession(connect, database, conn -> loadObjects(conn, database.getName()));
    }

    default void renameObject(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

    default void deleteObject(Connect connect, String sql, Runnable onSucceededUi) {
        executeObjectSql(connect, sql, onSucceededUi);
    }

    default void executeObjectSql(Connect connect, String sql, Runnable onSucceededUi) {
        BackgroundSqlTask backSqlTask = new BackgroundSqlTask();
        Task<Void> bgTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                UpdateResult updateResult = new UpdateResult();
                backSqlTask.setConnect(connect);
                long beginTime = System.currentTimeMillis();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                try (Connection conn = connectionService().getGbaseModeConnection(connect)) {
                    if (conn == null) {
                        throw new Exception("ERROR");
                    }

                    updateResult.setConnectId(connect.getId());
                    updateResult.setDatabase(connect.getDatabase());
                    updateResult.setUpdateSql(sql);
                    updateResult.setStartTime(sdf.format(beginTime));

                    backSqlTask.setBeginTime(sdf.format(beginTime));
                    backSqlTask.setConnectName(connect.getName());
                    backSqlTask.setDatabaseName(connect.getDatabase());
                    backSqlTask.setSql(sql);
                    BackgroundSqlUtil.backSqlTaskList.add(backSqlTask);
                    BackgroundSqlUtil.updateBackSqlUIOnStart();

                    try (Statement stmt = conn.createStatement()) {
                        backSqlTask.setStmt(stmt);
                        int affectRows = stmt.executeUpdate(sql);
                        updateResult.setAffectedRows(affectRows);
                    }
                    long endtime = System.currentTimeMillis();
                    updateResult.setElapsedTime(String.format("%.3f", (endtime - beginTime) / 1000.0) + " sec");
                    updateResult.setEndTime(sdf.format(endtime));
                    updateResult.setMark(I18n.t("backsql.history.mark.ui_task", "界面操作任务,独立事务"));
                    LocalDbRepository.saveSqlHistory(updateResult);
                } catch (SQLException e) {
                    BackgroundSqlUtil.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } catch (Exception e) {
                    BackgroundSqlUtil.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } finally {
                    BackgroundSqlUtil.backSqlTaskList.remove(backSqlTask);
                    BackgroundSqlUtil.updateBackSqlUIOnFinish();
                }
                return null;
            }
        };
        bgTask.setOnSucceeded(event -> {
            if (onSucceededUi != null) {
                onSucceededUi.run();
            }
        });
        backSqlTask.setFuture(BackgroundSqlUtil.backSqlExecutor.submit(bgTask));
    }

    default void executeObjectSqls(Connect connect, List<String> sqlList, Runnable onSucceededUi) {
        executeObjectSqls(connect, sqlList, onSucceededUi, null);
    }

    default void executeObjectSqls(Connect connect, List<String> sqlList, Runnable onSucceededUi, Runnable onNotSucceededUi) {
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
                try (Connection conn = connectionService().getGbaseModeConnection(connect)) {
                    if (conn == null) {
                        throw new Exception("ERROR");
                    }
                    for (String sql : orderedSqlList) {
                        if (backSqlTask.isCancelled() || isCancelled() || Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        long beginTime = System.currentTimeMillis();
                        UpdateResult updateResult = new UpdateResult();
                        updateResult.setConnectId(connect.getId());
                        updateResult.setDatabase(connect.getDatabase());
                        updateResult.setUpdateSql(sql);
                        updateResult.setStartTime(sdf.format(beginTime));

                        backSqlTask.setBeginTime(sdf.format(beginTime));
                        backSqlTask.setConnectName(connect.getName());
                        backSqlTask.setDatabaseName(connect.getDatabase());
                        backSqlTask.setSql(sql);
                        BackgroundSqlUtil.backSqlTaskList.add(backSqlTask);
                        BackgroundSqlUtil.updateBackSqlUIOnStart();

                        try (Statement stmt = conn.createStatement()) {
                            backSqlTask.setStmt(stmt);
                            int affectRows = stmt.executeUpdate(sql);
                            updateResult.setAffectedRows(affectRows);
                        } finally {
                            backSqlTask.setStmt(null);
                            BackgroundSqlUtil.backSqlTaskList.remove(backSqlTask);
                            BackgroundSqlUtil.updateBackSqlUIOnFinish();
                        }

                        long endtime = System.currentTimeMillis();
                        updateResult.setElapsedTime(String.format("%.3f", (endtime - beginTime) / 1000.0) + " sec");
                        updateResult.setEndTime(sdf.format(endtime));
                        updateResult.setMark(I18n.t("backsql.history.mark.ui_task", "界面操作任务,独立事务"));
                        LocalDbRepository.saveSqlHistory(updateResult);
                    }
                } catch (SQLException e) {
                    BackgroundSqlUtil.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } catch (Exception e) {
                    BackgroundSqlUtil.handleBackgroundSqlError(backSqlTask, e);
                    throw new Exception("error");
                } finally {
                    BackgroundSqlUtil.backSqlTaskList.remove(backSqlTask);
                    BackgroundSqlUtil.updateBackSqlUIOnFinish();
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
        backSqlTask.setFuture(BackgroundSqlUtil.backSqlExecutor.submit(bgTask));
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
                            CONNECTION_SERVICE = new com.dbboys.impl.ConnectionServiceImpl();
                        }
                    }
                }
            }
            return CONNECTION_SERVICE;
        }
    }
}
