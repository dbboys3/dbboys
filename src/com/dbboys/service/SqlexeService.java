package com.dbboys.service;

import com.dbboys.ctrl.SqlTabController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.dbboys.api.ConnectionService;
import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.SqlModeCapability;
import com.dbboys.app.AppErrorHandler;
import com.dbboys.util.SqlErrorUtil;
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
    private final DatabasePlatformResolver platformResolver;

    public SqlexeService() {
        this(com.dbboys.app.AppContext.get(ConnectionService.class),
                com.dbboys.app.AppContext.get(DatabaseService.class),
                com.dbboys.app.AppContext.get(DatabasePlatformResolver.class));
    }

    public SqlexeService(ConnectionService connectionService, DatabaseService databaseService, DatabasePlatformResolver platformResolver) {
        this.connectionService = connectionService;
        this.databaseService = databaseService;
        this.platformResolver = platformResolver;
    }

    public List<String> getSqlMode(Connect connect, Connection conn) {
        var repo = platformResolver.sqlexe(connect);
        if (!(repo instanceof SqlModeCapability sqlModeCapability)) {
            return List.of();
        }
        try {
            return sqlModeCapability.getSqlModes(conn);
        } catch (SQLException e) {
            log.error("Operation failed", e);
            return new ArrayList<>();
        }
    }

    public ConnectionService.ChangeDefaultDatabaseResult activeDatabase(Connect connect,
                                                                       Database database,
                                                                       SqlTabController sqlTabController) {
        ConnectionService.ChangeDefaultDatabaseResult result =
                connectionService.changeSessionDatabase(connect, database, false);
        if (result.isSuccess()) {
            if (result.isReconnected()) {
                sqlTabController.closeResultSet();
            }
            return result;
        }
        if (result.isDisconnected()) {
            return result;
        }
        if (result.getErrorCode() != null) {
            AppErrorHandler.handle(new SQLException(result.getErrorMessage(), null, result.getErrorCode()));
        }
        return result;
    }

    public List<Database> getDatabases(Connect connect) {
        List<Database> catalogs = new ArrayList<>();
        try {
            catalogs = databaseService.getDatabases(connect);
        } catch (SQLException e) {
            if (SqlErrorUtil.isDisconnectError(connect, e)) {
                TreeViewUtil.connectionDisconnected();
            } else {
                AppErrorHandler.handle(e);
            }
        }
        return catalogs;
    }
}




