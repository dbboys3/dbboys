package com.dbboys.app;

import com.dbboys.i18n.I18n;
import com.dbboys.util.AlertUtil;
import com.dbboys.util.SqlErrorUtil;
import com.dbboys.util.tree.TreeViewUtil;
import com.dbboys.vo.Connect;
import com.dbboys.vo.TreeData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.List;

public final class AppErrorHandler {
    private static final Logger log = LogManager.getLogger(AppErrorHandler.class);
    private static final List<ErrorStrategy> STRATEGIES = List.of(
            new SqlDisconnectedStrategy(),
            new SqlDatabaseNotChoicedStrategy(),
            new SqlGenericStrategy(),
            new IoStrategy(),
            new DefaultStrategy()
    );

    private AppErrorHandler() {
    }

    public static void handle(Throwable e) {
        if (e == null) {
            return;
        }
        for (ErrorStrategy strategy : STRATEGIES) {
            if (strategy.supports(e)) {
                strategy.handle(e);
                return;
            }
        }
    }

    public static void bindTask(Task<?> task, Runnable onFinally) {
        if (task == null) {
            return;
        }
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            handle(ex);
            if (onFinally != null) {
                onFinally.run();
            }
        });
        task.setOnCancelled(event -> {
            if (onFinally != null) {
                onFinally.run();
            }
        });
    }

    public interface ErrorStrategy {
        boolean supports(Throwable e);

        void handle(Throwable e);
    }

    private static String formatExceptionDetails(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(e.getClass().getName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static void showSqlErrorAlert(SQLException e) {
        if (e == null) {
            return;
        }
        Platform.runLater(() -> AlertUtil.CustomAlert(
                I18n.bind("common.error", "错误").get(),
                buildSqlErrorMessage(e)
        ));
    }

    private static String buildSqlErrorMessage(SQLException e) {
        if (e == null) {
            return "";
        }
        return "[" + e.getErrorCode() + "]" + e.getMessage();
    }

    private static boolean hasActiveSelectedMetadataConnection() {
        TreeView<TreeData> treeView = AppState.getDatabaseMetaTreeView();
        if (treeView == null || treeView.getSelectionModel() == null) {
            return false;
        }
        TreeItem<TreeData> selectedItem = treeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || selectedItem.getValue() == null) {
            return false;
        }
        TreeItem<TreeData> connTreeItem = TreeViewUtil.getMetaConnTreeItem(selectedItem);
        if (connTreeItem == null || !(connTreeItem.getValue() instanceof Connect connect)) {
            return false;
        }
        return connect.getConn() != null;
    }

    static class SqlDisconnectedStrategy implements ErrorStrategy {
        @Override
        public boolean supports(Throwable e) {
            return e instanceof SQLException se && SqlErrorUtil.isDisconnectError(se);
        }

        @Override
        public void handle(Throwable e) {
            SQLException se = (SQLException) e;
            try {
                log.error("Operation failed. code={}, sqlState={}, message={}",
                        se.getErrorCode(), se.getSQLState(), se.getMessage(), e);
                if (!hasActiveSelectedMetadataConnection()) {
                    showSqlErrorAlert(se);
                    return;
                }
                TreeViewUtil.connectionDisconnected();
            } catch (Exception ex) {
                log.error("Connection disconnected. {}", formatExceptionDetails(ex));
                showSqlErrorAlert(se);
            }
        }
    }

    static class SqlDatabaseNotChoicedStrategy implements ErrorStrategy {
        @Override
        public boolean supports(Throwable e) {
            return e instanceof SQLException se && SqlErrorUtil.requiresSessionRecovery(se);
        }

        @Override
        public void handle(Throwable e) {
            log.error(e.getMessage(), e);

        }
    }

    static class SqlGenericStrategy implements ErrorStrategy {
        @Override
        public boolean supports(Throwable e) {
            return e instanceof SQLException;
        }

        @Override
        public void handle(Throwable e) {
            SQLException se = (SQLException) e;
            showSqlErrorAlert(se);
            log.error("SQL error. code={}, sqlState={}, message={}\n{}",
                    se.getErrorCode(), se.getSQLState(), se.getMessage(), formatExceptionDetails(e));
        }
    }

    static class IoStrategy implements ErrorStrategy {
        @Override
        public boolean supports(Throwable e) {
            return e instanceof IOException;
        }

        @Override
        public void handle(Throwable e) {
            log.error("IO error. {}", formatExceptionDetails(e));
        }
    }

    static class DefaultStrategy implements ErrorStrategy {
        @Override
        public boolean supports(Throwable e) {
            return true;
        }

        @Override
        public void handle(Throwable e) {
            log.error("Unhandled error. {}", formatExceptionDetails(e));
        }
    }
}
