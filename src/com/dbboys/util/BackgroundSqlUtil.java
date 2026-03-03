package com.dbboys.util;

import com.dbboys.app.AppErrorHandler;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.dbboys.app.AppContext;
import com.dbboys.app.AppState;
import com.dbboys.i18n.I18n;
import com.dbboys.api.ConnectionService;
import com.dbboys.impl.ConnectionServiceImpl;
import com.dbboys.vo.BackgroundSqlTask;

import javafx.application.Platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
public class BackgroundSqlUtil {
    private static final Logger log = LogManager.getLogger(BackgroundSqlUtil.class);

    public static List<BackgroundSqlTask> backSqlTaskList = Collections.synchronizedList(new ArrayList<>());
    private static volatile ConnectionService connectionServiceInstance;
    public final static ExecutorService backSqlExecutor = Executors.newCachedThreadPool();

    public static ConnectionService getConnectionService() {
        if (connectionServiceInstance == null) {
            try {
                connectionServiceInstance = AppContext.get(ConnectionService.class);
            } catch (IllegalStateException e) {
                connectionServiceInstance = new ConnectionServiceImpl();
            }
        }
        return connectionServiceInstance;
    }

    /** @deprecated Use {@link #getConnectionService()} instead */
    @Deprecated
    public static final ConnectionService connectionService = new ConnectionServiceImpl();
    public static void updateBackSqlUIOnStart() {
        Platform.runLater(() -> {
            AppState.getStatusBackSqlStopButton().setDisable(false);
            setBackSqlCountLabelText(
                    I18n.t("backsql.status.running_count", "有%d个后台任务正在运行").formatted(backSqlTaskList.size())
            );
            AppState.getStatusBackSqlCountLabel().setStyle("-fx-font-size: 7;-fx-text-fill: black");
            PopupWindowUtil.sqlTaskTableView.getItems().clear();
            PopupWindowUtil.sqlTaskTableView.getItems().addAll(new ArrayList<>(backSqlTaskList));
        });
    }
    private static void setBackSqlCountLabelText(String text) {
        if (AppState.getMainController() == null || AppState.getStatusBackSqlCountLabel() == null) {
            return;
        }
        if (AppState.getStatusBackSqlCountLabel().textProperty().isBound()) {
            AppState.getStatusBackSqlCountLabel().textProperty().unbind();
        }
        AppState.getStatusBackSqlCountLabel().setText(text);
    }

        public static void handleBackgroundSqlError(BackgroundSqlTask backSqlTask, Exception e) {
        if (backSqlTask != null && backSqlTask.isCancelled()) {
            return;
        }
        Platform.runLater(() -> {
            if (e instanceof SQLException se) {
                if (se.getErrorCode() != -213) {
                    AlertUtil.CustomAlert(
                            I18n.t("backsql.error.title", "后台任务错误"),
                            I18n.t(
                                    "backsql.error.content",
                                    "连接名称: %s/%s\n执行任务: %s\n错误信息: %s %s"
                            ).formatted(
                                    backSqlTask.getConnect().getName(),
                                    backSqlTask.getConnect().getDatabase(),
                                    backSqlTask.getSql(),
                                    se.getErrorCode(),
                                    se.getMessage()
                            )
                    );
                }
            } else {
                AppErrorHandler.handle(e);
            }
        });
    }

    public static void updateBackSqlUIOnFinish() {
        Platform.runLater(() -> {
            if (backSqlTaskList.size() == 0) {
                AppState.getStatusBackSqlStopButton().setDisable(true);
                bindBackSqlCountLabel(
                        "backsql.status.none_running",
                        "没有正在运行的后台任务"
                );
                AppState.getStatusBackSqlCountLabel().setStyle("-fx-font-size: 7;-fx-text-fill: #888");
            } else {
                setBackSqlCountLabelText(
                        I18n.t("backsql.status.running_count_short", "有%d个正在运行的后台任务").formatted(backSqlTaskList.size())
                );
            }
            PopupWindowUtil.sqlTaskTableView.getItems().clear();
            PopupWindowUtil.sqlTaskTableView.getItems().addAll(new ArrayList<>(backSqlTaskList));
        });
    }

    private static void bindBackSqlCountLabel(String key, String fallback) {
        if (AppState.getMainController() == null || AppState.getStatusBackSqlCountLabel() == null) {
            return;
        }
        if (AppState.getStatusBackSqlCountLabel().textProperty().isBound()) {
            AppState.getStatusBackSqlCountLabel().textProperty().unbind();
        }
        AppState.getStatusBackSqlCountLabel().textProperty().bind(I18n.bind(key, fallback));
    }
}


