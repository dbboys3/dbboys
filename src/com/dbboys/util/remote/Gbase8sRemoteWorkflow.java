package com.dbboys.util.remote;

import com.dbboys.customnode.CustomInlineCssTextArea;
import com.dbboys.impl.dialect.gbase.GbaseDialect;
import com.dbboys.i18n.I18n;
import com.dbboys.vo.Connect;

import java.io.File;

public final class Gbase8sRemoteWorkflow {
    private Gbase8sRemoteWorkflow() {
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
                if (ctx.executeCommandWithExitStatus("ps -ef |grep gbasedbt |grep -v grep |awk '{print \"kill -9 \"$2}' |sh") != 0) {
                    throw new Exception(I18n.t("remote.uninstall.error.kill_process_failed", "kill gbasedbt用户进程失败！"));
                }
                return;
            case 2:
                if (ctx.executeCommandWithExitStatus("test -f /GBASEDBTTMP/.infxdirs") == 0) {
                    if (ctx.executeCommandWithExitStatus("cat /GBASEDBTTMP/.infxdirs  |awk '{print \"rm -rf \"$1}'|sh") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_install_dirs_failed", "删除数据库安装目录失败！"));
                    }
                    if (ctx.executeCommandWithExitStatus("rm -rf /GBASEDBTTMP") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_gbasedbttmp_failed", "删除目录/GBASEDBTTMP失败！"));
                    }
                }
                if (ctx.executeCommandWithExitStatus("test -d /opt/gbase") == 0) {
                    if (ctx.executeCommandWithExitStatus("rm -rf /opt/gbase") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_opt_gbase_failed", "删除数据库安装目录/opt/gbase失败！"));
                    }
                }
                return;
            case 3:
                ctx.executeCommandWithExitStatus("find / -user gbasedbt -group gbasedbt -exec rm -rf {} +");
                return;
            case 4:
                if (ctx.executeCommandWithExitStatus("test -d /etc/gbasedbt") == 0) {
                    if (ctx.executeCommandWithExitStatus("rm -rf /etc/gbasedbt") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_etc_gbasedbt_failed", "删除/etc/gbasedbt目录失败！"));
                    }
                }
                return;
            case 5:
                if (ctx.executeCommandWithExitStatus("id gbasedbt") == 0) {
                    if (ctx.executeCommandWithExitStatus("userdel -r -f gbasedbt") != 0) {
                        throw new Exception(I18n.t("remote.uninstall.error.remove_user_failed", "删除用户gbasedbt失败！"));
                    }
                    ctx.executeCommandWithExitStatus("groupdel gbasedbt");
                }
                return;
            default:
                throw new IllegalArgumentException("Unknown uninstall step: " + stepNo);
        }
    }

    public static void populateInstallResult(RemoteInstallExecutionContext ctx, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        String packageName = ctx.remotePackagePath() == null ? "" : new File(ctx.remotePackagePath()).getName();
        databaseInfoArea.replaceText("");
        databaseInfoArea.append(I18n.t("remote.install.result.db_version", "数据库版本") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(packageName + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

        databaseInfoArea.append(I18n.t("remote.install.result.db_instance_info", "数据库实例信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(I18n.t("remote.install.result.install_path", "安装路径") + "：" + ctx.fieldValue(Gbase8sRemoteFields.GBASEDBTDIR) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.instance_name", "实例名") + "：" + ctx.fieldValue(Gbase8sRemoteFields.GBASEDBTSERVER) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.listen_ip", "监听IP") + "：" + ctx.fieldValue(Gbase8sRemoteFields.LISTEN_IP) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.port", "端口") + "：" + ctx.fieldValue(Gbase8sRemoteFields.LISTEN_PORT) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.db_name", "库名") + "：" + ctx.fieldValue(Gbase8sRemoteFields.DEFAULT_DB_NAME) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.user_password", "用户名/密码") + "：" + Gbase8sRemoteFields.LOGIN_USERNAME + "/" + ctx.fieldValue(Gbase8sRemoteFields.GBASEDBT_PASSWORD) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.charset", "字符集") + "：" + ctx.fieldValue(Gbase8sRemoteFields.DB_LOCALE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append("GL_USEGLU：" + ctx.fieldValue(Gbase8sRemoteFields.GL_USEGLU) + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");

        databaseInfoArea.append(I18n.t("remote.install.result.space_config", "空间配置") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(I18n.t("remote.install.result.data_path", "数据文件路径") + "：" + ctx.fieldValue(Gbase8sRemoteFields.DATA_FILE_PATH) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.physlog_size", "物理日志大小") + "：" + ctx.fieldValue(Gbase8sRemoteFields.PHYSFILE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.log_size", "逻辑日志大小") + "：" + ctx.fieldValue(Gbase8sRemoteFields.LOGSIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.log_files", "逻辑日志个数") + "：" + ctx.fieldValue(Gbase8sRemoteFields.LOGFILES) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.tempdbs", "临时空间配置") + "：" + ctx.fieldValue(Gbase8sRemoteFields.TEMPDBS) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.sbspace", "智能大对象空间大小") + "：" + ctx.fieldValue(Gbase8sRemoteFields.SBSPACE_SIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.user_space", "用户空间大小") + "：" + ctx.fieldValue(Gbase8sRemoteFields.DATA_SPACE_SIZE) + "\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.onstat_d", "onstat -d输出") + "：\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(ctx.executeCommand("source ~gbasedbt/.bash_profile;onstat -d |sed '1,2d'") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.param_config", "参数配置") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.executeCommand("source ~gbasedbt/.bash_profile;onstat -g cfg |grep -v '^$' |sed '1,5d'") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.machine", "服务器型号") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.machineInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.os", "操作系统版本") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.osInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.kernel", "内核版本") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.kernelInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.cpu", "CPU信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.cpuInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.memory", "内存信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.memoryInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.disk", "磁盘信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.diskInfo() + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.info.filesystem", "文件系统信息") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.executeCommand("df -h") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.kernel_params", "内核参数") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.executeCommand("ipcs -l") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
        databaseInfoArea.append(I18n.t("remote.install.result.gbasedbt_ulimit", "gbasedt用户限制") + "\n", "-fx-fill: #569cd6;-fx-font-weight: bold;-fx-font-family:system;");
        databaseInfoArea.append(ctx.executeCommand("su - gbasedbt -c \"ulimit -a\"") + "\n\n", "-fx-fill: -color-fg-default; -fx-font-weight: normal;-fx-font-family:Courier New;");
    }

    public static Connect buildInstalledConnect(RemoteInstallExecutionContext ctx) {
        GbaseDialect dialect = new GbaseDialect();
        Connect connect = new Connect();
        connect.setDbtype(dialect.getDbType());
        connect.setIp(ctx.host());
        connect.setPort(ctx.fieldValue(Gbase8sRemoteFields.LISTEN_PORT));
        connect.setDatabase(ctx.fieldValue(Gbase8sRemoteFields.DEFAULT_DB_NAME));
        connect.setUsername(Gbase8sRemoteFields.LOGIN_USERNAME);
        connect.setPassword(ctx.fieldValue(Gbase8sRemoteFields.GBASEDBT_PASSWORD));
        connect.setProps(dialect.defaultConnectionProps());
        connect.setProps(dialect.modifyProps(connect, "DB_LOCALE", ctx.fieldValue(Gbase8sRemoteFields.DB_LOCALE)));
        return connect;
    }

    private static void cleanupExistingInstall(RemoteInstallExecutionContext ctx) throws Exception {
        ctx.executeCommandWithExitStatus("ps -ef |grep gbasedbt |grep -v grep |awk '{print \"kill -9 \"$2}' |sh");
        ctx.executeCommandWithExitStatus("find / -user gbasedbt -exec rm -rf {} +");
        if (ctx.executeCommandWithExitStatus("test -f /GBASEDBTTMP/.infxdirs") == 0) {
            if (ctx.executeCommandWithExitStatus("cat /GBASEDBTTMP/.infxdirs  |awk '{print \"rm -rf \"$1}'|sh") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_install_dir", "删除数据库安装目录失败！"));
            }
            if (ctx.executeCommandWithExitStatus("rm -rf /GBASEDBTTMP") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_gbasedbttmp", "删除目录/GBASEDBTTMP失败！"));
            }
        }
        if (ctx.executeCommandWithExitStatus("test -d /opt/gbase") == 0) {
            if (ctx.executeCommandWithExitStatus("rm -rf /opt/gbase") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_opt_gbase", "删除数据库安装目录/opt/gbase失败！"));
            }
        }
        if (ctx.executeCommandWithExitStatus("test -d /etc/gbasedbt") == 0) {
            if (ctx.executeCommandWithExitStatus("rm -rf /etc/gbasedbt") != 0) {
                throw new Exception(I18n.t("remote.install.error.cleanup_etc_gbasedbt", "删除/etc/gbasedbt目录失败！"));
            }
        }
        ctx.executeCommandWithExitStatus("crontab -u gbasedbt -r");
        if (ctx.executeCommandWithExitStatus("test -f /usr/lib64/libnsl.so.1") != 0) {
            ctx.executeCommandWithExitStatus("ln -s /usr/lib64/libnsl.so.2  /usr/lib64/libnsl.so.1");
        }
        if (ctx.executeCommandWithExitStatus("id gbasedbt") == 0) {
            if (ctx.executeCommandWithExitStatus("userdel -r -f gbasedbt") != 0) {
                throw new Exception(I18n.t("remote.install.error.delete_gbasedbt_user", "删除用户gbasedbt失败！"));
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
        String password = ctx.fieldValue(Gbase8sRemoteFields.GBASEDBT_PASSWORD);
        if (ctx.executeCommandWithExitStatus("groupadd gbasedbt&&useradd gbasedbt -d /home/gbasedbt -m -g gbasedbt&&echo \"gbasedbt:" + password + "\" | chpasswd") != 0) {
            throw new Exception(I18n.t("remote.install.error.create_user_group_failed", "创建gbasedbt用户或组失败！"));
        }
        ctx.executeCommandWithExitStatus("cat >>~gbasedbt/.bash_profile << EOF\nexport GBASEDBTDIR=" + ctx.fieldValue(Gbase8sRemoteFields.GBASEDBTDIR) +
                "\nexport GBASEDBTSERVER=" + ctx.fieldValue(Gbase8sRemoteFields.GBASEDBTSERVER) +
                "\nexport ONCONFIG=onconfig." + ctx.fieldValue(Gbase8sRemoteFields.GBASEDBTSERVER) +
                "\nexport GBASEDBTSQLHOSTS=\\$GBASEDBTDIR/etc/sqlhosts." + ctx.fieldValue(Gbase8sRemoteFields.GBASEDBTSERVER) +
                "\nexport DB_LOCALE=" + ctx.fieldValue(Gbase8sRemoteFields.DB_LOCALE) +
                "\nexport CLIENT_LOCALE=" + ctx.fieldValue(Gbase8sRemoteFields.DB_LOCALE) +
                "\nexport GL_USEGLU=" + ctx.fieldValue(Gbase8sRemoteFields.GL_USEGLU) +
                "\nexport PATH=\\$GBASEDBTDIR/bin:/usr/bin:\\${PATH}:.\nEOF");
    }

    private static void installDatabaseBinary(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("tar -xvf " + ctx.shellQuote(ctx.remotePackagePath())) != 0) {
            throw new Exception(I18n.t("remote.install.error.extract_package_failed", "解压安装包【%s】失败！").formatted(ctx.remotePackagePath()));
        }
        int status = ctx.executeCommandWithExitStatus("source ~gbasedbt/.bash_profile && mkdir -p $GBASEDBTDIR && chown gbasedbt:gbasedbt $GBASEDBTDIR && ./ids_install -i silent -DLICENSE_ACCEPTED=TRUE");
        if (status != 0) {
            throw new Exception(I18n.t("remote.install.error.install_to_gbasedbtdir_failed", "安装数据库到$GBASEDBTDIR失败！"));
        }
    }

    private static void initializeInstance(RemoteInstallExecutionContext ctx) throws Exception {
        String cmd =
                "source ~gbasedbt/.bash_profile &&" +
                        "DATADIR=" + ctx.fieldValue(Gbase8sRemoteFields.DATA_FILE_PATH) + "&&" +
                        "mkdir -p ${DATADIR} &&" +
                        "chown gbasedbt:gbasedbt ${DATADIR} &&" +
                        "cp $GBASEDBTDIR/etc/onconfig.std  $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "chown gbasedbt:gbasedbt $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^ROOTPATH.*#ROOTPATH ${DATADIR}/rootdbschk001#g\" $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^ROOTSIZE.*#ROOTSIZE " + ctx.fieldValue(Gbase8sRemoteFields.ROOTSIZE) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^DBSERVERNAME.*#DBSERVERNAME $GBASEDBTSERVER#g\" $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^TAPEDEV.*#TAPEDEV /dev/null#g\" $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "sed -i \"s#^LTAPEDEV.*#LTAPEDEV /dev/null#g\" $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "echo \"$GBASEDBTSERVER onsoctcp " + ctx.fieldValue(Gbase8sRemoteFields.LISTEN_IP) + " " + ctx.fieldValue(Gbase8sRemoteFields.LISTEN_PORT) + "\" >> $GBASEDBTSQLHOSTS &&" +
                        "chown gbasedbt:gbasedbt $GBASEDBTSQLHOSTS &&" +
                        "touch ${DATADIR}/rootdbschk001 &&" +
                        "chown gbasedbt:gbasedbt ${DATADIR}/rootdbschk001 &&" +
                        "chmod 660 ${DATADIR}/rootdbschk001 && oninit -ivyw";
        if (ctx.executeCommandWithExitStatus(cmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.init_instance_failed", "初始化实例失败！"));
        }
    }

    private static void tuneParameters(RemoteInstallExecutionContext ctx) throws Exception {
        String paramsCmd =
                "source ~gbasedbt/.bash_profile && " +
                        "sed -i \"s#^PHYSBUFF.*#PHYSBUFF 2048#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^LOGBUFF.*#LOGBUFF 2048#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^NETTYPE.*#NETTYPE soctcp,4,50,NET#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^MULTIPROCESSOR.*#MULTIPROCESSOR 1#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^CLEANERS.*#CLEANERS 128#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^LOCKS.*#LOCKS " + ctx.fieldValue(Gbase8sRemoteFields.LOCKS) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DEF_TABLE_LOCKMODE.*#DEF_TABLE_LOCKMODE row#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DS_TOTAL_MEMORY.*#DS_TOTAL_MEMORY " + ctx.fieldValue(Gbase8sRemoteFields.DS_TOTAL_MEMORY) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DS_NONPDQ_QUERY_MEM.*#DS_NONPDQ_QUERY_MEM " + ctx.fieldValue(Gbase8sRemoteFields.DS_NONPDQ_QUERY_MEM) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^SHMVIRTSIZE.*#SHMVIRTSIZE " + ctx.fieldValue(Gbase8sRemoteFields.SHMVIRTSIZE) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^SHMADD.*#SHMADD " + ctx.fieldValue(Gbase8sRemoteFields.SHMADD) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^STACKSIZE.*#STACKSIZE 2048#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^SBSPACENAME.*#SBSPACENAME sbspace01#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DBSPACETEMP.*#DBSPACETEMP tempdbs01#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^VPCLASS cpu.*#VPCLASS " + ctx.fieldValue(Gbase8sRemoteFields.VPCLASS) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^TEMPTAB_NOLOG.*#TEMPTAB_NOLOG 1#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^NS_CACHE.*#NS_CACHE host=0,service=0,user=0,group=0#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^DUMPSHMEM.*#DUMPSHMEM 0#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^USERMAPPING.*#USERMAPPING ADMIN#g\" $GBASEDBTDIR/etc/$ONCONFIG && " +
                        "sed -i \"s#^BUFFERPOOL size=2k.*#BUFFERPOOL " + ctx.fieldValue(Gbase8sRemoteFields.BUFFERPOOL_2K) + "#g\" $GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "echo \"BUFFERPOOL " + ctx.fieldValue(Gbase8sRemoteFields.BUFFERPOOL_16K) + "\">>$GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "echo \"ENABLE_NULL_STRING 0\">>$GBASEDBTDIR/etc/$ONCONFIG &&" +
                        "touch $GBASEDBTDIR/etc/sysadmin/stop &&" +
                        "chown gbasedbt:gbasedbt $GBASEDBTDIR/etc/sysadmin/stop &&" +
                        "mkdir -p /etc/gbasedbt &&" +
                        "echo \"USER:daemon\" > /etc/gbasedbt/allowed.surrogates &&" +
                        "onmode -ky &&" +
                        "su - gbasedbt -c \"oninit\" &&" +
                        "echo \"CREATE DEFAULT USER WITH PROPERTIES USER 'daemon'\" |dbaccess sysuser -";
        if (ctx.executeCommandWithExitStatus(paramsCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_params_failed", "优化配置参数失败！"));
        }
    }

    private static void optimizePhysicalLog(RemoteInstallExecutionContext ctx) throws Exception {
        String cmd = "source ~gbasedbt/.bash_profile && DATADIR=" + ctx.fieldValue(Gbase8sRemoteFields.DATA_FILE_PATH) + "&&" +
                "touch ${DATADIR}/plogdbschk001 &&" +
                "chown gbasedbt:gbasedbt ${DATADIR}/plogdbschk001 &&" +
                "chmod 660 ${DATADIR}/plogdbschk001 &&" +
                "onspaces -c -d plogdbs -p ${DATADIR}/plogdbschk001 -o 0 -s " + (intValue(ctx, Gbase8sRemoteFields.PHYSFILE) + 10000) + "&&" +
                "onparams -p -d plogdbs -s " + ctx.fieldValue(Gbase8sRemoteFields.PHYSFILE) + " -y";
        if (ctx.executeCommandWithExitStatus(cmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_physlog_failed", "优化物理日志失败！"));
        }
    }

    private static void optimizeLogicalLog(RemoteInstallExecutionContext ctx) throws Exception {
        String logicCmd =
                "source ~gbasedbt/.bash_profile && " +
                        "DATADIR=" + ctx.fieldValue(Gbase8sRemoteFields.DATA_FILE_PATH) + " && " +
                        "touch ${DATADIR}/llogdbschk001 && " +
                        "chown gbasedbt:gbasedbt ${DATADIR}/llogdbschk001 && " +
                        "chmod 660 ${DATADIR}/llogdbschk001 && " +
                        "onspaces -c -d llogdbs -p ${DATADIR}/llogdbschk001 -o 0 -s " +
                        (intValue(ctx, Gbase8sRemoteFields.LOGSIZE) * intValue(ctx, Gbase8sRemoteFields.LOGFILES) + 10240) + " && " +
                        "for i in `seq " + intValue(ctx, Gbase8sRemoteFields.LOGFILES) + "`;do onparams -a -d llogdbs -s " +
                        intValue(ctx, Gbase8sRemoteFields.LOGSIZE) + ";done && " +
                        "for i in `seq 7`;do onmode -l;done && " +
                        "onmode -c && " +
                        "for i in `seq 6`;do onparams -d -l $i -y;done";
        if (ctx.executeCommandWithExitStatus(logicCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_logical_log_failed", "优化逻辑日志失败！"));
        }
    }

    private static void optimizeTempDbspaces(RemoteInstallExecutionContext ctx) throws Exception {
        String[] parts = ctx.fieldValue(Gbase8sRemoteFields.TEMPDBS).split("\\*");
        int tempdbsNum = Integer.parseInt(parts[0]);
        int tempdbsSize = Integer.parseInt(parts[1]);
        StringBuilder onspaceCmd = new StringBuilder();
        StringBuilder dbspaceTemp = new StringBuilder("tempdbs01");
        for (int num = 1; num <= tempdbsNum; num++) {
            String suffix = String.format("%02d", num);
            onspaceCmd.append("touch ${DATADIR}/tempdbs").append(suffix).append("chk001 &&")
                    .append("chown gbasedbt:gbasedbt ${DATADIR}/tempdbs").append(suffix).append("chk001 &&")
                    .append("chmod 660 ${DATADIR}/tempdbs").append(suffix).append("chk001 &&")
                    .append("onspaces -c -d tempdbs").append(suffix).append(" -p ${DATADIR}/tempdbs").append(suffix)
                    .append("chk001 -o 0 -s ").append(tempdbsSize).append(" -k 16 -t &&");
            if (num > 1) {
                dbspaceTemp.append(",tempdbs").append(suffix);
            }
        }
        String tempdbsCmd = "source ~gbasedbt/.bash_profile && DATADIR="
                + ctx.fieldValue(Gbase8sRemoteFields.DATA_FILE_PATH) + "&&" + onspaceCmd + "onmode -wf DBSPACETEMP=" + dbspaceTemp;
        if (ctx.executeCommandWithExitStatus(tempdbsCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.tune_temp_space_failed", "优化临时空间失败！"));
        }
    }

    private static void createSbspace(RemoteInstallExecutionContext ctx) throws Exception {
        String sbspaceCmd = "source ~gbasedbt/.bash_profile && " +
                "DATADIR=" + ctx.fieldValue(Gbase8sRemoteFields.DATA_FILE_PATH) + " && " +
                "touch ${DATADIR}/sbspace01chk001 && " +
                "chown gbasedbt:gbasedbt ${DATADIR}/sbspace01chk001 && " +
                "chmod 660 ${DATADIR}/sbspace01chk001 && " +
                "onspaces -c -S sbspace01 -p ${DATADIR}/sbspace01chk001 -o 0 -s " +
                ctx.fieldValue(Gbase8sRemoteFields.SBSPACE_SIZE) + " " +
                "-Df \"LOGGING = ON, AVG_LO_SIZE=1\"";
        if (ctx.executeCommandWithExitStatus(sbspaceCmd) != 0) {
            throw new Exception(I18n.t("remote.install.error.create_sbspace_failed", "创建智能大对象空间失败！"));
        }
    }

    private static void createUserDbspace(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("""
                source ~gbasedbt/.bash_profile &&\
                DATADIR=%s &&\
                touch ${DATADIR}/datadbs01chk001 &&\
                chown gbasedbt:gbasedbt ${DATADIR}/datadbs01chk001 &&\
                chmod 660 ${DATADIR}/datadbs01chk001 &&\
                onspaces -c -d datadbs01 -p ${DATADIR}/datadbs01chk001 -o 0 -s %s -k 16
                """.formatted(
                ctx.fieldValue(Gbase8sRemoteFields.DATA_FILE_PATH),
                ctx.fieldValue(Gbase8sRemoteFields.DATA_SPACE_SIZE)
        )) != 0) {
            throw new Exception(I18n.t("remote.install.error.create_userdbspace_failed", "创建用户数据库空间失败！"));
        }
    }

    private static void createDefaultDatabase(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("""
                source ~gbasedbt/.bash_profile &&\
                echo "create database %s in datadbs01 with log" |dbaccess - -
                """.formatted(ctx.fieldValue(Gbase8sRemoteFields.DEFAULT_DB_NAME))) != 0) {
            throw new Exception(I18n.t("remote.install.error.create_default_db_failed", "创建默认数据库失败！"));
        }
    }

    private static void enableAutostart(RemoteInstallExecutionContext ctx) throws Exception {
        if (ctx.executeCommandWithExitStatus("""
                chmod +x /etc/rc.d/rc.local &&\
                sed -i '/^su - gbasedbt/d' /etc/rc.local &&\
                echo "su - gbasedbt -c \\"oninit\\"" >>/etc/rc.local
                """) != 0) {
            throw new Exception(I18n.t("remote.install.error.enable_autostart_failed", "配置开启自启动失败！"));
        }
    }

    private static void configureBackup(RemoteInstallExecutionContext ctx) throws Exception {
        String backupPath = ctx.fieldValue(Gbase8sRemoteFields.BACKUP_PATH);
        String backupCmd = "source ~gbasedbt/.bash_profile && mkdir -p " + backupPath + "&& chown gbasedbt:gbasedbt " + backupPath + "&& chmod 775 " + backupPath +
                "&& onmode -wf TAPEBLK=2048 && onmode -wf LTAPEBLK=2048 && onmode -wf LTAPEDEV=" + backupPath + "&& onmode -wf TAPEDEV=" + backupPath +
                """
                &&\
                sed -i "s#^BACKUP_CMD.*#BACKUP_CMD=\\"ontape -a -d\\" #g" $GBASEDBTDIR/etc/log_full.sh &&\
                onmode -wf ALARMPROGRAM=$GBASEDBTDIR/etc/log_full.sh &&\
                sh -c 'if [ -f /etc/cron.allow ]; then grep -q "^gbasedbt$" /etc/cron.allow || echo "gbasedbt" >> /etc/cron.allow; fi; exit 0' &&\
                echo "0 0 * * * $GBASEDBTDIR/scripts/backup.sh >> $GBASEDBTDIR/tmp/backup.log 2>&1" | crontab -u gbasedbt -
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
                source ~gbasedbt/.bash_profile &&
                mkdir -p $GBASEDBTDIR/scripts &&
                chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts &&
                touch $GBASEDBTDIR/scripts/backup.sh &&
                chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts/backup.sh &&
                chmod 775 $GBASEDBTDIR/scripts/backup.sh &&
                cat <<EOF >$GBASEDBTDIR/scripts/backup.sh
                #!/bin/bash
                . ~gbasedbt/.bash_profile
                onstat - |grep "On-Line" >/dev/null
                if [ \\$? -ne 1 ]
                then
                DATE=\\`date\\`
                echo "Level 0 backup of "\\$GBASEDBTSERVER" strat at "\\$DATE
                ontape -s -L 0
                DATE=\\`date\\`
                echo "Level 0 backup of "\\$GBASEDBTSERVER" completed at "\\$DATE
                TAPEDEV=\\`onstat -c|grep ^TAPEDEV |awk '{print \\$2}'\\`
                find \\${TAPEDEV} -mtime +7 -type f ! -name *.sh ! -name *.log |xargs rm -rf
                fi
                exit 0
                EOF
                """);

        ctx.executeCommandWithExitStatus("""
               source ~gbasedbt/.bash_profile &&\
touch $GBASEDBTDIR/scripts/GBase8schk.sh &&\
chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts/GBase8schk.sh &&\
chmod 775 $GBASEDBTDIR/scripts/GBase8schk.sh &&\
cat <<GBASEEOF >$GBASEDBTDIR/scripts/GBase8schk.sh
#!/bin/bash
###################################################################################
# filename: GBase8schk.sh
# Last modified by: L3 2025-11-25
# support OS: Linux
# support database version: GBase 8s V8.x
# useage: sh GBase8schk.sh [0]
# 0 do not collect statistics,this may take a long time
###################################################################################

if [[ -n "\\${GBASEDBTSERVER}" ]]; then
    INSTANCE=\\${GBASEDBTSERVER}
elif [[ -n "\\${INFORMIXSERVER}" ]]; then
    INSTANCE=\\${INFORMIXSERVER}
else
    echo "ERROR:can't found instance name!"
    exit 1
fi

echo ""
echo "Begin to collect data for INSTANCE:"\\${INSTANCE}
echo ""
mytime=\\`date '+%Y%m%d%H%M%S'\\`
outpath="GBase8schk_\\${INSTANCE}_\\${mytime}"

if [ ! -d \\${outpath} ]; then
mkdir \\${outpath}
fi

###################################################################################
## Machine
###################################################################################
echo "collect machine info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/machine.unl delimiter '|'
select
os_name,os_release,os_nodename,os_version,os_machine,os_num_procs,os_num_olprocs,
os_pagesize,os_mem_total,os_mem_free,os_open_file_lim,os_shmmax
from  sysmachineinfo;
EOF

###################################################################################
## Instance
###################################################################################
echo "collect instance info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/instance.unl delimiter '|'
select
dbinfo('UTC_TO_DATETIME',sh_boottime)||' T' start_time,
(current year to second - dbinfo('UTC_TO_DATETIME',sh_boottime))||' T'  run_time,
sh_maxchunks as maxchunks,
sh_maxdbspaces maxdbspaces,
sh_maxuserthreads maxuserthreads,
sh_maxtrans maxtrans,
sh_maxlocks locks,
sh_longtx longtxs,
dbinfo('UTC_TO_DATETIME',sh_pfclrtime)||' T'  onstat_z_running_time
from sysshmvals;
EOF

###################################################################################
## CPUVP
###################################################################################
echo "collect cpuvp info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/cpuvp.unl delimiter '|'
select vpid,classname class,pid,round(usecs_user,2) user_cpu,round(usecs_sys,2) sys_cpu,num_ready,
total_semops,total_busy_wts,total_yields,total_spins,vp_cache_size,vp_cache_allocs
from sysvplst ;
EOF

###################################################################################
## Memory
###################################################################################
echo "collect instance memory info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/memory.unl delimiter '|'
select
indx,bufsize pagesize,
nbuffs buffers,
round(nbuffs*bufsize/1024/1024/1024,2)||'GB' buffsize,
nlrus,mindirty,maxdirty,
(bufwaits / (bufwrites + pagreads)) * 100.00 buff_wait_rate,
100 * (bufreads-dskreads)/ bufreads buff_read_rate,
100 * (bufwrites-dskwrites)/ bufwrites buff_write_rate,
fgwrites,lruwrites ,chunkwrites
from sysbufpool;
EOF

###################################################################################
## Network
###################################################################################
echo "collect sqlhosts info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sqlhosts.unl delimiter '|'
select dbsvrnm,nettype,hostname,svcname,options,
svrsecurity,netbuf_size,svrgroup
from  syssqlhosts;
EOF

###################################################################################
## Session time
###################################################################################
echo "collect session runtime info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sessiontime.unl delimiter '|'
SELECT first 500 s.sid, s.username, s.hostname, q.odb_dbname database,
dbinfo('UTC_TO_DATETIME',s.connected) conection_time,
dbinfo('UTC_TO_DATETIME',t.last_run_time) last_run_time,
current - dbinfo('UTC_TO_DATETIME',s.connected) connected_since,
current - dbinfo('UTC_TO_DATETIME',t.last_run_time) idle_time
FROM syssessions s, systcblst t, sysrstcb r, sysopendb q
WHERE t.tid = r.tid AND s.sid = r.sid AND s.sid = q.odb_sessionid
ORDER BY 8 DESC;
EOF

###################################################################################
## Session wait
###################################################################################
echo "collect session waits info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sessionwait.unl delimiter '|'
select first 20 sid,pid, username, hostname,
is_wlatch, -- blocked waiting on a latch
is_wlock, -- blocked waiting on a locked record or table
is_wbuff, -- blocked waiting on a buffer
is_wckpt, -- blocked waiting on a checkpoint
is_incrit -- session is in a critical section of transaction-- (e.g writting to disk)
from syssessions
order by  is_wlatch+is_wlock+is_wbuff+is_wckpt+is_incrit desc;
EOF

###################################################################################
## Session IO
###################################################################################
echo "collect session IO info using sql ......"
dbaccess sysmaster -  << EOF
unload to ./\\${outpath}/sessionio.unl delimiter '|'
select first 100 syssesprof.sid,isreads,iswrites,isrewrites,
isdeletes,bufreads,bufwrites,seqscans ,
pagreads ,pagwrites,total_sorts ,dsksorts  ,
max_sortdiskspace,logspused
from syssesprof, syssessions
where syssesprof.sid = syssessions.sid
order by bufreads+bufwrites desc
;
EOF

###################################################################################
## Checkpoint
###################################################################################
echo "collect checkpoint info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/checkpoint.unl delimiter '|'
select
intvl,type,caller,dbinfo('UTC_TO_DATETIME',clock_time)||' T' clock_time,
round(crit_time,4),round(flush_time,4),round(cp_time,4),n_dirty_buffs,
plogs_per_sec,llogs_per_sec,dskflush_per_sec,ckpt_logid,ckpt_logpos,physused,logused,
n_crit_waits,tot_crit_wait,longest_crit_wait,block_time
from syscheckpoint order by intvl;
EOF

###################################################################################
## Database
###################################################################################
echo "collect database info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/database.unl delimiter '|'
SELECT trim(name) dbname,trim(owner) owner, created||' T'  created_time,
TRIM(DBINFO('dbspace',partnum)) AS dbspace,
CASE WHEN is_logging+is_buff_log=1 THEN "Unbuffered logging"
     WHEN is_logging+is_buff_log=2 THEN "Buffered logging"
     WHEN is_logging+is_buff_log=0 THEN "No logging"
ELSE "" END Logging_mode
FROM sysdatabases
where trim(name) not like 'sys%';
EOF

###################################################################################
## DBspace
###################################################################################
echo "collect dbspaces info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/dbspace.unl delimiter '|'
SELECT A.dbsnum as No, trim(B.name) as name,
CASE  WHEN (bitval(B.flags,'0x10')>0 AND bitval(B.flags,'0x2')>0)
  THEN 'MirroredBlobspace'
  WHEN bitval(B.flags,'0x10')>0  THEN 'Blobspace'
  WHEN bitval(B.flags,'0x2000')>0 AND bitval(B.flags,'0x8000')>0
  THEN 'TempSbspace'
  WHEN bitval(B.flags,'0x2000')>0 THEN 'TempDbspace'
  WHEN (bitval(B.flags,'0x8000')>0 AND bitval(B.flags,'0x2')>0)
  THEN 'MirroredSbspace'
  WHEN bitval(B.flags,'0x8000')>0  THEN 'SmartBlobspace'
  WHEN bitval(B.flags,'0x2')>0    THEN 'MirroredDbspace'
        ELSE   'Dbspace'
END  as dbstype,
 round(sum(chksize)*2/1024/1024,2)||'GB'  as DBS_SIZE ,
 round(sum(decode(mdsize,-1,nfree,udfree))*2/1024/1024,2)||'GB' as free_size,
 case when sum(decode(mdsize,-1,nfree,udfree))*100/sum(decode(mdsize,-1,chksize,udsize))
   >sum(decode(mdsize,-1,nfree,nfree))*100/sum(decode(mdsize,-1,chksize,mdsize))
then TRUNC(100-sum(decode(mdsize,-1,nfree,nfree))*100/sum(decode(mdsize,-1,chksize,mdsize)),2)||"%"
else TRUNC(100-sum(decode(mdsize,-1,nfree,udfree))*100/sum(decode(mdsize,-1,chksize,udsize)),2)||"%"
    end  as used,
  TRUNC(MAX(A.pagesize/1024))||"KB" as pgsize,
  MAX(B.nchunks) as nchunks
FROM syschktab A, sysdbstab B
WHERE A.dbsnum = B.dbsnum
 GROUP BY A.dbsnum,name, 3
ORDER BY A.dbsnum;
EOF

###################################################################################
## Chunks
###################################################################################
echo "collect chunk info using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/chunks.unl delimiter '|'
SELECT  A.chknum as num, B.name as spacename,
 TRUNC((A.pagesize/1024)) as pgsize,
 A.offset offset,
 round( A.chksize*2/1024/1024,2)||'GB'  as size,
 round(decode(A.mdsize,-1,A.nfree,A.udfree)*2/1024/1024,2)||'GB' as free,
 TRUNC(100 - decode(A.mdsize,-1,A.nfree,A.udfree)*100/A.chksize,2 )  as used,
 A.fname
FROM syschktab A, sysdbstab B
WHERE A.dbsnum = B.dbsnum
order by B.dbsnum;
EOF

###################################################################################
## Chunk IO
###################################################################################
echo "collect chunk IO using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/chunk_io.unl delimiter '|'
select d.name dbspace, fname[1,125] chunk_name,reads read_count,writes write_count,
reads+writes total_count,pagesread,pageswritten,
pagesread+pageswritten total_pg
from sysmaster:syschkio c, sysmaster:syschunks k, sysmaster:sysdbspaces d
where d.dbsnum = k.dbsnum and k.chknum  = c.chunknum
order by 8 desc;
EOF

###################################################################################
## Logical Log
###################################################################################
echo "collect logical log using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/logicallog.unl delimiter '|'
SELECT  A.number as num,  A.uniqid as uid,  round(A.size*2/1024,2)||'MB' as size,
 TRIM( TRUNC(A.used*100/A.size,0)||'%') as used,
d.name as spacename,
 TRIM( A.chunk||'_'||A.offset ) as location,
 decode(A.filltime,0,'NotFull',
 dbinfo('UTC_TO_DATETIME', A.filltime)::varchar(50))||' T' as filltime,
 CASE  WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x4')>0
   THEN 'UsedBackedUp'
   WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x2')>0
   THEN 'UsedCurrent'
   WHEN bitval(A.flags,'0x1') > 0   THEN 'Used'
   ELSE   hex(A.flags)::varchar(50)
 END as flags,
 CASE  WHEN A.filltime-B.filltime > 0 THEN
  round(CAST(TRUNC(A.size/(A.filltime-B.filltime),4)
      as varchar(20))*2/1024,2)||'MB/S'
   ELSE    ' N/A '   END as pps
FROM syslogfil A, syslogfil B,syschktab c, sysdbstab d
WHERE  A.uniqid-1 = B.uniqid
and c.dbsnum = d.dbsnum
and a.chunk=c.chknum
UNION
SELECT  A.number as num,  A.uniqid as uid, round(A.size*2/1024,2)||'MB' as size,
 TRIM( TRUNC(A.used*100/A.size,0)||'%') as used,
 d.name as spacename,
 TRIM( A.chunk||'_'||A.offset ) as location,
 decode(A.filltime,0,'NotFull',
 dbinfo('UTC_TO_DATETIME', A.filltime)::varchar(50))||' T'  as filltime,
 CASE   WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x4')>0
   THEN 'UsedBackedUp'
   WHEN bitval(A.flags,'0x1') > 0 AND bitval(A.flags,'0x2')>0
   THEN 'UsedCurrent'
   WHEN bitval(A.flags,'0x1') > 0  THEN 'Used'
   WHEN bitval(A.flags,'0x8') > 0  THEN 'NewAdd'
   ELSE hex(A.flags)::varchar(50)  END as flags,
   'N/A' as pps
FROM syslogfil A ,syschktab c, sysdbstab d
WHERE ( A.uniqid = (SELECT min(uniqid) FROM syslogfil WHERE uniqid > 0)
   OR A.uniqid = 0  )
and c.dbsnum = d.dbsnum
and a.chunk=c.chknum
ORDER BY A.uniqid ;
EOF

###################################################################################
## Locks on Table
###################################################################################
echo "collect table locks using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_actlock.unl delimiter '|'
select dbsname,tabname,
sum(pf_rqlock) as locks,
sum(pf_wtlock) as lockwaits,
sum(pf_deadlk) as deadlocks
from sysactptnhdr,systabnames
where systabnames.partnum = sysactptnhdr.partnum
group by dbsname,tabname
order by lockwaits,locks desc;
EOF

dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_lock.unl delimiter '|'
select dbsname,tabname,
sum(lockreqs) as lockreqs,
sum(lockwts) as lockwaits,
sum(deadlks) as deadlocks
from sysptprof
group by dbsname,tabname
order by deadlocks desc,lockwaits desc,lockreqs desc;
EOF

###################################################################################
## Databaes Used Space
###################################################################################
echo "collect database used space using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/database_space.unl delimiter '|'
select t1.dbsname,
round(sum(ti_nptotal)*max(ti_pagesize)/1024/1024/1024,2)||'GB' allocated_size,
round(sum(ti_npused)*max(ti_pagesize)/1024/1024/1024,2)||'GB'  used_size
from systabnames t1, systabinfo t2,sysdatabases t3
where t1.partnum = t2.ti_partnum
and trim(t3.name)=trim(t1.dbsname)
group by dbsname
order by sum(ti_nptotal) desc;
EOF

###################################################################################
## Tables Space
###################################################################################
echo "collect table and index used space using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_space.unl delimiter '|'
SELECT  st.dbsname databasename,  st.tabname,
    MAX(dbinfo('UTC_TO_DATETIME',sin.ti_created)) createdtime,
    SUM( sin.ti_nextns ) extents,
    SUM( sin.ti_nrows ) nrows,
    MAX( sin.ti_nkeys ) nkeys,
    MAX( sin.ti_pagesize ) pagesize,
    SUM( sin.ti_nptotal ) nptotal,
    round(SUM( sin.ti_nptotal*sd.pagesize )/1024/1024,2)||'MB' total_size,
    SUM( sin.ti_npused ) npused,
    round(SUM( sin.ti_npused*sd.pagesize )/1024/1024,2)||'MB' used_size,
    SUM( sin.ti_npdata ) npdata,
    round(SUM( sin.ti_npdata*sd.pagesize )/1024/1024,2)||'MB' data_size
FROM
    sysmaster:systabnames st,
    sysmaster:sysdbspaces sd,
    sysmaster:systabinfo sin
WHERE
    sd.dbsnum = trunc(st.partnum / 1048576)
    AND st.partnum = sin.ti_partnum
    AND st.dbsname NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1')
    AND st.tabname[1,3] NOT IN ('sys','TBL')
GROUP BY  1,  2
ORDER BY  8 DESC;
EOF

###################################################################################
## Tables Space By Partition
###################################################################################
echo "collect table and index partition used space using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_space_frag.unl delimiter '|'
SELECT  st.dbsname databasename,  st.tabname,st.partnum partnum,
    dbinfo('UTC_TO_DATETIME',sin.ti_created) createdtime,
    sin.ti_nextns  extents,
    sin.ti_nrows nrows,
    sin.ti_nkeys  nkeys,
    sin.ti_pagesize  pagesize,
    sin.ti_nptotal  nptotal,
    round(( sin.ti_nptotal*sd.pagesize )/1024/1024,2)||'MB' total_size,
    ( sin.ti_npused ) npused,
    round(( sin.ti_npused*sd.pagesize )/1024/1024,2)||'MB' used_size,
    ( sin.ti_npdata ) npdata,
    round(( sin.ti_npdata*sd.pagesize )/1024/1024,2)||'MB' data_size
FROM
    sysmaster:systabnames st,
    sysmaster:sysdbspaces sd,
    sysmaster:systabinfo sin
WHERE
    sd.dbsnum = trunc(st.partnum / 1048576)
    AND st.partnum = sin.ti_partnum
    AND st.dbsname NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1')
    AND st.tabname[1,3] NOT IN ('sys','TBL')
ORDER BY  9 DESC;
EOF

###################################################################################
## Tables and index IO and seqscans
###################################################################################
echo "collect table and index io and seqscans using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tab_io.unl delimiter '|'
SELECT
    st.dbsname,p.tabname,SUM( sin.ti_nrows ) nrows,
    round(SUM( sin.ti_nptotal*sd.pagesize )/1024/1024,2)||'MB' total_size,
    round(SUM( sin.ti_npused*sd.pagesize )/1024/1024,2)||'MB' used_size,
    SUM( seqscans ) AS seqscans,
    SUM( pagreads ) diskreads,
    SUM( bufreads ) bufreads,
    SUM( bufwrites ) bufwrites,
    SUM( pagwrites ) diskwrites,
    SUM( pagreads )+ SUM( pagwrites ) disk_rsws,
    trunc(decode(SUM( bufreads ),0,0,(100 -((SUM( pagreads )* 100)/ SUM( bufreads + pagreads )))),2) AS rbufhits,
    trunc(decode(SUM( bufwrites ),0,0,(100 -((SUM( pagwrites )* 100)/ SUM( bufwrites + pagwrites )))),2) AS wbufhits
FROM
    sysmaster:sysptprof p,
    sysmaster:systabinfo sin,
    sysmaster:sysdbspaces sd,
    sysmaster:systabnames st
WHERE
    sd.dbsnum = trunc(st.partnum / 1048576)
    AND p.partnum = st.partnum
    AND st.partnum = sin.ti_partnum
    AND st.dbsname NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1')
    AND st.tabname[1,3] NOT IN ('sys','TBL')
GROUP BY 1,  2
ORDER BY 11 DESC;
EOF

###################################################################################
## Current slowest sql
###################################################################################
echo "collect current slowest sql using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/slowsql.unl delimiter '|'
Select first 100 sqx_estcost,sqx_estrows,sqx_sqlstatement
FROM sysmaster:syssqexplain
order by sqx_estcost desc;
EOF

###################################################################################
## Table statistics,lockmode,index keys
###################################################################################
if [[ -z "\\$1" ]]; then
echo "collect tables statistics,lockmode,index keys using sql ......"
dbaccess sysmaster -  << EOF
unload to  ./\\${outpath}/tabstat.sql delimiter ";"
select
"unload to ./\\${outpath}/"||trim(name)||"_stat.unl Select t.tabname,t.created as tabcreated,t.nrows,(select sum( ti_nrows ) from sysmaster:systabnames tn join sysmaster:systabinfo ti on ti.ti_partnum = tn.partnum  where t.tabname=tn.tabname   and dbsname = '"||trim(name)||"' )  as realrows,t.locklevel,t.ustlowts,i.idxname,"||
"trim(case when i.part1>0 then (select colname from "||trim(name)||":syscolumns where colno=i.part1 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part2>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part2 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part3>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part3 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part4>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part4 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part5>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part5 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part6>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part6 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part7>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part7 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part8>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part8 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part9>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part9 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part10>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part10 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part11>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part11 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part12>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part12 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part13>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part13 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part14>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part14 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part15>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part15 and tabid=i.tabid) else '' end)||"||
"trim(case when i.part16>0 then (select ','||colname from "||trim(name)||":syscolumns where colno=i.part16 and tabid=i.tabid) else '' end ) index_cols"||
",i.nunique "||
"from "||trim(name)||":systables t left join "||trim(name)||":sysindexes i on t.tabid=i.tabid "||
"where t.tabid>99 "||
"and t.tabtype='T' "||
"order by 4 desc,1"
from sysdatabases
where name NOT IN ('sysmaster','sysuser','sysadmin','sysutils','sysha','syscdr','syscdcv1','sys')
and is_logging=1;
EOF
dbaccess sysmaster \\${outpath}/tabstat.sql
fi

###################################################################################
## onstat cmd
###################################################################################
echo "collect instance running status using onstat commands ......"
onstat -b > ./\\${outpath}/onstat_b.unl
onstat -C all > ./\\${outpath}/onstat_C_all.unl
onstat -C > ./\\${outpath}/onstat_bigc.unl
onstat -c > ./\\${outpath}/onstat_c.unl
onstat -D > ./\\${outpath}/onstat_bigd.unl
onstat -d > ./\\${outpath}/onstat_d.unl
onstat -F  > ./\\${outpath}/onstat_F.unl
onstat -g act > ./\\${outpath}/onstat_g_act.unl
onstat -g arc > ./\\${outpath}/onstat_g_arc.unl
onstat -g ath > ./\\${outpath}/onstat_g_ath.unl
onstat -g buf > ./\\${outpath}/onstat_g_buf.unl
onstat -g cluster > ./\\${outpath}/onstat_g_cluster.unl
onstat -g cmsm > ./\\${outpath}/onstat_g_cmsm.unl
onstat -g cfg > ./\\${outpath}/onstat_g_cfg.unl
onstat -g cfg diff > ./\\${outpath}/onstat_g_cfg_diff.unl
onstat -g ckp > ./\\${outpath}/onstat_g_ckp.unl
onstat -g con > ./\\${outpath}/onstat_g_con.unl
onstat -g cpu > ./\\${outpath}/onstat_g_cpu.unl
onstat -g dic > ./\\${outpath}/onstat_g_dic.unl
onstat -g dis > ./\\${outpath}/onstat_g_dis.unl
onstat -g dsc > ./\\${outpath}/onstat_g_dsc.unl
onstat -g env > ./\\${outpath}/onstat_g_env.unl
onstat -g glo > ./\\${outpath}/onstat_g_glo.unl
onstat -g iof > ./\\${outpath}/onstat_g_iof.unl
onstat -g iog > ./\\${outpath}/onstat_g_iog.unl
onstat -g ioq > ./\\${outpath}/onstat_g_ioq.unl
onstat -g iov > ./\\${outpath}/onstat_g_iov.unl
onstat -g lmx > ./\\${outpath}/onstat_g_lmx.unl
#onstat -g mem > ./\\${outpath}/onstat_g_mem.unl
onstat -g mgm > ./\\${outpath}/onstat_g_mgm.unl
onstat -g ntd  > ./\\${outpath}/onstat_g_ntd.unl
onstat -g ntt  > ./\\${outpath}/onstat_g_ntt.unl
onstat -g ntu  > ./\\${outpath}/onstat_g_ntu.unl
onstat -g osi > ./\\${outpath}/onstat_g_osi.unl
onstat -g rea > ./\\${outpath}/onstat_g_rea.unl
onstat -g seg > ./\\${outpath}/onstat_g_seg.unl
onstat -g ses 0 > ./\\${outpath}/onstat_g_ses_0.unl
onstat -g ses > ./\\${outpath}/onstat_g_ses.unl
onstat -g smb s > ./\\${outpath}/onstat_g_smb_s.unl
#onstat -g spi | sort -n -k 2 | tail -200 > ./\\${outpath}/onstat_g_spi.unl
onstat -g sql > ./\\${outpath}/onstat_g_sql.unl
onstat -g sql 0 > ./\\${outpath}/onstat_g_sql_0.unl
#onstat -g ssc > ./\\${outpath}/onstat_g_ssc.unl
#onstat -g stk >onstat_g_stk.unl
#onstat -g sts >onstat_g_sts.unl
onstat -g wai > ./\\${outpath}/onstat_g_wai.unl
onstat -L > ./\\${outpath}/onstat_bigl.unl
onstat -l > ./\\${outpath}/onstat_l.unl
onstat -p > ./\\${outpath}/onstat_p.unl
onstat -R > ./\\${outpath}/onstat_R.unl
onstat -u > ./\\${outpath}/onstat_u.unl
onstat -V > ./\\${outpath}/onstat_V.unl
onstat -x > ./\\${outpath}/onstat_x.unl
onstat -X > ./\\${outpath}/onstat_bigx.unl

###################################################################################
## system cmd
###################################################################################
echo ""
echo "collect instance running status using system command ......"
echo ""
echo "collect cm memory ......"
ps -aux |grep cmsm > ./\\${outpath}/cm_mem.unl

echo ""
echo "collect online.log last 50000 rows......"
onlinefile=\\`onstat -m |grep 'Message Log File' | awk '{print \\$4}'\\`
tail -50000 \\${onlinefile} > ./\\${outpath}/online.log

echo ""
echo "collect current user env ......"
env > ./\\${outpath}/env.unl

echo ""
echo "collect system cpu and memory using vmstat ......"
vmstat 1 5 > ./\\${outpath}/vmstat.unl

cp GBase8schk.sh ./\\${outpath}

echo ""
echo "##################################################################"
echo "GBase 8s Database Health Check Finshed"
echo "tar all of the output files in path: \\${outpath}"
echo "tar -cvf \\${outpath}.tar \\${outpath} "
echo "##################################################################"

###################################################################################
## end of all
###################################################################################
GBASEEOF
                """);

        ctx.executeCommandWithExitStatus("""
                source ~gbasedbt/.bash_profile &&\
touch $GBASEDBTDIR/scripts/GBase8smon.sh &&\
chown gbasedbt:gbasedbt $GBASEDBTDIR/scripts/GBase8smon.sh &&\
chmod 775 $GBASEDBTDIR/scripts/GBase8smon.sh &&\
cat <<GBASEEOF >$GBASEDBTDIR/scripts/GBase8smon.sh
#!/bin/bash
###################################################################################
# filename: GBase8smon.sh
# Last modified by: L3 2025-11-25
# support OS: Linux
# support database version: GBase 8s V8.x
# useage: sh GBase8smon.sh 5 100  #每5秒收集一次，收集100次
###################################################################################
# 以下信息，收集一次
if [ \\$# -lt 2 ]; then
  echo "Useage:sh gen.sh <interval> <count>"
  exit 0
else
  INTERVAL=\\$1
  COUNT=\\$2
fi
GENDATADIR=GBase8smon_\\$(date +%Y%m%d%H%M%S)
mkdir -p \\${GENDATADIR}
cd \\${GENDATADIR}
dmesg > dmesg.txt
free -m > free_m.txt
onstat -V > onstat_V.txt
onstat -d > onstat_d.txt
onstat -g seg > onstat_g_seg.txt
onstat -g env > onstat_g_env.txt
onstat -g osi > onstat_g_osi.txt
onstat -c > onstat_c.txt
onstat -g cluster > onstat_g_cluster.txt
onstat -g cmsm > onstat_g_cmsm.txt
ps -aux |grep cmsm > cm_mem.txt

# 以下信息，根据输入参数循环收集
for i in \\`seq \\$COUNT\\`
do
tmpdir=\\$(date +%Y%m%d%H%M%S)
mkdir \\$tmpdir
cd \\$tmpdir
onstat -g ses 0 > onstat_g_ses_0.txt
onstat -g stk > onstat_g_stk.txt
onstat -u > onstat_u.txt
onstat -x > onstat_x.txt
onstat -g ckp > onstat_g_ckp.txt
onstat -g ath > onstat_g_ath.txt
onstat -p > onstat_p.txt
onstat -g sql > onstat_g_sql.txt
vmstat > vmstat.txt
mpstat -P ALL > mpstat_P_ALL.txt
sar -d > sar_d.txt
cd ..
sleep \\$INTERVAL
done
cd ..
tar -cvf \\${GENDATADIR}.tar \\${GENDATADIR} >/dev/null 2>&1
rm -rf \\${GENDATADIR}
echo "GBase8smon.sh finished!"
echo "datafile is:"\\${GENDATADIR}.tar
GBASEEOF
                """);
    }
}
