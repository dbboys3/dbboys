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

    private static final String SQL_TABLE_STANDALONE_INDEX_NAMES = """
            SELECT i.object_name
            FROM all_objects i
            WHERE i.owner = ?
              AND i.object_type = 'INDEX'
              AND i.secondary = 'N'
              AND EXISTS (
                  SELECT 1 FROM all_indexes ai
                  WHERE ai.owner = i.owner
                    AND ai.index_name = i.object_name
                    AND ai.table_owner = ?
                    AND ai.table_name = ?)
              AND NOT EXISTS (
                  SELECT 1 FROM all_constraints c
                  WHERE c.owner = i.owner
                    AND c.index_name = i.object_name
                    AND c.index_name IS NOT NULL)
            ORDER BY i.object_name
            """;

    private static final String SQL_TABLE_CONSTRAINTS_PUC = """
            SELECT constraint_name, constraint_type
            FROM all_constraints
            WHERE owner = ?
              AND table_name = ?
              AND status = 'ENABLED'
              AND constraint_type IN ('P','U','C')
              AND (generated IS NULL OR generated = 'USER NAME')
            ORDER BY CASE constraint_type
                       WHEN 'P' THEN 1 WHEN 'U' THEN 2 WHEN 'C' THEN 3
                     END,
                     constraint_name
            """;

    private static final String SQL_TABLE_CONSTRAINTS_R = """
            SELECT constraint_name
            FROM all_constraints
            WHERE owner = ?
              AND table_name = ?
              AND status = 'ENABLED'
              AND constraint_type = 'R'
              AND (generated IS NULL OR generated = 'USER NAME')
            ORDER BY constraint_name
            """;

    private static final String SQL_TABLE_TRIGGERS = """
            SELECT trigger_name
            FROM all_triggers
            WHERE owner = ?
              AND table_owner = ?
              AND table_name = ?
            ORDER BY trigger_name
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
    public String printTable(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        String table = simpleObjectName(objectName).toUpperCase(java.util.Locale.ROOT);
        StringBuilder ddl = new StringBuilder();

        setEmbeddedTableConstraintsInMetadata(conn, false);
        configureMetadataTransform(conn);
        ddl.append(getDdl(conn, "TABLE", table, schema)).append("\n\n");
        setEmbeddedTableConstraintsInMetadata(conn, true);
        appendStandaloneIndexesForTable(conn, ddl, schema, table);
        appendConstraintsForTable(conn, ddl, schema, table);
        appendTriggersForTable(conn, ddl, schema, table);
        return ddl.toString().stripTrailing();
    }

    /** 迁移用建表 DDL 保持原来的表结构（不含独立索引/约束/触发器，避免迁移阶段重复创建）。 */
    @Override
    public String printTableForMigration(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        configureMetadataTransform(conn);
        return getDdl(conn, "TABLE", simpleObjectName(objectName).toUpperCase(java.util.Locale.ROOT), schema);
    }

    private void appendStandaloneIndexesForTable(Connection conn, StringBuilder ddl,
                                                 String schema, String table) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_TABLE_STANDALONE_INDEX_NAMES,
                List.of(schema, schema, table), rs -> rs.getString(1));
        if (names.isEmpty()) {
            return;
        }
        for (String name : names) {
            String objDdl = getDdlSafe(conn, "INDEX", name, schema).trim();
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append(";");
                }
                ddl.append("\n\n");
            }
        }
    }

    private void appendConstraintsForTable(Connection conn, StringBuilder ddl,
                                           String schema, String table) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String[]> pucRows = runner.query(SQL_TABLE_CONSTRAINTS_PUC, List.of(schema, table),
                rs -> new String[]{rs.getString(1), rs.getString(2)});
        List<String> refRows = runner.query(SQL_TABLE_CONSTRAINTS_R, List.of(schema, table),
                rs -> rs.getString(1));
        int n = pucRows.size() + refRows.size();
        if (n == 0) {
            return;
        }
        ddl.append("-- ### Constraints (").append(n).append(")\n\n");
        for (String[] row : pucRows) {
            String objDdl = getDdlSafe(conn, "CONSTRAINT", row[0], schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
        }
        ddl.append("-- ### Referential constraints (foreign keys)\n\n");
        for (String consName : refRows) {
            String objDdl = getDdlSafe(conn, "REF_CONSTRAINT", consName, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
        }
    }

    private void appendTriggersForTable(Connection conn, StringBuilder ddl,
                                        String schema, String table) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
        List<String> names = runner.query(SQL_TABLE_TRIGGERS, List.of(schema, schema, table),
                rs -> rs.getString(1));
        if (names.isEmpty()) {
            return;
        }
        ddl.append("-- ### Triggers (").append(names.size()).append(")\n\n");
        for (String name : names) {
            String objDdl = getDdlSafe(conn, "TRIGGER", name, schema);
            if (!objDdl.isEmpty()) {
                ddl.append(objDdl);
                if (!objDdl.endsWith(";")) {
                    ddl.append("\n;");
                }
                ddl.append("\n\n");
            }
        }
    }

    private static String simpleObjectName(String objectName) {
        String raw = objectName == null ? "" : objectName.trim();
        int dot = raw.lastIndexOf('.');
        String name = dot >= 0 ? raw.substring(dot + 1) : raw;
        String trimmed = name.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
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
