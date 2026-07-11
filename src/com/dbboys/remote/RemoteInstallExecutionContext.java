package com.dbboys.remote;

import com.jcraft.jsch.JSchException;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final String fileSystemInfo;
    private final Set<Integer> selectedInstallSteps;

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
            String diskInfo,
            String fileSystemInfo,
            Set<Integer> selectedInstallSteps
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
        this.fileSystemInfo = fileSystemInfo;
        this.selectedInstallSteps = selectedInstallSteps == null ? Set.of() : Set.copyOf(new HashSet<>(selectedInstallSteps));
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

    public void setFieldValue(String id, String value) {
        fieldValues.put(id, value == null ? "" : value);
    }

    public String executeCommand(String command) throws JSchException, IOException {
        return remoteClient.executeCommand(command);
    }

    public int executeCommandWithExitStatus(String command) throws JSchException, InterruptedException {
        return remoteClient.executeCommandWithExitStatus(command);
    }

    public RemoteSessionClient getRemoteClient() {
        return remoteClient;
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

    public String fileSystemInfo() {
        return fileSystemInfo;
    }

    public boolean isInstallStepSelected(int stepNo) {
        return selectedInstallSteps.contains(stepNo);
    }
}