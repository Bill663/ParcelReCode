# Parcel ReLabel v6 Controllers

This is the current v6 development version.

Focus:

- Keep the lightweight v5 runtime behavior.
- Split scan/session/print responsibilities out of `MainActivity`.
- Store recognition timing by device for performance follow-up.
- Add a one-click connected-device sample regression runner.
- Add a curated failed-sample workflow before any self-learning work.

Kept:

- Android app source in `android-app/`
- Embedded Open Camera runtime module
- OCR/barcode tracking recognition
- 2 x 2 single-QR workflow
- 4 x 6 two-QR workflow
- Android print handoff
- Large text accessibility mode
- Clear-all data action
- Scan-session guard

Build:

```powershell
.\scripts\build-android.ps1
```

Device regression:

```powershell
.\scripts\run-device-regression.ps1
```

By default the runner uses sample labels already stored on the connected device. It first checks the app's internal files, then the app-specific external storage folder. Use `-SampleDir` only when you want to push a local sample set from the PC. Unknown or failed captures should stay in the failed-sample curation flow until a human confirms the correct tracking output.

The previous v5 logic-check app remains in `..\v5-current\`.
