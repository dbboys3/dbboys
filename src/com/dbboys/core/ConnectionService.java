package com.dbboys.core;

import com.dbboys.model.Connect;
import com.dbboys.model.Catalog;

import java.sql.Connection;
import java.sql.SQLException;

public interface ConnectionService {

    /**
     * 仅创建 JDBC 连接，不做方言会话初始化。需要会话初始化时请用 {@link #getConnectionWithSessionInit}。
     */
    Connection createConnection(Connect connect) throws Exception;

    /**
     * 建连并在方言支持时做会话初始化；与具体数据库品牌无关。
     */
    Connection getConnectionWithSessionInit(Connect connect) throws Exception;

    void changeCommitMode(Connection conn, int commitChoiceBoxIndex) throws SQLException;

    default ChangeDefaultDatabaseResult changeDefaultDatabase(Connect connect, Catalog database) {
        return changeDefaultDatabase(connect, database, true);
    }

    /**
     * 切换当前库；当方言要求通过新连接重试时，可选择是否执行 sessionInit。
     */
    ChangeDefaultDatabaseResult changeDefaultDatabase(Connect connect, Catalog database, boolean sessionInitOnReconnect);

    default ChangeDefaultDatabaseResult changeSessionDatabase(Connect connect, Catalog database) {
        return changeSessionDatabase(connect, database, true);
    }

    /**
     * 仅切换当前会话的库，不持久化修改连接默认库。
     */
    ChangeDefaultDatabaseResult changeSessionDatabase(Connect connect, Catalog database, boolean sessionInitOnReconnect);

    /**
     * 按属性名和值调整连接属性 JSON；方言可在写入前对特定属性做归一化处理。
     */
    String modifyProps(Connect connect, String propName, String propValue);

    String setConnectInfo(Connect connect) throws Exception;

    String refreshRuntimeConnectInfo(Connect connect) throws Exception;

    Boolean testConn(Connect connect);

    <T> T withMetaSession(Connect connect, Catalog database, SqlWork<T> action) throws Exception;

    /** Disconnect and remove a cached SSH session so the next connection re-authenticates. */
    void invalidateSshSession(Connect connect);

    @FunctionalInterface
    interface SqlWork<T> {
        T apply(Connection conn) throws Exception;
    }

    class ChangeDefaultDatabaseResult {
        private boolean success;
        private boolean disconnected;
        private boolean reconnected;
        private Integer errorCode;
        private String errorMessage;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public boolean isDisconnected() {
            return disconnected;
        }

        public void setDisconnected(boolean disconnected) {
            this.disconnected = disconnected;
        }

        public boolean isReconnected() {
            return reconnected;
        }

        public void setReconnected(boolean reconnected) {
            this.reconnected = reconnected;
        }

        public Integer getErrorCode() {
            return errorCode;
        }

        public void setErrorCode(Integer errorCode) {
            this.errorCode = errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
