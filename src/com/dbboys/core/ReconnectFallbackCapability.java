package com.dbboys.core;

import com.dbboys.model.Connect;

/**
 * 可选的重连回退库能力。
 * 某些数据库在切库失败后需要先落回一个稳定库，再创建新连接继续执行。
 */
public interface ReconnectFallbackCapability {

    String reconnectFallbackDatabaseName();

    /**
     * 允许按连接配置给出回退库（如 PostgreSQL 回退到连接配置的库）。
     * 默认忽略连接，返回常量回退库名。
     */
    default String reconnectFallbackDatabaseName(Connect connect) {
        return reconnectFallbackDatabaseName();
    }
}
