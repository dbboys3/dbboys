package com.dbboys.ui.dialog;

import com.dbboys.app.AppErrorHandler;

import com.dbboys.app.AppExecutor;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;
import com.dbboys.app.AppState;
import com.dbboys.ui.component.CustomUserTextField;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.core.ConnectionService;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import com.dbboys.model.Connect;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public class DownloadManager {
    private static final Logger log = LogManager.getLogger(DownloadManager.class);
    private static final String CSV_BINARY_PREFIX = "base64:";

    private Task<Void> task;
    private final Object source;
    private String downloadUrl;
    private final File file;
    private final File tempFile; // 临时文件
    private TableView tableView;
    private ResultSetMetaData metaData;
    private ResultSet streamingResultSet;
    private String streamingFormat;
    private long totalRows = -1;
    private SqlExportManager.SqlExportSource sqlExportSource;
    private ExecutorService taskExecutor;
    private SqlExportManager.CustomExportSource customExportSource;


    private final Node rootPane; // StackPane 鐨勫瓙鑺傜偣
    private final ProgressBar progressBar;
    private final Label nameLabel;
    private final Label progressLabel;
    private final Label speedLabel;
    private final Button pauseButton;
    private final Button resumeButton;
    private final Button stopButton;

    private volatile boolean cancelled = false;
    private volatile boolean paused = false;
    private volatile long downloadedBytes = 0;
    private long totalBytes = 0;

    private final boolean autoCloseOnComplete;
    private final StackPane hostStackPane;
    private final boolean installerMode;
    private final CustomUserTextField installerRemotePathField;
    private final CustomUserTextField installerInstallFilePathField;

    public DownloadManager(
            Object source,
            File file,
            boolean autoCloseOnComplete,
            ResultSetMetaData metaData,
            StackPane hostStackPane,
            boolean installerMode,
            CustomUserTextField installerRemotePathField,
            CustomUserTextField installerInstallFilePathField
    ) {
        this.source = source;
        this.file = file;
        this.metaData = metaData;
        this.tempFile = new File(file.getAbsolutePath() + ".download"); // 临时文件
        this.autoCloseOnComplete = autoCloseOnComplete;
        this.hostStackPane = hostStackPane;
        this.installerMode = installerMode;
        this.installerRemotePathField = installerRemotePathField;
        this.installerInstallFilePathField = installerInstallFilePathField;
        if (source instanceof SqlExportManager.ResultSetExportSource) {
            SqlExportManager.ResultSetExportSource s = (SqlExportManager.ResultSetExportSource) source;
            this.streamingResultSet = s.resultSet;
            this.streamingFormat = s.format;
            this.metaData = s.metaData;
            this.totalRows = s.totalRows;
            this.taskExecutor = s.executor;
        } else if (source instanceof SqlExportManager.SqlExportSource s) {
            this.sqlExportSource = s;
            this.streamingFormat = s.format;
            this.taskExecutor = s.executor;
        } else if (source instanceof SqlExportManager.CustomExportSource s) {
            this.customExportSource = s;
        }

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(100);
        nameLabel = new Label();
        nameLabel.setTranslateY(-0.5);
        progressLabel = new Label("0%");
        progressLabel.setMinWidth(20);
        progressLabel.setTranslateY(-0.5);
        speedLabel = new Label();
        speedLabel.setTranslateY(-0.5);
        nameLabel.getStyleClass().add("download");
        progressLabel.getStyleClass().add("download");
        speedLabel.getStyleClass().add("download");

        pauseButton = new Button("");
        Tooltip pauseTooltip = new Tooltip();
        pauseTooltip.textProperty().bind(I18n.bind("download.tooltip.pause", "暂停下载"));
        pauseButton.setTooltip(pauseTooltip);
        resumeButton = new Button("");
        Tooltip resumeTooltip = new Tooltip();
        resumeTooltip.textProperty().bind(I18n.bind("download.tooltip.resume", "恢复下载"));
        resumeButton.setTooltip(resumeTooltip);
        stopButton = new Button("");
        Tooltip stopTooltip = new Tooltip();
        stopTooltip.textProperty().bind(I18n.bind("download.tooltip.cancel", "取消下载并删除未完成文件"));
        stopButton.setTooltip(stopTooltip);
        StackPane pauseStackPane = new StackPane();
        pauseStackPane.getChildren().addAll(pauseButton, resumeButton);

        HBox buttonBox = new HBox(5, pauseStackPane, stopButton);
        buttonBox.setAlignment(Pos.CENTER);

        pauseButton.setOnAction(e -> {
            pauseButton.setVisible(!pauseButton.isVisible());
            pauseDownload();
        });
        resumeButton.visibleProperty().bind(pauseButton.visibleProperty().not());
        resumeButton.setOnAction(e -> {
            pauseButton.setVisible(!pauseButton.isVisible());
            resumeDownload();
        });

        resumeButton.setGraphic(IconFactory.group(IconPaths.DOWNLOAD_RESUME, 0.5));
        resumeButton.getStyleClass().add("small");
        resumeButton.setFocusTraversable(false);

        pauseButton.setGraphic(IconFactory.group(IconPaths.DOWNLOAD_PAUSE, 0.6));
        pauseButton.getStyleClass().add("small");
        pauseButton.setFocusTraversable(false);

        stopButton.setGraphic(IconFactory.groupFixedColor(IconPaths.SQL_STOP, 0.5, IconFactory.stopColor()));
        stopButton.getStyleClass().add("small");
        stopButton.setFocusTraversable(false);
        stopButton.setOnAction(e -> cancelDownload());
        if (source instanceof String) {
            this.downloadUrl = (String) source;
            if (installerMode) {
                HBox topLine = new HBox(6, progressBar, progressLabel, buttonBox);
                topLine.setAlignment(Pos.CENTER_LEFT);
                HBox textLine = new HBox(6, nameLabel, speedLabel);
                textLine.setAlignment(Pos.CENTER_LEFT);
                rootPane = new VBox(2, topLine, textLine);
            } else {
                HBox line = new HBox(6, nameLabel, speedLabel, progressBar, progressLabel, buttonBox);
                line.setAlignment(Pos.CENTER_RIGHT);
                rootPane = line;
            }
            nameLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> (pauseButton.isVisible()
                            ? I18n.t("download.label.downloading_prefix", "正在下载：")
                            : I18n.t("download.label.paused_prefix", "已暂停下载：")) + file.getName(),
                    I18n.localeProperty(),
                    pauseButton.visibleProperty()
            ));
            speedLabel.textProperty().bind(I18n.bind("download.label.waiting", "等待开始..."));

        } else if (source instanceof SqlExportManager.ResultSetExportSource) {
            HBox line = new HBox(6, nameLabel, speedLabel, progressBar, progressLabel, stopButton);
            line.setAlignment(Pos.CENTER_RIGHT);
            rootPane = line;
            nameLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> I18n.t("download.label.exporting_prefix", "正在导出：") + file.getName(),
                    I18n.localeProperty()
            ));
        } else if (source instanceof SqlExportManager.SqlExportSource) {
            HBox line = new HBox(6, nameLabel, speedLabel, progressBar, progressLabel, stopButton);
            line.setAlignment(Pos.CENTER_RIGHT);
            rootPane = line;
            nameLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> I18n.t("download.label.exporting_prefix", "正在导出：") + file.getName(),
                    I18n.localeProperty()
            ));
        } else if (source instanceof SqlExportManager.CustomExportSource) {
            HBox line = new HBox(6, nameLabel, speedLabel, progressBar, progressLabel, stopButton);
            line.setAlignment(Pos.CENTER_RIGHT);
            rootPane = line;
            nameLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> I18n.t("download.label.exporting_prefix", "正在导出：") + customExportSource.displayName,
                    I18n.localeProperty()
            ));
        } else {
            this.tableView = (TableView) source;
            HBox line = new HBox(6, nameLabel, progressBar, progressLabel, stopButton);
            line.setAlignment(Pos.CENTER_RIGHT);
            rootPane = line;
            nameLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> I18n.t("download.label.exporting_prefix", "正在导出：") + file.getName(),
                    I18n.localeProperty()
            ));

        }
        if (installerMode) {
            StackPane.setAlignment(rootPane, Pos.CENTER_LEFT);
        }

    }

    public Node getRootPane() {
        return rootPane;
    }

    public void start() {
        startNewTask(false);
    }

    private synchronized void startNewTask(boolean isResume) {
        if (task != null && task.isRunning()) return;

        cancelled = false;
        if (source instanceof String) {
            task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    updateProgress(0, 1);
                    InputStream in = null;
                    RandomAccessFile out = null;
                    try {
                        long start = downloadedBytes;
                        if (downloadUrl.toLowerCase().startsWith("http")) {
                            HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                            conn.setRequestProperty("User-Agent", "JavaFX Downloader");
                            if (isResume && start > 0) conn.setRequestProperty("Range", "bytes=" + start + "-");
                            conn.connect();

                            int code = conn.getResponseCode();
                            if (code != 200 && code != 206) throw new IOException(I18n.t("download.error.connection_failed", "连接失败: HTTP ") + code);
                            if (totalBytes == 0) totalBytes = conn.getContentLengthLong() + start;
                            in = conn.getInputStream();
                        } else {
                            Path src = Paths.get(downloadUrl);
                            if (totalBytes == 0) totalBytes = Files.size(src);
                            in = Files.newInputStream(src);
                            if (start > 0) in.skip(start);
                        }

                        out = new RandomAccessFile(tempFile, "rw");
                        out.seek(start);

                        byte[] buffer = new byte[8192];
                        int len;
                        long lastUpdate = System.currentTimeMillis();
                        long lastRead = downloadedBytes;
                        double smoothedSpeed = 0, alpha = 0.3;

                        while (!cancelled && (len = in.read(buffer)) != -1) {
                            while (paused) {
                                Thread.sleep(200);
                                if (cancelled) break;
                            }
                            if (cancelled) break;

                            out.write(buffer, 0, len);
                            downloadedBytes += len;
                            updateProgress(downloadedBytes, totalBytes);

                            long now = System.currentTimeMillis();
                            if (now - lastUpdate >= 1000) {
                                long delta = downloadedBytes - lastRead;
                                double currentSpeed = delta / ((now - lastUpdate) / 1000.0);
                                smoothedSpeed = alpha * currentSpeed + (1 - alpha) * smoothedSpeed;

                                String speedText = smoothedSpeed >= 1024 * 1024 ?
                                        String.format("%.2f MB/s", smoothedSpeed / 1024 / 1024) :
                                        String.format("%.2f KB/s", smoothedSpeed / 1024);

                                updateMessage(String.format(
                                        I18n.t("download.message.progress", "已下载: %.2f / %.2f MB  速度: %s"),
                                        downloadedBytes / 1024.0 / 1024.0,
                                        totalBytes / 1024.0 / 1024.0,
                                        speedText
                                ));
                                lastUpdate = now;
                                lastRead = downloadedBytes;
                            }
                        }

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (in != null) in.close();
                        if (out != null) out.close();
                        if (cancelled) {
                            updateMessage(I18n.t("download.message.stopped_deleted", "下载已停止并删除文件"));
                        } else if (paused) {
                            updateMessage(I18n.t("download.message.paused", "已暂停"));
                        } else {
                            updateMessage(I18n.t("download.message.completed", "下载完成"));
                            updateProgress(1, 1);
                            boolean moved = true;
                            if (tempFile.exists()) {
                                moved = moveTempToTargetWithRetry();
                                if (!moved) {
                                    updateMessage(I18n.t("download.message.rename_failed", "下载完成，但重命名失败"));
                                }
                            }
                            boolean finalMoved = moved;
                            Platform.runLater(() -> {
                                if (!finalMoved) {
                                    AlertUtil.CustomAlert(
                                            I18n.t("download.error.title", "下载失败"),
                                            I18n.t("download.message.rename_failed", "下载完成，但重命名失败")
                                    );
                                    return;
                                }
                                if (autoCloseOnComplete) stackPaneRemoveSelf();
                                if(file.getName().contains("dbboys.upgrade.")){
                                    AppState.checkVersion();
                                }else{
                                    if (installerMode && installerInstallFilePathField != null && installerRemotePathField != null) {
                                        installerInstallFilePathField.setText(file.getAbsolutePath());
                                        installerRemotePathField.setText("/tmp/" + file.getName());
                                    }
                                    NotificationUtil.showMainNotification(I18n.t("download.notice.completed", "下载已完成！"));
                                }
                            });
                        }
                    }
                    return null;
                }
            };

            task.setOnFailed(e -> {
                stackPaneRemoveSelf();
                Throwable ex = task.getException();
                if (ex instanceof CancellationException) {
                    return;
                }
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), ex == null ? "" : ex.getMessage()));
            });

            progressBar.progressProperty().bind(task.progressProperty());
            speedLabel.textProperty().unbind();
            speedLabel.textProperty().bind(task.messageProperty());
            progressLabel.textProperty().bind(task.progressProperty().multiply(100).asString("%.0f%%"));
        } else if (source instanceof SqlExportManager.ResultSetExportSource) {
            task = createResultSetExportTask(streamingFormat, streamingResultSet, metaData, file, totalRows);
            task.setOnFailed(e -> {
                stackPaneRemoveSelf();
                Throwable ex = task.getException();
                if (ex instanceof CancellationException) {
                    return;
                }
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), ex == null ? "" : ex.getMessage()));
            });
            progressBar.progressProperty().bind(task.progressProperty());
            // 百分比显示在 progressLabel，行数/总行数显示在 speedLabel
            progressLabel.textProperty().bind(task.progressProperty().multiply(100).asString("%.0f%%"));
            speedLabel.textProperty().unbind();
            speedLabel.textProperty().bind(task.messageProperty());
        } else if (source instanceof SqlExportManager.SqlExportSource) {
            task = createSqlExportTask(sqlExportSource, file);
            task.setOnFailed(e -> {
                stackPaneRemoveSelf();
                Throwable ex = task.getException();
                if (ex instanceof CancellationException) {
                    return;
                }
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), ex == null ? "" : ex.getMessage()));
            });
            progressBar.progressProperty().bind(task.progressProperty());
            progressLabel.textProperty().bind(task.progressProperty().multiply(100).asString("%.0f%%"));
            speedLabel.textProperty().unbind();
            speedLabel.textProperty().bind(task.messageProperty());
        } else if (source instanceof SqlExportManager.CustomExportSource) {
            task = customExportSource.task;
            task.setOnSucceeded(e -> {
                if (autoCloseOnComplete) {
                    stackPaneRemoveSelf();
                }
            });
            task.setOnFailed(e -> {
                stackPaneRemoveSelf();
                Throwable ex = task.getException();
                if (ex instanceof CancellationException) {
                    return;
                }
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), ex == null ? "" : ex.getMessage()));
            });
            progressBar.progressProperty().bind(task.progressProperty());
            progressLabel.textProperty().bind(Bindings.createStringBinding(() -> {
                double progress = task.getProgress();
                return progress < 0 ? "" : String.format("%.0f%%", progress * 100);
            }, task.progressProperty()));
            speedLabel.textProperty().unbind();
            speedLabel.textProperty().bind(task.messageProperty());
        } 

        if (taskExecutor != null) {
            taskExecutor.submit(task);
        } else {
            AppExecutor.runTask(task);
        }
    }

    

    private Task<Void> createResultSetExportTask(String format, ResultSet rs, ResultSetMetaData meta, File file, long totalRows) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (totalRows > 0) {
                    updateProgress(0, totalRows);
                    updateMessage("0/" + totalRows);
                } else {
                    updateProgress(-1,1); // indeterminate
                    updateMessage("0/?");
                }
                java.util.function.Consumer<String> msg = this::updateMessage;
                java.util.function.BiConsumer<Long, Long> progressCb = (done, total) -> {
                    if (total > 0) {
                        updateProgress(done, total);
                        updateMessage(done + "/" + total);
                    } else {
                        updateMessage(done + "/?");
                    }
                };
                try {
                    SqlExportManager.logExportColumnMetadata(meta, "resultSetExport format=" + format);
                    switch (format.toLowerCase()) {
                        case "csv" -> writeCsvStreaming(rs, meta, file, progressCb, msg, totalRows);
                        case "json" -> writeJsonStreaming(rs, meta, file, progressCb, msg, totalRows);
                        case "sql" -> writeSqlStreaming(
                                rs,
                                meta,
                                file,
                                progressCb,
                                msg,
                                totalRows,
                                SqlExportManager.resolveSqlInsertTableName(meta, null, null)
                        );
                        default -> throw new IllegalArgumentException("Unknown format: " + format);
                    }
                    if (!cancelled) {
                        updateProgress(1,1);
                        Platform.runLater(() -> NotificationUtil.showMainNotification(I18n.t("download.notice.export_completed", "瀵煎嚭宸插畬鎴愶紒")));
                        if (autoCloseOnComplete) stackPaneRemoveSelf();
                    }
                } finally {
                    try { if (rs != null) rs.close(); } catch (Exception ignored) {}
                    try {
                        if (meta != null && rs != null && rs.getStatement() != null && rs.getStatement().getConnection() != null) {
                            rs.getStatement().getConnection().close();
                        }
                    } catch (Exception ignored) {}
                }
                return null;
            }
        };
    }

    private Task<Void> createSqlExportTask(SqlExportManager.SqlExportSource source, File file) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                long queryTotalRows = source.totalRowsHint > 0 ? source.totalRowsHint : -1;
                try (Connection conn = com.dbboys.app.AppContext.get(ConnectionService.class).getConnectionWithSessionInit(source.connect)) {
                    if (cancelled || isCancelled() || Thread.currentThread().isInterrupted()) {
                        return null;
                    }

                    if (queryTotalRows <= 0) {
                        try (PreparedStatement cps = conn.prepareStatement("select count(*) from (" + source.sql + ") t")) {
                            try (ResultSet crs = cps.executeQuery()) {
                                if (crs.next()) {
                                    queryTotalRows = crs.getLong(1);
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Count query failed, proceeding without total", e);
                        }
                    }

                    try (PreparedStatement ps = conn.prepareStatement(source.sql,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        try {
                            ps.setFetchSize(500);
                        } catch (Exception e) {
                            log.trace("setFetchSize not supported", e);
                        }

                        try (ResultSet rs = ps.executeQuery()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            SqlExportManager.logExportColumnMetadata(
                                    meta,
                                    "sqlExport format=" + source.format + " sql=" + source.sql
                            );
                            if (queryTotalRows > 0) {
                                updateProgress(0, queryTotalRows);
                                updateMessage("0/" + queryTotalRows);
                            } else {
                                updateProgress(-1, 1);
                                updateMessage("0/?");
                            }

                            java.util.function.Consumer<String> msg = this::updateMessage;
                            java.util.function.BiConsumer<Long, Long> progressCb = (done, total) -> {
                                if (total > 0) {
                                    updateProgress(done, total);
                                    updateMessage(done + "/" + total);
                                } else {
                                    updateMessage(done + "/?");
                                }
                            };

                            switch (source.format.toLowerCase()) {
                                case "csv" -> {
                                    if (!writeCsvStreaming(rs, meta, file, progressCb, msg, queryTotalRows,
                                            () -> cancelled || isCancelled() || Thread.currentThread().isInterrupted())) {
                                        updateProgress(1, 1);
                                        updateMessage("0/0");
                                    }
                                }
                                case "json" -> writeJsonStreaming(rs, meta, file, progressCb, msg, queryTotalRows);
                                case "sql" -> writeSqlStreaming(
                                        rs,
                                        meta,
                                        file,
                                        progressCb,
                                        msg,
                                        queryTotalRows,
                                        SqlExportManager.resolveSqlInsertTableName(
                                                meta,
                                                source.sqlInsertTargetTable,
                                                source.sql
                                        )
                                );
                                default -> throw new IllegalArgumentException("Unknown format: " + source.format);
                            }
                        }
                    }
                }

                if (!cancelled) {
                    updateProgress(1, 1);
                    Platform.runLater(() -> NotificationUtil.showMainNotification(I18n.t("download.notice.export_completed", "瀵煎嚭宸插畬鎴愶紒")));
                    if (autoCloseOnComplete) {
                        stackPaneRemoveSelf();
                    }
                }
                return null;
            }
        };
    }

    private boolean writeCsvStreaming(ResultSet rs, ResultSetMetaData meta, File file,
                                      java.util.function.BiConsumer<Long, Long> progressUpdater,
                                      java.util.function.Consumer<String> messageUpdater,
                                      long totalRows) throws Exception {
        return writeCsvStreaming(rs, meta, file, progressUpdater, messageUpdater, totalRows, null);
    }

    private boolean writeCsvStreaming(ResultSet rs, ResultSetMetaData meta, File file,
                                      java.util.function.BiConsumer<Long, Long> progressUpdater,
                                      java.util.function.Consumer<String> messageUpdater,
                                      long totalRows,
                                      BooleanSupplier cancelChecker) throws Exception {
        SqlExportManager.throwIfExportCancelled(cancelChecker);
        if (!rs.next()) {
            return false;
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write('\uFEFF');
            int columnCount = meta.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) writer.write(",");
                writer.write(escapeCsv(meta.getColumnLabel(i)));
            }
            writer.newLine();
            long row = 1;
            SqlExportManager.throwIfExportCancelled(cancelChecker);
            writeCsvStreamingRow(writer, rs, meta, columnCount);
            writer.newLine();
            if (progressUpdater != null) progressUpdater.accept(row, totalRows);
            while (!cancelled) {
                SqlExportManager.throwIfExportCancelled(cancelChecker);
                if (!rs.next()) {
                    break;
                }
                writeCsvStreamingRow(writer, rs, meta, columnCount);
                writer.newLine();
                row++;
                if (progressUpdater != null) progressUpdater.accept(row, totalRows);
                if (row % 200 == 0 && messageUpdater != null) {
                    messageUpdater.accept(totalRows > 0 ? (row + "/" + totalRows) : (row + "/?"));
                }
            }
        }
        return true;
    }

    private String readCsvCellValue(ResultSet rs, ResultSetMetaData meta, int columnIndex) throws Exception {
        String columnType = normalizeColumnType(meta.getColumnTypeName(columnIndex));
        if (isBinaryExportColumnType(columnType)) {
            byte[] bytes = rs.getBytes(columnIndex);
            return bytes == null ? null : CSV_BINARY_PREFIX + Base64.getEncoder().encodeToString(bytes);
        }
        return rs.getString(columnIndex);
    }

    private void writeCsvStreamingRow(BufferedWriter writer, ResultSet rs, ResultSetMetaData meta, int columnCount) throws Exception {
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) writer.write(",");
            String val = readCsvCellValue(rs, meta, i);
            writer.write(val == null ? "" : escapeCsv(val));
        }
    }

    private Object readJsonCellValue(ResultSet rs, ResultSetMetaData meta, int columnIndex) throws Exception {
        String columnType = normalizeColumnType(meta.getColumnTypeName(columnIndex));
        if (isBinaryExportColumnType(columnType)) {
            byte[] bytes = rs.getBytes(columnIndex);
            return bytes == null ? null : CSV_BINARY_PREFIX + Base64.getEncoder().encodeToString(bytes);
        }
        if (isRawExportColumnType(columnType)) {
            return rs.getString(columnIndex);
        }
        if (isTextLobExportColumnType(columnType)) {
            return rs.getString(columnIndex);
        }
        Object value = rs.getObject(columnIndex);
        if (value instanceof byte[]) {
            return rs.getString(columnIndex);
        }
        return value;
    }

    private boolean isBinaryExportColumnType(String columnType) {
        return columnType.startsWith("BYTE") || columnType.startsWith("BLOB");
    }

    private boolean isTextLobExportColumnType(String columnType) {
        return columnType.startsWith("TEXT") || columnType.startsWith("CLOB");
    }

    private boolean isRawExportColumnType(String columnType) {
        return columnType.startsWith("RAW");
    }

    private String normalizeColumnType(String columnType) {
        if (columnType == null) {
            return "";
        }
        return columnType.trim().toUpperCase(Locale.ROOT);
    }

    private void writeJsonStreaming(ResultSet rs, ResultSetMetaData meta, File file,
                                    java.util.function.BiConsumer<Long, Long> progressUpdater,
                                    java.util.function.Consumer<String> messageUpdater,
                                    long totalRows) throws Exception {
        int columnCount = meta.getColumnCount();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write("[");
            long row = 0;
            while (!cancelled && rs.next()) {
                if (row > 0) writer.write(",\n");
                writer.write("{");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) writer.write(",");
                    String key = meta.getColumnLabel(i);
                    Object val = readJsonCellValue(rs, meta, i);
                    writer.write("\"");
                    writer.write(escapeJson(key));
                    writer.write("\":");
                    if (val == null) {
                        writer.write("null");
                    } else if (val instanceof Number || val instanceof Boolean) {
                        writer.write(val.toString());
                    } else {
                        writer.write("\"");
                        writer.write(escapeJson(String.valueOf(val)));
                        writer.write("\"");
                    }
                }
                writer.write("}");
                row++;
                if (progressUpdater != null) progressUpdater.accept(row, totalRows);
                if (row % 200 == 0 && messageUpdater != null) {
                    messageUpdater.accept(totalRows > 0 ? (row + "/" + totalRows) : (row + "/?"));
                }
            }
            writer.write("]");
        }
    }

    private void writeSqlStreaming(ResultSet rs, ResultSetMetaData meta, File file,
                                   java.util.function.BiConsumer<Long, Long> progressUpdater,
                                   java.util.function.Consumer<String> messageUpdater,
                                   long totalRows,
                                   String insertTableName) throws Exception {
        int columnCount = meta.getColumnCount();
        String tableName = insertTableName == null || insertTableName.isBlank()
                ? "dbboys_unknown_table"
                : insertTableName.trim();
        String prefix = "insert into " + tableName + " values";

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            long row = 0;
            while (!cancelled && rs.next()) {
                writer.write(prefix);
                writer.write("(");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) writer.write(", ");
                    writer.write(readSqlCellLiteral(rs, meta, i));
                }
                writer.write(");\n");
                row++;
                if (progressUpdater != null) progressUpdater.accept(row, totalRows);
                if (row % 200 == 0 && messageUpdater != null) {
                    messageUpdater.accept(totalRows > 0 ? (row + "/" + totalRows) : (row + "/?"));
                }
            }
        }
    }

    private String readSqlCellLiteral(ResultSet rs, ResultSetMetaData meta, int columnIndex) throws Exception {
        Object rawValue = rs.getObject(columnIndex);
        if (rawValue == null && rs.wasNull()) {
            return "NULL";
        }
        if (rawValue instanceof Number) {
            return rawValue.toString();
        }
        String columnType = normalizeColumnType(meta.getColumnTypeName(columnIndex));
        if (isNumericExportColumnType(columnType)) {
            return rawValue == null ? "NULL" : rawValue.toString();
        }
        if (isBooleanExportColumnType(columnType)) {
            if (rawValue instanceof Boolean b) {
                return b ? "1" : "0";
            }
            return rawValue == null ? "NULL" : rawValue.toString();
        }

        String value = readCsvCellValue(rs, meta, columnIndex);
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private boolean isNumericExportColumnType(String columnType) {
        return SqlExportManager.isSqlExportNumericTypeName(columnType);
    }

    private boolean isBooleanExportColumnType(String columnType) {
        return columnType != null && columnType.startsWith("BOOLEAN");
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String escapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private boolean moveTempToTargetWithRetry() {
        Path sourcePath = tempFile.toPath();
        Path targetPath = file.toPath();
        int maxRetries = 6;
        long waitMillis = 120;

        for (int i = 0; i < maxRetries; i++) {
            try {
                Path parent = targetPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try {
                    Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveError) {
                    Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException e) {
                if (!tempFile.exists() && file.exists()) {
                    return true;
                }
                if (i == maxRetries - 1) {
                    log.warn("Failed to finalize download file after retries. temp={}, target={}", sourcePath, targetPath, e);
                    return false;
                }
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                waitMillis = Math.min(waitMillis * 2, 1000);
            }
        }
        return false;
    }


    private void stackPaneRemoveSelf() {
        //浠巐ist閲岀Щ闄ゅ綋鍓嶅璞★紝閬垮厤鍙栨秷鍚庢湁绌虹櫧杞鏄剧ず
        SqlExportManager.removeDownload(this, hostStackPane);
        Platform.runLater(() -> {
            StackPane parent = (StackPane) rootPane.getParent();
            if (parent != null) parent.getChildren().remove(rootPane);
        });
    }

    public void pauseDownload() {
        if (!paused) {
            paused = true;
        }
    }

    public void resumeDownload() {
        if (paused) {
            paused = false;
            startNewTask(true);
        }
    }

    public void cancelDownload() {
        if (cancelled) return;

        cancelled = true;
        paused = false;

        if (customExportSource != null && customExportSource.cancelAction != null) {
            try {
                customExportSource.cancelAction.run();
            } catch (Exception e) {
                log.debug("Custom export cancel action failed", e);
            }
        }

        if (task != null) task.cancel();

        AppExecutor.runAsync(() -> {
            try {
                if (task != null) task.get();
            } catch (Exception e) {
                log.debug("Task completion wait failed", e);
            }

            // Task 瀹屽叏缁撴潫鍚庡垹闄ゆ枃浠?
            boolean deleted = false;
            int retries = 5;
            while (!deleted && retries-- > 0) {
                if (tempFile.exists()) deleted = tempFile.delete();
                if (!deleted) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }

            final boolean success = deleted;
            Platform.runLater(() -> {
                stackPaneRemoveSelf();
                if(source instanceof String) {
                    NotificationUtil.showMainNotification(
                            // success ? "鏂囦欢銆? + file.getName() + "銆戜笅杞藉凡鍙栨秷锛? :
                            success ? I18n.t("download.notice.cancelled", "下载已取消！")
                                    : I18n.t("download.notice.delete_failed", "鏂囦欢銆?s銆戝垹闄ゅけ璐ワ紝鍙兘琚崰鐢紒").formatted(file.getName())
                    );
                }else{
                    NotificationUtil.showMainNotification(                    I18n.t("download.notice.export_cancelled", "瀵煎嚭宸插彇娑堬紒"));
                }
            });
        });
    }



}
