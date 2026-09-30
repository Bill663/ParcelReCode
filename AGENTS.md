# Parcel ReLabel handoff

Read `HANDOFF.md` before changing this project. The active app is
`versions/v8-current`; verify `versions/CURRENT_VERSION.txt` before work.
This is a native Android Java app with embedded Open Camera, not the original
HTML prototype. Preserve old version directories and all original sample photos.

Do not infer recognition accuracy from two sample scans or from carrier-model
confidence. Keep human confirmation and Android's print dialog. Preserve USPS
ZIP payloads and FedEx machine-line transformations; see TrackingRulesTest.

Use the root Windows build helper. On a different OS use the bundled Gradle
wrapper from the active android-app directory with a configured JDK and SDK.
The private transfer signing key is needed for updates to existing devices.
Never publish it or automatically uninstall an app to work around a signature
mismatch. Do not commit private parcel photos or signing material to a public repo.

The old chat is not required: HANDOFF.md records architecture, setup, verification
limits, and outstanding concerns. Inspect current source and connected devices
before relying on historical notes. No new features are authorized just by these notes.
