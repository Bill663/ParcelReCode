package com.parcelrecode.app;

import android.content.Context;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final CarrierVisualClassifier visualClassifier;
    private final ExecutorService recognitionExecutor;
    private final int workerCount;

    public ParcelRecognizer(Context context) {
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
        CarrierVisualClassifier loadedClassifier = null;
        try {
            loadedClassifier = new CarrierVisualClassifier(context.getApplicationContext());
        } catch (Exception error) {
            Log.e(LOG_TAG, "V7 carrier model unavailable; barcode and OCR remain active", error);
        }
        visualClassifier = loadedClassifier;
        workerCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        recognitionExecutor = Executors.newFixedThreadPool(workerCount, newRecognitionThreadFactory());
    }

    public TrackingRules.Candidate recognize(Bitmap bitmap) throws Exception {
        long startedAt = System.currentTimeMillis();
        Future<List<TrackingRules.Candidate>> barcodeFuture =
                recognitionExecutor.submit(() -> decodeBarcodes(bitmap));
        Future<List<TrackingRules.Candidate>> ocrFuture =
                recognitionExecutor.submit(() -> extractOcrCandidates(bitmap));
        Future<CarrierVisualClassifier.Prediction> modelFuture = visualClassifier == null
                ? null
                : recognitionExecutor.submit(() -> visualClassifier.classify(bitmap));

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
        CarrierVisualClassifier.Prediction modelPrediction = null;
        if (modelFuture != null) {
            try {
                modelPrediction = getFutureResult(modelFuture);
                Log.d(LOG_TAG, "V7 model predicted " + modelPrediction.company
                        + " at " + modelPrediction.confidence);
            } catch (Exception error) {
                Log.w(LOG_TAG, "V7 carrier model inference failed", error);
            }
        }
        TrackingRules.Candidate best = chooseBestWithModel(candidates, modelPrediction);
        Log.d(LOG_TAG, "Recognition finished in " + (System.currentTimeMillis() - startedAt)
                + " ms with " + workerCount + " recognition workers");
        return best;
    }

    private static TrackingRules.Candidate chooseBestWithModel(
            List<TrackingRules.Candidate> candidates,
            CarrierVisualClassifier.Prediction prediction
    ) {
        if (prediction == null || prediction.confidence < CarrierVisualClassifier.CONFIDENCE_GATE) {
            return TrackingRules.chooseBest(candidates);
        }
        Set<String> companies = new HashSet<>();
        for (TrackingRules.Candidate candidate : candidates) companies.add(candidate.company);
        if (companies.size() < 2) return TrackingRules.chooseBest(candidates);

        List<TrackingRules.Candidate> adjusted = new ArrayList<>(candidates.size());
        for (TrackingRules.Candidate candidate : candidates) {
            if (prediction.company.equals(candidate.company)) {
                adjusted.add(candidate.withSource(candidate.source + " + v7 model", candidate.score + 45));
            } else {
                adjusted.add(candidate);
            }
        }
        return TrackingRules.chooseBest(adjusted);
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
