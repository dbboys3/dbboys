package com.dbboys.dialect.dameng;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.RemoteInstallExecutionContext;
import com.dbboys.remote.RemoteUninstallExecutionContext;

import java.io.File;

public final class DamengRemoteWorkflow {
    private static final String RESULT_TITLE_STYLE = "-fx-fill: -color-dialog-title-fg;-fx-font-weight: bold;-fx-font-family:system;";

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
                extractPackage(ctx);
                return;
            case 5:
                initializeInstance(ctx);
                return;
            case 6:
                registerService(ctx);
                return;
            case 7:
                startAndSetPassword(ctx);
                return;
            case 8:
                installSampleData(ctx);
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
                "id " + userName + " >/dev/null 2>&1 || useradd -r -g " + groupName + " -s /bin/bash " + userName + ";" +
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

    private static void extractPackage(RemoteInstallExecutionContext ctx) throws Exception {
        String packagePath = ctx.remotePackagePath();
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String userName = DamengRemoteFields.USER_NAME;
        String groupName = DamengRemoteFields.GROUP_NAME;
        String lowerPkg = packagePath == null ? "" : packagePath.toLowerCase();

        if (lowerPkg.endsWith(".zip")) {
            // Extract zip to install path
            String script =
                    "rm -rf " + ctx.shellQuote(installPath) + "/*;" +
                    "mkdir -p " + ctx.shellQuote(installPath) + ";" +
                    "unzip -o " + ctx.shellQuote(packagePath) + " -d " + ctx.shellQuote(installPath) + ";" +
                    "chown -R " + userName + ":" + groupName + " " + ctx.shellQuote(installPath);
            if (ctx.executeCommandWithExitStatus(script) != 0) {
                throw new Exception(I18n.t("remote.install.dameng.error.extract_failed",
                        "Failed to extract ZIP package: ") + packagePath);
            }
        } else if (lowerPkg.endsWith(".tar.gz") || lowerPkg.endsWith(".tgz")) {
            String script =
                    "rm -rf " + ctx.shellQuote(installPath) + "/*;" +
                    "mkdir -p " + ctx.shellQuote(installPath) + ";" +
                    "tar -xzf " + ctx.shellQuote(packagePath) + " -C " + ctx.shellQuote(installPath) + " --strip-components=1;" +
                    "chown -R " + userName + ":" + groupName + " " + ctx.shellQuote(installPath);
            if (ctx.executeCommandWithExitStatus(script) != 0) {
                throw new Exception(I18n.t("remote.install.dameng.error.extract_failed",
                        "Failed to extract tar.gz package: ") + packagePath);
            }
        } else if (lowerPkg.endsWith(".tar")) {
            String script =
                    "rm -rf " + ctx.shellQuote(installPath) + "/*;" +
                    "mkdir -p " + ctx.shellQuote(installPath) + ";" +
                    "tar -xf " + ctx.shellQuote(packagePath) + " -C " + ctx.shellQuote(installPath) + " --strip-components=1;" +
                    "chown -R " + userName + ":" + groupName + " " + ctx.shellQuote(installPath);
            if (ctx.executeCommandWithExitStatus(script) != 0) {
                throw new Exception(I18n.t("remote.install.dameng.error.extract_failed",
                        "Failed to extract tar package: ") + packagePath);
            }
        } else if (lowerPkg.endsWith(".iso")) {
            // Fallback ISO mount + copy approach
            String mountPoint = "/mnt/dm8_iso";
            ctx.executeCommandWithExitStatus("mkdir -p " + mountPoint);
            if (ctx.executeCommandWithExitStatus("mount -o loop " + ctx.shellQuote(packagePath) + " " + mountPoint) != 0) {
                throw new Exception(I18n.t("remote.install.dameng.error.mount_failed",
                        "Failed to mount ISO file: ") + packagePath);
            }
            String script =
                    "rm -rf " + ctx.shellQuote(installPath) + "/*;" +
                    "mkdir -p " + ctx.shellQuote(installPath) + ";" +
                    "cp -r " + mountPoint + "/* " + ctx.shellQuote(installPath) + "/;" +
                    "chown -R " + userName + ":" + groupName + " " + ctx.shellQuote(installPath) + ";";
            int status = ctx.executeCommandWithExitStatus(script);
            ctx.executeCommandWithExitStatus("umount /mnt/dm8_iso 2>/dev/null || true");
            ctx.executeCommandWithExitStatus("rm -rf /mnt/dm8_iso");
            if (status != 0) {
                throw new Exception(I18n.t("remote.install.dameng.error.extract_failed",
                        "Failed to copy files from ISO: ") + packagePath);
            }
        }
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
        String lengthInChar = ctx.fieldValue(DamengRemoteFields.LENGTH_IN_CHAR);
        String logSize = ctx.fieldValue(DamengRemoteFields.LOG_SIZE);
        String buffer = ctx.fieldValue(DamengRemoteFields.BUFFER);
        String userName = DamengRemoteFields.USER_NAME;

        // find dminit binary
        String dminitBin = findBin(installPath, ctx, "dminit");
        if (dminitBin == null) {
            throw new Exception(I18n.t("remote.install.dameng.error.init_failed",
                    "dminit not found under install path: ") + installPath);
        }

        // Build dminit command
        StringBuilder cmd = new StringBuilder();
        cmd.append(dminitBin);
        cmd.append(" PATH=").append(ctx.shellQuote(dataPath));
        cmd.append(" DB_NAME=").append(instanceName);
        cmd.append(" INSTANCE_NAME=").append(instanceName);
        cmd.append(" PORT_NUM=").append(port);
        cmd.append(" PAGE_SIZE=").append(pageSize);
        cmd.append(" CASE_SENSITIVE=").append(caseSensitive);
        cmd.append(" CHARSET=").append(charset);
        if (compatibleMode != null && !compatibleMode.isBlank() && !"0".equals(compatibleMode)) {
            cmd.append(" COMPATIBLE_MODE=").append(compatibleMode);
        }
        cmd.append(" EXTENT_SIZE=").append(extentSize);
        cmd.append(" BLANK_PAD_MODE=").append(blankPadMode);
        cmd.append(" LENGTH_IN_CHAR=").append(lengthInChar);
        cmd.append(" LOG_SIZE=").append(logSize);
        cmd.append(" BUFFER=").append(buffer);

        String output = ctx.executeCommand("su - " + userName + " -c " + ctx.shellQuote(cmd.toString()) + " 2>&1");

        // Verify init succeeded by checking for dm.ini
        String checkOutput = ctx.executeCommand("test -f " + ctx.shellQuote(dataPath + "/" + instanceName + "/dm.ini") + " && echo 'OK' || echo 'FAIL'").trim();
        if (!"OK".equals(checkOutput)) {
            throw new Exception(I18n.t("remote.install.dameng.error.init_failed",
                    "Database instance initialization failed. Output: ") + output);
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
        String output = ctx.executeCommand(cmd + " 2>&1");

        ctx.executeCommandWithExitStatus("systemctl daemon-reload");
        String serviceName = DamengRemoteFields.SERVICE_PREFIX + instanceName;
        ctx.executeCommandWithExitStatus("systemctl enable " + serviceName + ".service 2>/dev/null || true");
    }

    private static void startAndSetPassword(RemoteInstallExecutionContext ctx) throws Exception {
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String instanceName = ctx.fieldValue(DamengRemoteFields.INSTANCE_NAME);
        String port = ctx.fieldValue(DamengRemoteFields.PORT);
        String newPassword = ctx.fieldValue(DamengRemoteFields.SYSDBA_PASSWORD);
        String serviceName = DamengRemoteFields.SERVICE_PREFIX + instanceName;
        String userName = DamengRemoteFields.USER_NAME;

        // Start service
        if (ctx.executeCommandWithExitStatus("systemctl start " + serviceName + ".service 2>/dev/null") != 0) {
            // Try starting directly as dmdba user
            String dmIni = ctx.fieldValue(DamengRemoteFields.DATA_PATH) + "/" + instanceName + "/dm.ini";
            ctx.executeCommandWithExitStatus(
                    "su - " + userName + " -c " +
                    ctx.shellQuote(findBin(installPath, ctx, "dmserver") + " " + dmIni) +
                    " &>/dev/null &");
        }

        // Wait for instance to be ready (poll with disql, up to 120 seconds)
        String disql = findBin(installPath, ctx, "disql");
        String waitScript =
                "for i in $(seq 1 60); do " +
                "  " + ctx.shellQuote(disql) + " SYSDBA/" + DamengRemoteFields.DEFAULT_PASSWORD +
                "@localhost:" + port + " -e \"select 1 from dual\" >/dev/null 2>&1 && break; " +
                "  sleep 2; " +
                "done; " +
                ctx.shellQuote(disql) + " SYSDBA/" + DamengRemoteFields.DEFAULT_PASSWORD +
                "@localhost:" + port + " -e \"select 1 from dual\" >/dev/null 2>&1 && echo 'READY' || echo 'TIMEOUT'";
        String result = ctx.executeCommand(waitScript).trim();

        if ("TIMEOUT".equals(result)) {
            throw new Exception(I18n.t("remote.install.dameng.error.start_service_failed",
                    "Dameng service did not become ready within 120 seconds."));
        }

        // Change SYSDBA password
        String alterPwdScript = disql + " SYSDBA/" + DamengRemoteFields.DEFAULT_PASSWORD +
                "@localhost:" + port + " <<'DM_EOF'\n" +
                "ALTER USER SYSDBA IDENTIFIED BY \"" + newPassword.replace("\"", "\\\"") + "\";\n" +
                "EXIT;\n" +
                "DM_EOF";
        String alterOutput = ctx.executeCommand(alterPwdScript + " 2>&1");
        if (alterOutput.contains("error") || alterOutput.contains("ERROR") || alterOutput.contains("fail")) {
            ctx.executeCommand("echo 'WARN: Failed to change SYSDBA password: " + alterOutput + "'");
        }
    }

    private static void installSampleData(RemoteInstallExecutionContext ctx) throws Exception {
        String installPath = ctx.fieldValue(DamengRemoteFields.INSTALL_PATH);
        String port = ctx.fieldValue(DamengRemoteFields.PORT);
        String password = ctx.fieldValue(DamengRemoteFields.SYSDBA_PASSWORD);
        String disql = findBin(installPath, ctx, "disql");

        // Look for sample scripts in common locations
        String[] sampleDirs = {
                installPath + "/samples",
                installPath + "/sample",
                installPath + "/demo",
                installPath + "/web/samples"
        };

        boolean foundSample = false;
        for (String dir : sampleDirs) {
            if (ctx.executeCommandWithExitStatus("test -d " + ctx.shellQuote(dir)) == 0) {
                String[] possibleScripts = {"dmhr_cre.sql", "DMHR_CRE.sql", "sample.sql", "demo.sql"};
                for (String script : possibleScripts) {
                    String scriptPath = dir + "/" + script;
                    if (ctx.executeCommandWithExitStatus("test -f " + ctx.shellQuote(scriptPath)) == 0) {
                        String runSample = disql + " SYSDBA/" + password.replace("\"", "\\\"") +
                                "@localhost:" + port + " <<'DM_SAMPLE_EOF'\n" +
                                "START " + scriptPath + ";\n" +
                                "EXIT;\n" +
                                "DM_SAMPLE_EOF";
                        ctx.executeCommand(runSample + " 2>&1 || true");
                        foundSample = true;
                        break;
                    }
                }
            }
            if (foundSample) break;
        }

        if (foundSample) {
            for (String dir : sampleDirs) {
                if (ctx.executeCommandWithExitStatus("test -d " + ctx.shellQuote(dir)) == 0) {
                    String[] dataScripts = {"dmhr_data.sql", "DMHR_DATA.sql", "sample_data.sql", "demo_data.sql"};
                    for (String script : dataScripts) {
                        String scriptPath = dir + "/" + script;
                        if (ctx.executeCommandWithExitStatus("test -f " + ctx.shellQuote(scriptPath)) == 0) {
                            String runData = disql + " SYSDBA/" + password.replace("\"", "\\\"") +
                                    "@localhost:" + port + " <<'DM_DATA_EOF'\n" +
                                    "START " + scriptPath + ";\n" +
                                    "EXIT;\n" +
                                    "DM_DATA_EOF";
                            ctx.executeCommand(runData + " 2>&1 || true");
                            break;
                        }
                    }
                }
            }
        }
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
        String result = ctx.executeCommand("find " + ctx.shellQuote(installPath) + " -name '" + binName + "' -type f 2>/dev/null | head -1").trim();
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
