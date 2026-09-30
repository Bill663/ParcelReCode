package com.parcelrecode.app;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

import zxingcpp.BarcodeReader;

public final class ParcelRecognizer implements AutoCloseable {
    private final BarcodeReader barcodeReader;
    private final TextRecognizer textRecognizer;

    public ParcelRecognizer() {
        BarcodeReader.Options options = new BarcodeReader.Options();
        options.setTryHarder(true);
        options.setTryRotate(true);
        options.setTryInvert(true);
        options.setTryDownscale(true);
        options.setTryDenoise(true);
        options.setTextMode(BarcodeReader.TextMode.HRI);
        barcodeReader = new BarcodeReader(options);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public TrackingRules.Candidate recognize(Bitmap bitmap) throws Exception {
        List<TrackingRules.Candidate> candidates = decodeBarcodes(bitmap);
        TrackingRules.Candidate barcodeBest = TrackingRules.chooseBest(candidates);
        boolean needsOcr = barcodeBest == null
                || "usps".equals(barcodeBest.company)
                || ("fedex".equals(barcodeBest.company) && !barcodeBest.isFedExMachine());

        if (needsOcr) {
            try {
                InputImage image = InputImage.fromBitmap(bitmap, 0);
                String text = Tasks.await(textRecognizer.process(image)).getText();
                candidates.addAll(TrackingRules.extractTextCandidates(text));
            } catch (Exception error) {
                if (barcodeBest == null) throw error;
                return barcodeBest;
            }
        }
        return TrackingRules.chooseBest(candidates);
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
        textRecognizer.close();
    }
}
