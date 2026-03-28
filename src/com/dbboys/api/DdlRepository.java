package com.dbboys.api;

import java.sql.Connection;

/**
 * 按数据库方言提供对象 DDL 导出能力。
 */
public interface DdlRepository {

    default String printDatabase(Connection conn, String databaseName) throws Exception {
        throw new UnsupportedOperationException("Database DDL export not supported");
    }

    String printTable(Connection conn, String objectName) throws Exception;

    String printView(Connection conn, String objectName) throws Exception;

    String printIndex(Connection conn, String objectName) throws Exception;

    String printSequence(Connection conn, String objectName) throws Exception;

    String printSynonym(Connection conn, String objectName) throws Exception;

    String printFunction(Connection conn, String objectName) throws Exception;

    String printProcedure(Connection conn, String objectName) throws Exception;

    String printTrigger(Connection conn, String objectName) throws Exception;

    String printPackage(Connection conn, String objectName) throws Exception;
}
