package com.dbboys.api;

/**
 * 可选的重连回退库能力。
 * 某些数据库在切库失败后需要先落回一个稳定库，再创建新连接继续执行。
 */
public interface ReconnectFallbackCapability {

    String reconnectFallbackDatabaseName();
}
