package com.dbboys.impl;

import com.dbboys.api.DatabasePlatformResolver;
import com.dbboys.api.DatabaseDialect;
import com.dbboys.api.DdlRepository;
import com.dbboys.api.InstanceAdminRepository;
import com.dbboys.api.MetadataRepository;
import com.dbboys.api.SqlexeRepository;
import com.dbboys.impl.dialect.DatabaseDialectRegistry;
import com.dbboys.vo.Connect;

/**
 * 多数据库路由统一入口：对外暴露方言、元数据仓库、SQL 仓库三类访问方式，
 * 避免 AppContext 和业务服务重复围绕 registry 再套一层 provider。
 */
public final class DialectServices implements DatabasePlatformResolver {

    private final DatabaseDialectRegistry registry;

    public DialectServices(DatabaseDialectRegistry registry) {
        this.registry = registry != null ? registry : DatabaseDialectRegistry.createDefault();
    }

    public static DialectServices createDefault() {
        return new DialectServices(DatabaseDialectRegistry.createDefault());
    }

    public DatabaseDialectRegistry getRegistry() {
        return registry;
    }

    public DatabaseDialect getDialect(String dbType) {
        return registry.getDialect(dbType);
    }

    public DatabaseDialect requireDialect(Connect connect) {
        return registry.requireDialect(connect);
    }

    public DatabaseDialect requireDialect(String dbType) {
        return registry.requireDialect(dbType);
    }

    @Override
    public MetadataRepository metadata(Connect connect) {
        return requireDialect(connect).getMetadataRepository();
    }

    @Override
    public SqlexeRepository sqlexe(Connect connect) {
        return requireDialect(connect).getSqlexeRepository();
    }

    @Override
    public DdlRepository ddl(Connect connect) {
        return requireDialect(connect).getDdlRepository();
    }

    @Override
    public InstanceAdminRepository admin(Connect connect) {
        return requireDialect(connect).getInstanceAdminRepository();
    }
}
