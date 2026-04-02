package com.dbboys.util.remote;

public final class RemoteDatabaseProviders {
    private static final RemoteDatabaseProvider GBASE_8S = new Gbase8sRemoteProvider();
    private static final RemoteDatabaseProvider INFORMIX = new InformixRemoteProvider();

    private RemoteDatabaseProviders() {
    }

    public static RemoteDatabaseProvider gbase8s() {
        return GBASE_8S;
    }

    public static RemoteDatabaseProvider informix() {
        return INFORMIX;
    }
}
