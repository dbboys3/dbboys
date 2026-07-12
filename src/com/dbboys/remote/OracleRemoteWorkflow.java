package com.dbboys.remote;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class OracleRemoteWorkflow {
    private static final Logger log = LogManager.getLogger(OracleRemoteWorkflow.class);
    private static final String RESULT_TITLE_STYLE = "-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;";
    private static final String RESULT_BODY_STYLE = "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;";
    private static final int FMT_ZIP = 1, FMT_RPM = 2, FMT_TAR = 3;

    private OracleRemoteWorkflow() {}

    // ---- Install steps (11 steps; 1-7 mandatory, 8-11 selectable) ----

    public static void executeInstallStep(int stepNo, RemoteInstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:  cleanup(ctx); break;
            case 2:  checkDiskSpace(ctx); break;
            case 3:  checkYumAndInstallDeps(ctx); break;
            case 4:  createUser(ctx); break;
            case 5:  configureKernel(ctx); break;
            case 6:  unpack(ctx); break;
            case 7:  installBinaries(ctx); break;
            case 8:  createDatabase(ctx); break;
            case 9:  configureListener(ctx); break;
            case 10: registerService(ctx); break;
            case 11: autostart(ctx); break;
            default: throw new IllegalArgumentException("Unknown Oracle install step: " + stepNo);
        }
    }

    public static void afterInstallSteps(RemoteInstallExecutionContext ctx) {}

    // ---- Uninstall ----

    // ============ Install step commands ============

    private static String uninstallStep1Cmd() {
        return "ps -ef | grep -i oracle | grep -v grep | awk '{print \"kill -9 \" $2}' | sh 2>/dev/null\n" +
               "systemctl stop oracle.service 2>/dev/null \n" +
               "echo OK";
    }
    private static String uninstallStep2Cmd() {
        return "ipcs -m 2>/dev/null | grep -i oracle | awk '{print \"ipcrm -m \" $2}' | sh 2>/dev/null \n" +
               "echo OK";
    }
    private static String uninstallStep3Cmd() {
        return "rpm -qa 2>/dev/null | grep oracle-database | xargs -r rpm -e --noscripts --nodeps  2>>/tmp/oracle_uninstall.log \n" +
               "echo OK";
    }
    private static String uninstallStep4Cmd() {
        return "systemctl disable oracle.service 2>/dev/null \n" +
               "rm -f /etc/systemd/system/oracle.service /etc/oratab /etc/oraInst.loc\n" +
               "rm -f /etc/init.d/oracle 2>/dev/null \n" +
               "systemctl daemon-reload 2>/dev/null \n" +
               "find / -user oracle 2>/dev/null -exec rm -rf {} + 2>/dev/null; \n" +
               "echo OK";
    }
    private static String uninstallStep5Cmd() {
        return "id oracle >/dev/null 2>&1 && { userdel -r -f oracle 2>/dev/null || userdel -f oracle 2>/dev/null; }\n" +
               "groupdel dba 2>/dev/null \n" +
               "groupdel oinstall 2>/dev/null \n" +
               "groupdel oper 2>/dev/null \n" +
               "echo OK";
    }
    public static void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1: ctx.executeCommandWithExitStatus(uninstallStep1Cmd()); break;
            case 2: ctx.executeCommandWithExitStatus(uninstallStep2Cmd()); break;
            case 3: ctx.executeCommandWithExitStatus(uninstallStep3Cmd()); break;
            case 4: ctx.executeCommandWithExitStatus(uninstallStep4Cmd()); break;
            case 5: ctx.executeCommandWithExitStatus(uninstallStep5Cmd()); break;
            default: throw new IllegalArgumentException("Unknown Oracle uninstall step: " + stepNo);
        }
    }

    // ---- Result / Connect ----

    public static void populateInstallResult(RemoteInstallExecutionContext ctx, CustomInlineCssTextArea area) throws Exception {
        String oh = resolveOracleHome(ctx);
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);
        area.replaceText("");

        // Database version
        area.append(I18n.t("remote.install.result.db_version", "Database version") + "\n", RESULT_TITLE_STYLE);
        try { area.append(runSql(ctx, "select banner from v$version where rownum=1") + "\n\n", RESULT_BODY_STYLE); } catch (Exception ignored) {}

        // Instance info
        area.append(I18n.t("remote.install.result.db_instance_info", "Instance Information") + "\n", RESULT_TITLE_STYLE);
        area.append(I18n.t("remote.install.result.oracle_home", "ORACLE_HOME") + "：" + oh + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.oracle_base", "ORACLE_BASE") + "：" + ob + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.instance_name", "Instance Name") + "：" + sid + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.listener_port", "Listener Port") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_LISTENER_PORT) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.user_password", "User/Password") + "：system/" + ctx.fieldValue(OracleRemoteFields.ORACLE_SYSTEM_PASSWORD) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.sys_user_password", "SYS User/Password") + "：sys/" + ctx.fieldValue(OracleRemoteFields.ORACLE_SYS_PASSWORD) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.charset", "Charset") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_CHARACTER_SET) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.ncharset", "National Charset") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_NATIONAL_CHARACTER_SET) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.memory", "Memory Target") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_MEMORY_MB) + " MB\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.data_path", "Data File Path") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.recovery_area", "Recovery Area") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_RECOVERY_AREA) + "\n\n", RESULT_BODY_STYLE);


        // Data file disk usage
        // Disk usage (full df -h output)
        try {
            String diskInfo = ctx.executeCommand("df -h");
            area.append(I18n.t("remote.install.result.disk_usage", "Disk Usage") + "\n", RESULT_TITLE_STYLE);
            area.append(diskInfo + "\n\n", RESULT_BODY_STYLE);
        } catch (Exception ignored) {}
        // System info
        area.append(I18n.t("remote.install.info.machine", "Server Model") + "\n", RESULT_TITLE_STYLE);
        area.append(ctx.machineInfo() + "\n\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.info.os", "Operating System") + "\n", RESULT_TITLE_STYLE);
        area.append(ctx.osInfo() + "\n\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.info.kernel", "Kernel Version") + "\n", RESULT_TITLE_STYLE);
        area.append(ctx.kernelInfo() + "\n\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.info.cpu", "CPU Information") + "\n", RESULT_TITLE_STYLE);
        area.append(ctx.cpuInfo() + "\n\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.info.memory", "Memory Information") + "\n", RESULT_TITLE_STYLE);
        area.append(ctx.memoryInfo() + "\n\n", RESULT_BODY_STYLE);
    }

    public static Connect buildInstalledConnect(RemoteInstallExecutionContext ctx) {
        Connect c = new Connect();
        c.setDbtype("ORACLE");
        c.setDriver("ojdbc11-23.26.1.0.0.jar");
        c.setIp(ctx.host());
        c.setPort(ctx.fieldValue(OracleRemoteFields.ORACLE_LISTENER_PORT));
        c.setCatalog(ctx.fieldValue(OracleRemoteFields.ORACLE_SID));
        c.setUsername("system");
        c.setPassword(ctx.fieldValue(OracleRemoteFields.ORACLE_SYSTEM_PASSWORD));
        return c;
    }

    // ============ Step 1: Cleanup (reuse uninstall workflow) ============
    private static void cleanup(RemoteInstallExecutionContext ctx) throws Exception {
        RemoteUninstallExecutionContext uctx = new RemoteUninstallExecutionContext(ctx.getRemoteClient(), ctx.host());
        for (int step = 1; step <= 5; step++) {
                executeUninstallStep(step, uctx);
        }
    }

    // ============ Step 2: Check /opt space >= 10GB ============
    private static void checkDiskSpace(RemoteInstallExecutionContext ctx) throws Exception {
        check(ctx.executeCommand(
            "avail_kb=$(df /opt | awk 'NR==2{print $4}')\n" +
            "[ \"$avail_kb\" -ge 10485760 ] || { echo \"ERROR: /opt has only $((avail_kb/1024/1024))GB free, need at least 10GB\"; exit 1; }\n" +
            "echo OK"), "/opt free space check failed (need >= 10GB)");
    }

    // ============ Step 3: Check yum repos and install dependencies ============
    private static void checkYumAndInstallDeps(RemoteInstallExecutionContext ctx) throws Exception {
        // Install Oracle dependencies via yum/dnf
        check(ctx.executeCommand(
            "if command -v yum >/dev/null 2>&1; then\n" +
            "  PKG_MGR=yum\n" +
            "elif command -v dnf >/dev/null 2>&1; then\n" +
            "  PKG_MGR=dnf\n" +
            "else\n" +
            "  echo 'No yum or dnf package manager found'; exit 1;\n" +
            "fi\n" +
            "$PKG_MGR install -y bc binutils compat-libcap1 gcc gcc-c++ glibc glibc-devel ksh libaio libstdc++ libstdc++-devel make net-tools smartmontools sysstat unixODBC unzip 2>&1\n" +
            "echo OK"), "Failed to install Oracle dependencies via yum/dnf");

        // Check yum/dnf repositories are available
        check(ctx.executeCommand(
            "echo 'Checking yum/dnf repositories'\n" +
            "if command -v yum >/dev/null 2>&1; then\n" +
            "  yum repolist 2>/dev/null | grep -q 'repolist: 0' && { echo 'ERROR: No enabled yum repositories found.'; exit 1; }\n" +
            "elif command -v dnf >/dev/null 2>&1; then\n" +
            "  dnf repolist 2>/dev/null | grep -q 'repolist: 0' && { echo 'ERROR: No enabled dnf repositories found.'; exit 1; }\n" +
            "else\n" +
            "  echo 'ERROR: No yum or dnf package manager found.'; exit 1;\n" +
            "fi\n" +
            "echo OK"), I18n.t("remote.install.oracle.error.no_yum_repos", "No enabled yum repositories found. Please configure a yum source first."));
    }

    // ============ Step 4: Create user and groups ============
    private static void createUser(RemoteInstallExecutionContext ctx) throws Exception {
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        String dd = ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR);
        String ra = ctx.fieldValue(OracleRemoteFields.ORACLE_RECOVERY_AREA);

        check(ctx.executeCommand(
            "groupadd -f oinstall 2>/dev/null \n" +
            "groupadd -f dba 2>/dev/null \n" +
            "groupadd -f oper 2>/dev/null \n" +
            "id oracle >/dev/null 2>&1 || useradd -g oinstall -G dba,oper -d /home/oracle -m -s /bin/bash oracle\n" +
            "usermod -a -G dba,oper oracle 2>/dev/null \necho OK"), "Failed to create oracle user/groups");

        check(ctx.executeCommand(
            "mkdir -p " + q(ob) + " " + q(dd) + " " + q(ra) + " /opt/oracle/staging /opt/oraInventory && " +
            "chown -R oracle:oinstall " + q(ob) + " " + q(dd) + " " + q(ra) + " /opt/oracle/staging /opt/oraInventory && " +
            "chmod -R 775 " + q(ob) + " && echo OK"), "Failed to create directories");
    }

    // ============ Step 5: Configure kernel parameters ============
    private static void configureKernel(RemoteInstallExecutionContext ctx) throws Exception {
        check(ctx.executeCommand(kernelParams(ctx)), "Failed to set kernel parameters");
    }

    // ============ Step 6: Unpack ============
    private static void unpack(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        if (pkg == null || pkg.isBlank()) throw new Exception("Package path is empty");
        int[] info = OracleRemoteProvider.detectOraclePackage(pkg);
        int fmt = info[1];

        // RPM packages don't need unpacking
        if (fmt == FMT_RPM) {
            return;
        }

        switch (fmt) {
            case FMT_ZIP:
                String staging = "/opt/oracle/staging";
                check(ctx.executeCommand(
                    "cd " + staging + "\n" +
                    "for z in " + pkg + "; do [ -f \"$z\" ] && { echo \"Unzipping $z\"; unzip -qo \"$z\"; } || echo \"SKIP $z\"; done\n" +
                    "oraparam=" + staging + "/database/install/oraparam.ini\n" +
                    "[ -f \"$oraparam\" ] && sed -i '/oracle\\.\\(jdk\\|javacompanion\\|ctx\\|precomp\\|sqlj\\|has\\|rdbms\\)/s/^\\(.*\\)unzip/#DISABLED_\\1unzip/' \"$oraparam\" \n" +
                    "echo OK"), "Failed to unzip Oracle package");
                break;
            default:
                // tarball — unpack in installBinaries for now
                break;
        }
    }

    // ============ Step 7: Install binaries + run root scripts ============
    private static void installBinaries(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        int[] info = OracleRemoteProvider.detectOraclePackage(pkg);
        int fmt = info[1];

        switch (fmt) {
            case FMT_ZIP:  installZip(ctx);  break;
            case FMT_RPM:  installRpm(ctx);  break;
            default:       installTar(ctx); break;
        }

        // Run root.sh after binary install
        String oh = resolveOracleHome(ctx);
        check(ctx.executeCommand("[ -x " + q(oh + "/root.sh") + " ] && " + q(oh + "/root.sh") + "; echo OK"),
            "root.sh failed");
    }

    private static void installZip(RemoteInstallExecutionContext ctx) throws Exception {
        String staging = "/opt/oracle/staging";
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        String oh = resolveOracleHome(ctx);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);

        String rspFile = "/tmp/oracle_" + sid + ".rsp";
        writeFile(ctx, rspFile,
            "oracle.install.responseFileVersion=/oracle/install/rspfmt_dbinstall_response_schema_v11_2_0\n" +
            "oracle.install.option=INSTALL_DB_SWONLY\n" +
            "ORACLE_HOSTNAME=" + ctx.host() + "\n" +
            "UNIX_GROUP_NAME=oinstall\n" +
            "INVENTORY_LOCATION=/opt/oraInventory\n" +
            "SELECTED_LANGUAGES=en,zh_CN\n" +
            "ORACLE_HOME=" + oh + "\n" +
            "ORACLE_BASE=" + ob + "\n" +
            "oracle.install.db.InstallEdition=EE\n" +
            "oracle.install.db.DBA_GROUP=dba\n" +
            "oracle.install.db.OPER_GROUP=oper\n" +
            "oracle.install.db.config.starterdb.type=GENERAL_PURPOSE\n" +
            "oracle.install.db.config.starterdb.globalDBName=" + sid + "\n" +
            "oracle.install.db.config.starterdb.SID=" + sid + "\n" +
            "oracle.install.db.config.starterdb.characterSet=" + ctx.fieldValue(OracleRemoteFields.ORACLE_CHARACTER_SET) + "\n" +
            "oracle.install.db.config.starterdb.memoryLimit=" + ctx.fieldValue(OracleRemoteFields.ORACLE_MEMORY_MB) + "\n" +
            "oracle.install.db.config.starterdb.password.ALL=" + ctx.fieldValue(OracleRemoteFields.ORACLE_SYS_PASSWORD) + "\n" +
            "SECURITY_UPDATES_VIA_MYORACLESUPPORT=false\n" +
            "DECLINE_SECURITY_UPDATES=true");

        // Run the silent installer with a CLEAN environment.
        // Oracle 11g's internal JVM crashes with NPE when it encounters
        // garbage env vars like %j %@dPP %mxhsV that leak from the shell.
        // `env -i` starts with an empty environment, then we set only what's needed.
        // The runOra() helper uses `su - oracle` which gives a login shell;
        // we pipe through bash so `env -i` takes effect.
        String installCmd =
            "#!/bin/bash\n" +
            "export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:" + staging + "/database\n" +
            "export HOME=/home/oracle\n" +
            "export TEMP=/tmp\n" +
            "export TMPDIR=/tmp\n" +
            "export CV_ASSUME_DISTID=OEL7\n" +
            "cd /home/oracle\n" +
            "exec env -i PATH=\"$PATH\" HOME=\"$HOME\" TEMP=\"$TEMP\" TMPDIR=\"$TMPDIR\" " +
            "CV_ASSUME_DISTID=\"$CV_ASSUME_DISTID\" " +
            staging + "/database/runInstaller -silent -noconfig -nowait " +
            "-ignorePrereq -ignoreSysPrereqs -waitforcompletion " +
            "-J-Djava.awt.headless=true " +
            "-responseFile " + rspFile + " 2>&1\n" +
            "echo RC=$?";
        // The runInstaller exits with RC=0 even when it prints "run root.sh".
        // "Please run /u01/app/.../root.sh" is NOT a failure — it's just
        // telling you to execute the next step (which we do automatically).
        String out = ctx.executeCommand(
            "cat << 'SCRIPT_EOF' > /tmp/runInstaller_" + sid + ".sh\n" +
            installCmd + "\n" +
            "SCRIPT_EOF\n" +
            "chmod +x /tmp/runInstaller_" + sid + ".sh && " +
            "chown oracle:oinstall /tmp/runInstaller_" + sid + ".sh && " +
            "su - oracle -s /bin/bash /tmp/runInstaller_" + sid + ".sh 2>&1");
        log.info("runInstaller output:\n{}", out);
        // If RC=0 we're good.  If the output contains "root.sh" that's a
        // normal post-install instruction, not an error.  Only fail on actual
        // fatal errors (FATAL, SEVERE with NPE, RC=255, etc.).
        if (out == null) {
            throw new Exception("Oracle runInstaller produced no output");
        }
        if (out.contains("RC=0") || out.contains("successful") || out.contains("Successfully")) {
            // Expected success path
        } else if (out.contains("root.sh") && !out.contains("FATAL") && !out.contains("SEVERE") && !out.contains("RC=255")) {
            // Normal: installer succeeded and is reminding us to run root.sh (next step)
        } else if (out.contains("FATAL") || out.contains("RC=255") || out.contains("RC=254") || out.contains("NullPointerException")) {
            throw new Exception("Oracle runInstaller failed:\n" + out);
        } else if (!out.contains("RC=0")) {
            // Unknown outcome — probably failed
            throw new Exception("Oracle runInstaller failed:\n" + out);
        }
        check(ctx.executeCommand(ensureOraInstLoc(ob)), "Failed to create oraInst.loc");
    }

    private static void installRpm(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        String installScript =
            "set -euo pipefail\n" +
            "echo '--- BEGIN ORACLE RPM INSTALL SCRIPT ---'\n" +
            "pkg_arg=" + q(pkg) + "\n" +
            "echo \"pkg_arg=$pkg_arg\"\n" +
            "set -x\n" +
            "for pkg_path in $pkg_arg; do\n" +
            "  echo \"Processing RPM: $pkg_path\"\n" +
            "  [ -f \"$pkg_path\" ] || { echo \"PKG_NOT_FOUND: $pkg_path\"; exit 1; }\n" +
            "  dir=$(dirname \"$pkg_path\")\n" +
            "  pre=$(find \"$dir\" -maxdepth 1 -type f -iname '*preinstall*.rpm' | sort | head -n 1)\n" +
            "  if [ -n \"$pre\" ]; then\n" +
            "    echo \"Installing preinstall RPM: $pre\"\n" +
            "    if command -v yum >/dev/null 2>&1; then\n" +
            "      yum localinstall -y \"$pre\"\n" +
            "    elif command -v dnf >/dev/null 2>&1; then\n" +
            "      dnf localinstall -y \"$pre\"\n" +
            "    else\n" +
            "      rpm -ivh \"$pre\"\n" +
            "    fi\n" +
            "  fi\n" +
            "  echo \"Installing RPM: $pkg_path\"\n" +
            "  if command -v yum >/dev/null 2>&1; then\n" +
            "    yum localinstall -y \"$pkg_path\"\n" +
            "  elif command -v dnf >/dev/null 2>&1; then\n" +
            "    dnf localinstall -y \"$pkg_path\"\n" +
            "  else\n" +
            "    rpm -ivh \"$pkg_path\"\n" +
            "  fi\n" +
            "done\n" +
            "echo '--- END ORACLE RPM INSTALL SCRIPT ---'\n" +
            "echo OK";
        log.info("RPM install script:\n{}", installScript);
        String out = ctx.executeCommand(
            "cat << 'SCRIPT_EOF' > /tmp/install_oracle_rpm.sh\n" + installScript + "\nSCRIPT_EOF\n" +
            "chmod +x /tmp/install_oracle_rpm.sh && bash -x /tmp/install_oracle_rpm.sh 2>&1");
        log.info("RPM install output:\n{}", out);
        check(out, "Failed to install Oracle RPM");
        check(ctx.executeCommand(ensureOraInstLoc(ob)), "Failed to create oraInst.loc");
    }

    private static void installTar(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        String oh = resolveOracleHome(ctx);
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        String staging = "/opt/oracle/staging";
        check(ctx.executeCommand(
            "[ -f " + q(pkg) + " ] || { echo PKG_NOT_FOUND; exit 1; }\n" +
            "cd " + staging + " && tar xf " + q(pkg) + "\n" +
            "top=$(ls -1d */ 2>/dev/null | head -1)\n" +
            "[ -n \"$top\" ] && rsync -a \"$top\" " + q(oh) + "/ && chown -R oracle:oinstall " + q(oh) + "\n" +
            "echo OK"), "Failed to extract Oracle tarball");
        check(ctx.executeCommand(ensureOraInstLoc(ob)), "Failed to create oraInst.loc");
    }

    // ============ Step 8: Create database (DBCA) ============
    private static void createDatabase(RemoteInstallExecutionContext ctx) throws Exception {
        String oh = resolveOracleHome(ctx);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);

        // DBCA requires /etc/oratab to exist and be writable by oracle,
        // but it must NOT contain any entry for this SID or this ORACLE_HOME.
        // Step 1 (cleanup) already wiped all Oracle files, so we just create
        // a fresh empty oratab here.
        check(ctx.executeCommand(
            "rm -f /etc/oratab && touch /etc/oratab && chown oracle:oinstall /etc/oratab && echo OK"),
            "Failed to create /etc/oratab");

        // 11g/12c uses INI-format response file; 18c+ needs command-line parameters
        boolean isOldDbca = oh != null && (oh.contains("/11g/") || oh.contains("/12c/"));
        if (isOldDbca) {
            String dbcaRsp = "/tmp/dbca_" + sid + ".rsp";
            writeFile(ctx, dbcaRsp,
                "[GENERAL]\n" +
                "RESPONSEFILE_VERSION = \"11.2.0\"\n" +
                "OPERATION_TYPE = \"createDatabase\"\n" +
                "[CREATEDATABASE]\n" +
                "GDBNAME = \"" + sid + "\"\n" +
                "SID = \"" + sid + "\"\n" +
                "TEMPLATENAME = \"General_Purpose.dbc\"\n" +
                "SYSPASSWORD = \"" + ctx.fieldValue(OracleRemoteFields.ORACLE_SYS_PASSWORD) + "\"\n" +
                "SYSTEMPASSWORD = \"" + ctx.fieldValue(OracleRemoteFields.ORACLE_SYSTEM_PASSWORD) + "\"\n" +
                "CHARACTERSET = \"" + ctx.fieldValue(OracleRemoteFields.ORACLE_CHARACTER_SET) + "\"\n" +
                "NATIONALCHARACTERSET = \"" + ctx.fieldValue(OracleRemoteFields.ORACLE_NATIONAL_CHARACTER_SET) + "\"\n" +
                "TOTALMEMORY = \"" + ctx.fieldValue(OracleRemoteFields.ORACLE_MEMORY_MB) + "\"\n" +
                "DATAFILEDESTINATION = \"" + ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR) + "\"\n" +
                "RECOVERYAREADESTINATION = \"" + ctx.fieldValue(OracleRemoteFields.ORACLE_RECOVERY_AREA) + "\"\n" +
                "STORAGETYPE = \"FS\"\n" +
                "DATABASETYPE = \"OLTP\"\n");
            ctx.executeCommand("chown oracle:oinstall " + q(dbcaRsp));
            check(runOra(ctx,
                "export ORACLE_HOME=" + q(oh) + "\n" +
                "export ORACLE_SID=" + q(sid) + "\n" +
                "export PATH=$ORACLE_HOME/bin:$PATH\n" +
                "$ORACLE_HOME/bin/dbca -silent -createDatabase -responseFile " + q(dbcaRsp) + " 2>&1\necho DBCA_RC=$?"),
                "DBCA database creation failed");
        } else {
            // 18c+ DBCA uses command-line parameters (no INI response file)
            check(runOra(ctx,
                "export ORACLE_HOME=" + q(oh) + "\n" +
                "export ORACLE_SID=" + q(sid) + "\n" +
                "export PATH=$ORACLE_HOME/bin:$PATH\n" +
                "$ORACLE_HOME/bin/dbca -silent -createDatabase " +
                "-templateName General_Purpose.dbc " +
                "-gdbName " + sid + " " +
                "-sid " + sid + " " +
                "-sysPassword '" + ctx.fieldValue(OracleRemoteFields.ORACLE_SYS_PASSWORD)+ "' " +
                "-systemPassword '" + ctx.fieldValue(OracleRemoteFields.ORACLE_SYSTEM_PASSWORD) + "' " +
                "-datafileDestination " + q(ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR)) + " " +
                "-recoveryAreaDestination " + q(ctx.fieldValue(OracleRemoteFields.ORACLE_RECOVERY_AREA)) + " " +
                "-characterSet " + ctx.fieldValue(OracleRemoteFields.ORACLE_CHARACTER_SET) + " " +
                "-nationalCharacterSet " + ctx.fieldValue(OracleRemoteFields.ORACLE_NATIONAL_CHARACTER_SET) + " " +
                "-totalMemory " + ctx.fieldValue(OracleRemoteFields.ORACLE_MEMORY_MB) + " " +
                "-storageType FS " +
                "-databaseType OLTP " +
                "-createAsContainerDatabase false " +
                "2>&1\necho DBCA_RC=$?"),
                "DBCA database creation failed");
        }
    }

    // ============ Step 9: Configure listener (netca) ============
    private static void configureListener(RemoteInstallExecutionContext ctx) throws Exception {
        String oh = resolveOracleHome(ctx);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);
        String port = ctx.fieldValue(OracleRemoteFields.ORACLE_LISTENER_PORT);

        // netca — SILENT mode.  Oracle 11g netca IS a GUI tool; -silent
        // suppresses the GUI but still requires the response file syntax exactly.
        // The response file must use the [General] / [oracle.net.ca] section
        // headers that the silent parser expects.  Also unset DISPLAY so the
        // JVM doesn't even try to init AWT.
        String netcaRsp = "/tmp/netca_" + sid + ".rsp";
        writeFile(ctx, netcaRsp,
            "[General]\n" +
            "RESPONSEFILE_VERSION=11.2\n" +
            "CREATE_TYPE=CUSTOM\n" +
            "[oracle.net.ca]\n" +
            "INSTALLED_COMPONENTS={\"server\",\"net8\",\"javavm\"}\n" +
            "INSTALL_TYPE=\"typical\"\n" +
            "LISTENER_NUMBER=1\n" +
            "LISTENER_NAMES={\"LISTENER\"}\n" +
            "LISTENER_PROTOCOLS={\"TCP:" + port + "\"}\n" +
            "LISTENER_START=\"LISTENER\"\n");
        ctx.executeCommand("chown oracle:oinstall " + q(netcaRsp));
        check(runOra(ctx,
            "unset DISPLAY\n" +
            "export ORACLE_HOME=" + q(oh) + "\n" +
            "export PATH=$ORACLE_HOME/bin:$PATH\n" +
            "$ORACLE_HOME/bin/netca /silent /responseFile " + q(netcaRsp) + " 2>&1\n" +
            "echo NETCA_RC=$?"),
            "netca failed");
    }

    // ============ Step 10: Register service (bash_profile + systemd unit + orapwd) ============
    private static void registerService(RemoteInstallExecutionContext ctx) throws Exception {
        String oh = resolveOracleHome(ctx);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);
        String sysPw = ctx.fieldValue(OracleRemoteFields.ORACLE_SYS_PASSWORD);

        // bash_profile
        check(runOra(ctx,
            "touch ~/.bash_profile\n" +
            "grep -q 'ORACLE_HOME=' ~/.bash_profile 2>/dev/null || echo 'export ORACLE_HOME=" + oh + "' >> ~/.bash_profile\n" +
            "grep -q 'ORACLE_SID=' ~/.bash_profile 2>/dev/null || echo 'export ORACLE_SID=" + sid + "' >> ~/.bash_profile\n" +
            "grep -q 'PATH=.*ORACLE_HOME' ~/.bash_profile 2>/dev/null || echo 'export PATH=" + oh + "/bin:$PATH' >> ~/.bash_profile\n" +
            "grep -q 'LD_LIBRARY_PATH=' ~/.bash_profile 2>/dev/null || echo 'export LD_LIBRARY_PATH=" + oh + "/lib:$LD_LIBRARY_PATH' >> ~/.bash_profile\n" +
            "echo OK"), "Failed to configure bash_profile");

        // systemd service unit
        writeFile(ctx, "/etc/systemd/system/oracle.service",
            "[Unit]\nDescription=Oracle Database Service\nAfter=network.target\n\n" +
            "[Service]\nType=forking\nUser=oracle\nGroup=oinstall\n" +
            "Environment=ORACLE_HOME=" + oh + "\n" +
            "Environment=ORACLE_SID=" + sid + "\n" +
            "ExecStart=" + oh + "/bin/dbstart " + oh + "\n" +
            "ExecStop=" + oh + "/bin/dbshut " + oh + "\n" +
            "Restart=on-failure\n\n" +
            "[Install]\nWantedBy=multi-user.target");
        check(ctx.executeCommand("chmod 644 /etc/systemd/system/oracle.service && echo OK"), "Failed to create systemd service");

        // orapwd
        runOra(ctx, oh + "/bin/orapwd file=" + oh + "/dbs/orapw" + sid + " password=" + q(sysPw) + " 2>/dev/null; echo OK");
    }

    // ============ Step 11: Autostart ============
    private static void autostart(RemoteInstallExecutionContext ctx) throws Exception {
        check(ctx.executeCommand("systemctl daemon-reload && systemctl enable oracle.service 2>&1; echo OK"),
            "Failed to enable autostart");
    }

    // ============ Helpers ============

    private static void writeFile(RemoteInstallExecutionContext ctx, String path, String content) throws Exception {
        execCheck(ctx, "cat << 'WRITEEOF' > " + q(path) + "\n" + content + "\nWRITEEOF\nchmod 644 " + q(path) + " && echo OK",
            "Failed to write file: " + path);
    }

    private static String runOra(RemoteInstallExecutionContext ctx, String cmd) throws Exception {
        log.info("[OracleInstall] {}", cmd);
        return exec(ctx, "su - oracle -s /bin/bash 2>&1 << 'ORAEOF'\n" + cmd + "\nORAEOF");
    }

    private static String exec(RemoteInstallExecutionContext ctx, String cmd) throws Exception {
        log.info("EXEC: {}", cmd);
        long t0 = System.currentTimeMillis();
        String out = ctx.executeCommand(cmd);
        long ms = System.currentTimeMillis() - t0;
        log.info("OUTPUT ({}ms):\n{}", ms, out);
        return out;
    }

    private static String execCheck(RemoteInstallExecutionContext ctx, String cmd, String errMsg) throws Exception {
        String out = exec(ctx, cmd);
        if (out == null || !(out.contains("OK") || out.contains("RC=0") || out.contains("DBCA_RC=0") || out.contains("NETCA_RC=0"))) {
            throw new Exception(errMsg + "\n" + out);
        }
        return out;
    }

    private static String check(String output, String msg) throws Exception {
        if (output == null || !(output.contains("OK") || output.contains("RC=0") || output.contains("DBCA_RC=0") || output.contains("NETCA_RC=0"))) {
            //String detail = output != null ? clip(output, 3000) : "(no output)";
            throw new Exception(msg + "\n" + output);
        }
        return output;
    }

    private static String runSql(RemoteInstallExecutionContext ctx, String sql) throws Exception {
        String oh = resolveOracleHome(ctx);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);
        return exec(ctx, "su - oracle -s /bin/bash 2>/dev/null << 'SQLEOF'\n" +
            "export ORACLE_HOME=" + oh + " ORACLE_SID=" + sid + " PATH=$ORACLE_HOME/bin:$PATH\n" +
            "$ORACLE_HOME/bin/sqlplus -S / as sysdba <<'EOS'\nset heading off feedback off pagesize 0\n" + sql + ";\nexit;\nEOS\n" +
            "SQLEOF");
    }

    private static String resolveOracleHome(RemoteInstallExecutionContext ctx) {
        return OracleRemoteProvider.inferOracleHome(ctx.remotePackagePath(), ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME));
    }

    private static String ensureOraInstLoc(String ob) {
        return "mkdir -p " + q(ob + "/oraInventory") + " && echo 'inventory_loc=" + ob + "/oraInventory" + "' > /etc/oraInst.loc && echo 'inst_group=oinstall' >> /etc/oraInst.loc && chmod 644 /etc/oraInst.loc && echo OK";
    }

    private static String kernelParams(RemoteInstallExecutionContext ctx) {
        long mem;
        try { mem = Long.parseLong(ctx.fieldValue(OracleRemoteFields.ORACLE_MEMORY_MB)) * 1024L * 1024L; }
        catch (NumberFormatException e) { mem = 2L * 1024 * 1024 * 1024; }
        return "cat >> /etc/sysctl.conf <<'SYSCTLEOF'\n" +
            "fs.aio-max-nr = 1048576\nfs.file-max = 6815744\n" +
            "kernel.shmall = " + (mem / 4096) + "\nkernel.shmmax = " + mem + "\nkernel.shmmni = 4096\n" +
            "kernel.sem = 250 32000 100 128\nnet.ipv4.ip_local_port_range = 9000 65500\n" +
            "net.core.rmem_default = 262144\nnet.core.rmem_max = 4194304\n" +
            "net.core.wmem_default = 262144\nnet.core.wmem_max = 1048576\n" +
            "SYSCTLEOF\nsysctl -p /etc/sysctl.conf 2>/dev/null \n" +
            "cat >> /etc/security/limits.conf <<'LIMITSEOF'\n" +
            "oracle soft nproc 2047\noracle hard nproc 16384\n" +
            "oracle soft nofile 1024\noracle hard nofile 65536\n" +
            "oracle soft stack 10240\noracle hard stack 32768\n" +
            "LIMITSEOF\necho OK";
    }

    private static String q(String s) { return s == null ? "''" : "'" + s.replace("'", "'\"'\"'") + "'"; }
}
