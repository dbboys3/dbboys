package com.dbboys.dialect.dameng;

import com.dbboys.dialect.common.OracleFamilyMetadataRepository;
import com.dbboys.infra.db.SqlRunner;
import com.dbboys.model.SysTable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Dameng metadata repository. All shared logic lives in {@link OracleFamilyMetadataRepository};
 * only Dameng-specific SQL text and behavior remain here.
 */
public final class DamengMetadataRepository extends OracleFamilyMetadataRepository {

    private static final String SQL_CURRENT_DATABASE = """
            select
                nvl(sys_context('USERENV', 'SERVICE_NAME'),
                    nvl(sys_context('USERENV', 'CON_NAME'),
                        sys_context('USERENV', 'DB_NAME'))) as dbname,
                username as owner,
                to_char(created, 'YYYY-MM-DD') as created_time,
                default_tablespace as dbspace,
                nvl(sys_context('USERENV', 'SERVICE_NAME'),
                    nvl(sys_context('USERENV', 'CON_NAME'),
                        sys_context('USERENV', 'DB_NAME'))) as service_name,
                sys_context('USERENV', 'LANGUAGE') as db_locale,
                nvl((select sum(bytes) from user_segments), 0) as schema_bytes
            from user_users
            """;

    private static final String SQL_USERS = """
            select username
            from all_users
            where username not in (
                'ANONYMOUS','APPQOSSYS','AUDSYS','CTXSYS','DBSNMP','DIP','DMSYS','DVF','DVSYS',
                'FLOWS_FILES','GGSYS','GSMADMIN_INTERNAL','GSMCATUSER','GSMUSER','LBACSYS','MDDATA',
                'MDSYS','OJVMSYS','OLAPSYS','DMHSYS','OUTLN','REMOTE_SCHEDULER_AGENT','SI_INFORMTN_SCHEMA',
                'SPATIAL_CSW_ADMIN_USR','SPATIAL_WFS_ADMIN_USR','SYS','SYS$UMF','SYSBACKUP','SYSDG',
                'SYSKM','SYSRAC','SYSTEM','WMSYS','XDB','XS$NULL'
            )
            order by username
            """;

    private static final String SQL_SESSION_SCHEMA_LIST_CONTEXT = """
            select
                nvl(sys_context('USERENV', 'SERVICE_NAME'),
                    nvl(sys_context('USERENV', 'CON_NAME'),
                        sys_context('USERENV', 'DB_NAME'))) as service_name,
                sys_context('USERENV', 'LANGUAGE') as db_locale
            from dual
            """;

    private static final String SQL_USERS_WITH_SIZE = """
            select u.username, nvl(s.total_bytes, 0) as schema_bytes,
                   to_char(u.created, 'YYYY-MM-DD') as created_time
            from all_users u
            left join (
                select owner, sum(bytes) as total_bytes
                from dba_segments
                group by owner
            ) s on s.owner = u.username
            where u.username not in (
                'ANONYMOUS','APPQOSSYS','AUDSYS','CTXSYS','DBSNMP','DIP','DMSYS','DVF','DVSYS',
                'FLOWS_FILES','GGSYS','GSMADMIN_INTERNAL','GSMCATUSER','GSMUSER','LBACSYS','MDDATA',
                'MDSYS','OJVMSYS','OLAPSYS','DMHSYS','OUTLN','REMOTE_SCHEDULER_AGENT','SI_INFORMTN_SCHEMA',
                'SPATIAL_CSW_ADMIN_USR','SPATIAL_WFS_ADMIN_USR','SYS','SYS$UMF','SYSBACKUP','SYSDG',
                'SYSKM','SYSRAC','SYSTEM','WMSYS','XDB','XS$NULL'
            )
            order by u.username
            """;

    /** Same filter as {@link #SQL_USERS}, plus schema creation date for tree tooltips. */
    private static final String SQL_USERS_WITH_CREATED = """
            select username, to_char(created, 'YYYY-MM-DD') as created_time
            from all_users
            where username not in (
                'ANONYMOUS','APPQOSSYS','AUDSYS','CTXSYS','DBSNMP','DIP','DMSYS','DVF','DVSYS',
                'FLOWS_FILES','GGSYS','GSMADMIN_INTERNAL','GSMCATUSER','GSMUSER','LBACSYS','MDDATA',
                'MDSYS','OJVMSYS','OLAPSYS','DMHSYS','OUTLN','REMOTE_SCHEDULER_AGENT','SI_INFORMTN_SCHEMA',
                'SPATIAL_CSW_ADMIN_USR','SPATIAL_WFS_ADMIN_USR','SYS','SYS$UMF','SYSBACKUP','SYSDG',
                'SYSKM','SYSRAC','SYSTEM','WMSYS','XDB','XS$NULL'
            )
            order by username
            """;

    private static final String SQL_SCHEMA_INFO = """
            select
                username as schema_name,
                to_char(created, 'YYYY-MM-DD') as created_time,
                nvl(sys_context('USERENV', 'SERVICE_NAME'),
                    nvl(sys_context('USERENV', 'CON_NAME'),
                        sys_context('USERENV', 'DB_NAME'))) as service_name,
                sys_context('USERENV', 'LANGUAGE') as db_locale
            from all_users
            where upper(username) = upper(?)
            """;

    private static final String SQL_USER_TABLES = """
            select
                t.owner,
                t.table_name,
                to_char(o.created, 'YYYY-MM-DD HH24:MI:SS') as created_time,
                nvl(tc.comments, '') as table_comment,
                nvl(t.num_rows, 0) as num_rows,
                nvl(t.blocks, 0) as blocks,
                nvl(t.logging, 'YES') as logging,
                (nvl(t.blocks, 0) * 8192) as size_bytes
            from all_tables t
            join all_objects o
              on o.owner = t.owner
             and o.object_name = t.table_name
             and o.object_type = 'TABLE'
            left join all_tab_comments tc
              on tc.owner = t.owner
             and tc.table_name = t.table_name
            where t.owner = ?
            order by t.table_name
            """;

    private static final String SQL_SEQUENCES = """
            select
                s.sequence_owner,
                s.sequence_name,
                to_char(o.created, 'YYYY-MM-DD HH24:MI:SS') as created_time,
                s.min_value,
                s.max_value,
                s.increment_by,
                s.cache_size,
                s.last_number,
                s.cycle_flag,
                s.order_flag
            from all_sequences s
            join all_objects o
              on o.owner = s.sequence_owner
             and o.object_name = s.sequence_name
             and o.object_type = 'SEQUENCE'
            where s.sequence_owner = ?
            order by s.sequence_name
            """;

    private static final String SQL_SYNONYMS = """
            select
                s.owner,
                s.synonym_name,
                s.table_owner,
                s.table_name,
                nvl(s.db_link, '') as db_link,
                to_char(o.created, 'YYYY-MM-DD HH24:MI:SS') as created_time
            from all_synonyms s
            left join all_objects o
              on o.owner = s.owner
             and o.object_name = s.synonym_name
             and o.object_type = 'SYNONYM'
            where s.owner = ?
            order by s.synonym_name
            """;

    private static final String SQL_TRIGGERS = """
            select
                t.owner,
                t.trigger_name,
                t.table_name,
                '' as trigger_type,
                t.status
            from all_triggers t
            where t.owner = ?
            order by t.trigger_name
            """;

    private static final String SQL_TRIGGER_DETAIL = """
            select
                t.owner,
                t.trigger_name,
                t.table_name,
                '' as trigger_type,
                t.status
            from all_triggers t
            where upper(t.owner) = upper(?)
              and upper(t.trigger_name) = upper(?)
            """;

    private static final String SQL_DICT_VIEW_COUNT = """
            select count(*)
            from all_objects
            where owner in ('SYS', 'SYSTEM')
              and object_type in ('TABLE', 'VIEW')
            """;

    private static final String SQL_DICT_VIEWS = """
            select object_name as table_name,
                   object_type as comments,
                   owner
            from all_objects
            where owner in ('SYS', 'SYSTEM')
              and object_type in ('TABLE', 'VIEW')
            order by owner, object_name
            """;

    private static final String SQL_VIEWS = """
            select
                v.owner,
                v.view_name,
                to_char(o.created, 'YYYY-MM-DD HH24:MI:SS') as created_time
            from all_views v
            join all_objects o
              on o.owner = v.owner
             and o.object_name = v.view_name
             and o.object_type = 'VIEW'
            where v.owner = ?
            order by v.view_name
            """;

    private static final String SQL_TYPE_COUNT = """
            select count(*)
            from all_objects
            where owner = ?
              and object_type in ('TYPE', 'CLASS')
            """;

    private static final String SQL_TYPES = """
            select object_name as type_name, owner, object_type as typecode
            from all_objects
            where owner = ?
              and object_type in ('TYPE', 'CLASS')
            order by object_name
            """;

    @Override
    protected String dialectName() {
        return "DAMENG";
    }

    @Override
    protected String sqlCurrentDatabase() {
        return SQL_CURRENT_DATABASE;
    }

    @Override
    protected String sqlUsers() {
        return SQL_USERS;
    }

    @Override
    protected String sqlSessionSchemaListContext() {
        return SQL_SESSION_SCHEMA_LIST_CONTEXT;
    }

    @Override
    protected String sqlUsersWithSize() {
        return SQL_USERS_WITH_SIZE;
    }

    @Override
    protected String sqlUsersWithCreated() {
        return SQL_USERS_WITH_CREATED;
    }

    @Override
    protected String sqlSchemaInfo() {
        return SQL_SCHEMA_INFO;
    }

    @Override
    protected String sqlUserTables() {
        return SQL_USER_TABLES;
    }

    @Override
    protected String sqlSequences() {
        return SQL_SEQUENCES;
    }

    @Override
    protected String sqlSynonyms() {
        return SQL_SYNONYMS;
    }

    @Override
    protected String sqlTriggers() {
        return SQL_TRIGGERS;
    }

    @Override
    protected String sqlTriggerDetail() {
        return SQL_TRIGGER_DETAIL;
    }

    @Override
    protected String sqlDictViewCount() {
        return SQL_DICT_VIEW_COUNT;
    }

    @Override
    protected String sqlViews() {
        return SQL_VIEWS;
    }

    @Override
    protected String sqlTypeCount() {
        return SQL_TYPE_COUNT;
    }

    @Override
    protected String sqlTypes() {
        return SQL_TYPES;
    }

    @Override
    public List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_DICT_VIEWS, null, rs -> {
            SysTable st = new SysTable(rs.getString("table_name"));
            String owner = blankToFallback(rs.getString("owner"), "SYS");
            st.setTableCatalog(owner);
            st.setTableOwner(owner);
            st.setTableComm(blankToEmpty(rs.getString("comments")));
            st.setTableTypeCode("view");
            return st;
        });
    }

    @Override
    protected String currentSchema(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        String schema = runner.queryOne(SQL_CURRENT_SCHEMA, null, rs -> rs.getString(1));
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        try {
            String jdbcSchema = conn.getSchema();
            if (jdbcSchema != null && !jdbcSchema.isBlank()) {
                return jdbcSchema;
            }
        } catch (Exception ignored) {
        }
        return blankToFallback(schema, "DAMENG");
    }

    /** Missing dictionary object while probing optional Oracle-compatible metadata views. */
    @Override
    protected boolean isOra942ObjectNotExists(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException se) {
                int code = se.getErrorCode();
                if (code == 942 || code == -2106 || code == 2106) {
                    return true;
                }
                String message = se.getMessage();
                if (message != null) {
                    String normalized = message.toUpperCase(Locale.ROOT);
                    if (normalized.contains("INVALID TABLE OR VIEW NAME")
                            || normalized.contains("无效的表或视图名")) {
                        return true;
                    }
                }
            }
            t = t.getCause();
        }
        return false;
    }


    @Override
    protected void applyCurrentSchema(Connection conn, String databaseName) throws SQLException {
        if (conn == null || databaseName == null || databaseName.isBlank()) {
            return;
        }
        String quotedSchema = "\"" + databaseName.replace("\"", "\"\"") + "\"";
        try (var stmt = conn.createStatement()) {
            try {
                stmt.execute("alter session set current_schema = " + quotedSchema);
            } catch (SQLException first) {
                stmt.execute("set schema " + quotedSchema);
            }
        }
    }
}
