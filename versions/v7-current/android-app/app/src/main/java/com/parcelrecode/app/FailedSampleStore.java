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

final class FailedSampleStore {
    private static final String LOG_TAG = "FailedSampleStore";
    static final String FAILED_SAMPLE_DIR = "failed-samples";
    static final String FAILED_SAMPLE_INDEX = "failed-samples.csv";

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

        File imageFile = new File(directory, fileName);
        try (FileOutputStream output = new FileOutputStream(imageFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
            appendIndex(timestamp, FAILED_SAMPLE_DIR + "/" + fileName, target, elapsedMs, "no_candidate", "");
        } catch (IOException error) {
            Log.w(LOG_TAG, "Could not save failed sample image", error);
            appendIndex(timestamp, "", target, elapsedMs, "no_candidate", error.getClass().getSimpleName());
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

    private static String csv(String value) {
        String text = value == null ? "" : value;
        if (!text.contains(",") && !text.contains("\"") && !text.contains("\n")) return text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
