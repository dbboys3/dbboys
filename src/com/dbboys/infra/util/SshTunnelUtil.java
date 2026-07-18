package com.dbboys.infra.util;

import com.dbboys.model.SshConnect;
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
     * Create an SSH tunnel for testing with SshConnect (supports password + key auth).
     */
    public static SshTunnel createTunnel(SshConnect sc) throws Exception {
        JSch jsch = new JSch();
        int port;
        try {
            port = Integer.parseInt(sc.getPort());
        } catch (NumberFormatException e) {
            port = 22;
        }
        Session session = jsch.getSession(sc.getUsername(), sc.getHost(), port);
        if (sc.isAuthKey()) {
            if (sc.getKeyPassphrase() != null && !sc.getKeyPassphrase().isBlank()) {
                jsch.addIdentity(sc.getKeyPath(), sc.getKeyPassphrase());
            } else {
                jsch.addIdentity(sc.getKeyPath());
            }
        } else {
            session.setPassword(sc.getPassword());
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(10000);

        int localPort = session.setPortForwardingL(0, "127.0.0.1", 1);
        log.info("SSH tunnel test OK: localhost:{} via {}@{}:{}",
                localPort, sc.getUsername(), sc.getHost(), port);

        return new SshTunnel(session, localPort);
    }

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
