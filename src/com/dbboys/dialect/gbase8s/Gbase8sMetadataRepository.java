package com.dbboys.dialect.gbase8s;

import com.dbboys.dialect.common.InformixFamilyMetadataRepository;
import com.dbboys.infra.db.SqlRunner;
import com.dbboys.model.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * GBase 8s metadata repository. All shared logic lives in {@link InformixFamilyMetadataRepository};
 * only GBase-specific SQL text and capability overrides remain here.
 */
public class Gbase8sMetadataRepository extends InformixFamilyMetadataRepository {

    private static final String SQL_TABLE_COMMENT = """
            select max(c.comments)
            from systables t
            left join syscomments c on t.tabname = c.tabname
            where t.tabtype in ('T','E') and t.tabname=?
            """;

    private static final String SQL_XTDTYPE_COUNT = """
            select count(*) from type$
            """;

    private static final String SQL_XTDTYPES = """
            select %s, trim(typ_name) as type_name
            from type$
            """;

    @Override
    protected String systemOwnerName() {
        return "gbasedbt";
    }

    @Override
    protected String idxFirstChar(String column) {
        return "LEFT(" + column + ",1)";
    }

    @Override
    protected String sqlTableComment() {
        return SQL_TABLE_COMMENT;
    }

    @Override
    protected String postSetDatabaseStatement() {
        return "set environment sqlmode 'gbase'";
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        return Gbase8sDdlRepository.getColInfo(conn, tableName);
    }

    @Override
    public int getObjectTypeCount(Connection conn, String databaseName) throws SQLException {
        try {
            SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
            Integer value = runner.queryOne(SQL_XTDTYPE_COUNT, null, rs -> rs.getInt(1));
            return value == null ? 0 : value;
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public List<Type> getObjectTypes(Connection conn, String databaseName) throws SQLException {
        try {
            SqlRunner runner = new SqlRunner(conn, DEFAULT_QUERY_TIMEOUT_SECONDS);
            String dbLit = toSqlStringLiteral(databaseName);
            String sql = SQL_XTDTYPES.formatted(dbLit);
            return runner.query(sql, null, rs -> {
                Type row = new Type(rs.getString(2));
                row.setDatabase(rs.getString(1));
                //row.setTypeKind(gbaseXtdTypeKind(rs.getInt(3)));
                return row;
            });
        } catch (SQLException e) {
            return List.of();
        }
    }

    @Override
    public int getQueueCount(Connection conn, String databaseName) throws SQLException {
        return 0;
    }

    @Override
    public List<Queue> getQueues(Connection conn, String databaseName) throws SQLException {
        return List.of();
    }
}
