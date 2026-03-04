package com.dbboys.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过本地回调接收网页登录后的 token，用于 AI API 调用。
 * 点击「通过网页登录」后启动本地 HTTP 服务，打开浏览器到登录页；
 * 登录页重定向到 http://127.0.0.1:port/callback?access_token=xxx 时，本类解析并保存 token。
 */
public final class AiAuthUtil {
    private static final Logger log = LogManager.getLogger(AiAuthUtil.class);
    private static final String CALLBACK_PATH = "/callback";
    private static final int DEFAULT_PORT = 28765;

    /** 可选大模型：OpenAI */
    public static final String PROVIDER_OPENAI = "openai";
    /** 可选大模型：豆包（火山引擎） */
    public static final String PROVIDER_DOUBAO = "doubao";

    private static final String DOUBAO_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    // 默认豆包模型，参考官方示例中的 doubao-seed-1-8-251228
    private static final String DOUBAO_DEFAULT_MODEL = "doubao-seed-1-8-251228";
    private static final String OPENAI_DEFAULT_MODEL = "gpt-3.5-turbo";

    private AiAuthUtil() {}

    /**
     * 获取当前选用的大模型提供商（openai / doubao）
     */
    public static String getProvider() {
        // 默认使用豆包；仅当配置显式为 openai 时才切到 OpenAI
        String p = ConfigManagerUtil.getProperty("AI_PROVIDER", PROVIDER_DOUBAO);
        return PROVIDER_DOUBAO.equals(p) ? PROVIDER_DOUBAO : PROVIDER_OPENAI;
    }

    /**
     * 设置大模型提供商
     */
    public static void setProvider(String provider) {
        if (provider != null && (PROVIDER_OPENAI.equals(provider) || PROVIDER_DOUBAO.equals(provider))) {
            ConfigManagerUtil.setProperty("AI_PROVIDER", provider);
        }
    }

    /**
     * 获取当前保存的 API Base URL。
     * 现在统一使用豆包官方地址，不再走 OpenAI。
     */
    public static String getApiBaseUrl() {
        return DOUBAO_BASE_URL;
    }

    /**
     * 获取当前请求使用的模型名（如 gpt-3.5-turbo / doubao-pro-32k 或火山方舟 endpoint_id）
     */
    public static String getModel() {
        String model = ConfigManagerUtil.getProperty("AI_MODEL", "").trim();
        if (model.isEmpty()) {
            return PROVIDER_DOUBAO.equals(getProvider()) ? DOUBAO_DEFAULT_MODEL : OPENAI_DEFAULT_MODEL;
        }
        return model;
    }

    /**
     * 设置模型名（可选，不设则用当前提供商默认）
     */
    public static void setModel(String model) {
        if (model != null) {
            ConfigManagerUtil.setProperty("AI_MODEL", model.trim());
        }
    }

    /**
     * 获取当前保存的 API Token
     */
    public static String getApiToken() {
        String provider = getProvider();
        String keyName = PROVIDER_DOUBAO.equals(provider)
                ? "AI_API_TOKEN_DOUBAO"
                : "AI_API_TOKEN_OPENAI";
        // 豆包默认使用内置 key，除非配置文件中显式覆盖
        if (PROVIDER_DOUBAO.equals(provider)) {
            return ConfigManagerUtil.getProperty(
                    keyName,
                    "13f1f8c1-5e29-46c9-836f-db2321b3c9b6"
            ).trim();
        }
        // 其它提供商不设置默认 key
        return ConfigManagerUtil.getProperty(keyName, "").trim();
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
        if (token != null) {
            String keyName = PROVIDER_DOUBAO.equals(getProvider())
                    ? "AI_API_TOKEN_DOUBAO"
                    : "AI_API_TOKEN_OPENAI";
            ConfigManagerUtil.setProperty(keyName, token.trim());
        }
    }

    /**
     * 保存 API Base URL
     */
    public static void setApiBaseUrl(String baseUrl) {
        if (baseUrl != null) {
            ConfigManagerUtil.setProperty("AI_API_BASE_URL", baseUrl.trim());
        }
    }

    /**
     * 获取登录页 URL（用于打开浏览器）；未配置则返回空字符串
     */
    public static String getLoginUrl() {
        return ConfigManagerUtil.getProperty("AI_LOGIN_URL", "");
    }

    /**
     * 设置登录页 URL（OAuth 授权页或获取 API Key 的页面）
     */
    public static void setLoginUrl(String url) {
        if (url != null) {
            ConfigManagerUtil.setProperty("AI_LOGIN_URL", url.trim());
        }
    }

    /**
     * 通过网页登录获取 API：启动本地回调服务，并打开浏览器到登录页。
     * 若配置了 AI_LOGIN_URL，则用 {redirect_uri} 占位符替换为实际回调地址；
     * 若未配置，则仅打开一个默认说明页或不做操作（由调用方决定）。
     *
     * @param onTokenReceived 在收到 token 后于 UI 线程执行（可用来刷新界面）
     * @return 本次使用的回调地址（如 http://127.0.0.1:28765/callback），若未启动服务则返回 null
     */
    public static String startWebLoginAndOpenBrowser(final Runnable onTokenReceived) {
        int port = DEFAULT_PORT;
        try {
            String portStr = ConfigManagerUtil.getProperty("AI_CALLBACK_PORT", String.valueOf(DEFAULT_PORT));
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            log.warn("Invalid AI_CALLBACK_PORT, use default {}", DEFAULT_PORT);
        }

        final int bindPort = port;
        final AtomicReference<String> receivedToken = new AtomicReference<>();
        final AtomicReference<ServerSocket> serverRef = new AtomicReference<>();

        Thread serverThread = new Thread(() -> {
            ServerSocket server = null;
            try {
                server = new ServerSocket(bindPort, 1, InetAddress.getByName("127.0.0.1"));
                serverRef.set(server);
                String callbackUrl = "http://127.0.0.1:" + bindPort + CALLBACK_PATH;

                String loginUrl = getLoginUrl();
                if (loginUrl != null && !loginUrl.trim().isEmpty()) {
                    String urlToOpen = loginUrl.replace("{redirect_uri}", callbackUrl)
                            .replace("{redirect_uri_encoded}", encodeUri(callbackUrl));
                    openBrowser(urlToOpen);
                } else {
                    openBrowser("https://platform.openai.com/api-keys");
                }

                Socket client = server.accept();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String line = in.readLine();
                if (line != null && line.startsWith("GET ")) {
                    int pathEnd = line.indexOf(' ', 4);
                    String pathAndQuery = pathEnd > 4 ? line.substring(4, pathEnd) : "";
                    int q = pathAndQuery.indexOf('?');
                    String path = q >= 0 ? pathAndQuery.substring(0, q) : pathAndQuery;
                    String query = q >= 0 && q + 1 < pathAndQuery.length() ? pathAndQuery.substring(q + 1) : "";

                    if (CALLBACK_PATH.equals(path)) {
                        String token = parseTokenFromQuery(query);
                        if (token != null) {
                            receivedToken.set(token);
                            ConfigManagerUtil.setProperty("AI_API_TOKEN", token);
                            if (getApiBaseUrl() == null || getApiBaseUrl().isEmpty()) {
                                ConfigManagerUtil.setProperty("AI_API_BASE_URL", "https://api.openai.com/v1");
                            }
                            if (onTokenReceived != null) {
                                javafx.application.Platform.runLater(onTokenReceived);
                            }
                        }
                    }
                }

                sendSuccessHtml(client);
                client.close();
            } catch (Exception e) {
                log.warn("AI callback server error: {}", e.getMessage());
            } finally {
                try {
                    if (server != null && !server.isClosed()) {
                        server.close();
                    }
                } catch (IOException e) {
                    log.debug("Close server: {}", e.getMessage());
                }
            }
        }, "AI-OAuth-Callback");
        serverThread.setDaemon(true);
        serverThread.start();

        return "http://127.0.0.1:" + bindPort + CALLBACK_PATH;
    }

    private static String encodeUri(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String parseTokenFromQuery(String query) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq <= 0) continue;
            String key = param.substring(0, eq).trim();
            String value = param.substring(eq + 1).trim();
            if ("access_token".equals(key) || "token".equals(key) || "api_key".equals(key)) {
                try {
                    return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException e) {
                    return value;
                }
            }
        }
        return null;
    }

    private static void sendSuccessHtml(Socket client) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>登录成功</title></head>"
                + "<body><p>登录成功，已获取 API 凭证。可关闭此页面返回应用。</p></body></html>";
        String response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n\r\n" + html;
        OutputStream out = client.getOutputStream();
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void openBrowser(String url) {
        try {
            URI uri = new URI(url);
            Desktop.getDesktop().browse(uri);
        } catch (Exception e) {
            log.warn("Open browser failed: {}", e.getMessage());
        }
    }
}
