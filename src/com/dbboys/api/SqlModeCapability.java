package com.dbboys.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 可选的 SQLMODE 能力。
 * 仅在数据库提供兼容模式切换时实现，其他数据库无需实现。
 */
public interface SqlModeCapability {

    List<String> getSqlModes(Connection conn) throws SQLException;

    void changeSqlMode(Connection conn, String sqlMode) throws SQLException;

    String detectSqlMode(String sql);

    boolean autoCommitsDdl(String sqlMode);
}
