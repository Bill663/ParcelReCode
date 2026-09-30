# Parcel ReLabel v4 Lightweight

This is the current lightweight version.

Kept:

- Android app source in `android-app/`
- Embedded Open Camera runtime module
- OCR/barcode tracking recognition
- 4 x 6 two-QR workflow
- Android print handoff
- Large text mode
- One-click build script in `scripts/build-android.ps1`

Removed from this active version:

- Unused `carrier_model.json` asset
- Build-time carrier model retraining
- Python training dependency path
- Open Camera test source trees and test-only dependencies
- Open Camera locale resources outside English and Simplified Chinese
- Icon design option sheets
- Local sample-photo and duration-rally bulk

Fallback and audit assets remain in `..\v3-current\`.
