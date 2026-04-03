package com.dbboys.impl;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.DatabasePlatform;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.impl.dialect.DatabasePlatformRegistry;
import com.dbboys.vo.Connect;

/**
 * 多数据库路由统一入口：对外暴露方言、元数据仓库、SQL 仓库三类访问方式，
 * 避免 AppContext 和业务服务重复围绕 registry 再套一层 provider。
 */
public final class DialectServices implements DatabasePlatformResolver {

    private final DatabasePlatformRegistry registry;

    public DialectServices(DatabasePlatformRegistry registry) {
        this.registry = registry != null ? registry : DatabasePlatformRegistry.createDefault();
    }

    public static DialectServices createDefault() {
        return new DialectServices(DatabasePlatformRegistry.createDefault());
    }

    public DatabasePlatformRegistry getPlatformRegistry() {
        return registry;
    }

    @Override
    public DatabasePlatform getPlatform(String dbType) {
        return registry.getPlatform(dbType);
    }

    @Override
    public DatabasePlatform requirePlatform(Connect connect) {
        return registry.requirePlatform(connect);
    }

    @Override
    public DatabasePlatform requirePlatform(String dbType) {
        return registry.requirePlatform(dbType);
    }

    @Override
    public MetadataRepository metadata(Connect connect) {
        return requirePlatform(connect).getMetadataRepository();
    }

    @Override
    public SqlexeRepository sqlexe(Connect connect) {
        return requirePlatform(connect).getSqlexeRepository();
    }

    @Override
    public DdlRepository ddl(Connect connect) {
        return requirePlatform(connect).getDdlRepository();
    }

    @Override
    public InstanceAdminRepository admin(Connect connect) {
        return requirePlatform(connect).getInstanceAdminRepository();
    }
}
