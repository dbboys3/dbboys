package com.dbboys.app;

import com.dbboys.core.DatabasePlatforms;
import com.dbboys.dialect.dameng.DamengDialect;
import com.dbboys.dialect.gbase.GbaseDialect;
import com.dbboys.dialect.genericjdbc.GeneralJdbcDialect;
import com.dbboys.dialect.informix.InformixDialect;
import com.dbboys.dialect.mysql.MysqlDialect;
import com.dbboys.dialect.oracle.OracleDialect;
import com.dbboys.dialect.sqlite.SqliteDialect;

/**
 * 组合根接线：实例化并注册全部数据库方言。
 * 方言实现集中在 app 层装配，core 只保留纯注册表，不依赖任何 dialect 实现。
 */
public final class PlatformBootstrap {

    private PlatformBootstrap() {
    }

    public static DatabasePlatforms createDefault() {
        DatabasePlatforms platforms = new DatabasePlatforms();
        platforms.register(new GeneralJdbcDialect());
        platforms.register(new GbaseDialect());
        platforms.register(new InformixDialect());
        platforms.register(new MysqlDialect());
        platforms.register(new OracleDialect());
        platforms.register(new DamengDialect());
        platforms.register(new SqliteDialect());
        return platforms;
    }
}
