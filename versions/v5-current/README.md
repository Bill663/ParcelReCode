# Parcel ReLabel v5 Logic Check

This is the current logic-check and bug-fix version.

Kept:

- Android app source in `android-app/`
- Embedded Open Camera runtime module
- OCR/barcode tracking recognition
- 4 x 6 two-QR workflow
- Android print handoff
- Large text mode
- Clear-all data action in both 2 x 2 and 4 x 6 modes
- Scan-session guard so stale OCR results cannot repopulate the app after a clear or newer scan
- One-click build script in `scripts/build-android.ps1`

Removed from this active version:

- Unused `carrier_model.json` asset
- Build-time carrier model retraining
- Python training dependency path
- Open Camera test source trees and test-only dependencies
- Open Camera locale resources outside English and Simplified Chinese
- Icon design option sheets
- Local sample-photo and duration-rally bulk

The previous lightweight fallback remains in `..\v4-current\`; older audit assets remain in `..\v3-current\`.
