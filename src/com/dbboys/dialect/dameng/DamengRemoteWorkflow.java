package com.dbboys.dialect.dameng;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.RemoteInstallExecutionContext;
import com.dbboys.remote.RemoteUninstallExecutionContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;

public final class DamengRemoteWorkflow {
    private static final String RESULT_TITLE_STYLE = "-fx-fill: -color-dialog-title-fg;-fx-font-weight: bold;-fx-font-family:system;";
    private static final Logger log = LogManager.getLogger(DamengRemoteWorkflow.class);

    private DamengRemoteWorkflow() {
    }

    // ==================== Install Steps ====================

    public static void executeInstallStep(int stepNo, RemoteInstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:
                cleanupExistingInstall(ctx);
                return;
            case 2:
                checkSystemDependencies(ctx);
                return;
            case 3:
                createUserAndDirectories(ctx);
                return;
            case 4:
                extractAndMountIso(ctx);
                return;
            case 5:
                silentInstall(ctx);
                return;
            case 6:
                initializeInstance(ctx);
                return;
            case 7:
                registerService(ctx);
                return;
            default:
                throw new IllegalArgumentException("Unknown Dameng install step: " + stepNo);
        }
    }

    public static void afterInstallSteps(RemoteInstallExecutionContext ctx) {
        // no post-install hook needed
    }

    public static void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:
                stopDamengService(ctx);
                return;
            case 2:
                removeService(ctx);
                return;
            case 3:
                removeDirectories(ctx);
                return;
            case 4:
                removeUserAndGroup(ctx);
                return;
            case 5:
                cleanupResidualFiles(ctx);
                return;
            default:
                throw new IllegalArgumentException("Unknown Dameng uninstall step: " + stepNo);
        }
    }

    // ==================== Install Step Implementations ====================

    private static void cleanupExistingInstall(RemoteInstallExecutionContext ctx) throws Exception {
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String dataPath = ctx.fieldValue(DamengRemoteFields.DATA_PATH);
        String instanceName = ctx.fieldValue(DamengRemoteFields.INSTANCE_NAME);
        String serviceName = DamengRemoteFields.SERVICE_PREFIX + instanceName;

        ctx.executeCommandWithExitStatus("systemctl stop " + serviceName + ".service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("pkill -9 dmserver || true");
        ctx.executeCommandWithExitStatus("pkill -9 dmwatcher || true");
        ctx.executeCommandWithExitStatus("systemctl disable " + serviceName + ".service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -f /etc/systemd/system/" + serviceName + ".service /etc/init.d/" + serviceName);
        ctx.executeCommandWithExitStatus("systemctl daemon-reload 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -rf " + ctx.shellQuote(installPath) + " 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -rf " + ctx.shellQuote(dataPath) + " 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -f /etc/security/limits.d/dameng.conf 2>/dev/null || true");
        if (ctx.executeCommandWithExitStatus("id " + DamengRemoteFields.USER_NAME) == 0) {
            ctx.executeCommandWithExitStatus("userdel -r -f " + DamengRemoteFields.USER_NAME + " 2>/dev/null || true");
            ctx.executeCommandWithExitStatus("groupdel " + DamengRemoteFields.GROUP_NAME + " 2>/dev/null || true");
        }
    }

    private static void checkSystemDependencies(RemoteInstallExecutionContext ctx) throws Exception {
        // Check unzip
        if (ctx.executeCommandWithExitStatus("command -v unzip >/dev/null") != 0) {
            throw new Exception(I18n.t("remote.install.dameng.error.unzip_missing",
                    "unzip is required. Install it with: yum install -y unzip or apt install -y unzip."));
        }

        // Check libaio
        if (ctx.executeCommandWithExitStatus("ldconfig -p 2>/dev/null | grep -q libaio || rpm -q libaio >/dev/null 2>&1 || dpkg -l libaio1 >/dev/null 2>&1") != 0) {
            throw new Exception(I18n.t("remote.install.dameng.error.dependency_libaio",
                    "libaio is required. Install it with: yum install -y libaio or apt install -y libaio1."));
        }

        // Check available disk space on /opt (at least 5GB)
        String diskFree = ctx.executeCommand("df -m /opt 2>/dev/null | tail -1 | awk '{print $4}' || echo '0'");
        try {
            int freeMb = Integer.parseInt(diskFree.trim());
            if (freeMb < 5000) {
                throw new Exception(I18n.t("remote.install.dameng.error.disk_space",
                        "At least 5GB free space is required on /opt. Available: ") + freeMb + "MB");
            }
        } catch (NumberFormatException ignored) {
        }

        // Verify package exists
        String packagePath = ctx.remotePackagePath();
        if (packagePath != null && !packagePath.isBlank()) {
            if (ctx.executeCommandWithExitStatus("test -f " + ctx.shellQuote(packagePath)) != 0) {
                throw new Exception(I18n.t("remote.install.error.remote_package_missing",
                        "Remote package file does not exist: ") + packagePath);
            }
        }
    }

    private static void createUserAndDirectories(RemoteInstallExecutionContext ctx) throws Exception {
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String dataPath = ctx.fieldValue(DamengRemoteFields.DATA_PATH);
        String password = ctx.fieldValue(DamengRemoteFields.SYSDBA_PASSWORD);
        String groupName = DamengRemoteFields.GROUP_NAME;
        String userName = DamengRemoteFields.USER_NAME;

        String script =
                "getent group " + groupName + " >/dev/null || groupadd " + groupName + ";" +
                "id " + userName + " >/dev/null 2>&1 || useradd -g " + groupName + " -d /home/" + userName + " -m -s /bin/bash " + userName + ";" +
                "echo \"" + userName + ":" + password + "\" | chpasswd;" +
                "mkdir -p " + ctx.shellQuote(installPath) + " " + ctx.shellQuote(dataPath) + ";" +
                "chown -R " + userName + ":" + groupName + " " + ctx.shellQuote(installPath) + " " + ctx.shellQuote(dataPath) + ";";

        if (ctx.executeCommandWithExitStatus(script) != 0) {
            throw new Exception(I18n.t("remote.install.dameng.error.create_user_dirs_failed",
                    "Failed to create dinstall group, dmdba user, or directories."));
        }

        // Set file descriptor and process limits
        String limitsConf = "/etc/security/limits.d/dameng.conf";
        String setLimits =
                "cat > " + limitsConf + " <<'LIMITS_EOF'\n" +
                userName + " soft nofile 65536\n" +
                userName + " hard nofile 65536\n" +
                userName + " soft nproc 20480\n" +
                userName + " hard nproc 20480\n" +
                "LIMITS_EOF";
        ctx.executeCommandWithExitStatus(setLimits);
    }

    /**
     * Step 4: Extract zip to get ISO, then mount the ISO.
     * The downloaded .zip contains a Dameng DM8 .iso file.
     */
    private static void extractAndMountIso(RemoteInstallExecutionContext ctx) throws Exception {
        String packagePath = ctx.remotePackagePath();
        String userName = DamengRemoteFields.USER_NAME;
        String groupName = DamengRemoteFields.GROUP_NAME;
        String lowerPkg = packagePath == null ? "" : packagePath.toLowerCase();
        String mountPoint = "/dm8_iso";

        // Cleanup any previous mount
        ctx.executeCommandWithExitStatus("umount " + mountPoint + " 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -rf " + mountPoint);

        if (lowerPkg.endsWith(".zip")) {
            // Zip contains an ISO file. Extract the zip, find the ISO, then mount it.
            String extractDir = "/tmp/dm8_extract";
            String script =
                    "rm -rf " + ctx.shellQuote(extractDir) + "&&" +
                    "mkdir -p " + ctx.shellQuote(extractDir) + " " + mountPoint + "&&" +
                    "unzip -o " + ctx.shellQuote(packagePath) + " -d " + ctx.shellQuote(extractDir) + " &&" +
                    // Find the ISO file inside the extracted content
                    "iso_file=$(find " + ctx.shellQuote(extractDir) + " -name '*.iso' -type f | head -1)&&" +
                
                    "mount -o loop \"$iso_file\" " + mountPoint  ;
            if (ctx.executeCommandWithExitStatus(script) != 0) {
                throw new Exception(I18n.t("remote.install.dameng.error.mount_failed",
                        "Failed to extract zip or mount ISO from: ") + packagePath);
            }
        } else if (lowerPkg.endsWith(".iso")) {
            // Directly mount the ISO
            ctx.executeCommandWithExitStatus("mkdir -p " + mountPoint);
            if (ctx.executeCommandWithExitStatus("mount -o loop " + ctx.shellQuote(packagePath) + " " + mountPoint) != 0) {
                throw new Exception(I18n.t("remote.install.dameng.error.mount_failed",
                        "Failed to mount ISO file: ") + packagePath);
            }
        } else {
            throw new Exception(I18n.t("remote.install.dameng.error.extract_failed",
                    "Unsupported package format. Expected .zip or .iso: ") + packagePath);
        }

        ctx.executeCommandWithExitStatus("chown -R " + userName + ":" + groupName + " " + mountPoint);
    }

    /**
     * Step 5: Run DMInstall.bin from the mounted ISO in silent mode.
     */
    private static void silentInstall(RemoteInstallExecutionContext ctx) throws Exception {
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String userName = DamengRemoteFields.USER_NAME;
        String groupName = DamengRemoteFields.GROUP_NAME;
        String mountPoint = "/dm8_iso";

        // Write auto_install.xml for silent install
        String xmlContent =
                "<?xml version=\"1.0\"?>\n" +
                "<DATABASE>\n" +
                "    <LANGUAGE>en</LANGUAGE>\n" +
                "    <TIMEZONE>+08:00</TIMEZONE>\n" +
                "    <KEY></KEY>\n" +
                "    <INSTALL_TYPE>1</INSTALL_TYPE>\n" +
                "    <INSTALL_PATH>" + installPath + "</INSTALL_PATH>\n" +
                "    <INIT_DB>N</INIT_DB>\n" +
                "    <INI_FILE_PATH></INI_FILE_PATH>\n" +
                "    <BOOLEAN_KEYS>1</BOOLEAN_KEYS>\n" +
                "</DATABASE>";

        ctx.executeCommand("cat > /tmp/auto_install.xml <<'DM_XML_EOF'\n" + xmlContent + "\nDM_XML_EOF");
        ctx.executeCommandWithExitStatus("chown " + userName + ":" + groupName + " /tmp/auto_install.xml");

        // Find DMInstall.bin in mounted ISO
        String installerBin = ctx.executeCommand(
                "find " + mountPoint + " -name 'DMInstall.bin' -type f 2>/dev/null | head -1").trim();
        if (installerBin.isEmpty()) {
            installerBin = ctx.executeCommand(
                    "find " + mountPoint + " -name '*.bin' -type f 2>/dev/null | head -1").trim();
        }
        if (installerBin.isEmpty()) {
            // Log ISO contents for debugging
            String isoContents = ctx.executeCommand("ls -laR " + ctx.shellQuote(mountPoint) + " 2>/dev/null | head -50 || echo '<empty>'");
            throw new Exception(I18n.t("remote.install.dameng.error.installer_not_found",
                    "DMInstall.bin not found in mounted ISO. Contents:\n") + isoContents);
        }

        // Run silent installer as dmdba (exit code 1 is OK — just means dmdba can't run root_installer.sh)
        ctx.executeCommandWithExitStatus("chmod a+x " + ctx.shellQuote(installerBin));
        String runCmd = "su - " + userName + " -c " +
                ctx.shellQuote(installerBin + " -q /tmp/auto_install.xml > /tmp/dm_install.log 2>&1; echo EXIT_CODE:$?");
        ctx.executeCommand(runCmd);

        // Run root_installer.sh as root
        ctx.executeCommandWithExitStatus(installPath + "/script/root/root_installer.sh > /tmp/dm_root_install.log 2>&1 || true");

        // Log install output
        ctx.executeCommand("{ echo '=== install stdout ==='; cat /tmp/dm_install.log 2>/dev/null || true; " +
                "echo '=== root_installer ==='; cat /tmp/dm_root_install.log 2>/dev/null || true; " +
                "} > /tmp/dm_install_diag.log 2>/dev/null; chmod 644 /tmp/dm_install_diag.log 2>/dev/null || true");

        // Check if install succeeded by looking for key binaries (DMInstall exits 1 when root_installer.sh fails, that's OK)
        String checkBin = ctx.executeCommand(
                "test -f " + ctx.shellQuote(installPath + "/bin/dminit") + " && echo 'OK' || echo 'FIND'").trim();
        if (!"OK".equals(checkBin)) {
            checkBin = ctx.executeCommand("test -d " + ctx.shellQuote(installPath) +
                    " && test -n \"$(find " + ctx.shellQuote(installPath) +
                    " -name 'dminit' -type f 2>/dev/null | head -1)\" && echo 'OK' || echo 'FAIL'").trim();
        }
        if (!"OK".equals(checkBin)) {
            String diagLog = ctx.executeCommand("cat /tmp/dm_install_diag.log 2>/dev/null || echo '<no log>'");
            throw new Exception(I18n.t("remote.install.dameng.error.install_failed",
                    "Silent install failed. Check /tmp/dm_install_diag.log on remote server.\n") + diagLog);
        }

        // Cleanup: unmount ISO
        ctx.executeCommandWithExitStatus("umount " + mountPoint + " 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -rf " + mountPoint);
        ctx.executeCommandWithExitStatus("rm -f /tmp/auto_install.xml");
    }

    private static void initializeInstance(RemoteInstallExecutionContext ctx) throws Exception {
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String dataPath = ctx.fieldValue(DamengRemoteFields.DATA_PATH);
        String instanceName = ctx.fieldValue(DamengRemoteFields.INSTANCE_NAME);
        String port = ctx.fieldValue(DamengRemoteFields.PORT);
        String pageSize = ctx.fieldValue(DamengRemoteFields.PAGE_SIZE);
        String caseSensitive = ctx.fieldValue(DamengRemoteFields.CASE_SENSITIVE);
        String charset = ctx.fieldValue(DamengRemoteFields.CHARSET);
        String compatibleMode = ctx.fieldValue(DamengRemoteFields.COMPATIBLE_MODE);
        String extentSize = ctx.fieldValue(DamengRemoteFields.EXTENT_SIZE);
        String blankPadMode = ctx.fieldValue(DamengRemoteFields.BLANK_PAD_MODE);
        String logSize = ctx.fieldValue(DamengRemoteFields.LOG_SIZE);
        String buffer = ctx.fieldValue(DamengRemoteFields.BUFFER);
        String userName = DamengRemoteFields.USER_NAME;

        // find dminit binary
        String dminitBin = findBin(installPath, ctx, "dminit");
        if (dminitBin == null) {
            // List contents of installPath for diagnostics
            String lsOutput = ctx.executeCommand("ls -la " + ctx.shellQuote(installPath) + " 2>/dev/null | head -30 || echo '<empty>'");
            throw new Exception(I18n.t("remote.install.dameng.error.init_failed",
                    "dminit not found under install path: ") + installPath + "\nInstall path contents:\n" + lsOutput);
        }

        // Pre-flight: verify dminit can actually run (check if it's a valid binary)
        String preflight = ctx.executeCommand("file " + ctx.shellQuote(dminitBin) + " 2>/dev/null; " +
                "ldd " + ctx.shellQuote(dminitBin) + " 2>&1 | head -10; " +
                "ls -la " + ctx.shellQuote(dminitBin) + " 2>/dev/null");
        ctx.executeCommand("echo 'DMINIT_PREFLIGHT:' > /tmp/dm_preflight.log 2>/dev/null; " +
                "echo '" + preflight.replace("'", "'\\''") + "' >> /tmp/dm_preflight.log 2>/dev/null || true");

        // Build dminit command with logging - use a shell script approach to avoid quoting issues
        String logFile = "/tmp/dm_init_" + instanceName + ".log";
        String initScript = "/tmp/dm_init_" + instanceName + ".sh";
        String sysdbaPwd = ctx.fieldValue(DamengRemoteFields.SYSDBA_PASSWORD);

        // Write a wrapper script that dmdba can execute
        String scriptContent =
                "#!/bin/bash\n" +
                dminitBin + " PATH='" + dataPath + "'" +
                " DB_NAME=" + instanceName +
                " INSTANCE_NAME=" + instanceName +
                " PORT_NUM=" + port +
                " PAGE_SIZE=" + pageSize +
                " CASE_SENSITIVE=" + caseSensitive +
                " CHARSET=" + charset +
                (compatibleMode != null && !compatibleMode.isBlank() && !"0".equals(compatibleMode)
                        ? " COMPATIBLE_MODE=" + compatibleMode : "") +
                " EXTENT_SIZE=" + extentSize +
                " BLANK_PAD_MODE=" + blankPadMode +
                " LOG_SIZE=" + logSize +
                " BUFFER=" + buffer +
                " SYSDBA_PWD=" + ctx.shellQuote(sysdbaPwd) +
                " SYSAUDITOR_PWD=" + ctx.shellQuote(sysdbaPwd) +
                " > " + logFile + " 2>&1\n" +
                "echo EXIT_CODE:$? >> " + logFile + "\n";

        ctx.executeCommand("cat > " + ctx.shellQuote(initScript) + " << 'INIT_SCRIPT_EOF'\n" + scriptContent + "INIT_SCRIPT_EOF");
        ctx.executeCommandWithExitStatus("chmod +x " + ctx.shellQuote(initScript));
        ctx.executeCommandWithExitStatus("chown " + userName + ":" + DamengRemoteFields.GROUP_NAME + " " + ctx.shellQuote(initScript));

        // Run the init script as dmdba, capture both stdout and stderr
        String runCmd = "su - " + userName + " -c " + ctx.shellQuote(initScript + " 2>&1");
        String output = ctx.executeCommand(runCmd);
        ctx.executeCommand("echo 'DMINIT_STDOUT: " + output.replace("'", "'\\''") + "' >> /tmp/dm_preflight.log 2>/dev/null || true");

        // Read the log for diagnostics, and dump everything to /tmp/dm_init_diag.log on remote
        ctx.executeCommand(
                "{ echo '=== dminit log ==='; cat " + ctx.shellQuote(logFile) +
                " 2>&1 || echo '<no log>'; echo; echo '=== stdout ==='; " +
                "echo '" + output.replace("'", "'\\''") + "'; echo; " +
                "echo '=== preflight ==='; cat /tmp/dm_preflight.log 2>&1 || true; " +
                "} > /tmp/dm_init_diag.log 2>&1; chmod 644 /tmp/dm_init_diag.log 2>&1 || true");

        // Verify init succeeded by checking for dm.ini
        String checkOutput = ctx.executeCommand(
                "test -f " + ctx.shellQuote(dataPath + "/" + instanceName + "/dm.ini") + " && echo 'OK' || echo 'FAIL'").trim();
        if (!"OK".equals(checkOutput)) {
            String diagLog = ctx.executeCommand("cat /tmp/dm_init_diag.log 2>/dev/null || echo '<no diag log>'");
            throw new Exception(I18n.t("remote.install.dameng.error.init_failed",
                    "Database instance initialization failed. Check /tmp/dm_init_diag.log on remote server.\n") + diagLog);
        }
    }

    private static void registerService(RemoteInstallExecutionContext ctx) throws Exception {
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String dataPath = ctx.fieldValue(DamengRemoteFields.DATA_PATH);
        String instanceName = ctx.fieldValue(DamengRemoteFields.INSTANCE_NAME);

        String dmIni = dataPath + "/" + instanceName + "/dm.ini";
        String serviceInstaller = findServiceInstaller(installPath, ctx);

        if (serviceInstaller == null) {
            throw new Exception(I18n.t("remote.install.dameng.error.register_service_failed",
                    "dm_service_installer.sh not found under install path. Service registration skipped."));
        }

        String cmd = serviceInstaller + " -t dmserver -p " + instanceName + " -dm_ini " + ctx.shellQuote(dmIni);
        ctx.executeCommand(cmd + " 2>&1");

        ctx.executeCommandWithExitStatus("systemctl daemon-reload");
        String serviceName = DamengRemoteFields.SERVICE_PREFIX + instanceName;
        ctx.executeCommandWithExitStatus("systemctl enable " + serviceName + ".service 2>/dev/null || true");
    }



    // ==================== Uninstall Step Implementations ====================

    private static void stopDamengService(RemoteUninstallExecutionContext ctx) throws Exception {
        ctx.executeCommandWithExitStatus("systemctl stop DmService*.service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("systemctl stop DmJobMonitor*.service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("pkill -9 dmserver || true");
        ctx.executeCommandWithExitStatus("pkill -9 dmwatcher || true");
        ctx.executeCommandWithExitStatus("pkill -9 DmJobMonitor || true");
    }

    private static void removeService(RemoteUninstallExecutionContext ctx) throws Exception {
        ctx.executeCommandWithExitStatus("systemctl disable DmService*.service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("systemctl disable DmJobMonitor*.service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -f /etc/systemd/system/DmService*.service /etc/systemd/system/DmJobMonitor*.service");
        ctx.executeCommandWithExitStatus("rm -f /etc/init.d/DmService* /etc/init.d/DmJobMonitor*");
        ctx.executeCommandWithExitStatus("systemctl daemon-reload 2>/dev/null || true");
    }

    private static void removeDirectories(RemoteUninstallExecutionContext ctx) throws Exception {
        ctx.executeCommandWithExitStatus("rm -rf /opt/dmdbms 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -rf /dm/data 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -rf /dm 2>/dev/null || true");

        // Detect and remove other possible paths
        String detectScript =
                "for f in $(find /opt /usr/local /usr -maxdepth 4 -name 'disql' -type f 2>/dev/null); do " +
                "  dirname \"$(dirname \"$f\")\"; " +
                "done | sort -u";
        String detected = ctx.executeCommand(detectScript).trim();
        if (detected != null && !detected.isBlank()) {
            for (String path : detected.split("\n")) {
                String trimmed = path.trim();
                if (!trimmed.isEmpty() && !"/".equals(trimmed) && !"/opt".equals(trimmed) &&
                    !"/usr".equals(trimmed) && !"/usr/local".equals(trimmed)) {
                    ctx.executeCommandWithExitStatus("rm -rf " + trimmed + " 2>/dev/null || true");
                }
            }
        }
    }

    private static void removeUserAndGroup(RemoteUninstallExecutionContext ctx) throws Exception {
        String userName = DamengRemoteFields.USER_NAME;
        String groupName = DamengRemoteFields.GROUP_NAME;
        if (ctx.executeCommandWithExitStatus("id " + userName) == 0) {
            ctx.executeCommandWithExitStatus("userdel -r -f " + userName + " 2>/dev/null || true");
        }
        ctx.executeCommandWithExitStatus("groupdel " + groupName + " 2>/dev/null || true");
    }

    private static void cleanupResidualFiles(RemoteUninstallExecutionContext ctx) throws Exception {
        ctx.executeCommandWithExitStatus("rm -f /etc/security/limits.d/dameng.conf 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("sed -i '/dmdba/d' /etc/security/limits.conf 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -f /tmp/auto_install.xml /tmp/dm_install.log /tmp/dm_init.log 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("umount /mnt/dm8_iso 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -rf /mnt/dm8_iso 2>/dev/null || true");
    }

    // ==================== Helpers ====================

    private static String findBin(String installPath, RemoteInstallExecutionContext ctx, String binName) throws Exception {
        String result = ctx.executeCommand(
                "find " + ctx.shellQuote(installPath) + " -name '" + binName + "' -type f 2>/dev/null | head -1"
        ).trim();
        if (!result.isEmpty()) {
            // Ensure it's executable after zip extraction
            ctx.executeCommandWithExitStatus("chmod a+x " + ctx.shellQuote(result) + " 2>/dev/null || true");
        }
        return result.isEmpty() ? null : result;
    }

    private static String findServiceInstaller(String installPath, RemoteInstallExecutionContext ctx) throws Exception {
        String result = ctx.executeCommand("find " + ctx.shellQuote(installPath) + " -name 'dm_service_installer.sh' -type f 2>/dev/null | head -1").trim();
        if (result.isEmpty()) {
            result = ctx.executeCommand("find " + ctx.shellQuote(installPath) + " -name 'dm_service_installer.sh' -path '*/root/*' -type f 2>/dev/null | head -1").trim();
        }
        return result.isEmpty() ? null : result;
    }

    // ==================== Result and Connect ====================

    public static void populateInstallResult(RemoteInstallExecutionContext ctx, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        String packageName = ctx.remotePackagePath() == null ? "" : new File(ctx.remotePackagePath()).getName();
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String dataPath = ctx.fieldValue(DamengRemoteFields.DATA_PATH);
        String port = ctx.fieldValue(DamengRemoteFields.PORT);
        String password = ctx.fieldValue(DamengRemoteFields.SYSDBA_PASSWORD);
        String charset = ctx.fieldValue(DamengRemoteFields.CHARSET);
        String pageSize = ctx.fieldValue(DamengRemoteFields.PAGE_SIZE);
        String caseSensitive = ctx.fieldValue(DamengRemoteFields.CASE_SENSITIVE);
        String compatibleMode = ctx.fieldValue(DamengRemoteFields.COMPATIBLE_MODE);
        String disql = findBin(installPath, ctx, "disql");

        databaseInfoArea.replaceText("");

        databaseInfoArea.append(I18n.t("remote.install.result.db_version", "Database version") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(packageName + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        if (disql != null) {
            databaseInfoArea.append(ctx.executeCommand(
                    ctx.shellQuote(disql) + " SYSDBA/" + password.replace("\"", "\\\"") +
                    "@localhost:" + port + " -e \"select id_code()\" 2>/dev/null || echo ''") + "\n\n",
                    "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        }

        databaseInfoArea.append(I18n.t("remote.install.result.db_instance_info", "Database instance info") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.install_path", "Install path") + ": " + installPath + "\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.data_path", "Data path") + ": " + dataPath + "\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.listen_ip", "Listen IP") + ": 0.0.0.0\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.port", "Port") + ": " + port + "\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.user_password", "User/password") + ": SYSDBA/" + password + "\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.dameng.cfg.charset.name", "Charset") + ": " + charset + "\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.dameng.cfg.page_size.name", "Page size") + ": " + pageSize + "K\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.dameng.cfg.case_sensitive.name", "Case sensitive") + ": " +
                ("1".equals(caseSensitive) ? "Y" : "N") + "\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.dameng.cfg.compatible_mode.name", "Compatible mode") + ": " + compatibleMode + "\n\n",
                "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

        if (disql != null) {
            databaseInfoArea.append(I18n.t("remote.install.dameng.result.variables", "Dameng variables") + "\n", RESULT_TITLE_STYLE);
            databaseInfoArea.append(ctx.executeCommand(
                    ctx.shellQuote(disql) + " SYSDBA/" + password.replace("\"", "\\\"") +
                    "@localhost:" + port + " -e \"select name, value from v\\$parameter where name in " +
                    "('INSTANCE_NAME','DB_NAME','PORT_NUM','PAGE_SIZE','CASE_SENSITIVE','CHARSET','COMPATIBLE_MODE','LENGTH_IN_CHAR')\" 2>/dev/null || echo ''") +
                    "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        }
    }

    public static Connect buildInstalledConnect(RemoteInstallExecutionContext ctx) {
        DamengDialect dialect = new DamengDialect();
        Connect connect = new Connect();
        connect.setDbtype(dialect.getDbType());
        connect.setIp(ctx.host());
        connect.setPort(ctx.fieldValue(DamengRemoteFields.PORT));
        connect.setCatalog(dialect.connection().defaultDatabase());
        connect.setUsername(DamengRemoteFields.DEFAULT_USERNAME);
        connect.setPassword(ctx.fieldValue(DamengRemoteFields.SYSDBA_PASSWORD));
        connect.setDriver("DmJdbcDriver11.jar");
        connect.setSessionCatalog(DamengRemoteFields.DEFAULT_USERNAME);
        connect.setProps(dialect.connection().defaultConnectionProps());
        return connect;
    }
}
