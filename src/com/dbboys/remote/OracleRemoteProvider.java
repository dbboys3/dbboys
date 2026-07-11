package com.dbboys.remote;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public final class OracleRemoteProvider implements RemoteDatabaseProvider {
    @Override
    public String id() {
        return "oracle";
    }

    @Override
    public String displayName() {
        return "Oracle";
    }

    @Override
    public boolean supportsPackageDownload() {
        return true;
    }

    @Override
    public String resolveDownloadUrl(String systemInfoText) {
        if (systemInfoText == null) {
            return null;
        }
        if (systemInfoText.contains("x86_64")) {
            return "https://www.dbboys.com/dl/oracle/server/x86/latest.zip";
        }
        return null;
    }

    @Override
    public List<String> installWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.install.oracle.desc.item1", "1. Remote install supports Linux/Unix only (RHEL/CentOS 7+ recommended), not Windows."),
                I18n.t("remote.install.oracle.desc.item2", "2. Prepare an Oracle 11g/12c/18c/19c/21c Linux x86_64 installation package (zip or rpm), or download the matched package automatically."),
                I18n.t("remote.install.oracle.desc.item3", "3. The wizard auto-detects the Oracle version from the package name, uses silent install (runInstaller -silent for 11g/12c or rpm for 18c+), creates the database with DBCA, configures the listener, and sets up systemd auto-start."),
                I18n.t("remote.install.oracle.desc.item4", "4. Existing Oracle installations by this wizard will be removed before installation.")
        );
    }

    @Override
    public List<String> uninstallWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.uninstall.oracle.desc.item1", "1. Remote uninstall supports Linux/Unix only, not Windows."),
                I18n.t("remote.uninstall.oracle.desc.item2", "2. Remote uninstall auto-detects the installed Oracle version, stops the database/listener, removes services, Oracle installation directories, and the oracle user/group.")
        );
    }

    @Override
    public String localPackageHintText() {
        return I18n.t("remote.install.oracle.package.local_hint", "Select an Oracle 11g/12c/18c/19c/21c Linux x86_64 installation package (zip for 11g/12c, rpm for 18c+).");
    }

    @Override
    public boolean isPackageCompatible(String systemInfoText, String packagePath) {
        if (packagePath == null || packagePath.isBlank()) {
            return true;
        }
        String lower = packagePath.toLowerCase();
        if (systemInfoText != null && systemInfoText.contains("x86_64")) {
            return !lower.contains("aarch64") && !lower.contains("arm64");
        }
        if (systemInfoText != null && systemInfoText.contains("aarch64")) {
            return false;
        }
        // Accept any Oracle package format
        if (lower.endsWith(".zip") || lower.endsWith(".rpm") || lower.endsWith(".tar")
                || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return true;
        }
        // Also accept the download URL filenames
        return lower.contains("oracle") || lower.contains("latest");
    }

    /**
     * Parse the package name and return version info: major version, and package format.
     * Returns int[2]: [majorVersion, formatCode]
     * formatCode: 1=zip (11g/12c runInstaller), 2=rpm (18c+), 3=tar (pre-installed)
     */
    static int[] detectOraclePackage(String packagePath) {
        if (packagePath == null || packagePath.isBlank()) {
            return new int[]{0, 2}; // version unknown, default to rpm format
        }
        String name = new java.io.File(packagePath).getName().toLowerCase();

        int major = 0; // version unknown by default
        int format = 2; // default rpm

        // Detect major version from name: 11g, 12c, 18c, 19c, 21c, 23ai
        if (name.contains("11g") || name.contains("11.") || name.contains("11r") || name.contains("11gr")) {
            major = 11;
        } else if (name.contains("12c") || name.contains("12.") || name.contains("12r")) {
            major = 12;
        } else if (name.contains("18c") || name.contains("18.")) {
            major = 18;
        } else if (name.contains("19c") || name.contains("19.")) {
            major = 19;
        } else if (name.contains("21c") || name.contains("21.")) {
            major = 21;
        } else if (name.contains("23ai") || name.contains("23.")) {
            major = 23;
        }

        // Detect package format
        if (name.endsWith(".zip")) {
            format = 1; // zip — runInstaller (11g, 12c)
        } else if (name.endsWith(".rpm")) {
            format = 2; // rpm — yum/dnf install (18c+)
        } else {
            format = 3; // tarball — pre-installed binaries
        }

        return new int[]{major, format};
    }

    static String inferOracleHome(String packagePath, String currentValue) {
        String home = currentValue == null ? "" : currentValue.trim();
        if (home.isEmpty() || "/u01/app/product/19c/dbhome_1".equals(home)
                || "/u01/app/product/18c/dbhome_1".equals(home)
                || "/u01/app/product/12c/dbhome_1".equals(home)
                || "/u01/app/product/11g/dbhome_1".equals(home)
                || "/u01/app/product/any/dbhome_1".equals(home)) {
            int[] info = detectOraclePackage(packagePath);
            int major = info[0];
            return switch (major) {
                case 11 -> "/u01/app/product/11g/dbhome_1";
                case 12 -> "/u01/app/product/12c/dbhome_1";
                case 18 -> "/u01/app/product/18c/dbhome_1";
                case 19 -> "/u01/app/product/19c/dbhome_1";
                case 21 -> "/u01/app/product/21c/dbhome_1";
                case 23 -> "/u01/app/product/23ai/dbhome_1";
                default -> "/u01/app/product/any/dbhome_1";
            };
        }
        return home;
    }

    @Override
    public List<RemoteInstallField> buildDefaultInstallFields(RemoteHostProfile hostProfile) {
        double totalMemGb = hostProfile == null ? 0 : hostProfile.getTotalMemoryGb();
        double freeDiskGb = hostProfile == null ? 0 : hostProfile.getFreeDiskSizeGb();

        int memoryMb = totalMemGb >= 16 ? 4096 : totalMemGb >= 8 ? 2048 : totalMemGb >= 4 ? 1024 : 512;
        String dataDir = freeDiskGb >= 50 ? "/u01/oradata" : "/u01/app/oradata";

        List<RemoteInstallField> fields = new ArrayList<>();
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_ROOT_PASSWORD,
                I18n.t("remote.install.oracle.cfg.root_password.name", "Root Password"),
                "",
                I18n.t("remote.install.oracle.cfg.root_password.desc", "The root password of the remote server, used to create the oracle user and configure kernel parameters.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_ORACLE_BASE,
                "ORACLE_BASE", "/u01/app",
                I18n.t("remote.install.oracle.cfg.oracle_base.desc", "Oracle base directory. ORACLE_HOME is created under this.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_ORACLE_HOME,
                "ORACLE_HOME", "/u01/app/product/any/dbhome_1",
                I18n.t("remote.install.oracle.cfg.oracle_home.desc", "Oracle home directory where binaries are installed.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_DATA_DIR,
                I18n.t("remote.install.oracle.cfg.data_dir.name", "Data Directory"),
                dataDir,
                I18n.t("remote.install.oracle.cfg.data_dir.desc", "Directory for Oracle data files. Ensure sufficient disk space.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_SID,
                I18n.t("remote.install.oracle.cfg.sid.name", "SID"),
                "ORCL",
                I18n.t("remote.install.oracle.cfg.sid.desc", "Oracle System Identifier (SID). The unique database instance name.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_SYS_PASSWORD,
                I18n.t("remote.install.oracle.cfg.sys_password.name", "SYS Password"),
                RemotePasswordUtil.generateComplexPassword(),
                I18n.t("remote.install.oracle.cfg.sys_password.desc", "Password for the SYS super-user account. Keep it strong.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_SYSTEM_PASSWORD,
                I18n.t("remote.install.oracle.cfg.system_password.name", "SYSTEM Password"),
                RemotePasswordUtil.generateComplexPassword(),
                I18n.t("remote.install.oracle.cfg.system_password.desc", "Password for the SYSTEM account. Keep it strong.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_CHARACTER_SET,
                I18n.t("remote.install.oracle.cfg.charset.name", "Character Set"),
                "AL32UTF8",
                I18n.t("remote.install.oracle.cfg.charset.desc", "Database character set (AL32UTF8 for full Unicode).")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_NATIONAL_CHARACTER_SET,
                I18n.t("remote.install.oracle.cfg.ncharset.name", "National Character Set"),
                "AL16UTF16",
                I18n.t("remote.install.oracle.cfg.ncharset.desc", "National character set for NCHAR/NVARCHAR2 columns.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_LISTENER_PORT,
                I18n.t("remote.install.oracle.cfg.port.name", "Listener Port"),
                "1521",
                I18n.t("remote.install.oracle.cfg.port.desc", "Oracle Net Listener port (default 1521).")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_MEMORY_MB,
                I18n.t("remote.install.oracle.cfg.memory.name", "Memory (MB)"),
                String.valueOf(memoryMb),
                I18n.t("remote.install.oracle.cfg.memory.desc", "Total memory target in MB for the Oracle instance (SGA + PGA).")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_DEFAULT_TABLESPACE_DATAFILE,
                I18n.t("remote.install.oracle.cfg.tablespace.name", "Default Tablespace Datafile"),
                dataDir + "/orcl/system01.dbf",
                I18n.t("remote.install.oracle.cfg.tablespace.desc", "Datafile path for the default permanent tablespace.")));
        fields.add(new RemoteInstallField(OracleRemoteFields.ORACLE_RECOVERY_AREA,
                I18n.t("remote.install.oracle.cfg.recovery.name", "Recovery Area"),
                "/u01/app/fast_recovery_area",
                I18n.t("remote.install.oracle.cfg.recovery.desc", "Fast recovery area for archived logs and backups.")));
        return fields;
    }

    @Override
    public List<RemoteInstallStepSpec> buildInstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.install.oracle.step1.name", "Remove Existing Oracle",
                        "remote.install.oracle.step1.desc",
                        "Stop existing database/listener, remove oracle user, wipe all Oracle directories and config files.", true, true),
                new RemoteInstallStepSpec("remote.install.oracle.step2.name", "Create User And Directories",
                        "remote.install.oracle.step2.desc",
                        "Create oracle OS user, oinstall/dba/oper groups, directory structure, and configure kernel parameters.", true, true),
                new RemoteInstallStepSpec("remote.install.oracle.step3.name", "Prepare Packages",
                        "remote.install.oracle.step3.desc",
                        "For zip packages, extract Oracle installation files into /u01/app/staging. For RPM packages, check yum/dnf repositories and prepare for installation.", true, true),
                new RemoteInstallStepSpec("remote.install.oracle.step4.name", "Install Binaries",
                        "remote.install.oracle.step4.desc",
                        "Run Oracle Universal Installer in silent mode (11g/12c) or rpm install (18c+).", true, true),
                new RemoteInstallStepSpec("remote.install.oracle.step5.name", "Run Root Scripts",
                        "remote.install.oracle.step5.desc",
                        "Execute root.sh to set ownership and permissions on Oracle binaries.", true, true),
                new RemoteInstallStepSpec("remote.install.oracle.step6.name", "Create Database",
                        "remote.install.oracle.step6.desc",
                        "Create the database with DBCA in silent mode using a response file.", true, false),
                new RemoteInstallStepSpec("remote.install.oracle.step7.name", "Configure Services",
                        "remote.install.oracle.step7.desc",
                        "Configure netca listener, oracle user bash_profile, and systemd service unit.", true, false),
                new RemoteInstallStepSpec("remote.install.oracle.step8.name", "Enable Autostart",
                        "remote.install.oracle.step8.desc",
                        "Enable the systemd oracle.service for automatic startup on system boot.", true, false)
        );
    }

    @Override
    public List<RemoteInstallStepSpec> buildUninstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.uninstall.oracle.step1.name", "Stop Database And Listener",
                        "remote.uninstall.oracle.step1.desc",
                        "Auto-detect installed version, shutdown the Oracle database and stop the listener.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.oracle.step2.name", "Remove Service And Config",
                        "remote.uninstall.oracle.step2.desc",
                        "Disable and remove the systemd oracle.service and /etc/oratab entries.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.oracle.step3.name", "Remove Directories",
                        "remote.uninstall.oracle.step3.desc",
                        "Remove ORACLE_HOME, ORACLE_BASE, data directories, and temporary installation files.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.oracle.step4.name", "Remove User And Groups",
                        "remote.uninstall.oracle.step4.desc",
                        "Delete oracle OS user and the oinstall, dba, oper groups.", true, true)
        );
    }

    @Override
    public void executeInstallStep(int stepNo, RemoteInstallExecutionContext context) throws Exception {
        OracleRemoteWorkflow.executeInstallStep(stepNo, context);
    }

    @Override
    public void afterInstallSteps(RemoteInstallExecutionContext context) throws Exception {
        OracleRemoteWorkflow.afterInstallSteps(context);
    }

    @Override
    public void populateInstallResult(RemoteInstallExecutionContext context, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        OracleRemoteWorkflow.populateInstallResult(context, databaseInfoArea);
    }

    @Override
    public Connect buildInstalledConnect(RemoteInstallExecutionContext context) {
        return OracleRemoteWorkflow.buildInstalledConnect(context);
    }

    @Override
    public void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext context) throws Exception {
        OracleRemoteWorkflow.executeUninstallStep(stepNo, context);
    }

    @Override
    public void startInstallWizard(Stage parent) {
        RemoteInstallerUtil.startWizard(parent, this);
    }

    @Override
    public void startUninstallWizard(Stage parent) {
        RemoteUninstallerUtil.startWizard(parent, this);
    }
}
