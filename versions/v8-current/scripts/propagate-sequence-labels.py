import csv
import re
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parents[1]
CURATION = WORKSPACE / "sample-photos2" / "curation-v7"
MANIFEST = CURATION / "curation-manifest.csv"


def timestamp(row):
    match = re.search(r"parcel-(\d+)", row["filename"])
    return int(match.group(1)) if match else 0


def nearest_labeled(rows, index, direction, max_gap):
    stop = max(-1, index - max_gap - 1) if direction < 0 else min(len(rows), index + max_gap + 1)
    for other in range(index + direction, stop, direction):
        if rows[other].get("carrier"):
            return rows[other]
    return None


def write_csv(path, rows, fields):
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main():
    with MANIFEST.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames
        rows = list(reader)

    usable = [row for row in rows if row["status"] != "rejected"]
    propagated = 0
    for index, row in enumerate(usable):
        if row["status"] != "review":
            continue
        before = nearest_labeled(usable, index, -1, 40)
        after = nearest_labeled(usable, index, 1, 40)
        if not before or not after or before["carrier"] != after["carrier"]:
            continue
        before_time = timestamp(before)
        after_time = timestamp(after)
        if not before_time or not after_time or after_time - before_time > 15 * 60 * 1000:
            continue
        row.update(
            carrier=before["carrier"],
            status="accepted",
            reason="matching_barcode_neighbors",
            label_source="sequence_between_matching_barcodes",
            confidence="0.99",
        )
        propagated += 1

    accepted = [row for row in rows if row["status"] == "accepted"]
    rejected = [row for row in rows if row["status"] == "rejected"]
    review = [row for row in rows if row["status"] == "review"]
    write_csv(CURATION / "curation-manifest.csv", rows, fields)
    write_csv(CURATION / "accepted-labels.csv", accepted, fields)
    write_csv(CURATION / "rejected-samples.csv", rejected, fields)
    write_csv(CURATION / "needs-review.csv", review, fields)
    write_csv(
        CURATION / "labels.csv",
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
    print(f"Sequence labels added: {propagated}")
    print(f"Accepted: {len(accepted)} {dict(sorted(Counter(row['carrier'] for row in accepted).items()))}")
    print(f"Rejected: {len(rejected)}")
    print(f"Needs review: {len(review)}")


if __name__ == "__main__":
    main()
