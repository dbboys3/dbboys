package com.dbboys.infra.ai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.dbboys.infra.config.ConfigManager;
import java.nio.file.Path;
import com.dbboys.infra.config.ConfigManager;
import java.nio.file.StandardOpenOption;
import com.dbboys.infra.config.ConfigManager;

/**
 * AI API 配置工具。
 * API Key 仅保存在当前用户临时目录，不写入配置文件。
 */
public final class AiAuthClient {
    private static final Logger log = LogManager.getLogger(AiAuthClient.class);

    private static final String PROVIDER_DOUBAO = "doubao";
    private static final String PROVIDER_DEEPSEEK = "deepseek";
    private static final String PROVIDER_KIMI = "kimi";
    private static final String PROVIDER_QWEN = "qwen";
    private static final String DOUBAO_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    private static final String KIMI_BASE_URL = "https://api.moonshot.cn/v1";
    private static final String QWEN_BASE_URL =
            "https://dashscope.aliyuncs.com/api/v2/apps/protocols/compatible-mode/v1";
    // 默认模型与下拉框候选保持一致，避免配置缺失时出现额外模型项
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";
    private static final String KIMI_DEFAULT_MODEL = "kimi-k2.5";
    private static final String KIMI_LEGACY_MODEL = "kimi-latest";
    private static final String API_TOKEN_DIR_NAME = "dbboys";
    private static final String LEGACY_API_TOKEN_FILE_PREFIX = "ai-api-token-";

    private AiAuthClient() {}

    /**
     * 获取当前保存的 API Base URL。
     * 根据当前选择的模型返回对应官方地址。
     */
    public static String getApiBaseUrl() {
        if (isKimiModel()) {
            return KIMI_BASE_URL;
        }
        if (isDeepSeekModel()) {
            return DEEPSEEK_BASE_URL;
        }
        if (isQwenModel()) {
            return QWEN_BASE_URL;
        }
        return DOUBAO_BASE_URL;
    }

    /**
     * 获取当前请求使用的模型名（如 gpt-3.5-turbo / doubao-pro-32k 或火山方舟 endpoint_id）
     */
    public static String getModel() {
        String model = ConfigManager.getProperty("AI_MODEL", "").trim();
        if (model.isEmpty()) {
            return DEFAULT_MODEL;
        }
        return model;
    }

    public static String getCurrentProviderKey() {
        return detectProvider(getModel());
    }

    public static boolean isKimiModel() {
        return PROVIDER_KIMI.equals(getCurrentProviderKey());
    }

    public static boolean isDoubaoModel() {
        return PROVIDER_DOUBAO.equals(getCurrentProviderKey());
    }

    public static boolean isDeepSeekModel() {
        return PROVIDER_DEEPSEEK.equals(getCurrentProviderKey());
    }

    public static boolean isQwenModel() {
        return PROVIDER_QWEN.equals(getCurrentProviderKey());
    }

    /**
     * 设置模型名（可选，不设则用当前提供商默认）
     */
    public static void setModel(String model) {
        if (model != null) {
            ConfigManager.setProperty("AI_MODEL", model.trim());
        }
    }

    /**
     * 获取当前保存的 API Token
     */
    public static String getApiToken() {
        return readApiToken(getModel());
    }

    /**
     * 是否已配置可用的 API（baseUrl + token 均非空）
     */
    public static boolean hasConfiguredApi() {
        String base = getApiBaseUrl();
        String token = getApiToken();
        return base != null && !base.trim().isEmpty() && token != null && !token.trim().isEmpty();
    }

    /**
     * 保存 API Token（例如用户手动粘贴）
     */
    public static void setApiToken(String token) {
        String model = getModel();
        String safeToken = token == null ? "" : token.trim();
        try {
            if (safeToken.isEmpty()) {
                deleteApiToken(model);
            } else {
                writeApiToken(model, safeToken);
            }
            deleteLegacyApiToken();
        } catch (IOException e) {
            throw new IllegalStateException("保存 API Key 失败: " + e.getMessage(), e);
        }
    }

    public static Path getApiTokenStoragePath() {
        return resolveApiTokenPath(getModel());
    }

    /**
     * 保存 API Base URL
     */
    public static void setApiBaseUrl(String baseUrl) {
        if (baseUrl != null) {
            ConfigManager.setProperty("AI_API_BASE_URL", baseUrl.trim());
        }
    }

    private static Path resolveApiTokenPath(String model) {
        String safeModel = sanitizeModelFileName(model);
        return Path.of(
                System.getProperty("java.io.tmpdir"),
                API_TOKEN_DIR_NAME,
                safeModel
        );
    }

    private static Path resolveLegacyApiTokenPath() {
        return Path.of(
                System.getProperty("java.io.tmpdir"),
                API_TOKEN_DIR_NAME,
                LEGACY_API_TOKEN_FILE_PREFIX + "doubao" + ".txt"
        );
    }

    private static String sanitizeModelFileName(String model) {
        String safeModel = model == null ? "" : model.trim();
        if (safeModel.isEmpty()) {
            safeModel = DEFAULT_MODEL;
        }
        return safeModel.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String detectProvider(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return PROVIDER_DOUBAO;
        }
        if (normalized.startsWith("kimi-") || normalized.startsWith("moonshot-")) {
            return PROVIDER_KIMI;
        }
        if (normalized.startsWith("deepseek-")) {
            return PROVIDER_DEEPSEEK;
        }
        if (normalized.startsWith("qwen")
                || normalized.startsWith("qwq-")
                || normalized.startsWith("qvq-")) {
            return PROVIDER_QWEN;
        }
        return PROVIDER_DOUBAO;
    }

    private static String readApiToken(String model) {
        Path tokenPath = resolveApiTokenPath(model);
        String token = readTokenFile(tokenPath);
        if (!token.isEmpty()) {
            return token;
        }

        String migratedKimiToken = migrateLegacyKimiToken(model);
        if (!migratedKimiToken.isEmpty()) {
            return migratedKimiToken;
        }

        Path legacyTokenPath = resolveLegacyApiTokenPath();
        String legacyToken = readTokenFile(legacyTokenPath);
        if (legacyToken.isEmpty()) {
            return "";
        }

        try {
            writeApiToken(model, legacyToken);
            Files.deleteIfExists(legacyTokenPath);
        } catch (IOException e) {
            log.warn("Migrate AI API token failed: {} -> {}", legacyTokenPath, tokenPath, e);
        }
        return legacyToken;
    }

    private static String migrateLegacyKimiToken(String model) {
        if (!PROVIDER_KIMI.equals(detectProvider(model))) {
            return "";
        }

        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedModel.equalsIgnoreCase(KIMI_LEGACY_MODEL)) {
            return "";
        }

        Path legacyKimiTokenPath = resolveApiTokenPath(KIMI_LEGACY_MODEL);
        String legacyKimiToken = readTokenFile(legacyKimiTokenPath);
        if (legacyKimiToken.isEmpty()) {
            return "";
        }

        try {
            writeApiToken(model, legacyKimiToken);
            Files.deleteIfExists(legacyKimiTokenPath);
        } catch (IOException e) {
            log.warn("Migrate Kimi API token failed: {} -> {}", legacyKimiTokenPath, resolveApiTokenPath(model), e);
        }
        return legacyKimiToken;
    }

    private static String readTokenFile(Path tokenPath) {
        if (!Files.exists(tokenPath)) {
            return "";
        }
        try {
            return Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("Read AI API token failed: {}", tokenPath, e);
            return "";
        }
    }

    private static void writeApiToken(String model, String token) throws IOException {
        Path tokenPath = resolveApiTokenPath(model);
        Path parent = tokenPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                tokenPath,
                token,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    private static void deleteApiToken(String model) throws IOException {
        Files.deleteIfExists(resolveApiTokenPath(model));
    }

    private static void deleteLegacyApiToken() throws IOException {
        Files.deleteIfExists(resolveLegacyApiTokenPath());
    }
}
