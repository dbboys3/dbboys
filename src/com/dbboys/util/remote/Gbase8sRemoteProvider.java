package com.dbboys.util.remote;

import com.dbboys.customnode.CustomInlineCssTextArea;
import com.dbboys.i18n.I18n;
import com.dbboys.vo.Connect;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public final class Gbase8sRemoteProvider implements RemoteDatabaseProvider {
    @Override
    public String id() {
        return "gbase8s";
    }

    @Override
    public String displayName() {
        return "GBASE 8S";
    }

    @Override
    public List<String> installWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.install.desc.item1", "1、远程安装仅用于Linux或Unix系统远程安装，不适用于Windows系统。"),
                I18n.t("remote.install.desc.item2", "2、安装前可准备好已下载的安装包，如未准备，可在安装过程中自动下载。"),
                I18n.t("remote.install.desc.item3", "3、安装前会自动卸载之前已存在的GBase 8s数据库安装，并清理所有相关信息。"),
                I18n.t("remote.install.desc.item4", "4、远程安装向导支持GBase 8s V8.7、GBase 8s V8.8。")
        );
    }

    @Override
    public List<String> uninstallWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.uninstall.desc.item1", "1、远程卸载仅用于Linux或Unix系统远程卸载，不适用于Windows系统。"),
                I18n.t("remote.uninstall.desc.item2", "2、远程卸载会自动卸载之前已存在的GBase 8s数据库安装，并清理所有相关信息。"),
                I18n.t("remote.uninstall.desc.item3", "3、远程卸载向导支持GBase 8s V8.7、GBase 8s V8.8。")
        );
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
            return "https://www.dbboys.com/dl/gbase8s/server/x86/latest.tar";
        }
        if (systemInfoText.contains("aarch64")) {
            return "https://www.dbboys.com/dl/gbase8s/server/arm/latest.tar";
        }
        return null;
    }

    @Override
    public boolean isPackageCompatible(String systemInfoText, String packagePath) {
        if (packagePath == null || systemInfoText == null) {
            return true;
        }
        if (systemInfoText.contains("x86_64")) {
            return packagePath.contains("x86_64");
        }
        if (systemInfoText.contains("aarch64")) {
            return packagePath.contains("aarch64") || packagePath.contains("arm");
        }
        return true;
    }

    @Override
    public List<RemoteInstallField> buildDefaultInstallFields(RemoteHostProfile hostProfile) {
        double totalMem = hostProfile == null ? 0 : hostProfile.getTotalMemoryGb();
        int cpuCount = hostProfile == null ? 1 : Math.max(hostProfile.getCpuCount(), 1);

        int locks = 1000000;
        int shmvirtSize = 102400;
        int dsTotalMemory = 102400;
        int k2Buffers = 51200;
        int k16Buffers = 51200;
        if (totalMem > 4 && totalMem <= 8) {
            shmvirtSize = 512000;
            dsTotalMemory = 512000;
            k2Buffers = 102400;
            k16Buffers = 102400;
        } else if (totalMem > 8 && totalMem <= 16) {
            shmvirtSize = 1024000;
            dsTotalMemory = 1024000;
            k2Buffers = 512000;
            k16Buffers = 204800;
        } else if (totalMem > 16 && totalMem <= 32) {
            locks = 10000000;
            shmvirtSize = 2048000;
            dsTotalMemory = 2048000;
            k2Buffers = 512000;
            k16Buffers = 409600;
        } else if (totalMem > 32 && totalMem <= 64) {
            locks = 10000000;
            shmvirtSize = 4096000;
            dsTotalMemory = 4096000;
            k2Buffers = 512000;
            k16Buffers = 819200;
        } else if (totalMem > 64 && totalMem <= 128) {
            locks = 10000000;
            shmvirtSize = 4096000;
            dsTotalMemory = 4096000;
            k2Buffers = 512000;
            k16Buffers = 2000000;
        } else if (totalMem > 128) {
            locks = 10000000;
            shmvirtSize = 10240000;
            dsTotalMemory = 4096000;
            k2Buffers = 512000;
            k16Buffers = 4000000;
        }

        List<RemoteInstallField> fields = new ArrayList<>();
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.GBASEDBT_PASSWORD, I18n.t("remote.install.cfg.gbasedbt_password.name", "gbasedbt用户密码"), "8S*P)0Od@.&", I18n.t("remote.install.cfg.gbasedbt_password.desc", "保持密码强度，部分系统如强度不够可能导致设置密码失败")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.GBASEDBTDIR, "GBASEDBTDIR", "/opt/gbase", I18n.t("remote.install.cfg.gbasedbtdir.desc", "数据库软件安装路径，无特殊要求不修改")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.GBASEDBTSERVER, "GBASEDBTSERVER", "gbase01", I18n.t("remote.install.cfg.gbasedbtserver.desc", "数据库实例名，无特殊要求不修改")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.DB_LOCALE, "DB_LOCALE", "zh_CN.utf8", I18n.t("remote.install.cfg.db_locale.desc", "默认字符集推荐utf8，如要兼容GBK使用zh_CN.gb18030-2000")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.GL_USEGLU, "GL_USEGLU", "1", I18n.t("remote.install.cfg.gl_useglu.desc", "是否开启GLU，建议开启，0关闭")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.DATA_FILE_PATH, I18n.t("remote.install.cfg.data_file_path.name", "数据文件路径"), "$GBASEDBTDIR/dbs", I18n.t("remote.install.cfg.data_file_path.desc", "如/data，路径必须存在，修改后相关空间大小根据空间可用量自动重新计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.ROOTSIZE, "ROOTSIZE", "1024000", I18n.t("remote.install.cfg.rootsize.desc", "根空间大小，建议不小于1G，固定值。")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.LISTEN_IP, I18n.t("remote.install.cfg.listen_ip.name", "监听IP"), "0.0.0.0", I18n.t("remote.install.cfg.listen_ip.desc", "默认监听所有IP，如无特殊要求不修改")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.LISTEN_PORT, I18n.t("remote.install.cfg.listen_port.name", "监听端口"), "9088", I18n.t("remote.install.cfg.listen_port.desc", "默认端口9088，如无特殊要求不修改")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.PHYSFILE, "PHYSFILE", "1024000", I18n.t("remote.install.cfg.physfile.desc", "物理日志大小，建议不小于10G，默认根据数据文件路径可用空间自动重新计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.LOGSIZE, "LOGSIZE", "102400", I18n.t("remote.install.cfg.logsize.desc", "单个逻辑日志大小，建议100MB固定值")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.LOGFILES, "LOGFILES", "10", I18n.t("remote.install.cfg.logfiles.desc", "逻辑日志个数，建议不小于100个，默认根据数据文件路径可用空间自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.TEMPDBS, I18n.t("remote.install.cfg.tempdbs.name", "临时空间配置"), "1*1024000", I18n.t("remote.install.cfg.tempdbs.desc", "数量*大小，如1*10240000，建议不小于10G，默认根据数据文件路径可用空间自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.SBSPACE_SIZE, I18n.t("remote.install.cfg.sbspace_size.name", "智能大对象空间大小"), "1024000", I18n.t("remote.install.cfg.sbspace_size.desc", "建议不小于10G，默认根据数据文件路径可用空间自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.DATA_SPACE_SIZE, I18n.t("remote.install.cfg.data_space_size.name", "用户数据空间大小"), "1024000", I18n.t("remote.install.cfg.data_space_size.desc", "建议不小于10G，默认根据数据文件路径可用空间自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.DEFAULT_DB_NAME, I18n.t("remote.install.cfg.default_db_name.name", "用户默认数据库名"), "gbasedb", I18n.t("remote.install.cfg.default_db_name.desc", "默认gbasedb，可自定义修改")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.LOCKS, "LOCKS", String.valueOf(locks), I18n.t("remote.install.cfg.locks.desc", "建议不小于10000000，默认根据内存自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.DS_TOTAL_MEMORY, "DS_TOTAL_MEMORY", String.valueOf(dsTotalMemory), I18n.t("remote.install.cfg.ds_total_memory.desc", "建议不小于4096000，默认根据内存自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.DS_NONPDQ_QUERY_MEM, "DS_NONPDQ_QUERY_MEM", String.valueOf(dsTotalMemory / 4), I18n.t("remote.install.cfg.ds_nonpdq.desc", "建议不小于1024000，默认根据内存自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.SHMVIRTSIZE, "SHMVIRTSIZE", String.valueOf(shmvirtSize), I18n.t("remote.install.cfg.shmvirtsize.desc", "建议不小于4096000，默认根据内存自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.SHMADD, "SHMADD", String.valueOf(shmvirtSize / 4), I18n.t("remote.install.cfg.shmadd.desc", "建议不小于1024000，默认根据内存自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.VPCLASS, "VPCLASS", "cpu,num=" + cpuCount + ",noage", I18n.t("remote.install.cfg.vpclass.desc", "如是numa架构多路服务器，可绑定CPU，默认等于CPU内核数量")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.BUFFERPOOL_2K, "BUFFERPOOL", "size=2k,buffers=" + k2Buffers + ",lrus=32,lru_min_dirty=50,lru_max_dirty=60", I18n.t("remote.install.cfg.bufferpool_2k.desc", "建议不小于1G，默认根据内存自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.BUFFERPOOL_16K, "BUFFERPOOL", "size=16k,buffers=" + k16Buffers + ",lrus=128,lru_min_dirty=50,lru_max_dirty=60", I18n.t("remote.install.cfg.bufferpool_16k.desc", "建议不超过内存的50%，默认根据内存自动计算")));
        fields.add(new RemoteInstallField(Gbase8sRemoteFields.BACKUP_PATH, I18n.t("remote.install.cfg.backup_path.name", "备份路径"), "$GBASEDBTDIR/backup", I18n.t("remote.install.cfg.backup_path.desc", "填写路径后每天0点执行全量备份到填写的指定路径，逻辑日志自动归档，保留7天。")));
        applyDiskSizeDefaults(fields, hostProfile == null ? 0 : hostProfile.getFreeDiskSizeGb());
        return fields;
    }

    @Override
    public List<RemoteInstallStepSpec> buildInstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.install.step4.step1.name", "卸载现有安装", "remote.install.step4.step1.desc", "kill所有gbasedbt用户进程，删除所有安装路径，删除gbasedbt数据文件，删除gbasedbt用户及组。", true, true),
                new RemoteInstallStepSpec("remote.install.step4.step2.name", "检查系统依赖", "remote.install.step4.step2.desc", "检查/opt不小于8G，权限755，/tmp不小于1G，内存不小于1G，检查所需unzip等依赖包关闭防火墙等。", true, true),
                new RemoteInstallStepSpec("remote.install.step4.step3.name", "创建用户组及用户", "remote.install.step4.step3.desc", "创建gbasedbt用户组和gbasedbt用户，配置环境变量GBASEDBTDIRGBASEDBTSERVER等。", true, true),
                new RemoteInstallStepSpec("remote.install.step4.step4.name", "安装数据库软件", "remote.install.step4.step4.desc", "安装软件到gbasedbt用户默认环境变量$GBASEDBTDIR指定路径。", true, true),
                new RemoteInstallStepSpec("remote.install.step4.step5.name", "初始化数据库实例", "remote.install.step4.step5.desc", "初始化数据库实例，数据文件路径$GBASEDBTDIR/dbs，监听IP 0.0.0.0，端口 9088。", true, true),
                new RemoteInstallStepSpec("remote.install.step4.step6.name", "优化配置参数", "remote.install.step4.step6.desc", "优化CPU内存等关键参数，启用数据库用户，关闭sysadmin，重启数据库实例。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step7.name", "优化物理日志", "remote.install.step4.step7.desc", "创建物理日志空间plogdbs，并将物理日志从rootdbs中移动到plogdbs。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step8.name", "优化逻辑日志", "remote.install.step4.step8.desc", "创建逻辑日志空间llogdbs，并将物理日志从rootdbs中移动到llogdbs。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step9.name", "优化临时空间", "remote.install.step4.step9.desc", "创建临时数据库空间tmpdbs01，避免在rootdbs中执行排序等操作。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step10.name", "创建大对象空间", "remote.install.step4.step10.desc", "创建默认智能大对象空间sbspace01，用于存放blob/clob数据。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step11.name", "创建用户数据空间", "remote.install.step4.step11.desc", "创建用户数据空间datadbs01，存放用户数据。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step12.name", "创建默认数据库", "remote.install.step4.step12.desc", "创建默认用户数据库gbasedb，存储于datadbs01。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step13.name", "配置开机自启", "remote.install.step4.step13.desc", "开机自启，自启方式为在/etc/rc.local中添加启动命令。", true, false),
                new RemoteInstallStepSpec("remote.install.step4.step14.name", "配置备份", "remote.install.step4.step14.desc", "备份脚本位于$GBASEDBTDIR/scripts/backup.sh，默认每天0备，保留7天。", false, false)
        );
    }

    @Override
    public List<RemoteInstallStepSpec> buildUninstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.uninstall.step2.step1.name", "关闭数据库进程", "remote.uninstall.step2.step1.desc", "kill所有owner为gbasedbt用户进程。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.step2.step2.name", "删除安装目录", "remote.uninstall.step2.step2.desc", "删除/GBASEDBTTMP/.infxdirs记录的所有目录，删除/GBASEDBTTMP目录，删除/opt/gbase。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.step2.step3.name", "删除数据文件", "remote.uninstall.step2.step3.desc", "删除所有owner为gbasedbt用户的文件。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.step2.step4.name", "删除用户目录", "remote.uninstall.step2.step4.desc", "如果存在/etc/gbasedbt，删除该目录。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.step2.step5.name", "删除用户及组", "remote.uninstall.step2.step5.desc", "删除gbasedbt用户及gbasedbt组。", true, true)
        );
    }

    @Override
    public void applyDiskSizeDefaults(List<RemoteInstallField> fields, double availableDiskSize) {
        int physFile = 1024000;
        int logFiles = 10;
        String tempdbs = "1*1024000";
        int sbdbsSize = 1024000;
        int datadbsSize = 1024000;

        if (availableDiskSize > 100 && availableDiskSize <= 200) {
            physFile = 5120000;
            logFiles = 50;
            tempdbs = "2*5120000";
            sbdbsSize = 5120000;
            datadbsSize = 5120000;
        } else if (availableDiskSize > 200) {
            physFile = 10240000;
            logFiles = 100;
            tempdbs = "2*10240000";
            sbdbsSize = 10240000;
            datadbsSize = 10240000;
        }

        updateFieldValue(fields, Gbase8sRemoteFields.PHYSFILE, String.valueOf(physFile));
        updateFieldValue(fields, Gbase8sRemoteFields.LOGFILES, String.valueOf(logFiles));
        updateFieldValue(fields, Gbase8sRemoteFields.TEMPDBS, tempdbs);
        updateFieldValue(fields, Gbase8sRemoteFields.SBSPACE_SIZE, String.valueOf(sbdbsSize));
        updateFieldValue(fields, Gbase8sRemoteFields.DATA_SPACE_SIZE, String.valueOf(datadbsSize));
    }

    @Override
    public void executeInstallStep(int stepNo, RemoteInstallExecutionContext context) throws Exception {
        Gbase8sRemoteWorkflow.executeInstallStep(stepNo, context);
    }

    @Override
    public void afterInstallSteps(RemoteInstallExecutionContext context) throws Exception {
        Gbase8sRemoteWorkflow.afterInstallSteps(context);
    }

    @Override
    public void populateInstallResult(RemoteInstallExecutionContext context, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        Gbase8sRemoteWorkflow.populateInstallResult(context, databaseInfoArea);
    }

    @Override
    public Connect buildInstalledConnect(RemoteInstallExecutionContext context) {
        return Gbase8sRemoteWorkflow.buildInstalledConnect(context);
    }

    @Override
    public void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext context) throws Exception {
        Gbase8sRemoteWorkflow.executeUninstallStep(stepNo, context);
    }

    @Override
    public void startInstallWizard(Stage parent) {
        RemoteInstallerUtil.startWizard(parent, this);
    }

    @Override
    public void startUninstallWizard(Stage parent) {
        RemoteUninstallerUtil.startWizard(parent, this);
    }

    private void updateFieldValue(List<RemoteInstallField> fields, String id, String value) {
        if (fields == null) {
            return;
        }
        for (RemoteInstallField field : fields) {
            if (field != null && id.equals(field.getId())) {
                field.setValue(value);
                return;
            }
        }
    }
}
