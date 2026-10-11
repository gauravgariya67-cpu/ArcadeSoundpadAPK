package com.arcade.soundpad;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Rational;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO_REQ = 201;
    private static final String BASE_URL = "https://www.myinstants.com";
    private static final Pattern INSTANT_RE = Pattern.compile(
        "play\\('([^']+\\.mp3)'[\\s\\S]*?class=\"instant-link[^\"]*\"[^>]*>([^<]+)</a>"
    );

    private WebView webView;
    private SoundpadService soundService;
    private boolean serviceBound = false;
    private File soundsDir;
    private File favFile;
    private File historyFile;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            soundService = ((SoundpadService.LocalBinder) binder).getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private static class KeepAliveWebView extends WebView {
        public KeepAliveWebView(Context context) {
            super(context);
        }
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        soundsDir = new File(getFilesDir(), "sounds");
        if (!soundsDir.exists()) soundsDir.mkdirs();
        favFile = new File(getFilesDir(), "favorites.json");
        historyFile = new File(getFilesDir(), "history.json");

        Intent svcIntent = new Intent(this, SoundpadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
        bindService(svcIntent, conn, Context.BIND_AUTO_CREATE);

        webView = new KeepAliveWebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);

        webView.loadUrl("file:///android_asset/index.html");
    }

    private String md5Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }

    private byte[] httpGetBytes(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private String readTextFile(File f, String fallback) {
        if (!f.exists()) return fallback;
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return fallback;
        }
    }

    private void writeTextFile(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public class AndroidBridge {
        @JavascriptInterface
        public String fetchInstants(String query, int page) {
            JSONArray arr = new JSONArray();
            try {
                String q = query == null ? "" : query.trim();
                String url = !q.isEmpty()
                    ? BASE_URL + "/en/search/?name=" + URLEncoder.encode(q, "UTF-8") + "&page=" + page
                    : BASE_URL + "/en/index/in/?page=" + page;
                String html = new String(httpGetBytes(url), StandardCharsets.UTF_8);
                Matcher m = INSTANT_RE.matcher(html);
                while (m.find()) {
                    String mp3Url = BASE_URL + m.group(1);
                    String title = m.group(2).trim();
                    File cached = new File(soundsDir, md5Short(mp3Url) + ".mp3");
                    JSONObject obj = new JSONObject();
                    obj.put("title", title);
                    obj.put("url", mp3Url);
                    obj.put("cached", cached.exists());
                    arr.put(obj);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        @JavascriptInterface
        public void playSound(String jsonStr) {
            pool.execute(() -> {
                try {
                    JSONObject item = new JSONObject(jsonStr);
                    String url = item.optString("url", "");
                    String title = item.optString("title", "Sound");
                    double trimStart = item.optDouble("trim_start", 0.0);
                    double trimEnd = item.optDouble("trim_end", 0.0);

                    File target;
                    if (url.startsWith("file://")) {
                        target = new File(url.substring(7));
                    } else {
                        target = new File(soundsDir, md5Short(url) + ".mp3");
                        if (!target.exists()) {
                            byte[] audioBytes = httpGetBytes(url);
                            try (FileOutputStream fos = new FileOutputStream(target)) {
                                fos.write(audioBytes);
                            }
                        }
                    }
                    if (serviceBound && target.exists()) {
                        soundService.playFile(target, title, trimStart, trimEnd);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        @JavascriptInterface
        public void playTts(String text, String voiceId) {
            pool.execute(() -> {
                try {
                    String clean = text == null ? "" : text.trim();
                    if (clean.isEmpty() || !serviceBound) return;
                    File ttsFile = new File(soundsDir, "tts_" + md5Short(voiceId + ":" + clean) + ".mp3");

                    if (!ttsFile.exists()) {
                        byte[] audioBytes = null;
                        if (!"google_en".equals(voiceId)) {
                            String[][] endpoints = {
                                {"https://tiktok-tts.weilnet.workers.dev/api/generation", "data"},
                                {"https://countik.com/api/text/speech", "v_data"},
                                {"https://gesserit.co/api/tiktok-tts", "base64"}
                            };
                            for (String[] ep : endpoints) {
                                try {
                                    HttpURLConnection c = (HttpURLConnection) new URL(ep[0]).openConnection();
                                    c.setRequestMethod("POST");
                                    c.setRequestProperty("Content-Type", "application/json");
                                    c.setDoOutput(true);
                                    c.setConnectTimeout(6000);
                                    JSONObject body = new JSONObject();
                                    body.put("text", clean);
                                    body.put("voice", voiceId);
                                    try (OutputStream os = c.getOutputStream()) {
                                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                                    }
                                    if (c.getResponseCode() == 200) {
                                        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                                            byte[] buf = new byte[4096];
                                            int n;
                                            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                                            JSONObject resp = new JSONObject(bos.toString("UTF-8"));
                                            String b64 = resp.optString(ep[1], "");
                                            if (!b64.isEmpty()) {
                                                if (b64.contains(",")) b64 = b64.split(",", 2)[1];
                                                audioBytes = Base64.decode(b64, Base64.DEFAULT);
                                                break;
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        if (audioBytes == null) {
                            String gUrl = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=en&q="
                                + URLEncoder.encode(clean, "UTF-8");
                            audioBytes = httpGetBytes(gUrl);
                        }
                        if (audioBytes != null) {
                            try (FileOutputStream fos = new FileOutputStream(ttsFile)) {
                                fos.write(audioBytes);
                            }
                        }
                    }
                    if (ttsFile.exists()) {
                        soundService.playFile(ttsFile, "TTS: " + clean, 0, 0);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopAll() {
            if (serviceBound) soundService.stopAll();
        }

        @JavascriptInterface
        public void updateMixer(float vol, float pitch, float speed, boolean overlap, boolean loop) {
            if (serviceBound) {
                soundService.volume = vol;
                soundService.pitch = pitch;
                soundService.speed = speed;
                soundService.overlapEnabled = overlap;
                soundService.loopEnabled = loop;
            }
        }

        @JavascriptInterface
        public String loadFavs() {
            return readTextFile(favFile, "{}");
        }

        @JavascriptInterface
        public void saveFavs(String json) {
            writeTextFile(favFile, json);
        }

        @JavascriptInterface
        public String loadHistory() {
            return readTextFile(historyFile, "[]");
        }

        @JavascriptInterface
        public void saveHistory(String json) {
            writeTextFile(historyFile, json);
        }

        @JavascriptInterface
        public void importLocalAudio() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, PICK_AUDIO_REQ);
        }

        @JavascriptInterface
        public void enterFloatingMode() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(9, 14))
                        .build();
                    enterPictureInPictureMode(params);
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AUDIO_REQ && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            pool.execute(() -> {
                try {
                    String name = "Local_Audio";
                    try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (idx >= 0) name = cursor.getString(idx).replaceFirst("[.][^.]+$", "");
                        }
                    }
                    File dest = new File(soundsDir, "local_" + System.currentTimeMillis() + ".mp3");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    final String fTitle = name.replace("'", "");
                    final String fUrl = "file://" + dest.getAbsolutePath();
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onLocalImported('" + fTitle + "','" + fUrl + "')", null
                    ));
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) unbindService(conn);
        super.onDestroy();
    }
}
