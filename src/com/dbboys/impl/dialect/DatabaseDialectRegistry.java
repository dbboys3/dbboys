package com.dbboys.impl.dialect;

import com.dbboys.api.DatabaseDialect;
import com.dbboys.impl.dialect.gbase.GbaseDialect;
import com.dbboys.impl.dialect.informix.InformixDialect;
import com.dbboys.impl.dialect.oracle.OracleDialect;
import com.dbboys.vo.Connect;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多库核心入口：一种 {@link Connect#getDbtype()} 对应一个 {@link DatabaseDialect}。
 */
public final class DatabaseDialectRegistry {

    private final Map<String, DatabaseDialect> dialectByDbType = new ConcurrentHashMap<>();

    public DatabaseDialectRegistry() {
    }

    public void register(DatabaseDialect dialect) {
        if (dialect == null || dialect.getDbType() == null || dialect.getDbType().isBlank()) {
            throw new IllegalArgumentException("dialect and getDbType() must be non-null and non-blank");
        }
        dialectByDbType.put(dialect.getDbType(), dialect);
    }

    public void register(String dbType, DatabaseDialect dialect) {
        if (dbType == null || dbType.isBlank() || dialect == null) {
            throw new IllegalArgumentException("dbType and dialect must be non-null and non-blank");
        }
        dialectByDbType.put(dbType, dialect);
    }

    public DatabaseDialect getDialect(String dbType) {
        return dbType == null ? null : dialectByDbType.get(dbType);
    }

    public DatabaseDialect requireDialect(Connect connect) {
        if (connect == null) {
            throw new IllegalArgumentException("connect is null");
        }
        return requireDialect(connect.getDbtype());
    }

    public DatabaseDialect requireDialect(String dbType) {
        DatabaseDialect dialect = getDialect(dbType);
        if (dialect == null) {
            throw new IllegalArgumentException("Unsupported database type: " + dbType);
        }
        return dialect;
    }

    public Collection<DatabaseDialect> getAllDialects() {
        return dialectByDbType.values();
    }

    public static DatabaseDialectRegistry createDefault() {
        DatabaseDialectRegistry registry = new DatabaseDialectRegistry();
        registry.register(new GbaseDialect());
        registry.register(new InformixDialect());
        registry.register(new OracleDialect());
        return registry;
    }
}
