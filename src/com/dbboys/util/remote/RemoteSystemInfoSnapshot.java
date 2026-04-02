package com.dbboys.util.remote;

public record RemoteSystemInfoSnapshot(
        String machineInfo,
        String osInfo,
        String kernelInfo,
        String cpuInfo,
        String memoryInfo,
        String diskInfo,
        String fileSystemInfo
) {
}
