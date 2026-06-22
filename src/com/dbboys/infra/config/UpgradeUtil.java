package com.dbboys.infra.config;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.notification.NotificationUtil;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.app.Main;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.infra.util.DownloadManagerUtil;
import com.dbboys.model.Version;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class UpgradeUtil {
    private static final Logger log = LogManager.getLogger(UpgradeUtil.class);
    private static final String WINDOWS_VERSION_URL = "https://www.dbboys.com/dl/dbboys/windows/version.json";
    private static final String LINUX_AMD64_VERSION_URL = "https://www.dbboys.com/dl/dbboys/linux/amd64/version.txt";
    private static final String LINUX_AARCH64_VERSION_URL = "https://www.dbboys.com/dl/dbboys/linux/aarch64/version.txt";

    private record VersionCheckResult(Path softDir, Path downloadedPackage, Version latestVersion, String versionDownloadUrl) {
        private boolean hasDownloadedPackage() {
            return downloadedPackage != null;
        }

        private boolean hasNewVersion() {
            return latestVersion != null && latestVersion.getBuild() > Main.VERSION.getBuild();
        }
    }

    private UpgradeUtil() {
    }

    //初始化数据库，响应恢复出厂设置
    public static void initDB() {
        initDatabaseToFactorySettings();
    }

    public static void initDatabaseToFactorySettings() {
        log.info("Restore to factory settings.");
        if (AlertUtil.CustomAlertConfirm(
                I18n.t("upgrade.confirm.reset.title", "恢复出厂设置"),
                I18n.t("upgrade.confirm.reset.content", "恢复出厂设置将删除所有数据及配置信息并重启软件，数据库知识不受影响，确定要恢复出厂设置吗？")
        )) {
            //线程后台处理，避免前台界面卡顿
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    initDefaultConfig();
                    return null;
                }
            };
            task.setOnSucceeded(event -> {
                //AlertUtil.CustomAlert("恢复出场设置","恢复出厂设置完成，程序即将关闭，关闭后请手动启动！");
                restartExecutable();
                //Platform.exit();
            });
            AppExecutor.runTask(task);
        }
    }

    public static void checkVersion() {
        triggerVersionCheck(true, true);
    }

    public static void checkVersionOnStartup() {
        triggerVersionCheck(false, false);
    }

    public static void checkLatestVersion() {
        triggerVersionCheck(true, true);
    }

    private static void triggerVersionCheck(boolean notifyLatest, boolean alertOnError) {
        AppExecutor.runAsync(() -> {
            try {
                VersionCheckResult result = resolveVersionCheckResult();
                Platform.runLater(() -> handleVersionCheckResult(result, notifyLatest));
            } catch (Exception e) {
                log.error("Check latest version failed.", e);
                if (alertOnError) {
                    Platform.runLater(() -> AlertUtil.CustomAlert(
                            I18n.t("common.error", "错误"),
                            e.getMessage()
                    ));
                }
            }
        });
    }

    private static VersionCheckResult resolveVersionCheckResult() throws Exception {
        Path softDir = Path.of(System.getProperty("user.dir"));
        Path downloadedPackage = findDownloadedUpgradePackage(softDir);
        if (downloadedPackage != null) {
            return new VersionCheckResult(softDir, downloadedPackage, null, "");
        }

        String versionCheckUrl = resolveVersionCheckUrl();
        URL url = new URL(versionCheckUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);

        String json;
        try (InputStream in = conn.getInputStream()) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JSONObject jsonObject = new JSONObject(json);
        Version lastVersion = new Version(jsonObject);
        return new VersionCheckResult(softDir, null, lastVersion, lastVersion.getUrl());
    }

    private static String resolveVersionCheckUrl() {
        String versionCheckUrl = WINDOWS_VERSION_URL;
        String osName = System.getProperty("os.name");
        String cpuArch = System.getProperty("os.arch");
        if (!osName.contains("Windows")) {
            if (cpuArch.contains("amd64")) {
                versionCheckUrl = LINUX_AMD64_VERSION_URL;
            } else {
                versionCheckUrl = LINUX_AARCH64_VERSION_URL;
            }
        }
        return versionCheckUrl;
    }

    private static Path findDownloadedUpgradePackage(Path softDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(softDir, "dbboys.upgrade.*.zip")) {
            Iterator<Path> it = stream.iterator();
            return it.hasNext() ? it.next() : null;
        }
    }

    private static void handleVersionCheckResult(VersionCheckResult result, boolean notifyLatest) {
        if (result == null) {
            return;
        }

        if (result.hasDownloadedPackage()) {
            Path file = result.downloadedPackage();
            if (AlertUtil.CustomAlertConfirm(
                    I18n.t("upgrade.confirm.version.title", "版本更新"),
                    I18n.t("upgrade.confirm.downloaded_package", "升级包\"%s\"已完成下载，确定要升级软件吗？").formatted(file.toAbsolutePath())
            )) {
                log.info("Start upgrade package: {}", new File(file.toUri()).getName());
                try {
                    runWindowsUpdaterScript(result.softDir().resolve("app").resolve("upgrade.bat"), result.softDir());
                } catch (IOException e) {
                    log.error("Run upgrade script failed.", e);
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
                }
            }
            return;
        }

        if (result.hasNewVersion()) {
            Version lastVersion = result.latestVersion();
            if (AlertUtil.CustomAlertConfirm(
                    I18n.t("upgrade.confirm.version.title", "版本更新"),
                    I18n.t("upgrade.confirm.new_version_found", "检测到新版本\"%s\"，是否需要升级软件？").formatted(lastVersion.getVersion())
            )) {
                try {
                    String defaultName = DownloadManagerUtil.getRealFileNameFromRedirect(result.versionDownloadUrl());
                    File saveFile = new File(result.softDir().toString(), defaultName);
                    DownloadManagerUtil.addDownload(result.versionDownloadUrl(), saveFile, true, null);
                } catch (Exception e) {
                    log.error("Prepare upgrade download failed.", e);
                    AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
                }
            }
            return;
        }

        if (notifyLatest) {
            NotificationUtil.showMainNotification(
                    I18n.t("upgrade.notice.already_latest", "当前已是最新版本，无需更新！")
            );
        }
    }

    public static void launchBatUpdater(Path batFile, Path appDir) throws IOException {
        runWindowsUpdaterScript(batFile, appDir);
    }

    public static void runWindowsUpdaterScript(Path batFile, Path appDir) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd.exe");
        cmd.add("/c"); // 执行完毕后关闭 cmd
        cmd.add(batFile.toAbsolutePath().toString());
        cmd.add(appDir.toAbsolutePath().toString()); // 可作为参数传给 BAT

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO(); // 可选：显示 BAT 输出
        pb.start();

        // 主程序必须退出，否则旧 JRE 无法替换
        Platform.exit();
        System.exit(0);
    }

    private static void restartExecutable() {
        try {
            String currentDir = new File(System.getProperty("user.dir")).getAbsolutePath();
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

            // 在当前路径查找启动器并构造启动命令
            ProcessBuilder pb;
            if (isWindows) {
                File exeFile = new File(currentDir, "dbboys.exe");
                if (!exeFile.exists()) {
                    AlertUtil.CustomAlert(
                            I18n.t("upgrade.error.restart.title", "重启错误"),
                            I18n.t("upgrade.error.executable_missing", "未找到可执行文件！")
                    );
                    return;
                }
                pb = new ProcessBuilder(exeFile.getAbsolutePath());
            } else {
                File startSh = new File(currentDir, "start.sh");
                if (startSh.exists()) {
                    pb = new ProcessBuilder("bash", startSh.getAbsolutePath());
                } else {
                    File linuxBin = new File(currentDir, "bin/dbboys");
                    if (linuxBin.exists()) {
                        pb = new ProcessBuilder(linuxBin.getAbsolutePath());
                    } else {
                        AlertUtil.CustomAlert(
                                I18n.t("upgrade.error.restart.title", "重启错误"),
                                I18n.t("upgrade.error.executable_missing", "未找到可执行文件！")
                        );
                        return;
                    }
                }
            }

            pb.start();
            Platform.exit();
            System.exit(0);

        } catch (Exception e) {
            log.error("Restart executable failed.", e);
            AlertUtil.CustomAlert(
                    I18n.t("upgrade.error.restart.title", "重启错误"),
                    I18n.t("upgrade.error.restart_failed", "重启失败！")
            );
        }
    }

    public static void initCfg() {
        initDefaultConfig();
    }

    public static void initDefaultConfig() {
        LocalDbRepository.initDB();
        // 与 etc/config.properties 中出厂默认一致
        ConfigManagerUtil.setProperty("AI_MODEL", "deepseek-v4-pro");
        ConfigManagerUtil.setProperty("CONNECT_KEEPALIVE_SECONDS", "180");
        ConfigManagerUtil.setProperty("DEFAULT_LISTVIEW_TAB", "0");
        ConfigManagerUtil.setProperty("RESULT_FETCH_PER_TIME", "200");
        ConfigManagerUtil.setProperty("SPLIT_DRIVER_MAIN", "0.2");
        ConfigManagerUtil.setProperty("SPLIT_DRIVER_SQL", "0.6");
        ConfigManagerUtil.setProperty("UI_LANG", "zh-CN");
        ConfigManagerUtil.setProperty("UI_THEME", "dark");
    }
}
