package com.dbboys.core;

/**
 * 数据库连接地址的输入模式，决定创建连接对话框里地址字段的呈现方式。
 * 方言通过 {@link ConnectionSupport#connectionAddressType()} 声明。
 */
public enum ConnectionAddressType {
    HOST_PORT,
    JDBC_URL,
    FILE_PATH
}