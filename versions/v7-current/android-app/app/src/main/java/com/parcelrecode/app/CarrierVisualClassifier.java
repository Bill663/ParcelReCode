package com.parcelrecode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class CarrierVisualClassifier {
    private static final String LOG_TAG = "CarrierV7Model";
    private static final int MODEL_VERSION = 7;
    private static final int FEATURE_SIZE = 3840;
    static final float CONFIDENCE_GATE = 0.85f;

    static final class Prediction {
        final String company;
        final float confidence;

        Prediction(String company, float confidence) {
            this.company = company;
            this.confidence = confidence;
        }
    }

    private static final class Tree {
        final int[] feature;
        final float[] threshold;
        final int[] left;
        final int[] right;
        final byte[] leafClass;

        Tree(int nodeCount) {
            feature = new int[nodeCount];
            threshold = new float[nodeCount];
            left = new int[nodeCount];
            right = new int[nodeCount];
            leafClass = new byte[nodeCount];
        }

        int predict(float[] values) {
            int node = 0;
            while (leafClass[node] < 0) {
                node = values[feature[node]] <= threshold[node] ? left[node] : right[node];
            }
            return leafClass[node];
        }
    }

    private final String[] labels;
    private final Tree[] trees;

    CarrierVisualClassifier(Context context) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                context.getAssets().open("carrier_model_v7.bin")
        ))) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!"PRC7".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("Unexpected v7 carrier model header");
            }
            int version = input.readInt();
            int labelCount = input.readInt();
            int treeCount = input.readInt();
            if (version != MODEL_VERSION || labelCount <= 1 || treeCount <= 0) {
                throw new IOException("Unsupported v7 carrier model metadata");
            }
            labels = new String[labelCount];
            for (int index = 0; index < labelCount; index++) {
                int length = input.readInt();
                if (length <= 0 || length > 128) throw new IOException("Invalid model label");
                byte[] encoded = new byte[length];
                input.readFully(encoded);
                labels[index] = new String(encoded, StandardCharsets.UTF_8);
            }
            trees = new Tree[treeCount];
            for (int treeIndex = 0; treeIndex < treeCount; treeIndex++) {
                int nodeCount = input.readInt();
                if (nodeCount <= 0 || nodeCount > 1_000_000) throw new IOException("Invalid model tree");
                Tree tree = new Tree(nodeCount);
                for (int node = 0; node < nodeCount; node++) {
                    tree.feature[node] = input.readInt();
                    tree.threshold[node] = input.readFloat();
                    tree.left[node] = input.readInt();
                    tree.right[node] = input.readInt();
                    tree.leafClass[node] = input.readByte();
                    if (tree.leafClass[node] < 0
                            && (tree.feature[node] < 0 || tree.feature[node] >= FEATURE_SIZE)) {
                        throw new IOException("Invalid model feature index");
                    }
                }
                trees[treeIndex] = tree;
            }
        }
        Log.i(LOG_TAG, "Loaded v7 carrier model with " + trees.length + " trees");
    }

    Prediction classify(Bitmap bitmap) {
        Prediction best = null;
        int[] rotations = {0, 90, 180, 270};
        for (int rotation : rotations) {
            Bitmap oriented = rotate(bitmap, rotation);
            float[] features = extractFeatures(oriented);
            if (oriented != bitmap) oriented.recycle();
            int[] votes = new int[labels.length];
            for (Tree tree : trees) votes[tree.predict(features)]++;
            int bestClass = 0;
            for (int index = 1; index < votes.length; index++) {
                if (votes[index] > votes[bestClass]) bestClass = index;
            }
            float confidence = votes[bestClass] / (float) trees.length;
            if (best == null || confidence > best.confidence) {
                best = new Prediction(labels[bestClass], confidence);
            }
        }
        return best;
    }

    private static Bitmap rotate(Bitmap bitmap, int degrees) {
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static float[] extractFeatures(Bitmap bitmap) {
        Bitmap scaled32 = Bitmap.createScaledBitmap(bitmap, 32, 32, true);
        int[] pixels32 = new int[32 * 32];
        scaled32.getPixels(pixels32, 0, 32, 0, 0, 32, 32);
        if (scaled32 != bitmap) scaled32.recycle();

        Bitmap scaled16 = Bitmap.createScaledBitmap(bitmap, 16, 16, true);
        int[] pixels16 = new int[16 * 16];
        scaled16.getPixels(pixels16, 0, 16, 0, 0, 16, 16);
        if (scaled16 != bitmap) scaled16.recycle();

        float[] features = new float[FEATURE_SIZE];
        float[] gray = new float[32 * 32];
        for (int index = 0; index < pixels32.length; index++) {
            int color = pixels32[index];
            gray[index] = (0.299f * ((color >> 16) & 0xff)
                    + 0.587f * ((color >> 8) & 0xff)
                    + 0.114f * (color & 0xff)) / 255f;
            features[index] = gray[index];
        }
        int offset = 32 * 32;
        for (int color : pixels16) {
            features[offset++] = ((color >> 16) & 0xff) / 255f;
            features[offset++] = ((color >> 8) & 0xff) / 255f;
            features[offset++] = (color & 0xff) / 255f;
        }
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                features[offset++] = x == 0 || x == 31
                        ? 0f
                        : (gray[y * 32 + x + 1] - gray[y * 32 + x - 1]) * 0.5f;
            }
        }
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                features[offset++] = y == 0 || y == 31
                        ? 0f
                        : (gray[(y + 1) * 32 + x] - gray[(y - 1) * 32 + x]) * 0.5f;
            }
        }
        return features;
    }
}
