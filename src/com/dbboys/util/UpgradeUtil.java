package com.dbboys.util;

import com.dbboys.app.AppExecutor;
import com.dbboys.db.local.LocalDbRepository;
import com.dbboys.app.Main;
import com.dbboys.i18n.I18n;
import com.dbboys.vo.Version;
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
        checkLatestVersion();
    }

    public static void checkLatestVersion() {
        String versionCheckUrl = WINDOWS_VERSION_URL;
        String versionDownloadUrl = "";
        String osName = System.getProperty("os.name");
        String cpuArch = System.getProperty("os.arch");
        if (!osName.contains("Windows")) {
            if (cpuArch.contains("amd64")) {
                versionCheckUrl = LINUX_AMD64_VERSION_URL;
            } else {
                versionCheckUrl = LINUX_AARCH64_VERSION_URL;
            }
        }
        try {
            //获取版本信息
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
            Path softDir = Path.of(System.getProperty("user.dir"));

            //获取upgrade.bat
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(softDir, "dbboys.upgrade.*.zip")) {
                Iterator<Path> it = stream.iterator();
                if (it.hasNext()) {
                    Path file = it.next();
                    if (AlertUtil.CustomAlertConfirm(
                            I18n.t("upgrade.confirm.version.title", "版本更新"),
                            I18n.t("upgrade.confirm.downloaded_package", "升级包\"%s\"已完成下载，确定要升级软件吗？").formatted(file.toAbsolutePath())
                    )) {
                        log.info("Start upgrade package: {}", new File(file.toUri()).getName());
                        runWindowsUpdaterScript(softDir.resolve("app").resolve("upgrade.bat"), softDir);
                    }
                    return;
                }
            }

            if (lastVersion.getBuild() > Main.VERSION.getBuild()) {
                versionDownloadUrl = lastVersion.getUrl();
                if (AlertUtil.CustomAlertConfirm(
                        I18n.t("upgrade.confirm.version.title", "版本更新"),
                        I18n.t("upgrade.confirm.new_version_found", "检查到新版本\"%s\"，确定要升级软件吗？").formatted(lastVersion.getVersion())
                )) {
                    String defaultName = DownloadManagerUtil.getRealFileNameFromRedirect(versionDownloadUrl);
                    File saveFile = new File(softDir.toString(), defaultName);  // 自动拼接路径
                    //下载完后会自动检查文件名，如果是升级包自动升级
                    DownloadManagerUtil.addDownload(versionDownloadUrl, saveFile, true, null);
                }
            } else {

                NotificationUtil.showMainNotification(
                        I18n.t("upgrade.notice.already_latest", "当前已是最新版本，无需更新！")
                );
            }

        } catch (Exception e) {
            AlertUtil.CustomAlert(I18n.t("common.error", "错误"), e.getMessage());
            log.error(e.getMessage(), e);
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
            // 获取当前运行程序所在目录
            String currentDir = new File(System.getProperty("user.dir")).getAbsolutePath();

            // 你的 exe 文件名（根据实际情况修改）
            String exeName = "dbboys.exe";

            // 构造 exe 路径
            File exeFile = new File(currentDir, exeName);

            if (!exeFile.exists()) {
                AlertUtil.CustomAlert(
                        I18n.t("upgrade.error.restart.title", "重启错误"),
                        I18n.t("upgrade.error.executable_missing", "未找到可执行文件！")
                );
                return;
            }

            // 启动新的进程
            new ProcessBuilder(exeFile.getAbsolutePath()).start();

            // 退出当前程序
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
        ConfigManagerUtil.setProperty("DEFAULT_LISTVIEW_TAB", "0");
        ConfigManagerUtil.setProperty("RESULT_FETCH_PER_TIME", "200");
        ConfigManagerUtil.setProperty("SPLIT_DRIVER_MAIN", "0.2");
        ConfigManagerUtil.setProperty("SPLIT_DRIVER_SQL", "0.6");
        ConfigManagerUtil.setProperty("UI_LANG", "zh-CN");
    }
}
