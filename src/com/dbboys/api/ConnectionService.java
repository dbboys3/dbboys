package com.dbboys.api;

import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;

import java.sql.Connection;
import java.sql.SQLException;

public interface ConnectionService {

    Connection createConnection(Connect connect) throws Exception;

    Connection getConnection(Connect connect) throws Exception;

    Connection getGbaseModeConnection(Connect connect) throws Exception;

    void changeCommitMode(Connection conn, int commitChoiceBoxIndex) throws SQLException;

    void sessionChangeToGbaseMode(Connection conn);

    ChangeDefaultDatabaseResult changeDefaultDatabase(Connect connect, Database database);

    String modifyProps(Connect connect, String DBlocale);

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
