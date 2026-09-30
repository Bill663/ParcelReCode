package com.parcelrecode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

final class FailedSampleStore {
    private static final String LOG_TAG = "FailedSampleStore";
    static final String FAILED_SAMPLE_DIR = "failed-samples";
    static final String FAILED_SAMPLE_INDEX = "failed-samples.csv";
    private static final String PREVIOUS_INDEX = "failed-samples.previous.csv";
    private static final int MAX_FAILED_SAMPLES = 40;
    private static final int MAX_FAILED_SAMPLE_SIDE = 1600;
    private static final long MAX_INDEX_BYTES = 512L * 1024L;

    private final Context appContext;

    FailedSampleStore(Context context) {
        appContext = context.getApplicationContext();
    }

    synchronized void recordNoCandidate(Bitmap bitmap, int target, long elapsedMs) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String fileName = "failed-" + timestamp + "-target-" + target + ".jpg";
        File directory = new File(appContext.getFilesDir(), FAILED_SAMPLE_DIR);
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(LOG_TAG, "Could not create failed sample directory");
            appendIndex(timestamp, "", target, elapsedMs, "no_candidate", "directory_create_failed");
            return;
        }

        pruneForNextSample(directory);
        File imageFile = new File(directory, fileName);
        Bitmap storedBitmap = downscaleForStorage(bitmap);
        try (FileOutputStream output = new FileOutputStream(imageFile)) {
            if (!storedBitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                throw new IOException("Bitmap compression failed");
            }
            appendIndex(timestamp, FAILED_SAMPLE_DIR + "/" + fileName, target, elapsedMs, "no_candidate", "");
        } catch (IOException error) {
            Log.w(LOG_TAG, "Could not save failed sample image", error);
            appendIndex(timestamp, "", target, elapsedMs, "no_candidate", error.getClass().getSimpleName());
        } finally {
            if (storedBitmap != bitmap) storedBitmap.recycle();
        }
    }

    synchronized void recordError(Uri uri, int target, long elapsedMs, Exception error) {
        String detail = error == null ? "" : error.getClass().getSimpleName();
        if (uri != null) detail = detail + " " + uri;
        appendIndex(String.valueOf(System.currentTimeMillis()), "", target, elapsedMs, "error", detail.trim());
    }

    private void appendIndex(
            String timestamp,
            String imagePath,
            int target,
            long elapsedMs,
            String reason,
            String detail
    ) {
        File file = new File(appContext.getFilesDir(), FAILED_SAMPLE_INDEX);
        rotateIndexIfNeeded(file);
        boolean writeHeader = !file.exists() || file.length() == 0;
        StringBuilder row = new StringBuilder();
        if (writeHeader) {
            row.append("timestamp_ms,manufacturer,model,hardware,target,elapsed_ms,reason,image_path,detail\n");
        }
        row.append(timestamp).append(',')
                .append(csv(Build.MANUFACTURER)).append(',')
                .append(csv(Build.MODEL)).append(',')
                .append(csv(Build.HARDWARE)).append(',')
                .append(target).append(',')
                .append(elapsedMs).append(',')
                .append(csv(reason)).append(',')
                .append(csv(imagePath)).append(',')
                .append(csv(detail)).append('\n');
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(row.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.w(LOG_TAG, "Could not write failed sample index", error);
        }
    }

    private void pruneForNextSample(File directory) {
        File[] images = directory.listFiles((unused, name) -> name.endsWith(".jpg"));
        if (images == null || images.length < MAX_FAILED_SAMPLES) return;
        Arrays.sort(images, Comparator.comparingLong(File::lastModified));
        int removeCount = images.length - MAX_FAILED_SAMPLES + 1;
        for (int index = 0; index < removeCount; index++) {
            if (!images[index].delete()) {
                Log.w(LOG_TAG, "Could not prune failed sample " + images[index].getName());
            }
        }
    }

    private static Bitmap downscaleForStorage(Bitmap bitmap) {
        int largestSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (largestSide <= MAX_FAILED_SAMPLE_SIDE) return bitmap;
        float scale = MAX_FAILED_SAMPLE_SIDE / (float) largestSide;
        return Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)),
                true);
    }

    private void rotateIndexIfNeeded(File file) {
        if (!file.exists() || file.length() < MAX_INDEX_BYTES) return;
        File previous = new File(appContext.getFilesDir(), PREVIOUS_INDEX);
        if (previous.exists() && !previous.delete()) {
            Log.w(LOG_TAG, "Could not replace previous failed sample index");
            return;
        }
        if (!file.renameTo(previous)) {
            Log.w(LOG_TAG, "Could not rotate failed sample index");
        }
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        if (!text.contains(",") && !text.contains("\"") && !text.contains("\n")) return text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
