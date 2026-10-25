package com.abk.greencardphoto;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 19;
    private static final int FILE_REQUEST = 20;
    private WebView web;
    private PermissionRequest pendingCamera;
    private ValueCallback<Uri[]> pendingChooser;
    private ServerSocket server;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff07111c);
        getWindow().setNavigationBarColor(0xff07111c);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new AndroidBridge(), "ABKAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("127.0.0.1".equals(u.getHost()) && "http".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri origin = request.getOrigin();
                    boolean local = origin != null && "127.0.0.1".equals(origin.getHost()) && "http".equals(origin.getScheme());
                    String[] resources = request.getResources();
                    boolean videoOnly = resources.length == 1 &&
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resources[0]);
                    if (!local || !videoOnly) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
                    } else {
                        pendingCamera = request;
                        requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_REQUEST);
                    }
                });
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pendingChooser != null) pendingChooser.onReceiveValue(null);
                pendingChooser = cb;
                Intent choose = new Intent(Intent.ACTION_GET_CONTENT);
                choose.addCategory(Intent.CATEGORY_OPENABLE);
                choose.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(choose, "اختر صورة"), FILE_REQUEST);
                    return true;
                } catch (Exception e) {
                    pendingChooser.onReceiveValue(null); pendingChooser = null; return false;
                }
            }
        });
        try {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/index.html";
            workers.execute(this::serve);
            web.loadUrl(url);
        } catch (IOException e) {
            Toast.makeText(this, "تعذر تشغيل التطبيق: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == CAMERA_REQUEST && pendingCamera != null) {
            PermissionRequest req = pendingCamera; pendingCamera = null;
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
                req.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
            else req.deny();
        }
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent result) {
        super.onActivityResult(request, resultCode, result);
        if (request == FILE_REQUEST && pendingChooser != null) {
            Uri[] selected = resultCode == RESULT_OK && result != null && result.getData() != null
                ? new Uri[] {result.getData()} : null;
            pendingChooser.onReceiveValue(selected);
            pendingChooser = null;
        }
    }

    private void serve() {
        while (server != null && !server.isClosed()) {
            try {
                Socket client = server.accept();
                workers.execute(() -> respond(client));
            } catch (IOException e) { break; }
        }
    }

    private void respond(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(4000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int ch;
            while (line.size() < 2048 && (ch = in.read()) != -1 && ch != '\n') line.write(ch);
            String[] req = line.toString("UTF-8").trim().split(" ");
            if (req.length < 2 || !"GET".equals(req[0])) {
                reply(s, 405, "text/plain", "Not allowed".getBytes(StandardCharsets.UTF_8)); return;
            }
            String path = req[1].split("\\?", 2)[0];
            String assetPath;
            if ("/".equals(path) || "/index.html".equals(path)) {
                assetPath = "www/index.html";
            } else if (path.startsWith("/mediapipe/") &&
                       path.matches("/mediapipe/[a-zA-Z0-9_./-]+") &&
                       !path.contains("..") && !path.contains("//")) {
                assetPath = "www" + path;
            } else {
                reply(s, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String mime = "application/octet-stream";
            if (path.endsWith(".html") || "/".equals(path)) mime = "text/html; charset=utf-8";
            else if (path.endsWith(".mjs") || path.endsWith(".js")) mime = "text/javascript; charset=utf-8";
            else if (path.endsWith(".wasm")) mime = "application/wasm";
            byte[] bytes;
            try (InputStream f = getAssets().open(assetPath)) { bytes = readAll(f); }
            reply(s, 200, mime, bytes);
        } catch (Exception ignored) {}
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static void reply(Socket s, int code, String type, byte[] content) throws IOException {
        String h = "HTTP/1.1 " + code + (code==200 ? " OK" : " Error") + "\r\n" +
            "Content-Type: " + type + "\r\nContent-Length: " + content.length +
            "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(h.getBytes(StandardCharsets.US_ASCII)); out.write(content); out.flush();
    }

    private final class AndroidBridge {
        @JavascriptInterface public void saveJpeg(String raw) {
            workers.execute(() -> {
                try {
                    if (raw == null || raw.length() > 450000) throw new IOException("بيانات الصورة غير صالحة");
                    byte[] jpeg = Base64.decode(raw, Base64.DEFAULT);
                    if (jpeg.length < 54000 || jpeg.length > 240000 ||
                        jpeg[0] != (byte) 0xff || jpeg[1] != (byte) 0xd8)
                        throw new IOException("حجم أو تنسيق JPG غير مطابق");
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
                    if (opts.outWidth != 600 || opts.outHeight != 600 || !"image/jpeg".equals(opts.outMimeType))
                        throw new IOException("المطلوب صورة JPG مقاس 600×600");
                    String name = "ABK-GREEN-CARD-" +
                        new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".jpg";
                    ContentValues vals = new ContentValues();
                    vals.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    vals.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    vals.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ABK Green Card");
                    vals.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri image = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals);
                    if (image == null) throw new IOException("تعذر إنشاء ملف الصورة");
                    try {
                        try (OutputStream out = getContentResolver().openOutputStream(image)) {
                            if (out == null) throw new IOException("تعذر كتابة الصورة");
                            out.write(jpeg);
                        }
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(image, done, null, null);
                    } catch (Exception e) {
                        getContentResolver().delete(image, null, null);
                        throw e;
                    }
                    report(true, "تم الحفظ في الصور / ABK Green Card");
                } catch (Exception e) { report(false, "فشل الحفظ: " + e.getMessage()); }
            });
        }
    }

    private void report(boolean ok, String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (web != null) web.evaluateJavascript("window.abkSaveCallback && window.abkSaveCallback(" +
                ok + "," + org.json.JSONObject.quote(message) + ")", null);
        });
    }
    @Override protected void onDestroy() {
        if (pendingCamera != null) { pendingCamera.deny(); pendingCamera = null; }
        if (pendingChooser != null) { pendingChooser.onReceiveValue(null); pendingChooser = null; }
        if (web != null) { web.loadUrl("about:blank"); web.destroy(); web = null; }
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
        super.onDestroy();
    }
}
