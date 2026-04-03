package com.dbboys.api;

import com.dbboys.vo.Connect;

/**
 * 多数据库统一解析入口。
 * 对上层隐藏 dbtype 到平台实现的路由细节，避免业务层分别依赖多种 provider 接口。
 */
public interface DatabasePlatformResolver {

    MetadataRepository metadata(Connect connect);

    SqlexeRepository sqlexe(Connect connect);

    DdlRepository ddl(Connect connect);

    InstanceAdminRepository admin(Connect connect);

    DatabasePlatform getPlatform(String dbType);

    DatabasePlatform requirePlatform(Connect connect);

    DatabasePlatform requirePlatform(String dbType);
}
