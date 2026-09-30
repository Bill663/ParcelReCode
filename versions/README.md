# Parcel ReLabel Versions

Current development path:

```text
versions\v8-current\
```

Fallback paths:

```text
versions\v2-legacy\LegacyVersion2\
versions\v1-legacy\legacy\
versions\v5-current\
versions\v4-current\
versions\v3-current\
versions\v6-current\
versions\v7-current\
```

## Rules

- New feature work starts from the current version folder.
- New major project states should get a new numbered folder: `v4-current`, `v5-current`, `v6-current`, and so on.
- When a new version becomes the current stable path, rename the old current folder to `vN-legacy`.
- Keep each version's APK output inside that version's `dist\` folder.
- Do not delete old version folders; they are kept for rollback.

## Current Folder Contents

`v8-current` contains the active refined Android app source, V7 trained model, regression tooling, and one-click build script.

`v7-current` is preserved as the trained-model fallback before v8.

`v6-current` is preserved as the controller/regression fallback before v7.

`v5-current` is preserved as the latest logic-check fallback path before v6.

`v4-current` is preserved as the latest lightweight fallback path before v5.

`v3-current` is preserved as the latest full-feature fallback path. It keeps the sample photos, training scripts, rally scripts, and the previous APK output.
