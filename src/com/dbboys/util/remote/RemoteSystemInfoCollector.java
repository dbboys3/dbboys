package com.dbboys.util.remote;

import com.jcraft.jsch.JSchException;

import java.io.IOException;

public final class RemoteSystemInfoCollector {
    private RemoteSystemInfoCollector() {
    }

    public static RemoteSystemInfoSnapshot collect(RemoteSessionClient client) throws JSchException, IOException, InterruptedException {
        String machineInfo = client.executeCommand("dmidecode -s system-product-name");
        String osInfo;
        if (isCommandExists(client, "nkvers")) {
            osInfo = client.executeCommand("nkvers");
        } else if (client.executeCommandWithExitStatus("test -f /etc/redhat-release") == 0) {
            osInfo = client.executeCommand("cat /etc/redhat-release");
        } else {
            osInfo = client.executeCommand("cat /etc/os-release");
        }
        String cpuInfo = client.executeCommand("lscpu");
        String memoryInfo = client.executeCommand("free -h");
        String fileSystemInfo = client.executeCommand("df -h");
        String diskInfo = client.executeCommand("lsblk");
        String kernelInfo = client.executeCommand("uname -a");
        return new RemoteSystemInfoSnapshot(
                machineInfo,
                osInfo,
                kernelInfo,
                cpuInfo,
                memoryInfo,
                diskInfo,
                fileSystemInfo
        );
    }

    private static boolean isCommandExists(RemoteSessionClient client, String command) throws JSchException, InterruptedException {
        return client.executeCommandWithExitStatus("command -v " + command) == 0;
    }
}
