package com.slicetechnologies.slice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.CookieManager;
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
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.os.Build;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.app.PictureInPictureParams;
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
    private AudioManager callAudioManager;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private AudioFocusRequest audioFocusRequest;
    private boolean audioPrepared = false;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String callbackName;
    private boolean frameBusy = false;
    private PermissionRequest pendingWebPermission;
    private String[] pendingRuntimePermissions = new String[0];
    private int captureWidth = 480, captureHeight = 270;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff111318);
        getWindow().setNavigationBarColor(0xff111318);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        webView = new WebView(this);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        webView.setBackgroundColor(0xff111318);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("slicesocial.vercel.app".equalsIgnoreCase(uri.getHost()) && "https".equalsIgnoreCase(uri.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    java.util.ArrayList<String> missing = new java.util.ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) &&
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                            !missing.contains(Manifest.permission.RECORD_AUDIO)) {
                            missing.add(Manifest.permission.RECORD_AUDIO);
                        }
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) &&
                            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                            !missing.contains(Manifest.permission.CAMERA)) {
                            missing.add(Manifest.permission.CAMERA);
                        }
                    }
                    if (!missing.isEmpty()) {
                        pendingWebPermission = request;
                        pendingRuntimePermissions = missing.toArray(new String[0]);
                        requestPermissions(pendingRuntimePermissions, 4128);
                    } else {
                        request.grant(request.getResources());
                    }
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
        @JavascriptInterface public void prepareAudio() {
            // Apply the audio mode synchronously: JavaScript starts getUserMedia immediately
            // after this bridge call returns, so posting the mode change to the UI queue races it.
            try {
                if (audioPrepared) return; // already prepared for an in-flight call; don't clobber previousAudioMode
                if (callAudioManager == null) callAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (callAudioManager != null) {
                    // Without explicitly holding audio focus, the system can keep routing/locking
                    // the mic to whatever last had focus (a notification, another app's playback,
                    // or a previous call that didn't clean up), which surfaces to WebRTC as
                    // NotReadableError even though RECORD_AUDIO is granted. Request focus first.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attrs)
                            .build();
                        callAudioManager.requestAudioFocus(audioFocusRequest);
                    } else {
                        callAudioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    }
                    previousAudioMode = callAudioManager.getMode();
                    callAudioManager.setMicrophoneMute(false);
                    callAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioPrepared = true;
                }
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public void endAudio() {
            try {
                if (callAudioManager != null) {
                    callAudioManager.setMode(previousAudioMode);
                    releaseAudioFocus();
                }
                audioPrepared = false;
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> {
                MainActivity.this.stopCapture();
                try {
                    if (callAudioManager != null) {
                        callAudioManager.setMode(previousAudioMode);
                        releaseAudioFocus();
                    }
                    audioPrepared = false;
                } catch (Exception ignored) {}
            });
        }
        @JavascriptInterface public boolean available() { return true; }
        @JavascriptInterface public boolean enterPictureInPicture() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
            runOnUiThread(() -> {
                try {
                    PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .build();
                    enterPictureInPictureMode(params);
                } catch (Exception ignored) {}
            });
            return true;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 4128 && pendingWebPermission != null) {
            boolean granted = pendingRuntimePermissions.length > 0 && grantResults.length == pendingRuntimePermissions.length;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
            else pendingWebPermission.deny();
            pendingWebPermission = null;
            pendingRuntimePermissions = new String[0];
        }
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
    private void releaseAudioFocus() {
        try {
            if (callAudioManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                callAudioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            } else {
                callAudioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignored) {}
    }
    private void stopCapture() {
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        display = null; reader = null; projection = null; callbackName = null; frameBusy = false;
    }
    @Override protected void onPause() {
        // Keep WebRTC and PiP rendering alive while Android shows the app in PiP.
        if (webView != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode())) webView.onPause();
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        stopCapture();
        try {
            if (callAudioManager != null) callAudioManager.setMode(previousAudioMode);
            releaseAudioFocus();
        } catch (Exception ignored) {}
        audioPrepared = false;
        if (webView != null) { webView.removeJavascriptInterface("SliceNativeScreenShare"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}