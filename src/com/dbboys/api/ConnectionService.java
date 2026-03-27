package com.dbboys.api;

import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;

import java.sql.Connection;
import java.sql.SQLException;

public interface ConnectionService {

    /**
     * 仅创建 JDBC 连接，不做方言会话初始化（如 GBase sqlmode）。需要会话初始化时请用 {@link #getConnectionWithSessionInit}。
     */
    Connection createConnection(Connect connect) throws Exception;

    /**
     * 建连并在方言支持时做会话初始化（如 GBase sqlmode）；与具体数据库品牌无关。
     */
    Connection getConnectionWithSessionInit(Connect connect) throws Exception;

    void changeCommitMode(Connection conn, int commitChoiceBoxIndex) throws SQLException;

    ChangeDefaultDatabaseResult changeDefaultDatabase(Connect connect, Database database);

    /**
     * 按属性名和值调整连接属性 JSON；方言可在写入前对特定属性做归一化处理（如 GBase 的 DB_LOCALE）。
     */
    String modifyProps(Connect connect, String propName, String propValue);

    String setConnectInfo(Connect connect) throws Exception;

    Boolean testConn(Connect connect);

    <T> T withMetaSession(Connect connect, Database database, SqlWork<T> action) throws Exception;

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
