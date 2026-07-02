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
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
        Map.entry("AI_MODEL", "deepseek-v4-pro"),
        Map.entry("AUTOCOMPLETE_ENABLED", "true"),
        Map.entry("AUTOCOMPLETE_TRIGGER_DELAY_MS", "50"),
        Map.entry("CONNECT_KEEPALIVE_SECONDS", "180"),
        Map.entry("DEFAULT_LISTVIEW_TAB", "0"),
        Map.entry("RESULT_FETCH_PER_TIME", "200"),
        Map.entry("SPLIT_DRIVER_MAIN", "0.2"),
        Map.entry("SPLIT_DRIVER_SQL", "0.6"),
        Map.entry("SQL_EDITOR_FONT_SIZE", "12"),
        Map.entry("UI_LANG", "zh-CN"),
        Map.entry("UI_THEME", "dark")
    );

    public static Map<String, String> getDefaults() {
        return DEFAULTS;
    }

    static{
        loadProperties();
    }

    // 璇诲彇閰嶇疆鏂囦欢
    private static void loadProperties() {
        try (InputStream input = new FileInputStream(filePath)) {
            properties.load(input);
        } catch (IOException e) {
            log.error("Operation failed", e);
        }
    }

    // 鑾峰彇灞炴€у€?鏂囦欢涓嶅瓨鍦ㄦ椂杩斿洖鍑哄巶榛樿鍊?
    public static String getProperty(String key) {
        String val = properties.getProperty(key);
        return val != null ? val : DEFAULTS.get(key);
    }

    // 鑾峰彇灞炴€у€?甯﹂粯璁ゅ€?,鏂囦欢涓嶅瓨鍦ㄦ椂鍏堟煡鍑哄巶榛樿鍊?娌℃湁鍐嶈繑鍥炲弬鏁?default
    public static String getProperty(String key, String defaultValue) {
        String val = properties.getProperty(key);
        return val != null ? val : DEFAULTS.getOrDefault(key, defaultValue);
    }

    // 淇敼灞炴€у€?
    public static void setProperty(String key, String value) {
        properties.setProperty(key, value);
        saveProperties();
    }

    // 淇濆瓨淇敼鍒版枃浠?
    private static void saveProperties() {
        try (OutputStream output = new FileOutputStream(filePath)) {
            properties.store(output, "Updated configuration");
        } catch (IOException e) {
            log.error("Operation failed", e);
        }
    }

}
