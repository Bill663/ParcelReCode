# Parcel ReLabel v8 Refined

V8 is a refinement release for the full warehouse workflow. It preserves V7's barcode, OCR, confidence-gated carrier model, carrier-specific payload rules, camera flash behavior, accessibility mode, 4 x 6 pairing, confirmation, and Android print handoff.

## V8 changes

- Compact phone-first screen with round-corner margins and a persistent print button.
- Clearer single-label confirmation and two compact 4 x 6 scan slots.
- Faster, debounced QR generation while tracking fields are edited.
- Camera result recovery if Android recreates the activity during capture.
- Failed-sample storage runs off the scan path, keeps at most 40 resized images, and rotates diagnostic logs.
- English and Simplified Chinese layouts, plus large-text mode, remain available in Settings.

## Recognition model

- The packaged model remains `android-app/app/src/main/assets/carrier_model_v7.bin` because V8 refines the app around the trained V7 model rather than retraining it.
- The visual model remains confidence-gated and cannot override an unambiguous barcode or tracking-format result.
- USPS and FedEx payload transformations remain covered by unit tests.

## Build

```powershell
.\scripts\build-android.ps1
```

The command runs unit tests, builds the Android app, and writes `dist/ParcelReLabel.apk`.

The complete V7 trained-model release remains available in `../v7-current/` as the immediate fallback.
