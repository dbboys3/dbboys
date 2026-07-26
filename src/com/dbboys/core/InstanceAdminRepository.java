package com.dbboys.core;

import com.dbboys.model.Connect;
import com.dbboys.model.SpaceUsage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 实例级管理能力，如空间信息与扩容策略。
 */
public interface InstanceAdminRepository {

    default boolean supportsAdminFeatures(Connect connect) {
        return false;
    }

    default boolean supportsHealthCheck(Connect connect) {
        return supportsAdminFeatures(connect);
    }

    default boolean supportsOnlineLog(Connect connect) {
        return supportsAdminFeatures(connect);
    }

    default boolean supportsSpaceManager(Connect connect) {
        return supportsAdminFeatures(connect);
    }

    default boolean supportsConfigManagement(Connect connect) {
        return supportsAdminFeatures(connect);
    }

    default boolean supportsStartStop(Connect connect) {
        return supportsAdminFeatures(connect);
    }

    default boolean supportsLockSession(Connect connect) {
        return false;
    }

    default boolean supportsSpaceMutation(Connect connect) {
        return supportsSpaceManager(connect);
    }

    void setStorageSegmentExtendable(Connection conn, int segmentId, boolean extendable) throws SQLException;

    void resizeStorageSpace(Connection conn, String storageSpaceName, int size1, int size2, int size3) throws SQLException;

    List<List<SpaceUsage>> getStorageSpaceUsage(Connection conn) throws SQLException;

    double getMaxStorageSpaceUsage(Connection conn) throws SQLException;

    default LockSessionResult getLockSessions(Connection conn, String databaseName, String tableName) throws SQLException {
        throw new UnsupportedOperationException("Lock sessions are not supported");
    }

    /** 系统关键表空间（禁止删除或删除其数据文件），由方言按自身名单判断。 */
    default boolean isProtectedTablespace(String tablespaceName) {
        return false;
    }

    /** 创建表空间（单个数据文件）的 DDL，由方言按自身语法生成。 */
    default String createTablespaceSql(String tablespaceName, String datafilePath, long sizeMb, boolean autoextendUnlimited) {
        throw new UnsupportedOperationException("Create tablespace is not supported");
    }

    /** 向已有表空间增加数据文件的 DDL，由方言按自身语法生成。 */
    default String addDatafileSql(String tablespaceName, String datafilePath, long sizeMb, boolean autoextendUnlimited) {
        throw new UnsupportedOperationException("Add datafile is not supported");
    }

    /** 删除表空间的 DDL，由方言按自身语法生成。 */
    default String dropTablespaceSql(String tablespaceName, boolean includingContentsAndDatafiles) {
        throw new UnsupportedOperationException("Drop tablespace is not supported");
    }

    /** 设置数据文件自动扩展的 DDL，由方言按自身语法生成。 */
    default String setDatafileAutoextendSql(String tablespaceName, String datafilePath, boolean enable) {
        throw new UnsupportedOperationException("Datafile autoextend is not supported");
    }

    /** 从表空间删除数据文件的 DDL，由方言按自身语法生成。 */
    default String dropDatafileSql(String tablespaceName, String datafilePath) {
        throw new UnsupportedOperationException("Drop datafile is not supported");
    }

    default void killLockSession(Connect connect, String owner) throws Exception {
        throw new UnsupportedOperationException("KILL lock session is not supported");
    }

    default boolean canKillLockSession(Connect connect) {
        return false;
    }

    default String killLockSessionCommand(String owner) {
        return "onmode -z " + owner;
    }

    default String getLockSessionDetail(Connect connect, String sid) throws Exception {
        throw new UnsupportedOperationException("Lock session detail is not supported");
    }

    default boolean canShowLockSessionDetail(Connect connect) {
        return false;
    }

    default String lockSessionDetailCommand(String sid) {
        return "onstat -g ses " + sid;
    }

    record LockSessionResult(List<String> columns, List<List<String>> rows) {
    }
}
