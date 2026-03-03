package com.dbboys.impl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SqlexeRepositoryImpl implements com.dbboys.api.SqlexeRepository {
    public static final String SQL_SYS_DUAL = "select  * from sysmaster:sysdual";
    public static final String SQLMODE_GBASE = "set environment sqlmode 'gbase'";
    public static final String SQLMODE_MYSQL = "set environment sqlmode 'mysql'";
    public static final String SQLMODE_ORACLE = "set environment sqlmode 'oracle'";
    public static final String SQL_TABNAMES_GROUP = "select tabid,tabname from systables group by 1";

    public void setDatabase(Connection conn, String databaseName) throws SQLException {
        conn.createStatement().executeUpdate("database " + databaseName);
    }


    public List<String> getSqlMode(Connection conn) throws SQLException {
        List<String> sqlmodes = new ArrayList<>();
        ResultSet rs = null;
        java.sql.Statement stmt = null;
        try {
            rs = conn.createStatement().executeQuery(SQL_SYS_DUAL);
            stmt = conn.createStatement();
            stmt.execute(SQLMODE_GBASE);
            sqlmodes.add("sqlmode=gbase");
            try {
                conn.createStatement().executeUpdate(SQLMODE_MYSQL);
                rs = conn.createStatement().executeQuery(SQL_TABNAMES_GROUP);
                conn.createStatement().executeUpdate(SQLMODE_GBASE);
                sqlmodes.add("sqlmode=oracle");
                sqlmodes.add("sqlmode=mysql");
            } catch (SQLException e1) {
                sqlmodes.add("sqlmode=oracle");
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == -201) {
                try {
                    rs = conn.createStatement().executeQuery(SQL_TABNAMES_GROUP);
                    sqlmodes.add("sqlmode=mysql");
                    sqlmodes.add("sqlmode=oracle");
                    sqlmodes.add("sqlmode=mysql");
                } catch (SQLException e1) {
                    sqlmodes.add("sqlmode=oracle");
                    sqlmodes.add("sqlmode=oracle");
                    try {
                        conn.createStatement().executeUpdate(SQLMODE_MYSQL);
                        rs = conn.createStatement().executeQuery(SQL_TABNAMES_GROUP);
                        sqlmodes.add("sqlmode=mysql");
                        conn.createStatement().executeUpdate(SQLMODE_ORACLE);
                    } catch (SQLException e2) {
                        // ignore
                    }
                }
            } else if (e.getErrorCode() == -19840) {
                sqlmodes.add("sqlmode=none");
            } else {
                throw e;
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e1) {
                    // ignore
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e1) {
                    // ignore
                }
            }
        }
        return sqlmodes;
    }
}
