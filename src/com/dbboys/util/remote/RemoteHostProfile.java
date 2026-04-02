package com.dbboys.util.remote;

public final class RemoteHostProfile {
    private final double freeDiskSizeGb;
    private final double totalMemoryGb;
    private final int cpuCount;

    public RemoteHostProfile(double freeDiskSizeGb, double totalMemoryGb, int cpuCount) {
        this.freeDiskSizeGb = freeDiskSizeGb;
        this.totalMemoryGb = totalMemoryGb;
        this.cpuCount = cpuCount;
    }

    public double getFreeDiskSizeGb() {
        return freeDiskSizeGb;
    }

    public double getTotalMemoryGb() {
        return totalMemoryGb;
    }

    public int getCpuCount() {
        return cpuCount;
    }
}
