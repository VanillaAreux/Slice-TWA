package com.slicetechnologies.slice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.CookieManager;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.app.PictureInPictureUiState;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 4127;
    private static final int REQUEST_NOTIFICATIONS = 4129;
    private static final String NOTIFICATION_CHANNEL = "slice_updates";
    private String notificationPermissionCallback;
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
        createNotificationChannel();
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
        webView.addJavascriptInterface(new NativeNotificationsBridge(), "SliceNativeNotifications");
        setContentView(webView);
        webView.loadUrl("https://slicesocial.vercel.app/");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, "Slice notifications", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Messages, friend requests, and call alerts from Slice");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private class NativeNotificationsBridge {
        @JavascriptInterface public boolean available() { return true; }
        @JavascriptInterface public void enable(String callback) {
            if (callback == null || !callback.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return;
            runOnUiThread(() -> {
                notificationPermissionCallback = callback;
                if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) completeNotificationPermission(true);
                else requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            });
        }
        @JavascriptInterface public void show(String title, String body, String target) {
            final String safeTitle = title == null || title.trim().isEmpty() ? "Slice" : title.trim().substring(0, Math.min(80, title.trim().length()));
            final String safeBody = body == null ? "" : body.trim().substring(0, Math.min(240, body.trim().length()));
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
                Intent launch = new Intent(MainActivity.this, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pending = PendingIntent.getActivity(MainActivity.this, (int)(System.currentTimeMillis() & 0x7fffffff), launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
                Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(MainActivity.this, NOTIFICATION_CHANNEL) : new Notification.Builder(MainActivity.this);
                builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(safeTitle).setContentText(safeBody)
                    .setStyle(new Notification.BigTextStyle().bigText(safeBody)).setContentIntent(pending).setAutoCancel(true);
                NotificationManager manager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.notify((int)(System.currentTimeMillis() & 0x7fffffff), builder.build());
            });
        }
    }

    private void completeNotificationPermission(boolean granted) {
        String callback = notificationPermissionCallback;
        notificationPermissionCallback = null;
        if (callback != null && webView != null) webView.evaluateJavascript("window[" + JSONObjectQuote(callback) + "](" + (granted ? "true" : "false") + ")", null);
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
            final AtomicBoolean entered = new AtomicBoolean(false);
            final CountDownLatch finished = new CountDownLatch(1);
            Runnable request = () -> {
                try {
                    PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .build();
                    entered.set(enterPictureInPictureMode(params));
                } catch (Exception ignored) {
                    entered.set(false);
                } finally {
                    finished.countDown();
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                request.run();
            } else {
                runOnUiThread(request);
                try {
                    if (!finished.await(1500, TimeUnit.MILLISECONDS)) return false;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return entered.get();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            boolean granted = Build.VERSION.SDK_INT < 33 || (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            completeNotificationPermission(granted);
            return;
        }
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
    @Override public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (webView != null) {
            final boolean inPip = isInPictureInPictureMode;
            // The initial task-switcher snapshot can be correct while the live
            // WebView surface redraws using the resized PiP viewport. Reassert
            // the JS layout after Android finishes its PiP resize and force the
            // native WebView to invalidate its composited surface.
            Runnable refreshPipSurface = () -> {
                if (webView == null || isFinishing()) return;
                String script = "if(window.__slicePipChanged)window.__slicePipChanged(" + inPip + ");"
                    + "if(window.__sliceRefreshCallPip)window.__sliceRefreshCallPip(" + inPip + ");";
                webView.evaluateJavascript(script, null);
                webView.invalidate();
                webView.requestLayout();
            };
            webView.post(refreshPipSurface);
            if (inPip) {
                handler.postDelayed(refreshPipSurface, 100);
                handler.postDelayed(refreshPipSurface, 300);
                handler.postDelayed(refreshPipSurface, 700);
            }
        }
    }

    @Override public void onPictureInPictureUiStateChanged(PictureInPictureUiState pipState) {
        super.onPictureInPictureUiStateChanged(pipState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pipState.isTransitioningToPip() && webView != null) {
            // Hide the page before Android captures/resizes the WebView surface.
            webView.evaluateJavascript("if(window.__sliceForceCallPipLayout)window.__sliceForceCallPipLayout()", null);
            webView.invalidate();
        }
    }

    @Override protected void onPause() {
        // Do not call WebView.onPause() here: Android can dispatch Activity.onPause
        // just before the PiP mode callback. Pausing the WebView at that moment
        // suspends its timers/media and can tear down the active WebRTC call.
        // Let Android background the Activity while the WebView call remains alive.
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
        if (webView != null) { webView.removeJavascriptInterface("SliceNativeScreenShare"); webView.removeJavascriptInterface("SliceNativeNotifications"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}