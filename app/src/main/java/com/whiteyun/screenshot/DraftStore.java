package com.whiteyun.screenshot;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class DraftStore {
    private DraftStore() {
    }

    static File createDraftFile(Context context) throws IOException {
        File dir = draftDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create draft dir");
        }
        String name = "WhiteYunDraft_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".png";
        return new File(dir, name);
    }

    static File promotePreview(Context context, File source) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("Preview file is missing");
        }
        if (isDraftFile(context, source)) {
            return source;
        }
        File target = createDraftFile(context);
        if (source.renameTo(target)) {
            return target;
        }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!source.delete()) {
            // ponytail: keeping the cache copy is safer than deleting the recovered draft; cache eviction is the cleanup path.
        }
        return target;
    }

    static File promoteLatestPreviewCache(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), "manual");
        File[] files = dir.listFiles((parent, name) ->
                name.startsWith("WhiteYunLongShot_") && name.endsWith(".png"));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        return promotePreview(context, files[0]);
    }

    static boolean isDraftFile(Context context, File file) {
        if (context == null || file == null) {
            return false;
        }
        try {
            return draftDir(context).getCanonicalFile().equals(file.getCanonicalFile().getParentFile());
        } catch (IOException exception) {
            return false;
        }
    }

    static List<DraftItem> list(Context context, int limit) {
        File[] files = draftDir(context).listFiles((dir, name) ->
                name.startsWith("WhiteYunDraft_") && name.endsWith(".png"));
        ArrayList<DraftItem> items = new ArrayList<>();
        if (files == null) {
            return items;
        }
        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        for (File file : files) {
            if (file.isFile()) {
                items.add(new DraftItem(file));
                if (limit > 0 && items.size() >= limit) {
                    break;
                }
            }
        }
        return items;
    }

    private static File draftDir(Context context) {
        return new File(context.getFilesDir(), "drafts");
    }

    static final class DraftItem {
        final File file;
        final String name;
        final long modifiedMillis;
        final long sizeBytes;

        DraftItem(File file) {
            this.file = file;
            this.name = file.getName();
            this.modifiedMillis = file.lastModified();
            this.sizeBytes = file.length();
        }
    }
}
