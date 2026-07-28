package com.dbboys.dialect.oracle;

import com.dbboys.dialect.common.OracleFamilyDdlRepository;
import com.dbboys.infra.db.SqlRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;

/** Oracle DDL export; shared logic lives in {@link OracleFamilyDdlRepository}. */
public final class OracleDdlRepository extends OracleFamilyDdlRepository {

    /**
     * Export progress total when splitting DDL: pre-data phase + post-data (standalone indexes, constraints, triggers).
     * Matches {@link #exportDatabaseDdlParts}; table count uses body-only DDL, indexes exclude constraint-backed ones.
     */
    private static final String SQL_EXPORT_ITEM_COUNT_SPLIT = """
            SELECT
              (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'SEQUENCE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'TABLE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'VIEW' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'SYNONYM' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_queues WHERE owner = ?)
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'FUNCTION' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'PROCEDURE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'PACKAGE' AND secondary = 'N')
            + (SELECT COUNT(*) FROM all_scheduler_jobs WHERE owner = ?)
            + (SELECT COUNT(*) FROM all_objects i WHERE i.owner = ? AND i.object_type = 'INDEX' AND i.secondary = 'N'
               AND NOT EXISTS (
                   SELECT 1 FROM all_constraints c
                   WHERE c.owner = i.owner
                     AND c.index_name = i.object_name
                     AND c.index_name IS NOT NULL))
            + (SELECT COUNT(*) FROM all_constraints
               WHERE owner = ? AND status = 'ENABLED'
                 AND constraint_type IN ('P','U','C','R')
                 AND (generated IS NULL OR generated = 'USER NAME'))
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'TRIGGER' AND secondary = 'N')
            AS cnt FROM dual
            """;

    /** Indexes not backing a constraint (PK/UK); exported in post-data phase. */
    private static final String SQL_STANDALONE_INDEX_NAMES = """
            SELECT i.object_name
            FROM all_objects i
            WHERE i.owner = ?
              AND i.object_type = 'INDEX'
              AND i.secondary = 'N'
              AND NOT EXISTS (
                  SELECT 1 FROM all_constraints c
                  WHERE c.owner = i.owner
                    AND c.index_name = i.object_name
                    AND c.index_name IS NOT NULL)
            ORDER BY i.object_name
            """;

    private static final String SQL_SCHEMA_OBJECTS = """
            SELECT object_name, object_type FROM all_objects
            WHERE owner = ? AND object_type = ? AND secondary = 'N'
            ORDER BY object_name
            """;

    private static final String SQL_QUEUE_NAMES = """
            SELECT name
            FROM all_queues
            WHERE owner = ?
            ORDER BY name
            """;

    private static final String SQL_SCHEDULER_JOB_NAMES = """
            SELECT job_name
            FROM all_scheduler_jobs
            WHERE owner = ?
            ORDER BY job_name
            """;

    @Override
    protected String defaultSchemaName() {
        return "ORACLE";
    }

    @Override
    protected String sqlExportItemCountSplit() {
        return SQL_EXPORT_ITEM_COUNT_SPLIT;
    }

    @Override
    protected int exportItemBindCount() {
        return 12;
    }

    @Override
    protected String sqlStandaloneIndexNames() {
        return SQL_STANDALONE_INDEX_NAMES;
    }

    @Override
    protected String sqlSchemaObjects() {
        return SQL_SCHEMA_OBJECTS;
    }

    @Override
    protected void configureMetadataTransform(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("BEGIN " +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'STORAGE',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'TABLESPACE',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SEGMENT_ATTRIBUTES',FALSE);" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',TRUE);" +
                    "END;");
        }
    }

    @Override
    protected void setEmbeddedTableConstraintsInMetadata(Connection conn, boolean include) throws SQLException {
        String v = include ? "TRUE" : "FALSE";
        try (var stmt = conn.createStatement()) {
            stmt.execute("BEGIN " +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'CONSTRAINTS'," + v + ");" +
                    "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'REF_CONSTRAINTS'," + v + ");" +
                    "END;");
        }
    }

    @Override
    public String printType(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        String spec = getDdl(conn, "TYPE", objectName, schema);
        String body;


        StringBuilder sb = new StringBuilder();
        sb.append(spec).append("\n/\n");
        return sb.toString().stripTrailing();
    }

    @Override
    protected long appendQueuesDdl(Connection conn, StringBuilder ddl, String schema,
                                   long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_QUEUE_NAMES, List.of(schema), rs -> rs.getString("name"));
        if (names.isEmpty()) {
            return completed;
        }
        ddl.append("-- ### Queues (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = getDdlSafe(conn, "AQ_QUEUE", name, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: Queues\n\n");
        return completed;
    }

    @Override
    protected long appendSchedulerJobsDdl(Connection conn, StringBuilder ddl, String schema,
                                          long completed, LongConsumer progressCallback) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_SCHEDULER_JOB_NAMES, List.of(schema), rs -> rs.getString("job_name"));
        if (names.isEmpty()) {
            return completed;
        }

        ddl.append("-- ### Scheduler Jobs (").append(names.size()).append(")\n\n");
        for (String name : names) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Export cancelled");
            }
            String objDdl = withTrailingSqlPlusSlash(getDdlSafe(conn, "PROCOBJ", name, schema));
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                ddl.append("\n\n");
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.accept(completed);
            }
        }
        ddl.append("-- ### FINISH: Scheduler Jobs\n\n");
        return completed;
    }
}
