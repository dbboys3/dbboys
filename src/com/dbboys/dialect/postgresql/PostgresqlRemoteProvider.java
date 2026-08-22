package com.dbboys.dialect.postgresql;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.*;
import com.dbboys.remote.wizard.RemoteInstallWizard;
import com.dbboys.remote.wizard.RemoteUninstallWizard;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public final class PostgresqlRemoteProvider implements RemoteDatabaseProvider {
    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public String displayName() {
        return "PostgreSQL 14";
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
            return "https://www.dbboys.com/dl/postgresql/server/x86/latest.tar";
        }
        if (systemInfoText.contains("aarch64")) {
            return "https://www.dbboys.com/dl/postgresql/server/arm/latest.tar";
        }
        return null;
    }

    @Override
    public List<String> installWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.install.postgresql.desc.item1", "1. Remote install supports Linux/Unix only, not Windows."),
                I18n.t("remote.install.postgresql.desc.item2", "2. Prepare PostgreSQL 14 PGDG rpm/deb packages (or a tar.gz bundle containing them); upload from local, fill in existing remote paths, or click the button to download the package matching the CPU automatically."),
                I18n.t("remote.install.postgresql.desc.item3", "3. The wizard installs the packages, initializes the data directory (initdb), configures postgresql.conf/pg_hba.conf, starts PostgreSQL, and sets the postgres password."),
                I18n.t("remote.install.postgresql.desc.item4", "4. Existing PostgreSQL 14 installed by this wizard will be removed before installation.")
        );
    }

    @Override
    public List<String> uninstallWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.uninstall.postgresql.desc.item1", "1. Remote uninstall supports Linux/Unix only, not Windows."),
                I18n.t("remote.uninstall.postgresql.desc.item2", "2. Remote uninstall stops PostgreSQL 14, removes the PGDG packages, service, data directory, and the postgres user/group.")
        );
    }

    @Override
    public String localPackageHintText() {
        return I18n.t("remote.install.postgresql.package.local_hint", "Select PostgreSQL 14 PGDG rpm/deb packages, or a tar.gz bundle containing them.");
    }

    @Override
    public boolean isPackageCompatible(String systemInfoText, String packagePath) {
        if (packagePath == null || packagePath.isBlank()) {
            return true;
        }
        // The remote path may hold several space-separated packages; each must look compatible.
        for (String path : packagePath.trim().split("\\s+")) {
            String lower = path.toLowerCase();
            if (!lower.endsWith(".rpm") && !lower.endsWith(".deb")
                    && !lower.endsWith(".tar") && !lower.endsWith(".tar.gz") && !lower.endsWith(".tgz")) {
                return false;
            }
            if (systemInfoText != null && systemInfoText.contains("x86_64")) {
                if (lower.contains("aarch64") || lower.contains("arm64")) {
                    return false;
                }
            }
            if (systemInfoText != null && systemInfoText.contains("aarch64")) {
                if (lower.contains("x86_64") || lower.contains("amd64")) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public List<RemoteInstallField> buildDefaultInstallFields(RemoteHostProfile hostProfile) {
        double totalMem = hostProfile == null ? 0 : hostProfile.getTotalMemoryGb();
        String sharedBuffers = totalMem >= 8 ? "2GB" : totalMem >= 4 ? "1GB" : "512MB";

        List<RemoteInstallField> fields = new ArrayList<>();
        fields.add(new RemoteInstallField(PostgresqlRemoteFields.PG_PASSWORD, I18n.t("remote.install.postgresql.cfg.password.name", "postgres password"), RemotePasswordUtil.generateComplexPassword(), I18n.t("remote.install.postgresql.cfg.password.desc", "Password for the postgres superuser. Keep it strong.")));
        fields.add(new RemoteInstallField(PostgresqlRemoteFields.PG_DATADIR, "datadir", "/var/lib/pgsql/14/data", I18n.t("remote.install.postgresql.cfg.datadir.desc", "PostgreSQL data directory (PGDATA). It will be created and initialized by initdb.")));
        fields.add(new RemoteInstallField(PostgresqlRemoteFields.PG_PORT, I18n.t("remote.install.postgresql.cfg.port.name", "Port"), "5432", I18n.t("remote.install.postgresql.cfg.port.desc", "PostgreSQL listen port.")));
        fields.add(new RemoteInstallField(PostgresqlRemoteFields.PG_LISTEN_ADDRESSES, I18n.t("remote.install.postgresql.cfg.listen_addresses.name", "Listen addresses"), "*", I18n.t("remote.install.postgresql.cfg.listen_addresses.desc", "Use * to listen on all interfaces.")));
        fields.add(new RemoteInstallField(PostgresqlRemoteFields.PG_ENCODING, I18n.t("remote.install.postgresql.cfg.encoding.name", "Encoding"), "UTF8", I18n.t("remote.install.postgresql.cfg.encoding.desc", "Default database cluster encoding, e.g. UTF8.")));
        fields.add(new RemoteInstallField(PostgresqlRemoteFields.PG_SHARED_BUFFERS, "shared_buffers", sharedBuffers, I18n.t("remote.install.postgresql.cfg.shared_buffers.desc", "Shared buffers size, for example 512MB, 1GB, 2GB.")));
        return fields;
    }

    @Override
    public List<RemoteInstallStepSpec> buildInstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.install.postgresql.step1.name", "Remove Existing PostgreSQL 14", "remote.install.postgresql.step1.desc", "Stop PostgreSQL and remove previous PostgreSQL 14 packages, service, data directory, and the postgres user.", true, true),
                new RemoteInstallStepSpec("remote.install.postgresql.step2.name", "Check Dependencies", "remote.install.postgresql.step2.desc", "Check the package file and the package tools (yum/dnf or dpkg/apt-get) required by PGDG packages.", true, true),
                new RemoteInstallStepSpec("remote.install.postgresql.step3.name", "Install Packages", "remote.install.postgresql.step3.desc", "Install the PostgreSQL 14 PGDG rpm/deb packages.", true, true),
                new RemoteInstallStepSpec("remote.install.postgresql.step4.name", "Initialize Cluster", "remote.install.postgresql.step4.desc", "Create the postgres user if needed and initialize the data directory with initdb (sets the postgres password).", true, true),
                new RemoteInstallStepSpec("remote.install.postgresql.step5.name", "Configure Instance", "remote.install.postgresql.step5.desc", "Write listen_addresses/port/shared_buffers to postgresql.conf and allow remote password login in pg_hba.conf.", true, true),
                new RemoteInstallStepSpec("remote.install.postgresql.step6.name", "Start And Verify", "remote.install.postgresql.step6.desc", "Create the systemd service, start PostgreSQL, and verify the connection.", true, true),
                new RemoteInstallStepSpec("remote.install.postgresql.step7.name", "Enable Autostart", "remote.install.postgresql.step7.desc", "Enable the systemd service for automatic startup.", true, false)
        );
    }

    @Override
    public List<RemoteInstallStepSpec> buildUninstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.uninstall.postgresql.step1.name", "Stop PostgreSQL", "remote.uninstall.postgresql.step1.desc", "Stop the postgresql-14 service and kill remaining postgres processes.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.postgresql.step2.name", "Remove Packages And Service", "remote.uninstall.postgresql.step2.desc", "Remove the PGDG postgresql14 packages and the systemd service created by the wizard.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.postgresql.step3.name", "Remove Directories", "remote.uninstall.postgresql.step3.desc", "Remove the data directory and related PostgreSQL 14 directories.", true, true),
                new RemoteInstallStepSpec("remote.uninstall.postgresql.step4.name", "Remove User And Group", "remote.uninstall.postgresql.step4.desc", "Delete the postgres user and postgres group.", true, true)
        );
    }

    @Override
    public void executeInstallStep(int stepNo, RemoteInstallExecutionContext context) throws Exception {
        PostgresqlRemoteWorkflow.executeInstallStep(stepNo, context);
    }

    @Override
    public void afterInstallSteps(RemoteInstallExecutionContext context) throws Exception {
        PostgresqlRemoteWorkflow.afterInstallSteps(context);
    }

    @Override
    public void populateInstallResult(RemoteInstallExecutionContext context, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        PostgresqlRemoteWorkflow.populateInstallResult(context, databaseInfoArea);
    }

    @Override
    public Connect buildInstalledConnect(RemoteInstallExecutionContext context) {
        return PostgresqlRemoteWorkflow.buildInstalledConnect(context);
    }

    @Override
    public void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext context) throws Exception {
        PostgresqlRemoteWorkflow.executeUninstallStep(stepNo, context);
    }

    @Override
    public void startInstallWizard(Stage parent) {
        RemoteInstallWizard.startWizard(parent, this);
    }

    @Override
    public void startUninstallWizard(Stage parent) {
        RemoteUninstallWizard.startWizard(parent, this);
    }
}
