package com.slicetechnologies.slice;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

// Reserved for a future long-running projection foreground service. Current capture is tied to
// the visible app Activity so the system consent prompt and stop lifecycle remain explicit.
public class ScreenCaptureService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}