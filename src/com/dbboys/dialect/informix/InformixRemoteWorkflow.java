package com.dbboys.dialect.informix;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.dialect.informix.InformixDialect;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.*;

import java.io.File;

public final class InformixRemoteWorkflow {
    private static final String RESULT_TITLE_STYLE = "-fx-fill: -color-accent-fg;-fx-font-weight: bold;-fx-font-family:system;";
    private static final int CREATE_DEFAULT_DATABASE_STEP_NO = 12;

    private InformixRemoteWorkflow() {
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
                createUserAndProfile(ctx);
                return;
            case 4:
                installDatabaseBinary(ctx);
                return;
            case 5:
                initializeInstance(ctx);
                return;
            case 6:
                tuneParameters(ctx);
                return;
            case 7:
                optimizePhysicalLog(ctx);
                return;
            case 8:
                optimizeLogicalLog(ctx);
                return;
            case 9:
                optimizeTempDbspaces(ctx);
                return;
            case 10:
                createSbspace(ctx);
                return;
            case 11:
                createUserDbspace(ctx);
                return;
            case 12:
                createDefaultDatabase(ctx);
                return;
            case 13:
                enableAutostart(ctx);
                return;
            case 14:
                configureBackup(ctx);
                return;
            default:
                throw new IllegalArgumentException("Unknown install step: " + stepNo);
        }
    }

    public static void afterInstallSteps(RemoteInstallExecutionContext ctx) throws Exception {
        installUtilityScripts(ctx);
    }

    public static void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext ctx) throws Exception {
        switch (stepNo) {
            case 1:
                if (ctx.executeCommandWithExitStatus("ps -ef |grep informix |grep -v grep |awk '{print \"kill -9 \"$2}' |sh") != 0) {
                    throw new Exception(I18n.t("remote.uninstall.informix.error.kill_process_failed", "kill informix用户进程失败！"));
                }
                return;
            case 2:
                if (ctx.executeCommandWithExitStatus("test -f /INFORMIXTMP/.infxdirs") == 0) {
                    if (ctx.executeCommandWithExitStatus("cat /INFORMIXTMP/.infxdirs  |awk '{print \"rm -rf \"$1}'|sh") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_install_dirs_failed", "删除数据库安装目录失败！"));
                    }
                    if (ctx.executeCommandWithExitStatus("rm -rf /INFORMIXTMP") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_informixtmp_failed", "删除目录/INFORMIXTMP失败！"));
                    }
                }
                if (ctx.executeCommandWithExitStatus("test -d /opt/informix") == 0) {
                    if (ctx.executeCommandWithExitStatus("rm -rf /opt/informix") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.informix.error.remove_opt_informix_failed", "删除数据库安装目录/opt/informix失败！"));
                    }
                }
                return;
            case 3:
                ctx.executeCommandWithExitStatus("find / -user informix -group informix -exec rm -rf {} +");
                return;
            case 4:
                if (ctx.executeCommandWithExitStatus("test -d /etc/informix") == 0) {
                    if (ctx.executeCommandWithExitStatus("rm -rf /etc/informix") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_etc_informix_failed", "删除/etc/informix目录失败！"));
                    }
                }
                return;
            case 5:
                if (ctx.executeCommandWithExitStatus("id informix") == 0) {
                    if (ctx.executeCommandWithExitStatus("userdel -r -f informix") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.informix.error.remove_user_failed", "删除用户informix失败！"));
                    }
                    ctx.executeCommandWithExitStatus("groupdel informix");
                }
                return;
            default:
                throw new IllegalArgumentException("Unknown uninstall step: " + stepNo);
        }
    }

    public static void populateInstallResult(RemoteInstallExecutionContext ctx, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        String packageName = ctx.remotePackagePath() == null ? "" : new File(ctx.remotePackagePath()).getName();
        databaseInfoArea.replaceText("");
        databaseInfoArea.append(I18n.t("remote.install.result.db_version", "数据库版本") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(packageName + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

        databaseInfoArea.append(I18n.t("remote.install.result.db_instance_info", "数据库实例信息") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.install_path", "安装路径") + "：" + ctx.fieldValue(InformixRemoteFields.INFORMIXDIR) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.instance_name", "实例名") + "：" + ctx.fieldValue(InformixRemoteFields.INFORMIXSERVER) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.listen_ip", "监听IP") + "：" + ctx.fieldValue(InformixRemoteFields.LISTEN_IP) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.port", "端口") + "：" + ctx.fieldValue(InformixRemoteFields.LISTEN_PORT) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        if (ctx.isInstallStepSelected(CREATE_DEFAULT_DATABASE_STEP_NO)) {
            databaseInfoArea.append(I18n.t("remote.install.result.db_name", "库名") + "：" + ctx.fieldValue(InformixRemoteFields.DEFAULT_DB_NAME) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        }
        databaseInfoArea.append(I18n.t("remote.install.result.user_password", "用户名/密码") + "：" + InformixRemoteFields.LOGIN_USERNAME + "/" + ctx.fieldValue(InformixRemoteFields.INFORMIX_PASSWORD) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.charset", "字符集") + "：" + ctx.fieldValue(InformixRemoteFields.DB_LOCALE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append("GL_USEGLU：" + ctx.fieldValue(InformixRemoteFields.GL_USEGLU) + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

        databaseInfoArea.append(I18n.t("remote.install.result.space_config", "空间配置") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(I18n.t("remote.install.result.data_path", "数据文件路径") + "：" + ctx.fieldValue(InformixRemoteFields.DATA_FILE_PATH) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.physlog_size", "物理日志大小") + "：" + ctx.fieldValue(InformixRemoteFields.PHYSFILE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.log_size", "逻辑日志大小") + "：" + ctx.fieldValue(InformixRemoteFields.LOGSIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.log_files", "逻辑日志个数") + "：" + ctx.fieldValue(InformixRemoteFields.LOGFILES) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.tempdbs", "临时空间配置") + "：" + ctx.fieldValue(InformixRemoteFields.TEMPDBS) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.sbspace", "智能大对象空间大小") + "：" + ctx.fieldValue(InformixRemoteFields.SBSPACE_SIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.user_space", "用户空间大小") + "：" + ctx.fieldValue(InformixRemoteFields.DATA_SPACE_SIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.onstat_d", "onstat -d输出") + "：\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(ctx.executeCommand("source ~informix/.bash_profile;onstat -d |sed '1,2d'") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.param_config", "参数配置") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.executeCommand("source ~informix/.bash_profile;onstat -g cfg |grep -v '^$' |sed '1,5d'") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.machine", "服务器型号") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.machineInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.os", "操作系统版本") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.osInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.kernel", "内核版本") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.kernelInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.cpu", "CPU信息") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.cpuInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.memory", "内存信息") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.memoryInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.disk", "磁盘信息") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.diskInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.filesystem", "文件系统信息") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.executeCommand("df -h") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.kernel_params", "内核参数") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.executeCommand("ipcs -l") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.informix_ulimit", "informix用户限制") + "\n", RESULT_TITLE_STYLE);
        databaseInfoArea.append(ctx.executeCommand("su - informix -c \"ulimit -a\"") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
    }

    public static Connect buildInstalledConnect(RemoteInstallExecutionContext ctx) {
        InformixDialect dialect = new InformixDialect();
        Connect connect = new Connect();
        connect.setDbtype(dialect.getDbType());
        connect.setIp(ctx.host());
        connect.setPort(ctx.fieldValue(InformixRemoteFields.LISTEN_PORT));
        connect.setCatalog(resolveInstalledDatabaseName(ctx, dialect.connection().defaultDatabase()));
        connect.setUsername(InformixRemoteFields.LOGIN_USERNAME);
        connect.setPassword(ctx.fieldValue(InformixRemoteFields.INFORMIX_PASSWORD));
        connect.setProps(dialect.connection().defaultConnectionProps());
        connect.setProps(dialect.connection().modifyProps(connect, "DB_LOCALE", ctx.fieldValue(InformixRemoteFields.DB_LOCALE)));
        return connect;
    }

    private static String resolveInstalledDatabaseName(RemoteInstallExecutionContext ctx, String fallbackDatabase) {
        if (ctx != null && ctx.isInstallStepSelected(CREATE_DEFAULT_DATABASE_STEP_NO)) {
            String databaseName = ctx.fieldValue(InformixRemoteFields.DEFAULT_DB_NAME);
            if (databaseName != null && !databaseName.isBlank()) {
                return databaseName;
            }
        }
        return fallbackDatabase;
    }

    private static void cleanupExistingInstall(RemoteInstallExecutionContext ctx) throws Exception {
        ctx.executeCommandWithExitStatus("ps -ef |grep informix |grep -v grep |awk '{print \"kill -9 \"$2}' |sh");
        ctx.executeCommandWithExitStatus("find / -user informix -exec rm -rf {} +");
        if (ctx.executeCommandWithExitStatus("test -f /INFORMIXTMP/.infxdirs") == 0) {
            if (ctx.executeCommandWithExitStatus("cat /INFORMIXTMP/.infxdirs  |awk '{print \"rm -rf \"$1}'|sh") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_install_dir", "删除数据库安装目录失败！"));
            }
            if (ctx.executeCommandWithExitStatus("rm -rf /INFORMIXTMP") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_informixtmp", "删除目录/INFORMIXTMP失败！"));
            }
        }
        if (ctx.executeCommandWithExitStatus("test -d /opt/informix") == 0) {
            if (ctx.executeCommandWithExitStatus("rm -rf /opt/informix") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_opt_informix", "删除数据库安装目录/opt/informix失败！"));
            }
        }
        if (ctx.executeCommandWithExitStatus("test -d /etc/informix") == 0) {
            if (ctx.executeCommandWithExitStatus("rm -rf /etc/informix") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_etc_informix", "删除/etc/informix目录失败！"));
            }
        }
        ctx.executeCommandWithExitStatus("crontab -u informix -r");
        if (ctx.executeCommandWithExitStatus("test -f /usr/lib64/libnsl.so.1") != 0) {
            ctx.executeCommandWithExitStatus("ln -s /usr/lib64/libnsl.so.2  /usr/lib64/libnsl.so.1");
        }
        if (ctx.executeCommandWithExitStatus("id informix") == 0) {
            if (ctx.executeCommandWithExitStatus("userdel -r -f informix") != 0) {
                throw new Exception(I18n.t("remote.install.error.delete_informix_user", "删除用户informix失败！"));
            }
        }
    }

    private static void checkSystemDependencies(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("chown root:root /opt&&chmod 755 /opt") != 0) {
            throw new Exception(I18n.t("remote.install.error.reset_opt_perm", "更改/opt为默认权限失败！"));
        }
        if (Double.parseDouble(ctx.executeCommand("df -m /opt |tail -1 |awk '{print $4/1000}'")) < 8) {
            throw new Exception(I18n.t("remote.install.error.opt_space_low", "空间检查不通过，最小要求/opt可用空间小于8G！"));
        }
        if (Double.parseDouble(ctx.executeCommand("df -m /tmp |tail -1 |awk '{print $4/1000}'")) < 1) {
            throw new Exception(I18n.t("remote.install.error.tmp_space_low", "空间检查不通过，最小要求/tmp可用空间小于1G！"));
        }
        if (ctx.executeCommandWithExitStatus("stat -c \"%a\" /tmp | grep -q '777'") != 0) {
            ctx.executeCommandWithExitStatus("chmod 777 /tmp");
        }
        if (Double.parseDouble(ctx.executeCommand("free -m |sed -n 2p |awk '{print $2/1024}'")) < 1) {
            throw new Exception(I18n.t("remote.install.error.memory_low", "内存检查不通过，最小要求1G！"));
        }
        if (ctx.executeCommandWithExitStatus("command -v unzip") != 0) {
            throw new Exception(I18n.t("remote.install.error.unzip_missing", "系统缺失unzip！"));
        }
        if (ctx.executeCommandWithExitStatus("systemctl stop firewalld.service") != 0) {
            ctx.executeCommandWithExitStatus("service iptables stop");
        }
        if (ctx.executeCommandWithExitStatus("systemctl disable firewalld.service") != 0) {
            ctx.executeCommandWithExitStatus("chkconfig iptables off");
        }
        ctx.executeCommandWithExitStatus("sed -i \"s#^hosts.*#hosts:      files#g\" /etc/nsswitch.conf");
        ctx.executeCommandWithExitStatus("sed -i \"s#^SELINUX=.*#SELINUX=disabled#g\" /etc/selinux/config");
        ctx.executeCommandWithExitStatus("sed -i \"s/^#RemoveIPC.*/RemoveIPC=no/g\" /etc/systemd/logind.conf");
        ctx.executeCommandWithExitStatus("systemctl daemon-reload");
        ctx.executeCommandWithExitStatus("systemctl restart systemd-logind");
        ctx.executeCommandWithExitStatus("sed -i '/^[[:space:]]*\\*[[:space:]]\\+\\(soft\\|hard\\)[[:space:]]/d' /etc/security/limits.conf");
        ctx.executeCommandWithExitStatus("sed -i '/^[[:space:]]*\\*[[:space:]]\\+\\(soft\\|hard\\)[[:space:]]/d' /etc/security/limits.d/20-nproc.conf");
        ctx.executeCommandWithExitStatus("echo \"* soft nproc 1048576\">> /etc/security/limits.conf");
        ctx.executeCommandWithExitStatus("echo \"* hard nproc 1048576\">> /etc/security/limits.conf");
        ctx.executeCommandWithExitStatus("echo \"* soft nofile 1048576\">> /etc/security/limits.conf");
        ctx.executeCommandWithExitStatus("echo \"* hard nofile 1048576\">> /etc/security/limits.conf");
        ctx.executeCommandWithExitStatus("sed -i \"/^kernel.shmmni.*/d\" /etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("sed -i \"/^kernel.shmmax.*/d\" /etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("sed -i \"/^kernel.shmall.*/d\" /etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("sed -i \"/^kernel.sem.*/d\" /etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("echo \"kernel.shmmni=4096\">> /etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("echo \"kernel.shmmax=18446744073709547520\">> /etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("echo \"kernel.shmall=18446744073709547520\">> /etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("echo \"kernel.sem=32000 1024000000  500 32000\" >>/etc/sysctl.conf");
        ctx.executeCommandWithExitStatus("sysctl -p");
    }

    private static void createUserAndProfile(RemoteInstallExecutionContext ctx) throws Exception {
        String password = ctx.fieldValue(InformixRemoteFields.INFORMIX_PASSWORD);
        if (ctx.executeCommandWithExitStatus("groupadd informix&&useradd informix -d /home/informix -m -g informix&&echo \"informix:" + password + "\" | chpasswd") != 0) {
            throw new Exception(I18n.t("remote.install.error.create_informix_user_group_failed", "创建informix用户或组失败！"));
        }
        ctx.executeCommandWithExitStatus("cat >>~informix/.bash_profile << EOF\nexport INFORMIXDIR=" + ctx.fieldValue(InformixRemoteFields.INFORMIXDIR) +
                "\nexport INFORMIXSERVER=" + ctx.fieldValue(InformixRemoteFields.INFORMIXSERVER) +
                "\nexport ONCONFIG=onconfig." + ctx.fieldValue(InformixRemoteFields.INFORMIXSERVER) +
                "\nexport INFORMIXSQLHOSTS=\\$INFORMIXDIR/etc/sqlhosts." + ctx.fieldValue(InformixRemoteFields.INFORMIXSERVER) +
                "\nexport DB_LOCALE=" + ctx.fieldValue(InformixRemoteFields.DB_LOCALE) +
                "\nexport CLIENT_LOCALE=" + ctx.fieldValue(InformixRemoteFields.DB_LOCALE) +
                "\nexport GL_USEGLU=" + ctx.fieldValue(InformixRemoteFields.GL_USEGLU) +
                "\nexport PATH=\\$INFORMIXDIR/bin:/usr/bin:\\${PATH}:.\nEOF");
    }

    private static void installDatabaseBinary(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("tar -xvf " + ctx.shellQuote(ctx.remotePackagePath())) != 0) {
            throw new Exception(I18n.t("remote.install.error.extract_package_failed", "解压安装包【%s】失败！").formatted(ctx.remotePackagePath()));
        }
        int status = ctx.executeCommandWithExitStatus("source ~informix/.bash_profile && mkdir -p $INFORMIXDIR && chown informix:informix $INFORMIXDIR && ./ids_install -i silent -DLICENSE_ACCEPTED=TRUE");
        if (status != 0) {
            throw new Exception(I18n.t("remote.install.error.install_to_informixdir_failed", "安装数据库到$INFORMIXDIR失败！"));
        }
    }

    private static void initializeInstance(RemoteInstallExecutionContext ctx) throws Exception {
        String cmd =
                "source ~informix/.bash_profile &&" +
                        "DATADIR=" + ctx.fieldValue(InformixRemoteFields.DATA_FILE_PATH) + "&&" +
                        "mkdir -p ${DATADIR} &&" +
                        "chown informix:informix ${DATADIR} &&" +
                        "cp $INFORMIXDIR/etc/onconfig.std  $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "chown informix:informix $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^ROOTPATH.*#ROOTPATH ${DATADIR}/rootdbschk001#g\" $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^ROOTSIZE.*#ROOTSIZE " + ctx.fieldValue(InformixRemoteFields.ROOTSIZE) + "#g\" $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^DBSERVERNAME.*#DBSERVERNAME $INFORMIXSERVER#g\" $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^TAPEDEV.*#TAPEDEV /dev/null#g\" $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^LTAPEDEV.*#LTAPEDEV /dev/null#g\" $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "echo \"$INFORMIXSERVER onsoctcp " + ctx.fieldValue(InformixRemoteFields.LISTEN_IP) + " " + ctx.fieldValue(InformixRemoteFields.LISTEN_PORT) + "\" >> $INFORMIXSQLHOSTS &&" +
                        "chown informix:informix $INFORMIXSQLHOSTS &&" +
                        "touch ${DATADIR}/rootdbschk001 &&" +
                        "chown informix:informix ${DATADIR}/rootdbschk001 &&" +
                        "chmod 660 ${DATADIR}/rootdbschk001 && oninit -ivyw";
        if (ctx.executeCommandWithExitStatus(cmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.init_instance_failed", "初始化实例失败！"));
        }
    }

    private static void tuneParameters(RemoteInstallExecutionContext ctx) throws Exception {
        String paramsCmd =
                "source ~informix/.bash_profile && " +
                        "sed -i \"s#^PHYSBUFF.*#PHYSBUFF 2048#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^LOGBUFF.*#LOGBUFF 2048#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^NETTYPE.*#NETTYPE soctcp,4,50,NET#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^MULTIPROCESSOR.*#MULTIPROCESSOR 1#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^CLEANERS.*#CLEANERS 128#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^LOCKS.*#LOCKS " + ctx.fieldValue(InformixRemoteFields.LOCKS) + "#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DEF_TABLE_LOCKMODE.*#DEF_TABLE_LOCKMODE row#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DS_TOTAL_MEMORY.*#DS_TOTAL_MEMORY " + ctx.fieldValue(InformixRemoteFields.DS_TOTAL_MEMORY) + "#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DS_NONPDQ_QUERY_MEM.*#DS_NONPDQ_QUERY_MEM " + ctx.fieldValue(InformixRemoteFields.DS_NONPDQ_QUERY_MEM) + "#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^SHMVIRTSIZE.*#SHMVIRTSIZE " + ctx.fieldValue(InformixRemoteFields.SHMVIRTSIZE) + "#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^SHMADD.*#SHMADD " + ctx.fieldValue(InformixRemoteFields.SHMADD) + "#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^STACKSIZE.*#STACKSIZE 2048#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^SBSPACENAME.*#SBSPACENAME sbspace01#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DBSPACETEMP.*#DBSPACETEMP tempdbs01#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^VPCLASS cpu.*#VPCLASS " + ctx.fieldValue(InformixRemoteFields.VPCLASS) + "#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^TEMPTAB_NOLOG.*#TEMPTAB_NOLOG 1#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^NS_CACHE.*#NS_CACHE host=0,service=0,user=0,group=0#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DUMPSHMEM.*#DUMPSHMEM 0#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^USERMAPPING.*#USERMAPPING ADMIN#g\" $INFORMIXDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^BUFFERPOOL size=2k.*#BUFFERPOOL " + ctx.fieldValue(InformixRemoteFields.BUFFERPOOL_2K) + "#g\" $INFORMIXDIR/etc/$ONCONFIG &&" +
                        "echo \"BUFFERPOOL " + ctx.fieldValue(InformixRemoteFields.BUFFERPOOL_16K) + "\">>$INFORMIXDIR/etc/$ONCONFIG &&" +
                        "echo \"ENABLE_NULL_STRING 0\">>$INFORMIXDIR/etc/$ONCONFIG &&" +
                        "touch $INFORMIXDIR/etc/sysadmin/stop &&" +
                        "chown informix:informix $INFORMIXDIR/etc/sysadmin/stop &&" +
                        "mkdir -p /etc/informix &&" +
                        "echo \"USER:daemon\" > /etc/informix/allowed.surrogates &&" +
                        "onmode -ky &&" +
                        "su - informix -c \"oninit\" &&" +
                        "echo \"CREATE DEFAULT USER WITH PROPERTIES USER 'daemon'\" |dbaccess sysuser -";
        if (ctx.executeCommandWithExitStatus(paramsCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_params_failed", "优化配置参数失败！"));
        }
    }

    private static void optimizePhysicalLog(RemoteInstallExecutionContext ctx) throws Exception {
        String cmd = "source ~informix/.bash_profile && DATADIR=" + ctx.fieldValue(InformixRemoteFields.DATA_FILE_PATH) + "&&" +
                "touch ${DATADIR}/plogdbschk001 &&" +
                "chown informix:informix ${DATADIR}/plogdbschk001 &&" +
                "chmod 660 ${DATADIR}/plogdbschk001 &&" +
                "onspaces -c -d plogdbs -p ${DATADIR}/plogdbschk001 -o 0 -s " + (intValue(ctx, InformixRemoteFields.PHYSFILE) + 10000) + "&&" +
                "onparams -p -d plogdbs -s " + ctx.fieldValue(InformixRemoteFields.PHYSFILE) + " -y";
        if (ctx.executeCommandWithExitStatus(cmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_physlog_failed", "优化物理日志失败！"));
        }
    }

    private static void optimizeLogicalLog(RemoteInstallExecutionContext ctx) throws Exception {
        String logicCmd =
                "source ~informix/.bash_profile && " +
                        "DATADIR=" + ctx.fieldValue(InformixRemoteFields.DATA_FILE_PATH) + " && " +
                        "touch ${DATADIR}/llogdbschk001 && " +
                        "chown informix:informix ${DATADIR}/llogdbschk001 && " +
                        "chmod 660 ${DATADIR}/llogdbschk001 && " +
                        "onspaces -c -d llogdbs -p ${DATADIR}/llogdbschk001 -o 0 -s " +
                        (intValue(ctx, InformixRemoteFields.LOGSIZE) * intValue(ctx, InformixRemoteFields.LOGFILES) + 10240) + " && " +
                        "for i in `seq " + intValue(ctx, InformixRemoteFields.LOGFILES) + "`;do onparams -a -d llogdbs -s " +
                        intValue(ctx, InformixRemoteFields.LOGSIZE) + ";done && " +
                        "for i in `seq 7`;do onmode -l;done && " +
                        "onmode -c && " +
                        "for i in `seq 6`;do onparams -d -l $i -y;done";
        if (ctx.executeCommandWithExitStatus(logicCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_logical_log_failed", "优化逻辑日志失败！"));
        }
    }

    private static void optimizeTempDbspaces(RemoteInstallExecutionContext ctx) throws Exception {
        String[] parts = ctx.fieldValue(InformixRemoteFields.TEMPDBS).split("\\*");
        int tempdbsNum = Integer.parseInt(parts[0]);
        int tempdbsSize = Integer.parseInt(parts[1]);
        StringBuilder onspaceCmd = new StringBuilder();
        StringBuilder dbspaceTemp = new StringBuilder("tempdbs01");
        for (int num = 1; num <= tempdbsNum; num++) {
            String suffix = String.format("%02d", num);
            onspaceCmd.append("touch ${DATADIR}/tempdbs").append(suffix).append("chk001 &&")
                    .append("chown informix:informix ${DATADIR}/tempdbs").append(suffix).append("chk001 &&")
                    .append("chmod 660 ${DATADIR}/tempdbs").append(suffix).append("chk001 &&")
                    .append("onspaces -c -d tempdbs").append(suffix).append(" -p ${DATADIR}/tempdbs").append(suffix)
                    .append("chk001 -o 0 -s ").append(tempdbsSize).append(" -k 16 -t &&");
            if (num > 1) {
                dbspaceTemp.append(",tempdbs").append(suffix);
            }
        }
        String tempdbsCmd = "source ~informix/.bash_profile && DATADIR="
                + ctx.fieldValue(InformixRemoteFields.DATA_FILE_PATH) + "&&" + onspaceCmd + "onmode -wf DBSPACETEMP=" + dbspaceTemp;
        if (ctx.executeCommandWithExitStatus(tempdbsCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_temp_space_failed", "优化临时空间失败！"));
        }
    }

    private static void createSbspace(RemoteInstallExecutionContext ctx) throws Exception {
        String sbspaceCmd = "source ~informix/.bash_profile && " +
                "DATADIR=" + ctx.fieldValue(InformixRemoteFields.DATA_FILE_PATH) + " && " +
                "touch ${DATADIR}/sbspace01chk001 && " +
                "chown informix:informix ${DATADIR}/sbspace01chk001 && " +
                "chmod 660 ${DATADIR}/sbspace01chk001 && " +
                "onspaces -c -S sbspace01 -p ${DATADIR}/sbspace01chk001 -o 0 -s " +
                ctx.fieldValue(InformixRemoteFields.SBSPACE_SIZE) + " " +
                "-Df \"LOGGING = ON, AVG_LO_SIZE=1\"";
        if (ctx.executeCommandWithExitStatus(sbspaceCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.create_sbspace_failed", "创建智能大对象空间失败！"));
        }
    }

    private static void createUserDbspace(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("""
                source ~informix/.bash_profile &&\
                DATADIR=%s &&\
                touch ${DATADIR}/datadbs01chk001 &&\
                chown informix:informix ${DATADIR}/datadbs01chk001 &&\
                chmod 660 ${DATADIR}/datadbs01chk001 &&\
                onspaces -c -d datadbs01 -p ${DATADIR}/datadbs01chk001 -o 0 -s %s -k 16
                """.formatted(
                ctx.fieldValue(InformixRemoteFields.DATA_FILE_PATH),
                ctx.fieldValue(InformixRemoteFields.DATA_SPACE_SIZE)
        )) != 0) {
            throw new Exception(I18n.t("remote.install.error.create_userdbspace_failed", "创建用户数据库空间失败！"));
        }
    }

    private static void createDefaultDatabase(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("""
                source ~informix/.bash_profile &&\
                echo "create database %s in datadbs01 with log" |dbaccess - -
                """.formatted(ctx.fieldValue(InformixRemoteFields.DEFAULT_DB_NAME))) != 0) {
            throw new Exception(I18n.t("remote.install.error.create_default_db_failed", "创建默认数据库失败！"));
        }
    }

    private static void enableAutostart(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("""
                chmod +x /etc/rc.d/rc.local &&\
                sed -i '/^su - informix/d' /etc/rc.local &&\
                echo "su - informix -c \\"oninit\\"" >>/etc/rc.local
                """) != 0) {
            throw new Exception(I18n.t("remote.install.error.enable_autostart_failed", "配置开启自启动失败！"));
        }
    }

    private static void configureBackup(RemoteInstallExecutionContext ctx) throws Exception {
        String backupPath = ctx.fieldValue(InformixRemoteFields.BACKUP_PATH);
        String backupCmd = "source ~informix/.bash_profile && mkdir -p " + backupPath + "&& chown informix:informix " + backupPath + "&& chmod 775 " + backupPath +
                "&& onmode -wf TAPEBLK=2048 && onmode -wf LTAPEBLK=2048 && onmode -wf LTAPEDEV=" + backupPath + "&& onmode -wf TAPEDEV=" + backupPath +
                """
                &&\
                sed -i "s#^BACKUP_CMD.*#BACKUP_CMD=\\"ontape -a -d\\" #g" $INFORMIXDIR/etc/log_full.sh &&\
                onmode -wf ALARMPROGRAM=$INFORMIXDIR/etc/log_full.sh &&\
                sh -c 'if [ -f /etc/cron.allow ]; then grep -q "^informix$" /etc/cron.allow || echo "informix" >> /etc/cron.allow; fi; exit 0' &&\
                echo "0 0 * * * $INFORMIXDIR/scripts/backup.sh >> $INFORMIXDIR/tmp/backup.log 2>&1" | crontab -u informix -
                """;
        if (ctx.executeCommandWithExitStatus(backupCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.configure_backup_failed", "配置备份失败！"));
        }
    }

    private static int intValue(RemoteInstallExecutionContext ctx, String fieldId) {
        return Integer.parseInt(ctx.fieldValue(fieldId));
    }

    private static void installUtilityScripts(RemoteInstallExecutionContext ctx) throws Exception {
        ctx.executeCommandWithExitStatus("""
                source ~informix/.bash_profile &&
                mkdir -p $INFORMIXDIR/scripts &&
                chown informix:informix $INFORMIXDIR/scripts &&
                touch $INFORMIXDIR/scripts/backup.sh &&
                chown informix:informix $INFORMIXDIR/scripts/backup.sh &&
                chmod 775 $INFORMIXDIR/scripts/backup.sh &&
                cat <<EOF >$INFORMIXDIR/scripts/backup.sh
                #!/bin/bash
                . ~informix/.bash_profile
                onstat - |grep "On-Line" >/dev/null
                if [ \\$? -ne 1 ]
                then
                DATE=\\`date\\`
                echo "Level 0 backup of "\\$INFORMIXSERVER" strat at "\\$DATE
                ontape -s -L 0
                DATE=\\`date\\`
                echo "Level 0 backup of "\\$INFORMIXSERVER" completed at "\\$DATE
                TAPEDEV=\\`onstat -c|grep ^TAPEDEV |awk '{print \\$2}'\\`
                find \\${TAPEDEV} -mtime +7 -type f ! -name *.sh ! -name *.log |xargs rm -rf
                fi
                exit 0
                EOF
                """);

        
    }
}
