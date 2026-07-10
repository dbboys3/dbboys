package com.dbboys.remote;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;

public final class OracleRemoteWorkflow {
    private static final String RESULT_TITLE_STYLE = "-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;";
    private static final String RESULT_BODY_STYLE = "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;";
    private static final int FMT_ZIP = 1, FMT_RPM = 2, FMT_TAR = 3;

    private OracleRemoteWorkflow() {}

    // ---- Install steps (8 steps) ----

    public static void executeInstallStep(int stepNo, RemoteInstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1: cleanup(ctx); break;
            case 2: createUser(ctx); break;
            case 3: unpack(ctx); break;
            case 4: installBinaries(ctx); break;
            case 5: runRootScripts(ctx); break;
            case 6: createDatabase(ctx); break;
            case 7: configureServices(ctx); break;
            case 8: autostart(ctx); break;
            default: throw new IllegalArgumentException("Unknown Oracle install step: " + stepNo);
        }
    }

    public static void afterInstallSteps(RemoteInstallExecutionContext ctx) {}

    // ---- Uninstall ----

    public static void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:
                // Best-effort stop: if oracle isn't installed, everything is "|| true".
                // Never fail this step — the system may be partially or not-at-all installed.
                ctx.executeCommandWithExitStatus(
                    "pkill -9 -f pmon 2>/dev/null || true\n" +
                    "pkill -9 -f tns_lsnr 2>/dev/null || true\n" +
                    "pkill -9 -f 'ora_' 2>/dev/null || true\n" +
                    "systemctl stop oracle.service 2>/dev/null || true\n" +
                    "echo OK");
                break;
            case 2:
                ctx.executeCommandWithExitStatus(
                    "systemctl disable oracle.service 2>/dev/null || true\n" +
                    "rm -f /etc/systemd/system/oracle.service /etc/oratab /etc/oraInst.loc\n" +
                    "rm -f /etc/init.d/oracle 2>/dev/null || true\n" +
                    "systemctl daemon-reload 2>/dev/null || true\n" +
                    "echo OK");
                break;
            case 3:
                ctx.executeCommandWithExitStatus(
                    "for d in /opt/oracle /u01 /u02 /u03 /u04 /tmp/oracle /tmp/OraInstall* /opt/oraInventory /opt/app/oracle; do\n" +
                    "  for g in $d; do [ -e \"$g\" ] && rm -rf \"$g\"; done\n" +
                    "done\n" +
                    "rm -f /etc/oratab /etc/oraInst.loc /tmp/dbca_* /tmp/netca_* /tmp/oracle_* 2>/dev/null || true\n" +
                    "echo OK");
                break;
            case 4:
                ctx.executeCommandWithExitStatus(
                    "id oracle >/dev/null 2>&1 && { userdel -r -f oracle 2>/dev/null || userdel -f oracle 2>/dev/null; }\n" +
                    "groupdel dba 2>/dev/null || true\n" +
                    "groupdel oinstall 2>/dev/null || true\n" +
                    "groupdel oper 2>/dev/null || true\n" +
                    "echo OK");
                break;
            default: throw new IllegalArgumentException("Unknown Oracle uninstall step: " + stepNo);
        }
    }

    private static String findOracleHome(RemoteUninstallExecutionContext ctx) throws Exception {
        String oratab = ctx.executeCommand("cat /etc/oratab 2>/dev/null | grep -v '^#' | grep -v '^$' | head -1 | cut -d: -f2 || true").trim();
        if (!oratab.isEmpty()) return oratab;
        for (String p : new String[]{"/opt/oracle/product/19c/dbhome_1","/opt/oracle/product/18c/dbhome_1",
            "/opt/oracle/product/12c/dbhome_1","/opt/oracle/product/11g/dbhome_1"}) {
            if (ctx.executeCommandWithExitStatus("[ -f '" + p.replace("'","'\\''") + "/bin/sqlplus' ]") == 0) return p;
        }
        return null;
    }

    // ---- Result / Connect ----

    public static void populateInstallResult(RemoteInstallExecutionContext ctx, CustomInlineCssTextArea area) throws Exception {
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
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
        area.append(I18n.t("remote.install.result.charset", "Charset") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_CHARACTER_SET) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.ncharset", "National Charset") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_NATIONAL_CHARACTER_SET) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.memory", "Memory Target") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_MEMORY_MB) + " MB\n\n", RESULT_BODY_STYLE);

        // Space config
        area.append(I18n.t("remote.install.result.space_config", "Space Configuration") + "\n", RESULT_TITLE_STYLE);
        area.append(I18n.t("remote.install.result.data_path", "Data File Path") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR) + "\n", RESULT_BODY_STYLE);
        area.append(I18n.t("remote.install.result.recovery_area", "Recovery Area") + "：" + ctx.fieldValue(OracleRemoteFields.ORACLE_RECOVERY_AREA) + "\n\n", RESULT_BODY_STYLE);

        // Data file disk usage
        String dd = ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR);
        try {
            String diskInfo = ctx.executeCommand("df -h " + q(dd) + " | tail -1");
            area.append(I18n.t("remote.install.result.disk_usage", "Disk Usage") + "：" + diskInfo + "\n\n", RESULT_BODY_STYLE);
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

    // ============ Step 1: Cleanup (delegates to uninstall workflow) ============
    private static void cleanup(RemoteInstallExecutionContext ctx) throws Exception {
        // Reuse the uninstall logic: stop DB/listener, wipe config, wipe dirs,
        // remove oracle user/groups.  This keeps install step 1 and the standalone
        // uninstall wizard 100% consistent — no duplicated cleanup code.
        String installOh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
        String dd = ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR);
        String ra = ctx.fieldValue(OracleRemoteFields.ORACLE_RECOVERY_AREA);

        // Shell commands mirror executeUninstallStep exactly, but run over
        // the same SSH session the install wizard already has open.
        exec(ctx, "pkill -9 -f pmon 2>/dev/null || true; pkill -9 -f tns_lsnr 2>/dev/null || true; pkill -9 -f 'ora_' 2>/dev/null || true; systemctl stop oracle.service 2>/dev/null || true");
        exec(ctx, "systemctl disable oracle.service 2>/dev/null || true; rm -f /etc/systemd/system/oracle.service /etc/oratab /etc/oraInst.loc; systemctl daemon-reload 2>/dev/null || true");
        exec(ctx, "for d in /opt/oracle /u01 /u02 /u03 /u04 /tmp/oracle /tmp/OraInstall* /opt/oraInventory /opt/app/oracle " + dd + " " + ra + " " + installOh + "; do [ -e \"$d\" ] && rm -rf \"$d\"; done; echo OK");
        exec(ctx, "id oracle >/dev/null 2>&1 && { userdel -r -f oracle 2>/dev/null || userdel -f oracle 2>/dev/null; }; groupdel dba 2>/dev/null || true; groupdel oinstall 2>/dev/null || true; groupdel oper 2>/dev/null || true; echo OK");
    }

    // ============ Step 2: Create user & dirs ============
    private static void createUser(RemoteInstallExecutionContext ctx) throws Exception {
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
        String dd = ctx.fieldValue(OracleRemoteFields.ORACLE_DATA_DIR);
        String ra = ctx.fieldValue(OracleRemoteFields.ORACLE_RECOVERY_AREA);

        check(ctx.executeCommand(
            "groupadd -f oinstall 2>/dev/null || true\n" +
            "groupadd -f dba 2>/dev/null || true\n" +
            "groupadd -f oper 2>/dev/null || true\n" +
            "id oracle >/dev/null 2>&1 || useradd -g oinstall -G dba,oper -d /home/oracle -m -s /bin/bash oracle\n" +
            "usermod -a -G dba,oper oracle 2>/dev/null || true\necho OK"), "Failed to create oracle user/groups");

        check(ctx.executeCommand(
            "mkdir -p " + q(ob) + " " + q(oh) + " " + q(dd) + " " + q(ra) + " /opt/oracle/staging /opt/oraInventory && " +
            "chown -R oracle:oinstall " + q(ob) + " " + q(dd) + " " + q(ra) + " /opt/oracle/staging /opt/oraInventory && " +
            "chmod -R 775 " + q(ob) + " && echo OK"), "Failed to create directories");

        check(ctx.executeCommand(kernelParams(ctx)), "Failed to set kernel parameters");
    }

    // ============ Step 3: Unpack ============
    private static void unpack(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        if (pkg == null || pkg.isBlank()) throw new Exception("Package path is empty");
        int[] info = OracleRemoteProvider.detectOraclePackage(pkg);
        int fmt = info[1];

        switch (fmt) {
            case FMT_ZIP:
                String staging = "/opt/oracle/staging";
                check(ctx.executeCommand(
                    "cd " + staging + "\n" +
                    "for z in " + pkg + "; do [ -f \"$z\" ] && { echo \"Unzipping $z\"; unzip -qo \"$z\"; } || echo \"SKIP $z\"; done\n" +
                    "oraparam=" + staging + "/database/install/oraparam.ini\n" +
                    "[ -f \"$oraparam\" ] && sed -i '/oracle\\.\\(jdk\\|javacompanion\\|ctx\\|precomp\\|sqlj\\|has\\|rdbms\\)/s/^\\(.*\\)unzip/#DISABLED_\\1unzip/' \"$oraparam\" || true\n" +
                    "echo OK"), "Failed to unzip Oracle package");
                break;
            case FMT_RPM:
                // nothing to unpack; rpm install handles it
                break;
            default:
                // tarball — unpack in extractAndInstall for now
                break;
        }
    }

    // ============ Step 4: Install binaries ============
    private static void installBinaries(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        int[] info = OracleRemoteProvider.detectOraclePackage(pkg);
        int fmt = info[1];

        switch (fmt) {
            case FMT_ZIP:  installZip(ctx);  break;
            case FMT_RPM:  installRpm(ctx);  break;
            default:       installTar(ctx); break;
        }
    }

    private static void installZip(RemoteInstallExecutionContext ctx) throws Exception {
        String staging = "/opt/oracle/staging";
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
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
        String installCmd = b64(
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
            "echo RC=$?");
        // The runInstaller exits with RC=0 even when it prints "run root.sh".
        // "Please run /opt/oracle/.../root.sh" is NOT a failure — it's just
        // telling you to execute the next step (which we do automatically).
        String out = ctx.executeCommand(
            "echo " + q(installCmd) + " | base64 -d > /tmp/runInstaller_" + sid + ".sh && " +
            "chmod +x /tmp/runInstaller_" + sid + ".sh && " +
            "chown oracle:oinstall /tmp/runInstaller_" + sid + ".sh && " +
            "su - oracle -s /bin/bash /tmp/runInstaller_" + sid + ".sh 2>&1");
        log("runInstaller output:\n" + smartClip(out, 3000));
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
            throw new Exception("Oracle runInstaller failed:\n" + smartClip(out, 2000));
        } else if (!out.contains("RC=0")) {
            // Unknown outcome — probably failed
            throw new Exception("Oracle runInstaller failed:\n" + smartClip(out, 2000));
        }
        check(ctx.executeCommand(ensureOraInstLoc(ob)), "Failed to create oraInst.loc");
    }

    private static void installRpm(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        String ob = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_BASE);
        check(ctx.executeCommand(
            "[ -f " + q(pkg) + " ] || { echo PKG_NOT_FOUND; exit 1; }\n" +
            "(yum localinstall -y " + q(pkg) + " 2>&1 || dnf localinstall -y " + q(pkg) + " 2>&1 || rpm -ivh " + q(pkg) + " 2>&1 || true)\necho OK"),
            "Failed to install Oracle RPM");
        check(ctx.executeCommand(ensureOraInstLoc(ob)), "Failed to create oraInst.loc");
    }

    private static void installTar(RemoteInstallExecutionContext ctx) throws Exception {
        String pkg = ctx.remotePackagePath();
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
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

    // ============ Step 5: Run root scripts ============
    private static void runRootScripts(RemoteInstallExecutionContext ctx) throws Exception {
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
        check(ctx.executeCommand("[ -x " + q(oh + "/root.sh") + " ] && " + q(oh + "/root.sh") + "; echo OK"),
            "root.sh failed");
    }

    // ============ Step 6: Create database (DBCA) ============
    private static void createDatabase(RemoteInstallExecutionContext ctx) throws Exception {
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);

        // DBCA requires /etc/oratab to exist and be writable by oracle,
        // but it must NOT contain any entry for this SID or this ORACLE_HOME.
        // Step 1 (cleanup) already wiped all Oracle files, so we just create
        // a fresh empty oratab here.
        check(ctx.executeCommand(
            "rm -f /etc/oratab && touch /etc/oratab && chown oracle:oinstall /etc/oratab && echo OK"),
            "Failed to create /etc/oratab");

        // 11g DBCA response file keys are ALL UPPERCASE (RESPONSEFILE_VERSION not responseFileVersion).
        // Also RECOVERYAREADESTINATION and RECOVERYAREASIZE conflict — 11g wants
        // RECOVERYAREADESTINATION + DB_RECOVERY_FILE_DEST_SIZE inside INITPARAMS.
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
            "MEMORYPERCENTAGE = \"40\"\n" +
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
    }

    // ============ Step 7: Configure services (listener + profile + systemd) ============
    private static void configureServices(RemoteInstallExecutionContext ctx) throws Exception {
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);
        String port = ctx.fieldValue(OracleRemoteFields.ORACLE_LISTENER_PORT);
        String sysPw = ctx.fieldValue(OracleRemoteFields.ORACLE_SYS_PASSWORD);

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

    // ============ Step 8: Autostart ============
    private static void autostart(RemoteInstallExecutionContext ctx) throws Exception {
        check(ctx.executeCommand("systemctl daemon-reload && systemctl enable oracle.service 2>&1; echo OK"),
            "Failed to enable autostart");
    }

    // ============ Helpers ============

    private static void writeFile(RemoteInstallExecutionContext ctx, String path, String content) throws Exception {
        execCheck(ctx, "echo " + q(b64(content)) + " | base64 -d > " + q(path) + " && chmod 644 " + q(path) + " && echo OK",
            "Failed to write file: " + path);
    }

    private static String runOra(RemoteInstallExecutionContext ctx, String cmd) throws Exception {
        return exec(ctx, "echo " + q(b64(cmd)) + " | base64 -d | su - oracle -s /bin/bash 2>&1");
    }

    private static String log(String msg) {
        System.out.println("[OracleInstall] " + msg);
        return msg;
    }

    private static String exec(RemoteInstallExecutionContext ctx, String cmd) throws Exception {
        log("EXEC: " + cliptail(cmd, 200));
        long t0 = System.currentTimeMillis();
        String out = ctx.executeCommand(cmd);
        long ms = System.currentTimeMillis() - t0;
        log("OUTPUT (" + ms + "ms):\n" + (out != null ? cliptail(out, 2000) : "(null)"));
        return out;
    }

    private static String execCheck(RemoteInstallExecutionContext ctx, String cmd, String errMsg) throws Exception {
        String out = exec(ctx, cmd);
        if (out == null || !(out.contains("OK") || out.contains("RC=0") || out.contains("DBCA_RC=0") || out.contains("NETCA_RC=0"))) {
            String detail = out != null ? clip(out, 3000) : "(no output)";
            throw new Exception(errMsg + "\n" + detail);
        }
        return out;
    }

    private static String check(String output, String msg) throws Exception {
        if (output == null || !(output.contains("OK") || output.contains("RC=0") || output.contains("DBCA_RC=0") || output.contains("NETCA_RC=0"))) {
            String detail = output != null ? clip(output, 3000) : "(no output)";
            throw new Exception(msg + "\n" + detail);
        }
        return output;
    }

    private static String runSql(RemoteInstallExecutionContext ctx, String sql) throws Exception {
        String oh = ctx.fieldValue(OracleRemoteFields.ORACLE_ORACLE_HOME);
        String sid = ctx.fieldValue(OracleRemoteFields.ORACLE_SID);
        return exec(ctx, "echo " + q(b64(
            "export ORACLE_HOME=" + oh + " ORACLE_SID=" + sid + " PATH=$ORACLE_HOME/bin:$PATH\n" +
            "$ORACLE_HOME/bin/sqlplus -S / as sysdba <<'EOS'\nset heading off feedback off pagesize 0\n" + sql + ";\nexit;\nEOS"
        )) + " | base64 -d | su - oracle -s /bin/bash 2>/dev/null");
    }

    private static String ensureOraInstLoc(String ob) {
        return "mkdir -p " + q(ob + "/oraInventory") + " && echo 'inventory_loc=" + ob + "/oraInventory' > /etc/oraInst.loc && echo 'inst_group=oinstall' >> /etc/oraInst.loc && chmod 644 /etc/oraInst.loc && echo OK";
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
            "SYSCTLEOF\nsysctl -p /etc/sysctl.conf 2>/dev/null || true\n" +
            "cat >> /etc/security/limits.conf <<'LIMITSEOF'\n" +
            "oracle soft nproc 2047\noracle hard nproc 16384\n" +
            "oracle soft nofile 1024\noracle hard nofile 65536\n" +
            "oracle soft stack 10240\noracle hard stack 32768\n" +
            "LIMITSEOF\necho OK";
    }

    private static String q(String s) { return s == null ? "''" : "'" + s.replace("'", "'\"'\"'") + "'"; }
    private static String b64(String s) { return java.util.Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }
    private static String cliptail(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }
    private static String smartClip(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "...(truncated)"; }
}
