package com.dbboys.service.migration;

import com.dbboys.core.DatabasePlatform;
import com.dbboys.core.PlatformResolvers;
import com.dbboys.model.Connect;
import com.dbboys.service.BackgroundSqlService;

import java.sql.Connection;

/**
 * 迁移专用连接工厂：仅建 JDBC 连接，不做方言会话初始化
 * （避免 GBase 8S 的 sqlmode 强制等会话级设置影响迁移的 DDL 与类型映射判定）。
 * 库/模式选择由连接 URL（catalog/sessionCatalog）承载。
 * 例外：SCHEMA / DATABASE_SCHEMA 模型（oracle/dameng/postgresql）的模式切换
 * 无法由 URL 承载（CURRENT_SCHEMA / search_path），这类平台补做方言会话初始化。
 */
public final class MigrationConnections {

    private MigrationConnections() {}

    /** 建迁移用连接（两层模型不 init；模式类平台按 sessionCatalog 做方言会话初始化）。 */
    public static Connection create(Connect connect) throws Exception {
        Connection conn = BackgroundSqlService.getConnectionService().createConnection(connect);
        try {
            DatabasePlatform platform = PlatformResolvers.get().requirePlatform(connect);
            if (platform.connection().supportsSessionInit()
                    && platform.catalogModel() != DatabasePlatform.CatalogModel.DATABASE
                    && connect.getSessionCatalog() != null && !connect.getSessionCatalog().isBlank()) {
                platform.connection().sessionInit(conn, connect);
            }
        } catch (Exception e) {
            try { conn.close(); } catch (Exception ignored) {}
            throw e;
        }
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
