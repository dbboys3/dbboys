package com.dbboys.dialect.informix;

import com.dbboys.dialect.common.InformixFamilyMetadataRepository;
import com.dbboys.model.ColumnsInfo;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * Informix metadata repository. All shared logic lives in {@link InformixFamilyMetadataRepository};
 * only Informix-specific SQL text remains here.
 */
public class InformixMetadataRepository extends InformixFamilyMetadataRepository {

    @Override
    protected String systemOwnerName() {
        return "informix";
    }

    @Override
    protected String idxFirstChar(String column) {
        return column + "[1,1]";
    }

    @Override
    protected String sqlTableComment() {
        return "";
    }

    @Override
    public ArrayList<ColumnsInfo> getColumns(Connection conn, String tableName) throws SQLException {
        ArrayList<ColumnsInfo> columns = InformixDdlRepository.getColInfo(conn, tableName);
        applyPrimaryKeyFlags(conn, tableName, columns);
        return columns;
    }
}
