package com.dbboys.dialect.dameng;

import com.dbboys.dialect.common.OracleFamilyDdlRepository;
import com.dbboys.infra.db.SqlRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Dameng DDL export; shared logic lives in {@link OracleFamilyDdlRepository}. */
public final class DamengDdlRepository extends OracleFamilyDdlRepository {

    private static final Logger log = LogManager.getLogger(DamengDdlRepository.class);

    /**
     * Export progress total when splitting DDL: pre-data phase + post-data (standalone indexes, constraints, triggers).
     * Matches {@link #exportDatabaseDdlParts}; table count uses body-only DDL, indexes exclude constraint-backed ones.
     */
    private static final String SQL_EXPORT_ITEM_COUNT_SPLIT = """
            SELECT
              (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'SEQUENCE')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'TABLE')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'VIEW')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'SYNONYM')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'FUNCTION')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'PROCEDURE')
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'PACKAGE')
            + (SELECT COUNT(*) FROM all_objects i WHERE i.owner = ? AND i.object_type = 'INDEX'
               AND NOT EXISTS (
                   SELECT 1 FROM all_constraints c
                   WHERE c.owner = i.owner
                     AND c.index_name = i.object_name
                     AND c.index_name IS NOT NULL))
            + (SELECT COUNT(*) FROM all_constraints
               WHERE owner = ? AND status = 'ENABLED'
                 AND constraint_type IN ('P','U','C','R')
                 AND (generated IS NULL OR generated = 'USER NAME'))
            + (SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'TRIGGER')
            AS cnt FROM dual
            """;

    /** Indexes not backing a constraint (PK/UK); exported in post-data phase. */
    private static final String SQL_STANDALONE_INDEX_NAMES = """
            SELECT i.object_name
            FROM all_objects i
            WHERE i.owner = ?
              AND i.object_type = 'INDEX'
              AND NOT EXISTS (
                  SELECT 1 FROM all_constraints c
                  WHERE c.owner = i.owner
                    AND c.index_name = i.object_name
                    AND c.index_name IS NOT NULL)
            ORDER BY i.object_name
            """;

    private static final String SQL_SCHEMA_OBJECTS = """
            SELECT object_name, object_type FROM all_objects
            WHERE owner = ? AND object_type = ?
            ORDER BY object_name
            """;

    @Override
    protected String defaultSchemaName() {
        return "DAMENG";
    }

    @Override
    protected String sqlExportItemCountSplit() {
        return SQL_EXPORT_ITEM_COUNT_SPLIT;
    }

    @Override
    protected int exportItemBindCount() {
        return 10;
    }

    @Override
    protected String sqlStandaloneIndexNames() {
        return SQL_STANDALONE_INDEX_NAMES;
    }

    @Override
    protected String sqlSchemaObjects() {
        return SQL_SCHEMA_OBJECTS;
    }

    /** Dameng {@code GET_DDL} object types differ from Oracle ({@code PKG_SPEC} vs {@code PACKAGE_SPEC}). */
    @Override
    protected String mapGetDdlObjectType(String objectType) {
        if (objectType == null) {
            return null;
        }
        return switch (objectType) {
            case "PACKAGE_SPEC" -> "PKG_SPEC";
            case "PACKAGE_BODY" -> "PKG_BODY";
            default -> objectType;
        };
    }

    @Override
    protected String getDdlQueryFallback(Connection conn, String objectType, String objectName, String schema,
                                         SQLException error) {
        log.debug("GET_DDL for {} {}.{} failed: {}, trying SP_GETDDL", objectType, schema, objectName, error.getMessage());
        // Dameng DBMS_METADATA.GET_DDL may not support all object types (error 20008 etc.)
        // Fallback to SP_GETDDL system procedure
        String ddl = "";
        try {
            String spResult = callSpGetDdl(conn, schema, objectName);
            if (spResult != null && !spResult.isBlank()) {
                ddl = spResult.trim();
            }
        } catch (Exception ex) {
            log.debug("SP_GETDDL fallback for {} {}.{} also failed: {}", objectType, schema, objectName, ex.getMessage());
        }
        return ddl;
    }

    @Override
    protected void configureMetadataTransform(Connection conn) throws SQLException {
        setTransformParam(conn, "STORAGE", false);
        setTransformParam(conn, "TABLESPACE", false);
        setTransformParam(conn, "SEGMENT_ATTRIBUTES", false);
        setTransformParam(conn, "SQLTERMINATOR", true);
    }

    @Override
    protected void setEmbeddedTableConstraintsInMetadata(Connection conn, boolean include) throws SQLException {
        setTransformParam(conn, "CONSTRAINTS", include);
        setTransformParam(conn, "REF_CONSTRAINTS", include);
    }

    private void setTransformParam(Connection conn, String paramName, boolean value) throws SQLException {
        if (conn == null || paramName == null || paramName.isBlank()) {
            return;
        }
        String v = value ? "TRUE" : "FALSE";
        try (var stmt = conn.createStatement()) {
            stmt.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'"
                    + paramName + "'," + v + "); END;");
        } catch (SQLException e) {
            if (isUnsupportedTransformParam(e)) {
                log.debug("Dameng DBMS_METADATA transform param skipped: {}", paramName, e);
                return;
            }
            throw e;
        }
    }

    private boolean isUnsupportedTransformParam(SQLException e) {
        if (e == null) {
            return false;
        }
        int code = e.getErrorCode();
        if (code == -20006 || code == 20006) {
            return true;
        }
        String message = e.getMessage();
        return message != null
                && (message.contains("非法的参数数据")
                || message.toUpperCase(Locale.ROOT).contains("SET_TRANSFORM_PARAM"));
    }

    @Override
    public String printType(Connection conn, String objectName) throws SQLException {
        String schema = currentSchema(conn);
        String upperName = objectName.toUpperCase();

        // Dameng DBMS_METADATA.GET_DDL does not support TYPE (error 20008).
        // Use SP_GETDDL system procedure or all_source to reconstruct DDL.
        String spec = "";

        // 1. Try SP_GETDDL (Dameng built-in system procedure)
        try {
            spec = callSpGetDdl(conn, schema, upperName);
            if (spec != null) spec = spec.trim();
        } catch (Exception e) {
            log.debug("SP_GETDDL for TYPE {}.{} failed: {}", schema, upperName, e.getMessage());
            spec = "";
        }

        // 2. Try all_source with TYPE
        if (spec == null || spec.isBlank()) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, upperName, "TYPE");
                if (!fromDict.isBlank()) {
                    spec = fromDict.trim();
                    if (!spec.toUpperCase(Locale.ROOT).startsWith("CREATE")) {
                        spec = "CREATE OR REPLACE " + spec;
                    }
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for TYPE {}.{}: {}", schema, upperName, e.getMessage());
            }
        }

        // 3. Try all_source with CLASS (Dameng stores some types as CLASS)
        if (spec == null || spec.isBlank()) {
            try {
                String fromDict = fetchPlSqlFromAllSource(conn, schema, upperName, "CLASS");
                if (!fromDict.isBlank()) {
                    spec = fromDict.trim();
                    if (!spec.toUpperCase(Locale.ROOT).startsWith("CREATE")) {
                        spec = "CREATE OR REPLACE " + spec;
                    }
                }
            } catch (SQLException e) {
                log.debug("ALL_SOURCE fallback for CLASS {}.{}: {}", schema, upperName, e.getMessage());
            }
        }

        // 4. Fallback to GET_DDL
        if (spec == null || spec.isBlank()) {
            try {
                configureMetadataTransform(conn);
                spec = getDdl(conn, "TYPE", upperName, schema);
            } catch (Exception e) {
                log.debug("GET_DDL for TYPE {}.{} failed: {}", schema, upperName, e.getMessage());
                spec = "";
            }
        }

        String body = "";


        StringBuilder sb = new StringBuilder();
        try {
            String fromDict = fetchPlSqlFromAllSource(conn, schema, upperName, "TYPE BODY");
            if (!fromDict.isBlank()) {
                body = fromDict.trim();
                if (!body.toUpperCase(Locale.ROOT).startsWith("CREATE")) {
                    body = "CREATE OR REPLACE " + body;
                }
            }
        } catch (SQLException e) {
            log.debug("ALL_SOURCE fallback for TYPE BODY {}.{}: {}", schema, upperName, e.getMessage());
        }
        if (body.isBlank()) {
            try {
                configureMetadataTransform(conn);
                body = getDdl(conn, "TYPE_BODY", upperName, schema);
            } catch (Exception e) {
                log.debug("GET_DDL for TYPE_BODY {}.{} failed: {}", schema, upperName, e.getMessage());
                body = "";
            }
        }
        if (!spec.isEmpty()) {
            sb.append(spec).append("\n/\n");
        }
        if (!body.isEmpty() && !body.startsWith("-- ERROR")) {
            sb.append(body).append("\n/\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    protected long appendQueuesDdl(Connection conn, StringBuilder ddl, String schema,
                                   long completed, LongConsumer progressCallback) throws SQLException {
        // Dameng does not support queue export
        return completed;
    }

    @Override
    protected long appendSchedulerJobsDdl(Connection conn, StringBuilder ddl, String schema,
                                          long completed, LongConsumer progressCallback) throws SQLException {
        // Dameng does not support scheduler job export
        return completed;
    }

    /**
     * Call Dameng built-in system procedure {@code SP_GETDDL} to get object DDL.
     * Falls back to querying SYSOBJECTS.DEFINITION if SP_GETDDL is not available.
     */
    private String callSpGetDdl(Connection conn, String schema, String objectName) throws SQLException {
        // Try SP_GETDDL first
        try {
            String sql = "SELECT SP_GETDDL(?, ?)";
            SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
            String result = runner.queryOne(sql, List.of(schema, objectName), rs -> readDdlClob(rs, 1));
            if (result != null && !result.isBlank()) {
                return result;
            }
        } catch (Exception e) {
            log.debug("SP_GETDDL not available for {}.{}: {}", schema, objectName, e.getMessage());
        }

        // Fallback: query SYSOBJECTS.DEFINITION
        try {
            String sql = "SELECT DEFINITION FROM SYSOBJECTS WHERE SCHID = (SELECT ID FROM SYSOBJECTS WHERE NAME = ? AND TYPE$ = 'SCH') AND NAME = ? AND DEFINITION IS NOT NULL";
            SqlRunner runner = new SqlRunner(conn, QUERY_TIMEOUT);
            return runner.queryOne(sql, List.of(schema, objectName), rs -> readDdlClob(rs, 1));
        } catch (Exception e) {
            log.debug("SYSOBJECTS fallback for {}.{}: {}", schema, objectName, e.getMessage());
            return null;
        }
    }
}
