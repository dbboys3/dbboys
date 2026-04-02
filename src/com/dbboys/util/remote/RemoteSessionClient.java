package com.dbboys.util.remote;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

public final class RemoteSessionClient {
    private final JSch jsch = new JSch();
    private Session session;

    public synchronized void connect(String username, String host, int port, String password, int timeoutMs) throws JSchException {
        disconnect();
        Session newSession = jsch.getSession(username, host, port);
        newSession.setPassword(password);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        newSession.setConfig(config);
        newSession.connect(timeoutMs);
        session = newSession;
    }

    public synchronized boolean isConnected() {
        return session != null && session.isConnected();
    }

    public synchronized void disconnect() {
        if (session != null) {
            if (session.isConnected()) {
                session.disconnect();
            }
            session = null;
        }
    }

    public synchronized String executeCommand(String command) throws JSchException, IOException {
        Session current = requireConnectedSession();
        ChannelExec channelExec = (ChannelExec) current.openChannel("exec");
        channelExec.setCommand(command);

        InputStream in = channelExec.getInputStream();
        channelExec.connect();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } finally {
            channelExec.disconnect();
        }
    }

    public synchronized int executeCommandWithExitStatus(String command) throws JSchException, InterruptedException {
        Session current = requireConnectedSession();
        ChannelExec channelExec = (ChannelExec) current.openChannel("exec");
        channelExec.setCommand(command);
        channelExec.connect();

        try {
            while (!channelExec.isClosed()) {
                Thread.sleep(100);
            }
            return channelExec.getExitStatus();
        } finally {
            channelExec.disconnect();
        }
    }

    public synchronized ChannelSftp openSftpChannel() throws JSchException {
        Session current = requireConnectedSession();
        ChannelSftp channelSftp = (ChannelSftp) current.openChannel("sftp");
        channelSftp.connect();
        return channelSftp;
    }

    private Session requireConnectedSession() throws JSchException {
        if (!isConnected()) {
            throw new JSchException("SSH session not connected");
        }
        return session;
    }
}
