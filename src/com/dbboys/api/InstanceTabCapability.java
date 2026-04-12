package com.dbboys.api;

import com.dbboys.vo.Connect;
import com.dbboys.vo.HealthCheck;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实例管理页签专用能力。
 * 用于承接实例名、巡检、运行日志、参数管理、启停与空间管理等平台差异。
 */
public interface InstanceTabCapability {

    default boolean supportsInfoTab(Connect connect) {
        return true;
    }

    default boolean supportsHealthCheckTab(Connect connect) {
        return false;
    }

    default boolean supportsLogTab(Connect connect) {
        return false;
    }

    default boolean supportsConfigTab(Connect connect) {
        return false;
    }

    default boolean canEditConfig(Connect connect) {
        return supportsConfigTab(connect);
    }

    default boolean supportsStartStopTab(Connect connect) {
        return false;
    }

    default String instanceName(Connect connect) {
        return "";
    }

    default String runtimeLogCommand(Connect connect) {
        return "";
    }

    default String installDirEnvName(Connect connect) {
        return "";
    }

    default String adminOsUser(Connect connect) {
        return "";
    }

    default String versionExpectation(Connect connect) {
        return "";
    }

    default String buildInfoText(Connect connect) {
        StringBuilder text = new StringBuilder();
        text.append("##########################################################################################\n");
        text.append("Connection Information\n");
        text.append("##########################################################################################\n");
        text.append(String.format("%-30s", "Connection Name")).append(connect.getName()).append("\n");
        text.append(String.format("%-30s", "Database Type")).append(connect.getDbtype()).append("\n");
        text.append(String.format("%-30s", "JDBC Driver"))
                .append(connect.getDriver()).append("  (MD5:").append(connect.getDrivermd5()).append(")\n");
        text.append(String.format("%-30s", "IP Address")).append(connect.getIp()).append("\n");
        text.append(String.format("%-30s", "Port")).append(connect.getPort()).append("\n");
        text.append(String.format("%-30s", "Database User")).append(connect.getUsername()).append("\n");
        text.append(String.format("%-30s", "Driver Properties")).append(resolveDriverProps(connect)).append("\n");
        text.append(String.format("%-30s", "Database Version")).append(connect.getDbversion()).append("\n");
        text.append(connect.getInfo()).append("\n");
        return text.toString();
    }

    default List<HealthCheck> loadHealthChecks(Connect connect) throws Exception {
        return List.of();
    }

    default CheckTableModel buildCheckTable(Connect connect) throws Exception {
        List<HealthCheck> checks = loadHealthChecks(connect);
        List<CheckColumn> columns = List.of(
                new CheckColumn("entry", "instance.check.column.item", "巡检项", CheckColumnKind.TEXT, 200),
                new CheckColumn("cmd", "instance.check.column.cmd", "巡检命令", CheckColumnKind.TEXT, 100),
                new CheckColumn("healthValue", "instance.check.column.expected", "正常值", CheckColumnKind.TEXT, 300),
                new CheckColumn("currentValue", "instance.check.column.current", "当前值", CheckColumnKind.TEXT, 300),
                new CheckColumn("status", "instance.check.column.result", "巡检结论", CheckColumnKind.STATUS, 100)
        );
        String runtimeLogCommand = runtimeLogCommand(connect);
        List<CheckRow> rows = checks.stream().map(check -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("entry", check.getEntry());
            values.put("cmd", check.getCmd());
            values.put("healthValue", check.getHealthValue());
            values.put("currentValue", check.getCurrentValue());
            values.put("status", check.getStatus());
            return new CheckRow(
                    values,
                    Map.of(),
                    check.getCmd(),
                    check.getCmdOutput(),
                    runtimeLogCommand != null && !runtimeLogCommand.isBlank() && runtimeLogCommand.equals(check.getCmd())
            );
        }).toList();
        return new CheckTableModel(columns, rows);
    }

    default List<ConfigEntry> loadConfigEntries(Connect connect) throws Exception {
        return List.of();
    }

    default String loadRuntimeLog(Connect connect) throws Exception {
        return "";
    }

    default boolean isInstanceOnline(Connect connect) throws Exception {
        return false;
    }

    default ConfigUpdateResult updateConfig(Connect connect, String paramName, String newValue) throws Exception {
        throw new UnsupportedOperationException("Config update is not supported");
    }

    default void startInstance(Connect connect) throws Exception {
        throw new UnsupportedOperationException("Instance start is not supported");
    }

    default void stopInstance(Connect connect) throws Exception {
        throw new UnsupportedOperationException("Instance stop is not supported");
    }

    default void createOrAddSpace(Connect connect, SpaceMutationRequest request) throws Exception {
        throw new UnsupportedOperationException("Space mutation is not supported");
    }

    default void abortCreateOrAddSpace(Connect connect) throws Exception {
        throw new UnsupportedOperationException("Space mutation abort is not supported");
    }

    default void dropSpace(Connect connect, String spaceName, List<String> datafilePaths) throws Exception {
        throw new UnsupportedOperationException("Drop space is not supported");
    }

    default void dropDatafile(Connect connect, String spaceName, String datafilePath) throws Exception {
        throw new UnsupportedOperationException("Drop datafile is not supported");
    }

    default SpaceLabels spaceLabels(Connect connect) {
        return informixGbaseDefaultSpaceLabels();
    }

    record ConfigEntry(String name, String value) {
    }

    record CheckColumn(String key, String titleI18nKey, String title, CheckColumnKind kind, double prefWidth) {
    }

    enum CheckColumnKind {
        TEXT,
        STATUS
    }

    record CheckRow(Map<String, String> values,
                    Map<String, String> valueI18nKeys,
                    String cmd,
                    String cmdOutput,
                    boolean openLogOnDoubleClick) {
        public String value(String key) {
            if (values == null || key == null) {
                return "";
            }
            String value = values.get(key);
            return value == null ? "" : value;
        }

        public String valueI18nKey(String key) {
            if (valueI18nKeys == null || key == null) {
                return "";
            }
            String value = valueI18nKeys.get(key);
            return value == null ? "" : value;
        }
    }

    record CheckTableModel(List<CheckColumn> columns, List<CheckRow> rows) {
    }

    record SpaceMutationRequest(boolean addFile,
                                String spaceName,
                                String filePath,
                                String sizeKb,
                                String pageSizeKb,
                                SpaceType spaceType,
                                String adminOsUser) {
    }

    record ConfigUpdateResult(ConfigUpdateStatus status, String message) {
    }

    enum ConfigUpdateStatus {
        APPLIED,
        FILE_ONLY,
        RESTART_REQUIRED
    }

    enum SpaceType {
        STANDARD,
        TEMP,
        BLOB
    }

    /**
     * @param unusedBarLabelI18nKey 非空时：图例中灰色条与 tooltip 中「总减已用」一行均使用该 key（如 Oracle 用「已分配未使用」）；
     *                              空串时使用通用 {@code space.legend.allocated_unused} / {@code space.tooltip.unused}。
     */
    record SpaceLabels(String legendI18nKey,
                       String legendText,
                       String dbspaceTitleI18nKey,
                       String dbspaceTitle,
                       String chunkTitleI18nKey,
                       String chunkTitle,
                       String databaseTitleI18nKey,
                       String databaseTitle,
                       String tableTitleI18nKey,
                       String tableTitle,
                       String unusedBarLabelI18nKey,
                       String unusedBarLabelFallback) {
    }

    /**
     * Informix / GBase 8s 等共用的容量管理图标题与类型图例（与 Oracle 区分：无「已分配未使用」覆盖键）。
     */
    static SpaceLabels informixGbaseDefaultSpaceLabels() {
        return new SpaceLabels(
                "instance.space.type.legend",
                "[T] 临时空间   [S] 智能大对象空间   [B] 简单大对象空间   [L] 空间大小已限制   [*k] 空间页大小为*KB",
                "instance.space.chart.dbspace",
                "数据库空间使用情况图(GB)",
                "instance.space.chart.chunk",
                "数据文件使用情况图(GB)",
                "instance.space.chart.database",
                "数据库使用空间情况(GB)",
                "instance.space.chart.table",
                "表/索引空间使用情况图TOP20(GB)",
                "",
                ""
        );
    }

    static String extractInfoValue(String info, String key) {
        if (info == null || info.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s+(.+?)\\s*$").matcher(info);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim();
    }

    static String resolveDriverProps(Connect connect) {
        StringBuilder props = new StringBuilder();
        JSONArray jsonArray = new JSONArray(connect.getProps());
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            if (jsonObject.getString("propValue") != null && !jsonObject.getString("propValue").isEmpty()) {
                props.append(jsonObject.getString("propName")).append("=")
                        .append(jsonObject.getString("propValue")).append(";");
            }
        }
        return props.toString();
    }

}
