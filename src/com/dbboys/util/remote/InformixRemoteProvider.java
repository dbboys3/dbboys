package com.dbboys.util.remote;

public final class InformixRemoteProvider implements RemoteDatabaseProvider {
    @Override
    public String id() {
        return "informix";
    }

    @Override
    public String displayName() {
        return "INFORMIX";
    }
}
