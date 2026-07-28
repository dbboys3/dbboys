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
public class SqlExportManager {
    private static final Logger log = LogManager.getLogger(SqlExportManager.class);
    private static final String EXPORT_BINARY_PREFIX = "base64:";
    private static final int SQL_EXPORT_MAX_CONCURRENCY = 8;
    private static final ExecutorService SQL_EXPORT_POOL = Executors.newFixedThreadPool(SQL_EXPORT_MAX_CONCURRENCY, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("dbboys-sql-export-" + t.getId());
        return t;
    });

    /**
     * 导出写入文件前记录 JDBC 解析到的列标签、字典名、类型名与 JDBC 类型（便于排查 Oracle 等与驱动相关的类型问题）。
     */
    public static void logExportColumnMetadata(ResultSetMetaData meta, String contextHint) {
        if (meta == null) {
            return;
        }
        try {
            int n = meta.getColumnCount();
            StringBuilder sb = new StringBuilder(Math.max(64, n * 64));
            for (int i = 1; i <= n; i++) {
                if (i > 1) {
                    sb.append(" | ");
                }
                sb.append('[').append(i).append(']');
                sb.append("label=").append(meta.getColumnLabel(i));
                sb.append(",name=").append(meta.getColumnName(i));
                sb.append(",typeName=").append(meta.getColumnTypeName(i));
                sb.append(",jdbcType=").append(meta.getColumnType(i));
            }
            String ctx = contextHint == null ? "" : contextHint;
            if (ctx.length() > 500) {
                ctx = ctx.substring(0, 500) + "...";
            }
            log.info("Export column metadata (before write), context=\"{}\": {}", ctx, sb);
        } catch (SQLException e) {
            log.warn("Failed to read ResultSetMetaData for export logging", e);
        }
    }

    private static final Pattern SQL_EXPORT_FROM_TABLE =
            Pattern.compile("(?is)\\bfrom\\s+((?:\"[^\"]+\"|[\\w$#]+)(?:\\.(?:\"[^\"]+\"|[\\w$#]+))?)\\s");

    /**
     * SQL 导出用的 insert 目标表名：优先调用方传入（元数据树导出），否则 JDBC {@link ResultSetMetaData#getTableName(int)}，
     * 再从 {@code select ... from <表>} 简单解析；避免使用保留字 {@code table} 作占位。
     */
    public static String resolveSqlInsertTableName(ResultSetMetaData meta, String override, String sql)
            throws SQLException {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        if (meta != null) {
            String t = meta.getTableName(1);
            if (t != null && !t.isBlank()) {
                return t.trim();
            }
        }
        if (sql != null && !sql.isBlank()) {
            String inferred = tryInferTableFromSelectSql(sql);
            if (inferred != null && !inferred.isBlank()) {
                return inferred;
            }
        }
        return "dbboys_unknown_table";
    }

    static String tryInferTableFromSelectSql(String sql) {
        Matcher m = SQL_EXPORT_FROM_TABLE.matcher(sql);
        if (m.find()) {
            String cand = m.group(1).trim();
            if (!cand.isEmpty() && !cand.equalsIgnoreCase("(")) {
                return cand;
            }
        }
        Matcher end = Pattern.compile("(?is)\\bfrom\\s+((?:\"[^\"]+\"|[\\w$#]+)(?:\\.(?:\"[^\"]+\"|[\\w$#]+))?)\\s*;?\\s*$")
                .matcher(sql.strip());
        if (end.find()) {
            return end.group(1).trim();
        }
        return null;
    }

    /** Oracle {@code NUMBER} / {@code BINARY_FLOAT} 等与 JDBC {@link Number} 字面量导出。 */
    static boolean isSqlExportNumericTypeName(String columnType) {
        if (columnType == null || columnType.isEmpty()) {
            return false;
        }
        return columnType.startsWith("NUMBER")
                || columnType.startsWith("BINARY_FLOAT")
                || columnType.startsWith("BINARY_DOUBLE")
                || columnType.startsWith("SMALLINT")
                || columnType.startsWith("INTEGER")
                || columnType.equals("INT")
                || columnType.startsWith("INT(")
                || columnType.startsWith("BIGINT")
                || columnType.startsWith("DECIMAL")
                || columnType.startsWith("NUMERIC")
                || columnType.startsWith("MONEY")
                || columnType.startsWith("SMALLFLOAT")
                || columnType.startsWith("FLOAT")
                || columnType.startsWith("DOUBLE")
                || columnType.startsWith("REAL")
                || columnType.startsWith("SERIAL")
                || columnType.startsWith("SERIAL8")
                || columnType.startsWith("BIGSERIAL");
    }

    public static StackPane downloadStackPane; // 默认下载容器
    private static final Map<StackPane, DownloadQueue> queueByStackPane = new HashMap<>();

    private static final class DownloadQueue {
        private final List<DownloadManager> tasks = new ArrayList<>();
        private int currentIndex = 0;
    }

    /** 缁撴灉闆嗘祦寮忓鍑烘簮锛堥伩鍏嶄竴娆℃€ц浇鍏ュ唴瀛橈級 */
    public static class ResultSetExportSource {
        public final ResultSet resultSet;
        public final ResultSetMetaData metaData;
        /** csv | json | sql */
        public final String format;
        public final long totalRows;
        public final ExecutorService executor;
        public ResultSetExportSource(ResultSet resultSet, ResultSetMetaData metaData, String format, long totalRows) {
            this(resultSet, metaData, format, totalRows, null);
        }
        public ResultSetExportSource(ResultSet resultSet,
                                     ResultSetMetaData metaData,
                                     String format,
                                     long totalRows,
                                     ExecutorService executor) {
            this.resultSet = resultSet;
            this.metaData = metaData;
            this.format = format;
            this.totalRows = totalRows;
            this.executor = executor;
        }
    }

    public static class SqlExportSource {
        public final Connect connect;
        public final String sql;
        public final String format;
        public final ExecutorService executor;
        public final long totalRowsHint;
        /** 非空时用于 SQL 导出 {@code insert into <此名> values(...)}（JDBC 元数据常拿不到表名） */
        public final String sqlInsertTargetTable;

        public SqlExportSource(Connect connect, String sql, String format, ExecutorService executor) {
            this(connect, sql, format, executor, -1, null);
        }

        public SqlExportSource(Connect connect,
                               String sql,
                               String format,
                               ExecutorService executor,
                               long totalRowsHint) {
            this(connect, sql, format, executor, totalRowsHint, null);
        }

        public SqlExportSource(Connect connect,
                               String sql,
                               String format,
                               ExecutorService executor,
                               long totalRowsHint,
                               String sqlInsertTargetTable) {
            this.connect = connect;
            this.sql = sql;
            this.format = format;
            this.executor = executor;
            this.totalRowsHint = totalRowsHint;
            this.sqlInsertTargetTable = (sqlInsertTargetTable == null || sqlInsertTargetTable.isBlank())
                    ? null
                    : sqlInsertTargetTable.trim();
        }
    }

    public static class CustomExportSource {
        public final String displayName;
        public final Task<Void> task;
        public final Runnable cancelAction;

        public CustomExportSource(String displayName, Task<Void> task) {
            this(displayName, task, null);
        }

        public CustomExportSource(String displayName, Task<Void> task, Runnable cancelAction) {
            this.displayName = displayName == null ? "" : displayName;
            this.task = task;
            this.cancelAction = cancelAction;
        }
    }

    static {
        downloadStackPane = AppState.getDownloadStackPane();
        // 自动轮播
        AppExecutor.runAsync(() -> {
            try {
                while (true) {
                    Thread.sleep(3000); // 每3秒切换
                    Platform.runLater(SqlExportManager::showNextForAllQueues);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public static boolean exportSqlToCsvFile(Connect sqlConnect, String sql, File file) throws Exception {
        return exportSqlToCsvFile(sqlConnect, sql, file, null);
    }

    public static boolean exportSqlToCsvFile(Connect sqlConnect,
                                             String sql,
                                             File file,
                                             BooleanSupplier cancelChecker) throws Exception {
        try (Connection conn = com.dbboys.app.AppContext.get(ConnectionService.class).getConnectionWithSessionInit(sqlConnect);
             PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            throwIfExportCancelled(cancelChecker);
            try {
                ps.setFetchSize(500);
            } catch (Exception e) {
                log.trace("setFetchSize not supported", e);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                logExportColumnMetadata(meta, "exportSqlToCsvFile sql=" + sql);
                return writeCsvFile(rs, meta, file, cancelChecker);
            }
        }
    }

    public static boolean exportSqlToCsvFile(Connection conn, String sql, File file) throws Exception {
        return exportSqlToCsvFile(conn, sql, file, null);
    }

    public static boolean exportSqlToCsvFile(Connection conn,
                                             String sql,
                                             File file,
                                             BooleanSupplier cancelChecker) throws Exception {
        return exportSqlToFile(conn, sql, file, "csv", -1, null, cancelChecker, null);
    }

    public static boolean exportSqlToFile(Connection conn,
                                          String sql,
                                          File file,
                                          String format,
                                          long totalRowsHint,
                                          java.util.function.BiConsumer<Long, Long> progressUpdater,
                                          BooleanSupplier cancelChecker) throws Exception {
        return exportSqlToFile(conn, sql, file, format, totalRowsHint, progressUpdater, cancelChecker, null);
    }

    /**
     * @param sqlInsertTargetTable 非空时用于 {@code insert into} 目标表名（JDBC 元数据常拿不到表名）；可为 null
     */
    public static boolean exportSqlToFile(Connection conn,
                                          String sql,
                                          File file,
                                          String format,
                                          long totalRowsHint,
                                          java.util.function.BiConsumer<Long, Long> progressUpdater,
                                          BooleanSupplier cancelChecker,
                                          String sqlInsertTargetTable) throws Exception {
        throwIfExportCancelled(cancelChecker);
        long queryTotalRows = totalRowsHint > 0 ? totalRowsHint : -1;
        if (queryTotalRows <= 0 && progressUpdater != null) {
            try (PreparedStatement cps = conn.prepareStatement("select count(*) from (" + sql + ") t")) {
                try (ResultSet crs = cps.executeQuery()) {
                    if (crs.next()) {
                        queryTotalRows = crs.getLong(1);
                    }
                }
            } catch (Exception e) {
                log.debug("Count query failed, proceeding without total", e);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            try {
                ps.setFetchSize(500);
            } catch (Exception e) {
                log.trace("setFetchSize not supported", e);
            }
            throwIfExportCancelled(cancelChecker);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                logExportColumnMetadata(meta, "exportSqlToFile format=" + format + " sql=" + sql);
                return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
                    case "csv" -> writeCsvFile(rs, meta, file, queryTotalRows, progressUpdater, cancelChecker);
                    case "json" -> {
                        writeJsonFile(rs, meta, file, queryTotalRows, progressUpdater, cancelChecker);
                        yield true;
                    }
                    case "sql" -> {
                        writeSqlFile(
                                rs,
                                meta,
                                file,
                                queryTotalRows,
                                progressUpdater,
                                cancelChecker,
                                resolveSqlInsertTableName(meta, sqlInsertTargetTable, sql)
                        );
                        yield true;
                    }
                    default -> throw new IllegalArgumentException("Unknown format: " + format);
                };
            }
        }
    }

    static boolean writeCsvFile(ResultSet rs,
                                ResultSetMetaData meta,
                                File file,
                                BooleanSupplier cancelChecker) throws Exception {
        return writeCsvFile(rs, meta, file, -1, null, cancelChecker);
    }

    private static boolean writeCsvFile(ResultSet rs,
                                        ResultSetMetaData meta,
                                        File file,
                                        long totalRows,
                                        java.util.function.BiConsumer<Long, Long> progressUpdater,
                                        BooleanSupplier cancelChecker) throws Exception {
        throwIfExportCancelled(cancelChecker);
        if (!rs.next()) {
            return false;
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write('\uFEFF');
            int columnCount = meta.getColumnCount();
            long row = 1;
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    writer.write(",");
                }
                writer.write(escapeCsvValue(meta.getColumnLabel(i)));
            }
            writer.newLine();
            throwIfExportCancelled(cancelChecker);
            writeCsvDataRow(writer, rs, meta, columnCount);
            updateExportProgress(progressUpdater, row, totalRows);
            while (rs.next()) {
                throwIfExportCancelled(cancelChecker);
                writeCsvDataRow(writer, rs, meta, columnCount);
                row++;
                updateExportProgress(progressUpdater, row, totalRows);
            }
        }
        return true;
    }

    private static void writeCsvDataRow(BufferedWriter writer, ResultSet rs, ResultSetMetaData meta, int columnCount) throws Exception {
        for (int i = 1; i <= columnCount; i++) {
            if (i > 1) {
                writer.write(",");
            }
            String val = readCsvExportValue(rs, meta, i);
            writer.write(val == null ? "" : escapeCsvValue(val));
        }
        writer.newLine();
    }

    private static String readCsvExportValue(ResultSet rs, ResultSetMetaData meta, int columnIndex) throws Exception {
        String columnType = normalizeExportColumnType(meta.getColumnTypeName(columnIndex));
        if (columnType.startsWith("BYTE") || columnType.startsWith("BLOB")) {
            byte[] bytes = rs.getBytes(columnIndex);
            return bytes == null ? null : "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
        return rs.getString(columnIndex);
    }

    private static String normalizeExportColumnType(String columnType) {
        if (columnType == null) {
            return "";
        }
        return columnType.trim().toUpperCase(Locale.ROOT);
    }

    private static String escapeCsvValue(String value) {
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

    private static void writeJsonFile(ResultSet rs,
                                      ResultSetMetaData meta,
                                      File file,
                                      long totalRows,
                                      java.util.function.BiConsumer<Long, Long> progressUpdater,
                                      BooleanSupplier cancelChecker) throws Exception {
        int columnCount = meta.getColumnCount();
        throwIfExportCancelled(cancelChecker);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write("[");
            long row = 0;
            while (rs.next()) {
                throwIfExportCancelled(cancelChecker);
                if (row > 0) {
                    writer.write(",\n");
                }
                writer.write("{");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        writer.write(",");
                    }
                    String key = meta.getColumnLabel(i);
                    Object value = readJsonExportValue(rs, meta, i);
                    writer.write("\"");
                    writer.write(escapeJsonValue(key));
                    writer.write("\":");
                    if (value == null) {
                        writer.write("null");
                    } else if (value instanceof Number || value instanceof Boolean) {
                        writer.write(value.toString());
                    } else {
                        writer.write("\"");
                        writer.write(escapeJsonValue(String.valueOf(value)));
                        writer.write("\"");
                    }
                }
                writer.write("}");
                row++;
                updateExportProgress(progressUpdater, row, totalRows);
            }
            writer.write("]");
        }
    }

    private static void writeSqlFile(ResultSet rs,
                                     ResultSetMetaData meta,
                                     File file,
                                     long totalRows,
                                     java.util.function.BiConsumer<Long, Long> progressUpdater,
                                     BooleanSupplier cancelChecker,
                                     String insertTableName) throws Exception {
        int columnCount = meta.getColumnCount();
        String tableName = insertTableName == null || insertTableName.isBlank()
                ? "dbboys_unknown_table"
                : insertTableName.trim();
        String prefix = "insert into " + tableName + " values";
        throwIfExportCancelled(cancelChecker);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            long row = 0;
            while (rs.next()) {
                throwIfExportCancelled(cancelChecker);
                writer.write(prefix);
                writer.write("(");
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        writer.write(", ");
                    }
                    writer.write(readSqlExportValue(rs, meta, i));
                }
                writer.write(");\n");
                row++;
                updateExportProgress(progressUpdater, row, totalRows);
            }
        }
    }

    private static Object readJsonExportValue(ResultSet rs, ResultSetMetaData meta, int columnIndex) throws Exception {
        String columnType = normalizeExportColumnType(meta.getColumnTypeName(columnIndex));
        if (columnType.startsWith("BYTE") || columnType.startsWith("BLOB")) {
            byte[] bytes = rs.getBytes(columnIndex);
            return bytes == null ? null : EXPORT_BINARY_PREFIX + Base64.getEncoder().encodeToString(bytes);
        }
        if (columnType.startsWith("RAW")
                || columnType.startsWith("TEXT")
                || columnType.startsWith("CLOB")) {
            return rs.getString(columnIndex);
        }
        Object value = rs.getObject(columnIndex);
        if (value instanceof byte[]) {
            return rs.getString(columnIndex);
        }
        return value;
    }

    private static String readSqlExportValue(ResultSet rs, ResultSetMetaData meta, int columnIndex) throws Exception {
        Object rawValue = rs.getObject(columnIndex);
        if (rawValue == null && rs.wasNull()) {
            return "NULL";
        }
        if (rawValue instanceof Number) {
            return rawValue.toString();
        }
        String columnType = normalizeExportColumnType(meta.getColumnTypeName(columnIndex));
        if (isSqlExportNumericTypeName(columnType)) {
            return rawValue == null ? "NULL" : rawValue.toString();
        }
        if (isBooleanExportColumnType(columnType)) {
            if (rawValue instanceof Boolean b) {
                return b ? "1" : "0";
            }
            return rawValue == null ? "NULL" : rawValue.toString();
        }
        String value = readCsvExportValue(rs, meta, columnIndex);
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean isBooleanExportColumnType(String columnType) {
        return columnType != null && columnType.startsWith("BOOLEAN");
    }

    private static String escapeJsonValue(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void updateExportProgress(java.util.function.BiConsumer<Long, Long> progressUpdater,
                                             long completedRows,
                                             long totalRows) {
        if (progressUpdater != null) {
            progressUpdater.accept(completedRows, totalRows);
        }
    }

    static void throwIfExportCancelled(BooleanSupplier cancelChecker) {
        if (cancelChecker != null && cancelChecker.getAsBoolean()) {
            throw new CancellationException("export cancelled");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("export cancelled");
        }
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
                false,
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
                false,
                true
        );
    }

    public static void addCustomExportTask(String displayName,
                                           File file,
                                           boolean autoCloseOnComplete,
                                           Task<Void> task) {
        addCustomExportTask(displayName, file, autoCloseOnComplete, task, null);
    }

    public static void addCustomExportTask(String displayName,
                                           File file,
                                           boolean autoCloseOnComplete,
                                           Task<Void> task,
                                           Runnable cancelAction) {
        addDownloadInternal(
                new CustomExportSource(displayName, task, cancelAction),
                file,
                autoCloseOnComplete,
                null,
                downloadStackPane,
                false,
                null,
                null,
                I18n.t("download.error.file_exists", "文件\"%s\"已存在，无需重复下载！"),
                I18n.t("download.error.file_downloading", "该文件正在下载，无需重复下载！"),
                false,
                true
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
            true,
            false
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
            boolean fillInstallerPathWhenDuplicate,
            boolean overwriteExistingFile
    ) {
        if (hostStackPane == null) {
            AlertUtil.CustomAlert(I18n.t("download.error.title", "下载失败"), I18n.t("download.error.host_missing", "下载容器未初始化"));
            return;
        }
        if(file.exists() && !overwriteExistingFile){
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
        DownloadManager wrapper = new DownloadManager(
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
        queueByStackPane.values().forEach(queue -> queue.tasks.forEach(DownloadManager::cancelDownload));
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
        addSqlExportTask(sqlConnect, sql, file, format, autoCloseOnComplete, -1);
    }

    public static void addSqlExportTask(Connect sqlConnect,
                                        String sql,
                                        File file,
                                        String format,
                                        boolean autoCloseOnComplete,
                                        long totalRowsHint) {
        addSqlExportTask(sqlConnect, sql, file, format, autoCloseOnComplete, totalRowsHint, null);
    }

    public static void addSqlExportTask(Connect sqlConnect,
                                        String sql,
                                        File file,
                                        String format,
                                        boolean autoCloseOnComplete,
                                        long totalRowsHint,
                                        String sqlInsertTargetTable) {
        addDownloadInternal(
                new SqlExportSource(new Connect(sqlConnect), sql, format, SQL_EXPORT_POOL, totalRowsHint, sqlInsertTargetTable),
                file,
                autoCloseOnComplete,
                null,
                downloadStackPane,
                false,
                null,
                null,
                I18n.t("download.error.file_exists", "文件\"%s\"已存在，无需重复下载！"),
                I18n.t("download.error.file_downloading", "该文件正在下载，无需重复下载！"),
                false,
                true
        );
    }

    /** 娓呴櫎鎵€鏈変换鍔?*/
    public void clearAll() {
        stopAll();
        Platform.runLater(() -> queueByStackPane.keySet().forEach(pane -> pane.getChildren().clear()));
        queueByStackPane.clear();
    }

    public static void removeDownload(DownloadManager wrapper, StackPane hostStackPane) {
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





