package com.whiteyun.screenshot;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPreferences {
    private static final String NAME = "whiteyun_preferences";
    private static final String KEY_AUTO_SCROLL = "capture_auto_scroll";
    private static final String KEY_SPEED_MODE = "capture_speed_mode";
    private static final String KEY_MANUAL_CAPTURE = "capture_manual_capture";
    private static final String KEY_STITCH_CROP_SYSTEM_BARS = "stitch_crop_system_bars";
    private static final String KEY_SAVE_ORIGINALS = "preview_save_originals";
    private static final String KEY_STITCH_NOTIFICATIONS = "stitch_completion_notifications";
    private static final String KEY_CAPTURE_STATUS_BAR = "capture_status_bar";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_APP_LANGUAGE_MIGRATED = "app_language_migrated";

    private AppPreferences() {
    }

    static boolean isAutoScrollEnabled(Context context) {
        return preferences(context).getBoolean(KEY_AUTO_SCROLL, false);
    }

    static boolean isSpeedModeEnabled(Context context) {
        return preferences(context).getBoolean(KEY_SPEED_MODE, true);
    }

    static boolean isManualCaptureEnabled(Context context) {
        return preferences(context).getBoolean(KEY_MANUAL_CAPTURE, false);
    }

    static boolean isStitchCropSystemBars(Context context) {
        return preferences(context).getBoolean(KEY_STITCH_CROP_SYSTEM_BARS, false);
    }

    static boolean isSaveOriginals(Context context) {
        return preferences(context).getBoolean(KEY_SAVE_ORIGINALS, false);
    }

    static boolean isStitchNotificationsEnabled(Context context) {
        return preferences(context).getBoolean(KEY_STITCH_NOTIFICATIONS, true);
    }

    static boolean isCaptureStatusBarEnabled(Context context) {
        return preferences(context).getBoolean(KEY_CAPTURE_STATUS_BAR, false);
    }

    static String getAppLanguage(Context context) {
        return preferences(context).getString(KEY_APP_LANGUAGE, "");
    }

    static boolean isAppLanguageMigrated(Context context) {
        return preferences(context).getBoolean(KEY_APP_LANGUAGE_MIGRATED, false);
    }

    static void setCaptureOptions(
            Context context,
            boolean autoScroll,
            boolean speedMode,
            boolean manualCapture) {
        preferences(context)
                .edit()
                .putBoolean(KEY_AUTO_SCROLL, autoScroll)
                .putBoolean(KEY_SPEED_MODE, speedMode)
                .putBoolean(KEY_MANUAL_CAPTURE, manualCapture)
                .apply();
    }

    static void setStitchCropSystemBars(Context context, boolean cropSystemBars) {
        preferences(context)
                .edit()
                .putBoolean(KEY_STITCH_CROP_SYSTEM_BARS, cropSystemBars)
                .apply();
    }

    static void setSaveOriginals(Context context, boolean saveOriginals) {
        preferences(context)
                .edit()
                .putBoolean(KEY_SAVE_ORIGINALS, saveOriginals)
                .apply();
    }

    static void setStitchNotificationsEnabled(Context context, boolean enabled) {
        preferences(context)
                .edit()
                .putBoolean(KEY_STITCH_NOTIFICATIONS, enabled)
                .apply();
    }

    static void setCaptureStatusBarEnabled(Context context, boolean enabled) {
        preferences(context)
                .edit()
                .putBoolean(KEY_CAPTURE_STATUS_BAR, enabled)
                .apply();
    }

    static void setAppLanguage(Context context, String languageTag) {
        preferences(context)
                .edit()
                .putString(KEY_APP_LANGUAGE, languageTag == null ? "" : languageTag)
                .apply();
    }

    static void setAppLanguageMigrated(Context context) {
        preferences(context)
                .edit()
                .putBoolean(KEY_APP_LANGUAGE_MIGRATED, true)
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        // ponytail: SharedPreferences is enough for these local booleans; migrate to DataStore if settings become syncable or multi-profile.
        Context application = context.getApplicationContext();
        return (application == null ? context : application)
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
