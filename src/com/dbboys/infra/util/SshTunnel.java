package com.dbboys.infra.util;

import com.jcraft.jsch.Session;

/**
 * Holds a JSch SSH session and the auto-assigned local port used for
 * port forwarding.
 */
public class SshTunnel implements AutoCloseable {
    private final Session session;
    private final int localPort;

    public SshTunnel(Session session, int localPort) {
        this.session = session;
        this.localPort = localPort;
    }

    public Session getSession() {
        return session;
    }

    public int getLocalPort() {
        return localPort;
    }

    @Override
    public void close() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}
