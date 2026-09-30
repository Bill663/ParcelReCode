import csv
import json
import random
import struct
import sys
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parents[1]
sys.path.insert(0, str((WORKSPACE / ".ml-packages").resolve()))

import numpy as np
from PIL import Image, ImageOps
from sklearn.ensemble import ExtraTreesClassifier
from sklearn.metrics import accuracy_score, balanced_accuracy_score, classification_report
from sklearn.model_selection import StratifiedGroupKFold, cross_val_predict


MODEL_PATH = ROOT / "android-app" / "app" / "src" / "main" / "assets" / "carrier_model_v7.bin"
OUTPUT = WORKSPACE / "sample-photos2" / "curation-v7" / "training"
NEW_LABELS = WORKSPACE / "sample-photos2" / "curation-v7" / "labels.csv"
OLD_LABELS = WORKSPACE / "versions" / "v3-current" / "sample-photos2" / "labels.csv"
RANDOM_SEED = 17
MAX_PER_CARRIER = 250
ROTATIONS = (0, 90, 180, 270)


def load_rows():
    rows = []
    sources = [
        (NEW_LABELS, WORKSPACE / "sample-photos2", "v7-new"),
        (OLD_LABELS, OLD_LABELS.parent, "v3-reviewed"),
    ]
    for label_path, image_root, source in sources:
        with label_path.open(newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                image_path = image_root / row["filename"]
                if not image_path.exists():
                    raise FileNotFoundError(image_path)
                rows.append(
                    {
                        "path": image_path,
                        "carrier": row["carrier"].strip().lower(),
                        "tracking": row.get("tracking", "").strip(),
                        "source": source,
                    }
                )

    grouped = defaultdict(list)
    for row in rows:
        grouped[row["carrier"]].append(row)
    rng = random.Random(RANDOM_SEED)
    selected = []
    for carrier, carrier_rows in sorted(grouped.items()):
        rng.shuffle(carrier_rows)
        selected.extend(carrier_rows[:MAX_PER_CARRIER])
    return selected


def image_features(image):
    rgb32 = image.convert("RGB").resize((32, 32), Image.Resampling.BILINEAR)
    pixels = np.asarray(rgb32, dtype=np.float32) / 255.0
    gray = 0.299 * pixels[:, :, 0] + 0.587 * pixels[:, :, 1] + 0.114 * pixels[:, :, 2]
    rgb16 = np.asarray(image.convert("RGB").resize((16, 16), Image.Resampling.BILINEAR), dtype=np.float32) / 255.0
    gx = np.zeros_like(gray)
    gy = np.zeros_like(gray)
    gx[:, 1:-1] = (gray[:, 2:] - gray[:, :-2]) * 0.5
    gy[1:-1, :] = (gray[2:, :] - gray[:-2, :]) * 0.5
    return np.concatenate((gray.ravel(), rgb16.ravel(), gx.ravel(), gy.ravel()))


def load_feature_rows(rows):
    features = []
    labels = []
    groups = []
    metadata = []
    for index, row in enumerate(rows):
        with Image.open(row["path"]) as opened:
            opened.draft("RGB", (512, 512))
            image = ImageOps.exif_transpose(opened).convert("RGB")
        for rotation in ROTATIONS:
            rotated = image if rotation == 0 else image.rotate(rotation, expand=True)
            features.append(image_features(rotated))
            labels.append(row["carrier"])
            groups.append(index)
            metadata.append((row, rotation))
    return np.asarray(features), np.asarray(labels), np.asarray(groups), metadata


def export_model(classifier):
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    with MODEL_PATH.open("wb") as handle:
        handle.write(b"PRC7")
        handle.write(struct.pack(">iii", 7, len(classifier.classes_), len(classifier.estimators_)))
        for label in classifier.classes_:
            encoded = str(label).encode("utf-8")
            handle.write(struct.pack(">i", len(encoded)))
            handle.write(encoded)
        for estimator in classifier.estimators_:
            tree = estimator.tree_
            handle.write(struct.pack(">i", tree.node_count))
            for index in range(tree.node_count):
                leaf_class = int(np.argmax(tree.value[index][0])) if tree.children_left[index] < 0 else -1
                handle.write(struct.pack(
                    ">ifiib",
                    int(tree.feature[index]),
                    float(tree.threshold[index]),
                    int(tree.children_left[index]),
                    int(tree.children_right[index]),
                    leaf_class,
                ))


def main():
    rows = load_rows()
    counts = dict(sorted(Counter(row["carrier"] for row in rows).items()))
    source_counts = dict(sorted(Counter(row["source"] for row in rows).items()))
    print(f"Selected {len(rows)} source images: {counts}", flush=True)
    features, labels, groups, metadata = load_feature_rows(rows)
    classifier = ExtraTreesClassifier(
        n_estimators=160,
        max_depth=22,
        min_samples_leaf=1,
        max_features=0.2,
        class_weight="balanced",
        n_jobs=-1,
        random_state=RANDOM_SEED,
    )
    cv = StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=RANDOM_SEED)
    probabilities = cross_val_predict(
        classifier,
        features,
        labels,
        cv=cv,
        groups=groups,
        n_jobs=1,
        method="predict_proba",
    )
    class_names = np.asarray(sorted(set(labels)))
    predictions = class_names[np.argmax(probabilities, axis=1)]
    confidence = np.max(probabilities, axis=1)
    accuracy = float(accuracy_score(labels, predictions))
    balanced = float(balanced_accuracy_score(labels, predictions))
    metrics = {
        "accuracy": round(accuracy, 6),
        "balancedAccuracy": round(balanced, 6),
    }
    classifier.fit(features, labels)
    export_model(classifier)

    gated_metrics = {}
    for threshold in (0.80, 0.85, 0.90):
        selected = confidence >= threshold
        gated_metrics[f"{threshold:.2f}"] = {
            "coverage": round(float(selected.mean()), 6),
            "precision": round(float((predictions[selected] == labels[selected]).mean()), 6) if selected.any() else 0.0,
            "rows": int(selected.sum()),
        }

    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = classification_report(labels, predictions, output_dict=True, zero_division=0)
    summary = {
        "modelVersion": 7,
        "sourceImages": len(rows),
        "augmentedTrainingRows": len(labels),
        "sourceCounts": source_counts,
        "carrierCountsAfterCap": counts,
        "crossValidationAccuracy": metrics["accuracy"],
        "crossValidationBalancedAccuracy": metrics["balancedAccuracy"],
        "confidenceGateMetrics": gated_metrics,
        "classificationReport": report,
        "modelPath": str(MODEL_PATH.relative_to(ROOT)).replace("\\", "/"),
    }
    (OUTPUT / "carrier-model-v7-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    with (OUTPUT / "carrier-model-v7-predictions.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["filename", "source", "rotation", "expected", "predicted", "correct", "confidence"])
        for (row, rotation), expected, predicted, probability in zip(metadata, labels, predictions, confidence):
            writer.writerow([
                row["path"].name,
                row["source"],
                rotation,
                expected,
                predicted,
                str(expected == predicted).lower(),
                f"{probability:.6f}",
            ])
    print(f"CV accuracy: {accuracy:.2%}")
    print(f"CV balanced accuracy: {balanced:.2%}")
    print(f"Model: {MODEL_PATH}")
    print(f"Summary: {OUTPUT / 'carrier-model-v7-summary.json'}")


if __name__ == "__main__":
    main()
