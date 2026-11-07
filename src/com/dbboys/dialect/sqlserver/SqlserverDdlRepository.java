package com.dbboys.dialect.sqlserver;

import com.dbboys.core.DdlRepository;
import com.dbboys.infra.db.SqlRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server DDL export. Views/procedures/functions/triggers return their
 * original text from sys.sql_modules; tables/indexes/sequences are
 * reconstructed from sys catalog metadata.
 */
public final class SqlserverDdlRepository implements DdlRepository {

    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private static final String SQL_MODULE_TEXT = """
            SELECT m.definition
            FROM sys.sql_modules m
            JOIN sys.objects o ON o.object_id = m.object_id
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ?
              AND o.name = ?
            """;

    private static final String SQL_COLUMNS = """
            SELECT c.name, ty.name AS type_name, c.max_length, c.precision, c.scale,
                   c.is_nullable, c.is_identity,
                   dc.definition AS column_default
            FROM sys.columns c
            JOIN sys.types ty ON ty.user_type_id = c.user_type_id
            JOIN sys.tables t ON t.object_id = c.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
            WHERE s.name = ?
              AND t.name = ?
            ORDER BY c.column_id
            """;

    private static final String SQL_PRIMARY_KEY = """
            SELECT kc.name, c.name AS column_name
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.key_constraints kc ON kc.parent_object_id = t.object_id AND kc.type = 'PK'
            JOIN sys.index_columns ic ON ic.object_id = t.object_id AND ic.index_id = kc.unique_index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE s.name = ?
              AND t.name = ?
            ORDER BY ic.key_ordinal
            """;

    private static final String SQL_INDEXES_OF_TABLE = """
            SELECT i.name, i.type_desc, i.is_unique
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND t.name = ?
              AND i.name IS NOT NULL
              AND i.is_primary_key = 0
              AND i.is_unique_constraint = 0
            ORDER BY i.name
            """;

    private static final String SQL_INDEX_COLUMNS = """
            SELECT c.name, ic.is_descending_key
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE s.name = ?
              AND t.name = ?
              AND i.name = ?
            ORDER BY ic.key_ordinal, ic.index_column_id
            """;

    private static final String SQL_INDEX_TABLE = """
            SELECT s.name AS schema_name, t.name AS table_name, i.type_desc, i.is_unique
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND i.name = ?
            """;

    private static final String SQL_SEQUENCE_DETAIL = """
            SELECT start_value, increment, minimum_value, maximum_value, is_cycling, cache_size
            FROM sys.sequences sq
            JOIN sys.schemas s ON s.schema_id = sq.schema_id
            WHERE s.name = ?
              AND sq.name = ?
            """;

    private static final String SQL_SYNONYM_BASE = """
            SELECT sn.base_object_name
            FROM sys.synonyms sn
            JOIN sys.schemas s ON s.schema_id = sn.schema_id
            WHERE s.name = ?
              AND sn.name = ?
            """;

    private static final String SQL_TABLE_NAMES = """
            SELECT t.name
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND t.is_ms_shipped = 0
            ORDER BY t.name
            """;

    private static final String SQL_OBJECT_NAMES = """
            SELECT o.name
            FROM sys.objects o
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ?
              AND o.type IN ({TYPES})
              AND o.is_ms_shipped = 0
            ORDER BY o.name
            """;

    private static final String SQL_SEQUENCE_NAMES = """
            SELECT sq.name
            FROM sys.sequences sq
            JOIN sys.schemas s ON s.schema_id = sq.schema_id
            WHERE s.name = ?
            ORDER BY sq.name
            """;

    private static final String SQL_SYNONYM_NAMES = """
            SELECT sn.name
            FROM sys.synonyms sn
            JOIN sys.schemas s ON s.schema_id = sn.schema_id
            WHERE s.name = ?
            ORDER BY sn.name
            """;

    private static final String SQL_TRIGGER_NAMES = """
            SELECT tr.name
            FROM sys.triggers tr
            JOIN sys.tables t ON t.object_id = tr.parent_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND tr.parent_class = 1
            ORDER BY tr.name
            """;

    // ==================================================================

    @Override
    public String printTable(Connection conn, String objectName) throws Exception {
        String schema = currentSchema(conn);
        String[] parts = SqlserverMetadataRepository.splitQualified(objectName, schema);
        String tableSchema = parts[0];
        String tableName = parts[1];

        SqlRunner runner = runner(conn);
        List<String> columnDefs = runner.query(SQL_COLUMNS, List.of(tableSchema, tableName), rs -> {
            StringBuilder col = new StringBuilder("  ");
            col.append(quote(rs.getString("name"))).append(' ');
            col.append(SqlserverMetadataRepository.formatType(
                    rs.getString("type_name"), rs.getInt("max_length"), rs.getInt("precision"), rs.getInt("scale")));
            if (rs.getBoolean("is_identity")) {
                col.append(" IDENTITY(1,1)");
            }
            String defaultValue = rs.getString("column_default");
            if (defaultValue != null && !defaultValue.isBlank()) {
                col.append(" DEFAULT ").append(defaultValue.trim());
            }
            col.append(rs.getBoolean("is_nullable") ? " NULL" : " NOT NULL");
            return col.toString();
        });

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(qualified(tableSchema, tableName)).append(" (\n");
        ddl.append(String.join(",\n", columnDefs));

        List<String[]> pk = runner.query(SQL_PRIMARY_KEY, List.of(tableSchema, tableName),
                rs -> new String[]{rs.getString("name"), rs.getString("column_name")});
        if (!pk.isEmpty()) {
            List<String> cols = new ArrayList<>();
            for (String[] row : pk) {
                cols.add(quote(row[1]));
            }
            ddl.append(",\n  CONSTRAINT ").append(quote(pk.get(0)[0]))
                    .append(" PRIMARY KEY (").append(String.join(", ", cols)).append(")");
        }
        ddl.append("\n);");
        return ddl.toString();
    }

    @Override
    public String printTableWithDependencies(Connection conn, String objectName) throws Exception {
        StringBuilder ddl = new StringBuilder(printTable(conn, objectName));
        String[] parts = SqlserverMetadataRepository.splitQualified(objectName, currentSchema(conn));
        SqlRunner runner = runner(conn);
        List<String[]> indexes = runner.query(SQL_INDEXES_OF_TABLE, List.of(parts[0], parts[1]),
                rs -> new String[]{rs.getString("name"), rs.getString("type_desc"), String.valueOf(rs.getBoolean("is_unique"))});
        for (String[] index : indexes) {
            ddl.append("\n\n").append(buildIndexDdl(conn, parts[0], parts[1],
                    index[0], index[1], Boolean.parseBoolean(index[2])));
        }
        return ddl.toString();
    }

    @Override
    public String printView(Connection conn, String objectName) throws Exception {
        return printModule(conn, objectName, "view");
    }

    @Override
    public String printIndex(Connection conn, String objectName) throws Exception {
        String[] parts = SqlserverMetadataRepository.splitQualified(objectName, currentSchema(conn));
        String[] info = runner(conn).queryOne(SQL_INDEX_TABLE, List.of(parts[0], parts[1]),
                rs -> new String[]{rs.getString("table_name"), rs.getString("type_desc"),
                        String.valueOf(rs.getBoolean("is_unique"))});
        if (info == null) {
            return "-- index " + objectName;
        }
        return buildIndexDdl(conn, parts[0], info[0], parts[1], info[1], Boolean.parseBoolean(info[2]));
    }

    private String buildIndexDdl(Connection conn, String schemaName, String tableName,
                                 String indexName, String typeDesc, boolean unique) throws SQLException {
        List<String> cols = runner(conn).query(SQL_INDEX_COLUMNS, List.of(schemaName, tableName, indexName), rs -> {
            String name = quote(rs.getString("name"));
            return rs.getBoolean("is_descending_key") ? name + " DESC" : name;
        });
        String upperType = typeDesc == null ? "" : typeDesc.toUpperCase(java.util.Locale.ROOT);
        String clustered = upperType.contains("NONCLUSTERED") ? "NONCLUSTERED"
                : upperType.contains("CLUSTERED") ? "CLUSTERED" : "NONCLUSTERED";
        return "CREATE " + (unique ? "UNIQUE " : "") + clustered + " INDEX " + quote(indexName)
                + " ON " + qualified(schemaName, tableName) + " (" + String.join(", ", cols) + ");";
    }

    @Override
    public String printSequence(Connection conn, String objectName) throws Exception {
        String[] parts = SqlserverMetadataRepository.splitQualified(objectName, currentSchema(conn));
        return runner(conn).queryOne(SQL_SEQUENCE_DETAIL, List.of(parts[0], parts[1]), rs -> {
            StringBuilder ddl = new StringBuilder("CREATE SEQUENCE ").append(qualified(parts[0], parts[1]));
            ddl.append("\n  START WITH ").append(rs.getLong("start_value"));
            ddl.append("\n  INCREMENT BY ").append(rs.getLong("increment"));
            ddl.append("\n  MINVALUE ").append(rs.getLong("minimum_value"));
            ddl.append("\n  MAXVALUE ").append(rs.getLong("maximum_value"));
            ddl.append("\n  ").append(rs.getBoolean("is_cycling") ? "CYCLE" : "NO CYCLE");
            long cacheSize = rs.getLong("cache_size");
            ddl.append("\n  ").append(rs.wasNull() ? "NO CACHE" : "CACHE " + cacheSize);
            return ddl.append(";").toString();
        });
    }

    @Override
    public String printSynonym(Connection conn, String objectName) throws Exception {
        String[] parts = SqlserverMetadataRepository.splitQualified(objectName, currentSchema(conn));
        return runner(conn).queryOne(SQL_SYNONYM_BASE, List.of(parts[0], parts[1]), rs ->
                "CREATE SYNONYM " + qualified(parts[0], parts[1]) + " FOR " + rs.getString(1) + ";");
    }

    @Override
    public String printFunction(Connection conn, String objectName) throws Exception {
        return printModule(conn, objectName, "function");
    }

    @Override
    public String printProcedure(Connection conn, String objectName) throws Exception {
        return printModule(conn, objectName, "procedure");
    }

    @Override
    public String printTrigger(Connection conn, String objectName) throws Exception {
        return printModule(conn, objectName, "trigger");
    }

    private String printModule(Connection conn, String objectName, String label) throws Exception {
        String[] parts = SqlserverMetadataRepository.splitQualified(objectName, currentSchema(conn));
        String text = runner(conn).queryOne(SQL_MODULE_TEXT, List.of(parts[0], parts[1]), rs -> rs.getString(1));
        return text == null ? "-- " + label + " " + objectName : text.strip();
    }

    @Override
    public String printPackage(Connection conn, String objectName) {
        return "--";
    }

    @Override
    public String printDatabase(Connection conn, String databaseName) throws Exception {
        String schema = databaseName == null ? "" : databaseName.trim();
        StringBuilder ddl = new StringBuilder();
        SqlRunner runner = runner(conn);

        List<String> sequences = runner.query(SQL_SEQUENCE_NAMES, List.of(schema), rs -> rs.getString(1));
        for (String name : sequences) {
            appendObject(ddl, printSequence(conn, qualified(schema, name)));
        }
        List<String> tables = runner.query(SQL_TABLE_NAMES, List.of(schema), rs -> rs.getString(1));
        for (String name : tables) {
            appendObject(ddl, printTableWithDependencies(conn, qualified(schema, name)));
        }
        List<String> views = runner.query(objectNamesSql("'V'"), List.of(schema), rs -> rs.getString(1));
        for (String name : views) {
            appendObject(ddl, printView(conn, qualified(schema, name)));
        }
        List<String> synonyms = runner.query(SQL_SYNONYM_NAMES, List.of(schema), rs -> rs.getString(1));
        for (String name : synonyms) {
            appendObject(ddl, printSynonym(conn, qualified(schema, name)));
        }
        List<String> functions = runner.query(objectNamesSql("'FN', 'IF', 'TF'"), List.of(schema), rs -> rs.getString(1));
        for (String name : functions) {
            appendObject(ddl, printFunction(conn, qualified(schema, name)));
        }
        List<String> procedures = runner.query(objectNamesSql("'P'"), List.of(schema), rs -> rs.getString(1));
        for (String name : procedures) {
            appendObject(ddl, printProcedure(conn, qualified(schema, name)));
        }
        List<String> triggers = runner.query(SQL_TRIGGER_NAMES, List.of(schema), rs -> rs.getString(1));
        for (String name : triggers) {
            appendObject(ddl, printTrigger(conn, qualified(schema, name)));
        }
        return ddl.toString();
    }

    private static String objectNamesSql(String types) {
        return SQL_OBJECT_NAMES.replace("{TYPES}", types);
    }

    private static void appendObject(StringBuilder ddl, String objectDdl) {
        if (objectDdl == null || objectDdl.isBlank()) {
            return;
        }
        if (ddl.length() > 0) {
            ddl.append("\n\nGO\n\n");
        }
        ddl.append(objectDdl);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    static String qualified(String schema, String name) {
        if (schema == null || schema.isBlank()) {
            return quote(name);
        }
        return quote(schema) + "." + quote(name);
    }

    static String quote(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            return value;
        }
        return "[" + value.replace("]", "]]" ) + "]";
    }

    private static String currentSchema(Connection conn) throws SQLException {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema.trim();
            }
        } catch (Exception ignored) {
        }
        String schema = runner(conn).queryOne("SELECT SCHEMA_NAME()", null, rs -> rs.getString(1));
        return schema != null && !schema.isBlank() ? schema.trim() : "dbo";
    }

    private static SqlRunner runner(Connection conn) {
        return new SqlRunner(conn, QUERY_TIMEOUT_SECONDS);
    }
}
