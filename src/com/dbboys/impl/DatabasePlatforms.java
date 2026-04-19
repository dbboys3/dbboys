package com.dbboys.impl;

import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.impl.dialect.genericjdbc.GeneralJdbcDialect;
import com.dbboys.impl.dialect.gbase.GbaseDialect;
import com.dbboys.impl.dialect.informix.InformixDialect;
import com.dbboys.impl.dialect.oracle.OracleDialect;
import com.dbboys.vo.Connect;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多库统一入口：既负责平台注册，也负责上层按 dbtype 解析平台与仓库。
 */
public final class DatabasePlatforms implements DatabasePlatformResolver {

    private final Map<String, DatabasePlatform> platformByDbType = new ConcurrentHashMap<>();

    public DatabasePlatforms() {
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

    @Override
    public Collection<DatabasePlatform> allPlatforms() {
        return platformByDbType.values();
    }

    @Override
    public DatabasePlatform getPlatform(String dbType) {
        if (dbType == null) {
            return null;
        }
        DatabasePlatform platform = platformByDbType.get(dbType);
        if (platform != null) {
            return platform;
        }
        for (Map.Entry<String, DatabasePlatform> entry : platformByDbType.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(dbType)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public DatabasePlatform requirePlatform(Connect connect) {
        if (connect == null) {
            throw new IllegalArgumentException("connect is null");
        }
        return requirePlatform(connect.getDbtype());
    }

    @Override
    public DatabasePlatform requirePlatform(String dbType) {
        DatabasePlatform platform = getPlatform(dbType);
        if (platform == null) {
            throw new IllegalArgumentException("Unsupported database type: " + dbType);
        }
        return platform;
    }

    @Override
    public MetadataRepository metadata(Connect connect) {
        return requirePlatform(connect).metadata();
    }

    @Override
    public SqlexeRepository sqlexe(Connect connect) {
        return requirePlatform(connect).sql();
    }

    @Override
    public DdlRepository ddl(Connect connect) {
        return requirePlatform(connect).ddl();
    }

    @Override
    public InstanceAdminRepository admin(Connect connect) {
        return requirePlatform(connect).admin();
    }

    public static DatabasePlatforms createDefault() {
        DatabasePlatforms platforms = new DatabasePlatforms();
        platforms.register(new GeneralJdbcDialect());
        platforms.register(new GbaseDialect());
        platforms.register(new InformixDialect());
        platforms.register(new OracleDialect());
        return platforms;
    }
}
