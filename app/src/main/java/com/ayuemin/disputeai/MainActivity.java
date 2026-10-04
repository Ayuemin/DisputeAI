package com.ayuemin.disputeai;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER = 7301;
    private static final String START_URL = "file:///android_asset/www/index.html";
    private static final String ASSET_PREFIX = "file:///android_asset/www/";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private int safeTopDp = 36;
    private int safeBottomDp = 0;
    private boolean pageReady = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#151A22"));
        getWindow().setNavigationBarColor(Color.parseColor("#0E1116"));

        WebView.setWebContentsDebuggingEnabled(false);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(false);
        ws.setDatabaseEnabled(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ws.setAllowFileAccessFromFileURLs(false);
            ws.setAllowUniversalAccessFromFileURLs(false);
        }
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setSupportMultipleWindows(false);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(true);
        }

        web.setBackgroundColor(Color.parseColor("#0E1116"));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri == null ? "" : uri.toString();
                return !url.startsWith(ASSET_PREFIX);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !url.startsWith(ASSET_PREFIX);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushInsets();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Не удалось открыть выбор файлов", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "DisputeNative");
        installInsetsListener();
        setContentView(web);
        web.loadUrl(START_URL);
    }

    private void installInsetsListener() {
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            int topPx;
            int bottomPx;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topPx = bars.top;
                bottomPx = bars.bottom;
            } else {
                topPx = insets.getSystemWindowInsetTop();
                bottomPx = insets.getSystemWindowInsetBottom();
            }
            float density = getResources().getDisplayMetrics().density;
            safeTopDp = Math.max(24, Math.round(topPx / Math.max(1f, density)));
            safeBottomDp = Math.max(0, Math.round(bottomPx / Math.max(1f, density)));
            pushInsets();
            return insets;
        });
        web.post(web::requestApplyInsets);
    }

    private void pushInsets() {
        if (!pageReady || web == null) return;
        final String js = "window.__setInsets && window.__setInsets(" + safeTopDp + "," + safeBottomDp + ");";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("DisputeNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void deliver(final String id, final JSONObject payload) {
        runOnUiThread(() -> {
            if (web == null) return;
            String js = "window.__onNativeResult(" + JSONObject.quote(id) + "," + payload.toString() + ");";
            web.evaluateJavascript(js, null);
        });
    }

    private final class Bridge {
        private final SharedPreferences prefs = getSharedPreferences("disputeai", MODE_PRIVATE);
        private final SecretStore secrets = new SecretStore(prefs);

        private void injectSecret(JSONObject slot) throws Exception {
            String id = slot.optString("id", "");
            String key = secrets.get(id);
            slot.remove("apiKey");
            if (key != null && !key.isEmpty()) slot.put("apiKey", key);
        }

        @JavascriptInterface
        public void chat(String id, String slotJson, String messagesJson) {
            new Thread(() -> {
                try {
                    JSONObject slot = new JSONObject(slotJson);
                    injectSecret(slot);
                    JSONObject out = LlmApi.call(slot, messagesJson, false);
                    slot.remove("apiKey");
                    deliver(id, out);
                } catch (Exception e) {
                    deliver(id, LlmApi.fail(e));
                }
            }, "dispute-chat-" + id).start();
        }

        @JavascriptInterface
        public void test(String id, String slotJson) {
            new Thread(() -> {
                try {
                    JSONObject slot = new JSONObject(slotJson);
                    injectSecret(slot);
                    String testMessages = "[{\"role\":\"user\",\"content\":\"Ответь одним словом: OK\"}]";
                    JSONObject out = LlmApi.call(slot, testMessages, true);
                    slot.remove("apiKey");
                    deliver(id, out);
                } catch (Exception e) {
                    deliver(id, LlmApi.fail(e));
                }
            }, "dispute-test-" + id).start();
        }

        @JavascriptInterface
        public String loadSettings() {
            String raw = prefs.getString("settings", "");
            if (raw == null || raw.isEmpty()) return "";
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject slots = root.optJSONObject("slots");
                if (slots != null) {
                    for (String id : new String[]{"a", "b"}) {
                        JSONObject s = slots.optJSONObject(id);
                        if (s == null) continue;
                        s.remove("apiKey");
                        s.put("hasApiKey", secrets.has(id));
                    }
                }
                return root.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveSettings(String json) {
            try {
                JSONObject root = new JSONObject(json == null ? "{}" : json);
                JSONObject slots = root.optJSONObject("slots");
                if (slots != null) {
                    for (String id : new String[]{"a", "b"}) {
                        JSONObject s = slots.optJSONObject(id);
                        if (s == null) continue;
                        String newKey = s.optString("apiKey", "").trim();
                        if (!newKey.isEmpty()) secrets.put(id, newKey);
                        s.remove("apiKey");
                        s.put("hasApiKey", secrets.has(id));
                    }
                }
                prefs.edit().putString("settings", root.toString()).apply();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void clearApiKey(String slotId) {
            secrets.remove(slotId);
        }

        @JavascriptInterface
        public void clearSecrets() {
            secrets.clearAll();
        }

        @JavascriptInterface
        public String loadHistory() {
            try (FileInputStream in = openFileInput("history.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveHistory(String json) {
            try (FileOutputStream out = openFileOutput("history.json", MODE_PRIVATE)) {
                out.write((json == null ? "[]" : json).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void toast(final String text) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }
    }

    private static final class SecretStore {
        private static final String KEYSTORE = "AndroidKeyStore";
        private static final String ALIAS = "DisputeAI.ApiKeys.v1";
        private static final String PREFIX = "secret.";
        private final SharedPreferences prefs;

        SecretStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        private SecretKey key() throws Exception {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            java.security.Key existing = store.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;

            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            kg.init(spec);
            return kg.generateKey();
        }

        void put(String slotId, String value) throws Exception {
            if (!"a".equals(slotId) && !"b".equals(slotId)) return;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + "." + Base64.encodeToString(ct, Base64.NO_WRAP);
            prefs.edit().putString(PREFIX + slotId, packed).apply();
        }

        String get(String slotId) {
            if (!"a".equals(slotId) && !"b".equals(slotId)) return "";
            String packed = prefs.getString(PREFIX + slotId, "");
            if (packed == null || packed.isEmpty()) return "";
            try {
                String[] parts = packed.split("\\.", 2);
                if (parts.length != 2) throw new IllegalStateException("bad secret");
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
                byte[] pt = cipher.doFinal(ct);
                return new String(pt, StandardCharsets.UTF_8);
            } catch (Exception e) {
                prefs.edit().remove(PREFIX + slotId).apply();
                return "";
            }
        }

        boolean has(String slotId) {
            return !get(slotId).isEmpty();
        }

        void remove(String slotId) {
            prefs.edit().remove(PREFIX + slotId).apply();
        }

        void clearAll() {
            prefs.edit().remove(PREFIX + "a").remove(PREFIX + "b").apply();
            try {
                KeyStore store = KeyStore.getInstance(KEYSTORE);
                store.load(null);
                if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
            } catch (Exception ignored) { }
        }
    }
}
