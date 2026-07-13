package com.whiteyun.screenshot;

import android.app.Application;

public class WhiteYunScreenshotApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (!BuildConfig.DEBUG && !AppIntegrity.hasOfficialSignature(this)) {
            throw new SecurityException("Untrusted application signature");
        }
        Diagnostics.install(this);
    }
}
