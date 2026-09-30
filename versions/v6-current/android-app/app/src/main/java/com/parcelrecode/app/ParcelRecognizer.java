package com.parcelrecode.app;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Process;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import zxingcpp.BarcodeReader;

public final class ParcelRecognizer implements AutoCloseable {
    private static final String LOG_TAG = "ParcelRecognizer";

    private final BarcodeReader barcodeReader;
    private final TextRecognizer textRecognizer;
    private final ExecutorService recognitionExecutor;
    private final int workerCount;

    public ParcelRecognizer() {
        BarcodeReader.Options options = new BarcodeReader.Options();
        options.setFormats(EnumSet.of(
                BarcodeReader.Format.QR_CODE,
                BarcodeReader.Format.CODE_128,
                BarcodeReader.Format.CODE_39,
                BarcodeReader.Format.DATA_MATRIX,
                BarcodeReader.Format.PDF_417
        ));
        options.setTryHarder(true);
        options.setTryRotate(true);
        options.setTryInvert(true);
        options.setTryDownscale(true);
        options.setTryDenoise(true);
        options.setTextMode(BarcodeReader.TextMode.HRI);
        barcodeReader = new BarcodeReader(options);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        workerCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        recognitionExecutor = Executors.newFixedThreadPool(workerCount, newRecognitionThreadFactory());
    }

    public TrackingRules.Candidate recognize(Bitmap bitmap) throws Exception {
        long startedAt = System.currentTimeMillis();
        Future<List<TrackingRules.Candidate>> barcodeFuture =
                recognitionExecutor.submit(() -> decodeBarcodes(bitmap));
        Future<List<TrackingRules.Candidate>> ocrFuture =
                recognitionExecutor.submit(() -> extractOcrCandidates(bitmap));

        List<TrackingRules.Candidate> candidates = getFutureResult(barcodeFuture);
        TrackingRules.Candidate barcodeBest = TrackingRules.chooseBest(candidates);
        boolean needsOcr = barcodeBest == null
                || "usps".equals(barcodeBest.company)
                || ("fedex".equals(barcodeBest.company) && !barcodeBest.isFedExMachine());

        if (needsOcr) {
            try {
                candidates.addAll(getFutureResult(ocrFuture));
            } catch (Exception error) {
                if (barcodeBest == null) throw error;
                return barcodeBest;
            }
        } else {
            ocrFuture.cancel(true);
        }
        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        Log.d(LOG_TAG, "Recognition finished in " + (System.currentTimeMillis() - startedAt)
                + " ms with " + workerCount + " recognition workers");
        return best;
    }

    private List<TrackingRules.Candidate> extractOcrCandidates(Bitmap bitmap) throws Exception {
        List<TrackingRules.Candidate> candidates = new ArrayList<>();
        int[] rotations = {0, 180, 90, 270};
        for (int rotation : rotations) {
            InputImage image = InputImage.fromBitmap(bitmap, rotation);
            String text = Tasks.await(textRecognizer.process(image)).getText();
            candidates.addAll(TrackingRules.extractTextCandidates(text));
            if (!candidates.isEmpty()) break;
        }
        return candidates;
    }

    private static <T> T getFutureResult(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    private List<TrackingRules.Candidate> decodeBarcodes(Bitmap bitmap) {
        List<TrackingRules.Candidate> candidates = new ArrayList<>();
        List<BarcodeReader.Result> results = barcodeReader.read(bitmap, new Rect(), 0);
        for (BarcodeReader.Result result : results) {
            TrackingRules.Candidate candidate = TrackingRules.parseMachineValue(result.getText());
            if (candidate != null) candidates.add(candidate);
        }
        return candidates;
    }

    @Override
    public void close() {
        recognitionExecutor.shutdownNow();
        textRecognizer.close();
    }

    private static ThreadFactory newRecognitionThreadFactory() {
        AtomicInteger threadNumber = new AtomicInteger();
        return runnable -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
            runnable.run();
        }, "ParcelRecognition-" + threadNumber.incrementAndGet());
    }
}
