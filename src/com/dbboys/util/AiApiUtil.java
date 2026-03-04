package com.dbboys.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 调用 OpenAI 兼容�?Chat Completions API（用�?AI 对话框）�?
 */
public final class AiApiUtil {
    private static final Logger log = LogManager.getLogger(AiApiUtil.class);
    private static final String CHAT_PATH = "/chat/completions";

    private AiApiUtil() {}

    /**
     * 发送单条用户消息，获取助手回复文本�?
     *
     * @param userMessage 用户输入
     * @return 助手回复内容，失败返�?null 并已打日�?
     */
    public static String chat(String userMessage) {
        String baseUrl = AiAuthUtil.getApiBaseUrl();
        String token = AiAuthUtil.getApiToken();
        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
            log.warn("AI API not configured (baseUrl or token missing)");
            return null;
        }

        String provider = AiAuthUtil.getProvider();
        String safeMessage = userMessage == null ? "" : userMessage;
        String endpoint;
        String json;

        if (AiAuthUtil.PROVIDER_DOUBAO.equals(provider)) {
            // 豆包：使�?/responses 接口，body 结构参考官方示�?
            endpoint = baseUrl.endsWith("/") ? baseUrl + "responses" : baseUrl + "/responses";
            json = buildDoubaoRequestJson(safeMessage);
        } else {
            // 默认 OpenAI 兼容 chat/completions
            endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + CHAT_PATH;
            json = buildOpenAiRequestJson(safeMessage);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(150000);
            conn.setReadTimeout(600000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readStream(conn.getErrorStream());
                log.warn("AI API error {}: {}", code, err);
                String userMsg = parseErrorMessage(err, code);
                return userMsg != null ? userMsg : null;
            }

            String body = readStream(conn.getInputStream());
            return parseContentFromResponse(body);
        } catch (Exception e) {
            log.warn("AI API request failed", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 校验当前配置下的 API Key 是否可用�?
     *
     * @return null 表示校验通过；非 null 为错误信息（可直接展示给用户）�?
     */
    public static String validateKey() {
        String baseUrl = AiAuthUtil.getApiBaseUrl();
        String token = AiAuthUtil.getApiToken();
        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
            return "未配置API 地址或密钥";
        }

        String provider = AiAuthUtil.getProvider();
        String endpoint;
        String json;

        String safeMessage = "ping";
        if (AiAuthUtil.PROVIDER_DOUBAO.equals(provider)) {
            endpoint = baseUrl.endsWith("/") ? baseUrl + "responses" : baseUrl + "/responses";
            json = buildDoubaoRequestJson(safeMessage);
        } else {
            endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + CHAT_PATH;
            json = buildOpenAiRequestJson(safeMessage);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                return null;
            }
            String err = readStream(conn.getErrorStream());
            log.warn("AI API key validate error {}: {}", code, err);
            String userMsg = parseErrorMessage(err, code);
            return userMsg != null ? userMsg : "HTTP " + code;
        } catch (Exception e) {
            log.warn("AI API key validate failed", e);
            return e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 运行一次豆包官方示例（图片+文本）的测试请求，返回解析出的文本结果或错误信息�?
     */
    public static String runDoubaoImageDemo() {
        String baseUrl = AiAuthUtil.getApiBaseUrl();
        String token = AiAuthUtil.getApiToken();
        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
            return "未配置豆包API 地址或密钥";
        }

        String endpoint = baseUrl.endsWith("/") ? baseUrl + "responses" : baseUrl + "/responses";

        // 构造与官方 curl 示例一致的 body（model 使用当前配置�?
        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "input_image");
        imagePart.put("image_url", "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png");

        JSONObject textPart = new JSONObject();
        textPart.put("type", "input_text");
        textPart.put("text", "你看见了什么？");

        JSONArray contentArray = new JSONArray();
        contentArray.put(imagePart);
        contentArray.put(textPart);

        JSONObject userInput = new JSONObject();
        userInput.put("role", "user");
        userInput.put("content", contentArray);

        JSONArray inputArray = new JSONArray();
        inputArray.put(userInput);

        JSONObject body = new JSONObject();
        body.put("model", AiAuthUtil.getModel());
        body.put("input", inputArray);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String resp = readStream(code == 200 ? conn.getInputStream() : conn.getErrorStream());
            if (code != 200) {
                log.warn("Doubao image demo error {}: {}", code, resp);
                String msg = parseErrorMessage(resp, code);
                return msg != null ? msg : "HTTP " + code;
            }
            String content = parseContentFromResponse(resp);
            return content != null ? content : "HTTP 200，但未解析到文本内容?";
        } catch (Exception e) {
            log.warn("Doubao image demo request failed", e);
            return e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** OpenAI 兼容 chat/completions 请求�?*/
    private static String buildOpenAiRequestJson(String userMessage) {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", userMessage);
        JSONArray messages = new JSONArray();
        messages.put(message);
        JSONObject body = new JSONObject();
        body.put("model", AiAuthUtil.getModel());
        body.put("messages", messages);
        return body.toString();
    }

    /** 豆包 Responses API 请求体（仅文本输入，参考官方示例） */
    private static String buildDoubaoRequestJson(String userMessage) {
        JSONObject textContent = new JSONObject();
        textContent.put("type", "input_text");
        textContent.put("text", userMessage);

        JSONArray contentArray = new JSONArray();
        contentArray.put(textContent);

        JSONObject userInput = new JSONObject();
        userInput.put("role", "user");
        userInput.put("content", contentArray);

        JSONArray inputArray = new JSONArray();
        inputArray.put(userInput);

        JSONObject body = new JSONObject();
        body.put("model", AiAuthUtil.getModel());
        body.put("input", inputArray);
        return body.toString();
    }

    /** �?API 错误响应 JSON 中解�?message，供界面直接展示�?*/
    private static String parseErrorMessage(String errBody, int statusCode) {
        if (errBody == null || errBody.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(errBody);
            if (root.has("error") && root.get("error") instanceof JSONObject) {
                JSONObject err = root.getJSONObject("error");
                if (err.has("message")) {
                    return "(" + statusCode + ") " + err.getString("message");
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String parseContentFromResponse(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(body);

            // 豆包 Responses v3 格式�?output 为arry，其中非后写 message 签式记录
            if (root.has("output") && root.get("output") instanceof JSONArray) {
                JSONArray outputArr = root.getJSONArray("output");
                for (int i = 0; i < outputArr.length(); i++) {
                    JSONObject item = outputArr.optJSONObject(i);
                    if (item == null) continue;
                    if ("message".equals(item.optString("type"))) {
                        String text = extractTextFromContent(item.opt("content"));
                        if (text != null && !text.isEmpty()) {
                            return text;
                        }
                    }
                }
                return null;
            }

            JSONArray choices;
            // 豆包 Responses 原有格式: { "output": { "choices": [...] } }
            if (root.has("output") && root.get("output") instanceof JSONObject) {
                JSONObject output = root.getJSONObject("output");
                if (!output.has("choices")) return null;
                choices = output.getJSONArray("choices");
            } else if (root.has("choices")) {
                // OpenAI 兼容格式: { "choices": [...] }
                choices = root.getJSONArray("choices");
            } else {
                return null;
            }

            if (choices.isEmpty()) return null;
            JSONObject choice0 = choices.getJSONObject(0);
            if (!choice0.has("message")) return null;
            JSONObject message = choice0.getJSONObject("message");
            String text = extractTextFromContent(message.opt("content"));
            return text != null && !text.isEmpty() ? text : null;
        } catch (Exception e) {
            log.warn("AI API parse response failed", e);
            return null;
        }
    }

    /** 展示关闭一个非破账的插口内容查询处理函�?*/
    private static String extractTextFromContent(Object content) {
        if (content == null) return null;
        StringBuilder sb = new StringBuilder();
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof JSONObject) {
                    JSONObject obj = (JSONObject) item;
                    if (obj.has("text")) {
                        sb.append(obj.optString("text", ""));
                    }
                } else if (item instanceof String) {
                    sb.append((String) item);
                }
            }
        } else if (content instanceof String) {
            sb.append((String) content);
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }
}


