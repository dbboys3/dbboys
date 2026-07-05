package com.dbboys.infra.util;

import com.jcraft.jsch.Session;

/**
 * Holds a JSch SSH session and the auto-assigned local port used for
 * port forwarding.
 */
public class SshTunnel implements AutoCloseable {
    private final Session session;
    private final int localPort;
    private final boolean shared;

    public SshTunnel(Session session, int localPort) {
        this(session, localPort, false);
    }

    public SshTunnel(Session session, int localPort, boolean shared) {
        this.session = session;
        this.localPort = localPort;
        this.shared = shared;
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
            if (shared) {
                try {
                    session.delPortForwardingL(localPort);
                } catch (Exception ignored) {
                }
            } else {
                session.disconnect();
            }
        }
    }
}
