package com.dbboys.infra.util;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Utility for creating and managing SSH port-forwarding tunnels.
 */
public final class SshTunnelUtil {
    private static final Logger log = LogManager.getLogger(SshTunnelUtil.class);

    private SshTunnelUtil() {}

    /**
     * Create an SSH tunnel that forwards a local port to {@code remoteHost:remotePort}.
     *
     * @param sshHost     SSH server hostname or IP
     * @param sshPort     SSH server port
     * @param sshUser     SSH username
     * @param sshPassword SSH password
     * @param remoteHost  target database host (from the SSH server's perspective)
     * @param remotePort  target database port
     * @return an {@link SshTunnel} containing the session and the auto-assigned local port
     * @throws Exception if the SSH connection or port forwarding fails
     */
    public static SshTunnel createTunnel(String sshHost, int sshPort,
                                          String sshUser, String sshPassword,
                                          String remoteHost, int remotePort) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(sshUser, sshHost, sshPort);
        session.setPassword(sshPassword);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(10000); // 10-second timeout

        int localPort = session.setPortForwardingL(0, remoteHost, remotePort);
        log.info("SSH tunnel established: localhost:{} -> {}:{} via {}@{}:{}",
                localPort, remoteHost, remotePort, sshUser, sshHost, sshPort);

        return new SshTunnel(session, localPort);
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
