package com.dbboys.remote;

import com.dbboys.infra.i18n.I18n;
import com.dbboys.ui.component.CustomInlineCssTextArea;

import java.io.IOException;

public final class RemoteSystemInfoCollector {
    /** Section title style (accent color, bold, system font). */
    public static final String TITLE_STYLE =
            "-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;";
    /** Section body style (default foreground, monospaced). */
    public static final String TEXT_STYLE =
            "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;";

    private RemoteSystemInfoCollector() {
    }

    public static RemoteSystemInfoSnapshot collect(RemoteSessionClient client) throws IOException, InterruptedException {
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

    private static boolean isCommandExists(RemoteSessionClient client, String command) throws IOException {
        return client.executeCommandWithExitStatus("command -v " + command) == 0;
    }

    // ---- Unified system-info rendering (install wizard step 2 + result panels) ----

    /** Append one section: a bold accent title line, then the monospaced body
     *  followed by a blank line. Database-specific sections keep their own
     *  styles in the dialect workflows. */
    public static void appendSection(CustomInlineCssTextArea area, String title, String content) {
        area.append(title + "\n", TITLE_STYLE);
        area.append((content == null ? "" : content) + "\n\n", TEXT_STYLE);
    }

    /** Append the standard system-info sections, in the same set and order as
     *  the install wizard's step-2 panel. */
    public static void appendSystemInfo(CustomInlineCssTextArea area, RemoteSystemInfoSnapshot s) {
        appendSection(area, I18n.t("remote.install.info.machine", "服务器型号"), s.machineInfo());
        appendSection(area, I18n.t("remote.install.info.os", "操作系统版本"), s.osInfo());
        appendSection(area, I18n.t("remote.install.info.kernel", "内核版本"), s.kernelInfo());
        appendSection(area, I18n.t("remote.install.info.cpu", "CPU信息"), s.cpuInfo());
        appendSection(area, I18n.t("remote.install.info.memory", "内存信息"), s.memoryInfo());
        appendSection(area, I18n.t("remote.install.info.disk", "磁盘信息"), s.diskInfo());
        appendSection(area, I18n.t("remote.install.info.filesystem", "文件系统信息"), s.fileSystemInfo());
    }

    /** Same standard sections, taken from an install execution context.
     *  Re-runs df -h and free -h after install to capture accurate post-install
     *  disk and memory usage. */
    public static void appendSystemInfo(CustomInlineCssTextArea area, RemoteInstallExecutionContext ctx) {
        String fileSystemInfo;
        try {
            fileSystemInfo = ctx.getRemoteClient().executeCommand("df -h");
        } catch (IOException e) {
            fileSystemInfo = ctx.fileSystemInfo();
        }
        String memoryInfo;
        try {
            memoryInfo = ctx.getRemoteClient().executeCommand("free -h");
        } catch (IOException e) {
            memoryInfo = ctx.memoryInfo();
        }
        appendSystemInfo(area, new RemoteSystemInfoSnapshot(
                ctx.machineInfo(), ctx.osInfo(), ctx.kernelInfo(), ctx.cpuInfo(),
                memoryInfo, ctx.diskInfo(), fileSystemInfo));
    }
}
