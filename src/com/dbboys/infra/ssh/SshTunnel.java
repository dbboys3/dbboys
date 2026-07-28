package com.dbboys.infra.ssh;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;

/**
 * Holds an SSH client session and the local port-forwarding tracker
 * with its auto-assigned local port.
 */
public class SshTunnel implements AutoCloseable {
    private final ClientSession session;
    private final ExplicitPortForwardingTracker tracker;
    private final int localPort;
    private final boolean shared;

    public SshTunnel(ClientSession session, ExplicitPortForwardingTracker tracker) {
        this(session, tracker, false);
    }

    public SshTunnel(ClientSession session, ExplicitPortForwardingTracker tracker, boolean shared) {
        this.session = session;
        this.tracker = tracker;
        this.localPort = tracker.getBoundAddress().getPort();
        this.shared = shared;
    }

    public ClientSession getSession() {
        return session;
    }

    public int getLocalPort() {
        return localPort;
    }

    @Override
    public void close() {
        if (tracker != null && tracker.isOpen()) {
            try {
                tracker.close();
            } catch (Exception ignored) {
            }
        }
        if (!shared && session != null && session.isOpen()) {
            session.close(false);
        }
    }
}
