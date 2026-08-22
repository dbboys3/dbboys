package com.dbboys.dialect.postgresql;

public final class PostgresqlRemoteFields {
    public static final String PG_PASSWORD = "pg_password";
    public static final String PG_DATADIR = "pg_datadir";
    public static final String PG_PORT = "pg_port";
    public static final String PG_LISTEN_ADDRESSES = "pg_listen_addresses";
    public static final String PG_ENCODING = "pg_encoding";
    public static final String PG_SHARED_BUFFERS = "pg_shared_buffers";
    /** Detected PostgreSQL bin directory on the remote host (set during install). */
    public static final String PG_BINDIR = "pg_bindir";

    public static final String LOGIN_USERNAME = "postgres";

    private PostgresqlRemoteFields() {
    }
}
