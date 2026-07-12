package com.dbboys.dialect.mysql;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.*;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public final class MysqlRemoteProvider implements RemoteDatabaseProvider {
    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String displayName() {
        return "MySQL";
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
            return "https://www.dbboys.com/dl/mysql/server/x86/latest.tar";
        }
        if (systemInfoText.contains("aarch64")) {
            return "https://www.dbboys.com/arm/mysql/server/x86/latest.tar";
        }
        return null;
    }

    @Override
    public List<String> installWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.install.mysql.desc.item1", "1. Remote install supports Linux/Unix only, not Windows."),
                I18n.t("remote.install.mysql.desc.item2", "2. Prepare a MySQL 5.7 or MySQL 8 Linux generic tar package, or download the matched package automatically."),
                I18n.t("remote.install.mysql.desc.item3", "3. The wizard extracts the package, initializes the data directory, configures my.cnf, starts mysqld, and sets the root password."),
                I18n.t("remote.install.mysql.desc.item4", "4. Existing MySQL installed by this wizard will be removed before installation.")
        );
    }

    @Override
    public List<String> uninstallWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.uninstall.mysql.desc.item1", "1. Remote uninstall supports Linux/Unix only, not Windows."),
                I18n.t("remote.uninstall.mysql.desc.item2", "2. Remote uninstall stops MySQL, removes the service, installation directory, data directory, and mysql user/group.")
        );
    }

    @Override
    public String localPackageHintText() {
        return I18n.t("remote.install.mysql.package.local_hint", "Select a MySQL 5.7 or MySQL 8 Linux generic tar.gz package.");
    }

    @Override
    public boolean isPackageCompatible(String systemInfoText, String packagePath) {
        if (packagePath == null || packagePath.isBlank()) {
            return true;
        }
        String lower = packagePath.toLowerCase();
        if (!lower.endsWith(".tar") && !lower.endsWith(".tar.gz") && !lower.endsWith(".tgz")
                && !lower.endsWith(".tar.xz") && !lower.endsWith(".txz")) {
            return false;
        }
        if (!lower.contains("mysql") && !lower.contains("latest.tar")) {
            return false;
        }
        if (systemInfoText != null && systemInfoText.contains("x86_64")) {
            return !lower.contains("aarch64") && !lower.contains("arm64");
        }
        if (systemInfoText != null && systemInfoText.contains("aarch64")) {
            return !lower.contains("x86_64") && !lower.contains("amd64");
        }
        if (lower.contains("5.7") || lower.contains("-5.7") || lower.contains("8.0") || lower.contains("-8.") || lower.contains("8.")) {
            return true;
        }
        return lower.contains("linux") || lower.contains("glibc") || lower.contains("latest.tar");
    }

    @Override
    public List<RemoteInstallField> buildDefaultInstallFields(RemoteHostProfile hostProfile) {
        double totalMem = hostProfile == null ? 0 : hostProfile.getTotalMemoryGb();
        String bufferPoolSize = totalMem >= 8 ? "2G" : totalMem >= 4 ? "1G" : "512M";

        List<RemoteInstallField> fields = new ArrayList<>();
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_ROOT_PASSWORD, I18n.t("remote.install.mysql.cfg.root_password.name", "MySQL root password"), RemotePasswordUtil.generateComplexPassword(), I18n.t("remote.install.mysql.cfg.root_password.desc", "Password for root@localhost and root@%. Keep it strong.")));
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_BASEDIR, "basedir", "/usr/local/mysql", I18n.t("remote.install.mysql.cfg.basedir.desc", "MySQL software installation directory.")));
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_DATADIR, "datadir", "/data/mysql", I18n.t("remote.install.mysql.cfg.datadir.desc", "MySQL data directory. It will be created and initialized.")));
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_PORT, I18n.t("remote.install.mysql.cfg.port.name", "Port"), "3306", I18n.t("remote.install.mysql.cfg.port.desc", "MySQL listen port.")));
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_BIND_ADDRESS, I18n.t("remote.install.mysql.cfg.bind_address.name", "Bind address"), "0.0.0.0", I18n.t("remote.install.mysql.cfg.bind_address.desc", "Use 0.0.0.0 to listen on all interfaces.")));
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_CHARSET, I18n.t("remote.install.mysql.cfg.charset.name", "Character set"), "utf8mb4", I18n.t("remote.install.mysql.cfg.charset.desc", "Default server character set.")));
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_COLLATION, I18n.t("remote.install.mysql.cfg.collation.name", "Collation"), "utf8mb4_general_ci", I18n.t("remote.install.mysql.cfg.collation.desc", "Default server collation.")));
        fields.add(new RemoteInstallField(MysqlRemoteFields.MYSQL_INNODB_BUFFER_POOL_SIZE, "innodb_buffer_pool_size", bufferPoolSize, I18n.t("remote.install.mysql.cfg.buffer_pool.desc", "InnoDB buffer pool size, for example 512M, 1G, 2G.")));
        return fields;
    }

    @Override
    public List<RemoteInstallStepSpec> buildInstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.install.mysql.step1.name", "Remove Existing MySQL", "remote.install.mysql.step1.desc", "Stop mysqld and remove previous MySQL service, installation directory, data directory, and mysql user.", true, true),
                new RemoteInstallStepSpec("remote.install.mysql.step2.name", "Check Dependencies", "remote.install.mysql.step2.desc", "Check tar/gzip tools and system directories required by the generic tar.gz package.", true, true),
                new RemoteInstallStepSpec("remote.install.mysql.step3.name", "Create User And Directories", "remote.install.mysql.step3.desc", "Create mysql group/user and prepare basedir/datadir.", true, true),
                new RemoteInstallStepSpec("remote.install.mysql.step4.name", "Extract MySQL Package", "remote.install.mysql.step4.desc", "Extract MySQL 5.7/8 tar.gz package and place it under basedir.", true, true),
                new RemoteInstallStepSpec("remote.install.mysql.step5.name", "Initialize Instance", "remote.install.mysql.step5.desc", "Generate my.cnf and initialize the MySQL data directory.", true, true),
                new RemoteInstallStepSpec("remote.install.mysql.step6.name", "Start And Set Password", "remote.install.mysql.step6.desc", "Start mysqld, set root password, and allow remote root login.", true, true),
                new RemoteInstallStepSpec("remote.install.mysql.step7.name", "Enable Autostart", "remote.install.mysql.step7.desc", "Create a systemd service for automatic startup.", true, false)
        );
    }

    @Override
    public List<RemoteInstallStepSpec> buildUninstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.uninstall.mysql.step1.name", "Stop MySQL", "remote.uninstall.mysql.step1.desc", "Stop mysql/mysqld service and kill remaining mysqld processes.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.mysql.step2.name", "Remove Service", "remote.uninstall.mysql.step2.desc", "Remove systemd/sysv startup configuration and /etc/my.cnf generated by the wizard.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.mysql.step3.name", "Remove Directories", "remote.uninstall.mysql.step3.desc", "Remove /usr/local/mysql and /data/mysql.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.mysql.step4.name", "Remove User And Group", "remote.uninstall.mysql.step4.desc", "Delete mysql user and mysql group.", true, true)
        );
    }

    @Override
    public void executeInstallStep(int stepNo, RemoteInstallExecutionContext context) throws Exception {
        MysqlRemoteWorkflow.executeInstallStep(stepNo, context);
    }

    @Override
    public void afterInstallSteps(RemoteInstallExecutionContext context) throws Exception {
        MysqlRemoteWorkflow.afterInstallSteps(context);
    }

    @Override
    public void populateInstallResult(RemoteInstallExecutionContext context, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        MysqlRemoteWorkflow.populateInstallResult(context, databaseInfoArea);
    }

    @Override
    public Connect buildInstalledConnect(RemoteInstallExecutionContext context) {
        return MysqlRemoteWorkflow.buildInstalledConnect(context);
    }

    @Override
    public void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext context) throws Exception {
        MysqlRemoteWorkflow.executeUninstallStep(stepNo, context);
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
