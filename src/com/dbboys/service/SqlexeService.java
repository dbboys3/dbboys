package com.dbboys.service;

import com.dbboys.ctrl.SqlTabController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.api.ConnectionService;
import com.dbboys.api.SqlexeRepositoryProvider;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.vo.Connect;
import com.dbboys.vo.Database;
import com.dbboys.util.tree.TreeViewUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SqlexeService {
    private static final Logger log = LogManager.getLogger(SqlexeService.class);
    private final ConnectionService connectionService;
    private final DatabaseService databaseService;
    private final SqlexeRepositoryProvider sqlexeRepositoryProvider;

    public SqlexeService() {
        this(com.dbboys.app.AppContext.get(ConnectionService.class), com.dbboys.app.AppContext.get(DatabaseService.class), com.dbboys.app.AppContext.get(SqlexeRepositoryProvider.class));
    }

    public SqlexeService(ConnectionService connectionService, DatabaseService databaseService, SqlexeRepositoryProvider sqlexeRepositoryProvider) {
        this.connectionService = connectionService;
        this.databaseService = databaseService;
        this.sqlexeRepositoryProvider = sqlexeRepositoryProvider;
    }

    public List<String> getSqlMode(Connect connect, Connection conn) {
        try {
            return sqlexeRepositoryProvider.sqlexe(connect).getSqlMode(conn);
        } catch (SQLException e) {
            log.error("Operation failed", e);
            return new ArrayList<>();
        }
    }

    public String activeDatabase(Connect connect, Database database, SqlTabController sqlTabController) {
        ConnectionService.ChangeDefaultDatabaseResult result = connectionService.changeDefaultDatabase(connect, database);
        if (result.isSuccess()) {
            if (result.isReconnected()) {
                sqlTabController.closeResultSet();
            }
            return "success";
        }
        if (result.isDisconnected()) {
            return "disconnected";
        }
        if (result.getErrorCode() != null) {
            AppErrorHandler.handle(new SQLException(result.getErrorMessage(), null, result.getErrorCode()));
        }
        return null;
    }

    public List<Database> getDatabases(Connect connect) {
        List<Database> catalogs = new ArrayList<>();
        try {
            catalogs = databaseService.getDatabases(connect, false);
        } catch (SQLException e) {
            if (e.getErrorCode() == -201) {
                try {
                    catalogs = databaseService.getDatabases(connect, true);
                } catch (Exception ex) {
                    log.error("Operation failed", ex);
                }
            } else if (e.getErrorCode() == -79716 || e.getErrorCode() == -79730) {
                TreeViewUtil.connectionDisconnected();
            } else {
                AppErrorHandler.handle(e);
            }
        }
        return catalogs;
    }
}




