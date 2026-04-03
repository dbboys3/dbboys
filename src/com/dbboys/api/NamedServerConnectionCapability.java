package com.dbboys.api;

/**
 * 可选的命名服务连接能力。
 * 适用于通过服务名/组名而不是主机端口建立连接的数据库。
 */
public interface NamedServerConnectionCapability {

    String namedServerPropertyName();
}
