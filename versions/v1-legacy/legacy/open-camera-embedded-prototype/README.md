# Legacy Parcel ReLabel Embedded Open Camera Prototype

This folder archives the side project that proved the embedded Open Camera approach.

The active main version now lives in the repository root `android-app/` folder. Future work should build on `android-app/`, not this legacy prototype.

## Structure

- `app/` - copied Parcel ReLabel app module, modified for the embedded-camera experiment.
- `open-camera/` - copied Open Camera app module converted to an Android library module.
- `open-camera-source/` - untouched official Open Camera source checkout used as the upstream reference.
- `dist/ParcelReLabel-embedded-opencamera.apk` - debug APK built from this side project.

## Integration Notes

- Parcel ReLabel depends on `:open-camera` as source code, not as a separately installed APK.
- `Take parcel photo` launches `net.sourceforge.opencamera.MainActivity` inside the Parcel ReLabel package.
- The final Android focus during verification was:
  `com.parcelrecode.app/net.sourceforge.opencamera.MainActivity`
- Parcel's main layout was renamed to `parcel_activity_main.xml` so Open Camera can keep its own `activity_main.xml`.
- Parcel's theme was renamed to `ParcelAppTheme` so Open Camera can keep its own `AppTheme`.
- Open Camera first-run and What's New dialogs are suppressed before launch for a warehouse workflow.
- The back camera flash preference is seeded to `flash_on`.

## Historical Build

From this folder, for reference only:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
& 'C:\Users\20102\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' :app:testDebugUnitTest :app:assembleDebug
```

Then copy:

```powershell
Copy-Item .\app\build\outputs\apk\debug\app-debug.apk .\dist\ParcelReLabel-embedded-opencamera.apk -Force
```

## License Note

Open Camera is GPL-licensed. Because this version embeds Open Camera source code into the APK, distribution of this APK should include the corresponding source and GPL license terms. The upstream checkout includes `gpl-3.0.txt` and Open Camera source notes.
