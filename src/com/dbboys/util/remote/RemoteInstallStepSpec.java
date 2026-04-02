package com.dbboys.util.remote;

public record RemoteInstallStepSpec(
        String nameKey,
        String defaultName,
        String descKey,
        String defaultDesc,
        boolean selected,
        boolean disabled
) {
}
