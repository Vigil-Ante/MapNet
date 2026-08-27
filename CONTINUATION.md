# MapNet Continuation Handoff

This file is for the next development agent working on MapNet. Read it before changing release, signing, Wi-Fi scanning, or update behavior.

## Repository state

- Repository: <https://github.com/Vigil-Ante/MapNet>
- Default branch: `main`
- Current release: `v0.2.5`
- Current signed APK: <https://github.com/Vigil-Ante/MapNet/releases/download/v0.2.5/MapNet-v0.2.5.apk>
- Package/application ID: `com.mapnet`
- Minimum Android version: API 26 (Android 8.0)

`v0.2.2` introduced the network diagnostics tool and the required `ACCESS_NETWORK_STATE` permission, fixing the launch crash present in `v0.2.1`. `v0.2.3` added search and deletion. The current connection flow uses Android's Wi-Fi Network Request prompt directly rather than adding a saved network first, and binds MapNet to the approved connection while the app remains open.

## Implemented behavior

- Wi-Fi survey observations are saved locally with BSSID-level history.
- The visible survey list, map, and summary collapse visible networks by Wi-Fi name (SSID), preventing duplicate entries across scans. Hidden/unavailable SSIDs remain separate by BSSID.
- The Survey list can be searched by Wi-Fi name or BSSID.
- AP details start Android's Wi-Fi Network Request prompt for supported open/personal WPA networks on Android 10 and newer. This connects MapNet directly but does not add a device-wide saved network; MapNet binds to the approved connection while the app remains open. Enterprise, legacy, hidden, and older Android configurations open Android Wi-Fi Settings instead.
- Deleting a visible AP removes every matching visible network record and its local BSSID observation history. It is not a permanent blocklist: a later scan can rediscover it.
- The **Tools** tab displays current Wi-Fi SSID/BSSID, IPv4 addresses, gateway, and DNS; offers Ping and traceroute; and maps locally reachable devices on a private IPv4 Wi-Fi subnet (up to 510 hosts). Discovery combines one ICMP request per host with local ARP records and must not claim to find devices blocked by firewall or guest/client isolation.
- The **Continuous scan** button runs while the app is open:
  - a normal scan request is made about every 30 seconds;
  - if Android rejects it (often due to Wi-Fi scan throttling), MapNet retries every 5 seconds until one is accepted;
  - only fresh scan results are persisted, preventing throttled/stale results from creating false new observations.
- The app’s **Update** action checks the latest GitHub Release manifest, verifies the downloaded APK’s SHA-256 and signing certificate, then delegates installation approval to Android.
- The **Map** tab uses Google Maps and shows grouped, historical *phone survey locations*, not alleged AP transmitter locations. A map marker summarizes every BSSID heard in one scan. The optional circle is the Android-reported location-accuracy radius. Room database version 2 records location accuracy, provider, and location timestamp for new observations; migration `MIGRATION_1_2` preserves existing observations, whose accuracy remains unknown.

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

## Google Maps configuration

The Google Maps Android key is intentionally not in Git. The project applies the Google Maps Secrets Gradle Plugin and reads `MAPS_API_KEY` from ignored `secrets.properties` (copy `secrets.properties.example`). `local.defaults.properties` supplies only `MAPS_API_KEY_NOT_CONFIGURED`, allowing compile-only source checkouts without exposing a functional key.

Before device testing or release builds, enable **Maps SDK for Android** in a billed Google Cloud project. Restrict the key to Android package `com.mapnet` and add the SHA-1 fingerprints for every signing certificate used to install MapNet (at least the persistent release key; add debug only for debug-device testing). Do not add the key to `local.defaults.properties`, `update.properties`, GitHub source, or an APK release secret without Android application restrictions.

The release workflow optionally reads a repository Actions secret named `MAPNET_GOOGLE_MAPS_API_KEY`. When present, it writes the value to an ignored `secrets.properties` file only on the GitHub runner. When absent, the release still publishes with the `MAPS_API_KEY_NOT_CONFIGURED` placeholder and the in-app map remains disabled until a later release is built with a restricted key.

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

1. Test the direct Android Wi-Fi Network Request confirmation flow on a physical Android device, along with local-network mapping on a private Wi-Fi subnet, the search list, deletion/re-scan behavior, and the in-app update flow.
2. Add device-level/instrumented tests where a physical device or emulator is available.
3. Add labelled AP position estimates only after collecting at least three distinct, high-quality survey positions per BSSID. Any estimate must remain clearly labelled as inferred rather than measured. Avoid recreating the former relative/fan-out marker behavior.
4. Evaluate Android’s long-term replacement options for deprecated active Wi-Fi scan APIs before targeting newer platform changes.
