package com.dbboys.infra.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.Map;
import java.util.Properties;

public class ConfigManagerUtil {
    private static final Logger log = LogManager.getLogger(ConfigManagerUtil.class);
    private static Properties properties = new Properties();
    private static String filePath="etc/config.properties";
    private static final Map<String, String> DEFAULTS = Map.of(
        "AI_MODEL", "deepseek-v4-pro",
        "AUTOCOMPLETE_ENABLED", "true",
        "AUTOCOMPLETE_TRIGGER_DELAY_MS", "50",
        "CONNECT_KEEPALIVE_SECONDS", "180",
        "DEFAULT_LISTVIEW_TAB", "0",
        "RESULT_FETCH_PER_TIME", "200",
        "SPLIT_DRIVER_MAIN", "0.2",
        "SPLIT_DRIVER_SQL", "0.6",
        "UI_LANG", "zh-CN",
        "UI_THEME", "dark"
    );

    public static Map<String, String> getDefaults() {
        return DEFAULTS;
    }

    static{
        loadProperties();
    }

    // 读取配置文件
    private static void loadProperties() {
        try (InputStream input = new FileInputStream(filePath)) {
            properties.load(input);
        } catch (IOException e) {
            log.error("Operation failed", e);
        }
    }

    // 获取属性值，文件不存在时返回出厂默认值
    public static String getProperty(String key) {
        String val = properties.getProperty(key);
        return val != null ? val : DEFAULTS.get(key);
    }

    // 获取属性值（带默认值），文件不存在时先查出厂默认值，没有再返回参数 default
    public static String getProperty(String key, String defaultValue) {
        String val = properties.getProperty(key);
        return val != null ? val : DEFAULTS.getOrDefault(key, defaultValue);
    }

    // 修改属性值
    public static void setProperty(String key, String value) {
        properties.setProperty(key, value);
        saveProperties();
    }

    // 保存修改到文件
    private static void saveProperties() {
        try (OutputStream output = new FileOutputStream(filePath)) {
            properties.store(output, "Updated configuration");
        } catch (IOException e) {
            log.error("Operation failed", e);
        }
    }

}
