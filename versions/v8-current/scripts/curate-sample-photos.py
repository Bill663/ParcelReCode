import argparse
import csv
import hashlib
import os
import re
import sys
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parents[1]
PACKAGE_PATH = WORKSPACE / ".ml-packages"
sys.path.insert(0, str(PACKAGE_PATH.resolve()))

import numpy as np
import zxingcpp
from PIL import Image, ImageDraw, ImageFont, ImageOps
from scipy import ndimage


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
FIELDS = [
    "filename", "carrier", "tracking", "status", "reason", "label_source",
    "confidence", "width", "height", "mean_luma", "contrast", "highlight_fraction",
    "shadow_fraction", "sharpness", "perceptual_hash", "duplicate_of", "barcode_values",
]


def clean(value):
    return re.sub(r"[^A-Z0-9]", "", (value or "").upper())


def parse_barcode(raw_value):
    compact = clean(raw_value)
    direct_patterns = [
        (r"UUS[A-Z0-9]{16}", "uniuni"),
        (r"GFUS\d{14}", "gofo"),
        (r"SWX\d{18}", "swiftx"),
        (r"SPX[A-Z]{3}\d{12}", "speedx"),
        (r"1LSD[A-Z0-9]{11}", "ontrac"),
    ]
    for pattern, carrier in direct_patterns:
        match = re.search(pattern, compact)
        if match:
            return carrier, match.group(), 1.0

    match = re.search(r"96\d{32}", compact)
    if match:
        machine = match.group()
        return "fedex", machine[-12:], 1.0

    match = re.search(r"420\d{5,9}92(\d{20})", compact)
    if match:
        return "usps", "92" + match.group(1), 1.0

    if re.fullmatch(r"9\d{21}", compact):
        return "usps", compact, 0.98
    if re.fullmatch(r"\d{12}", compact):
        return "fedex", compact, 0.96
    return "", "", 0.0


def perceptual_hash(gray):
    small = np.asarray(gray.resize((32, 32), Image.Resampling.LANCZOS), dtype=np.float32)
    transformed = ndimage.zoom(small, 0.25, order=1)
    threshold = float(np.median(transformed[1:, 1:]))
    bits = transformed >= threshold
    value = 0
    for bit in bits.ravel():
        value = (value << 1) | int(bit)
    return f"{value:016x}"


def analyze_one(path_value):
    path = Path(path_value)
    try:
        raw = path.read_bytes()
        exact_hash = hashlib.sha256(raw).hexdigest()
        with Image.open(path) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
        width, height = image.size
        image.thumbnail((1800, 1800), Image.Resampling.LANCZOS)
        gray = image.convert("L")
        sample = np.asarray(gray.resize((384, 384), Image.Resampling.BILINEAR), dtype=np.float32)
        mean_luma = float(sample.mean())
        contrast = float(sample.std())
        highlight_fraction = float((sample >= 250).mean())
        shadow_fraction = float((sample <= 12).mean())
        sharpness = float(ndimage.laplace(sample).var())

        barcode_image = image.copy()
        values = [
            result.text for result in zxingcpp.read_barcodes(
                barcode_image,
                try_rotate=True,
                try_downscale=True,
                try_invert=True,
                text_mode=zxingcpp.TextMode.HRI,
            )
        ]
        parsed = [parse_barcode(value) for value in values]
        parsed = [item for item in parsed if item[0]]
        parsed.sort(key=lambda item: item[2], reverse=True)
        carrier, tracking, confidence = parsed[0] if parsed else ("", "", 0.0)
        return {
            "filename": path.name,
            "carrier": carrier,
            "tracking": tracking,
            "status": "pending",
            "reason": "",
            "label_source": "barcode" if carrier else "",
            "confidence": f"{confidence:.2f}" if confidence else "",
            "width": width,
            "height": height,
            "mean_luma": f"{mean_luma:.2f}",
            "contrast": f"{contrast:.2f}",
            "highlight_fraction": f"{highlight_fraction:.4f}",
            "shadow_fraction": f"{shadow_fraction:.4f}",
            "sharpness": f"{sharpness:.2f}",
            "perceptual_hash": perceptual_hash(gray),
            "duplicate_of": "",
            "barcode_values": " | ".join(values),
            "exact_hash": exact_hash,
            "error": "",
        }
    except Exception as error:
        return {
            "filename": path.name,
            "status": "rejected",
            "reason": "unreadable_image",
            "error": str(error),
        }


def quality_score(row):
    return (
        float(row.get("sharpness", 0))
        + 0.5 * float(row.get("contrast", 0))
        - 80.0 * float(row.get("highlight_fraction", 0))
        - 40.0 * float(row.get("shadow_fraction", 0))
    )


def apply_filtering(rows):
    exact_seen = {}
    for row in rows:
        if row.get("status") == "rejected":
            continue
        if row["filename"].startswith(".trashed-"):
            row.update(status="rejected", reason="trashed_source")
            continue
        if min(int(row["width"]), int(row["height"])) < 720:
            row.update(status="rejected", reason="too_small")
            continue
        mean = float(row["mean_luma"])
        contrast = float(row["contrast"])
        highlights = float(row["highlight_fraction"])
        shadows = float(row["shadow_fraction"])
        sharpness = float(row["sharpness"])
        if mean > 238 or (highlights > 0.58 and contrast < 36):
            row.update(status="rejected", reason="overexposed")
            continue
        if mean < 28 or (shadows > 0.72 and contrast < 30):
            row.update(status="rejected", reason="underexposed")
            continue
        if sharpness < 18 and contrast < 45:
            row.update(status="rejected", reason="too_blurry")
            continue
        exact_hash = row["exact_hash"]
        if exact_hash in exact_seen:
            row.update(status="rejected", reason="exact_duplicate", duplicate_of=exact_seen[exact_hash])
            continue
        exact_seen[exact_hash] = row["filename"]

    tracking_groups = defaultdict(list)
    for row in rows:
        if row.get("status") != "rejected" and row.get("tracking"):
            tracking_groups[(row["carrier"], row["tracking"])].append(row)
    for group in tracking_groups.values():
        group.sort(key=quality_score, reverse=True)
        keeper = group[0]
        for duplicate in group[1:]:
            duplicate.update(
                status="rejected",
                reason="same_parcel_duplicate",
                duplicate_of=keeper["filename"],
            )

    for row in rows:
        if row.get("status") == "rejected":
            continue
        if row.get("carrier"):
            row.update(status="accepted", reason="high_confidence_barcode_label")
        else:
            row.update(status="review", reason="no_high_confidence_label")


def write_csv(path, rows, fields=FIELDS):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def create_contact_sheets(source, output, rows, prefix, page_size=80):
    output.mkdir(parents=True, exist_ok=True)
    font = ImageFont.load_default()
    for page_index in range(0, len(rows), page_size):
        page_rows = rows[page_index:page_index + page_size]
        sheet = Image.new("RGB", (1600, 2000), "white")
        draw = ImageDraw.Draw(sheet)
        for index, row in enumerate(page_rows):
            column = index % 8
            line = index // 8
            left = column * 200
            top = line * 200
            try:
                with Image.open(source / row["filename"]) as opened:
                    image = ImageOps.exif_transpose(opened).convert("RGB")
                    image.thumbnail((190, 155), Image.Resampling.LANCZOS)
                sheet.paste(image, (left + (200 - image.width) // 2, top))
            except Exception:
                pass
            label = f"{row['filename'][-22:-4]}\n{row.get('carrier') or row.get('reason', '')}"
            draw.multiline_text((left + 4, top + 158), label, fill="black", font=font, spacing=1)
        page_number = page_index // page_size + 1
        sheet.save(output / f"{prefix}-{page_number:03d}.jpg", quality=86)


def main():
    parser = argparse.ArgumentParser(description="Curate and barcode-label parcel photos for v7 training.")
    parser.add_argument("--source", type=Path, default=WORKSPACE / "sample-photos2")
    parser.add_argument("--output", type=Path, default=WORKSPACE / "sample-photos2" / "curation-v7")
    parser.add_argument("--workers", type=int, default=max(1, min(8, (os.cpu_count() or 4) - 1)))
    args = parser.parse_args()
    source = args.source.resolve()
    output = args.output.resolve()
    files = sorted(path for path in source.iterdir() if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)
    if not files:
        raise SystemExit(f"No images found in {source}")

    print(f"Analyzing {len(files)} images with {args.workers} workers", flush=True)
    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        rows = list(executor.map(analyze_one, map(str, files), chunksize=4))
    rows.sort(key=lambda row: row["filename"])
    apply_filtering(rows)

    accepted = [row for row in rows if row.get("status") == "accepted"]
    rejected = [row for row in rows if row.get("status") == "rejected"]
    review = [row for row in rows if row.get("status") == "review"]
    write_csv(output / "curation-manifest.csv", rows)
    write_csv(output / "accepted-labels.csv", accepted)
    write_csv(output / "rejected-samples.csv", rejected)
    write_csv(output / "needs-review.csv", review)
    write_csv(
        output / "labels.csv",
        [
            {
                "filename": row["filename"],
                "carrier": row["carrier"],
                "tracking": row["tracking"],
                "quality": "clean",
                "note": row["label_source"],
            }
            for row in accepted
        ],
        ["filename", "carrier", "tracking", "quality", "note"],
    )
    create_contact_sheets(source, output / "contact-sheets", accepted, "accepted")
    create_contact_sheets(source, output / "contact-sheets", rejected, "rejected")
    create_contact_sheets(source, output / "contact-sheets", review, "review")

    print(f"Accepted: {len(accepted)} {dict(sorted(Counter(row['carrier'] for row in accepted).items()))}")
    print(f"Rejected: {len(rejected)} {dict(sorted(Counter(row['reason'] for row in rejected).items()))}")
    print(f"Needs review: {len(review)}")
    print(f"Manifest: {output / 'curation-manifest.csv'}")


if __name__ == "__main__":
    main()
