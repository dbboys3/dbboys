package com.dbboys.util.remote;

import com.jcraft.jsch.JSchException;

import java.io.IOException;

public final class RemoteUninstallExecutionContext {
    private final RemoteSessionClient remoteClient;
    private final String host;

    public RemoteUninstallExecutionContext(RemoteSessionClient remoteClient, String host) {
        this.remoteClient = remoteClient;
        this.host = host;
    }

    public String executeCommand(String command) throws JSchException, IOException {
        return remoteClient.executeCommand(command);
    }

    public int executeCommandWithExitStatus(String command) throws JSchException, InterruptedException {
        return remoteClient.executeCommandWithExitStatus(command);
    }

    public String host() {
        return host;
    }
}
