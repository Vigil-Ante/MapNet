# MapNet Continuation Handoff

This file is for the next development agent working on MapNet. Read it before changing release, signing, Wi-Fi scanning, or update behavior.

## Repository state

- Repository: <https://github.com/Vigil-Ante/MapNet>
- Default branch: `main`
- Current release: `v0.2.2`
- Current release commit: `8e4826324780b5ee22330c06e5df1806c7bc0be7`
- Current signed APK: <https://github.com/Vigil-Ante/MapNet/releases/download/v0.2.2/MapNet-v0.2.2.apk>
- Package/application ID: `com.mapnet`
- Minimum Android version: API 26 (Android 8.0)

`v0.2.2` is the first release that includes the network diagnostics tool and has the required `ACCESS_NETWORK_STATE` permission. It fixes the launch crash present in `v0.2.1`.

## Implemented behavior

- Wi-Fi survey observations are saved locally with BSSID-level history.
- The visible survey list, map, and summary collapse visible networks by Wi-Fi name (SSID), preventing duplicate entries across scans. Hidden/unavailable SSIDs remain separate by BSSID.
- AP details provide a user-approved Android Wi-Fi connection request for supported open/personal WPA networks. Unsupported enterprise, legacy, and hidden configurations open Android Wi-Fi Settings instead.
- The **Tools** tab displays current Wi-Fi SSID/BSSID, IPv4 addresses, gateway, and DNS, and offers Ping and traceroute.
- The **Continuous scan** button runs while the app is open:
  - a normal scan request is made about every 30 seconds;
  - if Android rejects it (often due to Wi-Fi scan throttling), MapNet retries every 5 seconds until one is accepted;
  - only fresh scan results are persisted, preventing throttled/stale results from creating false new observations.
- The app’s **Update** action checks the latest GitHub Release manifest, verifies the downloaded APK’s SHA-256 and signing certificate, then delegates installation approval to Android.

## Android scanning constraints

Android controls Wi-Fi scanning frequency; an app cannot bypass it. `WifiManager.startScan()` can return `false` when the platform throttles scanning, the device is idle, or Wi-Fi hardware fails. The current implementation treats that as a retry condition and does not store stale scan results.

For local testing, compatible Android versions may expose **Developer options → Networking → Wi-Fi scan throttling**. See the official Android documentation: <https://developer.android.com/develop/connectivity/wifi/wifi-scan>.

## Build and validation

The workspace includes Gradle 8.7 and JDK 17 under `.tools`.

```powershell
.\mapnet-gradle.bat testDebugUnitTest assembleDebug --no-daemon --max-workers=1
```

The published release workflow runs the unit tests and builds a signed release APK. The relevant workflow is:

`/.github/workflows/release.yml`

GitHub Actions run for v0.2.2: <https://github.com/Vigil-Ante/MapNet/actions/runs/32186649399>

## Releases and signing

Pushing a tag named `vX.Y.Z` triggers the release workflow. It:

1. Restores the release keystore from GitHub Actions secrets.
2. Runs unit tests and builds a release APK.
3. Produces `MapNet-vX.Y.Z.apk`.
4. Creates `mapnet-update.json` containing version, URL, and SHA-256.
5. Publishes both to a public GitHub Release.

Never commit signing material or passwords. The persistent keystore is intentionally ignored by Git:

- Local keystore: `mapnet-release.jks`
- Windows Credential Manager entry: `MapNetReleaseSigning`
- GitHub Actions secrets: `MAPNET_SIGNING_KEY_BASE64`, `MAPNET_RELEASE_STORE_PASSWORD`, `MAPNET_RELEASE_KEY_ALIAS`, and `MAPNET_RELEASE_KEY_PASSWORD`

All future releases must use this same signing certificate or Android will reject in-app updates.

The updater endpoint is configured in `app/build.gradle.kts`:

`https://github.com/Vigil-Ante/MapNet/releases/latest/download/mapnet-update.json`

## Installation notes

- A signed release can update an earlier signed MapNet release directly.
- A debug-signed APK cannot update to the release-signed APK. To move from debug to release, uninstall the debug app first; this clears its local Room database.
- The updater cannot silently install. Android always shows the installer/permission prompt.

## Suggested next work

1. Test v0.2.2 on a physical Android device, including launch, Tools, continuous scan, and update flow.
2. Add device-level/instrumented tests where a physical device or emulator is available.
3. Consider improving the map view only after confirming the current map data and location permissions work on-device.
4. Evaluate Android’s long-term replacement options for deprecated active Wi-Fi scan APIs before targeting newer platform changes.

