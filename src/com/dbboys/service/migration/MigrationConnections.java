package com.dbboys.service.migration;

import com.dbboys.model.Connect;
import com.dbboys.service.BackgroundSqlService;

import java.sql.Connection;

/**
 * 迁移专用连接工厂：仅建 JDBC 连接，不做方言会话初始化
 * （避免 GBase 8S 的 sqlmode 强制等会话级设置影响迁移的 DDL 与类型映射判定）。
 * 库/模式选择由连接 URL（catalog/sessionCatalog）承载。
 */
public final class MigrationConnections {

    private MigrationConnections() {}

    /** 建迁移用连接（不 init）。 */
    public static Connection create(Connect connect) throws Exception {
        Connection conn = BackgroundSqlService.getConnectionService().createConnection(connect);
        EVER.put(conn, new Throwable("connection created"));
        return conn;
    }

    // ---- TEMP DEBUG（复现取消残留连接用，定位后移除） ----
    public static final java.util.Map<Connection, Throwable> EVER = new java.util.concurrent.ConcurrentHashMap<>();

    public static void reportLeaks() {
        final int[] leaked = {0};
        EVER.forEach((c, st) -> {
            try {
                if (!c.isClosed()) {
                    leaked[0]++;
                    System.out.println("LEAKED CONNECTION: " + c);
                    st.printStackTrace(System.out);
                }
            } catch (Exception ignored) {
            }
        });
        System.out.println("DEBUG reportLeaks: totalCreated=" + EVER.size() + " leaked=" + leaked[0]);
    }
}
