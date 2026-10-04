package com.ayuemin.disputeai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LlmApi {
    private static final int MAX_ERROR_BODY = 6000;
    private static final Pattern KEY_QUERY = Pattern.compile("([?&](?:key|api_key|apikey)=)[^&\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("\\b(?:sk|sk-or-v1)-[A-Za-z0-9_-]{8,}\\b", Pattern.CASE_INSENSITIVE);

    private LlmApi() {}

    public static JSONObject fail(Throwable e) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            String msg = e == null ? "Неизвестная ошибка" : e.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = e == null ? "Ошибка" : e.getClass().getSimpleName();
            o.put("error", redact(msg));
        } catch (Exception ignored) {}
        return o;
    }

    public static JSONObject call(JSONObject slot, String messagesJson, boolean brief) throws Exception {
        long started = System.currentTimeMillis();
        String provider = slot.optString("provider", "openai");
        JSONArray messages = new JSONArray(messagesJson == null ? "[]" : messagesJson);
        JSONObject result;
        switch (provider) {
            case "anthropic": result = anthropic(slot, messages); break;
            case "gemini": result = gemini(slot, messages); break;
            default: result = openAi(slot, messages); break;
        }
        result.put("ms", System.currentTimeMillis() - started);
        if (brief && result.optBoolean("ok")) {
            String text = result.optString("text", "");
            if (text.length() > 200) result.put("text", text.substring(0, 200));
        }
        return result;
    }

    private static JSONObject openAi(JSONObject slot, JSONArray messages) throws Exception {
        String base = trimSlash(slot.optString("baseUrl", ""));
        String endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        JSONObject body = new JSONObject();
        body.put("model", slot.optString("model"));
        JSONArray all = new JSONArray();
        String system = slot.optString("system", "");
        if (!system.isEmpty()) all.put(new JSONObject().put("role", "system").put("content", system));
        for (int i = 0; i < messages.length(); i++) all.put(messages.get(i));
        body.put("messages", all);
        body.put("temperature", slot.optDouble("temperature", 0.8));
        body.put("stream", false);

        JSONObject json = post(endpoint, body, slot, null);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("API не вернул choices: " + safeBody(json.toString()));
        JSONObject choice = choices.getJSONObject(0);
        JSONObject msg = choice.optJSONObject("message");
        String text = extractText(msg == null ? null : msg.opt("content"));
        if ((text == null || text.isEmpty()) && msg != null) text = msg.optString("reasoning_content", "");
        JSONObject out = new JSONObject().put("ok", true).put("text", text == null ? "" : text);
        if (json.has("usage")) out.put("usage", json.get("usage"));
        if (choice.has("finish_reason")) out.put("finishReason", choice.optString("finish_reason"));
        return out;
    }

    private static JSONObject anthropic(JSONObject slot, JSONArray messages) throws Exception {
        String base = trimSlash(slot.optString("baseUrl", "https://api.anthropic.com"));
        String endpoint = base.endsWith("/v1/messages") ? base : base + "/v1/messages";
        JSONObject body = new JSONObject();
        body.put("model", slot.optString("model"));
        body.put("system", slot.optString("system", ""));
        body.put("max_tokens", 32768);
        body.put("temperature", slot.optDouble("temperature", 0.8));
        JSONArray converted = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = "assistant".equals(m.optString("role")) ? "assistant" : "user";
            converted.put(new JSONObject().put("role", role).put("content", toAnthropicBlocks(m.opt("content"))));
        }
        body.put("messages", converted);
        String[][] extra = new String[][]{{"anthropic-version", "2023-06-01"}, {"x-api-key", slot.optString("apiKey", "")}};
        JSONObject json = post(endpoint, body, slot, extra);
        String text = extractText(json.opt("content"));
        JSONObject out = new JSONObject().put("ok", true).put("text", text == null ? "" : text);
        if (json.has("usage")) out.put("usage", json.get("usage"));
        if (json.has("stop_reason")) out.put("finishReason", json.optString("stop_reason"));
        return out;
    }

    private static JSONObject gemini(JSONObject slot, JSONArray messages) throws Exception {
        String base = trimSlash(slot.optString("baseUrl", "https://generativelanguage.googleapis.com"));
        String model = URLEncoder.encode(slot.optString("model"), "UTF-8").replace("+", "%20");
        String key = URLEncoder.encode(slot.optString("apiKey", ""), "UTF-8");
        String endpoint = base + "/v1beta/models/" + model + ":generateContent" + (key.isEmpty() ? "" : "?key=" + key);
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = "assistant".equals(m.optString("role")) ? "model" : "user";
            contents.put(new JSONObject().put("role", role).put("parts", toGeminiParts(m.opt("content"))));
        }
        body.put("contents", contents);
        String system = slot.optString("system", "");
        if (!system.isEmpty()) body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        body.put("generationConfig", new JSONObject().put("temperature", slot.optDouble("temperature", 0.8)));
        JSONObject json = post(endpoint, body, slot, null);
        JSONArray cands = json.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new Exception("Gemini не вернул ответ: " + safeBody(json.toString()));
        JSONObject cand = cands.getJSONObject(0);
        JSONObject content = cand.optJSONObject("content");
        String text = content == null ? "" : extractText(content.opt("parts"));
        JSONObject out = new JSONObject().put("ok", true).put("text", text == null ? "" : text);
        if (json.has("usageMetadata")) out.put("usage", json.get("usageMetadata"));
        if (cand.has("finishReason")) out.put("finishReason", cand.optString("finishReason"));
        return out;
    }

    private static JSONObject post(String url, JSONObject body, JSONObject slot, String[][] extraHeaders) throws Exception {
        String key = slot.optString("apiKey", "");
        validateEndpoint(url, key);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        int timeout = Math.max(10, Math.min(600, slot.optInt("timeoutSec", 120))) * 1000;
        c.setConnectTimeout(Math.min(timeout, 60000));
        c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setUseCaches(false);
        c.setDoOutput(true);

        String provider = slot.optString("provider", "openai");
        if (!key.isEmpty() && !"anthropic".equals(provider) && !"gemini".equals(provider)) {
            c.setRequestProperty("Authorization", "Bearer " + key);
        }
        if (extraHeaders != null) {
            for (String[] h : extraHeaders) {
                if (h != null && h.length == 2 && h[0] != null && h[1] != null && !h[1].isEmpty()) {
                    c.setRequestProperty(h[0], h[1]);
                }
            }
        }

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }

        int code = c.getResponseCode();
        if (code >= 300 && code < 400) {
            String location = c.getHeaderField("Location");
            c.disconnect();
            throw new Exception("HTTP " + code + ": перенаправление API заблокировано" + (location == null ? "" : ". Проверьте базовый адрес."));
        }

        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(in);
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + safeBody(text));
        return new JSONObject(text);
    }

    private static void validateEndpoint(String rawUrl, String apiKey) throws Exception {
        if (rawUrl == null || rawUrl.trim().isEmpty()) throw new Exception("Не указан адрес API");
        URI uri = URI.create(rawUrl.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) throw new Exception("Разрешены только HTTPS и локальный HTTP");
        if (uri.getHost() == null || uri.getHost().isEmpty()) throw new Exception("Некорректный адрес API");
        if (uri.getUserInfo() != null) throw new Exception("Логин/пароль в URL запрещены");
        if (uri.getFragment() != null) throw new Exception("Фрагмент # в адресе API не поддерживается");

        if ("http".equals(scheme)) {
            if (apiKey != null && !apiKey.isEmpty()) throw new Exception("API-ключ не отправляется по HTTP. Используйте HTTPS.");
            if (!isPrivateHost(uri.getHost())) throw new Exception("Обычный HTTP разрешён только для localhost/локальной сети и только без API-ключа.");
        }
    }

    private static boolean isPrivateHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || "::1".equals(h) || h.endsWith(".local")) return true;
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JSONArray toAnthropicBlocks(Object content) throws Exception {
        JSONArray out = new JSONArray();
        if (content == null || content == JSONObject.NULL) return out.put(new JSONObject().put("type", "text").put("text", ""));
        if (content instanceof String) return out.put(new JSONObject().put("type", "text").put("text", content));
        if (!(content instanceof JSONArray)) return out.put(new JSONObject().put("type", "text").put("text", String.valueOf(content)));

        JSONArray arr = (JSONArray) content;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            String type = p.optString("type");
            if ("text".equals(type)) {
                out.put(new JSONObject().put("type", "text").put("text", p.optString("text")));
            } else if ("image_url".equals(type)) {
                JSONObject iu = p.optJSONObject("image_url");
                DataUrl d = parseDataUrl(iu == null ? "" : iu.optString("url"));
                if (d != null) out.put(new JSONObject().put("type", "image").put("source",
                        new JSONObject().put("type", "base64").put("media_type", d.mime).put("data", d.data)));
            } else if ("file".equals(type)) {
                JSONObject f = p.optJSONObject("file");
                DataUrl d = parseDataUrl(f == null ? "" : f.optString("file_data"));
                if (d != null) out.put(new JSONObject().put("type", "document").put("source",
                        new JSONObject().put("type", "base64").put("media_type", d.mime).put("data", d.data)));
            }
        }
        if (out.length() == 0) out.put(new JSONObject().put("type", "text").put("text", ""));
        return out;
    }

    private static JSONArray toGeminiParts(Object content) throws Exception {
        JSONArray out = new JSONArray();
        if (content == null || content == JSONObject.NULL) return out.put(new JSONObject().put("text", ""));
        if (content instanceof String) return out.put(new JSONObject().put("text", content));
        if (!(content instanceof JSONArray)) return out.put(new JSONObject().put("text", String.valueOf(content)));

        JSONArray arr = (JSONArray) content;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            String type = p.optString("type");
            if ("text".equals(type)) {
                out.put(new JSONObject().put("text", p.optString("text")));
            } else if ("image_url".equals(type)) {
                JSONObject iu = p.optJSONObject("image_url");
                DataUrl d = parseDataUrl(iu == null ? "" : iu.optString("url"));
                if (d != null) out.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", d.mime).put("data", d.data)));
            } else if ("file".equals(type)) {
                JSONObject f = p.optJSONObject("file");
                DataUrl d = parseDataUrl(f == null ? "" : f.optString("file_data"));
                if (d != null) out.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", d.mime).put("data", d.data)));
            }
        }
        if (out.length() == 0) out.put(new JSONObject().put("text", ""));
        return out;
    }

    private static DataUrl parseDataUrl(String s) {
        if (s == null || !s.startsWith("data:")) return null;
        int comma = s.indexOf(',');
        if (comma < 0) return null;
        String head = s.substring(5, comma);
        if (!head.toLowerCase(Locale.ROOT).contains(";base64")) return null;
        String mime = head.split(";", 2)[0];
        if (mime.isEmpty()) mime = "application/octet-stream";
        String data = s.substring(comma + 1);
        return data.isEmpty() ? null : new DataUrl(mime, data);
    }

    private static final class DataUrl {
        final String mime;
        final String data;
        DataUrl(String mime, String data) { this.mime = mime; this.data = data; }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String safeBody(String s) {
        s = redact(s == null ? "" : s);
        return s.length() <= MAX_ERROR_BODY ? s : s.substring(0, MAX_ERROR_BODY) + "…";
    }

    private static String redact(String s) {
        String out = s == null ? "" : s;
        out = KEY_QUERY.matcher(out).replaceAll("$1***");
        out = BEARER.matcher(out).replaceAll("Bearer ***");
        out = OPENAI_STYLE_KEY.matcher(out).replaceAll("sk-***");
        return out;
    }

    private static String extractText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                Object item = a.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject o = (JSONObject) item;
                    String t = o.optString("text", "");
                    if (t.isEmpty() && o.has("content")) t = extractText(o.opt("content"));
                    if (!t.isEmpty()) { if (b.length() > 0) b.append("\n"); b.append(t); }
                } else if (item != null) {
                    if (b.length() > 0) b.append("\n");
                    b.append(String.valueOf(item));
                }
            }
            return b.toString();
        }
        return String.valueOf(value);
    }
}
