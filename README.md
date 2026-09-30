# Parcel ReLabel

Moving computers or continuing in a new ChatGPT session? Read [HANDOFF.md](HANDOFF.md)
first. It includes setup, signing continuity, architecture, and verification limits.

This project is organized by numbered versions so new work can move forward while older working builds stay available as fallback paths.

## Current Version

Current development version: `versions/v8-current/`

Goal for v8: Refine the full warehouse workflow with a compact phone-first interface, smoother QR generation, safer long-run diagnostics, and stronger camera/print state handling while preserving V7 recognition behavior.

Build it from the project root by double-clicking:

```text
Build Android APK.cmd
```

Or run:

```powershell
.\versions\v8-current\scripts\build-android.ps1
```

The current APK is written to:

```text
versions\v8-current\dist\ParcelReLabel.apk
```

## Version Fallbacks

- `versions/v8-current/` - current refined app.
- `versions/v7-current/` - trained-model fallback before v8.
- `versions/v6-current/` - controller/regression fallback before v7.
- `versions/v5-current/` - latest logic-check fallback before v6.
- `versions/v4-current/` - latest lightweight fallback before v5.
- `versions/v3-current/` - latest full-feature fallback. The folder name is still `v3-current` because Windows denied the rename, but it is now treated as the v3 fallback path.
- `versions/v2-legacy/LegacyVersion2/` - legacy device-base APK fallback.
- `versions/v1-legacy/legacy/` - earliest prototypes and old external Open Camera materials.

If v8 breaks, use `versions/v7-current/` as the immediate fallback.

## Versioning Rule

Create future versions as sibling folders under `versions/`:

```text
versions\v6-current\
versions\v7-current\
versions\v8-current\
```

When a new version becomes stable:

1. Keep the previous current folder available as the fallback path.
2. Keep each version's working APK inside that version's `dist\` folder.
3. Update `versions\CURRENT_VERSION.txt`.
4. Update this README and `versions\README.md`.
5. Update `Build Android APK.cmd` to point at the new current version.

Do not delete old version folders. They are the fallback path.

## Current Version Layout

Inside `versions/v8-current/`:

- `android-app/` - Android application source and embedded Open Camera module.
- `scripts/build-android.ps1` - one-command build/test/APK helper.
- `dist/` - APK output for this version.

V8 retains the V7 trained carrier model. V7 curation output remains in `sample-photos2/curation-v7/`; older training/sample/rally assets remain in `versions/v3-current/` for audit and fallback use.
