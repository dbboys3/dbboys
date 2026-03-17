package com.dbboys.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.util.function.Consumer;

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

        if (Thread.currentThread().isInterrupted()) {
            return null;
        }

        String provider = AiAuthUtil.getProvider();
        String safeMessage = userMessage == null ? "" : userMessage;
        String endpoint;
        String json;

        if (AiAuthUtil.PROVIDER_DOUBAO.equals(provider)) {
            // 豆包：使�?/responses 接口，body 结构参考官方示�?
            endpoint = baseUrl.endsWith("/") ? baseUrl + "responses" : baseUrl + "/responses";
            json = buildDoubaoRequestJson(safeMessage, false);
        } else {
            // 默认 OpenAI 兼容 chat/completions
            endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + CHAT_PATH;
            json = buildOpenAiRequestJson(safeMessage, false);
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
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("cancelled");
                }
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
        } catch (InterruptedIOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("AI API request cancelled");
            return null;
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
     * 以流式方式发送单条用户消息，边接收边回调文本增量。
     */
    public static String chatStream(String userMessage, Consumer<String> deltaConsumer) {
        String baseUrl = AiAuthUtil.getApiBaseUrl();
        String token = AiAuthUtil.getApiToken();
        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
            log.warn("AI API not configured (baseUrl or token missing)");
            return null;
        }

        if (Thread.currentThread().isInterrupted()) {
            return null;
        }

        String provider = AiAuthUtil.getProvider();
        String safeMessage = userMessage == null ? "" : userMessage;
        String endpoint;
        String json;

        if (AiAuthUtil.PROVIDER_DOUBAO.equals(provider)) {
            endpoint = baseUrl.endsWith("/") ? baseUrl + "responses" : baseUrl + "/responses";
            json = buildDoubaoRequestJson(safeMessage, true);
        } else {
            endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + CHAT_PATH;
            json = buildOpenAiRequestJson(safeMessage, true);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(150000);
            conn.setReadTimeout(600000);

            try (OutputStream os = conn.getOutputStream()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("cancelled");
                }
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readStream(conn.getErrorStream());
                log.warn("AI API stream error {}: {}", code, err);
                String userMsg = parseErrorMessage(err, code);
                return userMsg != null ? userMsg : null;
            }

            String contentType = conn.getHeaderField("Content-Type");
            if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                String streamedText = readEventStream(conn.getInputStream(), deltaConsumer);
                if (streamedText != null && !streamedText.isEmpty()) {
                    return streamedText;
                }
                log.warn("AI API stream response contained no parsable text, fallback to non-stream request");
                return chat(userMessage);
            }

            String body = readStream(conn.getInputStream());
            String text = parseContentFromResponse(body);
            if (text != null && !text.isEmpty() && deltaConsumer != null) {
                deltaConsumer.accept(text);
            }
            if (text != null && !text.isEmpty()) {
                return text;
            }
            log.warn("AI API stream request returned no parsable text, fallback to non-stream request");
            return chat(userMessage);
        } catch (InterruptedIOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("AI API stream request cancelled");
            return null;
        } catch (Exception e) {
            log.warn("AI API stream request failed", e);
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
            json = buildDoubaoRequestJson(safeMessage, false);
        } else {
            endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + CHAT_PATH;
            json = buildOpenAiRequestJson(safeMessage, false);
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
    private static String buildOpenAiRequestJson(String userMessage, boolean stream) {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", userMessage);
        JSONArray messages = new JSONArray();
        messages.put(message);
        JSONObject body = new JSONObject();
        body.put("model", AiAuthUtil.getModel());
        body.put("messages", messages);
        if (stream) {
            body.put("stream", true);
        }
        return body.toString();
    }

    /** 豆包 Responses API 请求体（仅文本输入，参考官方示例） */
    private static String buildDoubaoRequestJson(String userMessage, boolean stream) {
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
        if (stream) {
            body.put("stream", true);
        }
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
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("interrupted");
                }
                sb.append(line);
                if (r.ready()) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }

    private static String readEventStream(InputStream is, Consumer<String> deltaConsumer) throws IOException {
        if (is == null) return "";
        StringBuilder fullText = new StringBuilder();
        StringBuilder fallbackText = new StringBuilder();
        StringBuilder eventData = new StringBuilder();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("interrupted");
                }

                if (line.isEmpty()) {
                    consumeStreamEvent(eventData, fullText, fallbackText, deltaConsumer);
                    eventData.setLength(0);
                    continue;
                }

                if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append('\n');
                    }
                    eventData.append(line.substring(5).trim());
                } else if (line.startsWith("event:")
                        || line.startsWith("id:")
                        || line.startsWith("retry:")
                        || line.startsWith(":")) {
                    // 标准 SSE 元信息行，不属于 data payload
                    continue;
                } else if (eventData.length() == 0) {
                    eventData.append(line.trim());
                }
            }
        }

        consumeStreamEvent(eventData, fullText, fallbackText, deltaConsumer);
        if (fullText.length() > 0) {
            return fullText.toString();
        }
        return fallbackText.length() > 0 ? fallbackText.toString() : null;
    }

    private static void consumeStreamEvent(StringBuilder eventData,
                                           StringBuilder fullText,
                                           StringBuilder fallbackText,
                                           Consumer<String> deltaConsumer) {
        if (eventData == null || eventData.isEmpty()) {
            return;
        }

        String raw = eventData.toString().trim();
        if (raw.isEmpty() || "[DONE]".equals(raw)) {
            return;
        }

        try {
            JSONObject root = new JSONObject(raw);
            String delta = extractDeltaFromStreamEvent(root);
            if (delta != null && !delta.isEmpty()) {
                fullText.append(delta);
                if (deltaConsumer != null) {
                    deltaConsumer.accept(delta);
                }
            }

            String finalText = extractFinalTextFromStreamEvent(root);
            if (finalText != null && !finalText.isEmpty()) {
                fallbackText.setLength(0);
                fallbackText.append(finalText);
            }
        } catch (Exception e) {
            log.debug("Ignore non-json SSE event: {}", raw);
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

    private static String extractDeltaFromStreamEvent(JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONArray choices = root.optJSONArray("choices");
        if (choices != null && !choices.isEmpty()) {
            JSONObject choice0 = choices.optJSONObject(0);
            if (choice0 != null) {
                JSONObject deltaObj = choice0.optJSONObject("delta");
                if (deltaObj != null) {
                    String deltaText = extractTextFromContent(deltaObj.opt("content"));
                    if (deltaText != null && !deltaText.isEmpty()) {
                        return deltaText;
                    }
                    String content = deltaObj.optString("content", "");
                    if (!content.isEmpty()) {
                        return content;
                    }
                }
            }
        }

        String type = root.optString("type");
        if (type.contains("delta")) {
            String delta = extractTextValue(root.opt("delta"));
            if (delta != null && !delta.isEmpty()) {
                return delta;
            }
            String text = extractTextValue(root.opt("text"));
            if (text != null && !text.isEmpty()) {
                return text;
            }
            String content = extractTextFromContent(root.opt("content"));
            if (content != null && !content.isEmpty()) {
                return content;
            }
        }

        JSONObject item = root.optJSONObject("item");
        if (item != null && type.contains("delta")) {
            return extractTextFromContent(item.opt("content"));
        }
        return null;
    }

    private static String extractFinalTextFromStreamEvent(JSONObject root) {
        if (root == null) {
            return null;
        }

        JSONObject response = root.optJSONObject("response");
        if (response != null) {
            String text = parseContentFromResponse(response.toString());
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return parseContentFromResponse(root.toString());
    }

    private static String extractTextValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof JSONObject obj) {
            String text = obj.optString("text", "");
            if (!text.isEmpty()) {
                return text;
            }
            String delta = extractTextValue(obj.opt("delta"));
            if (delta != null && !delta.isEmpty()) {
                return delta;
            }
            return extractTextFromContent(obj.opt("content"));
        }
        if (value instanceof JSONArray arr) {
            return extractTextFromContent(arr);
        }
        return null;
    }
}


