package com.parcelrecode.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class RecognitionTimingStore {
    private static final String LOG_TAG = "RecognitionTiming";
    private static final String PREFS_NAME = "recognition_timing";
    static final String TIMING_FILE_NAME = "recognition-timing.csv";
    private static final String PREVIOUS_TIMING_FILE_NAME = "recognition-timing.previous.csv";
    private static final long MAX_TIMING_FILE_BYTES = 512L * 1024L;

    private final Context appContext;

    RecognitionTimingStore(Context context) {
        appContext = context.getApplicationContext();
    }

    synchronized void record(long elapsedMs, TrackingRules.Candidate candidate, Exception error) {
        boolean success = candidate != null;
        String carrier = candidate == null ? "" : candidate.carrier;
        String source = candidate == null ? "" : candidate.source;
        String errorName = error == null ? "" : error.getClass().getSimpleName();
        updateAggregate(elapsedMs, success);
        appendCsvRow(elapsedMs, success, carrier, source, errorName);
    }

    private void updateAggregate(long elapsedMs, boolean success) {
        String keyPrefix = deviceKey();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long count = prefs.getLong(keyPrefix + ".count", 0L) + 1L;
        long successCount = prefs.getLong(keyPrefix + ".success", 0L) + (success ? 1L : 0L);
        long totalMs = prefs.getLong(keyPrefix + ".total_ms", 0L) + elapsedMs;
        prefs.edit()
                .putLong(keyPrefix + ".count", count)
                .putLong(keyPrefix + ".success", successCount)
                .putLong(keyPrefix + ".total_ms", totalMs)
                .putLong(keyPrefix + ".last_ms", elapsedMs)
                .putLong(keyPrefix + ".last_at", System.currentTimeMillis())
                .apply();
    }

    private void appendCsvRow(
            long elapsedMs,
            boolean success,
            String carrier,
            String source,
            String errorName
    ) {
        File file = new File(appContext.getFilesDir(), TIMING_FILE_NAME);
        rotateTimingFileIfNeeded(file);
        boolean writeHeader = !file.exists() || file.length() == 0;
        StringBuilder row = new StringBuilder();
        if (writeHeader) {
            row.append("timestamp_ms,manufacturer,model,hardware,sdk,elapsed_ms,success,carrier,source,error\n");
        }
        row.append(System.currentTimeMillis()).append(',')
                .append(csv(Build.MANUFACTURER)).append(',')
                .append(csv(Build.MODEL)).append(',')
                .append(csv(Build.HARDWARE)).append(',')
                .append(Build.VERSION.SDK_INT).append(',')
                .append(elapsedMs).append(',')
                .append(success).append(',')
                .append(csv(carrier)).append(',')
                .append(csv(source)).append(',')
                .append(csv(errorName)).append('\n');
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(row.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.w(LOG_TAG, "Could not write recognition timing", error);
        }
    }

    private void rotateTimingFileIfNeeded(File file) {
        if (!file.exists() || file.length() < MAX_TIMING_FILE_BYTES) return;
        File previous = new File(appContext.getFilesDir(), PREVIOUS_TIMING_FILE_NAME);
        if (previous.exists() && !previous.delete()) {
            Log.w(LOG_TAG, "Could not replace previous recognition timing log");
            return;
        }
        if (!file.renameTo(previous)) {
            Log.w(LOG_TAG, "Could not rotate recognition timing log");
        }
    }

    private static String deviceKey() {
        return (Build.MANUFACTURER + "_" + Build.MODEL + "_" + Build.HARDWARE)
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "_");
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        if (!text.contains(",") && !text.contains("\"") && !text.contains("\n")) return text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
