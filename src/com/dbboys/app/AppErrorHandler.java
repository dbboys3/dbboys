package com.dbboys.app;

import com.dbboys.i18n.I18n;
import com.dbboys.util.tree.TreeViewUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.application.Platform;
import javafx.concurrent.Task;

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

    static class SqlDisconnectedStrategy implements ErrorStrategy {
        @Override
        public boolean supports(Throwable e) {
            return e instanceof SQLException se && (se.getErrorCode() == -79716 || se.getErrorCode() == -79730);
        }

        @Override
        public void handle(Throwable e) {
            try {
                log.error("Operation failed", e);
                TreeViewUtil.connectionDisconnected();
            } catch (Exception ex) {
                log.error("Connection disconnected. {}", formatExceptionDetails(ex));
            }
        }
    }

    static class SqlDatabaseNotChoicedStrategy implements ErrorStrategy {
        @Override
        public boolean supports(Throwable e) {
            return e instanceof SQLException se && (se.getErrorCode() == -23197 || se.getErrorCode() == -349);
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
            Platform.runLater(()->{
            com.dbboys.util.AlertUtil.CustomAlert(I18n.bind("common.error", "错误").get(), "[" + se.getErrorCode() + "]" + e.getMessage());
            });
            log.error("SQL error. code={}, message={}\n{}", se.getErrorCode(), se.getMessage(), formatExceptionDetails(e));
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
