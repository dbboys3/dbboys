package com.dbboys.dialect.informix;

import com.dbboys.dialect.common.InformixFamilyInstanceAdminRepository;

import java.sql.Connection;

/**
 * Informix instance-admin repository. Shared logic lives in {@link InformixFamilyInstanceAdminRepository};
 * only the version-dependent SQL construction remains here (Informix 11.50 lacks syschunks.max_size).
 */
public final class InformixInstanceAdminRepository extends InformixFamilyInstanceAdminRepository {

    @Override
    protected String systemOwnerName() {
        return "informix";
    }

    @Override
    protected String[] sqlStorageSpaceUsage(Connection conn) {
        int dbVersion = InformixDdlRepository.getDataBaseProductVersionNumber(conn);
        // Informix 11.50 does not have max_size column in sysmaster:syschunks.
        // Default to supporting max_size when version cannot be determined.
        boolean isLegacy = dbVersion > 0 && dbVersion <= 1170;

        // --- build dbspace-list SQL ---
        String maxSizeCase = isLegacy ? "" : "case when max_size>0 then '[L]' else '' end||\n";
        String maxSizeCol = isLegacy ? "0 as max_size" : "max(max_size)/1024 as max_size";
        String extendableCol = isLegacy ? "0 as is_extendable" : "sum(is_extendable) as is_extendable";
        String dbspaceSql = """
                SELECT
                  A.dbsnum as No,
                  case when is_temp==1 then '[T]' else '' end
                  ||
                  case when is_sbspace==1 then '[S]'
                  when is_blobspace==1 then '[B]'
                  else '' end||
                  %s
                  trim(B.name)||'['||round(A.pagesize/1024)||'k]' as label,trim(B.name) as name,
                  %s,
                  round(sum(case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024,2)  as data_SIZE ,
                  round(sum(case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024,2) as dataused_size,
                  sum(e.extents),
                  round(sum(decode(mdsize,-1,0,mdsize))*2/1024/1024,2)  as Meta_SIZE ,
                  round(sum(decode(mdsize,-1,0,mdsize))*2/1024/1024-sum(decode(mdsize,-1,0,nfree))*2/1024/1024,2) as metaused_size,
                  %s
                  FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                  left join (select chunk,count(*) as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                  group by 1,2,3
                  order by 1
                """.formatted(maxSizeCase, extendableCol, maxSizeCol);

        // --- build chunk-list SQL ---
        String chunkExtendableCol = isLegacy ? "0 as is_extendable" : "is_extendable";
        String chunkSql = """
                SELECT A.dbsnum as No,A.chknum,
                trim(fname)||' [ '||trim(B.name)||' ] ' as label,trim(fname) as filename,
                %s,
                round((case when is_sbchunk==1 then udsize else chksize end)*2/1024/1024,2)  as data_SIZE ,
                round((case when is_sbchunk==1 then udsize-udfree when is_blobchunk==1 then chksize-nfree*a.pagesize/2048  else chksize-nfree end)*2/1024/1024,2) as dataused_size,
                e.extents,chksize,
                chksize -nfree,
                round((decode(mdsize,-1,0,mdsize))*2/1024/1024,2)  as Meta_SIZE ,
                round((decode(mdsize,-1,0,mdsize))*2/1024/1024-(decode(mdsize,-1,0,nfree))*2/1024/1024,2) as metaused_size
                FROM sysmaster:syschunks A join sysmaster:sysdbspaces B on A.dbsnum = B.dbsnum
                left join (select chunk,count(*)-1 as extents from sysmaster:sysextents where tabname!='TBLSpace' group by chunk) e on E.chunk=A.chknum
                order by 1,2
                """.formatted(chunkExtendableCol);
        return new String[]{dbspaceSql, chunkSql};
    }

    @Override
    protected String sqlMaxSpaceUsage(Connection conn) {
        int dbVersion = InformixDdlRepository.getDataBaseProductVersionNumber(conn);
        boolean isLegacy = dbVersion > 0 && dbVersion <= 1170;
        String extendableCol = isLegacy ? "0" : "sum(is_extendable)";
        String havingClause = isLegacy
                ? "having 0=0 and sum(e.extents)>0"
                : "having sum(is_extendable)=0 and sum(e.extents)>0";
        String sql = """
                SELECT first 1
                  trim(B.name) as name,
                  %s,
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
                  %s
                  order by percent desc;
                """.formatted(extendableCol, havingClause);
        return sql;
    }
}
