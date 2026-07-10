package com.dbboys.remote;

public final class RemoteDatabaseProviders {
    private static final RemoteDatabaseProvider GBASE_8S = new Gbase8sRemoteProvider();
    private static final RemoteDatabaseProvider INFORMIX = new InformixRemoteProvider();
    private static final RemoteDatabaseProvider MYSQL = new MysqlRemoteProvider();
    private static final RemoteDatabaseProvider ORACLE = new OracleRemoteProvider();

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
}
