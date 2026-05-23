package com.dbboys.impl.dialect.dameng;

import com.dbboys.api.MetadataRepository;
import com.dbboys.db.SqlRunner;
import com.dbboys.vo.ColumnsInfo;
import com.dbboys.vo.DBPackage;
import com.dbboys.vo.Catalog;
import com.dbboys.vo.Function;
import com.dbboys.vo.Index;
import com.dbboys.vo.Queue;
import com.dbboys.vo.Type;
import com.dbboys.vo.Procedure;
import com.dbboys.vo.Sequence;
import com.dbboys.vo.Synonym;
import com.dbboys.vo.SysTable;
import com.dbboys.vo.Table;
import com.dbboys.vo.Trigger;
import com.dbboys.vo.User;
import com.dbboys.vo.View;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DamengMetadataRepository implements MetadataRepository {
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final String ZERO_SIZE = "0B";
    private static final int DAMENG_FLAGS = 16384;
    private static final int DAMENG_PROC_FLAGS = 4;

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

    private static final String SQL_CURRENT_SCHEMA = """
            select sys_context('USERENV', 'CURRENT_SCHEMA') from dual
            """;

    private static final String SQL_SESSION_USER = """
            select user from dual
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

    private static final String SQL_USER_TABLES_COUNT = """
            select count(*)
            from all_tables
            where owner = ?
            """;

    /** Logged-in user's segments only; used as last resort when not browsing own schema. */
    private static final String SQL_USER_TABLES_SIZE = """
            select nvl(sum(bytes), 0)
            from user_segments
            where segment_type like 'TABLE%%' or segment_type = 'NESTED TABLE'
            """;

    private static final String SQL_DBA_TABLE_SEGMENTS_SUM_BY_OWNER = """
            select nvl(sum(bytes), 0)
            from dba_segments
            where owner = ?
              and (segment_type like 'TABLE%%' or segment_type = 'NESTED TABLE')
            """;

    private static final String SQL_ALL_TABLE_SEGMENTS_SUM_BY_OWNER = """
            select nvl(sum(bytes), 0)
            from all_segments
            where owner = ?
              and (segment_type like 'TABLE%%' or segment_type = 'NESTED TABLE')
            """;

    /**
     * Table folder list: avoid {@code ALL_SEGMENTS}/{@code USER_SEGMENTS} (full scan + group by per schema — very slow).
     * Row size uses {@code blocks * 8192} as a coarse estimate (default block size; single-table DDL still uses exact segments).
     */
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

    /**
     * Minimal fallback if a dictionary join is not visible (ORA-00942); still avoids segment views.
     */
    private static final String SQL_USER_TABLES_MINIMAL_FALLBACK = """
            select
                t.owner,
                t.table_name,
                cast(null as varchar2(20)) as created_time,
                cast(null as varchar2(1)) as table_comment,
                nvl(t.num_rows, 0) as num_rows,
                nvl(t.blocks, 0) as blocks,
                nvl(t.logging, 'YES') as logging,
                (nvl(t.blocks, 0) * 8192) as size_bytes
            from all_tables t
            where t.owner = ?
            order by t.table_name
            """;

    private static final String SQL_TABLE_DETAIL = """
            select
                t.owner,
                t.table_name,
                to_char(o.created, 'YYYY-MM-DD HH24:MI:SS') as created_time,
                nvl(tc.comments, '') as table_comment,
                nvl(t.num_rows, 0) as num_rows,
                nvl(t.blocks, 0) as blocks,
                nvl(t.logging, 'YES') as logging,
                nvl(s.bytes, 0) as size_bytes
            from all_tables t
            join all_objects o
              on o.owner = t.owner
             and o.object_name = t.table_name
             and o.object_type = 'TABLE'
            left join all_tab_comments tc
              on tc.owner = t.owner
             and tc.table_name = t.table_name
            left join (
                select segment_name, sum(bytes) as bytes
                from all_segments
                where owner = ?
                  and (segment_type like 'TABLE%%' or segment_type = 'NESTED TABLE')
                group by segment_name
            ) s
              on s.segment_name = t.table_name
            where upper(t.owner) = upper(?)
              and upper(t.table_name) = upper(?)
            """;

    private static final String SQL_TABLE_DETAIL_VIA_USER_SEGMENTS = """
            select
                t.owner,
                t.table_name,
                to_char(o.created, 'YYYY-MM-DD HH24:MI:SS') as created_time,
                nvl(tc.comments, '') as table_comment,
                nvl(t.num_rows, 0) as num_rows,
                nvl(t.blocks, 0) as blocks,
                nvl(t.logging, 'YES') as logging,
                nvl(s.bytes, 0) as size_bytes
            from all_tables t
            join all_objects o
              on o.owner = t.owner
             and o.object_name = t.table_name
             and o.object_type = 'TABLE'
            left join all_tab_comments tc
              on tc.owner = t.owner
             and tc.table_name = t.table_name
            left join (
                select segment_name, sum(bytes) as bytes
                from user_segments
                where segment_type like 'TABLE%%' or segment_type = 'NESTED TABLE'
                group by segment_name
            ) s
              on s.segment_name = t.table_name
            where upper(t.owner) = upper(?)
              and upper(t.table_name) = upper(?)
            """;

    private static final String SQL_TABLE_DETAIL_WITHOUT_SEGMENT_BYTES = """
            select
                t.owner,
                t.table_name,
                to_char(o.created, 'YYYY-MM-DD HH24:MI:SS') as created_time,
                nvl(tc.comments, '') as table_comment,
                nvl(t.num_rows, 0) as num_rows,
                nvl(t.blocks, 0) as blocks,
                nvl(t.logging, 'YES') as logging,
                cast(0 as number) as size_bytes
            from all_tables t
            join all_objects o
              on o.owner = t.owner
             and o.object_name = t.table_name
             and o.object_type = 'TABLE'
            left join all_tab_comments tc
              on tc.owner = t.owner
             and tc.table_name = t.table_name
            where upper(t.owner) = upper(?)
              and upper(t.table_name) = upper(?)
            """;

    /** Same row shape as detail queries; coarse size from blocks (aligned with SQL_USER_TABLES) when all_segments is unavailable. */
    private static final String SQL_TABLE_DETAIL_BLOCK_SIZE_ESTIMATE = """
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
            where upper(t.owner) = upper(?)
              and upper(t.table_name) = upper(?)
            """;

    private static final String SQL_TABLE_COMMENT = """
            select nvl(comments, '')
            from all_tab_comments
            where upper(owner) = upper(?)
              and upper(table_name) = upper(?)
            """;

    private static final String SQL_COLUMNS = """
            select
                c.data_default as default_value,
                c.column_id,
                c.column_name,
                c.data_type,
                nvl(c.char_length, c.data_length) as column_length,
                case
                    when c.data_type in ('CHAR', 'NCHAR', 'VARCHAR2', 'NVARCHAR2') then nvl(c.char_length, c.data_length)
                    else nvl(c.data_precision, 0)
                end as precision_value,
                nvl(c.data_scale, 0) as scale_value,
                c.nullable,
                case when pk.column_name is not null then 1 else 0 end as is_pk,
                nvl(cc.comments, '') as comments
            from all_tab_columns c
            left join all_col_comments cc
              on cc.owner = c.owner
             and cc.table_name = c.table_name
             and cc.column_name = c.column_name
            left join (
                select acc.owner, acc.table_name, acc.column_name
                from all_cons_columns acc
                join all_constraints ac
                  on ac.owner = acc.owner
                 and ac.table_name = acc.table_name
                 and ac.constraint_name = acc.constraint_name
                where ac.constraint_type = 'P'
            ) pk
              on pk.owner = c.owner
             and pk.table_name = c.table_name
             and pk.column_name = c.column_name
            where upper(c.owner) = upper(?)
              and upper(c.table_name) = upper(?)
            order by c.column_id
            """;

    private static final String SQL_INDEX_COUNT = """
            select count(*)
            from all_indexes
            where owner = ?
              and generated = 'N'
            """;

    private static final String SQL_INDEX_SIZE = """
            select nvl(sum(bytes), 0)
            from user_segments
            where segment_type like 'INDEX%%'
            """;

    private static final String SQL_DBA_INDEX_SEGMENTS_SUM_BY_OWNER = """
            select nvl(sum(bytes), 0)
            from dba_segments
            where owner = ?
              and segment_type like 'INDEX%%'
            """;

    private static final String SQL_ALL_INDEX_SEGMENTS_SUM_BY_OWNER = """
            select nvl(sum(bytes), 0)
            from all_segments
            where owner = ?
              and segment_type like 'INDEX%%'
            """;

    private static final String SQL_INDEXES = """
            select
                i.owner,
                i.index_name,
                i.table_name,
                i.index_type,
                nvl(cols.index_cols, '') as index_cols,
                nvl(i.blevel, 0) as blevel,
                nvl(i.distinct_keys, 0) as distinct_keys,
                nvl(i.leaf_blocks, 0) as leaf_blocks,
                nvl(s.bytes, 0) as size_bytes,
                nvl(i.status, 'VALID') as status
            from all_indexes i
            left join (
                select index_owner, index_name,
                       listagg(column_name, ',') within group (order by column_position) as index_cols
                from all_ind_columns
                group by index_owner, index_name
            ) cols
              on cols.index_owner = i.owner
             and cols.index_name = i.index_name
            left join (
                select segment_name, sum(bytes) as bytes
                from all_segments
                where owner = ?
                  and segment_type like 'INDEX%%'
                group by segment_name
            ) s
              on s.segment_name = i.index_name
            where i.owner = ?
              and i.generated = 'N'
            order by i.index_name
            """;

    private static final String SQL_INDEXES_VIA_USER_SEGMENTS = """
            select
                i.owner,
                i.index_name,
                i.table_name,
                i.index_type,
                nvl(cols.index_cols, '') as index_cols,
                nvl(i.blevel, 0) as blevel,
                nvl(i.distinct_keys, 0) as distinct_keys,
                nvl(i.leaf_blocks, 0) as leaf_blocks,
                nvl(s.bytes, 0) as size_bytes,
                nvl(i.status, 'VALID') as status
            from all_indexes i
            left join (
                select index_owner, index_name,
                       listagg(column_name, ',') within group (order by column_position) as index_cols
                from all_ind_columns
                group by index_owner, index_name
            ) cols
              on cols.index_owner = i.owner
             and cols.index_name = i.index_name
            left join (
                select segment_name, sum(bytes) as bytes
                from user_segments
                where segment_type like 'INDEX%%'
                group by segment_name
            ) s
              on s.segment_name = i.index_name
            where i.owner = ?
              and i.generated = 'N'
            order by i.index_name
            """;

    private static final String SQL_INDEXES_WITHOUT_SEGMENT_BYTES = """
            select
                i.owner,
                i.index_name,
                i.table_name,
                i.index_type,
                nvl(cols.index_cols, '') as index_cols,
                nvl(i.blevel, 0) as blevel,
                nvl(i.distinct_keys, 0) as distinct_keys,
                nvl(i.leaf_blocks, 0) as leaf_blocks,
                cast(0 as number) as size_bytes,
                nvl(i.status, 'VALID') as status
            from all_indexes i
            left join (
                select index_owner, index_name,
                       listagg(column_name, ',') within group (order by column_position) as index_cols
                from all_ind_columns
                group by index_owner, index_name
            ) cols
              on cols.index_owner = i.owner
             and cols.index_name = i.index_name
            where i.owner = ?
              and i.generated = 'N'
            order by i.index_name
            """;

    private static final String SQL_INDEX_DETAIL = """
            select
                i.owner,
                i.index_name,
                i.table_name,
                i.index_type,
                nvl(cols.index_cols, '') as index_cols,
                nvl(i.blevel, 0) as blevel,
                nvl(i.distinct_keys, 0) as distinct_keys,
                nvl(i.leaf_blocks, 0) as leaf_blocks,
                nvl(s.bytes, 0) as size_bytes,
                nvl(i.status, 'VALID') as status
            from all_indexes i
            left join (
                select index_owner, index_name,
                       listagg(column_name, ',') within group (order by column_position) as index_cols
                from all_ind_columns
                group by index_owner, index_name
            ) cols
              on cols.index_owner = i.owner
             and cols.index_name = i.index_name
            left join (
                select segment_name, sum(bytes) as bytes
                from all_segments
                where owner = ?
                  and segment_type like 'INDEX%%'
                group by segment_name
            ) s
              on s.segment_name = i.index_name
            where upper(i.owner) = upper(?)
              and upper(i.index_name) = upper(?)
              and i.generated = 'N'
            """;

    private static final String SQL_INDEX_DETAIL_VIA_USER_SEGMENTS = """
            select
                i.owner,
                i.index_name,
                i.table_name,
                i.index_type,
                nvl(cols.index_cols, '') as index_cols,
                nvl(i.blevel, 0) as blevel,
                nvl(i.distinct_keys, 0) as distinct_keys,
                nvl(i.leaf_blocks, 0) as leaf_blocks,
                nvl(s.bytes, 0) as size_bytes,
                nvl(i.status, 'VALID') as status
            from all_indexes i
            left join (
                select index_owner, index_name,
                       listagg(column_name, ',') within group (order by column_position) as index_cols
                from all_ind_columns
                group by index_owner, index_name
            ) cols
              on cols.index_owner = i.owner
             and cols.index_name = i.index_name
            left join (
                select segment_name, sum(bytes) as bytes
                from user_segments
                where segment_type like 'INDEX%%'
                group by segment_name
            ) s
              on s.segment_name = i.index_name
            where upper(i.owner) = upper(?)
              and upper(i.index_name) = upper(?)
              and i.generated = 'N'
            """;

    private static final String SQL_INDEX_DETAIL_WITHOUT_SEGMENT_BYTES = """
            select
                i.owner,
                i.index_name,
                i.table_name,
                i.index_type,
                nvl(cols.index_cols, '') as index_cols,
                nvl(i.blevel, 0) as blevel,
                nvl(i.distinct_keys, 0) as distinct_keys,
                nvl(i.leaf_blocks, 0) as leaf_blocks,
                cast(0 as number) as size_bytes,
                nvl(i.status, 'VALID') as status
            from all_indexes i
            left join (
                select index_owner, index_name,
                       listagg(column_name, ',') within group (order by column_position) as index_cols
                from all_ind_columns
                group by index_owner, index_name
            ) cols
              on cols.index_owner = i.owner
             and cols.index_name = i.index_name
            where upper(i.owner) = upper(?)
              and upper(i.index_name) = upper(?)
              and i.generated = 'N'
            """;

    private static final String SQL_SEQUENCE_COUNT = """
            select count(*)
            from all_sequences
            where sequence_owner = ?
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

    private static final String SQL_SYNONYM_COUNT = """
            select count(*)
            from all_synonyms
            where owner = ?
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

    private static final String SQL_TRIGGER_COUNT = """
            select count(*)
            from all_triggers
            where owner = ?
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

    private static final String SQL_VIEW_COUNT = """
            select count(*)
            from all_objects
            where owner = ?
              and object_type = 'VIEW'
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

    private static final String SQL_FUNCTION_COUNT = """
            select count(*)
            from all_objects
            where owner = ?
              and object_type = 'FUNCTION'
            """;

    private static final String SQL_FUNCTIONS = """
            select owner, object_name
            from all_objects
            where owner = ?
              and object_type = 'FUNCTION'
            order by object_name
            """;

    private static final String SQL_PROCEDURE_COUNT = """
            select count(*)
            from all_objects
            where owner = ?
              and object_type = 'PROCEDURE'
            """;

    private static final String SQL_PROCEDURES = """
            select owner, object_name
            from all_objects
            where owner = ?
              and object_type = 'PROCEDURE'
            order by object_name
            """;

    private static final String SQL_PACKAGE_COUNT = """
            select count(*)
            from all_objects
            where owner = ?
              and object_type = 'PACKAGE'
            """;

    private static final String SQL_PACKAGES = """
            select
                p.owner,
                p.object_name as package_name,
                case when b.object_name is null then 1 else 0 end as is_empty
            from all_objects p
            left join all_objects b
              on b.owner = p.owner
             and b.object_name = p.object_name
             and b.object_type = 'PACKAGE BODY'
            where p.owner = ?
              and p.object_type = 'PACKAGE'
            order by p.object_name
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

    private static final String SQL_QUEUE_COUNT = """
            select count(*)
            from all_queues
            where owner = ?
            """;

    private static final String SQL_QUEUES = """
            select name, owner, queue_table
            from all_queues
            where owner = ?
            order by name
            """;

    private static final String SQL_SCHEDULER_JOB_COUNT = """
            select count(*) from user_scheduler_jobs
            """;

    private static final String SQL_SCHEDULER_JOB_NAMES = """
            select job_name from user_scheduler_jobs order by job_name
            """;

    private static final String SQL_RECYCLE_BIN_COUNT = """
            select count(*) from user_recyclebin
            """;

    private static final String SQL_RECYCLE_BIN_NAMES = """
            select nvl(nullif(trim(original_name), ''), object_name) as display_name
            from user_recyclebin
            order by object_name
            """;

    private static final String SQL_INDEX_COLUMNS_FOR_TABLE = """
            select cols
            from (
                select index_name,
                       listagg(column_name, ',') within group (order by column_position) as cols
                from all_ind_columns
                where upper(table_owner) = upper(?)
                  and upper(table_name) = upper(?)
                group by index_name
            )
            order by 1
            """;

    @Override
    public List<User> getUsers(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.query(SQL_USERS, null, rs -> new User(rs.getString(1)));
    }

    @Override
    public List<Catalog> getDatabases(Connection conn) throws SQLException {
        return getMetadataDatabases(conn);
    }

    @Override
    public List<Catalog> getMetadataDatabases(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        SessionSchemaListContext sessionCtx = loadSessionSchemaListContext(runner);
        List<Catalog> schemas;
        try {
            schemas = runner.query(SQL_USERS_WITH_SIZE, null, rs -> {
                Catalog db = mapSchemaDatabase(rs.getString(1));
                db.setDbSize(formatBytes(rs.getBigDecimal(2)));
                db.setDbCreated(blankToEmpty(rs.getString("created_time")));
                applySessionSchemaListContext(db, sessionCtx);
                return db;
            });
        } catch (SQLException e) {
            schemas = runner.query(SQL_USERS_WITH_CREATED, null, rs -> {
                Catalog db = mapSchemaDatabase(rs.getString(1));
                db.setDbCreated(blankToEmpty(rs.getString("created_time")));
                applySessionSchemaListContext(db, sessionCtx);
                return db;
            });
        }
        String currentSchema = currentSchema(conn);
        boolean exists = schemas.stream().anyMatch(schema -> currentSchema.equalsIgnoreCase(schema.getName()));
        if (!exists) {
            schemas = new ArrayList<>(schemas);
            Catalog injected = mapSchemaDatabase(currentSchema);
            String created = querySchemaCreatedTime(runner, currentSchema);
            if (created != null) {
                injected.setDbCreated(blankToEmpty(created));
            }
            applySessionSchemaListContext(injected, sessionCtx);
            schemas.add(0, injected);
        }
        return schemas;
    }

    private record SessionSchemaListContext(String serviceName, String dbLocale) {
        static SessionSchemaListContext empty() {
            return new SessionSchemaListContext("", "");
        }
    }

    private SessionSchemaListContext loadSessionSchemaListContext(SqlRunner runner) throws SQLException {
        try {
            SessionSchemaListContext ctx = runner.queryOne(SQL_SESSION_SCHEMA_LIST_CONTEXT, null, rs ->
                    new SessionSchemaListContext(
                            blankToEmpty(rs.getString("service_name")),
                            blankToEmpty(rs.getString("db_locale"))));
            return ctx != null ? ctx : SessionSchemaListContext.empty();
        } catch (SQLException e) {
            return SessionSchemaListContext.empty();
        }
    }

    private static void applySessionSchemaListContext(Catalog db, SessionSchemaListContext ctx) {
        if (ctx == null) {
            return;
        }
        db.setDbUseGLU(ctx.serviceName());
        db.setDbLocale(ctx.dbLocale());
    }

    private String querySchemaCreatedTime(SqlRunner runner, String schemaName) throws SQLException {
        return runner.queryOne(
                """
                        select to_char(created, 'YYYY-MM-DD') as created_time
                        from all_users
                        where upper(username) = upper(?)
                        """,
                List.of(schemaName),
                rs -> rs.getString(1));
    }

    @Override
    public Catalog getDatabaseInfo(Connection conn, String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isBlank()) {
            return loadCurrentDatabase(conn);
        }
        Catalog schema = loadSchemaInfo(conn, databaseName);
        if (schema != null) {
            return schema;
        }
        Catalog current = loadCurrentDatabase(conn);
        return databaseName.equalsIgnoreCase(current.getName()) ? current : null;
    }

    @Override
    public int getUserTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_USER_TABLES_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public String getUserTablesSize(Connection conn, String databaseName) throws SQLException {
        return queryOwnerTableSegmentsTotalSize(conn, resolveBrowsedSchemaOwner(conn, databaseName));
    }

    @Override
    public int getSystemTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_DICT_VIEW_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public String getSystemTablesSize(Connection conn, String databaseName) {
        return null;
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
    public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = resolveBrowsedSchemaOwner(conn, databaseName);
        boolean includeSize = canReadSchemaSegmentSize(owner);
        try {
            return runner.query(SQL_USER_TABLES, List.of(owner), rs -> mapTable(rs, owner, includeSize));
        } catch (SQLException e) {
            if (!isOra942ObjectNotExists(e)) {
                throw e;
            }
            return runner.query(SQL_USER_TABLES_MINIMAL_FALLBACK, List.of(owner), rs -> mapTable(rs, owner, includeSize));
        }
    }

    @Override
    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        QualifiedObjectName objectName = parseObjectName(tableName);
        SqlRunner runner = runner(conn);
        String owner = resolveTableOwner(conn, databaseName, objectName.owner());
        boolean includeSize = canReadSchemaSegmentSize(owner);
        String tab = objectName.objectName();
        try {
            return runner.queryOne(SQL_TABLE_DETAIL, List.of(owner, owner, tab), rs -> mapTable(rs, owner, includeSize));
        } catch (SQLException e) {
            if (!isOra942ObjectNotExists(e)) {
                throw e;
            }
            if (owner.equalsIgnoreCase(sessionUser(conn))) {
                return runner.queryOne(SQL_TABLE_DETAIL_VIA_USER_SEGMENTS, List.of(owner, tab), rs -> mapTable(rs, owner, includeSize));
            }
            try {
                return runner.queryOne(SQL_TABLE_DETAIL_BLOCK_SIZE_ESTIMATE, List.of(owner, tab), rs -> mapTable(rs, owner, true));
            } catch (SQLException e2) {
                if (!isOra942ObjectNotExists(e2)) {
                    throw e2;
                }
                return runner.queryOne(SQL_TABLE_DETAIL_WITHOUT_SEGMENT_BYTES, List.of(owner, tab), rs -> mapTable(rs, owner, false));
            }
        }
    }

    @Override
    public String getTableComment(Connection conn, String tableName) throws SQLException {
        QualifiedObjectName objectName = parseObjectName(tableName);
        SqlRunner runner = runner(conn);
        String owner = resolveOwner(conn, objectName.owner());
        String comment = runner.queryOne(SQL_TABLE_COMMENT, List.of(owner, objectName.objectName()), rs -> rs.getString(1));
        return comment == null ? "" : comment;
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        QualifiedObjectName objectName = parseObjectName(tableName);
        SqlRunner runner = runner(conn);
        String owner = resolveOwner(conn, objectName.owner());
        List<ColumnsInfo> rows = runner.query(SQL_COLUMNS, List.of(owner, objectName.objectName()), rs -> {
            ColumnsInfo columnsInfo = new ColumnsInfo();
            // ALL_TAB_COLUMNS.DATA_DEFAULT is LONG: cannot appear in CASE/REPLACE/TO_CLOB in SQL; JDBC must read it before other columns.
            String defNorm = normalizeDamengColumnDefault(rs.getString("default_value"));
            columnsInfo.setColDefType(defNorm.isEmpty() ? "" : "DEFAULT");
            columnsInfo.setColDef(defNorm);
            columnsInfo.setColNo(rs.getInt("column_id"));
            columnsInfo.setColName(rs.getString("column_name"));
            columnsInfo.setColType(rs.getString("data_type"));
            columnsInfo.setColLength(rs.getInt("column_length"));
            columnsInfo.setTypeP(rs.getInt("precision_value"));
            columnsInfo.setTypeS(rs.getInt("scale_value"));
            columnsInfo.setIsNullable("Y".equalsIgnoreCase(rs.getString("nullable")));
            columnsInfo.setIsPK(rs.getInt("is_pk") == 1);
            columnsInfo.setColComm(blankToEmpty(rs.getString("comments")));
            columnsInfo.setIsAutoincrement(false);
            return columnsInfo;
        });
        return new ArrayList<>(rows);
    }

    @Override
    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        boolean includeSize = canReadSchemaSegmentSize(owner);
        try {
            return runner.query(SQL_INDEXES, List.of(owner, owner), rs -> mapIndex(rs, owner, includeSize));
        } catch (SQLException e) {
            if (!isOra942ObjectNotExists(e)) {
                throw e;
            }
            if (owner.equalsIgnoreCase(sessionUser(conn))) {
                return runner.query(SQL_INDEXES_VIA_USER_SEGMENTS, List.of(owner), rs -> mapIndex(rs, owner, includeSize));
            }
            return runner.query(SQL_INDEXES_WITHOUT_SEGMENT_BYTES, List.of(owner), rs -> mapIndex(rs, owner, false));
        }
    }

    @Override
    public int getIndexCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_INDEX_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public String getIndexSize(Connection conn) throws SQLException {
        return queryOwnerIndexSegmentsTotalSize(conn, currentSchema(conn));
    }

    @Override
    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        QualifiedObjectName objectName = parseObjectName(indexName);
        SqlRunner runner = runner(conn);
        String owner = resolveOwner(conn, objectName.owner());
        boolean includeSize = canReadSchemaSegmentSize(owner);
        String idx = objectName.objectName();
        try {
            return runner.queryOne(SQL_INDEX_DETAIL, List.of(owner, owner, idx), rs -> mapIndex(rs, owner, includeSize));
        } catch (SQLException e) {
            if (!isOra942ObjectNotExists(e)) {
                throw e;
            }
            if (owner.equalsIgnoreCase(sessionUser(conn))) {
                return runner.queryOne(SQL_INDEX_DETAIL_VIA_USER_SEGMENTS, List.of(owner, idx), rs -> mapIndex(rs, owner, includeSize));
            }
            return runner.queryOne(SQL_INDEX_DETAIL_WITHOUT_SEGMENT_BYTES, List.of(owner, idx), rs -> mapIndex(rs, owner, false));
        }
    }

    @Override
    public int getSequenceCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_SEQUENCE_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        return runner.query(SQL_SEQUENCES, List.of(owner), rs -> mapSequence(rs, owner));
    }

    @Override
    public int getSynonymCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_SYNONYM_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        return runner.query(SQL_SYNONYMS, List.of(owner), rs -> mapSynonym(rs, owner));
    }

    @Override
    public int getTriggerCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_TRIGGER_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        return runner.query(SQL_TRIGGERS, List.of(owner), rs -> mapTrigger(rs, owner));
    }

    @Override
    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException {
        QualifiedObjectName objectName = parseObjectName(triggerName);
        SqlRunner runner = runner(conn);
        String owner = resolveOwner(conn, objectName.owner());
        return runner.queryOne(SQL_TRIGGER_DETAIL, List.of(owner, objectName.objectName()), rs -> mapTrigger(rs, owner));
    }

    @Override
    public int getViewCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_VIEW_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        return runner.query(SQL_VIEWS, List.of(owner), rs -> {
            View view = new View(rs.getString("view_name"));
            view.setDbname(owner);
            view.setOwner(rs.getString("owner"));
            view.setCreateTime(blankToEmpty(rs.getString("created_time")));
            view.setFlags(DAMENG_FLAGS);
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
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_FUNCTION_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        return runner.query(SQL_FUNCTIONS, List.of(owner), rs -> {
            Function function = new Function(rs.getString("object_name"));
            function.setDatabase(owner);
            function.setOwner(rs.getString("owner"));
            function.setRows(0);
            return function;
        });
    }

    @Override
    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        Integer value = runner.queryOne(SQL_PROCEDURE_COUNT, List.of(currentSchema(conn)), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        return runner.query(SQL_PROCEDURES, List.of(owner), rs -> {
            Procedure procedure = new Procedure(rs.getString("object_name"));
            procedure.setDatabase(owner);
            procedure.setOwner(rs.getString("owner"));
            procedure.setRows(0);
            procedure.setProcFlags(DAMENG_PROC_FLAGS);
            return procedure;
        });
    }

    @Override
    public int getPackageCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = currentSchema(conn);
        Integer value = runner.queryOne(SQL_PACKAGE_COUNT, List.of(owner), rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    @Override
    public List<DBPackage> getPackages(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = (databaseName == null || databaseName.isBlank()) ? currentSchema(conn) : databaseName;
        return runner.query(SQL_PACKAGES, List.of(owner), rs -> {
            DBPackage dbPackage = new DBPackage(rs.getString("package_name"));
            dbPackage.setDatabase(owner);
            dbPackage.setOwner(rs.getString("owner"));
            dbPackage.setRows(0);
            dbPackage.setIsEmpty(rs.getInt("is_empty") == 1);
            return dbPackage;
        });
    }

    @Override
    public int getObjectTypeCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = (databaseName == null || databaseName.isBlank()) ? currentSchema(conn) : databaseName;
        try {
            Integer value = runner.queryOne(SQL_TYPE_COUNT, List.of(owner), rs -> rs.getInt(1));
            return value == null ? 0 : value;
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public List<Type> getObjectTypes(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = (databaseName == null || databaseName.isBlank()) ? currentSchema(conn) : databaseName;
        try {
            return runner.query(SQL_TYPES, List.of(owner), rs -> {
                Type row = new Type(rs.getString("type_name"));
                row.setDatabase(owner);
                row.setOwner(rs.getString("owner"));
                row.setTypeKind(blankToEmpty(rs.getString("typecode")));
                return row;
            });
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return List.of();
            }
            throw e;
        }
    }

    @Override
    public int getQueueCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = (databaseName == null || databaseName.isBlank()) ? currentSchema(conn) : databaseName;
        try {
            Integer value = runner.queryOne(SQL_QUEUE_COUNT, List.of(owner), rs -> rs.getInt(1));
            return value == null ? 0 : value;
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public List<Queue> getQueues(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        String owner = (databaseName == null || databaseName.isBlank()) ? currentSchema(conn) : databaseName;
        try {
            return runner.query(SQL_QUEUES, List.of(owner), rs -> {
                Queue row = new Queue(rs.getString("name"));
                row.setDatabase(owner);
                row.setOwner(rs.getString("owner"));
                return row;
            });
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return List.of();
            }
            throw e;
        }
    }

    @Override
    public int getSchedulerJobCount(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        try {
            Integer value = runner.queryOne(SQL_SCHEDULER_JOB_COUNT, null, rs -> rs.getInt(1));
            return value == null ? 0 : value;
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public List<String> getSchedulerJobNames(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = runner(conn);
        try {
            return runner.query(SQL_SCHEDULER_JOB_NAMES, null, rs -> blankToEmpty(rs.getString(1)));
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return List.of();
            }
            throw e;
        }
    }

    @Override
    public int getRecycleBinCount(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        try {
            Integer value = runner.queryOne(SQL_RECYCLE_BIN_COUNT, null, rs -> rs.getInt(1));
            return value == null ? 0 : value;
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return 0;
            }
            throw e;
        }
    }

    @Override
    public List<String> getRecycleBinDisplayNames(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        try {
            return runner.query(SQL_RECYCLE_BIN_NAMES, null, rs -> blankToEmpty(rs.getString(1)));
        } catch (SQLException e) {
            if (isOra942ObjectNotExists(e)) {
                return List.of();
            }
            throw e;
        }
    }

    @Override
    public List<String> getStorageSpacesForCreateDatabase(Connection conn) {
        return List.of();
    }

    @Override
    public void changeDatabase(Connection conn, String databaseName) throws SQLException {
        applyCurrentSchema(conn, databaseName);
    }

    @Override
    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        applyCurrentSchema(conn, databaseName);
    }

    @Override
    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        QualifiedObjectName objectName = parseObjectName(tableName);
        SqlRunner runner = runner(conn);
        String owner = resolveOwner(conn, objectName.owner());
        return runner.query(SQL_INDEX_COLUMNS_FOR_TABLE, List.of(owner, objectName.objectName()), rs -> rs.getString(1));
    }

    private SqlRunner runner(Connection conn) {
        return new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    private Catalog loadCurrentDatabase(Connection conn) throws SQLException {
        String fallbackName = fallbackDatabaseName(conn);
        String fallbackOwner = currentSchema(conn);
        SqlRunner runner = runner(conn);
        Catalog database = runner.queryOne(SQL_CURRENT_DATABASE, null, rs -> {
            Catalog row = new Catalog(blankToFallback(rs.getString("dbname"), fallbackName));
            row.setDbOwner(blankToFallback(rs.getString("owner"), fallbackOwner));
            row.setDbCreated(blankToEmpty(rs.getString("created_time")));
            row.setDbSpace(blankToEmpty(rs.getString("dbspace")));
            row.setDbLog("");
            row.setDbUseGLU(blankToEmpty(rs.getString("service_name")));
            row.setDbLocale(blankToEmpty(rs.getString("db_locale")));
            row.setDbSize(formatBytes(rs.getBigDecimal("schema_bytes")));
            return row;
        });
        if (database != null) {
            return database;
        }
        Catalog fallback = new Catalog(fallbackName);
        fallback.setDbOwner(fallbackOwner);
        fallback.setDbCreated("");
        fallback.setDbSpace("");
        fallback.setDbLog("");
        fallback.setDbUseGLU("");
        fallback.setDbLocale("");
        fallback.setDbSize(ZERO_SIZE);
        return fallback;
    }

    private static final String SQL_SCHEMA_SIZE = """
            select nvl(sum(bytes), 0) from dba_segments where owner = ?
            """;

    private Catalog loadSchemaInfo(Connection conn, String schemaName) throws SQLException {
        SqlRunner runner = runner(conn);
        return runner.queryOne(SQL_SCHEMA_INFO, List.of(schemaName), rs -> {
            Catalog schema = new Catalog(blankToFallback(rs.getString("schema_name"), schemaName));
            schema.setDbOwner(blankToFallback(rs.getString("schema_name"), schemaName));
            schema.setDbCreated(blankToEmpty(rs.getString("created_time")));
            schema.setDbSpace("");
            schema.setDbLog("");
            schema.setDbUseGLU(blankToEmpty(rs.getString("service_name")));
            schema.setDbLocale(blankToEmpty(rs.getString("db_locale")));
            schema.setDbSize(querySchemaSize(conn, schema.getName()));
            return schema;
        });
    }

    private String querySchemaSize(Connection conn, String schemaName) {
        try {
            return queryFormattedSize(conn, SQL_SCHEMA_SIZE, List.of(schemaName));
        } catch (SQLException e) {
            try {
                if (schemaName.equalsIgnoreCase(sessionUser(conn))) {
                    return queryFormattedSize(conn, SQL_USER_TABLES_SIZE, null);
                }
            } catch (SQLException ignored) {
            }
            return "";
        }
    }

    private String currentSchema(Connection conn) throws SQLException {
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

    private String sessionUser(Connection conn) throws SQLException {
        SqlRunner runner = runner(conn);
        String sessionUser = runner.queryOne(SQL_SESSION_USER, null, rs -> rs.getString(1));
        return blankToFallback(sessionUser, "DAMENG");
    }

    /**
     * {@code user_segments} reflects the login user, not {@code CURRENT_SCHEMA}. List/detail SQL uses
     * {@code all_segments} with owner so sizes can load for any schema the session can see.
     */
    private static boolean canReadSchemaSegmentSize(String schemaName) {
        return schemaName != null && !schemaName.isBlank();
    }

    /** Missing dictionary object while probing optional Oracle-compatible metadata views. */
    private static boolean isOra942ObjectNotExists(Throwable t) {
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

    private String queryOwnerTableSegmentsTotalSize(Connection conn, String owner) throws SQLException {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        try {
            return queryFormattedSize(conn, SQL_DBA_TABLE_SEGMENTS_SUM_BY_OWNER, List.of(owner));
        } catch (SQLException e) {
            try {
                return queryFormattedSize(conn, SQL_ALL_TABLE_SEGMENTS_SUM_BY_OWNER, List.of(owner));
            } catch (SQLException e2) {
                if (owner.equalsIgnoreCase(sessionUser(conn))) {
                    return queryFormattedSize(conn, SQL_USER_TABLES_SIZE, null);
                }
                return null;
            }
        }
    }

    private String queryOwnerIndexSegmentsTotalSize(Connection conn, String owner) throws SQLException {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        try {
            return queryFormattedSize(conn, SQL_DBA_INDEX_SEGMENTS_SUM_BY_OWNER, List.of(owner));
        } catch (SQLException e) {
            try {
                return queryFormattedSize(conn, SQL_ALL_INDEX_SEGMENTS_SUM_BY_OWNER, List.of(owner));
            } catch (SQLException e2) {
                if (owner.equalsIgnoreCase(sessionUser(conn))) {
                    return queryFormattedSize(conn, SQL_INDEX_SIZE, null);
                }
                return null;
            }
        }
    }

    private String fallbackDatabaseName(Connection conn) {
        try {
            String catalog = conn.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                return catalog;
            }
        } catch (Exception ignored) {
        }
        try {
            String schema = conn.getSchema();
            if (schema != null && !schema.isBlank()) {
                return schema;
            }
        } catch (Exception ignored) {
        }
        return "DAMENG";
    }

    private String resolveOwner(Connection conn, String owner) throws SQLException {
        return owner == null || owner.isBlank() ? currentSchema(conn) : owner;
    }

    /** Schema / database node in the tree (e.g. other user's schema); avoids relying on JDBC {@code getSchema()} alone. */
    private String resolveBrowsedSchemaOwner(Connection conn, String databaseName) throws SQLException {
        if (databaseName != null && !databaseName.isBlank()) {
            return databaseName.trim();
        }
        return currentSchema(conn);
    }

    private String resolveTableOwner(Connection conn, String databaseName, String ownerFromQualifier) throws SQLException {
        if (ownerFromQualifier != null && !ownerFromQualifier.isBlank()) {
            return ownerFromQualifier;
        }
        return resolveBrowsedSchemaOwner(conn, databaseName);
    }

    private void applyCurrentSchema(Connection conn, String databaseName) throws SQLException {
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

    private String queryFormattedSize(Connection conn, String sql, List<Object> params) throws SQLException {
        SqlRunner runner = runner(conn);
        BigDecimal value = runner.queryOne(sql, params, rs -> rs.getBigDecimal(1));
        return formatBytes(value);
    }

    private Catalog mapSchemaDatabase(String schemaName) {
        Catalog database = new Catalog(blankToFallback(schemaName, "DAMENG"));
        database.setDbOwner(database.getName());
        database.setDbCreated("");
        database.setDbSpace("");
        database.setDbLog("");
        database.setDbUseGLU("");
        database.setDbLocale("");
        database.setDbSize("");
        return database;
    }

    private void applyDamengTableLoggingType(Table table, java.sql.ResultSet rs) throws SQLException {
        String log = rs.getString("logging");
        if (log != null && "NO".equalsIgnoreCase(log.trim())) {
            table.setTableTypeCode("nologging");
        } else {
            table.setTableTypeCode("logging");
        }
    }

    private Table mapTable(java.sql.ResultSet rs, String databaseName, boolean includeSize) throws SQLException {
        Table table = new Table(rs.getString("table_name"));
        table.setTableCatalog(databaseName);
        table.setTableOwner(rs.getString("owner"));
        table.setCreateTime(blankToEmpty(rs.getString("created_time")));
        table.setTableComm(blankToEmpty(rs.getString("table_comment")));
        applyDamengTableLoggingType(table, rs);
        table.setLockType("");
        table.setIsfragment(0);
        table.setExtents(0);
        table.setNrows(rs.getInt("num_rows"));
        table.setPagesize(0);
        table.setNptotal(rs.getInt("blocks"));
        table.setTotalsize(includeSize ? formatBytes(rs.getBigDecimal("size_bytes")) : "");
        table.setNpdata(rs.getInt("blocks"));
        table.setUsedsize(includeSize ? formatBytes(rs.getBigDecimal("size_bytes")) : "");
        return table;
    }

    private Index mapIndex(java.sql.ResultSet rs, String databaseName, boolean includeSize) throws SQLException {
        Index index = new Index(rs.getString("index_name"));
        index.setDatabase(databaseName);
        index.setIndexOwner(rs.getString("owner"));
        index.setTableOwner(rs.getString("owner"));
        index.setTabname(rs.getString("table_name"));
        index.setCols(blankToEmpty(rs.getString("index_cols")));
        index.setIdxtype(blankToEmpty(rs.getString("index_type")));
        index.setLevels(String.valueOf(rs.getInt("blevel")));
        index.setUniqvalues(String.valueOf(rs.getLong("distinct_keys")));
        index.setPagesize("");
        index.setTotalpages(String.valueOf(rs.getLong("leaf_blocks")));
        index.setTotalsize(includeSize ? formatBytes(rs.getBigDecimal("size_bytes")) : "");
        index.setIsdisabled(!"VALID".equalsIgnoreCase(rs.getString("status")));
        return index;
    }

    private Sequence mapSequence(java.sql.ResultSet rs, String databaseName) throws SQLException {
        Sequence sequence = new Sequence(rs.getString("sequence_name"));
        sequence.setDatabase(databaseName);
        sequence.setSeqOwner(rs.getString("sequence_owner"));
        sequence.setMinVal(safeToLong(toBigInteger(rs.getBigDecimal("min_value"))));
        sequence.setMaxVal(safeToLong(toBigInteger(rs.getBigDecimal("max_value"))));
        sequence.setIncVal(safeToLong(toBigInteger(rs.getBigDecimal("increment_by"))));
        sequence.setCache(safeToLong(toBigInteger(rs.getBigDecimal("cache_size"))));
        sequence.setNextVal(toBigInteger(rs.getBigDecimal("last_number")));
        sequence.setIsCycle(blankToEmpty(rs.getString("cycle_flag")));
        sequence.setIsOrder(blankToEmpty(rs.getString("order_flag")));
        sequence.setCreated(blankToEmpty(rs.getString("created_time")));
        sequence.setFlags(DAMENG_FLAGS);
        return sequence;
    }

    private Synonym mapSynonym(java.sql.ResultSet rs, String databaseName) throws SQLException {
        Synonym synonym = new Synonym(rs.getString("synonym_name"));
        synonym.setDatabase(databaseName);
        synonym.setSynonymType("PRIVATE");
        synonym.setSynOwner(rs.getString("owner"));
        synonym.setROwner(blankToEmpty(rs.getString("table_owner")));
        synonym.setRTabName(blankToEmpty(rs.getString("table_name")));
        synonym.setRServerName(blankToEmpty(rs.getString("db_link")));
        synonym.setCreated(blankToEmpty(rs.getString("created_time")));
        synonym.setFlags(DAMENG_FLAGS);
        return synonym;
    }

    private Trigger mapTrigger(java.sql.ResultSet rs, String databaseName) throws SQLException {
        Trigger trigger = new Trigger(rs.getString("trigger_name"));
        trigger.setDatabase(databaseName);
        trigger.setTableName(blankToEmpty(rs.getString("table_name")));
        trigger.setTriggerType(blankToEmpty(rs.getString("trigger_type")));
        trigger.setIsdisabled(!"ENABLED".equalsIgnoreCase(rs.getString("status")));
        trigger.setTriggerMode("O");
        return trigger;
    }

    private String formatBytes(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return ZERO_SIZE;
        }
        double bytes = value.doubleValue();
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int unitIndex = 0;
        while (bytes >= 1024 && unitIndex < units.length - 1) {
            bytes /= 1024.0;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return value.toBigInteger().toString() + units[unitIndex];
        }
        if (bytes >= 100) {
            return String.format(Locale.ROOT, "%.0f%s", bytes, units[unitIndex]);
        }
        if (bytes >= 10) {
            return String.format(Locale.ROOT, "%.1f%s", bytes, units[unitIndex]);
        }
        return String.format(Locale.ROOT, "%.2f%s", bytes, units[unitIndex]);
    }

    private BigInteger toBigInteger(BigDecimal value) {
        return value == null ? BigInteger.ZERO : value.toBigInteger();
    }

    private long safeToLong(BigInteger value) {
        if (value == null) {
            return 0L;
        }
        if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    /** Whitespace cleanup previously done in SQL; kept for LONG defaults read via JDBC. */
    private static String normalizeDamengColumnDefault(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.replace("\r", " ").replace("\n", " ").trim();
        return t.isEmpty() ? "" : t;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToFallback(String value, String fallback) {
        String normalized = blankToEmpty(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private QualifiedObjectName parseObjectName(String rawName) {
        if (rawName == null) {
            return new QualifiedObjectName("", "");
        }
        boolean inQuotes = false;
        int splitIndex = -1;
        for (int i = 0; i < rawName.length(); i++) {
            char current = rawName.charAt(i);
            if (current == '"') {
                inQuotes = !inQuotes;
            } else if (current == '.' && !inQuotes) {
                splitIndex = i;
            }
        }
        if (splitIndex < 0) {
            return new QualifiedObjectName("", normalizeIdentifier(rawName));
        }
        String owner = normalizeIdentifier(rawName.substring(0, splitIndex));
        String objectName = normalizeIdentifier(rawName.substring(splitIndex + 1));
        return new QualifiedObjectName(owner, objectName);
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String normalized = identifier.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("`") && normalized.endsWith("`") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.trim();
    }

    private record QualifiedObjectName(String owner, String objectName) {
    }
}
