package com.dbboys.app;

import com.dbboys.remote.RemoteDatabaseProvider;

public final class RemoteDatabaseProviders {
    private static final RemoteDatabaseProvider GBASE_8S = new com.dbboys.dialect.gbase8s.Gbase8sRemoteProvider();
    private static final RemoteDatabaseProvider INFORMIX = new com.dbboys.dialect.informix.InformixRemoteProvider();
    private static final RemoteDatabaseProvider MYSQL = new com.dbboys.dialect.mysql.MysqlRemoteProvider();
    private static final RemoteDatabaseProvider ORACLE = new com.dbboys.dialect.oracle.OracleRemoteProvider();
    private static final RemoteDatabaseProvider DAMENG = new com.dbboys.dialect.dameng.DamengRemoteProvider();

    private RemoteDatabaseProviders() {
    }

    public static RemoteDatabaseProvider gbase8s() {
        return GBASE_8S;
    }

    public static RemoteDatabaseProvider informix() {
        return INFORMIX;
    }

    public static RemoteDatabaseProvider mysql() {
        return MYSQL;
    }

    public static RemoteDatabaseProvider oracle() {
        return ORACLE;
    }

    public static RemoteDatabaseProvider dameng() {
        return DAMENG;
    }
}
