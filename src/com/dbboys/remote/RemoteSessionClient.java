package com.dbboys.remote;

import com.dbboys.infra.ssh.SshUtil;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;

import java.io.IOException;

public final class RemoteSessionClient {
    private ClientSession session;

    public synchronized void connect(String username, String host, int port, String password, int timeoutMs) throws Exception {
        disconnect();
        session = SshUtil.getPasswordSession(username, host, port, password, timeoutMs);
    }

    public synchronized boolean isConnected() {
        return session != null && session.isOpen();
    }

    public synchronized void disconnect() {
        if (session != null) {
            session.close(false);
            session = null;
        }
    }

    public synchronized String executeCommand(String command) throws IOException {
        return SshUtil.executeCommand(requireConnectedSession(), command);
    }

    public synchronized int executeCommandWithExitStatus(String command) throws IOException {
        return SshUtil.executeCommandWithExitStatus(requireConnectedSession(), command);
    }

    public synchronized SftpClient openSftpClient() throws IOException {
        return SshUtil.createSftpClient(requireConnectedSession());
    }

    private ClientSession requireConnectedSession() throws IOException {
        if (!isConnected()) {
            throw new IOException("SSH session not connected");
        }
        return session;
    }
}
