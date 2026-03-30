package com.dbboys.vo;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Future;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class BackgroundSqlTask {
    private String id;
    private String beginTime;
    private String connectName;
    private String databaseName;
    private String sql;
    private final StringProperty progress = new SimpleStringProperty("");
    private String operate;
    private Connect connect;
    private Connection connection;
    private Statement stmt;
    private Future<?> future;
    private Runnable cancelAction;
    private volatile boolean cancelled;
    public BackgroundSqlTask(){
        id= UUID.randomUUID().toString();
    }
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBeginTime() {
        return beginTime;
    }

    public void setBeginTime(String beginTime) {
        this.beginTime = beginTime;
    }

    public String getConnectName() {
        return connectName;
    }

    public void setConnectName(String connectName) {
        this.connectName = connectName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public Connect getConnect() {
        return connect;
    }

    public void setConnect(Connect connect) {
        this.connect = connect;
    }



    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getOperate() {
        return operate;
    }

    public void setOperate(String operate) {
        this.operate = operate;
    }

    public String getProgress() {
        return progress.get();
    }

    public void setProgress(String progress) {
        this.progress.set(progress == null ? "" : progress);
    }

    public StringProperty progressProperty() {
        return progress;
    }

    public Statement getStmt() {
        return stmt;
    }

    public void setStmt(Statement stmt) {
        this.stmt = stmt;
    }

    public Connection getConnection() {
        return connection;
    }

    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    public Future<?> getFuture() {
        return future;
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public void setCancelAction(Runnable cancelAction) {
        this.cancelAction = cancelAction;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        if (future != null) {
            future.cancel(true);
        }
        if (cancelAction != null) {
            try {
                cancelAction.run();
            } catch (Exception e) {
                // ignore
            }
        }
        if (stmt != null) {
            try {
                stmt.cancel();
            } catch (Exception e) {
                // ignore
            }
        }
        if (connection != null) {
            try {
                connection.abort(Runnable::run);
            } catch (Exception e) {
                // ignore
            }
            try {
                connection.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }
}

