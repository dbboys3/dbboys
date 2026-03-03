package com.dbboys.util;

import com.dbboys.app.AppErrorHandler;

import com.dbboys.app.AppExecutor;
import com.dbboys.app.AppState;
import com.dbboys.customnode.CustomUserTextField;
import com.dbboys.i18n.I18n;
import com.dbboys.api.ConnectionService;
import com.dbboys.ui.IconFactory;
import com.dbboys.ui.IconPaths;
import com.dbboys.vo.Connect;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class DownloadTaskWrapper {
    private static final Logger log = LogManager.getLogger(DownloadTaskWrapper.class);

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

    public DownloadTaskWrapper(
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
        if (source instanceof DownloadManagerUtil.ResultSetExportSource) {
            DownloadManagerUtil.ResultSetExportSource s = (DownloadManagerUtil.ResultSetExportSource) source;
            this.streamingResultSet = s.resultSet;
            this.streamingFormat = s.format;
            this.metaData = s.metaData;
            this.totalRows = s.totalRows;
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
        //rootPane.setStyle("-fx-padding: 10; -fx-background-color: #f8f8f8;");

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
        resumeButton.getStyleClass().add("little-custom-button");
        resumeButton.setFocusTraversable(false);

        pauseButton.setGraphic(IconFactory.group(IconPaths.DOWNLOAD_PAUSE, 0.6));
        pauseButton.getStyleClass().add("little-custom-button");
        pauseButton.setFocusTraversable(false);

        stopButton.setGraphic(IconFactory.group(IconPaths.SQL_STOP, 0.5, IconFactory.dangerColor()));
        stopButton.getStyleClass().add("little-custom-button");
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

        } else if (source instanceof DownloadManagerUtil.ResultSetExportSource) {
            HBox line = new HBox(6, nameLabel, speedLabel, progressBar, progressLabel, stopButton);
            line.setAlignment(Pos.CENTER_RIGHT);
            rootPane = line;
            nameLabel.textProperty().bind(Bindings.createStringBinding(
                    () -> I18n.t("download.label.exporting_prefix", "正在导出：") + file.getName(),
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
                                //rootPane.setStyle("-fx-background-color: #c8e6c9; -fx-padding: 10;");
                            });
                        }
                    }
                    return null;
                }
            };

            task.setOnFailed(e -> {
                stackPaneRemoveSelf();
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), task.getException().getMessage()));
            });

            progressBar.progressProperty().bind(task.progressProperty());
            speedLabel.textProperty().unbind();
            speedLabel.textProperty().bind(task.messageProperty());
            progressLabel.textProperty().bind(task.progressProperty().multiply(100).asString("%.0f%%"));
        } else if (source instanceof DownloadManagerUtil.ResultSetExportSource) {
            task = createResultSetExportTask(streamingFormat, streamingResultSet, metaData, file, totalRows);
            task.setOnFailed(e -> {
                stackPaneRemoveSelf();
                Platform.runLater(() -> AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), task.getException().getMessage()));
            });
            progressBar.progressProperty().bind(task.progressProperty());
            // 百分比显示在 progressLabel，行数/总行数显示在 speedLabel
            progressLabel.textProperty().bind(task.progressProperty().multiply(100).asString("%.0f%%"));
            speedLabel.textProperty().unbind();
            speedLabel.textProperty().bind(task.messageProperty());
        } 

        AppExecutor.runTask(task);
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
                    switch (format.toLowerCase()) {
                        case "csv" -> writeCsvStreaming(rs, meta, file, progressCb, msg, totalRows);
                        case "json" -> writeJsonStreaming(rs, meta, file, progressCb, msg, totalRows);
                        case "sql" -> writeSqlStreaming(rs, meta, file, progressCb, msg, totalRows);
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

    private void writeCsvStreaming(ResultSet rs, ResultSetMetaData meta, File file,
                                   java.util.function.BiConsumer<Long, Long> progressUpdater,
                                   java.util.function.Consumer<String> messageUpdater,
                                   long totalRows) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            int columnCount = meta.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) writer.write(",");
                writer.write(escapeCsv(meta.getColumnLabel(i)));
            }
            writer.newLine();
            long row = 0;
            while (!cancelled && rs.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) writer.write(",");
                    Object val = rs.getObject(i);
                    writer.write(val == null ? "" : escapeCsv(String.valueOf(val)));
                }
                writer.newLine();
                row++;
                if (totalRows > 0 && progressUpdater != null) progressUpdater.accept(row, totalRows);
                if (row % 200 == 0 && messageUpdater != null) {
                    messageUpdater.accept(totalRows > 0 ? (row + "/" + totalRows) : (row + "/?"));
                }
            }
        }
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
                    Object val = rs.getObject(i);
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
                if (totalRows > 0 && progressUpdater != null) progressUpdater.accept(row, totalRows);
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
                                   long totalRows) throws Exception {
        int columnCount = meta.getColumnCount();
        String tableName = meta.getTableName(1);
        if (tableName == null || tableName.isBlank()) tableName = "table";
        StringBuilder prefix = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) prefix.append(", ");
            prefix.append(meta.getColumnLabel(i));
        }
        prefix.append(") VALUES ");

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            long row = 0;
            while (!cancelled && rs.next()) {
                writer.write(prefix.toString());
                writer.write("(");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) writer.write(", ");
                    Object val = rs.getObject(i);
                    if (val == null) {
                        writer.write("NULL");
                    } else if (val instanceof Number || val instanceof Boolean) {
                        writer.write(val.toString());
                    } else {
                        writer.write("'");
                        writer.write(val.toString().replace("'", "''"));
                        writer.write("'");
                    }
                }
                writer.write(");\n");
                row++;
                if (totalRows > 0 && progressUpdater != null) progressUpdater.accept(row, totalRows);
                if (row % 200 == 0 && messageUpdater != null) {
                    messageUpdater.accept(totalRows > 0 ? (row + "/" + totalRows) : (row + "/?"));
                }
            }
        }
    }

    private String escapeCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
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
        DownloadManagerUtil.removeDownload(this, hostStackPane);
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


public class DownloadManagerUtil {
    private static final Logger log = LogManager.getLogger(DownloadManagerUtil.class);

    public static StackPane downloadStackPane; // 默认下载容器
    private static final Map<StackPane, DownloadQueue> queueByStackPane = new HashMap<>();

    private static final class DownloadQueue {
        private final List<DownloadTaskWrapper> tasks = new ArrayList<>();
        private int currentIndex = 0;
    }

    /** 缁撴灉闆嗘祦寮忓鍑烘簮锛堥伩鍏嶄竴娆℃€ц浇鍏ュ唴瀛橈級 */
    public static class ResultSetExportSource {
        public final ResultSet resultSet;
        public final ResultSetMetaData metaData;
        /** csv | json | sql */
        public final String format;
        public final long totalRows;
        public ResultSetExportSource(ResultSet resultSet, ResultSetMetaData metaData, String format, long totalRows) {
            this.resultSet = resultSet;
            this.metaData = metaData;
            this.format = format;
            this.totalRows = totalRows;
        }
    }

    static {
        downloadStackPane = AppState.getDownloadStackPane();
        // 自动轮播
        AppExecutor.runAsync(() -> {
            try {
                while (true) {
                    Thread.sleep(3000); // 每3秒切换
                    Platform.runLater(DownloadManagerUtil::showNextForAllQueues);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }


    /** 添加下载任务 */
    public static void addDownload(Object source, File file, boolean autoCloseOnComplete, ResultSetMetaData metaData) {
        addDownloadInternal(
                source,
                file,
                autoCloseOnComplete,
                metaData,
                downloadStackPane,
                false,
                null,
                null,
                I18n.t("download.error.file_exists", "文件\"%s\"已存在，无需重复下载！"),
                I18n.t("download.error.file_downloading", "该文件正在下载，无需重复下载！"),
                false
        );
    }

    /** 娣诲姞缁撴灉闆嗗鍑轰换鍔★紙鍚庡彴銆佸彲鏆傚仠/鍙栨秷銆佹祦寮忓啓鍑猴級 */
    public static void addResultSetExport(ResultSetExportSource source, File file, boolean autoCloseOnComplete) {
        addDownloadInternal(
                source,
                file,
                autoCloseOnComplete,
                source.metaData,
                downloadStackPane,
                false,
                null,
                null,
                I18n.t("download.error.file_exists", "文件\"%s\"已存在，无需重复下载！"),
                I18n.t("download.error.file_downloading", "该文件正在下载，无需重复下载！"),
                false
        );
    }

    public static void addInstallDownload(
            Object source,
            File file,
            boolean autoCloseOnComplete,
            ResultSetMetaData metaData,
            StackPane hostStackPane,
            CustomUserTextField remotePathField,
            CustomUserTextField installFilePathField
    ) {
        addDownloadInternal(
                source,
                file,
                autoCloseOnComplete,
                metaData,
                hostStackPane,
                true,
                remotePathField,
                installFilePathField,
                I18n.t("install.download.error.file_exists", "该文件在目录中已存在，无需重复下载！"),
                I18n.t("install.download.error.file_downloading", "该文件正在下载，路径已自动填充，无需重复下载！"),
                true
        );
    }

    private static void addDownloadInternal(
            Object source,
            File file,
            boolean autoCloseOnComplete,
            ResultSetMetaData metaData,
            StackPane hostStackPane,
            boolean installerMode,
            CustomUserTextField remotePathField,
            CustomUserTextField installFilePathField,
            String fileExistsMessage,
            String downloadingMessage,
            boolean fillInstallerPathWhenDuplicate
    ) {
        if (hostStackPane == null) {
            AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), I18n.t("download.error.host_missing", "下载容器未初始化"));
            return;
        }
        if(file.exists()){
            if (fillInstallerPathWhenDuplicate && installFilePathField != null && remotePathField != null) {
                installFilePathField.setText(file.getAbsolutePath());
                remotePathField.setText("/tmp/" + file.getName());
            }
            AlertUtil.CustomAlert(
                    I18n.t("download.error.title", "下载失败"),
                    fileExistsMessage.formatted(file.getAbsolutePath())
            );

            return;
        }

        File tempFile=new File(file.getAbsolutePath()+".download");
        if(tempFile.exists()){
            Platform.runLater(() -> {
               AlertUtil.CustomAlert(
                       I18n.t("download.error.title", "下载失败"),
                       downloadingMessage
               );
            });
            return;
        }
        DownloadQueue queue = getOrCreateQueue(hostStackPane);
        DownloadTaskWrapper wrapper = new DownloadTaskWrapper(
                source,
                file,
                autoCloseOnComplete,
                metaData,
                hostStackPane,
                installerMode,
                remotePathField,
                installFilePathField
        );
        queue.tasks.add(wrapper);

        Platform.runLater(() -> {
            hostStackPane.getChildren().add(wrapper.getRootPane());
            wrapper.getRootPane().setVisible(false); // 榛樿闅愯棌
            if (queue.tasks.size() == 1) {
                wrapper.getRootPane().setVisible(true); // 绗竴涓樉绀?
            }
        });

        wrapper.start(); // 鍚姩涓嬭浇
    }

    private static DownloadQueue getOrCreateQueue(StackPane hostStackPane) {
        return queueByStackPane.computeIfAbsent(hostStackPane, key -> new DownloadQueue());
    }

    /** 鏄剧ず涓嬩竴涓换鍔?*/
    private static void showNextForAllQueues() {
        for (Map.Entry<StackPane, DownloadQueue> entry : queueByStackPane.entrySet()) {
            showNext(entry.getValue());
        }
    }

    private static void showNext(DownloadQueue queue) {
        if (queue.tasks.isEmpty()) return;

        // 闅愯棌褰撳墠鏄剧ず
        if (queue.currentIndex < queue.tasks.size()) {
            queue.tasks.get(queue.currentIndex).getRootPane().setVisible(false);
        }

        queue.currentIndex = (queue.currentIndex + 1) % queue.tasks.size();

        // 鏄剧ず涓嬩竴涓?
        queue.tasks.get(queue.currentIndex).getRootPane().setVisible(true);
    }

    /** 鍋滄鎵€鏈変换鍔?*/
    public void stopAll() {
        queueByStackPane.values().forEach(queue -> queue.tasks.forEach(DownloadTaskWrapper::cancelDownload));
    }

    /**
     * 基于 SQL 的结果集异步导出，复用下载管理的流式写出，避免阻塞 UI。
     * 注意：传入的连接需由调用方维护生命周期。
     */
    public static void addSqlExportTask(Connect sqlConnect,
                                        String sql,
                                        File file,
                                        String format,
                                        boolean autoCloseOnComplete) {
        Task<Void> prepTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                long totalRows = -1;
                Connection conn = com.dbboys.app.AppContext.get(ConnectionService.class).getConnection(sqlConnect);
                // 统计总行数
                try (PreparedStatement cps = conn.prepareStatement("select count(*) from (" + sql + ") t")) {
                    try (ResultSet crs = cps.executeQuery()) {
                        if (crs.next()) totalRows = crs.getLong(1);
                    }
                } catch (Exception e) {
                    log.debug("Count query failed, proceeding without total", e);
                }

                PreparedStatement ps = conn.prepareStatement(sql,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                try { ps.setFetchSize(500); } catch (Exception e) { log.trace("setFetchSize not supported", e); }
                ResultSet rs = ps.executeQuery();
                ResultSetMetaData meta = rs.getMetaData();
                long finalTotalRows = totalRows;

                Platform.runLater(() -> addResultSetExport(
                        new ResultSetExportSource(rs, meta, format, finalTotalRows),
                        file,
                        autoCloseOnComplete
                ));
                return null;
            }
        };
        prepTask.setOnFailed(ev -> AppErrorHandler.handle(prepTask.getException()));
        AppExecutor.runTask(prepTask);
    }

    /** 娓呴櫎鎵€鏈変换鍔?*/
    public void clearAll() {
        stopAll();
        Platform.runLater(() -> queueByStackPane.keySet().forEach(pane -> pane.getChildren().clear()));
        queueByStackPane.clear();
    }

    public static void removeDownload(DownloadTaskWrapper wrapper, StackPane hostStackPane) {
        DownloadQueue queue = queueByStackPane.get(hostStackPane);
        if (queue == null) {
            return;
        }
        int index = queue.tasks.indexOf(wrapper);
        if (index == -1) return;

        queue.tasks.remove(wrapper);

        // 淇 currentIndex锛岄伩鍏嶈秺鐣?
        if (queue.currentIndex >= queue.tasks.size()) {
            queue.currentIndex = 0;
        }

        // 如果移除的是当前显示的任务，需要展示下一个
        if (!queue.tasks.isEmpty()) {
            queue.tasks.get(queue.currentIndex).getRootPane().setVisible(true);
        } else {
            queueByStackPane.remove(hostStackPane);
        }
    }

    /**
     * 杩借釜HTTP閲嶅畾鍚戯紝鑾峰彇鐪熷疄鏂囦欢鍚?
     * @param originalUrl 鍘熷涓嬭浇閾炬帴
     * @return 鐪熷疄鏂囦欢鍚嶏紙瑙ｆ瀽澶辫触杩斿洖鍘熸枃浠跺悕锛?
     */
    public static String getRealFileNameFromRedirect(String originalUrl) throws Exception {
        String fileName="";
        fileName=originalUrl.substring(originalUrl.lastIndexOf("/")+1);

        HttpURLConnection conn = null;
            URL url = new URL(originalUrl);
            // 鎵嬪姩杩借釜鎵€鏈夐噸瀹氬悜锛屼笉渚濊禆鑷姩璺宠浆
            while (true) {
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("HEAD"); // 浠呰幏鍙栧搷搴斿ご锛屼笉涓嬭浇鍐呭锛屾彁鍗囨€ц兘
                conn.setInstanceFollowRedirects(false); // 鍏抽棴鑷姩閲嶅畾鍚戯紝鎵嬪姩澶勭悊
                conn.setRequestProperty("User-Agent", "JavaFX Downloader");
                conn.connect();

                int responseCode = conn.getResponseCode();
                // 澶勭悊3xx閲嶅畾鍚戝搷搴?
                if (responseCode >= 300 && responseCode < 400) {
                    String redirectUrl = conn.getHeaderField("Location");
                    if (redirectUrl == null) break;
                    // 澶勭悊鐩稿璺緞閲嶅畾鍚戯紙濡?Location: /file.zip锛?
                    url = new URL(url, redirectUrl);
                    conn.disconnect();
                } else {
                    break;
                }
            }

            // 浼樺厛绾?锛氫粠 Content-Disposition 鍝嶅簲澶磋В鏋愭枃浠跺悕锛堟爣鍑嗘柟寮忥級
            String disposition = conn.getHeaderField("Content-Disposition");
            if (disposition != null && !disposition.isEmpty()) {
                Pattern pattern = Pattern.compile("filename[^;=\\n]*=((['\"]).*?\\2|[^;\\n]*)");
                Matcher matcher = pattern.matcher(disposition);
                if (matcher.find()) {
                    fileName = matcher.group(1).replace("\"", "").replace("'", "");
                    fileName= URLDecoder.decode(fileName, StandardCharsets.UTF_8.name());
                    return fileName;
                }
            }

            // 浼樺厛绾?锛氫粠鏈€缁堥噸瀹氬悜鐨刄RL涓В鏋愭枃浠跺悕
            String finalUrl = url.toString();
            fileName = finalUrl.substring(finalUrl.lastIndexOf('/') + 1);
            // 鍘婚櫎URL鍙傛暟锛堝 file.zip?token=xxx 鈫?file.zip锛?
            if (fileName.contains("?")) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
        if (conn != null) conn.disconnect();
        fileName=URLDecoder.decode(fileName, StandardCharsets.UTF_8.name());
        return fileName;
        //return fileName; // 解析失败则返回原文件名

    }

    public static String encodeUrl(String url) throws Exception {
        URL u = new URL(url);
        URI uri = new URI(
                u.getProtocol(),
                u.getUserInfo(),
                u.getHost(),
                u.getPort(),
                u.getPath(),
                u.getQuery(),
                u.getRef()
        );
        log.info("url is:"+url);
        log.info("return url is:"+uri.toASCIIString());
        return uri.toASCIIString();
    }



}






