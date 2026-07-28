package com.dbboys.dialect.dameng;

import com.dbboys.ui.component.CustomInlineCssTextArea;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.Connect;
import com.dbboys.remote.*;
import com.dbboys.remote.wizard.RemoteInstallWizard;
import com.dbboys.remote.wizard.RemoteUninstallWizard;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

public final class DamengRemoteProvider implements RemoteDatabaseProvider {
    @Override
    public String id() {
        return "dameng";
    }

    @Override
    public String displayName() {
        return I18n.t("remote.provider.dameng.name", "达梦");
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
            return "https://download.dameng.com/eco/adapter/DM8/202607/dm8_20260710_x86_CentOS7_64.zip";
        }
        if (systemInfoText.contains("aarch64")) {
            return "https://download.dameng.com/eco/adapter/DM8/202607/dm8_20260708_HWarm920_kylin10_sp1_64.zip";
        }
        return null;
    }

    @Override
    public String localPackageHintText() {
        return I18n.t("remote.install.dameng.package.local_hint",
                "请选择达梦DM8 ISO安装镜像文件(.iso)、DMInstall.bin或预解压的.tar.gz/.zip安装包。");
    }

    @Override
    public boolean isPackageCompatible(String systemInfoText, String packagePath) {
        if (packagePath == null || packagePath.isBlank()) {
            return true;
        }
        String lower = packagePath.toLowerCase();
        // Accept ISO, .bin, tar.gz, zip packages
        if (!lower.endsWith(".iso") && !lower.endsWith(".bin")
                && !lower.endsWith(".tar.gz") && !lower.endsWith(".tgz")
                && !lower.endsWith(".tar") && !lower.endsWith(".zip")) {
            return false;
        }
        // Must contain dameng/dm related keywords
        if (!lower.contains("dm") && !lower.contains("dameng") && !lower.contains("DM8") && !lower.contains("dm8")) {
            return false;
        }
        // Architecture check
        if (systemInfoText != null && systemInfoText.contains("x86_64")) {
            return !lower.contains("aarch64") && !lower.contains("arm64");
        }
        if (systemInfoText != null && systemInfoText.contains("aarch64")) {
            return !lower.contains("x86_64") && !lower.contains("amd64");
        }
        return true;
    }

    @Override
    public List<String> installWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.install.dameng.desc.item1", "1、远程安装仅用于Linux或Unix系统远程安装，不适用于Windows系统。"),
                I18n.t("remote.install.dameng.desc.item2", "2、安装前可准备好已下载的达梦DM8安装包(.zip)，如未准备，可在安装过程中自动下载匹配CPU的版本。"),
                I18n.t("remote.install.dameng.desc.item3", "3、向导会解压安装包、执行静默安装、初始化数据库实例、注册系统服务并启动达梦数据库。"),
                I18n.t("remote.install.dameng.desc.item4", "4、安装前会自动卸载由本向导安装的既有达梦数据库，并清理相关目录。")
        );
    }

    @Override
    public List<String> uninstallWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.uninstall.dameng.desc.item1", "1、远程卸载仅用于Linux/Unix系统，不适用于Windows系统。"),
                I18n.t("remote.uninstall.dameng.desc.item2", "2、远程卸载会停止达梦服务、删除系统服务、安装目录、数据目录以及dmdba用户/dinstall组。")
        );
    }

    @Override
    public List<RemoteInstallField> buildDefaultInstallFields(RemoteHostProfile hostProfile) {
        double totalMem = hostProfile == null ? 0 : hostProfile.getTotalMemoryGb();
        String bufferSize = totalMem >= 16 ? "16000" : totalMem >= 8 ? "8000" : totalMem >= 4 ? "4000" : "2000";

        List<RemoteInstallField> fields = new ArrayList<>();
        fields.add(new RemoteInstallField(DamengRemoteFields.PORT,
                I18n.t("remote.install.dameng.cfg.port.name", "端口"),
                "5236",
                I18n.t("remote.install.dameng.cfg.port.desc", "达梦数据库监听端口，默认5236。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.SYSDBA_PASSWORD,
                I18n.t("remote.install.dameng.cfg.sysdba_password.name", "SYSDBA密码"),
                RemotePasswordUtil.generateComplexPassword(),
                I18n.t("remote.install.dameng.cfg.sysdba_password.desc", "SYSDBA管理员账户密码，请妥善保管。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.INSTALL_PATH,
                I18n.t("remote.install.dameng.cfg.install_path.name", "安装路径"),
                "/opt/dmdbms",
                I18n.t("remote.install.dameng.cfg.install_path.desc", "达梦数据库软件安装根目录。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.DATA_PATH,
                I18n.t("remote.install.dameng.cfg.data_path.name", "数据路径"),
                "/dm/data",
                I18n.t("remote.install.dameng.cfg.data_path.desc", "数据库数据文件目录，将在此目录下创建以实例名命名的子目录。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.INSTANCE_NAME,
                I18n.t("remote.install.dameng.cfg.instance_name.name", "实例名"),
                "DMSERVER",
                I18n.t("remote.install.dameng.cfg.instance_name.desc", "达梦数据库实例名称，用于服务注册和连接标识。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.CHARSET,
                I18n.t("remote.install.dameng.cfg.charset.name", "字符集"),
                "0",
                I18n.t("remote.install.dameng.cfg.charset.desc", "0=GB18030, 1=UTF-8, 2=EUC-KR, 3=EUC-TW, 4=ISO_8859_1, 5=ISO_8859_9, 6=GBK, 7=UTF-16。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.PAGE_SIZE,
                I18n.t("remote.install.dameng.cfg.page_size.name", "页大小(KB)"),
                "32",
                I18n.t("remote.install.dameng.cfg.page_size.desc", "可选 4/8/16/32，建议32以获得更好性能。初始化后不可更改。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.CASE_SENSITIVE,
                I18n.t("remote.install.dameng.cfg.case_sensitive.name", "大小写敏感"),
                "1",
                I18n.t("remote.install.dameng.cfg.case_sensitive.desc", "1=是(Y)，0=否(N)。初始化后不可更改。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.COMPATIBLE_MODE,
                I18n.t("remote.install.dameng.cfg.compatible_mode.name", "兼容模式"),
                "0",
                I18n.t("remote.install.dameng.cfg.compatible_mode.desc", "0=不兼容, 1=SQL92, 2=Oracle, 3=MySQL, 4=DM6, 5=TERADATA, 6=POSTGRES。初始化后不可更改。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.EXTENT_SIZE,
                I18n.t("remote.install.dameng.cfg.extent_size.name", "簇大小"),
                "16",
                I18n.t("remote.install.dameng.cfg.extent_size.desc", "每个簇包含的页数，默认16（16×32K=512K）。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.BLANK_PAD_MODE,
                I18n.t("remote.install.dameng.cfg.blank_pad_mode.name", "空格填充模式"),
                "0",
                I18n.t("remote.install.dameng.cfg.blank_pad_mode.desc", "0=否（兼容Oracle），1=是。初始化后不可更改。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.LENGTH_IN_CHAR,
                I18n.t("remote.install.dameng.cfg.length_in_char.name", "VARCHAR按字符计"),
                "1",
                I18n.t("remote.install.dameng.cfg.length_in_char.desc", "1=是（VARCHAR(10)=10字符），0=否（按字节）。初始化后不可更改。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.LOG_SIZE,
                I18n.t("remote.install.dameng.cfg.log_size.name", "日志文件大小(MB)"),
                "2048",
                I18n.t("remote.install.dameng.cfg.log_size.desc", "联机重做日志文件大小(MB)，范围256-8192。")));

        fields.add(new RemoteInstallField(DamengRemoteFields.BUFFER,
                I18n.t("remote.install.dameng.cfg.buffer.name", "缓冲区(MB)"),
                bufferSize,
                I18n.t("remote.install.dameng.cfg.buffer.desc", "系统缓冲区大小(MB)，建议设为物理内存的60%-80%。")));

        return fields;
    }

    @Override
    public List<RemoteInstallStepSpec> buildInstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.install.dameng.step1.name", "卸载已有达梦安装",
                        "remote.install.dameng.step1.desc",
                        "停止已有的达梦服务和进程，删除旧的安装目录、数据目录以及dmdba用户/dinstall组。", true, true),
                new RemoteInstallStepSpec("remote.install.dameng.step2.name", "检查系统依赖",
                        "remote.install.dameng.step2.desc",
                        "检查unzip、libaio、磁盘空间(>/opt ≥5G)等系统依赖。", true, true),
                new RemoteInstallStepSpec("remote.install.dameng.step3.name", "创建用户和目录",
                        "remote.install.dameng.step3.desc",
                        "创建dinstall组和dmdba用户，创建安装和数据目录，配置文件描述符限制。", true, true),
                new RemoteInstallStepSpec("remote.install.dameng.step4.name", "解压并挂载ISO",
                        "remote.install.dameng.step4.desc",
                        "解压zip安装包获取ISO镜像文件，挂载ISO至/mnt/dm8_iso。", true, true),
                new RemoteInstallStepSpec("remote.install.dameng.step5.name", "执行静默安装",
                        "remote.install.dameng.step5.desc",
                        "使用XML配置文件运行DMInstall.bin进行静默安装至/opt/dmdbms。", true, true),
                new RemoteInstallStepSpec("remote.install.dameng.step6.name", "初始化数据库实例",
                        "remote.install.dameng.step6.desc",
                        "使用dminit工具初始化数据库实例，配置字符集、页大小、大小写敏感、兼容模式等参数。", true, true),
                new RemoteInstallStepSpec("remote.install.dameng.step7.name", "注册系统服务",
                        "remote.install.dameng.step7.desc",
                        "使用dm_service_installer.sh注册systemd系统服务，设置开机自启。", true, true),
                new RemoteInstallStepSpec("remote.install.dameng.step8.name", "启动服务",
                        "remote.install.dameng.step8.desc",
                        "启动达梦数据库服务(systemctl start DmService)。", true, true)
        );
    }

    @Override
    public List<RemoteInstallStepSpec> buildUninstallStepSpecs() {
        return List.of(
                new RemoteInstallStepSpec("remote.uninstall.dameng.step1.name", "停止达梦服务",
                        "remote.uninstall.dameng.step1.desc",
                        "停止所有达梦服务和进程（dmserver、dmwatcher等）。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.dameng.step2.name", "删除系统服务",
                        "remote.uninstall.dameng.step2.desc",
                        "删除systemd服务配置，禁用开机自启。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.dameng.step3.name", "删除目录",
                        "remote.uninstall.dameng.step3.desc",
                        "自动检测并删除达梦安装目录和数据目录。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.dameng.step4.name", "删除用户及组",
                        "remote.uninstall.dameng.step4.desc",
                        "删除dmdba用户和dinstall组。", true, true),
                new RemoteInstallStepSpec("remote.uninstall.dameng.step5.name", "清理残留文件",
                        "remote.uninstall.dameng.step5.desc",
                        "清理limits.conf中的dmdba配置和临时文件。", true, true)
        );
    }

    @Override
    public void executeInstallStep(int stepNo, RemoteInstallExecutionContext context) throws Exception {
        DamengRemoteWorkflow.executeInstallStep(stepNo, context);
    }

    @Override
    public void afterInstallSteps(RemoteInstallExecutionContext context) throws Exception {
        DamengRemoteWorkflow.afterInstallSteps(context);
    }

    @Override
    public void populateInstallResult(RemoteInstallExecutionContext context, CustomInlineCssTextArea databaseInfoArea) throws Exception {
        DamengRemoteWorkflow.populateInstallResult(context, databaseInfoArea);
    }

    @Override
    public Connect buildInstalledConnect(RemoteInstallExecutionContext context) {
        return DamengRemoteWorkflow.buildInstalledConnect(context);
    }

    @Override
    public void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext context) throws Exception {
        DamengRemoteWorkflow.executeUninstallStep(stepNo, context);
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
