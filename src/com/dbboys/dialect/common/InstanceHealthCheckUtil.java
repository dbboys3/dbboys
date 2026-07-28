package com.dbboys.dialect.common;
import com.dbboys.infra.ssh.SshUtil;

import com.dbboys.service.AdminService;
import com.dbboys.model.Connect;
import com.dbboys.model.HealthCheck;
import org.apache.sshd.client.session.ClientSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstanceHealthCheckUtil {
    private static final Logger log = LogManager.getLogger(InstanceHealthCheckUtil.class);

    private InstanceHealthCheckUtil() {
    }

    public static List<HealthCheck> loadInformixStyleHealthChecks(Connect connect,
                                                                  String versionExpectation,
                                                                  String runtimeLogCommand) {
        List<HealthCheck> result = new ArrayList<>();
        String instanceStatus = "";
        String onstat_g_osi = "";
        String onstat_g_osi_machine = "";
        String onstat_g_osi_cpu = "";
        String onstat_g_osi_mem = "";
        String onstat_V = "";
        String onstat_ = "";
        String onstat_g_seg_greped = "";
        String onstat_g_seg = "";
        String onstat_g_cluster = "";
        String onstat_l = "";
        String onstat_l_llog = "";
        String onstat_d_greped = "";
        String onstat_d = "";
        double spaceTopPercent = 0;
        String onstat_g_arc_greped = "onstat_g_arc_greped";
        String onstat_g_arc = "";
        String onstat_m = "";
        String onstat_g_sql_greped = "";
        String onstat_g_sql = "";
        String onstat_g_act_greped = "";
        String onstat_g_act = "";
        String onstat_g_rea_greped = "";
        String onstat_g_rea = "";
        String onstat_g_wai = "";
        String onstat_g_wai_logio = "";
        String onstat_g_wai_lockwait = "";
        String onstat_g_wai_bufwait = "";
        String onstat_g_wai_iowait = "";
        String onstat_x_greped = "";
        String onstat_x = "";
        String onstat_p = "";
        String onstat_p_greped = "";
        String onstat_p_deadlks = "";
        try {
            ClientSession session = SshUtil.getConnect(connect);
            String env = SshUtil.extractEnvValue(connect.getInfo());
            onstat_g_osi = SshUtil.executeCommand(session, env + "onstat -g osi");
            onstat_g_osi_machine = SshUtil.executeCommand(session, env + "onstat -g osi |awk '/OS Machine/ {print $3}'");
            onstat_g_osi_cpu = SshUtil.executeCommand(session, env + "onstat -g osi |awk '/Number of online processors/{print $5}'");
            onstat_g_osi_mem = SshUtil.executeCommand(session, env + "onstat -g osi |grep -v 'System memory page size' |awk '/System memory/{print $3$4}'");
            onstat_V = SshUtil.executeCommand(session, env + "onstat -V");
            onstat_ = SshUtil.executeCommand(session, env + "onstat -||true", true);
            onstat_g_seg_greped = SshUtil.executeCommand(session, env + "onstat -g seg|grep -c ' V '");
            onstat_g_seg = SshUtil.executeCommand(session, env + "onstat -g seg");
            onstat_g_cluster = SshUtil.executeCommand(session, env + "onstat -g cluster||true");
            onstat_l = SshUtil.executeCommand(session, env + "onstat -l");
            onstat_l_llog = SshUtil.executeCommand(session, env + "onstat -l |grep -c 'U------'||true");
            onstat_d_greped = SshUtil.executeCommand(session, env + "onstat -d|grep -c PD||true");
            onstat_d = SshUtil.executeCommand(session, env + "onstat -d");
            spaceTopPercent = new AdminService().getMaxStorageSpaceUsage(connect);
            onstat_g_arc_greped = SshUtil.executeCommand(session, env + "onstat -g arc |grep -A 1 ' level ' |sed -n '2p' |awk '{print $4}'");
            onstat_g_arc = SshUtil.executeCommand(session, env + "onstat -g arc");
            onstat_m = SshUtil.executeCommand(session, env + "onstat -c |awk '/^MSGPATH/ {print \"tail -1000 \"$2}' |sh|egrep -ic 'err|failed|warning|allocated|full|long|down|Died|Aborting|Abort'");
            onstat_g_sql_greped = SshUtil.executeCommand(session, env + "onstat -g sql |egrep -v 'On-Line|Read-Only|Current|Database|^$' |wc -l");
            onstat_g_sql = SshUtil.executeCommand(session, env + "onstat -g sql");
            onstat_g_act_greped = SshUtil.executeCommand(session, env + "onstat -g act |grep -v soctcppoll |grep -v '^$'|wc -l");
            onstat_g_act = SshUtil.executeCommand(session, env + "onstat -g act");
            onstat_g_rea_greped = SshUtil.executeCommand(session, env + "onstat -g rea|grep -v '^$'| wc -l");
            onstat_g_rea = SshUtil.executeCommand(session, env + "onstat -g rea");
            onstat_g_wai = SshUtil.executeCommand(session, env + "onstat -g wai");
            onstat_g_wai_logio = SshUtil.executeCommand(session, env + "onstat -g wai|grep -c 'logio cond'||true");
            onstat_g_wai_lockwait = SshUtil.executeCommand(session, env + "onstat -g wai|grep -c 'yield lockwait'||true");
            onstat_g_wai_bufwait = SshUtil.executeCommand(session, env + "onstat -g wai|grep -c 'yield bufwait'||true");
            onstat_g_wai_iowait = SshUtil.executeCommand(session, env + "onstat -g wai|grep -c 'IO Wait'||true");
            onstat_x_greped = SshUtil.executeCommand(session, env + "onstat -x |grep -v '^$' |grep -v ' - ' |wc -l");
            onstat_x = SshUtil.executeCommand(session, env + "onstat -x");
            onstat_p = SshUtil.executeCommand(session, env + "onstat -p");
            onstat_p_greped = SshUtil.executeCommand(session, env + "onstat -p |grep -A 1 rewrite |sed -n '2p' |awk '{print $4\" \"$5\" \"$6\" \"$7\" \"$8\" \"$9}'");
            onstat_p_deadlks = SshUtil.executeCommand(session, env + "onstat -p |grep -A 1 deadlks |sed -n '2p' |awk '{print $4}'");
            SshUtil.disConnect(session);
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        String currentValue;
        String status;
        currentValue = "实例状态异常";
        status = "2";
        if (!onstat_g_osi_machine.isEmpty()) {
            currentValue = onstat_g_osi_machine;
            status = "0";
        }
        add(result, "系统架构", "onstat -g osi", "x86_64/aarch64", currentValue, status, onstat_g_osi);

        currentValue = "实例状态异常";
        status = "2";
        if (!onstat_g_osi_cpu.isEmpty()) {
            currentValue = onstat_g_osi_cpu;
            status = "0";
        }
        add(result, "CPU数量", "onstat -g osi", "1核心以上", currentValue, status, onstat_g_osi);

        currentValue = "实例状态异常";
        status = "2";
        if (!onstat_g_osi_mem.isEmpty()) {
            currentValue = onstat_g_osi_mem;
            status = "0";
        }
        add(result, "内存大小", "onstat -g osi", "2GB以上", currentValue, status, onstat_g_osi);

        currentValue = "实例状态异常";
        status = "2";
        if (!onstat_V.isEmpty()) {
            currentValue = onstat_V;
            status = "0";
        }
        add(result, "数据库版本", "onstat -V", versionExpectation, currentValue, status, onstat_V);

        currentValue = "实例状态异常";
        status = "2";
        if (!onstat_.isEmpty()) {
            if (onstat_.contains("Your evaluation license")) {
                currentValue = onstat_.split("Your evaluation license will expire on ")[1];
                status = "1";
            } else {
                currentValue = "Permanent";
                status = "0";
            }
        }
        add(result, "软件授权有效期", "onstat -", "永久", currentValue, status, onstat_);

        String strictRegex = "((?:On-Line|Read-Only)(?:\\s+\\(.*\\))?)\\s+--";
        Pattern strictPattern = Pattern.compile(strictRegex);
        Matcher strictMatcher = strictPattern.matcher(onstat_);
        currentValue = "实例状态异常";
        status = "2";
        if (!onstat_.isEmpty()) {
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
                status = "0";
            } else {
                currentValue = "Off-Line";
                instanceStatus = "Off-Line";
            }
        }
        add(result, "实例状态", "onstat -", "主节点或单机On-Line，集群备机Read-Only", currentValue, status, onstat_);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_, instanceStatus)) {
            strictMatcher = Pattern.compile("(Blocked:.*)").matcher(onstat_);
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
            } else {
                currentValue = "Not Blocked";
                status = "0";
            }
        }
        add(result, "实例是否BLOCKED", "onstat -", "正常无Blocked:显示", currentValue, status, onstat_);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_, instanceStatus)) {
            strictMatcher = Pattern.compile("--\\s+Up\\s+(.*)\\s+--").matcher(onstat_);
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
                status = "0";
            }
        }
        add(result, "实例运行天数", "onstat -", "xxx Days", currentValue, status, onstat_);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_, instanceStatus)) {
            strictMatcher = Pattern.compile("--\\s+([0-9]*\\s+Kbytes)").matcher(onstat_);
            if (strictMatcher.find()) {
                currentValue = strictMatcher.group(1);
                status = "0";
            }
        }
        add(result, "实例内存总量", "onstat -", "xxx Kbytes", currentValue, status, onstat_);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_seg_greped, instanceStatus)) {
            currentValue = onstat_g_seg_greped;
            int segments = parseIntOrDefault(currentValue, 0);
            status = segments > 3 ? "1" : "0";
        }
        add(result, "实例内存段数量", "onstat -g seg", "V段不超过3个", currentValue, status, onstat_g_seg);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_cluster, instanceStatus)) {
            currentValue = onstat_g_cluster;
            if (currentValue.contains("Disconnected")) {
                currentValue = "Disconnected";
            } else if (currentValue.contains("Connected")) {
                currentValue = "Connected";
                status = "0";
            } else {
                currentValue = "Not Clustered";
                status = "0";
            }
        }
        add(result, "实例集群状态", "onstat -g cluster", "无集群或Connected", currentValue, status, onstat_g_cluster);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_l, instanceStatus)) {
            String plogsize = onstat_l.split("Physical Logging")[1].split("Logical Logging")[0];
            strictMatcher = Pattern.compile("^\\s*\\d+:\\d+\\s+(\\d+)").matcher(plogsize.split("\n")[4]);
            if (strictMatcher.find()) {
                int psize = parseIntOrDefault(strictMatcher.group(1), 0) * 2;
                currentValue = psize + "k";
                status = psize < 512000 * 2 ? "1" : "0";
            }
        }
        add(result, "实例物理日志", "onstat -l", "physize不小于1G", currentValue, status, onstat_l);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_l_llog, instanceStatus)) {
            currentValue = onstat_l_llog;
            status = parseIntOrDefault(onstat_l_llog, 0) > 0 ? "1" : "0";
        }
        add(result, "实例逻辑日志", "onstat -l", "U------状态日志为0", currentValue, status, onstat_l);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_d_greped, instanceStatus)) {
            currentValue = onstat_d_greped;
            status = parseIntOrDefault(currentValue, 0) > 0 ? "2" : "0";
        }
        add(result, "实例空间状态", "onstat -d", "无PD状态", currentValue, status, onstat_d);

        currentValue = "实例状态异常";
        status = "2";
        if (spaceTopPercent != 0) {
            currentValue = String.valueOf(spaceTopPercent) + "%";
            status = "0";
            if (spaceTopPercent > 80) {
                status = "1";
            }
            if (spaceTopPercent > 90) {
                status = "2";
            }
        }
        add(result, "实例空间使用率", "onstat -d", "使用率小于80%", currentValue, status, onstat_d);

        currentValue = "实例状态异常";
        status = "2";
        if (!onstat_g_arc_greped.equals("onstat_g_arc_greped") && !instanceStatus.contains("Off-Line")) {
            currentValue = onstat_g_arc_greped;
            boolean isOver1Day = false;
            if (!currentValue.isEmpty()) {
                try {
                    Date date = new SimpleDateFormat("MM/dd/yyyy.HH:mm").parse(currentValue);
                    long timeDiffMs = new Date().getTime() - date.getTime();
                    isOver1Day = timeDiffMs > 24 * 60 * 60 * 1000L;
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            }
            status = "0";
            if (isOver1Day || currentValue.isEmpty()) {
                status = "1";
            }
            if (currentValue.isEmpty()) {
                currentValue = "Never Archived";
            }
        }
        add(result, "实例空间备份", "onstat -g arc", "最后一次备份时间在24小时内", currentValue, status, onstat_g_arc);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_m, instanceStatus)) {
            currentValue = onstat_m;
            status = parseIntOrDefault(onstat_m, 0) > 0 ? "1" : "0";
        }
        add(result, "实例运行日志", runtimeLogCommand, "err、failed关键字数量为0", currentValue, status, onstat_m);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_sql_greped, instanceStatus)) {
            currentValue = onstat_g_sql_greped;
            int totalConnectionCount = parseIntOrDefault(currentValue, 0);
            status = totalConnectionCount >= 10000 ? "1" : "0";
            currentValue = String.valueOf(totalConnectionCount);
        }
        add(result, "实例总连接数", "onstat -g sql", "<10000", currentValue, status, onstat_g_sql);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_act_greped, instanceStatus)) {
            currentValue = onstat_g_act_greped;
            int activeConnectionCount = parseIntOrDefault(currentValue, 0);
            status = activeConnectionCount >= 1003 ? "1" : "0";
            currentValue = String.valueOf(activeConnectionCount - 3);
        }
        add(result, "实例活动连接数", "onstat -g act", "<1000", currentValue, status, onstat_g_act);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_rea_greped, instanceStatus)) {
            currentValue = onstat_g_rea_greped;
            int queueCount = parseIntOrDefault(currentValue, 0);
            status = queueCount > 3 ? "1" : "0";
            currentValue = String.valueOf(queueCount - 3);
        }
        add(result, "实例队列数量", "onstat -g rea", "0或少量", currentValue, status, onstat_g_rea);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_wai_logio, instanceStatus)) {
            currentValue = onstat_g_wai_logio;
            status = parseIntOrDefault(currentValue, 0) > 0 ? "1" : "0";
        }
        add(result, "实例逻辑日志等待logio cond", "onstat -g wai", "0或少量", currentValue, status, onstat_g_wai);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_wai_lockwait, instanceStatus)) {
            currentValue = onstat_g_wai_lockwait;
            status = parseIntOrDefault(currentValue, 0) > 0 ? "1" : "0";
        }
        add(result, "实例锁等待yield lockwait", "onstat -g wai", "0或少量", currentValue, status, onstat_g_wai);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_wai_bufwait, instanceStatus)) {
            currentValue = onstat_g_wai_bufwait;
            status = parseIntOrDefault(currentValue, 0) > 0 ? "1" : "0";
        }
        add(result, "实例buf等待yield bufwait", "onstat -g wai", "0或少量", currentValue, status, onstat_g_wai);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_g_wai_iowait, instanceStatus)) {
            currentValue = onstat_g_wai_iowait;
            status = parseIntOrDefault(currentValue, 0) > 0 ? "1" : "0";
        }
        add(result, "实例IO等待IO Wait", "onstat -g wai", "0或少量", currentValue, status, onstat_g_wai);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_x_greped, instanceStatus)) {
            currentValue = String.valueOf(parseIntOrDefault(onstat_x_greped, 0) - 5);
            status = "0";
        }
        add(result, "实例打开未提交事务数", "onstat -x", "少量", currentValue, status, onstat_x);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_p_greped, instanceStatus)) {
            currentValue = tokenAt(onstat_p_greped, 4, currentValue);
            status = "0";
        }
        add(result, "实例已提交事务数", "onstat -p", "业务繁忙度决定", currentValue, status, onstat_p);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_p_greped, instanceStatus)) {
            currentValue = tokenAt(onstat_p_greped, 5, currentValue);
            status = "0";
        }
        add(result, "实例回滚事务数", "onstat -p", "少量", currentValue, status, onstat_p);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_p_greped, instanceStatus)) {
            currentValue = tokenAt(onstat_p_greped, 1, currentValue);
            status = "0";
        }
        add(result, "实例插入数量", "onstat -p", "业务繁忙度决定", currentValue, status, onstat_p);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_p_greped, instanceStatus)) {
            currentValue = tokenAt(onstat_p_greped, 2, currentValue);
            status = "0";
        }
        add(result, "实例更新数量", "onstat -p", "业务繁忙度决定", currentValue, status, onstat_p);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_p_greped, instanceStatus)) {
            currentValue = tokenAt(onstat_p_greped, 3, currentValue);
            status = "0";
        }
        add(result, "实例删除数量", "onstat -p", "业务繁忙度决定", currentValue, status, onstat_p);

        currentValue = "实例状态异常";
        status = "2";
        if (canEvaluateCheck(onstat_p_deadlks, instanceStatus)) {
            currentValue = onstat_p_deadlks;
            status = "0";
        }
        add(result, "实例死锁数量", "onstat -p", "业务逻辑决定", currentValue, status, onstat_p);
        return result;
    }

    private static void add(List<HealthCheck> datalist,
                            String entry,
                            String cmd,
                            String expectedValue,
                            String currentValue,
                            String status,
                            String cmdOutput) {
        datalist.add(new HealthCheck(entry, cmd, expectedValue, currentValue, status, cmdOutput));
    }

    private static boolean canEvaluateCheck(String value, String instanceStatus) {
        return value != null && !value.isEmpty() && instanceStatus != null && !instanceStatus.contains("Off-Line");
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String tokenAt(String value, int index, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String[] tokens = value.trim().split("\\s+");
        if (index < 0 || index >= tokens.length) {
            return defaultValue;
        }
        return tokens[index];
    }
}
