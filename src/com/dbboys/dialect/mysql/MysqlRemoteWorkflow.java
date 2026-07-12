package com.dbboys.dialect.mysql;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.dialect.mysql.MysqlDialect;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.*;

import java.io.File;

public final class MysqlRemoteWorkflow {
    private static final String RESULT_TITLE_STYLE = "-fx-fill: -color-dialog-title-fg;-fx-font-weight: bold;-fx-font-family:system;";
    private MysqlRemoteWorkflow() {
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
                createUserAndDirectories(ctx);
                return;
            case 4:
                extractMysqlPackage(ctx);
                return;
            case 5:
                initializeInstance(ctx);
                return;
            case 6:
                startAndSecureInstance(ctx);
                return;
            case 7:
                enableAutostart(ctx);
                return;
            default:
                throw new IllegalArgumentException("Unknown MySQL install step: " + stepNo);
        }
    }

    public static void afterInstallSteps(RemoteInstallExecutionContext ctx) {
    }

    public static void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:
                ctx.executeCommandWithExitStatus("systemctl stop mysql.service mysqld.service 2>/dev/null || service mysql stop 2>/dev/null || service mysqld stop 2>/dev/null || true");
                ctx.executeCommandWithExitStatus("pkill -9 mysqld || true");
                return;
            case 2:
                ctx.executeCommandWithExitStatus("systemctl disable mysql.service mysqld.service 2>/dev/null || true");
                ctx.executeCommandWithExitStatus("rm -f /etc/systemd/system/mysql.service /etc/systemd/system/mysqld.service /etc/init.d/mysql /etc/init.d/mysqld");
                ctx.executeCommandWithExitStatus("systemctl daemon-reload 2>/dev/null || true");
                return;
            case 3:
                String removeDirsScript = "basedir=$(awk -F= '/^[[:space:]]*basedir[[:space:]]*=/{gsub(/[[:space:]]/,\"\",$2); print $2; exit}' /etc/my.cnf 2>/dev/null);" +
                        "datadir=$(awk -F= '/^[[:space:]]*datadir[[:space:]]*=/{gsub(/[[:space:]]/,\"\",$2); print $2; exit}' /etc/my.cnf 2>/dev/null);" +
                        "[ -n \"$basedir\" ] || basedir=/usr/local/mysql;" +
                        "[ -n \"$datadir\" ] || datadir=/data/mysql;" +
                        "rm -rf \"$basedir\" \"$datadir\" /etc/my.cnf";
                if (ctx.executeCommandWithExitStatus(removeDirsScript) != 0) {
                    throw new Exception(I18n.t("remote.uninstall.mysql.error.remove_dirs_failed", "Failed to remove MySQL directories."));
                }
                return;
            case 4:
                if (ctx.executeCommandWithExitStatus("id mysql") == 0) {
                    ctx.executeCommandWithExitStatus("userdel -r -f mysql 2>/dev/null || userdel -f mysql 2>/dev/null || true");
                    ctx.executeCommandWithExitStatus("groupdel mysql 2>/dev/null || true");
                }
                return;
            default:
                throw new IllegalArgumentException("Unknown MySQL uninstall step: " + stepNo);
        }
    }

    public static void populateInstallResult(RemoteInstallExecutionContext ctx, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        String packageName = ctx.remotePackagePath() == null ? "" : new File(ctx.remotePackagePath()).getName();
        String basedir = ctx.fieldValue(MysqlRemoteFields.MYSQL_BASEDIR);
        String socket = mysqlSocket(ctx);
        databaseInfoArea.replaceText("");
        databaseInfoArea.append(I18n.t("remote.install.result.db_version", "Database version") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(packageName + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(ctx.executeCommand(ctx.shellQuote(basedir + "/bin/mysql") + " --socket=" + ctx.shellQuote(socket) + " -uroot -p" + ctx.shellQuote(ctx.fieldValue(MysqlRemoteFields.MYSQL_ROOT_PASSWORD)) + " -Nse \"select version()\" 2>/dev/null") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

        databaseInfoArea.append(I18n.t("remote.install.result.db_instance_info", "Database instance info") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.install_path", "Install path") + ": " + basedir + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.data_path", "Data path") + ": " + ctx.fieldValue(MysqlRemoteFields.MYSQL_DATADIR) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.listen_ip", "Listen IP") + ": " + ctx.fieldValue(MysqlRemoteFields.MYSQL_BIND_ADDRESS) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.port", "Port") + ": " + ctx.fieldValue(MysqlRemoteFields.MYSQL_PORT) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.user_password", "User/password") + ": root/" + ctx.fieldValue(MysqlRemoteFields.MYSQL_ROOT_PASSWORD) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.charset", "Charset") + ": " + ctx.fieldValue(MysqlRemoteFields.MYSQL_CHARSET) + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

        databaseInfoArea.append(I18n.t("remote.install.mysql.result.variables", "MySQL variables") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.executeCommand(ctx.shellQuote(basedir + "/bin/mysql") + " --socket=" + ctx.shellQuote(socket) + " -uroot -p" + ctx.shellQuote(ctx.fieldValue(MysqlRemoteFields.MYSQL_ROOT_PASSWORD)) + " -e \"show variables where Variable_name in ('basedir','datadir','port','socket','version','innodb_buffer_pool_size')\" 2>/dev/null") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
    }

    public static Connect buildInstalledConnect(RemoteInstallExecutionContext ctx) {
        MysqlDialect dialect = new MysqlDialect();
        Connect connect = new Connect();
        connect.setDbtype(dialect.getDbType());
        connect.setIp(ctx.host());
        connect.setPort(ctx.fieldValue(MysqlRemoteFields.MYSQL_PORT));
        connect.setCatalog(dialect.connection().defaultDatabase());
        connect.setUsername(MysqlRemoteFields.LOGIN_USERNAME);
        connect.setPassword(ctx.fieldValue(MysqlRemoteFields.MYSQL_ROOT_PASSWORD));
        connect.setDriver("mysql-connector-j-8.0.32.jar");
        connect.setProps(dialect.connection().defaultConnectionProps());
        return connect;
    }

    private static void cleanupExistingInstall(RemoteInstallExecutionContext ctx) throws Exception {
        String basedir = ctx.fieldValue(MysqlRemoteFields.MYSQL_BASEDIR);
        String datadir = ctx.fieldValue(MysqlRemoteFields.MYSQL_DATADIR);
        ctx.executeCommandWithExitStatus("systemctl stop mysql.service mysqld.service 2>/dev/null || service mysql stop 2>/dev/null || service mysqld stop 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("pkill -9 mysqld || true");
        ctx.executeCommandWithExitStatus("systemctl disable mysql.service mysqld.service 2>/dev/null || true");
        ctx.executeCommandWithExitStatus("rm -f /etc/systemd/system/mysql.service /etc/systemd/system/mysqld.service /etc/init.d/mysql /etc/init.d/mysqld /etc/my.cnf");
        ctx.executeCommandWithExitStatus("systemctl daemon-reload 2>/dev/null || true");
        if (ctx.executeCommandWithExitStatus("rm -rf " + ctx.shellQuote(basedir) + " " + ctx.shellQuote(datadir)) != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.cleanup_failed", "Failed to clean existing MySQL installation."));
        }
        if (ctx.executeCommandWithExitStatus("id mysql") == 0) {
            ctx.executeCommandWithExitStatus("userdel -r -f mysql 2>/dev/null || userdel -f mysql 2>/dev/null || true");
            ctx.executeCommandWithExitStatus("groupdel mysql 2>/dev/null || true");
        }
    }

    private static void checkSystemDependencies(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("command -v tar >/dev/null") != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.tar_missing", "tar is required to extract MySQL packages."));
        }
        String packagePath = ctx.remotePackagePath() == null ? "" : ctx.remotePackagePath().toLowerCase();
        if ((packagePath.endsWith(".tar.xz") || packagePath.endsWith(".txz"))
                && ctx.executeCommandWithExitStatus("command -v xz >/dev/null") != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.xz_missing", "xz is required to extract MySQL tar.xz packages."));
        }
        if (ctx.executeCommandWithExitStatus("test -f " + ctx.shellQuote(ctx.remotePackagePath())) != 0) {
            throw new Exception(I18n.t("remote.install.error.remote_package_missing", "Remote package file does not exist."));
        }
        if (ctx.executeCommandWithExitStatus("mkdir -p /tmp && test -w /tmp") != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.tmp_not_writable", "/tmp is not writable."));
        }
    }

    private static void createUserAndDirectories(RemoteInstallExecutionContext ctx) throws Exception {
        String basedir = ctx.fieldValue(MysqlRemoteFields.MYSQL_BASEDIR);
        String datadir = ctx.fieldValue(MysqlRemoteFields.MYSQL_DATADIR);
        String script = "getent group mysql >/dev/null || groupadd mysql;" +
                "id mysql >/dev/null 2>&1 || useradd -r -g mysql -s /sbin/nologin mysql;" +
                "mkdir -p " + ctx.shellQuote(remoteParentPath(basedir)) + " " + ctx.shellQuote(datadir) + ";" +
                "chown -R mysql:mysql " + ctx.shellQuote(datadir);
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.create_user_dirs_failed", "Failed to create mysql user or directories."));
        }
    }

    private static void extractMysqlPackage(RemoteInstallExecutionContext ctx) throws Exception {
        String basedir = ctx.fieldValue(MysqlRemoteFields.MYSQL_BASEDIR);
        String packagePath = ctx.remotePackagePath();
        String script = "set -e;" +
                "work=/tmp/dbboys_mysql_install;" +
                "rm -rf \"$work\";" +
                "mkdir -p \"$work\";" +
                "tar -xf " + ctx.shellQuote(packagePath) + " -C \"$work\";" +
                "src=$(find \"$work\" -mindepth 1 -maxdepth 1 -type d | head -1);" +
                "[ -n \"$src\" ];" +
                "rm -rf " + ctx.shellQuote(basedir) + ";" +
                "mkdir -p " + ctx.shellQuote(remoteParentPath(basedir)) + ";" +
                "mv \"$src\" " + ctx.shellQuote(basedir) + ";" +
                "chown -R mysql:mysql " + ctx.shellQuote(basedir) + ";" +
                "rm -rf \"$work\"";
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.extract_failed", "Failed to extract MySQL package."));
        }
    }

    private static void initializeInstance(RemoteInstallExecutionContext ctx) throws Exception {
        String basedir = ctx.fieldValue(MysqlRemoteFields.MYSQL_BASEDIR);
        String datadir = ctx.fieldValue(MysqlRemoteFields.MYSQL_DATADIR);
        String socket = mysqlSocket(ctx);
        String myCnf = """
                [mysqld]
                basedir=%s
                datadir=%s
                port=%s
                socket=%s
                pid-file=%s/mysql.pid
                bind-address=%s
                user=mysql
                character-set-server=%s
                collation-server=%s
                innodb_buffer_pool_size=%s
                max_connections=1000
                lower_case_table_names=1
                log-error=%s/error.log

                [client]
                socket=%s
                default-character-set=%s
                """.formatted(
                basedir,
                datadir,
                ctx.fieldValue(MysqlRemoteFields.MYSQL_PORT),
                socket,
                datadir,
                ctx.fieldValue(MysqlRemoteFields.MYSQL_BIND_ADDRESS),
                ctx.fieldValue(MysqlRemoteFields.MYSQL_CHARSET),
                ctx.fieldValue(MysqlRemoteFields.MYSQL_COLLATION),
                ctx.fieldValue(MysqlRemoteFields.MYSQL_INNODB_BUFFER_POOL_SIZE),
                datadir,
                socket,
                ctx.fieldValue(MysqlRemoteFields.MYSQL_CHARSET)
        );
        String initLog = "/tmp/dbboys_mysql_initialize.log";
        String script = "set -e;" +
                "rm -rf " + ctx.shellQuote(datadir) + "/*;" +
                "mkdir -p " + ctx.shellQuote(datadir) + ";" +
                "chown -R mysql:mysql " + ctx.shellQuote(datadir) + ";" +
                "cat > /etc/my.cnf <<'EOF'\n" + myCnf + "EOF\n" +
                "rm -f " + ctx.shellQuote(initLog) + ";" +
                ctx.shellQuote(basedir + "/bin/mysqld") + " --defaults-file=/etc/my.cnf --initialize-insecure --user=mysql >" + ctx.shellQuote(initLog) + " 2>&1";
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            String diagnostics = readMysqlInitializeDiagnostics(ctx, datadir, initLog);
            String message = I18n.t("remote.install.mysql.error.initialize_failed", "Failed to initialize MySQL instance.");
            if (!diagnostics.isBlank()) {
                message += "\n\n" + diagnostics;
            }
            throw new Exception(message);
        }
    }

    private static void startAndSecureInstance(RemoteInstallExecutionContext ctx) throws Exception {
        String basedir = ctx.fieldValue(MysqlRemoteFields.MYSQL_BASEDIR);
        String socket = mysqlSocket(ctx);
        String password = ctx.fieldValue(MysqlRemoteFields.MYSQL_ROOT_PASSWORD);
        String sql = """
                ALTER USER 'root'@'localhost' IDENTIFIED BY %s;
                CREATE USER IF NOT EXISTS 'root'@'%%' IDENTIFIED BY %s;
                GRANT ALL PRIVILEGES ON *.* TO 'root'@'%%' WITH GRANT OPTION;
                FLUSH PRIVILEGES;
                """.formatted(sqlLiteral(password), sqlLiteral(password));
        String script = "set -e;" +
                ctx.shellQuote(basedir + "/bin/mysqld_safe") + " --defaults-file=/etc/my.cnf --user=mysql >/tmp/dbboys_mysql_start.log 2>&1 & " +
                "for i in $(seq 1 60); do " + ctx.shellQuote(basedir + "/bin/mysqladmin") + " --socket=" + ctx.shellQuote(socket) + " -uroot ping >/dev/null 2>&1 && break; sleep 2; done;" +
                ctx.shellQuote(basedir + "/bin/mysqladmin") + " --socket=" + ctx.shellQuote(socket) + " -uroot ping >/dev/null 2>&1;" +
                "cat > /tmp/dbboys_mysql_secure.sql <<'EOF'\n" + sql + "EOF\n" +
                ctx.shellQuote(basedir + "/bin/mysql") + " --socket=" + ctx.shellQuote(socket) + " -uroot --connect-expired-password < /tmp/dbboys_mysql_secure.sql;" +
                "rm -f /tmp/dbboys_mysql_secure.sql";
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.start_secure_failed", "Failed to start MySQL or set root password."));
        }
    }

    private static void enableAutostart(RemoteInstallExecutionContext ctx) throws Exception {
        String basedir = ctx.fieldValue(MysqlRemoteFields.MYSQL_BASEDIR);
        String socket = mysqlSocket(ctx);
        String password = ctx.fieldValue(MysqlRemoteFields.MYSQL_ROOT_PASSWORD);
        String service = """
                [Unit]
                Description=MySQL Server
                After=network.target

                [Service]
                Type=simple
                User=mysql
                Group=mysql
                ExecStart=%s/bin/mysqld --defaults-file=/etc/my.cnf --user=mysql
                LimitNOFILE=65535
                Restart=on-failure

                [Install]
                WantedBy=multi-user.target
                """.formatted(basedir);
        String script = "set -e;" +
                "cat > /etc/systemd/system/mysql.service <<'EOF'\n" + service + "EOF\n" +
                "systemctl daemon-reload;" +
                "systemctl enable mysql.service;" +
                ctx.shellQuote(basedir + "/bin/mysqladmin") + " --socket=" + ctx.shellQuote(socket) + " -uroot -p" + ctx.shellQuote(password) + " shutdown >/dev/null 2>&1 || pkill -TERM mysqld || true;" +
                "sleep 3;" +
                "systemctl start mysql.service";
        if (ctx.executeCommandWithExitStatus(script) != 0) {
            throw new Exception(I18n.t("remote.install.mysql.error.autostart_failed", "Failed to enable MySQL autostart."));
        }
    }

    private static String mysqlSocket(RemoteInstallExecutionContext ctx) {
        return ctx.fieldValue(MysqlRemoteFields.MYSQL_DATADIR) + "/mysql.sock";
    }

    private static String remoteParentPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/";
        }
        String normalized = path.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return ".";
        }
        return normalized.substring(0, lastSlash);
    }

    private static String readMysqlInitializeDiagnostics(RemoteInstallExecutionContext ctx, String datadir, String initLog) {
        try {
            String command = "{ " +
                    "echo '[mysqld initialize output]'; " +
                    "test -s " + ctx.shellQuote(initLog) + " && cat " + ctx.shellQuote(initLog) + " || echo '(empty)'; " +
                    "echo; " +
                    "echo '[mysql error.log]'; " +
                    "test -s " + ctx.shellQuote(datadir + "/error.log") + " && tail -n 120 " + ctx.shellQuote(datadir + "/error.log") + " || echo '(not found)'; " +
                    " } 2>&1";
            return ctx.executeCommand(command);
        } catch (Exception e) {
            return I18n.t("remote.install.mysql.error.read_initialize_log_failed", "Failed to read MySQL initialize logs: %s").formatted(e.getMessage());
        }
    }

    private static String sqlLiteral(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
