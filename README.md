# Slice Android app

This app loads Slice in an Android WebView so the app can request Android's native screen-capture consent and provide screen frames to Slice's web screen-share flow. This is no longer a thin Trusted Web Activity.

## Screen sharing

When the web app calls `window.SliceNativeScreenShare.start(callbackName)`, Android displays the system MediaProjection consent prompt. On approval, the app captures the screen at a reduced resolution and sends JPEG frames to the named JavaScript callback. The web app draws those frames to a canvas and publishes its canvas stream through the existing WebRTC flow. Audio from the device is not captured.

The screen-capture prompt is shown every time sharing starts. Android's own sharing indicator/stop controls remain in effect. The implementation is intended for active, foreground calls and Stages.

## Build

Open this project in Android Studio and build/install the debug APK. A current Android SDK and JDK 17 are required. Test camera/microphone permissions, push notifications, links, login, calls, and screen sharing before distributing. This is a WebView-based app rather than a TWA, so browser-only features and push notification behavior may differ from the old package.

The launcher URL is `https://slicesocial.vercel.app/`.
