# Parcel ReLabel v7 Trained Model

V7 preserves the v6 barcode, OCR, tracking-format, QR, 4 x 6, accessibility, and Android print workflows. It adds a locally trained visual carrier model built from the new `sample-photos2` dataset and the previously reviewed carrier examples.

## Dataset curation

- Raw source photos remain unchanged in `../../sample-photos2/`.
- `scripts/curate-sample-photos.py` rejects trashed, overexposed, underexposed, blurry, exact-duplicate, and same-parcel duplicate samples.
- `scripts/propagate-sequence-labels.py` labels an undecoded image only when nearby decoded barcode labels agree on both sides within the configured capture window.
- Curation manifests, labels, and contact sheets are in `../../sample-photos2/curation-v7/`.

## Model behavior

- `scripts/train-carrier-model-v7.py` trains a rotation-aware 160-tree carrier classifier.
- The packaged model is `android-app/app/src/main/assets/carrier_model_v7.bin`.
- Held-out precision is 93.7% at the app's 0.85 confidence gate.
- The model only breaks ties when OCR produces candidates from more than one carrier. It cannot override an unambiguous barcode or tracking rule.

## Build

```powershell
.\scripts\build-android.ps1
```

The APK is written to `dist/ParcelReLabel.apk`.

The previous v6 controller/regression app remains in `../v6-current/`.
