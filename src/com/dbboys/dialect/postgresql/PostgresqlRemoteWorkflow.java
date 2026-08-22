package com.dbboys.dialect.postgresql;

import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.*;
import com.dbboys.ui.component.CustomInlineCssTextArea;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PostgresqlRemoteWorkflow {
    private static final String RESULT_TITLE_STYLE = "-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;";
    private static final String RESULT_BODY_STYLE = "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;";
    private static final String UNIT_NAME = "postgresql-14";
    private static final String UNIT_PATH = "/etc/systemd/system/postgresql-14.service";
    private static final String INSTALL_LOG = "/tmp/dbboys_pg14_install.log";
    private static final String EXTRACT_DIR = "/tmp/dbboys_pg14_pkgs";

    private PostgresqlRemoteWorkflow() {
    }

    public static void executeInstallStep(int stepNo, RemoteInstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:
                cleanupExistingInstall(ctx);
                return;
            case 2:
                checkSystemDependencies(ctx);
                return;
            case 3:
                installPackages(ctx);
                return;
            case 4:
                initializeCluster(ctx);
                return;
            case 5:
                configureInstance(ctx);
                return;
            case 6:
                startAndVerify(ctx);
                return;
            case 7:
                enableAutostart(ctx);
                return;
            default:
                throw new IllegalArgumentException("Unknown PostgreSQL install step: " + stepNo);
        }
    }

    public static void afterInstallSteps(RemoteInstallExecutionContext ctx) {
    }

    public static void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:
                ctx.executeCommandWithExitStatus("systemctl stop postgresql-14.service postgresql.service postgresql@14-main.service 2>/dev/null"
                        + " || service postgresql-14 stop 2>/dev/null || service postgresql stop 2>/dev/null || true");
                ctx.executeCommandWithExitStatus("pkill -9 -x postgres || true; pkill -9 -x postmaster || true");
                return;
            case 2:
                if (isRpmFamily(ctx)) {
                    ctx.executeCommandWithExitStatus("yum remove -y \"postgresql14*\" >/dev/null 2>&1 || true");
                } else {
                    ctx.executeCommandWithExitStatus("apt-get remove -y --purge postgresql-14 postgresql-client-14 >/dev/null 2>&1 || true");
                }
                return;
            case 3: {
                // Recover PGDATA from the wizard-generated unit (deleted below) before removing directories
                String script = "pgdata=$(sed -n 's/^Environment=PGDATA=//p' " + UNIT_PATH + " 2>/dev/null | head -1);"
                        + "[ -n \"$pgdata\" ] || pgdata=/var/lib/pgsql/14/data;"
                        + "rm -rf \"$pgdata\" /var/lib/pgsql/14 /etc/postgresql/14 /usr/pgsql-14;"
                        + "rm -f " + UNIT_PATH + ";"
                        + "systemctl daemon-reload 2>/dev/null || true";
                if (ctx.executeCommandWithExitStatus(script) != 0) {
                    throw new Exception(I18n.t("remote.uninstall.postgresql.error.remove_dirs_failed", "Failed to remove PostgreSQL directories."));
                }
                return;
            }
            case 4:
                if (ctx.executeCommandWithExitStatus("id postgres") == 0) {
                    ctx.executeCommandWithExitStatus("userdel -r -f postgres 2>/dev/null || userdel -f postgres 2>/dev/null || true");
                    ctx.executeCommandWithExitStatus("groupdel postgres 2>/dev/null || true");
                }
                return;
            default:
                throw new IllegalArgumentException("Unknown PostgreSQL uninstall step: " + stepNo);
        }
    }

    public static void populateInstallResult(RemoteInstallExecutionContext ctx, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        String packagePath = ctx.remotePackagePath() == null ? "" : ctx.remotePackagePath().trim();
        String packageName = packagePath.isEmpty() ? "" : new File(packagePath.split("\\s+")[0]).getName();
        String binDir = binDir(ctx);
        String datadir = ctx.fieldValue(PostgresqlRemoteFields.PG_DATADIR);
        String port = ctx.fieldValue(PostgresqlRemoteFields.PG_PORT);
        String password = ctx.fieldValue(PostgresqlRemoteFields.PG_PASSWORD);
        String psql = "PGPASSWORD=" + ctx.shellQuote(password) + " " + ctx.shellQuote(binDir + "/psql")
                + " -h 127.0.0.1 -p " + ctx.shellQuote(port) + " -U postgres -d postgres";

        databaseInfoArea.replaceText("");
        databaseInfoArea.append(I18n.t("remote.install.result.db_version", "Database version") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(packageName + "\n", RESULT_BODY_STYLE);
        databaseInfoArea.append(ctx.executeCommand(psql + " -tc \"select version()\" 2>/dev/null") + "\n\n", RESULT_BODY_STYLE);

        databaseInfoArea.append(I18n.t("remote.install.result.db_instance_info", "Database instance info") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.install_path", "Install path") + ": " + binDir + "\n", RESULT_BODY_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.data_path", "Data path") + ": " + datadir + "\n", RESULT_BODY_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.listen_ip", "Listen IP") + ": " + ctx.fieldValue(PostgresqlRemoteFields.PG_LISTEN_ADDRESSES) + "\n", RESULT_BODY_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.port", "Port") + ": " + port + "\n", RESULT_BODY_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.user_password", "User/password") + ": postgres/" + password + "\n", RESULT_BODY_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.postgresql.result.encoding", "Encoding") + ": " + ctx.fieldValue(PostgresqlRemoteFields.PG_ENCODING) + "\n\n", RESULT_BODY_STYLE);

        // System info goes through the unified renderer (same interface and
        // title/text styles as the wizard's step-2 system-info panel)
        RemoteSystemInfoCollector.appendSystemInfo(databaseInfoArea, ctx);
    }

    public static Connect buildInstalledConnect(RemoteInstallExecutionContext ctx) {
        PostgresqlDialect dialect = new PostgresqlDialect();
        Connect connect = new Connect();
        connect.setDbtype(dialect.getDbType());
        connect.setIp(ctx.host());
        connect.setPort(ctx.fieldValue(PostgresqlRemoteFields.PG_PORT));
        connect.setCatalog(dialect.connection().defaultDatabase());
        connect.setUsername(PostgresqlRemoteFields.LOGIN_USERNAME);
        connect.setPassword(ctx.fieldValue(PostgresqlRemoteFields.PG_PASSWORD));
        connect.setDriver("postgresql-42.7.13.jar");
        connect.setProps(dialect.connection().defaultConnectionProps());
        return connect;
    }

    private static void cleanupExistingInstall(RemoteInstallExecutionContext ctx) throws Exception {
        String datadir = ctx.fieldValue(PostgresqlRemoteFields.PG_DATADIR);
        ctx.executeCommandWithExitStatus("systemctl stop postgresql-14.service postgresql.service postgresql@14-main.service 2>/dev/null"
                + " || service postgresql-14 stop 2>/dev/null || service postgresql stop 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("pkill -9 -x postgres || true; pkill -9 -x postmaster || true");
        removePackages(ctx);
        ctx.executeCommandWithExitStatus("systemctl disable postgresql-14.service postgresql.service postgresql@14-main.service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -f " + UNIT_PATH + "; systemctl daemon-reload 2>/dev/null || true");
        if (ctx.executeCommandWithExitStatus("rm -rf " + ctx.shellQuote(datadir)
                + " /var/lib/pgsql/14 /etc/postgresql/14 /usr/pgsql-14") != 0) {
            throw new Exception(I18n.t("remote.install.postgresql.error.cleanup_failed", "Failed to clean existing PostgreSQL 14 installation."));
        }
        if (ctx.executeCommandWithExitStatus("id postgres") == 0) {
            ctx.executeCommandWithExitStatus("userdel -r -f postgres 2>/dev/null || userdel -f postgres 2>/dev/null || true");
            ctx.executeCommandWithExitStatus("groupdel postgres 2>/dev/null || true");
        }
    }

    private static void checkSystemDependencies(RemoteInstallExecutionContext ctx) throws Exception {
        String packagePath = ctx.remotePackagePath() == null ? "" : ctx.remotePackagePath().trim();
        if (packagePath.isEmpty()) {
            throw new Exception(I18n.t("remote.install.error.remote_package_missing", "Remote package file does not exist."));
        }
        boolean needsTar = false;
        for (String path : packagePath.split("\\s+")) {
            if (ctx.executeCommandWithExitStatus("test -f " + ctx.shellQuote(path)) != 0) {
                throw new Exception(I18n.t("remote.install.error.remote_package_missing", "Remote package file does not exist.") + " " + path);
            }
            String lower = path.toLowerCase();
            if (lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
                needsTar = true;
            }
        }
        if (needsTar && ctx.executeCommandWithExitStatus("command -v tar >/dev/null") != 0) {
            throw new Exception(I18n.t("remote.install.postgresql.error.tar_missing", "tar is required to extract the package bundle."));
        }
        if (isRpmFamily(ctx)) {
            if (ctx.executeCommandWithExitStatus("command -v yum >/dev/null || command -v dnf >/dev/null") != 0) {
                throw new Exception(I18n.t("remote.install.postgresql.error.pkg_tool_missing", "yum or dnf is required to install rpm packages."));
            }
        } else if (ctx.executeCommandWithExitStatus("command -v dpkg >/dev/null && command -v apt-get >/dev/null") != 0) {
            throw new Exception(I18n.t("remote.install.postgresql.error.pkg_tool_missing_deb", "dpkg and apt-get are required to install deb packages."));
        }
        if (ctx.executeCommandWithExitStatus("mkdir -p /tmp && test -w /tmp") != 0) {
            throw new Exception(I18n.t("remote.install.postgresql.error.tmp_not_writable", "/tmp is not writable."));
        }
    }

    private static void installPackages(RemoteInstallExecutionContext ctx) throws Exception {
        String packagePath = ctx.remotePackagePath().trim();
        // Collect every rpm/deb first, then install each family in ONE command:
        // installing package-by-package fails on intra-set dependencies (e.g.
        // postgresql14-server needs postgresql14-libs) when no repo is reachable.
        List<String> rpms = new ArrayList<>();
        List<String> debs = new ArrayList<>();
        boolean tarExtracted = false;
        ctx.executeCommandWithExitStatus("rm -rf " + EXTRACT_DIR);
        for (String path : packagePath.split("\\s+")) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".rpm")) {
                rpms.add(ctx.shellQuote(path));
            } else if (lower.endsWith(".deb")) {
                debs.add(ctx.shellQuote(path));
            } else {
                // tar bundle: extract now, its rpm/deb members join the same install command
                String extract = "set -e; mkdir -p " + EXTRACT_DIR + ";"
                        + "tar -xf " + ctx.shellQuote(path) + " -C " + EXTRACT_DIR;
                if (ctx.executeCommandWithExitStatus("{ " + extract + "; } >" + INSTALL_LOG + " 2>&1") != 0) {
                    throwInstallFailed(ctx);
                }
                tarExtracted = true;
            }
        }
        if (tarExtracted) {
            String found = ctx.executeCommand("find " + EXTRACT_DIR + " -type f \\( -iname '*.rpm' -o -iname '*.deb' \\) | sort");
            for (String line : found.split("\\R")) {
                String p = line.trim();
                if (p.toLowerCase().endsWith(".rpm")) {
                    rpms.add(ctx.shellQuote(p));
                } else if (p.toLowerCase().endsWith(".deb")) {
                    debs.add(ctx.shellQuote(p));
                }
            }
        }
        if (rpms.isEmpty() && debs.isEmpty()) {
            throw new Exception(I18n.t("remote.install.postgresql.error.no_packages", "No rpm/deb package found to install."));
        }
        // yum install resolves dependencies from the configured OS repositories
        if (!rpms.isEmpty()) {
            runInstall(ctx, "yum install -y " + String.join(" ", rpms));
        }
        if (!debs.isEmpty()) {
            runInstall(ctx, "dpkg -i " + String.join(" ", debs) + " || apt-get -f install -y");
        }
        ctx.executeCommandWithExitStatus("rm -rf " + EXTRACT_DIR);
        // Remember where the PGDG packages put the binaries for the following steps
        ctx.setFieldValue(PostgresqlRemoteFields.PG_BINDIR, detectBinDir(ctx));
    }

    /** Run one package-install command; on failure raise with the install log tail. */
    private static void runInstall(RemoteInstallExecutionContext ctx, String command) throws Exception {
        if (ctx.executeCommandWithExitStatus("{ " + command + "; } >" + INSTALL_LOG + " 2>&1") != 0) {
            throwInstallFailed(ctx);
        }
    }

    private static void throwInstallFailed(RemoteInstallExecutionContext ctx) throws Exception {
        String diagnostics = ctx.executeCommand("tail -n 50 " + INSTALL_LOG + " 2>/dev/null");
        String message = I18n.t("remote.install.postgresql.error.install_failed", "Failed to install PostgreSQL 14 packages.");
        if (!diagnostics.isBlank()) {
            message += "\n\n" + diagnostics;
        }
        throw new Exception(message);
    }

    private static void initializeCluster(RemoteInstallExecutionContext ctx) throws Exception {
        String datadir = ctx.fieldValue(PostgresqlRemoteFields.PG_DATADIR);
        String password = ctx.fieldValue(PostgresqlRemoteFields.PG_PASSWORD);
        String encoding = ctx.fieldValue(PostgresqlRemoteFields.PG_ENCODING);
        String binDir = binDir(ctx);
        String pwfile = "/tmp/dbboys_pg14_pwfile";
        String initLog = "/tmp/dbboys_pg14_initdb.log";
        // initdb refuses to run as root: run it as the postgres user, password via --pwfile
        String initCmd = binDir + "/initdb -D \"" + datadir + "\" -U postgres -E \"" + encoding + "\""
                + " --auth-local=trust --auth-host=scram-sha-256 --pwfile=\"" + pwfile + "\"";
        String script = "set -e;"
                + "getent group postgres >/dev/null || groupadd postgres;"
                + "id postgres >/dev/null 2>&1 || useradd -r -g postgres -s /sbin/nologin postgres;"
                + "rm -rf " + ctx.shellQuote(datadir) + ";"
                + "mkdir -p " + ctx.shellQuote(datadir) + ";"
                + "chown -R postgres:postgres " + ctx.shellQuote(datadir) + ";"
                + "chmod 700 " + ctx.shellQuote(datadir) + ";"
                + "umask 077; cat > " + pwfile + " <<'EOF'\n" + password + "\nEOF\n"
                + "chown postgres:postgres " + pwfile + ";"
                + "su -s /bin/bash postgres -c " + ctx.shellQuote(initCmd) + " >" + initLog + " 2>&1;"
                + "rm -f " + pwfile;
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            ctx.executeCommandWithExitStatus("rm -f " + pwfile);
            String diagnostics = ctx.executeCommand("tail -n 50 " + initLog + " 2>/dev/null");
            String message = I18n.t("remote.install.postgresql.error.initdb_failed", "Failed to initialize the PostgreSQL cluster (initdb).");
            if (!diagnostics.isBlank()) {
                message += "\n\n" + diagnostics;
            }
            throw new Exception(message);
        }
    }

    private static void configureInstance(RemoteInstallExecutionContext ctx) throws Exception {
        String datadir = ctx.fieldValue(PostgresqlRemoteFields.PG_DATADIR);
        String listenAddresses = ctx.fieldValue(PostgresqlRemoteFields.PG_LISTEN_ADDRESSES).replace("'", "");
        String confExtra = "\n# dbboys remote install settings\n"
                + "listen_addresses = '" + listenAddresses + "'\n"
                + "port = " + ctx.fieldValue(PostgresqlRemoteFields.PG_PORT).replaceAll("[^0-9]", "") + "\n"
                + "shared_buffers = " + ctx.fieldValue(PostgresqlRemoteFields.PG_SHARED_BUFFERS).replaceAll("[^0-9A-Za-z]", "") + "\n"
                + "logging_collector = on\n";
        String hbaExtra = "\n# dbboys remote install: allow remote password login\n"
                + "host all all 0.0.0.0/0 scram-sha-256\n"
                + "host all all ::/0 scram-sha-256\n";
        String script = "set -e;"
                + "cat >> " + ctx.shellQuote(datadir + "/postgresql.conf") + " <<'EOF'\n" + confExtra + "EOF\n"
                + "cat >> " + ctx.shellQuote(datadir + "/pg_hba.conf") + " <<'EOF'\n" + hbaExtra + "EOF\n";
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            throw new Exception(I18n.t("remote.install.postgresql.error.configure_failed", "Failed to configure postgresql.conf/pg_hba.conf."));
        }
    }

    private static void startAndVerify(RemoteInstallExecutionContext ctx) throws Exception {
        String datadir = ctx.fieldValue(PostgresqlRemoteFields.PG_DATADIR);
        String port = ctx.fieldValue(PostgresqlRemoteFields.PG_PORT);
        String password = ctx.fieldValue(PostgresqlRemoteFields.PG_PASSWORD);
        String binDir = binDir(ctx);
        // Own unit instead of the PGDG-shipped one: Type=forking + pg_ctl works for both
        // rpm and deb layouts and honors the configured PGDATA without drop-in overrides.
        String unit = """
                [Unit]
                Description=PostgreSQL 14 database server
                After=network.target

                [Service]
                Type=forking
                User=postgres
                Group=postgres
                Environment=PGDATA=%s
                ExecStart=%s/pg_ctl start -D $PGDATA -w -t 120
                ExecStop=%s/pg_ctl stop -D $PGDATA -m fast
                ExecReload=%s/pg_ctl reload -D $PGDATA
                TimeoutSec=130
                Restart=on-failure
                LimitNOFILE=65535

                [Install]
                WantedBy=multi-user.target
                """.formatted(datadir, binDir, binDir, binDir);
        String script = "set -e;"
                + "cat > " + UNIT_PATH + " <<'EOF'\n" + unit + "EOF\n"
                + "systemctl daemon-reload;"
                + "systemctl start " + UNIT_NAME + ".service;"
                + "for i in $(seq 1 30); do " + ctx.shellQuote(binDir + "/pg_isready") + " -h 127.0.0.1 -p " + ctx.shellQuote(port) + " >/dev/null 2>&1 && break; sleep 2; done;"
                + ctx.shellQuote(binDir + "/pg_isready") + " -h 127.0.0.1 -p " + ctx.shellQuote(port) + " >/dev/null 2>&1;"
                + "PGPASSWORD=" + ctx.shellQuote(password) + " " + ctx.shellQuote(binDir + "/psql")
                + " -h 127.0.0.1 -p " + ctx.shellQuote(port) + " -U postgres -d postgres"
                + " -tc \"select version()\" >/dev/null 2>&1";
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            String diagnostics = ctx.executeCommand("{ systemctl status " + UNIT_NAME + ".service --no-pager 2>/dev/null | tail -n 20;"
                    + " echo; tail -n 50 " + ctx.shellQuote(datadir + "/log/postgresql-Sat.log") + " 2>/dev/null; } 2>&1");
            String message = I18n.t("remote.install.postgresql.error.start_verify_failed", "Failed to start PostgreSQL or verify the connection.");
            if (!diagnostics.isBlank()) {
                message += "\n\n" + diagnostics;
            }
            throw new Exception(message);
        }
    }

    private static void enableAutostart(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("systemctl enable " + UNIT_NAME + ".service") != 0) {
            throw new Exception(I18n.t("remote.install.postgresql.error.autostart_failed", "Failed to enable PostgreSQL autostart."));
        }
    }

    private static void removePackages(RemoteInstallExecutionContext ctx) throws IOException {
        if (isRpmFamily(ctx)) {
            ctx.executeCommandWithExitStatus("yum remove -y \"postgresql14*\" >/dev/null 2>&1 || true");
        } else {
            ctx.executeCommandWithExitStatus("apt-get remove -y --purge postgresql-14 postgresql-client-14 >/dev/null 2>&1 || true");
        }
    }

    private static boolean isRpmFamily(RemoteInstallExecutionContext ctx) throws IOException {
        return ctx.executeCommandWithExitStatus("command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1") == 0;
    }

    private static boolean isRpmFamily(RemoteUninstallExecutionContext ctx) throws IOException {
        return ctx.executeCommandWithExitStatus("command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1") == 0;
    }

    /** Detected PostgreSQL bin directory; falls back to detection when not cached by step 3. */
    private static String binDir(RemoteInstallExecutionContext ctx) throws Exception {
        String dir;
        try {
            dir = ctx.fieldValue(PostgresqlRemoteFields.PG_BINDIR);
        } catch (IllegalStateException e) {
            dir = "";
        }
        if (dir == null || dir.isBlank()) {
            dir = detectBinDir(ctx);
            ctx.setFieldValue(PostgresqlRemoteFields.PG_BINDIR, dir);
        }
        return dir;
    }

    /** PGDG layout: /usr/pgsql-14/bin on rpm family, /usr/lib/postgresql/14/bin on deb family. */
    private static String detectBinDir(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("test -x /usr/pgsql-14/bin/initdb") == 0) {
            return "/usr/pgsql-14/bin";
        }
        if (ctx.executeCommandWithExitStatus("test -x /usr/lib/postgresql/14/bin/initdb") == 0) {
            return "/usr/lib/postgresql/14/bin";
        }
        String found = ctx.executeCommand("dirname $(command -v initdb) 2>/dev/null").trim();
        if (!found.isEmpty() && !".".equals(found)) {
            return found;
        }
        throw new Exception(I18n.t("remote.install.postgresql.error.bindir_not_found", "PostgreSQL 14 binaries not found after package installation."));
    }
}
