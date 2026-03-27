package com.dbboys.api;

import com.dbboys.vo.Connect;

/**
 * 按连接（dbtype）提供对应的 SQL 执行/模式仓库实现。
 */
public interface SqlexeRepositoryProvider {

    SqlexeRepository sqlexe(Connect connect);
}
