package com.dbboys.service.migration;

import com.dbboys.core.PlatformResolvers;
import com.dbboys.core.SqlModeCapability;
import com.dbboys.model.Connect;
import com.dbboys.service.BackgroundSqlService;

import java.sql.Connection;
import java.util.List;

/**
 * 迁移界面展示用：连接的数据库类型 + sqlmode 探测。
 * 仅实现了 {@link SqlModeCapability} 的平台（GBase 8S）能探测到 sqlmode；
 * 探测打开一个不做会话初始化的临时连接，取支持清单首项（与 SQL 页"当前模式"同口径）。
 */
public final class MigrationConnectInfo {

    private MigrationConnectInfo() {}

    /**
     * "GBASE 8S（sqlmode=gbase）" 形式的展示文本；平台不支持 sqlmode 或探测失败时只回 dbtype。
     * 会建临时连接探测，耗时操作，请在后台线程调用。
     */
    public static String dbTypeWithSqlMode(Connect connect) {
        if (connect == null) {
            return "";
        }
        String dbtype = connect.getDbtype() == null ? "" : connect.getDbtype();
        String sqlMode = probeSqlMode(connect);
        return sqlMode == null ? dbtype : dbtype + "（" + sqlMode + "）";
    }

    /** 类型映射/展示用的有效平台类型：有 sqlmode 以 sqlmode 为准（oracle→ORACLE、mysql→MYSQL、
     *  gbase→GBASE 8S），否则为连接的数据库类型。可能建临时连接探测，请在后台线程调用。 */
    public static String effectiveDbType(Connect connect) {
        if (connect == null) {
            return null;
        }
        String sqlMode = probeSqlMode(connect);
        if (sqlMode != null) {
            String mode = sqlMode.replace("sqlmode=", "").trim();
            if ("oracle".equalsIgnoreCase(mode)) {
                return "ORACLE";
            }
            if ("mysql".equalsIgnoreCase(mode)) {
                return "MYSQL";
            }
            if ("gbase".equalsIgnoreCase(mode)) {
                return "GBASE 8S";
            }
        }
        return connect.getDbtype();
    }

    /** 探测当前 sqlmode；不支持/失败返回 null。 */
    private static String probeSqlMode(Connect connect) {
        try {
            var repo = PlatformResolvers.get().sqlexe(connect);
            if (!(repo instanceof SqlModeCapability capability)) {
                return null;
            }
            try (Connection conn = BackgroundSqlService.getConnectionService().createConnection(connect)) {
                List<String> modes = capability.getSqlModes(conn);
                if (modes == null || modes.isEmpty()) {
                    return null;
                }
                String first = modes.get(0);
                return first == null || first.isBlank() || "sqlmode=none".equals(first) ? null : first;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
