package com.dbboys.util;

import com.dbboys.api.ConnectionService;
import com.dbboys.api.InstanceTabCapability;
import com.dbboys.app.AppContext;
import com.dbboys.vo.Connect;
import com.jcraft.jsch.Session;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class InstanceMutationUtil {
    private InstanceMutationUtil() {
    }

    public static InstanceTabCapability.ConfigUpdateResult updateInformixStyleConfig(Connect connect,
                                                                                     String installDirEnvName,
                                                                                     String paramName,
                                                                                     String newValue) throws Exception {
        String installDirEnv = "$" + installDirEnvName;
        String cmd;
        if ("BUFFERPOOL".equals(paramName) || "VPCLASS".equals(paramName)) {
            cmd = "sed -i \"s#^" + paramName + " *" + newValue.split(",")[0] + ".*#" + paramName + " "
                    + newValue.replace("$", "\\$") + "#g\" " + installDirEnv + "/etc/$ONCONFIG";
        } else {
            cmd = "onmode -wf " + paramName + "=\"" + newValue + "\";sed -i \"s#^" + paramName + ".*#" + paramName
                    + " " + newValue.replace("$", "\\$") + "#g\" " + installDirEnv + "/etc/$ONCONFIG";
        }

        Session session = JschUtil.getConnect(connect);
        try {
            String result = JschUtil.executeCommand(session, JschUtil.extractEnvValue(connect.getInfo()) + cmd, true);
            if (result.contains("has been changed to")) {
                return new InstanceTabCapability.ConfigUpdateResult(
                        InstanceTabCapability.ConfigUpdateStatus.APPLIED,
                        "参数已修改生效！"
                );
            }
            if (result.contains("shared memory not initialized")) {
                return new InstanceTabCapability.ConfigUpdateResult(
                        InstanceTabCapability.ConfigUpdateStatus.FILE_ONLY,
                        "配置文件已修改，数据库未启动，下次启动后生效！"
                );
            }
            return new InstanceTabCapability.ConfigUpdateResult(
                    InstanceTabCapability.ConfigUpdateStatus.RESTART_REQUIRED,
                    "参数已修改，请重启数据库生效！"
            );
        } finally {
            JschUtil.disConnect(session);
        }
    }

    public static void startInformixStyleInstance(Connect connect) throws Exception {
        Session session = JschUtil.getConnect(connect);
        try {
            int result = JschUtil.executeCommandWithExitStatus(session, JschUtil.extractEnvValue(connect.getInfo()) + "oninit");
            if (result != 0) {
                throw new Exception("启动数据库失败，请检查日志错误！");
            }
        } finally {
            JschUtil.disConnect(session);
        }
    }

    public static void stopInformixStyleInstance(Connect connect) throws Exception {
        Session session = JschUtil.getConnect(connect);
        try {
            int result = JschUtil.executeCommandWithExitStatus(session, JschUtil.extractEnvValue(connect.getInfo()) + "onmode -ky&&onclean -ky");
            if (result != 0) {
                throw new Exception("关闭数据库失败，请检查日志错误！");
            }
        } finally {
            JschUtil.disConnect(session);
        }
    }

    public static void createOrAddInformixStyleSpace(Connect connect,
                                                     InstanceTabCapability.SpaceMutationRequest request) throws Exception {
        String cmd;
        if (request.addFile()) {
            cmd = "onspaces -a " + request.spaceName() + " -p " + request.filePath() + " -o 0 -s " + request.sizeKb();
        } else {
            cmd = switch (request.spaceType()) {
                case STANDARD -> "onspaces -c -d " + request.spaceName() + " -p " + request.filePath()
                        + " -o 0 -s " + request.sizeKb() + " -k " + request.pageSizeKb();
                case TEMP -> "onspaces -c -d " + request.spaceName() + " -p " + request.filePath()
                        + " -o 0 -s " + request.sizeKb() + " -k " + request.pageSizeKb() + " -t";
                case BLOB -> "onspaces -c -S " + request.spaceName() + " -p " + request.filePath()
                        + " -o 0 -s " + request.sizeKb() + " -Df \"LOGGING = ON, AVG_LO_SIZE=1\"";
            };
        }
        cmd = "touch " + request.filePath()
                + "&&chown " + request.adminOsUser() + ":" + request.adminOsUser() + " " + request.filePath()
                + "&&chmod 660 " + request.filePath()
                + "&&" + cmd;
        Session session = JschUtil.getConnect(connect);
        try {
            String result = JschUtil.executeCommand(session, JschUtil.extractEnvValue(connect.getInfo()) + cmd);
            if (!(result.contains("Space successfully added") || result.contains("Chunk successfully added"))) {
                throw new Exception(result);
            }
        } finally {
            JschUtil.disConnect(session);
        }
    }

    public static void abortCreateOrAddInformixStyleSpace(Connect connect) throws Exception {
        Session session = JschUtil.getConnect(connect);
        try {
            int result = JschUtil.executeCommandWithExitStatus(session, "ps -ef |grep onspaces|grep -v grep |awk '{print \"kill -9 \"$2}' |sh ");
            if (result != 0) {
                throw new Exception("停止创建空间失败！");
            }
        } finally {
            JschUtil.disConnect(session);
        }
    }

    public static void dropInformixStyleSpace(Connect connect,
                                              String spaceName,
                                              List<String> datafilePaths) throws Exception {
        StringBuilder cmd = new StringBuilder("onspaces -d ").append(spaceName).append(" -y ");
        if (datafilePaths != null) {
            for (String path : datafilePaths) {
                cmd.append("&& rm -rf ").append(path);
            }
        }
        Session session = JschUtil.getConnect(connect);
        try {
            String result = JschUtil.executeCommand(session, JschUtil.extractEnvValue(connect.getInfo()) + cmd);
            if (!result.contains("Space successfully dropped")) {
                throw new Exception(result);
            }
        } finally {
            JschUtil.disConnect(session);
        }
    }

    public static void dropInformixStyleDatafile(Connect connect,
                                                 String spaceName,
                                                 String datafilePath) throws Exception {
        String cmd = "onspaces -d " + spaceName + " -p " + datafilePath + " -o 0 -y&& rm -rf " + datafilePath;
        Session session = JschUtil.getConnect(connect);
        try {
            String result = JschUtil.executeCommand(session, JschUtil.extractEnvValue(connect.getInfo()) + cmd);
            if (!result.contains("Chunk successfully dropped")) {
                throw new Exception(result);
            }
        } finally {
            JschUtil.disConnect(session);
        }
    }

    public static InstanceTabCapability.ConfigUpdateResult updateOracleConfig(Connect connect,
                                                                              String paramName,
                                                                              String newValue) throws Exception {
        ConnectionService connectionService = AppContext.get(ConnectionService.class);
        String sql = "alter system set " + paramName + " = " + toOracleAlterSystemLiteral(newValue) + " scope=both sid='*'";
        try (Connection conn = connectionService.getConnectionWithSessionInit(connect);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return new InstanceTabCapability.ConfigUpdateResult(
                    InstanceTabCapability.ConfigUpdateStatus.APPLIED,
                    "参数已修改生效"
            );
        }
    }

    public static void startOracleInstance(Connect connect) throws Exception {
        Connect prelimConnect = cloneConnectWithOracleProp(connect, "prelim_auth", "true");
        prelimConnect = cloneConnectWithOracleProp(prelimConnect, "internal_logon", "sysdba");
        try (Connection prelimConn = AppContext.get(ConnectionService.class).createConnection(prelimConnect)) {
            Object oracleConnection = unwrapOracleConnection(prelimConn);
            Class<?> startupModeClass = loadOracleClass(oracleConnection, "oracle.jdbc.OracleConnection$DatabaseStartupMode");
            Object noRestriction = Enum.valueOf((Class<Enum>) startupModeClass.asSubclass(Enum.class), "NO_RESTRICTION");
            Method startup = oracleConnection.getClass().getMethod("startup", startupModeClass);
            startup.invoke(oracleConnection, noRestriction);
        }

        Connect sysdbaConnect = cloneConnectWithOracleProp(connect, "internal_logon", "sysdba");
        try (Connection conn = AppContext.get(ConnectionService.class).createConnection(sysdbaConnect);
             Statement stmt = conn.createStatement()) {
            stmt.execute("alter database mount");
            stmt.execute("alter database open");
        }
    }

    public static void stopOracleInstance(Connect connect) throws Exception {
        Connect sysdbaConnect = cloneConnectWithOracleProp(connect, "internal_logon", "sysdba");
        try (Connection conn = AppContext.get(ConnectionService.class).createConnection(sysdbaConnect);
             Statement stmt = conn.createStatement()) {
            Object oracleConnection = unwrapOracleConnection(conn);
            Class<?> shutdownModeClass = loadOracleClass(oracleConnection, "oracle.jdbc.OracleConnection$DatabaseShutdownMode");
            Object immediate = Enum.valueOf((Class<Enum>) shutdownModeClass.asSubclass(Enum.class), "IMMEDIATE");
            Object finalMode = Enum.valueOf((Class<Enum>) shutdownModeClass.asSubclass(Enum.class), "FINAL");
            Method shutdown = oracleConnection.getClass().getMethod("shutdown", shutdownModeClass);
            shutdown.invoke(oracleConnection, immediate);
            stmt.execute("alter database close normal");
            stmt.execute("alter database dismount");
            shutdown.invoke(oracleConnection, finalMode);
        }
    }

    private static Connect cloneConnectWithOracleProp(Connect connect, String propName, String propValue) {
        Connect clone = new Connect(connect);
        ConnectionService connectionService = AppContext.get(ConnectionService.class);
        clone.setProps(connectionService.modifyProps(clone, propName, propValue));
        return clone;
    }

    private static Object unwrapOracleConnection(Connection conn) throws Exception {
        Class<?> oracleConnectionClass = Class.forName("oracle.jdbc.OracleConnection", true, conn.getClass().getClassLoader());
        if (oracleConnectionClass.isInstance(conn)) {
            return conn;
        }
        return conn.unwrap((Class<?>) oracleConnectionClass);
    }

    private static Class<?> loadOracleClass(Object oracleConnection, String className) throws ClassNotFoundException {
        return Class.forName(className, true, oracleConnection.getClass().getClassLoader());
    }

    private static String toOracleAlterSystemLiteral(String value) {
        if (value == null) {
            return "''";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "''";
        }
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            return trimmed;
        }
        if (trimmed.matches("^[+-]?\\d+(\\.\\d+)?([KMGTP]?)$")) {
            return trimmed;
        }
        if (trimmed.matches("(?i)^(true|false|immediate|deferred|memory|spfile|both)$")) {
            return trimmed.toUpperCase();
        }
        return "'" + trimmed.replace("'", "''") + "'";
    }

    /**
     * 不允许 DROP 的 Oracle 表空间（系统/撤销/常见临时名）。
     */
    public static boolean isOracleProtectedTablespace(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String u = name.trim().toUpperCase(Locale.ROOT);
        if ("SYSTEM".equals(u) || "SYSAUX".equals(u)) {
            return true;
        }
        if (u.startsWith("UNDO")) {
            return true;
        }
        return "TEMP".equals(u);
    }

    private static void assertOracleTablespaceIdentifier(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Tablespace name is required");
        }
        String name = rawName.trim();
        if (!name.matches("^[A-Za-z][A-Za-z0-9_$#]{0,127}$")) {
            throw new IllegalArgumentException("Invalid tablespace name (use letters, digits, _, $, # only)");
        }
    }

    private static void assertOracleDatafilePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Datafile path is required");
        }
        if (path.contains(";") || path.contains("--") || path.contains("\n") || path.contains("\r")) {
            throw new IllegalArgumentException("Invalid datafile path");
        }
    }

    /**
     * 若用户在执行期间调用了 {@link Statement#cancel()}，Oracle 常见为 ORA-01013。
     */
    public static boolean isLikelyOracleStatementCancelled(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException sqlEx) {
                if (sqlEx.getErrorCode() == 1013) {
                    return true;
                }
                String msg = sqlEx.getMessage();
                if (msg != null) {
                    String u = msg.toUpperCase(Locale.ROOT);
                    if (u.contains("ORA-01013") || u.contains("01013")) {
                        return true;
                    }
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * @param cancellableStatement 非空时，在 {@code execute} 前设为当前 {@link Statement}，结束后清空，供 UI 调用 {@link Statement#cancel()}。
     */
    private static void executeOracleSpaceDdl(Connect connect, String sql,
                                              AtomicReference<Statement> cancellableStatement) throws Exception {
        ConnectionService connectionService = AppContext.get(ConnectionService.class);
        Connection conn = connectionService.getConnectionWithSessionInit(connect);
        try {
            Statement stmt = conn.createStatement();
            try {
                if (cancellableStatement != null) {
                    cancellableStatement.set(stmt);
                }
                stmt.execute(sql);
                if (!conn.getAutoCommit()) {
                    conn.commit();
                }
            } finally {
                if (cancellableStatement != null) {
                    cancellableStatement.compareAndSet(stmt, null);
                }
                try {
                    stmt.close();
                } catch (Exception ignored) {
                }
            }
        } finally {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 创建永久表空间：一个数据文件，可选 AUTOEXTEND NEXT 10M MAXSIZE UNLIMITED。
     */
    public static void createOracleTablespace(Connect connect,
                                              String tablespaceName,
                                              String datafilePath,
                                              long initialSizeMb,
                                              boolean autoextendUnlimited,
                                              AtomicReference<Statement> cancellableStatement) throws Exception {
        assertOracleTablespaceIdentifier(tablespaceName);
        assertOracleDatafilePath(datafilePath);
        if (initialSizeMb <= 0 || initialSizeMb > 8388608) {
            throw new IllegalArgumentException("Initial size (MB) must be between 1 and 8388608");
        }
        String ts = tablespaceName.trim().toUpperCase(Locale.ROOT);
        String quotedPath = "'" + datafilePath.trim().replace("'", "''") + "'";
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLESPACE ").append(ts);
        sql.append(" DATAFILE ").append(quotedPath);
        sql.append(" SIZE ").append(initialSizeMb).append("M");
        if (autoextendUnlimited) {
            sql.append(" AUTOEXTEND ON NEXT 10M MAXSIZE UNLIMITED");
        }
        executeOracleSpaceDdl(connect, sql.toString(), cancellableStatement);
    }

    /**
     * 删除表空间；{@code includingContentsAndDatafiles} 为 true 时追加 {@code INCLUDING CONTENTS AND DATAFILES}。
     */
    public static void dropOracleTablespace(Connect connect,
                                          String tablespaceName,
                                          boolean includingContentsAndDatafiles,
                                          AtomicReference<Statement> cancellableStatement) throws Exception {
        if (isOracleProtectedTablespace(tablespaceName)) {
            throw new IllegalArgumentException("Refusing to drop a protected tablespace");
        }
        assertOracleTablespaceIdentifier(tablespaceName);
        String ts = tablespaceName.trim().toUpperCase(Locale.ROOT);
        StringBuilder sql = new StringBuilder("DROP TABLESPACE ").append(ts).append(" INCLUDING CONTENTS");
        if (includingContentsAndDatafiles) {
            sql.append(" AND DATAFILES");
        }
        executeOracleSpaceDdl(connect, sql.toString(), cancellableStatement);
    }

    /**
     * 向已有表空间增加数据文件；可选与创建表空间一致的自动扩展子句。
     */
    public static void addOracleDatafile(Connect connect,
                                         String tablespaceName,
                                         String datafilePath,
                                         long sizeMb,
                                         boolean autoextendUnlimited,
                                         AtomicReference<Statement> cancellableStatement) throws Exception {
        assertOracleTablespaceIdentifier(tablespaceName);
        assertOracleDatafilePath(datafilePath);
        if (sizeMb <= 0 || sizeMb > 8388608) {
            throw new IllegalArgumentException("Size (MB) must be between 1 and 8388608");
        }
        String ts = tablespaceName.trim().toUpperCase(Locale.ROOT);
        String quotedPath = "'" + datafilePath.trim().replace("'", "''") + "'";
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLESPACE ").append(ts);
        sql.append(" ADD DATAFILE ").append(quotedPath);
        sql.append(" SIZE ").append(sizeMb).append("M");
        if (autoextendUnlimited) {
            sql.append(" AUTOEXTEND ON NEXT 10M MAXSIZE UNLIMITED");
        }
        executeOracleSpaceDdl(connect, sql.toString(), cancellableStatement);
    }

    /** 从 Oracle 数据文件图标签解析表空间名，格式为：{@code 文件全路径 [ TABLESPACE_NAME ]}。 */
    public static String parseOracleTablespaceFromDatafileLabel(String label) {
        if (label == null) {
            return null;
        }
        int open = label.lastIndexOf(" [ ");
        int close = label.lastIndexOf(" ]");
        if (open < 0 || close <= open + 3) {
            return null;
        }
        return label.substring(open + 3, close).trim();
    }

    /**
     * 设置数据文件自动扩展。使用 {@code ALTER DATABASE DATAFILE}（{@code ALTER TABLESPACE ... DATAFILE ... AUTOEXTEND}
     * 不是合法语法，会导致执行报错）。
     */
    public static void setOracleDatafileAutoextend(Connect connect,
                                                   String tablespaceName,
                                                   String datafilePath,
                                                   boolean enable,
                                                   AtomicReference<Statement> cancellableStatement) throws Exception {
        assertOracleTablespaceIdentifier(tablespaceName);
        assertOracleDatafilePath(datafilePath);
        String quotedPath = "'" + datafilePath.trim().replace("'", "''") + "'";
        String sql = enable
                ? "ALTER DATABASE DATAFILE " + quotedPath + " AUTOEXTEND ON NEXT 10M MAXSIZE UNLIMITED"
                : "ALTER DATABASE DATAFILE " + quotedPath + " AUTOEXTEND OFF";
        executeOracleSpaceDdl(connect, sql, cancellableStatement);
    }

    /**
     * 从表空间删除一个数据文件（{@code ALTER TABLESPACE ... DROP DATAFILE}）。
     */
    public static void dropOracleDatafile(Connect connect,
                                        String tablespaceName,
                                        String datafilePath,
                                        AtomicReference<Statement> cancellableStatement) throws Exception {
        if (isOracleProtectedTablespace(tablespaceName)) {
            throw new IllegalArgumentException("Refusing to drop a datafile from a protected tablespace");
        }
        assertOracleTablespaceIdentifier(tablespaceName);
        assertOracleDatafilePath(datafilePath);
        String ts = tablespaceName.trim().toUpperCase(Locale.ROOT);
        String quotedPath = "'" + datafilePath.trim().replace("'", "''") + "'";
        String sql = "ALTER TABLESPACE " + ts + " DROP DATAFILE " + quotedPath;
        executeOracleSpaceDdl(connect, sql, cancellableStatement);
    }
}
