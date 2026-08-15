package com.dbboys.core;

import java.sql.Connection;
import java.util.function.LongConsumer;

/**
 * 按数据库方言提供对象 DDL 导出能力。
 */
public interface DdlRepository {

    class DatabaseDdlParts {
        private final String preDataSql;
        private final String postDataSql;

        public DatabaseDdlParts(String preDataSql, String postDataSql) {
            this.preDataSql = preDataSql == null ? "" : preDataSql;
            this.postDataSql = postDataSql == null ? "" : postDataSql;
        }

        public String getPreDataSql() {
            return preDataSql;
        }

        public String getPostDataSql() {
            return postDataSql;
        }
    }

    default long countDatabaseExportItems(Connection conn, String databaseName) throws Exception {
        return -1;
    }

    default String printDatabase(Connection conn, String databaseName) throws Exception {
        throw new UnsupportedOperationException("Database DDL export not supported");
    }

    default String printDatabase(Connection conn, String databaseName, LongConsumer progressCallback) throws Exception {
        return printDatabase(conn, databaseName);
    }

    default DatabaseDdlParts exportDatabaseDdlParts(Connection conn,
                                                    String databaseName,
                                                    LongConsumer progressCallback) throws Exception {
        return new DatabaseDdlParts(printDatabase(conn, databaseName, progressCallback), "");
    }

    String printTable(Connection conn, String objectName) throws Exception;

    /** 迁移用建表 DDL：默认同 {@link #printTable}；Informix 族覆盖为不带索引和约束的版本
     *  （索引/外键作为独立迁移对象，在全部表数据迁移完成后才创建）。 */
    default String printTableForMigration(Connection conn, String objectName) throws Exception {
        return printTable(conn, objectName);
    }

    String printView(Connection conn, String objectName) throws Exception;

    String printIndex(Connection conn, String objectName) throws Exception;

    String printSequence(Connection conn, String objectName) throws Exception;

    String printSynonym(Connection conn, String objectName) throws Exception;

    String printFunction(Connection conn, String objectName) throws Exception;

    String printProcedure(Connection conn, String objectName) throws Exception;

    String printTrigger(Connection conn, String objectName) throws Exception;

    String printPackage(Connection conn, String objectName) throws Exception;

    /** Object / collection types (e.g. Oracle {@code TYPE} / {@code TYPE_BODY}). */
    default String printType(Connection conn, String objectName) throws Exception {
        return "--";
    }

    /** Queues (e.g. Oracle Advanced Queuing {@code QUEUE}). */
    default String printQueue(Connection conn, String objectName) throws Exception {
        return "--";
    }

    /** Scheduler jobs (e.g. Oracle DBMS_SCHEDULER jobs). */
    default String printSchedulerJob(Connection conn, String objectName) throws Exception {
        return "--";
    }
}
