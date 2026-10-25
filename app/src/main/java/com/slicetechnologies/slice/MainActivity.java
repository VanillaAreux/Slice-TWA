package com.slicetechnologies.slice;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 4127;
    private WebView webView;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String callbackName;
    private boolean frameBusy = false;
    private int captureWidth = 480, captureHeight = 270;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff111318);
        getWindow().setNavigationBarColor(0xff111318);
        webView = new WebView(this);
        webView.setBackgroundColor(0xff111318);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) ||
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            request.grant(request.getResources());
                            return;
                        }
                    }
                    request.deny();
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {}
        });
        webView.addJavascriptInterface(new ScreenShareBridge(), "SliceNativeScreenShare");
        setContentView(webView);
        webView.loadUrl("https://slicesocial.vercel.app/");
    }

    private class ScreenShareBridge {
        @JavascriptInterface public void start(String callback) {
            runOnUiThread(() -> {
                if (callback == null || !callback.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                    Toast.makeText(MainActivity.this, "Invalid screen-share callback", Toast.LENGTH_SHORT).show();
                    return;
                }
                callbackName = callback;
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
            });
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(MainActivity.this::stopCapture);
        }
        @JavascriptInterface public boolean available() { return true; }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            sendEvent("null");
            return;
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) { sendEvent("null"); return; }
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            float scale = Math.min(1f, 480f / Math.max(metrics.widthPixels, metrics.heightPixels));
            captureWidth = Math.max(2, ((int)(metrics.widthPixels * scale)) & ~1);
            captureHeight = Math.max(2, ((int)(metrics.heightPixels * scale)) & ~1);
            reader = ImageReader.newInstance(captureWidth, captureHeight, android.graphics.PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> captureFrame(source), handler);
            display = projection.createVirtualDisplay("SliceScreenShare", captureWidth, captureHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            sendEvent("started");
        } catch (Exception e) {
            stopCapture();
            sendEvent("null");
            Toast.makeText(this, "Could not start screen capture", Toast.LENGTH_SHORT).show();
        }
    }

    private void captureFrame(ImageReader source) {
        if (frameBusy) return;
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            frameBusy = true;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int paddedWidth = rowStride / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
            padded.recycle();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 48, out);
            bitmap.recycle();
            String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            runOnUiThread(() -> {
                if (webView != null && callbackName != null) {
                    webView.evaluateJavascript("window[" + JSONObjectQuote(callbackName) + "](" + JSONObjectQuote(encoded) + ")", null);
                }
                frameBusy = false;
            });
        } catch (Exception ignored) {
            frameBusy = false;
        } finally {
            if (image != null) image.close();
        }
    }

    private String JSONObjectQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    private void sendEvent(String value) {
        runOnUiThread(() -> {
            if (webView != null && callbackName != null)
                webView.evaluateJavascript("window[" + JSONObjectQuote(callbackName) + "](" + JSONObjectQuote(value) + ")", null);
        });
    }
    private void stopCapture() {
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        display = null; reader = null; projection = null; callbackName = null; frameBusy = false;
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        stopCapture();
        if (webView != null) { webView.removeJavascriptInterface("SliceNativeScreenShare"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}