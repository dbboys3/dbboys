package com.dbboys.dialect.sqlserver;

import com.dbboys.core.MetadataRepository;
import com.dbboys.infra.db.SqlRunner;
import com.dbboys.model.*;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server metadata repository backed by sys catalog views.
 * Tree: databases (level 1) → schemas (level 2) → objects (DATABASE_SCHEMA model).
 */
public final class SqlserverMetadataRepository implements MetadataRepository {

    /** SQLState thrown to request a reconnect-based cross-database switch. */
    public static final String RECONNECT_REQUIRED_STATE = "25P01";

    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private static final String SQL_DATABASES = """
            SELECT d.name,
                   SUSER_SNAME(d.owner_sid) AS owner,
                   CONVERT(varchar(19), d.create_date, 120) AS created_time,
                   d.collation_name,
                   COALESCE((SELECT SUM(mf.size) * 8 / 1024
                             FROM sys.master_files mf
                             WHERE mf.database_id = d.database_id), 0) AS size_mb
            FROM sys.databases d
            WHERE d.state_desc = 'ONLINE'
              AND d.source_database_id IS NULL
            ORDER BY d.name
            """;

    private static final String SQL_SCHEMAS = """
            SELECT s.name,
                   dp.name AS owner,
                   COALESCE((SELECT SUM(a.total_pages) * 8
                             FROM sys.tables t
                             JOIN sys.partitions p ON p.object_id = t.object_id
                             JOIN sys.allocation_units a ON a.container_id = p.partition_id
                             WHERE t.schema_id = s.schema_id), 0) AS size_kb
            FROM sys.schemas s
            JOIN sys.database_principals dp ON dp.principal_id = s.principal_id
            ORDER BY s.name
            """;

    private static final String SQL_SCHEMA_INFO = """
            SELECT s.name,
                   dp.name AS owner,
                   COALESCE((SELECT SUM(a.total_pages) * 8
                             FROM sys.tables t
                             JOIN sys.partitions p ON p.object_id = t.object_id
                             JOIN sys.allocation_units a ON a.container_id = p.partition_id
                             WHERE t.schema_id = s.schema_id), 0) AS size_kb
            FROM sys.schemas s
            JOIN sys.database_principals dp ON dp.principal_id = s.principal_id
            WHERE s.name = ?
            """;

    private static final String SQL_USERS = """
            SELECT name
            FROM sys.database_principals
            WHERE type IN ('S', 'U', 'G')
              AND name NOT IN ('dbo', 'guest', 'INFORMATION_SCHEMA', 'sys', 'public')
            ORDER BY name
            """;

    private static final String SQL_TABLES = """
            SELECT t.name,
                   s.name AS schema_name,
                   CONVERT(varchar(19), t.create_date, 120) AS created_time,
                   COALESCE((SELECT SUM(p.rows)
                             FROM sys.partitions p
                             WHERE p.object_id = t.object_id AND p.index_id IN (0, 1)), 0) AS num_rows,
                   COALESCE((SELECT SUM(a.total_pages) * 8
                             FROM sys.partitions p
                             JOIN sys.allocation_units a ON a.container_id = p.partition_id
                             WHERE p.object_id = t.object_id), 0) AS size_kb,
                   COALESCE(ep.value, '') AS table_comment
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.extended_properties ep
              ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
            WHERE s.name = ?
              AND t.is_ms_shipped = 0
            ORDER BY t.name
            """;

    private static final String SQL_TABLE_DETAIL = """
            SELECT t.name,
                   s.name AS schema_name,
                   CONVERT(varchar(19), t.create_date, 120) AS created_time,
                   COALESCE((SELECT SUM(p.rows)
                             FROM sys.partitions p
                             WHERE p.object_id = t.object_id AND p.index_id IN (0, 1)), 0) AS num_rows,
                   COALESCE((SELECT SUM(a.total_pages) * 8
                             FROM sys.partitions p
                             JOIN sys.allocation_units a ON a.container_id = p.partition_id
                             WHERE p.object_id = t.object_id), 0) AS size_kb,
                   COALESCE(ep.value, '') AS table_comment
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.extended_properties ep
              ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
            WHERE s.name = ?
              AND t.name = ?
            """;

    private static final String SQL_TABLES_COUNT = """
            SELECT COUNT(*)
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND t.is_ms_shipped = 0
            """;

    private static final String SQL_TABLE_COMMENT = """
            SELECT COALESCE(CAST(ep.value AS nvarchar(max)), '')
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.extended_properties ep
              ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
            WHERE s.name = ?
              AND t.name = ?
            """;

    private static final String SQL_COLUMNS = """
            SELECT c.name,
                   ty.name AS type_name,
                   c.max_length, c.precision, c.scale,
                   c.is_nullable, c.is_identity, c.column_id,
                   dc.definition AS column_default,
                   COALESCE(CAST(ep.value AS nvarchar(max)), '') AS col_comment
            FROM sys.columns c
            JOIN sys.types ty ON ty.user_type_id = c.user_type_id
            JOIN sys.tables t ON t.object_id = c.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
            LEFT JOIN sys.extended_properties ep
              ON ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
            WHERE s.name = ?
              AND t.name = ?
            ORDER BY c.column_id
            """;

    private static final String SQL_PK_COLUMNS = """
            SELECT c.name
            FROM sys.tables t
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.indexes i ON i.object_id = t.object_id AND i.is_primary_key = 1
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE s.name = ?
              AND t.name = ?
            ORDER BY ic.key_ordinal
            """;

    private static final String SQL_INDEXES = """
            SELECT i.name,
                   t.name AS table_name,
                   i.type_desc, i.is_unique, i.is_primary_key, i.is_disabled
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND i.name IS NOT NULL
            ORDER BY i.name
            """;

    private static final String SQL_INDEX_DETAIL = """
            SELECT i.name,
                   t.name AS table_name,
                   i.type_desc, i.is_unique, i.is_primary_key, i.is_disabled
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND i.name = ?
            """;

    private static final String SQL_INDEX_COUNT = """
            SELECT COUNT(*)
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND i.name IS NOT NULL
            """;

    private static final String SQL_INDEX_COLUMNS = """
            SELECT c.name, ic.is_descending_key
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE s.name = ?
              AND i.name = ?
            ORDER BY ic.key_ordinal, ic.index_column_id
            """;

    private static final String SQL_INDEX_COLUMNS_FOR_TABLE = """
            SELECT c.name
            FROM sys.indexes i
            JOIN sys.tables t ON t.object_id = i.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE s.name = ?
              AND t.name = ?
            ORDER BY i.name, ic.key_ordinal
            """;

    private static final String SQL_VIEWS = """
            SELECT v.name,
                   s.name AS schema_name,
                   CONVERT(varchar(19), v.create_date, 120) AS created_time
            FROM sys.views v
            JOIN sys.schemas s ON s.schema_id = v.schema_id
            WHERE s.name = ?
              AND v.is_ms_shipped = 0
            ORDER BY v.name
            """;

    private static final String SQL_VIEW_COUNT = """
            SELECT COUNT(*)
            FROM sys.views v
            JOIN sys.schemas s ON s.schema_id = v.schema_id
            WHERE s.name = ?
              AND v.is_ms_shipped = 0
            """;

    private static final String SQL_SEQUENCES = """
            SELECT sq.name,
                   s.name AS schema_name,
                   sq.start_value, sq.increment, sq.minimum_value, sq.maximum_value,
                   sq.is_cycling, sq.cache_size, sq.current_value
            FROM sys.sequences sq
            JOIN sys.schemas s ON s.schema_id = sq.schema_id
            WHERE s.name = ?
            ORDER BY sq.name
            """;

    private static final String SQL_SEQUENCE_COUNT = """
            SELECT COUNT(*)
            FROM sys.sequences sq
            JOIN sys.schemas s ON s.schema_id = sq.schema_id
            WHERE s.name = ?
            """;

    private static final String SQL_SYNONYMS = """
            SELECT sn.name,
                   s.name AS schema_name,
                   sn.base_object_name,
                   CONVERT(varchar(19), sn.create_date, 120) AS created_time
            FROM sys.synonyms sn
            JOIN sys.schemas s ON s.schema_id = sn.schema_id
            WHERE s.name = ?
            ORDER BY sn.name
            """;

    private static final String SQL_SYNONYM_COUNT = """
            SELECT COUNT(*)
            FROM sys.synonyms sn
            JOIN sys.schemas s ON s.schema_id = sn.schema_id
            WHERE s.name = ?
            """;

    private static final String SQL_TRIGGERS = """
            SELECT tr.name,
                   t.name AS table_name,
                   CASE WHEN tr.is_instead_of_trigger = 1 THEN 'INSTEAD OF' ELSE 'AFTER' END AS timing,
                   COALESCE(STUFF((SELECT ', ' + te.type_desc
                                   FROM sys.trigger_events te
                                   WHERE te.object_id = tr.object_id
                                   ORDER BY te.type_desc
                                   FOR XML PATH('')), 1, 2, ''), '') AS events,
                   tr.is_disabled,
                   CONVERT(varchar(19), tr.create_date, 120) AS created_time
            FROM sys.triggers tr
            JOIN sys.tables t ON t.object_id = tr.parent_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND tr.parent_class = 1
            ORDER BY tr.name
            """;

    private static final String SQL_TRIGGER_DETAIL = """
            SELECT tr.name,
                   t.name AS table_name,
                   CASE WHEN tr.is_instead_of_trigger = 1 THEN 'INSTEAD OF' ELSE 'AFTER' END AS timing,
                   COALESCE(STUFF((SELECT ', ' + te.type_desc
                                   FROM sys.trigger_events te
                                   WHERE te.object_id = tr.object_id
                                   ORDER BY te.type_desc
                                   FOR XML PATH('')), 1, 2, ''), '') AS events,
                   tr.is_disabled
            FROM sys.triggers tr
            JOIN sys.tables t ON t.object_id = tr.parent_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND tr.name = ?
            """;

    private static final String SQL_TRIGGER_COUNT = """
            SELECT COUNT(*)
            FROM sys.triggers tr
            JOIN sys.tables t ON t.object_id = tr.parent_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = ?
              AND tr.parent_class = 1
            """;

    private static final String SQL_ROUTINES = """
            SELECT o.name,
                   s.name AS schema_name,
                   CONVERT(varchar(19), o.create_date, 120) AS created_time
            FROM sys.objects o
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ?
              AND o.type IN ({TYPES})
              AND o.is_ms_shipped = 0
            ORDER BY o.name
            """;

    private static final String SQL_ROUTINE_COUNT = """
            SELECT COUNT(*)
            FROM sys.objects o
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE s.name = ?
              AND o.type IN ({TYPES})
              AND o.is_ms_shipped = 0
            """;

    private static final String FUNCTION_TYPES = "'FN', 'IF', 'TF', 'FS', 'FT', 'AF'";
    private static final String PROCEDURE_TYPES = "'P', 'PC', 'RF', 'X'";

    // 系统对象 = 微软随库发布（is_ms_shipped=1）且可直接查询的目录视图/表
    // （sys.tables、INFORMATION_SCHEMA.*、master 库的 spt_* 等）。
    // sys.system_objects 只含资源库对象，master.dbo 的 spt_* 不在其中，必须用
    // sys.all_objects（本库对象 + 资源库对象）按 is_ms_shipped 过滤。
    // type 'S' 的内部基表不可直接查询（需要 DAC），不列入。
    private static final String SQL_SYSTEM_TABLES = """
            SELECT o.name,
                   s.name AS schema_name,
                   RTRIM(o.type) AS object_type
            FROM sys.all_objects o
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE o.is_ms_shipped = 1
              AND s.name = ?
              AND o.type IN ('U', 'V')
            ORDER BY o.name
            """;

    private static final String SQL_SYSTEM_TABLES_COUNT = """
            SELECT COUNT(*)
            FROM sys.all_objects o
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            WHERE o.is_ms_shipped = 1
              AND s.name = ?
              AND o.type IN ('U', 'V')
            """;

    // ==================================================================

    @Override
    public List<User> getUsers(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_USERS, null, rs -> new User(rs.getString("name")));
    }

    @Override
    public boolean supportsUsers(Connect connect) {
        return true;
    }

    @Override
    public List<Database> getDatabases(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_DATABASES, null, rs -> {
            Database database = new Database(rs.getString("name"));
            database.setDbOwner(blank(rs.getString("owner")));
            database.setDbCreated(blank(rs.getString("created_time")));
            database.setDbLocale(blank(rs.getString("collation_name")));
            long sizeMb = rs.getLong("size_mb");
            database.setDbSize(sizeMb > 0 ? sizeMb + " MB" : "");
            database.setDbLog("");
            database.setDbSpace("");
            database.setDbUseGLU("");
            return database;
        });
    }

    @Override
    public List<Schema> getSchemas(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        String parentDb = currentDatabase(conn);
        return runner.query(SQL_SCHEMAS, null, rs -> {
            Schema schema = new Schema(rs.getString("name"), parentDb);
            schema.setDbOwner(blank(rs.getString("owner")));
            schema.setDbSize(formatSize(rs.getLong("size_kb")));
            schema.setDbLog("");
            schema.setDbSpace("");
            schema.setDbLocale("");
            schema.setDbUseGLU("");
            schema.setDbCreated("");
            return schema;
        });
    }

    @Override
    public CatalogNode getDatabaseInfo(Connection conn, String databaseName) throws SQLException {
        // 三层模型下本方法查询的是模式信息，必须返回 Schema（保持树节点身份）。
        SqlRunner runner = runner(conn);
        Schema info = runner.queryOne(SQL_SCHEMA_INFO, List.of(schemaName(databaseName)), rs -> {
            Schema schema = new Schema(rs.getString("name"), currentDatabaseQuiet(conn));
            schema.setDbOwner(blank(rs.getString("owner")));
            schema.setDbSize(formatSize(rs.getLong("size_kb")));
            schema.setDbLog("");
            schema.setDbSpace("");
            schema.setDbLocale("");
            schema.setDbUseGLU("");
            schema.setDbCreated("");
            return schema;
        });
        if (info != null) {
            return info;
        }
        return new Schema(databaseName == null ? "" : databaseName.trim(), currentDatabaseQuiet(conn));
    }

    @Override
    public int getUserTablesCount(Connection conn) throws SQLException {
        return getUserTablesCount(conn, null);
    }

    @Override
    public int getUserTablesCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_TABLES_COUNT, List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getUserTablesSize(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Long sizeKb = runner.queryOne("""
                SELECT COALESCE(SUM(a.total_pages) * 8, 0)
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                JOIN sys.partitions p ON p.object_id = t.object_id
                JOIN sys.allocation_units a ON a.container_id = p.partition_id
                WHERE s.name = ?
                  AND t.is_ms_shipped = 0
                """, List.of(schemaName(databaseName)), rs -> rs.getLong(1));
        return sizeKb == null || sizeKb <= 0 ? null : formatSize(sizeKb);
    }

    @Override
    public int getSystemTablesCount(Connection conn) throws SQLException {
        return getSystemTablesCount(conn, null);
    }

    @Override
    public int getSystemTablesCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_SYSTEM_TABLES_COUNT, List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getSystemTablesSize(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Long sizeKb = runner.queryOne("""
                SELECT COALESCE(SUM(a.total_pages) * 8, 0)
                FROM sys.all_objects o
                JOIN sys.schemas s ON s.schema_id = o.schema_id
                JOIN sys.partitions p ON p.object_id = o.object_id
                JOIN sys.allocation_units a ON a.container_id = p.partition_id
                WHERE o.is_ms_shipped = 1
                  AND s.name = ?
                  AND o.type IN ('U', 'V')
                """, List.of(schemaName(databaseName)), rs -> rs.getLong(1));
        return sizeKb == null || sizeKb <= 0 ? null : formatSize(sizeKb);
    }

    @Override
    public List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_SYSTEM_TABLES, List.of(schemaName(databaseName)), rs -> {
            SysTable table = new SysTable(rs.getString("name"));
            String schema = blank(rs.getString("schema_name"));
            table.setTableCatalog(schema);
            // 拖动生成查询 SQL 时以 owner 做模式限定（sys.tables 等）
            table.setTableOwner(schema);
            table.setTableTypeCode("V".equals(rs.getString("object_type")) ? "view" : "table");
            return table;
        });
    }

    @Override
    public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_TABLES, List.of(schemaName(databaseName)), SqlserverMetadataRepository::mapTable);
    }

    @Override
    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        Table table = runner.queryOne(SQL_TABLE_DETAIL,
                List.of(schemaName(databaseName), objectName(tableName)), SqlserverMetadataRepository::mapTable);
        return table != null ? table : new Table(objectName(tableName));
    }

    private static Table mapTable(java.sql.ResultSet rs) throws SQLException {
        Table table = new Table(rs.getString("name"));
        table.setTableCatalog(rs.getString("schema_name"));
        table.setTableOwner(rs.getString("schema_name"));
        table.setTableComm(blank(rs.getString("table_comment")));
        table.setNrows(rs.getInt("num_rows"));
        long sizeKb = rs.getLong("size_kb");
        table.setTotalsize(sizeKb > 0 ? sizeKb + " KB" : "");
        table.setCreateTime(blank(rs.getString("created_time")));
        return table;
    }

    @Override
    public String getTableComment(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        String comment = runner.queryOne(SQL_TABLE_COMMENT, List.of(parts[0], parts[1]), rs -> rs.getString(1));
        return comment == null ? "" : comment;
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        List<String> pkColumns = runner.query(SQL_PK_COLUMNS, List.of(parts[0], parts[1]), rs -> rs.getString("name"));
        List<ColumnsInfo> columns = runner.query(SQL_COLUMNS, List.of(parts[0], parts[1]), rs -> {
            ColumnsInfo column = new ColumnsInfo();
            String name = rs.getString("name");
            column.setColName(name);
            column.setColNo(rs.getInt("column_id"));
            String typeName = rs.getString("type_name");
            column.setColType(typeName);
            int[] typeArgs = typeArgs(typeName, rs.getInt("max_length"), rs.getInt("precision"), rs.getInt("scale"));
            column.setTypeP(typeArgs[0]);
            column.setTypeS(typeArgs[1]);
            column.setColLength(typeArgs[0]);
            column.setIsNullable(rs.getBoolean("is_nullable"));
            column.setIsAutoincrement(rs.getBoolean("is_identity"));
            column.setColDef(rs.getString("column_default"));
            column.setColComm(blank(rs.getString("col_comment")));
            column.setIsPK(pkColumns.contains(name));
            return column;
        });
        return new ArrayList<>(columns);
    }

    @Override
    public List<String> getPrimaryKeyColumns(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        return runner.query(SQL_PK_COLUMNS, List.of(parts[0], parts[1]), rs -> rs.getString("name"));
    }

    @Override
    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_INDEXES, List.of(schemaName(databaseName)), rs -> {
            Index index = new Index(rs.getString("name"));
            index.setDatabase(schemaName(databaseName));
            index.setTabname(rs.getString("table_name"));
            index.setIdxtype(rs.getBoolean("is_primary_key") ? "PRIMARY"
                    : rs.getBoolean("is_unique") ? "UNIQUE" : "NONUNIQUE");
            index.setIsdisabled(rs.getBoolean("is_disabled"));
            index.setCols(loadIndexColumns(conn, schemaName(databaseName), rs.getString("name")));
            return index;
        });
    }

    @Override
    public int getIndexCount(Connection conn) throws SQLException {
        return getIndexCount(conn, null);
    }

    @Override
    public int getIndexCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_INDEX_COUNT, List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public String getIndexSize(Connection conn) throws SQLException {
        return getIndexSize(conn, null);
    }

    @Override
    public String getIndexSize(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        // index_id > 1：排除堆(0)与聚集索引(1)（它们就是表数据本身）
        Long sizeKb = runner.queryOne("""
                SELECT COALESCE(SUM(a.total_pages) * 8, 0)
                FROM sys.indexes i
                JOIN sys.tables t ON t.object_id = i.object_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                JOIN sys.partitions p ON p.object_id = i.object_id AND p.index_id = i.index_id
                JOIN sys.allocation_units a ON a.container_id = p.partition_id
                WHERE s.name = ?
                  AND i.name IS NOT NULL
                  AND i.index_id > 1
                """, List.of(contextSchema(conn, databaseName)), rs -> rs.getLong(1));
        return sizeKb == null || sizeKb <= 0 ? null : formatSize(sizeKb);
    }

    @Override
    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        SqlRunner runner = runner(conn);
        Index index = runner.queryOne(SQL_INDEX_DETAIL,
                List.of(schemaName(databaseName), objectName(indexName)), rs -> {
                    Index idx = new Index(rs.getString("name"));
                    idx.setDatabase(schemaName(databaseName));
                    idx.setTabname(rs.getString("table_name"));
                    idx.setIdxtype(rs.getBoolean("is_primary_key") ? "PRIMARY"
                            : rs.getBoolean("is_unique") ? "UNIQUE" : "NONUNIQUE");
                    idx.setIsdisabled(rs.getBoolean("is_disabled"));
                    return idx;
                });
        return index != null ? index : new Index(objectName(indexName));
    }

    private String loadIndexColumns(Connection conn, String schemaName, String indexName) {
        try {
            SqlRunner runner = runner(conn);
            List<String> cols = runner.query(SQL_INDEX_COLUMNS, List.of(schemaName, indexName), rs -> {
                String name = rs.getString("name");
                return rs.getBoolean("is_descending_key") ? name + " DESC" : name;
            });
            return String.join(", ", cols);
        } catch (SQLException e) {
            return "";
        }
    }

    @Override
    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = runner(conn);
        String[] parts = splitQualified(tableName, currentSchema(conn));
        return runner.query(SQL_INDEX_COLUMNS_FOR_TABLE, List.of(parts[0], parts[1]), rs -> rs.getString("name"));
    }

    @Override
    public int getSequenceCount(Connection conn) throws SQLException {
        return getSequenceCount(conn, null);
    }

    @Override
    public int getSequenceCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_SEQUENCE_COUNT, List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_SEQUENCES, List.of(schemaName(databaseName)), rs -> {
            Sequence sequence = new Sequence(rs.getString("name"));
            sequence.setDatabase(rs.getString("schema_name"));
            sequence.setMinValue(toBigInteger(rs.getString("minimum_value"), BigInteger.ONE));
            sequence.setMaxValue(toBigInteger(rs.getString("maximum_value"), new BigInteger("9223372036854775807")));
            sequence.setIncValue(toBigInteger(rs.getString("increment"), BigInteger.ONE));
            sequence.setNextVal(toBigInteger(rs.getString("current_value"), BigInteger.ONE));
            long cacheSize = rs.getLong("cache_size");
            sequence.setCache(rs.wasNull() ? 0 : cacheSize);
            return sequence;
        });
    }

    @Override
    public int getSynonymCount(Connection conn) throws SQLException {
        return getSynonymCount(conn, null);
    }

    @Override
    public int getSynonymCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_SYNONYM_COUNT, List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_SYNONYMS, List.of(schemaName(databaseName)), rs -> {
            Synonym synonym = new Synonym(rs.getString("name"));
            synonym.setDatabase(rs.getString("schema_name"));
            synonym.setSynonymType(blank(rs.getString("base_object_name")));
            synonym.setCreated(blank(rs.getString("created_time")));
            return synonym;
        });
    }

    @Override
    public int getTriggerCount(Connection conn) throws SQLException {
        return getTriggerCount(conn, null);
    }

    @Override
    public int getTriggerCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_TRIGGER_COUNT, List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_TRIGGERS, List.of(schemaName(databaseName)), rs -> {
            Trigger trigger = new Trigger(rs.getString("name"));
            trigger.setDatabase(schemaName(databaseName));
            trigger.setTableName(rs.getString("table_name"));
            trigger.setTriggerType(triggerTypeText(rs.getString("timing"), rs.getString("events")));
            trigger.setIsdisabled(rs.getBoolean("is_disabled"));
            return trigger;
        });
    }

    @Override
    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException {
        SqlRunner runner = runner(conn);
        Trigger trigger = runner.queryOne(SQL_TRIGGER_DETAIL,
                List.of(schemaName(databaseName), objectName(triggerName)), rs -> {
                    Trigger t = new Trigger(rs.getString("name"));
                    t.setDatabase(schemaName(databaseName));
                    t.setTableName(rs.getString("table_name"));
                    t.setTriggerType(triggerTypeText(rs.getString("timing"), rs.getString("events")));
                    t.setIsdisabled(rs.getBoolean("is_disabled"));
                    return t;
                });
        return trigger != null ? trigger : new Trigger(objectName(triggerName));
    }

    private static String triggerTypeText(String timing, String events) {
        String combined = ((timing == null ? "" : timing.trim()) + " "
                + (events == null ? "" : events.trim())).trim();
        return combined;
    }

    @Override
    public int getViewCount(Connection conn) throws SQLException {
        return getViewCount(conn, null);
    }

    @Override
    public int getViewCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(SQL_VIEW_COUNT, List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_VIEWS, List.of(schemaName(databaseName)), rs -> {
            View view = new View(rs.getString("name"));
            view.setDbname(rs.getString("schema_name"));
            view.setOwner(rs.getString("schema_name"));
            view.setCreateTime(blank(rs.getString("created_time")));
            return view;
        });
    }

    @Override
    public int getSystemDualTabId(Connection conn) {
        return 0;
    }

    @Override
    public boolean hasSysProcTypeColumn(Connection conn) {
        return false;
    }

    @Override
    public int getFunctionCount(Connection conn, boolean filterType) throws SQLException {
        return getFunctionCount(conn, filterType, null);
    }

    @Override
    public int getFunctionCount(Connection conn, boolean filterType, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(routineSql(SQL_ROUTINE_COUNT, FUNCTION_TYPES),
                List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(routineSql(SQL_ROUTINES, FUNCTION_TYPES), List.of(schemaName(databaseName)), rs -> {
            Function function = new Function(rs.getString("name"));
            function.setDatabase(rs.getString("schema_name"));
            function.setOwner(rs.getString("schema_name"));
            return function;
        });
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        return getProcedureCount(conn, filterType, null);
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer count = runner.queryOne(routineSql(SQL_ROUTINE_COUNT, PROCEDURE_TYPES),
                List.of(contextSchema(conn, databaseName)), rs -> rs.getInt(1));
        return count == null ? 0 : count;
    }

    @Override
    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(routineSql(SQL_ROUTINES, PROCEDURE_TYPES), List.of(schemaName(databaseName)), rs -> {
            Procedure procedure = new Procedure(rs.getString("name"));
            procedure.setDatabase(rs.getString("schema_name"));
            procedure.setOwner(rs.getString("schema_name"));
            return procedure;
        });
    }

    private static String routineSql(String template, String types) {
        return template.replace("{TYPES}", types);
    }

    @Override
    public int getPackageCount(Connection conn) {
        return 0;
    }

    @Override
    public List<DBPackage> getPackages(Connection conn, String databaseName) {
        return List.of();
    }

    @Override
    public List<String> getStorageSpacesForCreateDatabase(Connection conn) {
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
        String name = databaseName.trim();
        SqlRunner runner = runner(conn);
        Boolean isDatabase = runner.queryOne(
                "SELECT CASE WHEN EXISTS (SELECT 1 FROM sys.databases WHERE name = ?) THEN 1 ELSE 0 END",
                List.of(name), rs -> rs.getBoolean(1));
        if (Boolean.TRUE.equals(isDatabase)) {
            String current = currentDatabase(conn);
            if (name.equalsIgnoreCase(current)) {
                return; // 已在目标库
            }
            // 跨库切换只能靠重连——抛出 RECONNECT_REQUIRED_STATE，由连接租约逻辑重连到目标库。
            throw new SQLException("cross-database switch requires reconnect: " + name, RECONNECT_REQUIRED_STATE);
        }
        // 模式级：T-SQL 无会话级 SET SCHEMA；对象访问均按模式限定名处理，无需动作。
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    String currentSchema(Connection conn) throws SQLException {
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema.trim();
            }
        } catch (Exception ignored) {
        }
        SqlRunner runner = runner(conn);
        String schema = runner.queryOne("SELECT SCHEMA_NAME()", null, rs -> rs.getString(1));
        return schema != null && !schema.isBlank() ? schema.trim() : "dbo";
    }

    /**
     * 计数/大小查询的目标模式。T-SQL 无会话级 SET SCHEMA，连接上的"当前模式"
     * 始终是登录默认模式，因此必须优先使用树节点（databaseName）携带的模式名。
     */
    private String contextSchema(Connection conn, String databaseName) throws SQLException {
        return databaseName == null || databaseName.isBlank() ? currentSchema(conn) : schemaName(databaseName);
    }

    String currentDatabase(Connection conn) throws SQLException {
        try {
            String catalog = conn.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                return catalog.trim();
            }
        } catch (Exception ignored) {
        }
        String database = runner(conn).queryOne("SELECT DB_NAME()", null, rs -> rs.getString(1));
        return database != null && !database.isBlank() ? database.trim() : "";
    }

    private String currentDatabaseQuiet(Connection conn) {
        try {
            return currentDatabase(conn);
        } catch (SQLException e) {
            return "";
        }
    }

    private static String schemaName(String databaseName) {
        return databaseName == null ? "" : databaseName.trim();
    }

    private static String objectName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        int dot = trimmed.lastIndexOf('.');
        String bare = dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
        return bare.replace("[", "").replace("]", "").replace("\"", "");
    }

    /** Splits "schema.table" into [schema, table]; unqualified names use the given default schema. */
    static String[] splitQualified(String name, String defaultSchema) {
        String trimmed = name == null ? "" : name.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot > 0) {
            return new String[]{
                    trimmed.substring(0, dot).replace("[", "").replace("]", "").replace("\"", ""),
                    trimmed.substring(dot + 1).replace("[", "").replace("]", "").replace("\"", "")};
        }
        return new String[]{
                defaultSchema == null ? "" : defaultSchema.trim(),
                trimmed.replace("[", "").replace("]", "").replace("\"", "")};
    }

    /** T-SQL type modifiers: max_length is byte count (nvarchar halves; -1 means MAX). */
    static int[] typeArgs(String typeName, int maxLength, int precision, int scale) {
        String type = typeName == null ? "" : typeName.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "varchar", "char", "varbinary", "binary" -> new int[]{maxLength < 0 ? -1 : maxLength, 0};
            case "nvarchar", "nchar" -> new int[]{maxLength < 0 ? -1 : maxLength / 2, 0};
            case "decimal", "numeric" -> new int[]{precision, scale};
            case "float", "real" -> new int[]{precision, 0};
            case "datetime2", "datetimeoffset", "time" -> new int[]{0, scale};
            default -> new int[]{0, 0};
        };
    }

    static String formatType(String typeName, int maxLength, int precision, int scale) {
        String type = typeName == null ? "" : typeName.trim();
        int[] args = typeArgs(type, maxLength, precision, scale);
        String lower = type.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "varchar", "char", "varbinary", "binary", "nvarchar", "nchar" ->
                    args[0] < 0 ? type + "(max)" : type + "(" + args[0] + ")";
            case "decimal", "numeric" -> type + "(" + args[0] + ", " + args[1] + ")";
            case "datetime2", "datetimeoffset", "time" -> args[1] > 0 ? type + "(" + args[1] + ")" : type;
            default -> type;
        };
    }

    private static BigInteger toBigInteger(String value, BigInteger fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigInteger(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    /** 数据大小显示：>=1MB 显示 MB（与库列表一致），小表显示 KB，空模式显示 0 KB（对齐 PG 的 0 bytes）。 */
    private static String formatSize(long sizeKb) {
        if (sizeKb <= 0) {
            return "0 KB";
        }
        return sizeKb >= 1024 ? (sizeKb / 1024) + " MB" : sizeKb + " KB";
    }

    private static SqlRunner runner(Connection conn) {
        return new SqlRunner(conn, QUERY_TIMEOUT_SECONDS);
    }
}
