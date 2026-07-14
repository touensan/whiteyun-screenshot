package com.whiteyun.screenshot;

import android.app.Application;
import android.content.Context;

public class WhiteYunScreenshotApp extends Application {
    private static WhiteYunScreenshotApp instance;
    private static Context systemBaseContext;

    @Override
    protected void attachBaseContext(Context base) {
        systemBaseContext = base;
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppLocale.migrateToPlatformIfNeeded(this);
        if (!BuildConfig.DEBUG && !AppIntegrity.hasOfficialSignature(this)) {
            throw new SecurityException("Untrusted application signature");
        }
        Diagnostics.install(this);
    }

    static String text(int resourceId, Object... arguments) {
        Context base = systemBaseContext == null ? instance : systemBaseContext;
        Context context = AppLocale.wrap(base);
        return arguments == null || arguments.length == 0
                ? context.getString(resourceId)
                : context.getString(resourceId, arguments);
    }
}
