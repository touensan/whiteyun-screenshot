package com.whiteyun.screenshot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Diagnostics {
    private static final String DIR_NAME = "diagnostics";
    private static final String LAST_CRASH = "last_crash.txt";
    private static volatile boolean installed;

    private Diagnostics() {
    }

    static void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrash(appContext, thread, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    static Uri export(Context context) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "WhiteYunDiagnostics_" + timestamp + ".txt";
        byte[] report = buildReport(context).getBytes(StandardCharsets.UTF_8);

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WhiteYunScreenshot");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }

        try (OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) {
                throw new IOException("MediaStore openOutputStream returned null");
            }
            output.write(report);
        } catch (IOException exception) {
            resolver.delete(uri, null, null);
            throw exception;
        }

        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private static void writeCrash(Context context, Thread thread, Throwable throwable) {
        try {
            File dir = new File(context.getFilesDir(), DIR_NAME);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            try (FileOutputStream output = new FileOutputStream(new File(dir, LAST_CRASH))) {
                output.write(buildCrashReport(context, thread, throwable).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    static String buildReport(Context context) {
        StringBuilder builder = new StringBuilder();
        builder.append("WhiteYunScreenshot diagnostics\n");
        builder.append("Generated: ").append(now()).append('\n');
        appendAppInfo(context, builder);
        appendDeviceInfo(builder);
        appendMemoryInfo(builder);
        appendAutoScrollEvidenceInfo(context, builder);
        appendStitchDebugInfo(context, builder);
        builder.append('\n').append("Last crash:\n");
        String crash = readLastCrash(context);
        builder.append(crash == null ? "none\n" : crash);
        return builder.toString();
    }

    private static String buildCrashReport(Context context, Thread thread, Throwable throwable) {
        StringWriter trace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(trace));
        StringBuilder builder = new StringBuilder();
        builder.append("Time: ").append(now()).append('\n');
        builder.append("Thread: ").append(thread.getName()).append('\n');
        appendAppInfo(context, builder);
        appendDeviceInfo(builder);
        builder.append('\n').append(trace);
        return builder.toString();
    }

    private static void appendAppInfo(Context context, StringBuilder builder) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            builder.append("Package: ").append(context.getPackageName()).append('\n');
            builder.append("Version: ").append(info.versionName).append(" (").append(info.getLongVersionCode()).append(")\n");
        } catch (PackageManager.NameNotFoundException exception) {
            builder.append("Package: ").append(context.getPackageName()).append('\n');
            builder.append("Version: unknown\n");
        }
    }

    private static void appendDeviceInfo(StringBuilder builder) {
        builder.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        builder.append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" / ").append(Build.DEVICE).append('\n');
    }

    private static void appendMemoryInfo(StringBuilder builder) {
        Runtime runtime = Runtime.getRuntime();
        builder.append("Runtime max MB: ").append(runtime.maxMemory() / 1024 / 1024).append('\n');
        builder.append("Runtime total MB: ").append(runtime.totalMemory() / 1024 / 1024).append('\n');
        builder.append("Runtime free MB: ").append(runtime.freeMemory() / 1024 / 1024).append('\n');
    }

    private static void appendAutoScrollEvidenceInfo(Context context, StringBuilder builder) {
        builder.append("Auto scroll evidence:\n");
        AutoScrollEvidenceStore.EvidenceSummary summary = AutoScrollEvidenceStore.latestSummary(context);
        if (summary == null) {
            builder.append("none\n");
            return;
        }
        builder.append("Directory: ").append(summary.dirPath).append('\n');
        builder.append("Manifest: ").append(summary.manifestPath).append('\n');
    }

    private static void appendStitchDebugInfo(Context context, StringBuilder builder) {
        builder.append("Stitch debug:\n");
        StitchDebugStore.StitchDebugSummary summary = StitchDebugStore.latestSummary(context);
        if (summary == null) {
            builder.append("none\n");
            return;
        }
        builder.append("Directory: ").append(summary.dirPath).append('\n');
        builder.append("Manifest: ").append(summary.manifestPath).append('\n');
    }

    private static String readLastCrash(Context context) {
        File file = new File(new File(context.getFilesDir(), DIR_NAME), LAST_CRASH);
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        } catch (IOException exception) {
            return "Could not read last crash: " + exception.getMessage() + '\n';
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
    }
}
