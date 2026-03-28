package com.dbboys.impl.dialect.gbase;

import com.dbboys.i18n.I18n;
import com.dbboys.db.SqlRunner;
import com.dbboys.util.SqlParserUtil;
import com.dbboys.vo.*;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class GbaseMetadataRepository implements com.dbboys.api.MetadataRepository {
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private static final String SQL_DATABASES_GBASE = """
            select t1.*,t2.allocedsize from(
            SELECT trim(name) dbname,
            trim(owner) owner,
            to_char(created,'YYYY-MM-DD')  created_time,
            TRIM(DBINFO('dbspace',partnum)) AS dbspace,
            CASE WHEN is_logging+is_buff_log=1 THEN 'unbuffered'
                 WHEN is_logging+is_buff_log=2 THEN 'buffered'
                 WHEN is_logging+is_buff_log=0 THEN 'nolog'
            ELSE '' END Logging_mode,
            is_nls,
            trim(replace(replace(dbs_collate,'57372','UTF8'),'5488','GB18030-2000'))
            FROM sysmaster:sysdatabases d,sysmaster:sysdbslocale s where d.name=s.dbs_dbsname
            order by
            case when name in ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1','gbasedbt','sys')
            then 0 else 1 end,name
            ) t1
            left join
            (
            SELECT
            trim(st.dbsname) dbname,
            replace(format_units(sum(sin.ti_nptotal*sd.pagesize),'b'),' ','') allocedsize
            from
            sysmaster:systabnames st JOIN sysmaster:systabinfo sin ON  st.partnum=sin.ti_partnum
            JOIN sysmaster:sysdbspaces sd ON sd.dbsnum = trunc(st.partnum/1048576)
            GROUP BY 1
            )t2
            on t1.dbname=t2.dbname
            """;

    private static final String SQL_DATABASES_ORACLE = """
            select t1.*,t2.allocedsize from(
            SELECT trim(name) dbname,
            trim(owner) owner,
            to_char(created,'YYYY-MM-DD')  created_time,
            TRIM(DBINFO('dbspace',partnum)) AS dbspace,
            CASE WHEN is_logging+is_buff_log=1 THEN 'unbuffered'
                 WHEN is_logging+is_buff_log=2 THEN 'buffered'
                 WHEN is_logging+is_buff_log=0 THEN 'nolog'
            ELSE '' END Logging_mode,
            is_nls,
            trim(replace(replace(dbs_collate,'57372','UTF8'),'5488','GB18030-2000'))
            FROM sysmaster.sysdatabases d,sysmaster.sysdbslocale s where d.name=s.dbs_dbsname
            order by
            case when name in ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1','gbasedbt','sys')
            then 0 else 1 end,name
            ) t1
            left join
            (
            SELECT
            trim(st.dbsname) dbname,
            replace(format_units(sum(sin.ti_nptotal*sd.pagesize),'b'),' ','') allocedsize
            from
            sysmaster.systabnames st JOIN sysmaster.systabinfo sin ON  st.partnum=sin.ti_partnum
            JOIN sysmaster.sysdbspaces sd ON sd.dbsnum = trunc(st.partnum/1048576)
            GROUP BY 1
            )t2
            on t1.dbname=t2.dbname
            """;

    private static final String SQL_DATABASE_INFO = """
            select t1.*,t2.allocedsize from(
            SELECT trim(name) dbname,
            trim(owner) owner,
            to_char(created,'YYYY-MM-DD')  created_time,
            TRIM(DBINFO('dbspace',partnum)) AS dbspace,
            CASE WHEN is_logging+is_buff_log=1 THEN 'unbuffered'
                 WHEN is_logging+is_buff_log=2 THEN 'buffered'
                 WHEN is_logging+is_buff_log=0 THEN 'nolog'
            ELSE '' END Logging_mode,
            is_nls,
            trim(replace(replace(dbs_collate,'57372','UTF8'),'5488','GB18030-2000'))
            FROM sysmaster:sysdatabases d,sysmaster:sysdbslocale s where d.name=s.dbs_dbsname
            and trim(name)  =?
            ) t1
            left join
            (
            SELECT
            trim(st.dbsname) dbname,
            replace(format_units(sum(sin.ti_nptotal*sin.ti_pagesize),'b'),' ','') allocedsize
            from
            sysmaster:systabnames st JOIN sysmaster:systabinfo sin ON  st.partnum=sin.ti_partnum
            where st.dbsname=?
            GROUP BY 1
            )t2
            on t1.dbname=t2.dbname
            """;

    private static final String SQL_USERS = """
            select username from sysuser:sysusermap where username!='public';
            """;

    private static final String SQL_DBSPACE_FOR_CREATE_DATABASE = """
            SELECT name,pgsize,
            CASE when extendablechunks >0 THEN 'autoextendable' ELSE free_size||'GB Free' END AS freesize

            from(
            SELECT trim(B.name) as name,
            CASE  WHEN (sysmaster:bitval(B.flags,'0x10')>0 AND sysmaster:bitval(B.flags,'0x2')>0)
              THEN 'MirroredBlobspace'
              WHEN sysmaster:bitval(B.flags,'0x10')>0  THEN 'Blobspace'
              WHEN sysmaster:bitval(B.flags,'0x2000')>0 AND sysmaster:bitval(B.flags,'0x8000')>0
              THEN 'TempSbspace'
              WHEN sysmaster:bitval(B.flags,'0x2000')>0 THEN 'TempDbspace'
              WHEN (sysmaster:bitval(B.flags,'0x8000')>0 AND sysmaster:bitval(B.flags,'0x2')>0)
              THEN 'MirroredSbspace'
              WHEN sysmaster:bitval(B.flags,'0x8000')>0  THEN 'SmartBlobspace'
              WHEN sysmaster:bitval(B.flags,'0x2')>0    THEN 'MirroredDbspace'
                    ELSE   'Dbspace'
            END  as dbstype,
             round(sum(decode(mdsize,-1,nfree,udfree))*2/1024/1024,2) as free_size,
              TRUNC(MAX(A.pagesize/1024))||"K Page," as pgsize,
              sum(is_extendable) extendablechunks
            FROM sysmaster:syschunks A, sysmaster:sysdbstab B
            WHERE A.dbsnum = B.dbsnum
             GROUP BY name, 2
            ORDER BY extendablechunks DESC,free_size DESC)
            WHERE dbstype='Dbspace'
            """;

    private static final String SQL_USER_TABLES_COUNT = """
            select count(*) from systables where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION')  and tabtype='T'
            """;

    private static final String SQL_USER_TABLES_SIZE = """
            select replace(format_units(sum(ti_nptotal*ti_pagesize),'b'),' ','')
            from systables s left join sysmaster:systabnames n on s.tabname=trim(n.tabname)
            left join sysmaster:systabinfo i on i.ti_partnum=n.partnum
            where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION')  and n.dbsname=?
            """;

    private static final String SQL_SYSTEM_TABLES_COUNT = """
            select count(*) from systables where tabid<=(SELECT tabid FROM systables WHERE tabname = ' VERSION')
            """;

    private static final String SQL_SYSTEM_TABLES_SIZE = """
            select replace(format_units(sum(ti_nptotal*ti_pagesize),'b'),' ','')
            from systables s left join sysmaster:systabnames n on s.tabname=trim(n.tabname)
            left join sysmaster:systabinfo i on i.ti_partnum=n.partnum
            where tabid<=(SELECT tabid FROM systables WHERE tabname = ' VERSION') and n.dbsname=?
            """;

    private static final String SQL_SYSTEM_TABLES = """
            select ?,dt.tabname,max(dt.owner),max(to_char(dt.created,'YYYY-MM-DD')),max(case when dt.tabtype=='V' then 'view' else 'table' end ),max(dt.locklevel) lock_level,
            max(case when dt.partnum==0 then 1 else 0 end) isfragment,sum(ti_nextns) extents,
            sum(sin.ti_nrows) nrows,max(sin.ti_pagesize) pagesize, sum(sin.ti_nptotal) nptotal, nvl(replace(format_units(sum(sin.ti_nptotal*sin.ti_pagesize),'b'),' ','') ,'0.000B')  total_size,
            sum(sin.ti_npdata) npused,nvl(replace(format_units(sum(sin.ti_npdata*sin.ti_pagesize),'b'),' ',''),'0.000B') used_size
            from systables dt left join sysmaster:systabnames st
            on trim(dt.tabname)=trim(st.tabname) and st.dbsname=?
            left join sysmaster:systabinfo sin on st.partnum=sin.ti_partnum
            where  dt.tabid<=(SELECT tabid FROM systables WHERE tabname = ' VERSION')
            group by 1,2
            order by  2
            """;

    private static final String SQL_USER_TABLES = """
            select ?,tabname,max(owner),max(createtime),max(tabtype),max(locklevel),max(isfragment),
            sum(ti_nextns) extents,
            sum(ti_nrows) nrows,max(ti_pagesize) pagesize, sum(ti_nptotal) nptotal,  replace(format_units(sum(ti_nptotal*ti_pagesize),'b'),' ','')  total_size,
            sum(ti_npdata) npused,replace(format_units(sum(ti_npdata*ti_pagesize),'b'),' ','')
             from
            (select tabname,owner,to_char(created,'YYYY-MM-DD') createtime,
            case when t.flags==16 then 'raw' when t.flags==32 then 'external' else 'standard' end tabtype,
            locklevel,case when t.partnum==0 then 1 else 0 end isfragment,
            case when t.partnum=0 then f.partn else t.partnum end  as partnum
            from
            systables t left join sysfragments f on t.tabid=f.tabid
            where t.tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION') and tabtype in ('T','E')
            ) t
            left join sysmaster:systabinfo i on i.ti_partnum=partnum
            group by 1,2
            order by  2
            """;

    private static final String SQL_TABLE_DETAIL = """
            select ?,max(tabname),max(owner),max(createtime),max(tabtype),max(locklevel),max(isfragment),
            sum(ti_nextns) extents,
            sum(ti_nrows) nrows,max(ti_pagesize) pagesize, sum(ti_nptotal) nptotal,  replace(format_units(sum(ti_nptotal*ti_pagesize),'b'),' ','')  total_size,
            sum(ti_npdata) npused,replace(format_units(sum(ti_npdata*ti_pagesize),'b'),' ','')
             from
            (select tabname,owner,to_char(created,'YYYY-MM-DD') createtime,
            case when t.flags==16 then 'raw' when t.flags==32 then 'external' else 'standard' end tabtype,
            locklevel,case when t.partnum==0 then 1 else 0 end isfragment,
            case when t.partnum=0 then f.partn else t.partnum end  as partnum
            from
            systables t left join sysfragments f on t.tabid=f.tabid
            where tabname=?) t
            join sysmaster:systabinfo i on i.ti_partnum=partnum
            """;

    private static final String SQL_TABLE_COMMENT = """
            select max(c.comments)
            from systables t
            left join syscomments c on t.tabname = c.tabname
            where t.tabtype in ('T','E') and t.tabname=?
            """;

    private static final String SQL_INDEXES = """
            select ?, i.idxname, t.tabname,
            trim( case when i.part1 > 0 then( select colname from syscolumns where colno = i.part1 and tabid = i.tabid ) else '' end )
            || trim( case when i.part2 > 0 then( select ',' || colname from syscolumns where colno = i.part2 and tabid = i.tabid ) else '' end )
            || trim( case when i.part3 > 0 then( select ',' || colname from syscolumns where colno = i.part3 and tabid = i.tabid ) else '' end )
            || trim( case when i.part4 > 0 then( select ',' || colname from syscolumns where colno = i.part4 and tabid = i.tabid ) else '' end )
            || trim( case when i.part5 > 0 then( select ',' || colname from syscolumns where colno = i.part5 and tabid = i.tabid ) else '' end )
            || trim( case when i.part6 > 0 then( select ',' || colname from syscolumns where colno = i.part6 and tabid = i.tabid ) else '' end )
            || trim( case when i.part7 > 0 then( select ',' || colname from syscolumns where colno = i.part7 and tabid = i.tabid ) else '' end )
            || trim( case when i.part8 > 0 then( select ',' || colname from syscolumns where colno = i.part8 and tabid = i.tabid ) else '' end )
            || trim( case when i.part9 > 0 then( select ',' || colname from syscolumns where colno = i.part9 and tabid = i.tabid ) else '' end )
            || trim( case when i.part10 > 0 then( select ',' || colname from syscolumns where colno = i.part10 and tabid = i.tabid ) else '' end )
            || trim( case when i.part11 > 0 then( select ',' || colname from syscolumns where colno = i.part11 and tabid = i.tabid ) else '' end )
            || trim( case when i.part12 > 0 then( select ',' || colname from syscolumns where colno = i.part12 and tabid = i.tabid ) else '' end )
            || trim( case when i.part13 > 0 then( select ',' || colname from syscolumns where colno = i.part13 and tabid = i.tabid ) else '' end )
            || trim( case when i.part14 > 0 then( select ',' || colname from syscolumns where colno = i.part14 and tabid = i.tabid ) else '' end )
            || trim( case when i.part15 > 0 then( select ',' || colname from syscolumns where colno = i.part15 and tabid = i.tabid ) else '' end )
            || trim( case when i.part16 > 0 then( select ',' || colname from syscolumns where colno = i.part16 and tabid = i.tabid ) else '' end ) as cols,
            i.idxtype,
            i.levels,
            i.nunique,
            sin.ti_pagesize pagesize,
            sum(sin.ti_nptotal) nptotal,
            replace(format_units(sum(sin.ti_nptotal*sin.ti_pagesize),'b'),' ','')  total_size,
            max(o.state)
            from
            systables t join sysindexes i
            on t.tabid = i.tabid and t.tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION')
            join sysobjstate o on  o.tabid=t.tabid and o.name=i.idxname
            left join sysmaster:systabnames st
            on trim(i.idxname)=trim(st.tabname) and st.dbsname=?
            left join sysmaster:systabinfo sin on st.partnum=sin.ti_partnum
            group by 1,2,3,4,5,6,7,8
            order by 3,4
            """;

    private static final String SQL_INDEX_COUNT = """
            select count(*) from sysindexes i,systables t where i.tabid=t.tabid and t.tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION')
            """;

    private static final String SQL_INDEX_SIZE = """
            select replace(format_units(sum(ti_nptotal*ti_pagesize),'b'),' ','')
            from sysindexes s left join sysmaster:systabnames n on trim(s.idxname)=trim(n.tabname)
            left join sysmaster:systabinfo i on i.ti_partnum=n.partnum
            where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION')
            """;

    private static final String SQL_INDEX = """
            select ?, i.idxname, t.tabname,
            trim( case when i.part1 > 0 then( select colname from syscolumns where colno = i.part1 and tabid = i.tabid ) else '' end )
            || trim( case when i.part2 > 0 then( select ',' || colname from syscolumns where colno = i.part2 and tabid = i.tabid ) else '' end )
            || trim( case when i.part3 > 0 then( select ',' || colname from syscolumns where colno = i.part3 and tabid = i.tabid ) else '' end )
            || trim( case when i.part4 > 0 then( select ',' || colname from syscolumns where colno = i.part4 and tabid = i.tabid ) else '' end )
            || trim( case when i.part5 > 0 then( select ',' || colname from syscolumns where colno = i.part5 and tabid = i.tabid ) else '' end )
            || trim( case when i.part6 > 0 then( select ',' || colname from syscolumns where colno = i.part6 and tabid = i.tabid ) else '' end )
            || trim( case when i.part7 > 0 then( select ',' || colname from syscolumns where colno = i.part7 and tabid = i.tabid ) else '' end )
            || trim( case when i.part8 > 0 then( select ',' || colname from syscolumns where colno = i.part8 and tabid = i.tabid ) else '' end )
            || trim( case when i.part9 > 0 then( select ',' || colname from syscolumns where colno = i.part9 and tabid = i.tabid ) else '' end )
            || trim( case when i.part10 > 0 then( select ',' || colname from syscolumns where colno = i.part10 and tabid = i.tabid ) else '' end )
            || trim( case when i.part11 > 0 then( select ',' || colname from syscolumns where colno = i.part11 and tabid = i.tabid ) else '' end )
            || trim( case when i.part12 > 0 then( select ',' || colname from syscolumns where colno = i.part12 and tabid = i.tabid ) else '' end )
            || trim( case when i.part13 > 0 then( select ',' || colname from syscolumns where colno = i.part13 and tabid = i.tabid ) else '' end )
            || trim( case when i.part14 > 0 then( select ',' || colname from syscolumns where colno = i.part14 and tabid = i.tabid ) else '' end )
            || trim( case when i.part15 > 0 then( select ',' || colname from syscolumns where colno = i.part15 and tabid = i.tabid ) else '' end )
            || trim( case when i.part16 > 0 then( select ',' || colname from syscolumns where colno = i.part16 and tabid = i.tabid ) else '' end ) as cols,
            i.idxtype,
            i.levels,
            i.nunique,
            sin.ti_pagesize pagesize,
            sum(sin.ti_nptotal) nptotal,
            replace(format_units(sum(sin.ti_nptotal*sin.ti_pagesize),'b'),' ','')  total_size,
            max(o.state)
            from
            systables t join sysindexes i
            on t.tabid = i.tabid and i.idxname==?
            join sysobjstate o on  o.tabid=t.tabid and o.name=i.idxname
            left join sysmaster:systabnames st
            on trim(i.idxname)=trim(st.tabname)
            left join sysmaster:systabinfo sin on st.partnum=sin.ti_partnum
            group by 1,2,3,4,5,6,7,8
            order by 3,4
            """;

    private static final String SQL_SEQUENCE_COUNT = """
            select count(*) from systables where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION') and tabtype in('Q')
            """;

    private static final String SQL_SEQUENCES = """
            select ?,tabname as seqname,min_val,max_val,inc_val,cache,cur_serial8,t.created
            from systables t,syssequences q,sysmaster:sysptnhdr p
            where t.tabtype='Q' and t.tabid=q.tabid and t.partnum=p.partnum
            """;

    private static final String SQL_SYNONYM_COUNT = """
            select count(*) from systables where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION') and tabtype in('P','S')
            """;

    private static final String SQL_SYNONYMS = """
            select ?,tabname,case tabtype when 'S' then 'PUBLIC' else 'PRIVATE' end,created
            from systables where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION') and tabtype in ('P','S')
            """;

    private static final String SQL_TRIGGER_COUNT = """
            select count(*) from systriggers
            """;

    private static final String SQL_TRIGGERS = """
            select ?,tabname,trigname,
            case event when 'S' then 'select' when 'D' then 'delete' when 'U' then 'update' when 'I' then 'insert' end,
            s.state
            from systriggers t,sysobjstate s,systables st
            where t.tabid=st.tabid and s.objtype='T' and s.name=t.trigname
            """;

    private static final String SQL_TRIGGER = """
            select ?,tabname,trigname,
            case event when 'S' then 'select' when 'D' then 'delete' when 'U' then 'update' when 'I' then 'insert' end,
            s.state
            from systriggers t,sysobjstate s,systables st
            where t.tabid=st.tabid and s.objtype='T' and s.name=t.trigname and t.trigname=?
            """;

    private static final String SQL_VIEW_COUNT = """
            select count(*) from systables where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION')  and tabtype='V'
            """;

    private static final String SQL_VIEWS = """
            select ?,tabname,owner,to_char(created,'YYYY-MM-DD')
            from systables where tabid>(SELECT tabid FROM systables WHERE tabname = ' VERSION')  and tabtype='V'
            """;

    private static final String SQL_SYS_DUAL_TABID = """
            select tabid from systables where tabname='dual'
            """;

    private static final String SQL_SYS_PROC_HAS_TYPE = """
            select count(*) from systables t,syscolumns c where t.tabid=c.tabid and t.tabname='sysprocedures' and c.colname='type'
            """;

    private static final String SQL_FUNCTION_COUNT = """
            SELECT COUNT(distinct procname) FROM sysprocedures WHERE isproc = 'f' and mode='O'%s
            """;

    private static final String SQL_FUNCTIONS = """
            select distinct ?,procname,owner FROM sysprocedures WHERE isproc = 'f' and mode='O'%s
            """;

    private static final String SQL_PROCEDURE_COUNT = """
            SELECT COUNT(distinct procname ) FROM sysprocedures WHERE isproc = 't' and mode='O'%s
            """;

    private static final String SQL_PROCEDURES = """
            select distinct ?,procname,owner FROM sysprocedures WHERE isproc = 't' and mode='O'%s
            """;

    private static final String SQL_PACKAGE_COUNT = """
            SELECT COUNT(distinct procname) FROM sysprocedures WHERE mode='O' and retsize=0
            """;

    private static final String SQL_PACKAGES = """
            select ?,procname,owner,count(*) FROM sysprocedures WHERE mode='O' and retsize=0 group by 1,2,3 order by 1,2
            """;

    private static final String SQL_PRIMARY_KEY_COLUMNS = """
            select trim(case when i.part1>0 then (select colname from syscolumns where colno=i.part1 and tabid=i.tabid) else '' end)||
            trim(case when i.part2>0 then (select ','||colname from syscolumns where colno=i.part2 and tabid=i.tabid) else '' end)||
            trim(case when i.part3>0 then (select ','||colname from syscolumns where colno=i.part3 and tabid=i.tabid) else '' end)||
            trim(case when i.part4>0 then (select ','||colname from syscolumns where colno=i.part4 and tabid=i.tabid) else '' end)||
            trim(case when i.part5>0 then (select ','||colname from syscolumns where colno=i.part5 and tabid=i.tabid) else '' end)||
            trim(case when i.part6>0 then (select ','||colname from syscolumns where colno=i.part6 and tabid=i.tabid) else '' end)||
            trim(case when i.part7>0 then (select ','||colname from syscolumns where colno=i.part7 and tabid=i.tabid) else '' end)||
            trim(case when i.part8>0 then (select ','||colname from syscolumns where colno=i.part8 and tabid=i.tabid) else '' end)||
            trim(case when i.part9>0 then (select ','||colname from syscolumns where colno=i.part9 and tabid=i.tabid) else '' end)||
            trim(case when i.part10>0 then (select ','||colname from syscolumns where colno=i.part10 and tabid=i.tabid) else '' end)||
            trim(case when i.part11>0 then (select ','||colname from syscolumns where colno=i.part11 and tabid=i.tabid) else '' end)||
            trim(case when i.part12>0 then (select ','||colname from syscolumns where colno=i.part12 and tabid=i.tabid) else '' end)||
            trim(case when i.part13>0 then (select ','||colname from syscolumns where colno=i.part13 and tabid=i.tabid) else '' end)||
            trim(case when i.part14>0 then (select ','||colname from syscolumns where colno=i.part14 and tabid=i.tabid) else '' end)||
            trim(case when i.part15>0 then (select ','||colname from syscolumns where colno=i.part15 and tabid=i.tabid) else '' end)||
            trim(case when i.part16>0 then (select ','||colname from syscolumns where colno=i.part16 and tabid=i.tabid) else '' end)
            from systables t, sysconstraints c, sysindexes i
            where t.tabid=c.tabid
            and t.tabid=i.tabid
            and t.tabtype='T'
            and c.constrtype='P'
            and c.idxname = i.idxname
            and t.tabname=?
            """;

    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        return GbaseDdlRepository.getColInfo(conn, tableName);
    }

    @Override
    public List<String> getPrimaryKeyColumns(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String indexColumns = runner.queryOne(SQL_PRIMARY_KEY_COLUMNS, List.of(normalizeTableLookupName(tableName)), rs -> rs.getString(1));
        if (indexColumns == null || indexColumns.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String column : indexColumns.split(",")) {
            String normalized = normalizeIdentifier(column);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    public List<User> getUsers(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_USERS, null, rs -> new User(rs.getString(1)));
    }

    @Override
    public boolean supportsUsers(Connect connect) {
        return connect != null && "gbasedbt".equalsIgnoreCase(connect.getUsername());
    }

    public List<Database> getDatabases(Connection conn, boolean useOracleSyntax) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String sql = useOracleSyntax ? SQL_DATABASES_ORACLE : SQL_DATABASES_GBASE;
        return runner.query(sql, null, rs -> {
            Database database = new Database(rs.getString(1));
            database.setDbOwner(rs.getString(2));
            database.setDbCreated(rs.getString(3));
            database.setDbSpace(rs.getString(4));
            database.setDbLog(rs.getString(5));
            database.setDbUseGLU(rs.getString(6));
            database.setDbLocale(rs.getString(7));
            database.setDbSize(rs.getString(8));
            return database;
        });
    }

    @Override
    public boolean shouldRetryGetDatabases(SQLException e) {
        return e != null && e.getErrorCode() == -201;
    }

    public Database getDatabaseInfo(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_DATABASE_INFO, List.of(databaseName, databaseName), rs -> {
            Database database = new Database(rs.getString(1));
            database.setDbOwner(rs.getString(2));
            database.setDbCreated(rs.getString(3));
            database.setDbSpace(rs.getString(4));
            database.setDbLog(rs.getString(5));
            database.setDbUseGLU(rs.getString(6));
            database.setDbLocale(rs.getString(7));
            database.setDbSize(rs.getString(8));
            return database;
        });
    }

    public int getUserTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_USER_TABLES_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public String getUserTablesSize(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_USER_TABLES_SIZE, List.of(databaseName), rs -> rs.getString(1));
    }

    public int getSystemTablesCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_SYSTEM_TABLES_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public String getSystemTablesSize(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_SYSTEM_TABLES_SIZE, List.of(databaseName), rs -> rs.getString(1));
    }

    public List<SysTable> getSystemTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_SYSTEM_TABLES, List.of(databaseName, databaseName), rs -> {
            SysTable table = new SysTable(rs.getString(2));
            table.setTableCatalog(rs.getString(1));
            table.setTableOwner(rs.getString(3));
            table.setCreateTime(rs.getString(4));
            table.setTableTypeCode(rs.getString(5));
            table.setLockType(rs.getString(6));
            table.setIsfragment(rs.getInt(7));
            table.setExtents(rs.getInt(8));
            table.setNrows(rs.getInt(9));
            table.setPagesize(rs.getInt(10));
            table.setNptotal(rs.getInt(11));
            table.setTotalsize(rs.getString(12));
            table.setNpdata(rs.getInt(13));
            table.setUsedsize(rs.getString(14));
            return table;
        });
    }

    public List<Table> getUserTables(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_USER_TABLES, List.of(databaseName), rs -> {
            Table table = new Table(rs.getString(2));
            table.setTableCatalog(rs.getString(1));
            table.setTableOwner(rs.getString(3));
            table.setCreateTime(rs.getString(4));
            table.setTableTypeCode(rs.getString(5));
            table.setLockType(rs.getString(6));
            table.setIsfragment(rs.getInt(7));
            table.setExtents(rs.getInt(8));
            table.setNrows(rs.getInt(9));
            table.setPagesize(rs.getInt(10));
            table.setNptotal(rs.getInt(11));
            table.setTotalsize(rs.getString(12));
            table.setNpdata(rs.getInt(13));
            table.setUsedsize(rs.getString(14));
            return table;
        });
    }

    public Table getTable(Connection conn, String databaseName, String tableName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_TABLE_DETAIL, List.of(databaseName, tableName), rs -> {
            Table table = new Table(rs.getString(2));
            table.setTableCatalog(rs.getString(1));
            table.setTableOwner(rs.getString(3));
            table.setCreateTime(rs.getString(4));
            table.setTableTypeCode(rs.getString(5));
            table.setLockType(rs.getString(6));
            table.setIsfragment(rs.getInt(7));
            table.setExtents(rs.getInt(8));
            table.setNrows(rs.getInt(9));
            table.setPagesize(rs.getInt(10));
            table.setNptotal(rs.getInt(11));
            table.setTotalsize(rs.getString(12));
            table.setNpdata(rs.getInt(13));
            table.setUsedsize(rs.getString(14));
            return table;
        });
    }

    public String getTableComment(Connection conn, String tableName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String comment = runner.queryOne(SQL_TABLE_COMMENT, List.of(tableName), rs -> rs.getString(1));
        return comment == null ? "" : comment;
    }

    public List<Index> getIndexes(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_INDEXES, List.of(databaseName, databaseName), rs -> {
            Index index = new Index(rs.getString(2));
            index.setDatabase(rs.getString(1));
            index.setTabname(rs.getString(3));
            index.setCols(rs.getString(4));
            index.setIdxtype(rs.getString(5));
            index.setLevels(rs.getString(6));
            index.setUniqvalues(rs.getString(7));
            index.setPagesize(rs.getString(8));
            index.setTotalpages(rs.getString(9));
            index.setTotalsize(rs.getString(10));
            index.setIsdisabled(rs.getString(11).equals("E") ? false : true);
            return index;
        });
    }

    public int getIndexCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_INDEX_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public String getIndexSize(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_INDEX_SIZE, null, rs -> rs.getString(1));
    }

    public Index getIndex(Connection conn, String databaseName, String indexName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_INDEX, List.of(databaseName, indexName), rs -> {
            Index index = new Index(rs.getString(2));
            index.setDatabase(rs.getString(1));
            index.setTabname(rs.getString(3));
            index.setCols(rs.getString(4));
            index.setIdxtype(rs.getString(5));
            index.setLevels(rs.getString(6));
            index.setUniqvalues(rs.getString(7));
            index.setPagesize(rs.getString(8));
            index.setTotalpages(rs.getString(9));
            index.setTotalsize(rs.getString(10));
            index.setIsdisabled(rs.getString(11).equals("E") ? false : true);
            return index;
        });
    }

    public int getSequenceCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_SEQUENCE_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public List<Sequence> getSequences(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_SEQUENCES, List.of(databaseName), rs -> {
            Sequence sequence = new Sequence(rs.getString(2));
            sequence.setDatabase(rs.getString(1));
            sequence.setMinValue(BigInteger.valueOf(rs.getLong(3)));
            sequence.setMaxValue(BigInteger.valueOf(rs.getLong(4)));
            sequence.setIncValue(BigInteger.valueOf(rs.getLong(5)));
            sequence.setCache(rs.getInt(6));
            sequence.setNextVal(BigInteger.valueOf(rs.getLong(7)));
            sequence.setCreated(rs.getString(8));
            return sequence;
        });
    }

    public List<Sequence> getSequences(Connection conn, Database database) throws SQLException {
        return getSequences(conn, database.getName());
    }

    public int getSynonymCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_SYNONYM_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public List<Synonym> getSynonyms(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_SYNONYMS, List.of(databaseName), rs -> {
            Synonym synonym = new Synonym(rs.getString(2));
            synonym.setDatabase(rs.getString(1));
            synonym.setSynonymType(rs.getString(3));
            synonym.setCreated(rs.getString(4));
            return synonym;
        });
    }

    public int getTriggerCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_TRIGGER_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public List<Trigger> getTriggers(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_TRIGGERS, List.of(databaseName), rs -> {
            Trigger trigger = new Trigger(rs.getString(3));
            trigger.setDatabase(rs.getString(1));
            trigger.setTableName(rs.getString(2));
            trigger.setTriggerType(rs.getString(4));
            trigger.setIsdisabled(rs.getString(5).equals("E") ? false : true);
            return trigger;
        });
    }

    public Trigger getTrigger(Connection conn, String databaseName, String triggerName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(SQL_TRIGGER, List.of(databaseName, triggerName), rs -> {
            Trigger trigger = new Trigger(rs.getString(3));
            trigger.setDatabase(rs.getString(1));
            trigger.setTableName(rs.getString(2));
            trigger.setTriggerType(rs.getString(4));
            trigger.setIsdisabled(rs.getString(5).equals("E") ? false : true);
            return trigger;
        });
    }

    public int getViewCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_VIEW_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public List<View> getViews(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_VIEWS, List.of(databaseName), rs -> {
            View view = new View(rs.getString(2));
            view.setDbname(rs.getString(1));
            view.setOwner(rs.getString(3));
            view.setCreateTime(rs.getString(4));
            return view;
        });
    }

    public int getSystemDualTabId(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_SYS_DUAL_TABID, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public boolean hasSysProcTypeColumn(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_SYS_PROC_HAS_TYPE, null, rs -> rs.getInt(1));
        return value != null && value == 1;
    }

    public int getFunctionCount(Connection conn, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String sql = String.format(SQL_FUNCTION_COUNT, filterType ? " and type==0" : "");
        Integer value = runner.queryOne(sql, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public List<Function> getFunctions(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String sql = String.format(SQL_FUNCTIONS, filterType ? " and type==0" : "");
        return runner.query(sql, List.of(databaseName), rs -> {
            Function function = new Function(rs.getString(2));
            function.setDatabase(rs.getString(1));
            function.setOwner(rs.getString(3));
            return function;
        });
    }

    public int getProcedureCount(Connection conn, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String sql = String.format(SQL_PROCEDURE_COUNT, filterType ? " and type==0" : "");
        Integer value = runner.queryOne(sql, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public List<Procedure> getProcedures(Connection conn, String databaseName, boolean filterType) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        String sql = String.format(SQL_PROCEDURES, filterType ? " and type==0" : "");
        return runner.query(sql, List.of(databaseName), rs -> {
            Procedure procedure = new Procedure(rs.getString(2));
            procedure.setDatabase(rs.getString(1));
            procedure.setOwner(rs.getString(3));
            return procedure;
        });
    }

    public int getPackageCount(Connection conn) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Integer value = runner.queryOne(SQL_PACKAGE_COUNT, null, rs -> rs.getInt(1));
        return value == null ? 0 : value;
    }

    public List<DBPackage> getPackages(Connection conn, String databaseName) throws SQLException {
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_PACKAGES, List.of(databaseName), rs -> {
            DBPackage dbpackage = new DBPackage(rs.getString(2));
            dbpackage.setDatabase(rs.getString(1));
            dbpackage.setOwner(rs.getString(3));
            if (rs.getInt(4) == 1) {
                dbpackage.setIsEmpty(true);
            } else {
                dbpackage.setIsEmpty(false);
            }
            return dbpackage;
        });
    }


    public List<String> getDBspaceForCreateDatabase(Connection conn) throws SQLException {
        //如果连接的上一步操作是切库且报错了，如没有权限，当前连接是没有库的，直接执行会报错，需要先切到sysmaster库
        changeDatabase(conn, "sysmaster");
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(SQL_DBSPACE_FOR_CREATE_DATABASE, null, rs ->
                rs.getString(1) + "(" + rs.getString(2)
                + rs.getString(3)
                        .replace("autoextendable", I18n.t("dbspace.autoextendable", "自动扩展"))
                        .replace("Free", I18n.t("dbspace.free", "可用"))
                + ")"

        );
    }

    public void changeDatabase(Connection conn, String databaseName) throws SQLException {
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("database " + databaseName);
        }
    }

    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        try (java.sql.PreparedStatement pstmt = conn.prepareStatement("database ?")) {
            pstmt.setString(1, databaseName);
            pstmt.executeUpdate();
        }
        try (java.sql.PreparedStatement pstmt = conn.prepareStatement("set environment sqlmode 'gbase'")) {
            pstmt.executeUpdate();
        }
        catch (SQLException e) {
            // ignore
        }
    }

    public List<String> getIndexColumnsForTable(Connection conn, String tableName) throws SQLException {
        String fetchSql = """
                SELECT
                trim( case when i.part1 > 0 then( select colname from syscolumns where colno = i.part1 and tabid = i.tabid ) else '' end )|| trim( case when i.part2 > 0 then( select ',' || colname from syscolumns where colno = i.part2 and tabid = i.tabid ) else '' end )|| trim( case when i.part3 > 0 then( select ',' || colname from syscolumns where colno = i.part3 and tabid = i.tabid ) else '' end )|| trim( case when i.part4 > 0 then( select ',' || colname from syscolumns where colno = i.part4 and tabid = i.tabid ) else '' end )|| trim( case when i.part5 > 0 then( select ',' || colname from syscolumns where colno = i.part5 and tabid = i.tabid ) else '' end )|| trim( case when i.part6 > 0 then( select ',' || colname from syscolumns where colno = i.part6 and tabid = i.tabid ) else '' end )|| trim( case when i.part7 > 0 then( select ',' || colname from syscolumns where colno = i.part7 and tabid = i.tabid ) else '' end )|| trim( case when i.part8 > 0 then( select ',' || colname from syscolumns where colno = i.part8 and tabid = i.tabid ) else '' end )|| trim( case when i.part9 > 0 then( select ',' || colname from syscolumns where colno = i.part9 and tabid = i.tabid ) else '' end )|| trim( case when i.part10 > 0 then( select ',' || colname from syscolumns where colno = i.part10 and tabid = i.tabid ) else '' end )|| trim( case when i.part11 > 0 then( select ',' || colname from syscolumns where colno = i.part11 and tabid = i.tabid ) else '' end )|| trim( case when i.part12 > 0 then( select ',' || colname from syscolumns where colno = i.part12 and tabid = i.tabid ) else '' end )|| trim( case when i.part13 > 0 then( select ',' || colname from syscolumns where colno = i.part13 and tabid = i.tabid ) else '' end )|| trim( case when i.part14 > 0 then( select ',' || colname from syscolumns where colno = i.part14 and tabid = i.tabid ) else '' end )|| trim( case when i.part15 > 0 then( select ',' || colname from syscolumns where colno = i.part15 and tabid = i.tabid ) else '' end )|| trim( case when i.part16 > 0 then( select ',' || colname from syscolumns where colno = i.part16 and tabid = i.tabid ) else '' end )
                from
                systables t  join sysindexes i on
                t.tabid = i.tabid
                where
                t.tabtype = 'T'
                and tabname=?
                """;
        SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.query(fetchSql, List.of(tableName), rs -> rs.getString(1));        
    }

    private static String normalizeTableLookupName(String tableName) {
        if (tableName == null) {
            return "";
        }
        String normalized = tableName.trim();
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < normalized.length() - 1) {
            normalized = normalized.substring(dotIndex + 1);
        }
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.trim();
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier == null ? "" : identifier.trim().replace("\"", "").toLowerCase();
    }
}
