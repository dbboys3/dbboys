package com.dbboys.dialect.sqlserver;

import com.dbboys.core.DdlRepository;
import com.dbboys.infra.db.SqlRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * SQL Server DDL export. Views/procedures/functions/triggers return their
 * original text from sys.sql_modules; tables/indexes/sequences are
 * reconstructed from sys catalog metadata.
 */
public final class SqlserverDdlRepository implements DdlRepository {

    private static final int QUERY_TIMEOUT_SECONDS = 30;

    /** 内置模式：实例创建后已存在，导出时不生成 CREATE SCHEMA。 */
    private static final Set<String> DEFAULT_SCHEMAS = Set.of("dbo", "guest", "sys", "information_schema");

    private static final String SQL_MODULE_TEXT = """
            SELECT m.definition
            FROM sys.sql_modules m
            JOIN sys.objects o ON o.object_id = m.object_id
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ?
              AND o.name = ?
            """;

    private static final String SQL_COLUMNS = """
            SELECT c.name,
                   CASE WHEN ty.is_user_defined = 1
                        THEN SCHEMA_NAME(ty.schema_id) + '.' + ty.name
                        ELSE ty.name END AS type_name,
                   c.max_length, c.precision, c.scale,
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
        return text == null ? "-- " + label + " " + objectName : qualifyModuleName(text.strip(), parts[0], parts[1]);
    }

    /**
     * sys.sql_modules 保存的是创建时的原文，对象名可能未带模式限定
     * （CREATE VIEW v AS ...）。导出统一补成 [schema].[name]，已带正确限定的原文不动。
     */
    private static String qualifyModuleName(String ddlText, String schema, String name) {
        if (ddlText == null || schema == null || schema.isBlank() || name == null || name.isBlank()) {
            return ddlText;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?im)^(\\s*CREATE\\s+(?:OR\\s+ALTER\\s+)?\\w+\\s+)(\\S+)")
                .matcher(ddlText);
        if (!m.find()) {
            return ddlText;
        }
        String token = m.group(2);
        int paren = token.indexOf('(');
        String namePart = paren >= 0 ? token.substring(0, paren) : token;
        String suffix = paren >= 0 ? token.substring(paren) : "";
        String unquoted = namePart.replace("[", "").replace("]", "").replace("\"", "");
        if (unquoted.equalsIgnoreCase(schema + "." + name)) {
            return ddlText;
        }
        return ddlText.substring(0, m.start(2)) + qualified(schema, name) + suffix + ddlText.substring(m.end(2));
    }

    @Override
    public String printPackage(Connection conn, String objectName) {
        return "--";
    }

    private static final String SQL_TYPE_INFO = """
            SELECT TYPE_NAME(t.system_type_id) AS base_type,
                   t.max_length, t.precision, t.scale, t.is_nullable,
                   CASE WHEN tt.user_type_id IS NOT NULL THEN 1 ELSE 0 END AS is_table_type,
                   tt.type_table_object_id
            FROM sys.types t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.table_types tt ON tt.user_type_id = t.user_type_id
            WHERE s.name = ?
              AND t.name = ?
              AND t.is_user_defined = 1
            """;

    private static final String SQL_TYPE_COLUMNS = """
            SELECT c.name, ty.name AS type_name, c.max_length, c.precision, c.scale,
                   c.is_nullable, c.is_identity,
                   dc.definition AS column_default
            FROM sys.columns c
            JOIN sys.types ty ON ty.user_type_id = c.user_type_id
            LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
            WHERE c.object_id = ?
            ORDER BY c.column_id
            """;

    private static final String SQL_TYPE_PRIMARY_KEY = """
            SELECT kc.name, c.name AS column_name
            FROM sys.key_constraints kc
            JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE kc.parent_object_id = ?
              AND kc.type = 'PK'
            ORDER BY ic.key_ordinal
            """;

    /** 用户自定义类型：别名类型重建 CREATE TYPE ... FROM，表类型重建 CREATE TYPE ... AS TABLE。 */
    @Override
    public String printType(Connection conn, String objectName) throws Exception {
        String[] parts = SqlserverMetadataRepository.splitQualified(objectName, currentSchema(conn));
        SqlRunner runner = runner(conn);
        String[] info = runner.queryOne(SQL_TYPE_INFO, List.of(parts[0], parts[1]), rs -> new String[]{
                rs.getString("base_type"),
                String.valueOf(rs.getInt("max_length")),
                String.valueOf(rs.getInt("precision")),
                String.valueOf(rs.getInt("scale")),
                String.valueOf(rs.getBoolean("is_nullable")),
                String.valueOf(rs.getBoolean("is_table_type")),
                String.valueOf(rs.getInt("type_table_object_id"))
        });
        if (info == null) {
            return "-- type " + objectName;
        }
        StringBuilder ddl = new StringBuilder("CREATE TYPE ").append(qualified(parts[0], parts[1]));
        if (!Boolean.parseBoolean(info[5])) {
            ddl.append(" FROM ").append(SqlserverMetadataRepository.formatType(info[0],
                    Integer.parseInt(info[1]), Integer.parseInt(info[2]), Integer.parseInt(info[3])));
            ddl.append(Boolean.parseBoolean(info[4]) ? " NULL;" : " NOT NULL;");
            return ddl.toString();
        }
        int tableObjectId = Integer.parseInt(info[6]);
        List<String> cols = runner.query(SQL_TYPE_COLUMNS, List.of(tableObjectId), rs -> {
            StringBuilder col = new StringBuilder("  ").append(quote(rs.getString("name"))).append(' ');
            col.append(SqlserverMetadataRepository.formatType(rs.getString("type_name"),
                    rs.getInt("max_length"), rs.getInt("precision"), rs.getInt("scale")));
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
        List<String[]> pk = runner.query(SQL_TYPE_PRIMARY_KEY, List.of(tableObjectId),
                rs -> new String[]{rs.getString("name"), rs.getString("column_name")});
        if (!pk.isEmpty()) {
            List<String> pkCols = new ArrayList<>();
            for (String[] row : pk) {
                pkCols.add(quote(row[1]));
            }
            cols.add("  CONSTRAINT " + quote(pk.get(0)[0]) + " PRIMARY KEY (" + String.join(", ", pkCols) + ")");
        }
        return ddl.append(" AS TABLE (\n").append(String.join(",\n", cols)).append("\n);").toString();
    }

    private static final String SQL_TYPE_NAMES = """
            SELECT t.name
            FROM sys.types t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND t.is_user_defined = 1
            ORDER BY t.name
            """;

    private static final String SQL_INDEX_ROWS = """
            SELECT t.name AS table_name, i.name, i.type_desc, i.is_unique
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND i.name IS NOT NULL
              AND i.is_primary_key = 0
              AND i.is_unique_constraint = 0
            ORDER BY t.name, i.name
            """;

    private static final String SQL_FOREIGN_KEYS = """
            SELECT fk.name AS fk_name,
                   t.name AS table_name,
                   rs.name AS ref_schema_name,
                   rt.name AS ref_table_name,
                   fk.delete_referential_action,
                   fk.update_referential_action,
                   STUFF((SELECT ', ' + QUOTENAME(pc.name)
                          FROM sys.foreign_key_columns fkc
                          JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id
                                                AND pc.column_id = fkc.parent_column_id
                          WHERE fkc.constraint_object_id = fk.object_id
                          ORDER BY fkc.constraint_column_id
                          FOR XML PATH('')), 1, 2, '') AS columns_csv,
                   STUFF((SELECT ', ' + QUOTENAME(rc.name)
                          FROM sys.foreign_key_columns fkc
                          JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id
                                                AND rc.column_id = fkc.referenced_column_id
                          WHERE fkc.constraint_object_id = fk.object_id
                          ORDER BY fkc.constraint_column_id
                          FOR XML PATH('')), 1, 2, '') AS ref_columns_csv
            FROM sys.foreign_keys fk
            JOIN sys.tables t ON t.object_id = fk.parent_object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
            JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
            WHERE s.name = ?
            ORDER BY t.name, fk.name
            """;

    @Override
    public String printDatabase(Connection conn, String databaseName) throws Exception {
        return printDatabase(conn, databaseName, null);
    }

    @Override
    public String printDatabase(Connection conn, String databaseName, LongConsumer progressCallback) throws Exception {
        DdlRepository.DatabaseDdlParts parts = exportDatabaseDdlParts(conn, databaseName, progressCallback);
        if (parts.getPostDataSql().isBlank()) {
            return parts.getPreDataSql();
        }
        return parts.getPreDataSql() + "\n\n" + parts.getPostDataSql();
    }

    /**
     * 模式导出：pre 段为建模式/类型/序列/表/视图/同义词/函数/过程，post 段为
     * 独立索引、外键约束、触发器（数据导入完成后执行）。语句一律分号结束，不使用 GO。
     */
    @Override
    public DdlRepository.DatabaseDdlParts exportDatabaseDdlParts(Connection conn,
                                                                 String databaseName,
                                                                 LongConsumer progressCallback) throws Exception {
        String schema = databaseName == null ? "" : databaseName.trim();
        SqlRunner runner = runner(conn);

        List<String> types = runner.query(SQL_TYPE_NAMES, List.of(schema), rs -> rs.getString(1));
        List<String> sequences = runner.query(SQL_SEQUENCE_NAMES, List.of(schema), rs -> rs.getString(1));
        List<String> tables = runner.query(SQL_TABLE_NAMES, List.of(schema), rs -> rs.getString(1));
        List<String> views = runner.query(objectNamesSql("'V'"), List.of(schema), rs -> rs.getString(1));
        List<String> synonyms = runner.query(SQL_SYNONYM_NAMES, List.of(schema), rs -> rs.getString(1));
        List<String> functions = runner.query(objectNamesSql("'FN', 'IF', 'TF'"), List.of(schema), rs -> rs.getString(1));
        List<String> procedures = runner.query(objectNamesSql("'P'"), List.of(schema), rs -> rs.getString(1));
        List<String> triggers = runner.query(SQL_TRIGGER_NAMES, List.of(schema), rs -> rs.getString(1));
        List<String[]> indexRows = runner.query(SQL_INDEX_ROWS, List.of(schema), rs -> new String[]{
                rs.getString("table_name"), rs.getString("name"), rs.getString("type_desc"),
                String.valueOf(rs.getBoolean("is_unique"))});
        List<String[]> fks = runner.query(SQL_FOREIGN_KEYS, List.of(schema), rs -> new String[]{
                rs.getString("fk_name"), rs.getString("table_name"),
                rs.getString("ref_schema_name"), rs.getString("ref_table_name"),
                String.valueOf(rs.getShort("delete_referential_action")),
                String.valueOf(rs.getShort("update_referential_action")),
                rs.getString("columns_csv"), rs.getString("ref_columns_csv")});

        long total = (long) types.size() + sequences.size() + tables.size() + views.size()
                + synonyms.size() + functions.size() + procedures.size()
                + indexRows.size() + fks.size() + triggers.size();
        long completed = 0;

        StringBuilder pre = new StringBuilder();
        if (!schema.isBlank() && !DEFAULT_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
            // 非内置模式补 CREATE SCHEMA
            appendStatement(pre, "CREATE SCHEMA " + quote(schema));
        }
        for (String name : types) {
            appendStatement(pre, printType(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String name : sequences) {
            appendStatement(pre, printSequence(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String name : tables) {
            appendStatement(pre, printTable(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String name : views) {
            appendStatement(pre, printView(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String name : synonyms) {
            appendStatement(pre, printSynonym(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String name : functions) {
            appendStatement(pre, printFunction(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String name : procedures) {
            appendStatement(pre, printProcedure(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }

        StringBuilder post = new StringBuilder();
        for (String[] index : indexRows) {
            appendStatement(post, buildIndexDdl(conn, schema, index[0], index[1], index[2],
                    Boolean.parseBoolean(index[3])));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String[] fk : fks) {
            appendStatement(post, buildForeignKeyDdl(schema, fk));
            completed = reportProgress(progressCallback, completed, total);
        }
        for (String name : triggers) {
            appendStatement(post, printTrigger(conn, qualified(schema, name)));
            completed = reportProgress(progressCallback, completed, total);
        }
        return new DdlRepository.DatabaseDdlParts(pre.toString(), post.toString());
    }

    /** fk row: 0=name 1=table 2=refSchema 3=refTable 4=deleteAction 5=updateAction 6=cols 7=refCols */
    private static String buildForeignKeyDdl(String schema, String[] fk) {
        StringBuilder ddl = new StringBuilder("ALTER TABLE ").append(qualified(schema, fk[1]))
                .append(" ADD CONSTRAINT ").append(quote(fk[0]))
                .append(" FOREIGN KEY (").append(fk[6]).append(")")
                .append(" REFERENCES ").append(qualified(fk[2], fk[3])).append(" (").append(fk[7]).append(")");
        ddl.append(switch (fk[4]) {
            case "1" -> " ON DELETE CASCADE";
            case "2" -> " ON DELETE SET NULL";
            case "3" -> " ON DELETE SET DEFAULT";
            default -> "";
        });
        ddl.append(switch (fk[5]) {
            case "1" -> " ON UPDATE CASCADE";
            case "2" -> " ON UPDATE SET NULL";
            case "3" -> " ON UPDATE SET DEFAULT";
            default -> "";
        });
        return ddl.toString();
    }

    private static String objectNamesSql(String types) {
        return SQL_OBJECT_NAMES.replace("{TYPES}", types);
    }

    /** 追加一条语句：分号结束（模块文本可能不带分号），语句间空行分隔，不使用 GO。 */
    private static void appendStatement(StringBuilder ddl, String statement) {
        if (statement == null || statement.isBlank()) {
            return;
        }
        if (ddl.length() > 0) {
            ddl.append("\n\n");
        }
        String text = statement.strip();
        ddl.append(text);
        if (!text.endsWith(";")) {
            ddl.append(';');
        }
    }

    private static long reportProgress(LongConsumer progressCallback, long completed, long total) {
        if (progressCallback != null) {
            progressCallback.accept(completed + 1);
        }
        return completed + 1;
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
