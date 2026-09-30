package com.parcelrecode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

final class ParcelScanController implements AutoCloseable {
    private static final String LOG_TAG = "ParcelScanController";
    private static final int MAX_IMAGE_SIDE = 2560;

    interface Callback {
        void onScanComplete(ScanResult result);
        void onScanError(ScanError error);
    }

    static final class ScanResult {
        final int sessionId;
        final int target;
        final Bitmap bitmap;
        final TrackingRules.Candidate candidate;
        final long elapsedMs;

        ScanResult(
                int sessionId,
                int target,
                Bitmap bitmap,
                TrackingRules.Candidate candidate,
                long elapsedMs
        ) {
            this.sessionId = sessionId;
            this.target = target;
            this.bitmap = bitmap;
            this.candidate = candidate;
            this.elapsedMs = elapsedMs;
        }
    }

    static final class ScanError {
        final int sessionId;
        final int target;
        final Exception exception;
        final long elapsedMs;

        ScanError(int sessionId, int target, Exception exception, long elapsedMs) {
            this.sessionId = sessionId;
            this.target = target;
            this.exception = exception;
            this.elapsedMs = elapsedMs;
        }
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(newScanThreadFactory());
    private final ExecutorService diagnosticsExecutor =
            Executors.newSingleThreadExecutor(newDiagnosticsThreadFactory());
    private final ParcelRecognizer parcelRecognizer;
    private final RecognitionTimingStore timingStore;
    private final FailedSampleStore failedSampleStore;

    ParcelScanController(Context context) {
        appContext = context.getApplicationContext();
        parcelRecognizer = new ParcelRecognizer(appContext);
        timingStore = new RecognitionTimingStore(appContext);
        failedSampleStore = new FailedSampleStore(appContext);
    }

    void processPhoto(Uri uri, int target, int sessionId, Callback callback) {
        executor.execute(() -> {
            long startedAt = System.currentTimeMillis();
            Bitmap bitmap = null;
            try {
                bitmap = loadBitmap(uri, MAX_IMAGE_SIDE);
                TrackingRules.Candidate candidate = parcelRecognizer.recognize(bitmap);
                long elapsedMs = System.currentTimeMillis() - startedAt;
                Log.d(LOG_TAG, "Photo processed in " + elapsedMs + " ms");
                callback.onScanComplete(new ScanResult(sessionId, target, bitmap, candidate, elapsedMs));
                Bitmap diagnosticBitmap = bitmap;
                enqueueDiagnostics(() -> {
                    timingStore.record(elapsedMs, candidate, null);
                    if (candidate == null) {
                        failedSampleStore.recordNoCandidate(diagnosticBitmap, target, elapsedMs);
                    }
                });
            } catch (Exception error) {
                long elapsedMs = System.currentTimeMillis() - startedAt;
                Log.e(LOG_TAG, "Photo processing failed", error);
                callback.onScanError(new ScanError(sessionId, target, error, elapsedMs));
                enqueueDiagnostics(() -> {
                    timingStore.record(elapsedMs, null, error);
                    failedSampleStore.recordError(uri, target, elapsedMs, error);
                });
            }
        });
    }

    private Bitmap loadBitmap(Uri uri, int maxSide) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return loadLegacyBitmap(uri, maxSide);
        }
        ImageDecoder.Source source = "file".equals(uri.getScheme())
                ? ImageDecoder.createSource(new File(uri.getPath()))
                : ImageDecoder.createSource(appContext.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, imageSource) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            int largest = Math.max(width, height);
            if (largest > maxSide) {
                float scale = maxSide / (float) largest;
                decoder.setTargetSize(Math.round(width * scale), Math.round(height * scale));
            }
        });
    }

    private void enqueueDiagnostics(Runnable work) {
        try {
            diagnosticsExecutor.execute(work);
        } catch (RejectedExecutionException ignored) {
            Log.d(LOG_TAG, "Diagnostics skipped during shutdown");
        }
    }

    private Bitmap loadLegacyBitmap(Uri uri, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = appContext.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxSide * 2) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream input = appContext.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IOException("Bitmap decode failed");

        int largest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (largest > maxSide) {
            float scale = maxSide / (float) largest;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale),
                    true
            );
            bitmap.recycle();
            bitmap = scaled;
        }

        int rotation = readExifRotation(uri);
        if (rotation == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private int readExifRotation(Uri uri) {
        try (InputStream input = appContext.getContentResolver().openInputStream(uri)) {
            if (input == null) return 0;
            int orientation = new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException ignored) {
            return 0;
        }
        return 0;
    }

    @Override
    public void close() {
        parcelRecognizer.close();
        executor.shutdownNow();
        diagnosticsExecutor.shutdownNow();
    }

    private static ThreadFactory newScanThreadFactory() {
        return runnable -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
            runnable.run();
        }, "ParcelScan");
    }

    private static ThreadFactory newDiagnosticsThreadFactory() {
        return runnable -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "ParcelDiagnostics");
    }
}
