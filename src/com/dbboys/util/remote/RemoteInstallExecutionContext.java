package com.dbboys.util.remote;

import com.jcraft.jsch.JSchException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RemoteInstallExecutionContext {
    private final RemoteSessionClient remoteClient;
    private final Map<String, String> fieldValues;
    private final String remotePackagePath;
    private final String host;
    private final String machineInfo;
    private final String osInfo;
    private final String kernelInfo;
    private final String cpuInfo;
    private final String memoryInfo;
    private final String diskInfo;

    public RemoteInstallExecutionContext(
            RemoteSessionClient remoteClient,
            List<RemoteInstallField> fields,
            String remotePackagePath,
            String host,
            String machineInfo,
            String osInfo,
            String kernelInfo,
            String cpuInfo,
            String memoryInfo,
            String diskInfo
    ) {
        this.remoteClient = remoteClient;
        this.remotePackagePath = remotePackagePath;
        this.host = host;
        this.machineInfo = machineInfo;
        this.osInfo = osInfo;
        this.kernelInfo = kernelInfo;
        this.cpuInfo = cpuInfo;
        this.memoryInfo = memoryInfo;
        this.diskInfo = diskInfo;
        this.fieldValues = new HashMap<>();
        if (fields != null) {
            for (RemoteInstallField field : fields) {
                if (field != null && field.getId() != null) {
                    fieldValues.put(field.getId(), field.getValue());
                }
            }
        }
    }

    public String fieldValue(String id) {
        if (!fieldValues.containsKey(id)) {
            throw new IllegalStateException("Missing install field: " + id);
        }
        String value = fieldValues.get(id);
        return value == null ? "" : value;
    }

    public String executeCommand(String command) throws JSchException, IOException {
        return remoteClient.executeCommand(command);
    }

    public int executeCommandWithExitStatus(String command) throws JSchException, InterruptedException {
        return remoteClient.executeCommandWithExitStatus(command);
    }

    public String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public String remotePackagePath() {
        return remotePackagePath;
    }

    public String host() {
        return host;
    }

    public String machineInfo() {
        return machineInfo;
    }

    public String osInfo() {
        return osInfo;
    }

    public String kernelInfo() {
        return kernelInfo;
    }

    public String cpuInfo() {
        return cpuInfo;
    }

    public String memoryInfo() {
        return memoryInfo;
    }

    public String diskInfo() {
        return diskInfo;
    }
}
