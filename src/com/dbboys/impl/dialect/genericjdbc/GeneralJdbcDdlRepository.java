package com.dbboys.impl.dialect.genericjdbc;

import com.dbboys.api.DdlRepository;

import java.sql.Connection;

public final class GeneralJdbcDdlRepository implements DdlRepository {
    private static final String MSG = "-- General JDBC does not provide built-in metadata DDL extraction.";

    @Override
    public String printTable(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printView(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printIndex(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printSequence(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printSynonym(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printFunction(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printProcedure(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printTrigger(Connection conn, String objectName) {
        return MSG;
    }

    @Override
    public String printPackage(Connection conn, String objectName) {
        return MSG;
    }
}
