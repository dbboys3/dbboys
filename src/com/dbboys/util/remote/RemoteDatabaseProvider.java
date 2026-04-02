package com.dbboys.util.remote;

import com.dbboys.customnode.CustomInlineCssTextArea;
import com.dbboys.i18n.I18n;
import com.dbboys.util.NotificationUtil;
import com.dbboys.vo.Connect;
import javafx.stage.Stage;

import java.util.List;

public interface RemoteDatabaseProvider {
    String id();

    String displayName();

    default List<String> installWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.install.desc.item1", "1、远程安装仅用于Linux或Unix系统远程安装，不适用于Windows系统。"),
                supportsPackageDownload()
                        ? I18n.t("remote.install.desc.item2", "2、安装前可准备好已下载的安装包，如未准备，可在安装过程中自动下载。")
                        : I18n.t("remote.install.desc.item2.local_only", "2、安装前请准备好已下载的安装包，并在后续步骤填写本地或远程路径。"),
                I18n.t("remote.provider.install.desc.cleanup", "3、安装前会自动卸载之前已存在的%s数据库安装，并清理所有相关信息。").formatted(displayName())
        );
    }

    default List<String> uninstallWizardDescriptionLines() {
        return List.of(
                I18n.t("remote.uninstall.desc.item1", "1、远程卸载仅用于Linux或Unix系统远程卸载，不适用于Windows系统。"),
                I18n.t("remote.provider.uninstall.desc.cleanup", "2、远程卸载会自动卸载之前已存在的%s数据库安装，并清理所有相关信息。").formatted(displayName())
        );
    }

    default boolean supportsPackageDownload() {
        return false;
    }

    default String localPackageHintText() {
        return I18n.t("remote.provider.package.local_hint", "请选择已下载的安装包，并填写本地路径。");
    }

    default String downloadHintPrefixText() {
        return I18n.t("remote.install.step3.download_prefix", "选择已下载的安装包，或点击");
    }

    default String downloadHintSuffixText() {
        return I18n.t("remote.install.step3.download_suffix", "自动下载与CPU型号匹配的最新试用版本，下载到桌面并填充下框。");
    }

    default String resolveDownloadUrl(String systemInfoText) {
        return null;
    }

    default boolean isPackageCompatible(String systemInfoText, String packagePath) {
        return true;
    }

    default String unsupportedPlatformMessage() {
        return I18n.t("remote.install.error.unknown_platform", "未知系统平台，请手动下载数据库安装包！");
    }

    default List<RemoteInstallField> buildDefaultInstallFields(RemoteHostProfile hostProfile) {
        return List.of();
    }

    default List<RemoteInstallStepSpec> buildInstallStepSpecs() {
        return List.of();
    }

    default List<RemoteInstallStepSpec> buildUninstallStepSpecs() {
        return List.of();
    }

    default void applyDiskSizeDefaults(List<RemoteInstallField> fields, double availableDiskSize) {
    }

    default void executeInstallStep(int stepNo, RemoteInstallExecutionContext context) throws Exception {
        throw new UnsupportedOperationException("Install step is not implemented for provider: " + displayName());
    }

    default void afterInstallSteps(RemoteInstallExecutionContext context) throws Exception {
    }

    default void populateInstallResult(RemoteInstallExecutionContext context, CustomInlineCssTextArea databaseInfoArea) throws Exception {
    }

    default Connect buildInstalledConnect(RemoteInstallExecutionContext context) {
        return null;
    }

    default void executeUninstallStep(int stepNo, RemoteUninstallExecutionContext context) throws Exception {
        throw new UnsupportedOperationException("Uninstall step is not implemented for provider: " + displayName());
    }

    default void startInstallWizard(Stage parent) {
        NotificationUtil.showMainNotification(
                I18n.t("remote.provider.notice.install_not_supported", "%s 远程安装暂未实现")
                        .formatted(displayName())
        );
    }

    default void startUninstallWizard(Stage parent) {
        NotificationUtil.showMainNotification(
                I18n.t("remote.provider.notice.uninstall_not_supported", "%s 远程卸载暂未实现")
                        .formatted(displayName())
        );
    }
}
