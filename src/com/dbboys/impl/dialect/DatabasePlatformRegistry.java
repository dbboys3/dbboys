package com.dbboys.impl.dialect;

import com.dbboys.api.DatabasePlatform;
import com.dbboys.impl.dialect.gbase.GbaseDialect;
import com.dbboys.impl.dialect.informix.InformixDialect;
import com.dbboys.impl.dialect.oracle.OracleDialect;
import com.dbboys.vo.Connect;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多库核心入口：一种 {@link Connect#getDbtype()} 对应一个 {@link DatabasePlatform}。
 */
public final class DatabasePlatformRegistry {

    private final Map<String, DatabasePlatform> platformByDbType = new ConcurrentHashMap<>();

    public DatabasePlatformRegistry() {
    }

    public void register(DatabasePlatform platform) {
        if (platform == null || platform.getDbType() == null || platform.getDbType().isBlank()) {
            throw new IllegalArgumentException("platform and getDbType() must be non-null and non-blank");
        }
        platformByDbType.put(platform.getDbType(), platform);
    }

    public void register(String dbType, DatabasePlatform platform) {
        if (dbType == null || dbType.isBlank() || platform == null) {
            throw new IllegalArgumentException("dbType and platform must be non-null and non-blank");
        }
        platformByDbType.put(dbType, platform);
    }

    public DatabasePlatform getPlatform(String dbType) {
        return dbType == null ? null : platformByDbType.get(dbType);
    }

    public DatabasePlatform requirePlatform(Connect connect) {
        if (connect == null) {
            throw new IllegalArgumentException("connect is null");
        }
        return requirePlatform(connect.getDbtype());
    }

    public DatabasePlatform requirePlatform(String dbType) {
        DatabasePlatform platform = getPlatform(dbType);
        if (platform == null) {
            throw new IllegalArgumentException("Unsupported database type: " + dbType);
        }
        return platform;
    }

    public Collection<DatabasePlatform> getAllPlatforms() {
        return platformByDbType.values();
    }

    public static DatabasePlatformRegistry createDefault() {
        DatabasePlatformRegistry registry = new DatabasePlatformRegistry();
        registry.register(new GbaseDialect());
        registry.register(new InformixDialect());
        registry.register(new OracleDialect());
        return registry;
    }
}
