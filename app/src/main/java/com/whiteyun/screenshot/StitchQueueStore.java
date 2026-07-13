package com.whiteyun.screenshot;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Disk-backed FIFO input for long-shot stitching. */
final class StitchQueueStore {
    static final String STATE_QUEUED = "queued";
    static final String STATE_RUNNING = "running";
    static final String STATE_DONE = "done";
    static final String STATE_REVIEW = "review";
    static final String STATE_FAILED = "failed";
    static final String STATE_CANCELED = "canceled";

    private static final String ROOT_DIR = "stitch-queue";
    private static final String FRAMES_DIR = "frames";
    private static final String JOB_FILE = "job.properties";

    private StitchQueueStore() {
    }

    static Job enqueueAuto(
            Context context,
            String mode,
            File sourceDir,
            List<File> sourceFrames,
            int[] scrollDeltas) throws IOException {
        if (sourceDir == null || !sourceDir.isDirectory()) {
            throw new IOException("自动长截图原始帧不存在");
        }
        validateSourceFrames(sourceFrames);

        File root = root(context);
        String id = "job_" + UUID.randomUUID();
        File staging = new File(root, "." + id + ".part");
        File stagingFrames = new File(staging, FRAMES_DIR);
        File target = new File(root, id);
        boolean movedDirectory = false;
        try {
            if (!staging.mkdirs()) {
                throw new IOException("无法创建拼接队列任务");
            }
            // ponytail: cache and files normally share app-private storage, so rename is O(1);
            // fall back to copy if a device puts them on different volumes.
            movedDirectory = sourceDir.renameTo(stagingFrames);
            if (!movedDirectory) {
                copyFrames(sourceFrames, stagingFrames);
            }
            writeNewJob(staging, id, mode, sourceFrames, scrollDeltas);
            if (!staging.renameTo(target)) {
                throw new IOException("拼接队列任务发布失败");
            }
            return load(target);
        } catch (IOException | RuntimeException error) {
            if (movedDirectory && stagingFrames.isDirectory() && !sourceDir.exists()) {
                stagingFrames.renameTo(sourceDir);
            }
            deleteRecursively(staging);
            throw error;
        }
    }

    static Job enqueueManual(
            Context context,
            String mode,
            List<Bitmap> frames,
            int[] scrollDeltas) throws IOException {
        if (frames == null || frames.isEmpty()) {
            throw new IOException("没有可拼接的采样帧");
        }
        File root = root(context);
        String id = "job_" + UUID.randomUUID();
        File staging = new File(root, "." + id + ".part");
        File frameDir = new File(staging, FRAMES_DIR);
        ArrayList<File> written = new ArrayList<>(frames.size());
        try {
            if (!frameDir.mkdirs()) {
                throw new IOException("无法创建拼接队列原始帧目录");
            }
            for (int i = 0; i < frames.size(); i++) {
                Bitmap frame = frames.get(i);
                if (frame == null || frame.isRecycled()) {
                    throw new IOException("第 " + (i + 1) + " 帧已不可用");
                }
                File file = new File(frameDir, String.format("frame_%05d.png", i + 1));
                StreamingLongScreenshotStitcher.writeFramePng(frame, file);
                written.add(file);
            }
            writeNewJob(staging, id, mode, written, scrollDeltas);
            File target = new File(root, id);
            if (!staging.renameTo(target)) {
                throw new IOException("拼接队列任务发布失败");
            }
            return load(target);
        } catch (IOException | RuntimeException error) {
            deleteRecursively(staging);
            throw error;
        }
    }

    static Job takeNext(Context context) throws IOException {
        List<Job> jobs = loadAll(context);
        for (Job job : jobs) {
            if (STATE_RUNNING.equals(job.state)) {
                job.state = STATE_QUEUED;
                job.progress = 0;
                job.message = "已恢复后台拼接队列";
                save(job);
            }
        }
        for (Job job : jobs) {
            if (STATE_QUEUED.equals(job.state)) {
                job.state = STATE_RUNNING;
                job.progress = 0;
                save(job);
                return job;
            }
        }
        return null;
    }

    static boolean hasPending(Context context) {
        try {
            for (Job job : loadAll(context)) {
                if (STATE_QUEUED.equals(job.state) || STATE_RUNNING.equals(job.state)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // A corrupt old task must not block opening the app.
        }
        return false;
    }

    static List<Job> list(Context context) throws IOException {
        List<Job> jobs = loadAll(context);
        // ponytail: keep worker FIFO oldest-first while the user-facing list shows newest-first.
        Collections.reverse(jobs);
        return jobs;
    }

    static int queuePosition(Context context, String jobId) {
        try {
            int position = 0;
            for (Job job : loadAll(context)) {
                if (STATE_QUEUED.equals(job.state) || STATE_RUNNING.equals(job.state)) {
                    position++;
                }
                if (job.id.equals(jobId)) {
                    return position;
                }
            }
        } catch (IOException ignored) {
            // Status text without a position is still useful.
        }
        return 0;
    }

    static Job latest(Context context) {
        try {
            List<Job> jobs = loadAll(context);
            return jobs.isEmpty() ? null : jobs.get(jobs.size() - 1);
        } catch (IOException ignored) {
            return null;
        }
    }

    static Job requeueLatestRetryable(Context context) throws IOException {
        List<Job> jobs = loadAll(context);
        for (int i = jobs.size() - 1; i >= 0; i--) {
            Job job = jobs.get(i);
            if (STATE_FAILED.equals(job.state)
                    || STATE_CANCELED.equals(job.state)
                    || STATE_REVIEW.equals(job.state)) {
                job.state = STATE_QUEUED;
                job.progress = 0;
                job.message = "已重新加入后台拼接队列";
                save(job);
                return job;
            }
        }
        return null;
    }

    static Job cancelFirstPending(Context context) throws IOException {
        for (Job job : loadAll(context)) {
            if (STATE_QUEUED.equals(job.state) || STATE_RUNNING.equals(job.state)) {
                job.state = STATE_CANCELED;
                job.message = "已取消后台拼接";
                save(job);
                return job;
            }
        }
        return null;
    }

    static Job cancel(Context context, String jobId) throws IOException {
        if (jobId == null || jobId.isEmpty()) {
            return cancelFirstPending(context);
        }
        for (Job job : loadAll(context)) {
            if (job.id.equals(jobId)
                    && (STATE_QUEUED.equals(job.state) || STATE_RUNNING.equals(job.state))) {
                job.state = STATE_CANCELED;
                job.message = "已取消后台拼接";
                save(job);
                return job;
            }
        }
        return null;
    }

    static Job requeue(Context context, String jobId) throws IOException {
        if (jobId == null || jobId.isEmpty()) {
            return requeueLatestRetryable(context);
        }
        for (Job job : loadAll(context)) {
            if (job.id.equals(jobId)
                    && (STATE_FAILED.equals(job.state)
                    || STATE_CANCELED.equals(job.state)
                    || STATE_REVIEW.equals(job.state))) {
                job.state = STATE_QUEUED;
                job.progress = 0;
                job.message = "已重新加入后台拼接队列";
                save(job);
                return job;
            }
        }
        return null;
    }

    static void save(Job job) throws IOException {
        Properties values = new Properties();
        values.setProperty("id", job.id);
        values.setProperty("mode", job.mode);
        values.setProperty("createdAt", Long.toString(job.createdAt));
        values.setProperty("state", job.state);
        values.setProperty("progress", Integer.toString(job.progress));
        values.setProperty("message", job.message == null ? "" : job.message);
        values.setProperty("resultPath", job.resultPath == null ? "" : job.resultPath);
        values.setProperty("frameCount", Integer.toString(job.frameFiles.size()));
        for (int i = 0; i < job.frameFiles.size(); i++) {
            values.setProperty("frame." + i, job.frameFiles.get(i).getName());
            int delta = i < job.scrollDeltas.length ? job.scrollDeltas[i] : 0;
            values.setProperty("delta." + i, Integer.toString(delta));
        }
        File target = new File(job.directory, JOB_FILE);
        File part = new File(job.directory, JOB_FILE + ".part");
        try (FileOutputStream output = new FileOutputStream(part)) {
            values.store(output, "WhiteYun stitch queue");
        }
        if (!part.renameTo(target)) {
            // Android/Linux rename replaces the target; this fallback covers unusual filesystems.
            if (!target.delete() || !part.renameTo(target)) {
                part.delete();
                throw new IOException("拼接队列状态保存失败");
            }
        }
    }

    static void removeAutoFrames(Job job) {
        deleteRecursively(new File(job.directory, FRAMES_DIR));
    }

    private static void writeNewJob(
            File staging,
            String id,
            String mode,
            List<File> sourceFrames,
            int[] scrollDeltas) throws IOException {
        File finalDirectory = new File(staging.getParentFile(), id);
        File finalFrames = new File(finalDirectory, FRAMES_DIR);
        ArrayList<File> frameFiles = new ArrayList<>(sourceFrames.size());
        for (File source : sourceFrames) {
            frameFiles.add(new File(finalFrames, source.getName()));
        }
        Job job = new Job(
                finalDirectory,
                id,
                mode,
                System.currentTimeMillis(),
                STATE_QUEUED,
                0,
                "已加入后台拼接队列",
                "",
                frameFiles,
                normalizedDeltas(scrollDeltas, frameFiles.size()));
        // Save against the staging directory, then move the complete directory into view.
        Job stagingJob = job.withDirectory(staging);
        save(stagingJob);
    }

    private static List<Job> loadAll(Context context) throws IOException {
        File[] directories = root(context).listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) {
            return new ArrayList<>();
        }
        Arrays.sort(directories, Comparator.comparing(File::getName));
        ArrayList<Job> jobs = new ArrayList<>(directories.length);
        for (File directory : directories) {
            if (directory.getName().startsWith(".")) {
                continue;
            }
            File metadata = new File(directory, JOB_FILE);
            if (!metadata.isFile()) {
                continue;
            }
            try {
                jobs.add(load(directory));
            } catch (IOException ignored) {
                // Keep the queue runnable if an interrupted old job has malformed metadata.
            }
        }
        jobs.sort(Comparator.comparingLong(job -> job.createdAt));
        return jobs;
    }

    private static Job load(File directory) throws IOException {
        Properties values = new Properties();
        try (FileInputStream input = new FileInputStream(new File(directory, JOB_FILE))) {
            values.load(input);
        }
        String id = required(values, "id");
        String mode = required(values, "mode");
        int count = parseInt(values, "frameCount", 0);
        if (count <= 0) {
            throw new IOException("拼接队列帧清单为空");
        }
        String state = values.getProperty("state", STATE_QUEUED);
        ArrayList<File> frames = new ArrayList<>(count);
        int[] deltas = new int[count];
        File frameDir = new File(directory, FRAMES_DIR);
        for (int i = 0; i < count; i++) {
            String name = required(values, "frame." + i);
            File frame = new File(frameDir, name);
            if (!frame.isFile() && !STATE_DONE.equals(state)) {
                throw new IOException("拼接队列原始帧丢失");
            }
            frames.add(frame);
            deltas[i] = parseInt(values, "delta." + i, 0);
        }
        return new Job(
                directory,
                id,
                mode,
                parseLong(values, "createdAt", directory.lastModified()),
                state,
                parseInt(values, "progress", 0),
                values.getProperty("message", ""),
                values.getProperty("resultPath", ""),
                frames,
                deltas);
    }

    private static File root(Context context) throws IOException {
        File root = new File(context.getFilesDir(), ROOT_DIR);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("无法创建拼接队列目录");
        }
        return root;
    }

    private static void validateSourceFrames(List<File> frames) throws IOException {
        if (frames == null || frames.isEmpty()) {
            throw new IOException("没有可拼接的采样帧");
        }
        for (File frame : frames) {
            if (frame == null || !frame.isFile()) {
                throw new IOException("自动长截图原始帧丢失");
            }
        }
    }

    private static void copyFrames(List<File> sources, File destinationDir) throws IOException {
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            throw new IOException("无法创建拼接队列原始帧目录");
        }
        byte[] buffer = new byte[32 * 1024];
        for (File source : sources) {
            File target = new File(destinationDir, source.getName());
            File part = new File(target.getAbsolutePath() + ".part");
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(part)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            if (!part.renameTo(target)) {
                part.delete();
                throw new IOException("拼接队列原始帧发布失败");
            }
        }
    }

    private static int[] normalizedDeltas(int[] values, int count) {
        int[] normalized = new int[count];
        if (values != null) {
            System.arraycopy(values, 0, normalized, 0, Math.min(values.length, count));
        }
        return normalized;
    }

    private static String required(Properties values, String name) throws IOException {
        String value = values.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("拼接队列状态不完整");
        }
        return value;
    }

    private static int parseInt(Properties values, String name, int fallback) {
        try {
            return Integer.parseInt(values.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(Properties values, String name, long fallback) {
        try {
            return Long.parseLong(values.getProperty(name, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    static final class Job {
        final File directory;
        final String id;
        final String mode;
        final long createdAt;
        final List<File> frameFiles;
        final int[] scrollDeltas;
        String state;
        int progress;
        String message;
        String resultPath;

        Job(
                File directory,
                String id,
                String mode,
                long createdAt,
                String state,
                int progress,
                String message,
                String resultPath,
                List<File> frameFiles,
                int[] scrollDeltas) {
            this.directory = directory;
            this.id = id;
            this.mode = mode;
            this.createdAt = createdAt;
            this.state = state;
            this.progress = Math.max(0, Math.min(100, progress));
            this.message = message == null ? "" : message;
            this.resultPath = resultPath == null ? "" : resultPath;
            this.frameFiles = new ArrayList<>(frameFiles);
            this.scrollDeltas = normalizedDeltas(scrollDeltas, frameFiles.size());
        }

        Job withDirectory(File directory) {
            ArrayList<File> relocated = new ArrayList<>(frameFiles.size());
            File frameDir = new File(directory, FRAMES_DIR);
            for (File frame : frameFiles) {
                relocated.add(new File(frameDir, frame.getName()));
            }
            return new Job(
                    directory,
                    id,
                    mode,
                    createdAt,
                    state,
                    progress,
                    message,
                    resultPath,
                    relocated,
                    scrollDeltas);
        }
    }
}
