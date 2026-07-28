package com.dbboys.infra.util;

import com.dbboys.core.ConnectionService;
import com.dbboys.core.InstanceTabCapability;
import com.dbboys.app.AppContext;
import com.dbboys.model.Connect;
import org.apache.sshd.client.session.ClientSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InstanceRuntimeUtil {
    private static final String SQL_ORACLE_PARAMETERS = """
            select name, nvl(display_value, value) as value
            from v$parameter
            where issys_modifiable in ('IMMEDIATE', 'DEFERRED')
            order by name
            """;
    private static final String SQL_ORACLE_ALERT_LOG = """
            select line from (
                select to_char(originating_timestamp, 'YYYY-MM-DD HH24:MI:SS') || ' ' || message_text as line
                from v$diag_alert_ext
                where message_text is not null
                order by originating_timestamp desc
            )
            where rownum <= 500
            """;
    private static final String SQL_ORACLE_DIAG_TRACE = """
            select value
            from v$diag_info
            where name = 'Diag Trace'
            """;
    private static final String SQL_ORACLE_INSTANCE_STATUS = """
            select status
            from v$instance
            """;

    private InstanceRuntimeUtil() {
    }

    public static String loadInformixStyleRuntimeLog(Connect connect) throws Exception {
        ClientSession session = SshUtil.getConnect(connect);
        try {
            return SshUtil.executeCommand(
                    session,
                    SshUtil.extractEnvValue(connect.getInfo()) + "onstat -c |awk '/^MSGPATH/ {print \"tail -1000 \"$2}' |sh"
            );
        } finally {
            SshUtil.disConnect(session);
        }
    }

    public static List<InstanceTabCapability.ConfigEntry> loadInformixStyleConfigEntries(Connect connect) throws Exception {
        ClientSession session = SshUtil.getConnect(connect);
        try {
            String config = SshUtil.executeCommand(
                    session,
                    SshUtil.extractEnvValue(connect.getInfo()) + "onstat -c |grep -v '^$' |grep -v '^#' |sed '1,2d'"
            );
            return parseConfigEntries(config);
        } finally {
            SshUtil.disConnect(session);
        }
    }

    public static boolean isInformixStyleInstanceOnline(Connect connect) throws Exception {
        ClientSession session = SshUtil.getConnect(connect);
        try {
            String instanceStatus = SshUtil.executeCommand(session, SshUtil.extractEnvValue(connect.getInfo()) + "onstat -||true");
            return instanceStatus.contains("On-Line") || instanceStatus.contains("Read-Only");
        } finally {
            SshUtil.disConnect(session);
        }
    }

    public static List<InstanceTabCapability.ConfigEntry> loadOracleConfigEntries(Connect connect) throws Exception {
        ConnectionService connectionService = AppContext.get(ConnectionService.class);
        try (Connection conn = connectionService.getConnectionWithSessionInit(connect);
             PreparedStatement pstmt = conn.prepareStatement(SQL_ORACLE_PARAMETERS);
             ResultSet rs = pstmt.executeQuery()) {
            List<InstanceTabCapability.ConfigEntry> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new InstanceTabCapability.ConfigEntry(
                        rs.getString("name"),
                        rs.getString("value")
                ));
            }
            return result;
        }
    }

    public static String loadOracleRuntimeLog(Connect connect) throws Exception {
        ConnectionService connectionService = AppContext.get(ConnectionService.class);
        try (Connection conn = connectionService.getConnectionWithSessionInit(connect)) {
            List<String> lines = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(SQL_ORACLE_ALERT_LOG);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null && !line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
            if (!lines.isEmpty()) {
                Collections.reverse(lines);
                return String.join("\n", lines);
            }

            try (PreparedStatement pstmt = conn.prepareStatement(SQL_ORACLE_DIAG_TRACE);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String tracePath = rs.getString(1);
                    if (tracePath != null && !tracePath.isBlank()) {
                        return "Diag Trace Path: " + tracePath.trim();
                    }
                }
            }
            return "";
        }
    }

    public static boolean isOracleInstanceOnline(Connect connect) throws Exception {
        ConnectionService connectionService = AppContext.get(ConnectionService.class);
        try (Connection conn = connectionService.getConnectionWithSessionInit(connect);
             PreparedStatement pstmt = conn.prepareStatement(SQL_ORACLE_INSTANCE_STATUS);
             ResultSet rs = pstmt.executeQuery()) {
            if (!rs.next()) {
                return false;
            }
            String status = rs.getString(1);
            return status != null && !status.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<InstanceTabCapability.ConfigEntry> parseConfigEntries(String rawData) {
        List<InstanceTabCapability.ConfigEntry> result = new ArrayList<>();
        String[] lines = rawData.replaceAll("\r\n", "\n").split("\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }
            String[] parts = trimmedLine.split("\\s+", 2);
            String paramName = parts[0];
            String paramValue = parts.length > 1 ? parts[1] : "";
            result.add(new InstanceTabCapability.ConfigEntry(paramName, paramValue));
        }
        return result;
    }
}
