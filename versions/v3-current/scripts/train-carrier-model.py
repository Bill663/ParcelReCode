import csv
import json
import re
import sys
import warnings
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def find_workspace_packages():
    for candidate_root in [ROOT, *ROOT.parents]:
        package_path = candidate_root / ".ml-packages"
        if package_path.exists():
            return package_path
    return ROOT / ".ml-packages"


sys.path.insert(0, str(find_workspace_packages().resolve()))

import numpy as np
import zxingcpp
from PIL import Image, ImageOps
from scipy import ndimage
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler


warnings.filterwarnings("ignore")
MODEL_PATH = ROOT / "android-app" / "app" / "src" / "main" / "assets" / "carrier_model.json"
REPORT_PATH = ROOT / "sample-photos" / "exports" / "training" / "carrier-model-report.csv"
SUMMARY_PATH = ROOT / "sample-photos" / "exports" / "training" / "carrier-model-summary.json"


def clean(value):
    return re.sub("[^A-Z0-9]", "", (value or "").upper())


def carrier_from_barcode(raw_value):
    compact = clean(raw_value)
    patterns = [
        (r"UUS[A-Z0-9]{16}", "uniuni"),
        (r"GFUS\d{14}", "gofo"),
        (r"SWX\d{18}", "swiftx"),
        (r"SPX[A-Z]{3}\d{12}", "speedx"),
        (r"1LSD[A-Z0-9]{11}", "ontrac"),
        (r"96\d{32}", "fedex"),
        (r"420\d{5,9}92\d{20}", "usps"),
    ]
    for pattern, carrier in patterns:
        if re.search(pattern, compact):
            return carrier
    if re.fullmatch(r"9\d{21}", compact):
        return "usps"
    if re.fullmatch(r"\d{12}", compact):
        return "fedex"
    return ""


def load_rows():
    rows = []
    for label_path in sorted(ROOT.glob("sample-photos*/labels.csv")):
        folder = label_path.parent
        with label_path.open(newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                image_path = folder / row["filename"]
                if not image_path.exists():
                    raise FileNotFoundError(f"Missing labeled image: {image_path}")
                rows.append(
                    {
                        "image": image_path,
                        "carrier": row["carrier"].strip().lower(),
                        "quality": row.get("quality", "clean").strip().lower(),
                        "note": row.get("note", "").strip(),
                    }
                )
    if not rows:
        raise SystemExit("No labels.csv files found under sample-photos*.")
    return rows


def crop_label(image):
    image = image.convert("RGB")
    small = image.copy()
    small.thumbnail((600, 600), Image.Resampling.LANCZOS)

    pixels = np.asarray(small, dtype=np.float32) / 255.0
    max_channel = pixels.max(axis=2)
    min_channel = pixels.min(axis=2)
    saturation = (max_channel - min_channel) / (max_channel + 1e-6)
    luminance = (
        0.299 * pixels[:, :, 0]
        + 0.587 * pixels[:, :, 1]
        + 0.114 * pixels[:, :, 2]
    )
    mask = (luminance > 0.56) & (saturation < 0.34)
    mask = ndimage.binary_opening(mask, structure=np.ones((3, 3)))
    mask = ndimage.binary_closing(mask, structure=np.ones((5, 5)))

    labeled, count = ndimage.label(mask)
    if count == 0:
        return image

    height, width = mask.shape
    best = None
    for index, slices in enumerate(ndimage.find_objects(labeled), start=1):
        if slices is None:
            continue
        ys, xs = slices
        area = int((labeled[slices] == index).sum())
        box_width = xs.stop - xs.start
        box_height = ys.stop - ys.start
        fraction = area / (height * width)
        aspect = box_width / max(1, box_height)
        score = float(area)
        if 0.18 < aspect < 2.9:
            score *= 1.35
        if fraction > 0.70:
            score *= 0.25
        if best is None or score > best[0]:
            best = (score, area, xs.start, ys.start, xs.stop, ys.stop)

    if best is None or best[1] < 250:
        return image

    _, _, x1, y1, x2, y2 = best
    scale_x = image.width / small.width
    scale_y = image.height / small.height
    padding = 0.05 * max((x2 - x1) * scale_x, (y2 - y1) * scale_y)
    box = (
        max(0, int(x1 * scale_x - padding)),
        max(0, int(y1 * scale_y - padding)),
        min(image.width, int(x2 * scale_x + padding)),
        min(image.height, int(y2 * scale_y + padding)),
    )
    if (box[2] - box[0]) * (box[3] - box[1]) < 0.015 * image.width * image.height:
        return image
    return image.crop(box)


def hog(gray, cells=8, bins=9):
    image = np.asarray(gray, dtype=np.float32) / 255.0
    gx = np.zeros_like(image)
    gy = np.zeros_like(image)
    gx[:, 1:-1] = image[:, 2:] - image[:, :-2]
    gy[1:-1, :] = image[2:, :] - image[:-2, :]
    magnitude = np.sqrt(gx * gx + gy * gy)
    angle = (np.arctan2(gy, gx) + np.pi) * (bins / (2 * np.pi))
    height, width = image.shape
    cell_height = height // cells
    cell_width = width // cells
    features = []
    for cell_y in range(cells):
        for cell_x in range(cells):
            cell_magnitude = magnitude[
                cell_y * cell_height : (cell_y + 1) * cell_height,
                cell_x * cell_width : (cell_x + 1) * cell_width,
            ].ravel()
            cell_angle = (
                angle[
                    cell_y * cell_height : (cell_y + 1) * cell_height,
                    cell_x * cell_width : (cell_x + 1) * cell_width,
                ].astype(np.int32).ravel()
                % bins
            )
            histogram = np.bincount(cell_angle, weights=cell_magnitude, minlength=bins).astype(np.float32)
            features.append(histogram / (np.linalg.norm(histogram) + 1e-6))
    return np.concatenate(features)


def image_features(path):
    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    image = crop_label(image)
    image.thumbnail((64, 64), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (64, 64), (255, 255, 255))
    canvas.paste(image, ((64 - image.width) // 2, (64 - image.height) // 2))

    gray = canvas.convert("L")
    gray_values = np.asarray(gray, dtype=np.float32) / 255.0
    ink = 1.0 - gray_values
    low_gray = np.asarray(gray.resize((32, 32), Image.Resampling.BILINEAR), dtype=np.float32).reshape(-1) / 255.0
    low_rgb = np.asarray(canvas.resize((16, 16), Image.Resampling.BILINEAR), dtype=np.float32).reshape(-1) / 255.0
    return np.concatenate(
        [
            low_gray,
            low_rgb,
            hog(gray),
            ink.mean(axis=0),
            ink.mean(axis=1),
            ink.std(axis=0),
            ink.std(axis=1),
        ]
    )


def barcode_carrier(path):
    image = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    image.thumbnail((2200, 2200), Image.Resampling.LANCZOS)
    results = zxingcpp.read_barcodes(
        image,
        try_rotate=True,
        try_downscale=True,
        try_invert=True,
        text_mode=zxingcpp.TextMode.HRI,
    )
    carriers = [carrier_from_barcode(result.text) for result in results]
    carriers = [carrier for carrier in carriers if carrier]
    if "fedex" in carriers:
        return "fedex"
    return carriers[0] if carriers else ""


def export_model(pipeline, labels, counts, visual_accuracy, stacked_accuracy):
    scaler = pipeline.named_steps["standardscaler"]
    classifier = pipeline.named_steps["logisticregression"]
    payload = {
        "version": 2,
        "kind": "barcode-plus-logistic-visual-fallback",
        "featureSize": int(classifier.coef_.shape[1]),
        "labels": [str(label) for label in classifier.classes_],
        "counts": counts,
        "visualCrossValidationAccuracy": round(float(visual_accuracy), 6),
        "stackedCrossValidationAccuracy": round(float(stacked_accuracy), 6),
        "scalerMean": [round(float(value), 8) for value in scaler.mean_],
        "scalerScale": [round(float(value), 8) for value in scaler.scale_],
        "coefficients": [
            [round(float(value), 8) for value in row]
            for row in classifier.coef_
        ],
        "intercepts": [round(float(value), 8) for value in classifier.intercept_],
        "notes": [
            "Use barcode carrier when available.",
            "Use logistic visual classifier as fallback when barcode carrier is missing.",
            "Half FedEx / half USPS labels are labeled fedex in labels.csv.",
        ],
    }
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def main():
    rows = load_rows()
    labels = np.array([row["carrier"] for row in rows])
    features = np.vstack([image_features(row["image"]) for row in rows])
    barcode_predictions = np.array([barcode_carrier(row["image"]) for row in rows])
    counts = dict(sorted(Counter(labels).items()))

    pipeline = make_pipeline(
        StandardScaler(),
        LogisticRegression(C=0.3, max_iter=3000),
    )
    cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=7)
    visual_predictions = cross_val_predict(pipeline, features, labels, cv=cv, n_jobs=1)
    stacked_predictions = np.array(
        [
            barcode if barcode else visual
            for barcode, visual in zip(barcode_predictions, visual_predictions)
        ]
    )
    visual_accuracy = accuracy_score(labels, visual_predictions)
    barcode_accuracy = accuracy_score(labels, barcode_predictions)
    stacked_accuracy = accuracy_score(labels, stacked_predictions)

    pipeline.fit(features, labels)
    export_model(pipeline, labels, counts, visual_accuracy, stacked_accuracy)

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with REPORT_PATH.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "filename",
            "expected",
            "barcode_prediction",
            "visual_prediction",
            "stacked_prediction",
            "correct",
            "quality",
            "note",
        ])
        for row, expected, barcode, visual, stacked in zip(
            rows, labels, barcode_predictions, visual_predictions, stacked_predictions
        ):
            writer.writerow([
                str(row["image"].relative_to(ROOT)).replace("\\", "/"),
                expected,
                barcode,
                visual,
                stacked,
                str(expected == stacked).lower(),
                row["quality"],
                row["note"],
            ])

    summary = {
        "totalImages": len(rows),
        "carrierCounts": counts,
        "barcodeAccuracy": round(float(barcode_accuracy), 6),
        "visualCrossValidationAccuracy": round(float(visual_accuracy), 6),
        "stackedCrossValidationAccuracy": round(float(stacked_accuracy), 6),
        "stackedCorrect": int((labels == stacked_predictions).sum()),
        "stackedTotal": len(rows),
        "targetAccuracy": 0.95,
        "targetMet": bool(stacked_accuracy >= 0.95),
        "mixedFedexUspsAsFedex": sum(
            1 for row in rows if row["note"] == "mixed_fedex_usps_as_fedex"
        ),
    }
    SUMMARY_PATH.write_text(json.dumps(summary, indent=2), encoding="utf-8")

    print(f"trained {len(rows)} images across {len(counts)} carriers")
    print(f"barcode accuracy: {barcode_accuracy:.2%}")
    print(f"visual CV accuracy: {visual_accuracy:.2%}")
    print(f"stacked CV accuracy: {stacked_accuracy:.2%}")
    print(f"target met: {summary['targetMet']}")
    print(f"model: {MODEL_PATH}")
    print(f"report: {REPORT_PATH}")
    print(f"summary: {SUMMARY_PATH}")


if __name__ == "__main__":
    main()
