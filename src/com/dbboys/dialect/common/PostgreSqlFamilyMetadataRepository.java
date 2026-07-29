package com.dbboys.dialect.common;

import com.dbboys.core.MetadataRepository;
import com.dbboys.infra.db.SqlRunner;
import com.dbboys.model.*;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared metadata implementation for the PostgreSQL family of dialects.
 * Uses {@code information_schema} and {@code pg_catalog} for all metadata queries.
 * Dialect differences are isolated behind the hook methods below.
 */
public abstract class PostgreSqlFamilyMetadataRepository implements MetadataRepository {
    protected static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    // ------------------------------------------------------------------
    // Databases (real PG databases, tree level 1)
    // ------------------------------------------------------------------

    private static final String SQL_DATABASES = """
            SELECT
                d.datname,
                pg_catalog.pg_get_userbyid(d.datdba) AS owner,
                COALESCE(pg_catalog.obj_description(d.oid), '') AS comment,
                pg_catalog.pg_size_pretty(pg_catalog.pg_database_size(d.datname)) AS total_size
            FROM pg_catalog.pg_database d
            WHERE NOT d.datistemplate
              AND d.datallowconn
            ORDER BY d.datname
            """;

    // ------------------------------------------------------------------
    // Schemas of the current database (tree level 2)
    // ------------------------------------------------------------------

    private static final String SQL_SCHEMAS = """
            SELECT
                s.schema_name,
                pg_catalog.pg_get_userbyid(nspowner) AS owner,
                COALESCE(pg_catalog.obj_description(n.oid), '') AS comment,
                pg_catalog.pg_size_pretty(
                    COALESCE(SUM(pg_catalog.pg_total_relation_size(c.oid)), 0)
                ) AS total_size
            FROM information_schema.schemata s
            JOIN pg_catalog.pg_namespace n ON n.nspname = s.schema_name
            LEFT JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid
            WHERE s.schema_name NOT IN ('pg_catalog', 'information_schema')
              AND s.schema_name NOT LIKE 'pg_toast%'
              AND s.schema_name NOT LIKE 'pg_temp%'
            GROUP BY s.schema_name, nspowner, n.oid
            ORDER BY s.schema_name
            """;

    private static final String SQL_SCHEMA_INFO = """
            SELECT
                s.schema_name,
                pg_catalog.pg_get_userbyid(nspowner) AS owner,
                COALESCE(pg_catalog.obj_description(n.oid), '') AS comment,
                pg_catalog.pg_size_pretty(
                    COALESCE(SUM(pg_catalog.pg_total_relation_size(c.oid)), 0)
                ) AS total_size
            FROM information_schema.schemata s
            JOIN pg_catalog.pg_namespace n ON n.nspname = s.schema_name
            LEFT JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid
            WHERE s.schema_name = ?
            GROUP BY s.schema_name, nspowner, n.oid
            """;

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    private static final String SQL_USERS = """
            SELECT usename AS username
            FROM pg_catalog.pg_user
            ORDER BY usename
            """;

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    private static final String SQL_USER_TABLES = """
            SELECT
                t.table_schema,
                t.table_name,
                COALESCE(pg_catalog.obj_description(c.oid), '') AS table_comment,
                COALESCE(s.n_live_tup, 0) AS num_rows,
                pg_catalog.pg_size_pretty(pg_catalog.pg_total_relation_size(c.oid)) AS total_size,
                c.reltuples::bigint AS reltuples,
                c.relpages::bigint AS relpages
            FROM information_schema.tables t
            JOIN pg_catalog.pg_class c ON c.relname = t.table_name
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema
            LEFT JOIN pg_catalog.pg_stat_user_tables s ON s.relid = c.oid
            WHERE t.table_schema = ?
              AND t.table_type = 'BASE TABLE'
            ORDER BY t.table_name
            """;

    private static final String SQL_USER_TABLE_DETAIL = """
            SELECT
                t.table_schema,
                t.table_name,
                COALESCE(pg_catalog.obj_description(c.oid), '') AS table_comment,
                COALESCE(s.n_live_tup, 0) AS num_rows,
                pg_catalog.pg_size_pretty(pg_catalog.pg_total_relation_size(c.oid)) AS total_size
            FROM information_schema.tables t
            JOIN pg_catalog.pg_class c ON c.relname = t.table_name
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema
            LEFT JOIN pg_catalog.pg_stat_user_tables s ON s.relid = c.oid
            WHERE t.table_schema = ?
              AND t.table_name = ?
              AND t.table_type = 'BASE TABLE'
            """;

    private static final String SQL_USER_TABLES_COUNT = """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            """;

    private static final String SQL_USER_TABLES_SIZE = """
            SELECT pg_catalog.pg_size_pretty(
                COALESCE(SUM(pg_catalog.pg_total_relation_size(c.oid)), 0)
            )
            FROM information_schema.tables t
            JOIN pg_catalog.pg_class c ON c.relname = t.table_name
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema
            WHERE t.table_schema = ?
              AND t.table_type = 'BASE TABLE'
            """;

    private static final String SQL_SYSTEM_TABLES = """
            SELECT
                t.schemaname AS table_schema,
                t.relname AS table_name,
                COALESCE(pg_catalog.obj_description(c.oid), '') AS table_comment,
                COALESCE(t.n_live_tup, 0) AS num_rows,
                pg_catalog.pg_size_pretty(pg_catalog.pg_total_relation_size(c.oid)) AS total_size
            FROM pg_catalog.pg_stat_all_tables t
            JOIN pg_catalog.pg_class c ON c.oid = t.relid
            WHERE t.schemaname = 'pg_catalog'
            ORDER BY t.relname
            """;

    private static final String SQL_SYSTEM_TABLES_COUNT = """
            SELECT COUNT(*)
            FROM pg_catalog.pg_stat_all_tables
            WHERE schemaname = 'pg_catalog'
            """;

    private static final String SQL_SYSTEM_TABLES_SIZE = """
            SELECT pg_catalog.pg_size_pretty(
                COALESCE(SUM(pg_catalog.pg_total_relation_size(relid)), 0)
            )
            FROM pg_catalog.pg_stat_all_tables
            WHERE schemaname = 'pg_catalog'
            """;

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    private static final String SQL_VIEWS = """
            SELECT
                t.table_schema,
                t.table_name
            FROM information_schema.tables t
            WHERE t.table_schema = ?
              AND t.table_type = 'VIEW'
            ORDER BY t.table_name
            """;

    private static final String SQL_VIEW_COUNT = """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'VIEW'
            """;

    // ------------------------------------------------------------------
    // Columns (pg_attribute + format_type: exact type text incl. arrays/domains)
    // ------------------------------------------------------------------

    private static final String SQL_COLUMNS = """
            SELECT
                a.attname AS column_name,
                pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                a.attnotnull AS not_null,
                COALESCE(pg_catalog.pg_get_expr(d.adbin, d.adrelid), '') AS column_default,
                a.attnum::int AS colno,
                COALESCE(pg_catalog.col_description(a.attrelid, a.attnum), '') AS col_comment
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE n.nspname = ?
              AND c.relname = ?
              AND c.relkind IN ('r', 'v', 'm', 'f', 'p')
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

    // ------------------------------------------------------------------
    // Indexes
    // ------------------------------------------------------------------

    private static final String SQL_INDEXES = """
            SELECT
                n.nspname AS schemaname,
                c.relname AS tablename,
                ic.relname AS indexname,
                pg_catalog.pg_get_indexdef(i.indexrelid) AS indexdef,
                i.indisunique AS is_unique,
                i.indisprimary AS is_primary,
                i.indisclustered AS is_clustered,
                pg_catalog.pg_size_pretty(pg_catalog.pg_relation_size(i.indexrelid)) AS index_size
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
            JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
            ORDER BY ic.relname
            """;

    private static final String SQL_INDEX_COUNT = """
            SELECT COUNT(*)
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
            """;

    private static final String SQL_INDEX_SIZE = """
            SELECT pg_catalog.pg_size_pretty(
                COALESCE(SUM(pg_catalog.pg_relation_size(i.indexrelid)), 0)
            )
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
            """;

    private static final String SQL_INDEX_DETAIL = """
            SELECT
                n.nspname AS schemaname,
                c.relname AS tablename,
                ic.relname AS indexname,
                pg_catalog.pg_get_indexdef(i.indexrelid) AS indexdef,
                i.indisunique AS is_unique,
                i.indisprimary AS is_primary
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
            JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND ic.relname = ?
            """;

    // ------------------------------------------------------------------
    // Sequences (cache value lives in pg_sequence, not information_schema)
    // ------------------------------------------------------------------

    private static final String SQL_SEQUENCES = """
            SELECT
                s.sequence_schema,
                s.sequence_name,
                s.data_type,
                s.minimum_value::text AS min_value,
                s.maximum_value::text AS max_value,
                s.increment::text AS increment,
                COALESCE(s.start_value::text, '') AS start_value,
                COALESCE(ps.seqcache::text, '1') AS cache_size,
                s.cycle_option
            FROM information_schema.sequences s
            LEFT JOIN pg_catalog.pg_class c ON c.relname = s.sequence_name AND c.relkind = 'S'
            LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.sequence_schema
            LEFT JOIN pg_catalog.pg_sequence ps ON ps.seqrelid = c.oid
            WHERE s.sequence_schema = ?
            ORDER BY s.sequence_name
            """;

    private static final String SQL_SEQUENCE_COUNT = """
            SELECT COUNT(*)
            FROM information_schema.sequences
            WHERE sequence_schema = ?
            """;

    // ------------------------------------------------------------------
    // Synonyms — PostgreSQL does not support synonyms
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Functions & Procedures (pg_proc: one row per overload, no cross join)
    // ------------------------------------------------------------------

    private static final String SQL_ROUTINES = """
            SELECT
                n.nspname AS routine_schema,
                p.proname AS routine_name,
                pg_catalog.pg_get_function_result(p.oid) AS return_type,
                pg_catalog.pg_get_function_identity_arguments(p.oid) AS arguments,
                pg_catalog.pg_get_userbyid(p.proowner) AS owner,
                l.lanname AS language
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            JOIN pg_catalog.pg_language l ON l.oid = p.prolang
            WHERE n.nspname = ?
              AND p.prokind = ?
            ORDER BY p.proname, p.oid
            """;

    private static final String SQL_ROUTINE_COUNT = """
            SELECT COUNT(*)
            FROM pg_catalog.pg_proc p
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ?
              AND p.prokind = ?
            """;

    // ------------------------------------------------------------------
    // Triggers (grouped: information_schema has one row per event)
    // ------------------------------------------------------------------

    private static final String SQL_TRIGGERS = """
            SELECT
                t.trigger_schema,
                t.trigger_name,
                MAX(t.event_object_schema) AS table_schema,
                MAX(t.event_object_table) AS table_name,
                MAX(t.action_timing) || ' ' || string_agg(DISTINCT t.event_manipulation, ' OR ') AS trigger_type,
                MAX(t.action_statement) AS action_statement
            FROM information_schema.triggers t
            WHERE t.trigger_schema = ?
            GROUP BY t.trigger_schema, t.trigger_name
            ORDER BY t.trigger_name
            """;

    private static final String SQL_TRIGGER_COUNT = """
            SELECT COUNT(DISTINCT trigger_name)
            FROM information_schema.triggers
            WHERE trigger_schema = ?
            """;

    private static final String SQL_TRIGGER_DETAIL = """
            SELECT
                t.trigger_schema,
                t.trigger_name,
                MAX(t.event_object_table) AS table_name,
                MAX(t.action_timing) || ' ' || string_agg(DISTINCT t.event_manipulation, ' OR ') AS trigger_type,
                MAX(t.action_statement) AS action_statement
            FROM information_schema.triggers t
            WHERE UPPER(t.trigger_schema) = UPPER(?)
              AND UPPER(t.trigger_name) = UPPER(?)
            GROUP BY t.trigger_schema, t.trigger_name
            """;

    // ------------------------------------------------------------------
    // Table Comment
    // ------------------------------------------------------------------

    private static final String SQL_TABLE_COMMENT = """
            SELECT COALESCE(pg_catalog.obj_description(c.oid), '')
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = ?
              AND c.relkind IN ('r', 'v', 'm', 'f', 'p')
              AND n.nspname = current_schema()
            """;

    // ------------------------------------------------------------------
    // Index columns for table
    // ------------------------------------------------------------------

    private static final String SQL_INDEX_COLUMNS = """
            SELECT array_to_string(
                ARRAY(
                    SELECT pg_catalog.pg_get_indexdef(i.indexrelid, k.i::int, true)
                    FROM generate_series(1, i.indnatts) k(i)
                ), ','
            )
            FROM pg_catalog.pg_index i
            JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = ?
              AND n.nspname = current_schema()
            LIMIT 1
            """;

    // ==================================================================
    // Implementations
    // ==================================================================

    @Override
    public List<User> getUsers(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_USERS, null, rs -> new User(rs.getString("username")));
    }

    @Override
    public boolean supportsUsers(Connect connect) {
        return true;
    }

    @Override
    public List<Catalog> getDatabases(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_DATABASES, null, rs -> {
            Catalog catalog = new Catalog(rs.getString("datname"));
            catalog.setDbOwner(blankToEmpty(rs.getString("owner")));
            catalog.setDbLog("");
            catalog.setDbUseGLU("");
            catalog.setDbLocale(blankToEmpty(rs.getString("comment")));
            catalog.setDbSpace("");
            catalog.setDbSize(blankToEmpty(rs.getString("total_size")));
            catalog.setDbCreated("");
            return catalog;
        });
    }

    @Override
    public List<Catalog> getSchemas(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_SCHEMAS, null, rs -> {
            Catalog catalog = new Catalog(rs.getString("schema_name"));
            catalog.setDbOwner(blankToEmpty(rs.getString("owner")));
            catalog.setDbLog("");
            catalog.setDbUseGLU("");
            catalog.setDbLocale(blankToEmpty(rs.getString("comment")));
            catalog.setDbSpace("");
            catalog.setDbSize(blankToEmpty(rs.getString("total_size")));
            catalog.setDbCreated("");
            return catalog;
        });
    }

    @Override
    public Catalog getDatabaseInfo(Connection conn, String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isBlank()) {
            return new Catalog("");
        }
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_SCHEMA_INFO, List.of(databaseName), rs -> {
            Catalog catalog = new Catalog(rs.getString("schema_name"));
            catalog.setDbOwner(blankToEmpty(rs.getString("owner")));
            catalog.setDbLog("");
            catalog.setDbUseGLU("");
            catalog.setDbLocale(blankToEmpty(rs.getString("comment")));
            catalog.setDbSpace("");
            catalog.setDbSize(blankToEmpty(rs.getString("total_size")));
            catalog.setDbCreated("");
            return catalog;
        });
    }

    @Override
    public int getUserTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String schema = currentSchema(conn);
        Integer count = runner.queryOne(SQL_USER_TABLES_COUNT, List.of(schema), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getUserTablesSize(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String schema = databaseName != null && !databaseName.isBlank() ? databaseName : currentSchema(conn);
        String size = runner.queryOne(SQL_USER_TABLES_SIZE, List.of(schema), rs -> rs.getString(1));
        return size == null ? "0B" : size;
    }

    @Override
    public int getSystemTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer count = runner.queryOne(SQL_SYSTEM_TABLES_COUNT, null, rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getSystemTablesSize(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String size = runner.queryOne(SQL_SYSTEM_TABLES_SIZE, null, rs -> rs.getString(1));
        return size == null ? "0B" : size;
    }

    @Override
    public List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_SYSTEM_TABLES, null, rs -> {
            SysTable table = new SysTable(rs.getString("table_name"));
            table.setTableCatalog(rs.getString("table_schema"));
            table.setTableOwner("");
            table.setTableComm(blankToEmpty(rs.getString("table_comment")));
            table.setNrows(rs.getInt("num_rows"));
            table.setTotalsize(rs.getString("total_size"));
            return table;
        });
    }

    @Override
    public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_USER_TABLES, List.of(databaseName), rs -> {
            Table table = new Table(rs.getString("table_name"));
            table.setTableCatalog(rs.getString("table_schema"));
            table.setTableOwner("");
            table.setTableComm(blankToEmpty(rs.getString("table_comment")));
            table.setNrows(rs.getInt("num_rows"));
            table.setTotalsize(rs.getString("total_size"));
            return table;
        });
    }

    @Override
    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_USER_TABLE_DETAIL, List.of(databaseName, tableName), rs -> {
            Table table = new Table(rs.getString("table_name"));
            table.setTableCatalog(rs.getString("table_schema"));
            table.setTableOwner("");
            table.setTableComm(blankToEmpty(rs.getString("table_comment")));
            table.setNrows(rs.getInt("num_rows"));
            table.setTotalsize(rs.getString("total_size"));
            return table;
        });
    }

    @Override
    public String getTableComment(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String comment = runner.queryOne(SQL_TABLE_COMMENT, List.of(tableName), rs -> rs.getString(1));
        return comment == null ? "" : comment;
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        String schema = currentSchema(conn);
        // Parse schema.table if qualified
        String lookupSchema = schema;
        String lookupTable = tableName;
        if (tableName != null && tableName.contains(".")) {
            int dot = tableName.lastIndexOf('.');
            lookupSchema = tableName.substring(0, dot);
            lookupTable = tableName.substring(dot + 1);
        }
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(SQL_COLUMNS, List.of(lookupSchema, lookupTable), rs -> {
            ColumnsInfo col = new ColumnsInfo();
            col.setColName(rs.getString("column_name"));
            col.setColType(rs.getString("data_type"));
            col.setColNo(rs.getInt("colno"));
            col.setIsNullable(!rs.getBoolean("not_null"));
            col.setColComm(blankToEmpty(rs.getString("col_comment")));
            col.setColDef(blankToEmpty(rs.getString("column_default")));
            return col;
        }));
    }

    @Override
    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_INDEXES, List.of(databaseName), rs -> {
            Index index = new Index(rs.getString("indexname"));
            index.setDatabase(rs.getString("schemaname"));
            index.setTabname(rs.getString("tablename"));
            index.setIdxtype(rs.getBoolean("is_primary") ? "PRIMARY"
                    : rs.getBoolean("is_unique") ? "UNIQUE" : "NONUNIQUE");
            index.setIsdisabled(false);
            return index;
        });
    }

    @Override
    public int getIndexCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String schema = currentSchema(conn);
        Integer count = runner.queryOne(SQL_INDEX_COUNT, List.of(schema), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getIndexSize(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String schema = currentSchema(conn);
        String size = runner.queryOne(SQL_INDEX_SIZE, List.of(schema), rs -> rs.getString(1));
        return size == null ? "0B" : size;
    }

    @Override
    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_INDEX_DETAIL, List.of(databaseName, indexName), rs -> {
            Index index = new Index(rs.getString("indexname"));
            index.setDatabase(rs.getString("schemaname"));
            index.setTabname(rs.getString("tablename"));
            index.setIdxtype(rs.getBoolean("is_primary") ? "PRIMARY"
                    : rs.getBoolean("is_unique") ? "UNIQUE" : "NONUNIQUE");
            return index;
        });
    }

    @Override
    public int getSequenceCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String schema = currentSchema(conn);
        Integer count = runner.queryOne(SQL_SEQUENCE_COUNT, List.of(schema), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_SEQUENCES, List.of(databaseName), rs -> {
            Sequence seq = new Sequence(rs.getString("sequence_name"));
            seq.setDatabase(rs.getString("sequence_schema"));
            seq.setMinValue(new BigInteger(rs.getString("min_value") != null
                    ? rs.getString("min_value") : "1"));
            seq.setMaxValue(new BigInteger(rs.getString("max_value") != null
                    ? rs.getString("max_value") : "9223372036854775807"));
            seq.setIncValue(new BigInteger(rs.getString("increment") != null
                    ? rs.getString("increment") : "1"));
            seq.setCache(rs.getString("cache_size") != null && !rs.getString("cache_size").isBlank()
                    ? Long.parseLong(rs.getString("cache_size")) : 1);
            return seq;
        });
    }

    @Override
    public int getSynonymCount(Connection conn) throws SQLException {
        return 0;
    }

    @Override
    public List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException {
        return List.of();
    }

    @Override
    public int getTriggerCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer count = runner.queryOne(SQL_TRIGGER_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_TRIGGERS, List.of(databaseName), rs -> {
            Trigger trigger = new Trigger(rs.getString("trigger_name"));
            trigger.setDatabase(rs.getString("trigger_schema"));
            trigger.setTableName(rs.getString("table_name"));
            trigger.setTriggerType(rs.getString("trigger_type"));
            return trigger;
        });
    }

    @Override
    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_TRIGGER_DETAIL, List.of(databaseName, triggerName), rs -> {
            Trigger trigger = new Trigger(rs.getString("trigger_name"));
            trigger.setDatabase(rs.getString("trigger_schema"));
            trigger.setTableName(rs.getString("table_name"));
            trigger.setTriggerType(rs.getString("trigger_type"));
            return trigger;
        });
    }

    @Override
    public int getViewCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer count = runner.queryOne(SQL_VIEW_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_VIEWS, List.of(databaseName), rs -> {
            View view = new View(rs.getString("table_name"));
            view.setDbname(rs.getString("table_schema"));
            view.setOwner("");
            return view;
        });
    }

    @Override
    public int getSystemDualTabId(Connection conn) throws SQLException {
        return 0;
    }

    @Override
    public boolean hasSysProcTypeColumn(Connection conn) throws SQLException {
        return false;
    }

    @Override
    public int getFunctionCount(Connection conn, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer count = runner.queryOne(SQL_ROUTINE_COUNT,
                List.of(currentSchema(conn), "f"), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_ROUTINES, List.of(databaseName, "f"), rs -> {
            Function func = new Function(rs.getString("routine_name"));
            func.setDatabase(rs.getString("routine_schema"));
            func.setOwner(blankToEmpty(rs.getString("owner")));
            return func;
        });
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer count = runner.queryOne(SQL_ROUTINE_COUNT,
                List.of(currentSchema(conn), "p"), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_ROUTINES, List.of(databaseName, "p"), rs -> {
            Procedure proc = new Procedure(rs.getString("routine_name"));
            proc.setDatabase(rs.getString("routine_schema"));
            proc.setOwner(blankToEmpty(rs.getString("owner")));
            return proc;
        });
    }

    @Override
    public int getPackageCount(Connection conn) throws SQLException {
        return 0;
    }

    @Override
    public List<DBPackage> getPackages(Connection conn, String databaseName) throws SQLException {
        return List.of();
    }

    @Override
    public List<String> getStorageSpacesForCreateDatabase(Connection conn) throws SQLException {
        return List.of();
    }

    @Override
    public void changeDatabase(Connection conn, String databaseName) throws SQLException {
        setDatabase(conn, databaseName);
    }

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        // 库-模式-表模型：名称若是真实数据库，只能靠重连切换——
        // 抛出 25P01（方言归类为 RETRY_WITH_NEW_CONNECTION），
        // 由 ConnectionServiceImpl 的租约逻辑重连到目标库。
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Boolean isDatabase = runner.queryOne(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = ?)",
                List.of(databaseName), rs -> rs.getBoolean(1));
        if (Boolean.TRUE.equals(isDatabase)) {
            String current = runner.queryOne("SELECT current_database()", null, rs -> rs.getString(1));
            if (databaseName.equals(current)) {
                return; // 已在目标库
            }
            throw new SQLException("cross-database switch requires reconnect: " + databaseName, "25P01");
        }
        // 否则按模式处理：search_path 切换
        String schema = databaseName.replace("\"", "\"\"");
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"" + schema + "\"");
        }
    }

    @Override
    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String result = runner.queryOne(SQL_INDEX_COLUMNS, List.of(tableName), rs -> rs.getString(1));
        if (result == null || result.isBlank()) {
            return List.of();
        }
        return List.of(result.split(","));
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    protected String currentSchema(Connection conn) throws SQLException {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema;
            }
        } catch (Exception ignored) {
        }
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String schema = runner.queryOne("SELECT current_schema()", null, rs -> rs.getString(1));
        return schema != null && !schema.isBlank() ? schema : "public";
    }

    protected static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
