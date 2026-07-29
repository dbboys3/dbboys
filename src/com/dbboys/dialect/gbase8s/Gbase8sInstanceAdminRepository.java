package com.dbboys.dialect.gbase8s;

import com.dbboys.dialect.common.InformixFamilyInstanceAdminRepository;

import java.sql.Connection;

/**
 * GBase 8s instance-admin repository. Shared logic lives in {@link InformixFamilyInstanceAdminRepository};
 * only the GBase-fixed SQL text remains here.
 */
public final class Gbase8sInstanceAdminRepository extends InformixFamilyInstanceAdminRepository {

    private static final String SQL_DBSPACE_USAGE = """
                SELECT
                  A.dbsnum as No,
                  case when is_temp==1 then '[T]' else '' end
                  ||
                  case when is_sbspace==1 then '[S]'
                  when is_blobspace==1 then '[B]'
                  else '' end||
                  case when max_size>0 then '[L]' else '' end||       
                  trim(B.name)||'['||round(A.pagesize/1024)||'k]' as label,trim(B.name) as name,
                  sum(is_extendable),
                  round(sum(case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024,2)  as data_SIZE ,
                  round(sum(case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024,2) as dataused_size,
                  sum(e.extents),
                  round(sum(decode(mdsize,-1,0,mdsize))*2/1024/1024,2)  as Meta_SIZE ,
                  round(sum(decode(mdsize,-1,0,mdsize))*2/1024/1024-sum(decode(mdsize,-1,0,nfree))*2/1024/1024,2) as metaused_size,
                  max(max_size)/1024
                  FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                  left join (select chunk,count(*) as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                  group by 1,2,3
                  order by 1
                """;

    private static final String SQL_CHUNK_USAGE = """
                SELECT A.dbsnum as No,A.chknum,
                trim(fname)||' [ '||trim(B.name)||' ] ' as label,trim(fname) as filename,
                is_extendable,
                round((case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024,2)  as data_SIZE ,
                round((case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024,2) as dataused_size,
                e.extents,chksize,
                chksize -nfree,
                round((decode(mdsize,-1,0,mdsize))*2/1024/1024,2)  as Meta_SIZE ,
                round((decode(mdsize,-1,0,mdsize))*2/1024/1024-(decode(mdsize,-1,0,nfree))*2/1024/1024,2) as metaused_size
                FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                left join (select chunk,count(*)-1 as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                order by 1,2
                """;

    private static final String SQL_MAX_SPACE_USAGE = """
                SELECT first 1
                  trim(B.name) as name,
                  sum(is_extendable),
                  case when
                  round(
                  (sum(case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024)
                           / (sum(case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024)*100,2)
                  >
                  round(sum(decode(mdsize,-1,0,mdsize-nfree))/sum(decode(mdsize,-1,1,mdsize))*100,2)
                  then
                  round(
                  (sum(case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024)
                           / (sum(case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024)*100,2)
                  else round(sum(decode(mdsize,-1,0,mdsize-nfree))/sum(decode(mdsize,-1,1,mdsize))*100,2)
                  end
                  as percent
                  ,sum(e.extents)
                  FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                  left join (select chunk,count(*) as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                  group by 1
                  having sum(is_extendable) =0
                  and sum(e.extents)>0
                  order by percent desc;
                """;

    @Override
    protected String systemOwnerName() {
        return "gbasedbt";
    }

    @Override
    protected String[] sqlStorageSpaceUsage(Connection conn) {
        return new String[]{SQL_DBSPACE_USAGE, SQL_CHUNK_USAGE};
    }

    @Override
    protected String sqlMaxSpaceUsage(Connection conn) {
        return SQL_MAX_SPACE_USAGE;
    }
}
