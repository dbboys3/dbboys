package com.dbboys.infra.util;

import com.dbboys.model.SshConnect;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;

/**
 * Utility for creating and managing SSH port-forwarding tunnels.
 */
public final class SshTunnelUtil {
    private static final Logger log = LogManager.getLogger(SshTunnelUtil.class);

    private static final long CONNECT_TIMEOUT_MS = 10000;

    private SshTunnelUtil() {}

    /**
     * Create an SSH tunnel for testing with SshConnect (supports password + key auth).
     */
    public static SshTunnel createTunnel(SshConnect sc) throws Exception {
        ClientSession session = SshUtil.getSshSession(sc, CONNECT_TIMEOUT_MS);
        ExplicitPortForwardingTracker tracker = session.createLocalPortForwardingTracker(
                new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress("127.0.0.1", 1));
        int localPort = tracker.getBoundAddress().getPort();
        log.info("SSH tunnel test OK: localhost:{} via {}@{}:{}",
                localPort, sc.getUsername(), sc.getHost(), sc.getPort());

        return new SshTunnel(session, tracker);
    }

    public static SshTunnel createTunnel(String sshHost, int sshPort,
                                          String sshUser, String sshPassword,
                                          String remoteHost, int remotePort) throws Exception {
        ClientSession session = SshUtil.getPasswordSession(sshUser, sshHost, sshPort, sshPassword, CONNECT_TIMEOUT_MS);
        ExplicitPortForwardingTracker tracker = session.createLocalPortForwardingTracker(
                new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress(remoteHost, remotePort));
        int localPort = tracker.getBoundAddress().getPort();
        log.info("SSH tunnel established: localhost:{} -> {}:{} via {}@{}:{}",
                localPort, remoteHost, remotePort, sshUser, sshHost, sshPort);

        return new SshTunnel(session, tracker);
    }

    /**
     * Close an SSH tunnel, disconnecting the underlying session.
     */
    public static void closeTunnel(SshTunnel tunnel) {
        if (tunnel != null) {
            tunnel.close();
        }
    }
}
