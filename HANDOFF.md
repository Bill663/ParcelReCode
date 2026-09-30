# Start here on the new computer

Prepared 2026-09-16. Copy/extract this project into a short local path such as
`C:\Projects\ParcelReCode`. Open that folder in your coding assistant and ask it
to read AGENTS.md and this file. Conversation history does not travel with files.

## Setup and build

1. Install Android Studio. In SDK Manager install Android SDK Platform 36,
   Build Tools 36.0.0 and Platform Tools. Accept the SDK licenses.
2. Use JDK 21 (Android Studio's bundled runtime worked on the original machine).
   For a custom location set JAVA_HOME. Set ANDROID_HOME for a custom SDK location.
3. Double-click `Build Android APK.cmd`. This runs tests and builds V8.
   The Gradle wrapper downloads Gradle 9.4.1 when no matching cache exists;
   Android Gradle Plugin is 9.2.0. First build requires internet access.
4. Output: `versions/v8-current/dist/ParcelReLabel.apk`.
   The existing APK can be installed immediately without building or retraining.
5. Enable USB debugging on the phone and approve the new computer's RSA prompt.
   Check `adb devices -l`, then `adb -s SERIAL install -r PATH_TO_APK`.
   Launch `com.parcelrecode.app/.MainActivity` and inspect startup logs.

On macOS/Linux configure JDK 21 and the Android SDK, then run `sh ./gradlew
testDebugUnitTest assembleDebug` in `versions/v8-current/android-app` (one line).
The Windows helper is the previously tested build route; other OS builds have
not been verified. Remove any old local.properties if copying the raw folder;
the prepared archive omits machine-specific local.properties.

## Signing and private data

`transfer-support/signing/parcel-debug.keystore` is a private copy of the existing
development signing key. V8 Gradle uses it by project-relative path so builds on
the new machine can update installed V8 apps. Standard Android debug alias and
passwords apply. Keep this key and the parcel photos private; the archive contains
shipping addresses. The key is excluded from Git but included in the archive.
Do not replace it with the new computer's automatically generated debug key.
Never uninstall the installed app to bypass a signature mismatch without asking.

## Current state

- Active folder: versions/v8-current. Version 8.0.0-v8-refined, versionCode 10.
- App ID: com.parcelrecode.app. Minimum Android API 27, target/compile API 36.
- V7 and earlier folders are historical fallbacks; keep them.
- Root build launcher and versions/CURRENT_VERSION.txt both select V8.
- Last recorded installs: SEUIC CRUISE2 serials 807eb598 and 9d179d99.
  Detect currently attached devices instead of assuming either is present.
- Much of this working tree is untracked. A Git clone alone is NOT a full backup.

## Product requirements and architecture

Warehouse operator photographs a parcel, checks recognized tracking/carrier,
generates a QR, and confirms printing through Android's print service. All
recognition runs locally. In 4 x 6 mode two successive parcels fill top and bottom
QR positions on one label. Single mode is 2 x 2. Keep flash default ON for capture,
not continuous torch. Success status is green; failure is red. Language (English
and Simplified Chinese) and large-text mode are in Settings. Allow corner margins.

Main sources under android-app/app/src/main/java/com/parcelrecode/app:
- MainActivity: views, camera/picker, editable fields, QR and UI state.
- ParcelScanController: image decoding, background recognition and diagnostics.
- ParcelRecognizer: barcode and ML Kit OCR, plus gated visual carrier prediction.
- TrackingRules: supported tracking formats and payload transformations.
- FourBySixWorkflow / ScanSessionController: pairing and stale-result protection.
- ParcelPrintController / LabelPrintAdapter: Android printing and page layout.
- CarrierVisualClassifier: loads assets/carrier_model_v7.bin (160 trees).

Preserve USPS payload `420` + five-digit destination ZIP + tracking, and the
FedEx lower-machine-line rewrite using `0207286700`. Tests document examples.
The V7 visual model is only a tie-breaker at confidence >= 0.85. V8 does not train
online or learn automatically from print actions. Do not claim general 90%/95%
tracking accuracy: historical model cross-validation was about 73.6% overall,
39.9% balanced; gated carrier precision was about 93.7% on 19% coverage.

V8 tightened layout and added QR edit debounce, bulk bitmap writes, pending
camera URI state, background diagnostics, a 40-image failed-sample limit and log
rotation. Failure thumbnails are resized to max side 1600; original photos remain.

## Samples and optional training

All original photo directories and curation artifacts are retained in the archive.
sample-photos2/curation-v7 contains labels/manifests and training reports.
Training scripts are in versions/v8-current/scripts; they still produce the V7
model and refer to versions/v3-current/sample-photos2/labels.csv for old labels.
Retraining is NOT necessary to build or use the app.

For optional training, create a Python environment and install numpy, Pillow,
scipy, scikit-learn and zxing-cpp. See transfer-support/training-requirements.txt.
The old .ml-packages folder and Codex's machine runtime are not portable and are
excluded. Historical training scripts outside V8 may contain old absolute paths;
inspect them before execution. Training can overwrite the packaged model.

## Verification and known follow-up concerns

Historical V8 verification: 25 JVM tests passed; two USPS photos produced correct
ZIP/tracking payloads in roughly 1.4-1.7 seconds on a CRUISE2. Single and paired
flows, Chinese large text, English layout and Android print preview were checked.
No physical print job was submitted. This is not a full dataset accuracy test or
a sustained reliability run. Flash mode was inspected, not optically measured.

Review these before claiming stronger reliability:
- Activity recreation saves pending camera URI/target, but full pair/photo state
  is not comprehensively persisted across process death or language recreation.
- 4 x 6 currently hides single-label fields; verify manual recovery when USPS ZIP
  is missing and when a replacement scan fails or fields become invalid.
- QR edit debounce should prevent printing stale payloads during pending edits.
- Print handoff currently marks the pair printed before the user submits the
  system dialog; verify cancel/retry behavior before changing that contract.
- Newly added diagnostics retention and shutdown races lack focused test coverage.

These are review concerns from source inspection, not completed fixes. Keep the
user's next request in scope. Transfer preparation changes build portability and
signing configuration only; it does not change application behavior.

## Archive

`transfer/ParcelReCode-transfer.tar` is generated by
`transfer-support/pack-project.ps1`. It retains Git history, all versions, APKs,
sample photos, models and this handoff. It excludes generated build/Gradle caches,
IDE state, Python packages/bytecode, local.properties, and the transfer directory
itself. Original files are not deleted. A SHA-256 sidecar verifies transport.
Extract into an empty directory with `tar -xf ParcelReCode-transfer.tar`.
