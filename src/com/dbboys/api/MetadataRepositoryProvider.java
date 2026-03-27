package com.dbboys.api;

import com.dbboys.vo.Connect;

/**
 * 按连接（dbtype）提供对应的元数据仓库实现。
 */
public interface MetadataRepositoryProvider {

    MetadataRepository metadata(Connect connect);
}
