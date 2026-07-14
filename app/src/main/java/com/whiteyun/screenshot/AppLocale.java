package com.whiteyun.screenshot;

import android.app.LocaleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

final class AppLocale {
    static final String SYSTEM_DEFAULT = "";

    private static final String[] SUPPORTED_TAGS = {
            "en", "zh-CN", "zh-TW", "es", "fr", "de", "pt-BR", "ru",
            "ja", "ko", "ar", "hi", "id", "it", "tr", "vi", "th", "pl", "nl"
    };
    private static final String[] LAUNCHER_ALIAS_SUFFIXES = {
            "LauncherDefault", "LauncherEn", "LauncherZhCn", "LauncherZhTw", "LauncherEs",
            "LauncherFr", "LauncherDe", "LauncherPtBr", "LauncherRu", "LauncherJa",
            "LauncherKo", "LauncherAr", "LauncherHi", "LauncherId", "LauncherIt",
            "LauncherTr", "LauncherVi", "LauncherTh", "LauncherPl", "LauncherNl"
    };

    private AppLocale() {
    }

    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base;
        }
        String tag = normalize(AppPreferences.getAppLanguage(base));
        if (tag.isEmpty()) {
            return base;
        }
        Locale locale = Locale.forLanguageTag(tag);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    static Context forTag(Context base, String requestedTag) {
        String tag = normalize(requestedTag);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        LocaleList locales = tag.isEmpty()
                ? Resources.getSystem().getConfiguration().getLocales()
                : LocaleList.forLanguageTags(tag);
        Locale locale = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        configuration.setLocales(locales);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    static void migrateToPlatformIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || AppPreferences.isAppLanguageMigrated(context)) {
            return;
        }
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        String stored = normalize(AppPreferences.getAppLanguage(context));
        if (manager != null && manager.getApplicationLocales().isEmpty() && !stored.isEmpty()) {
            manager.setApplicationLocales(LocaleList.forLanguageTags(stored));
        }
        AppPreferences.setAppLanguageMigrated(context);
    }

    static String currentTag(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager == null || manager.getApplicationLocales().isEmpty()) {
                return SYSTEM_DEFAULT;
            }
            return normalize(manager.getApplicationLocales().get(0).toLanguageTag());
        }
        return normalize(AppPreferences.getAppLanguage(context));
    }

    static void set(Context context, String requestedTag) {
        String tag = normalize(requestedTag);
        AppPreferences.setAppLanguage(context, tag);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) {
                manager.setApplicationLocales(tag.isEmpty()
                        ? LocaleList.getEmptyLocaleList()
                        : LocaleList.forLanguageTags(tag));
            }
            AppPreferences.setAppLanguageMigrated(context);
        }
        syncLauncherAlias(context);
    }

    static void syncLauncherAlias(Context context) {
        String tag = currentTag(context);
        int selected = tag.isEmpty() ? 0 : indexOf(tag) + 1;
        if (selected <= 0) {
            selected = 0;
        }
        PackageManager packageManager = context.getPackageManager();
        for (int i = 0; i < LAUNCHER_ALIAS_SUFFIXES.length; i++) {
            ComponentName component = new ComponentName(
                    context,
                    context.getPackageName() + "." + LAUNCHER_ALIAS_SUFFIXES[i]);
            packageManager.setComponentEnabledSetting(
                    component,
                    i == selected
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    static String[] supportedTags() {
        return SUPPORTED_TAGS.clone();
    }

    static String nativeDisplayName(String tag) {
        Locale locale = Locale.forLanguageTag(normalize(tag));
        return locale.getDisplayName(locale);
    }

    private static int indexOf(String tag) {
        for (int i = 0; i < SUPPORTED_TAGS.length; i++) {
            if (SUPPORTED_TAGS[i].equals(tag)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String requestedTag) {
        if (requestedTag == null || requestedTag.trim().isEmpty()) {
            return SYSTEM_DEFAULT;
        }
        Locale requested = Locale.forLanguageTag(requestedTag);
        String canonical = requested.toLanguageTag();
        if ("zh".equals(requested.getLanguage())) {
            String script = requested.getScript();
            String country = requested.getCountry();
            return "Hant".equalsIgnoreCase(script)
                    || "TW".equalsIgnoreCase(country)
                    || "HK".equalsIgnoreCase(country)
                    || "MO".equalsIgnoreCase(country)
                    ? "zh-TW"
                    : "zh-CN";
        }
        for (String supported : SUPPORTED_TAGS) {
            if (supported.equalsIgnoreCase(canonical)) {
                return supported;
            }
        }
        return SYSTEM_DEFAULT;
    }
}
