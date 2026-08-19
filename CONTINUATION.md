# MapNet Continuation Handoff

This file is for the next development agent working on MapNet. Read it before changing release, signing, Wi-Fi scanning, or update behavior.

## Repository state

- Repository: <https://github.com/Vigil-Ante/MapNet>
- Default branch: `main`
- Current release: `v0.2.5` (being published from the current `main` commit)
- Current signed APK: <https://github.com/Vigil-Ante/MapNet/releases/download/v0.2.5/MapNet-v0.2.5.apk>
- Package/application ID: `com.mapnet`
- Minimum Android version: API 26 (Android 8.0)

`v0.2.2` introduced the network diagnostics tool and the required `ACCESS_NETWORK_STATE` permission, fixing the launch crash present in `v0.2.1`. `v0.2.3` replaces the unreliable Wi-Fi Suggestion approval flow with Android's explicit saved-network confirmation screen, and adds search and deletion. `v0.2.4` follows an `ADD_WIFI_RESULT_ALREADY_EXISTS` result with Android's user-approved Wi-Fi Network Request prompt, so a saved AP can be connected for MapNet rather than ending at the informational result. `v0.2.5` adds the required `CHANGE_NETWORK_STATE` permission for that request and MapNet's process network binding.

## Implemented behavior

- Wi-Fi survey observations are saved locally with BSSID-level history.
- The visible survey list, map, and summary collapse visible networks by Wi-Fi name (SSID), preventing duplicate entries across scans. Hidden/unavailable SSIDs remain separate by BSSID.
- The Survey list can be searched by Wi-Fi name or BSSID.
- AP details open Android's explicit `ACTION_WIFI_ADD_NETWORKS` confirmation screen for supported open/personal WPA networks on Android 11 and newer. This adds the network as a normal user-managed saved Wi-Fi network. If Android reports the requested configuration already exists, MapNet starts an Android-approved Wi-Fi Network Request and binds MapNet to the connected network while the app remains open. Android 10, enterprise, legacy, and hidden configurations open Android Wi-Fi Settings instead.
- Deleting a visible AP removes every matching visible network record and its local BSSID observation history. It is not a permanent blocklist: a later scan can rediscover it.
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

GitHub Actions runs: <https://github.com/Vigil-Ante/MapNet/actions>

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

1. Test v0.2.5 on a physical Android device, especially the Android system confirmation screens for a new network and an already-saved network, the search list, deletion/re-scan behavior, and the in-app update flow.
2. Add device-level/instrumented tests where a physical device or emulator is available.
3. Consider improving the map view only after confirming the current map data and location permissions work on-device.
4. Evaluate Android’s long-term replacement options for deprecated active Wi-Fi scan APIs before targeting newer platform changes.
