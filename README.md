# MapNet

MapNet is a local-first Android Wi-Fi survey MVP. It retains each BSSID as a local historical observation while showing one current survey entry per visible Wi-Fi name, and classifies Android scan capabilities into an understandable security state.

## Included MVP workflow

1. Run a Wi-Fi survey, or enable **Continuous scan** to request the next scan automatically while MapNet is open (Android requires location and nearby-Wi-Fi permission). Search the visible list by Wi-Fi name or BSSID.
2. Save the observed APs and raw capabilities to a local Room database.
3. Flag traditional open networks with an accessible `⚠ OPEN` label.
4. Filter the list and the Google Maps survey view together by security type.
5. Inspect an AP’s normalized security details and observations count, or delete the local network record and its local history. A later scan can rediscover a deleted network.
6. On Android 10 or newer, request Android's explicit confirmation to connect MapNet to supported open and personal WPA networks without saving them as a device-wide Wi-Fi network. Enterprise, legacy, hidden, and older Android configurations open Wi-Fi Settings instead.
7. Open Tools for a persistent, devices-first inventory of the connected private Wi-Fi subnet. Search and filter known devices, inspect locally discovered identity and service details, save friendly names/types/notes, review online/offline history, and run per-device diagnostics.

Continuous scan requests a normal scan roughly every 30 seconds. When Android declines a request because of its system-level Wi-Fi scan throttle, MapNet retries every five seconds until Android accepts one; Android does not expose an exact throttle-expiry notification.

`OWE / Enhanced Open` is deliberately shown as passwordless **and encrypted**, rather than as a traditional open network.

## Local device inventory

**Scan devices** sends one ICMP echo request to each usable address on the currently connected private IPv4 Wi-Fi subnet (up to 510 addresses), then combines responses with the local ARP table, configured gateway, reverse DNS, mDNS/DNS-SD, SSDP/UPnP, NetBIOS, and a bundled offline MAC-vendor seed. It does not scan the public internet, use cloud fingerprinting, or require a Google Maps API key. Devices that reject discovery traffic or are separated by guest-network/client isolation may not be detected.

Successful scans are stored per network. MapNet shows cached results immediately and refreshes a new or stale network when Tools opens. A device is marked offline only after a later scan of that same network completes successfully; a canceled or failed scan does not change saved statuses.

Tap a device for identity, first/last-seen times, discovery sources, services, open ports, and a status timeline. Available actions are Ping, Traceroute, a bounded TCP port scan, copying IP/MAC details, and opening a locally advertised or detected web interface. **Forget device** removes only MapNet's saved record and does not disconnect it. Router-specific Block and Pause Internet controls are intentionally not presented because Android cannot provide them without a supported router administration API.

## Build

Open this directory in Android Studio, select a device running Android 8.0 (API 26) or newer, and run the `app` configuration. The project uses Jetpack Compose, Room, and Android Wi-Fi scan APIs.

Gradle 8.7 and Temurin JDK 17 are supplied locally in `.tools`. For a command-line build, install/configure an Android SDK (set `ANDROID_HOME`, or put `sdk.dir=<SDK path>` in an untracked `local.properties`) and run:

```powershell
.\mapnet-gradle.bat testDebugUnitTest lintDebug assembleDebug
```

## Google Maps survey view

MapNet's map displays **survey positions**: where the phone heard Wi-Fi radios. A Wi-Fi scan cannot determine a transmitter's physical position, so it never represents the displayed pin as an AP location. Each pin summarizes the BSSIDs observed in one scan; its blue circle is the Android location provider's reported location-accuracy radius. Historical records created before this version remain visible but show that their accuracy is unavailable.

The map uses Google Maps. Before running it on a phone:

1. Create or select a Google Cloud project with billing enabled, then enable **Maps SDK for Android**.
2. Create an Android-restricted API key for package `com.mapnet`, adding the SHA-1 certificate fingerprints for the debug and/or persistent release signing key that will install the app.
3. Copy `secrets.properties.example` to ignored `secrets.properties` and set `MAPS_API_KEY` to that key. Never commit this file or an unrestricted key.

The Gradle Secrets Plugin supplies this value to the Android manifest. `local.defaults.properties` carries only a non-working placeholder so a source-only checkout can compile without exposing a key.

The app's **Settings** tab reports whether the installed APK includes a key, whether Google Play services are available, and the exact package/SHA-1 restriction values for that APK. It also has a temporary key-entry field that copies `MAPS_API_KEY=...` for `secrets.properties`. Google Maps reads this setting from the manifest at build time, so entering a key on the phone cannot alter the installed APK; rebuild and install after changing it.

## GitHub Release updates

MapNet now has an **Update** button. It checks a small `mapnet-update.json` asset in the latest public GitHub Release, downloads the indicated APK over HTTPS, verifies its SHA-256 checksum and signing certificate, and opens Android's normal installer. Android always requires you to approve the installation; this application does not silently install software.

The first release APK must still be installed manually. Once that signed release is on the phone, later releases can be installed from the Update button without transferring an APK.

### One-time GitHub setup

1. Create a GitHub repository and push this project to it.
2. Create one persistent release key and keep its `.jks` file and passwords safe. Do **not** commit the key:

   ```powershell
   keytool -genkeypair -v -keystore mapnet-release.jks -alias mapnet -keyalg RSA -keysize 4096 -validity 10000
   ```

3. In the GitHub repository's Actions secrets, add the following values:

   - `MAPNET_SIGNING_KEY_BASE64` — output of `[Convert]::ToBase64String([IO.File]::ReadAllBytes("mapnet-release.jks"))`
   - `MAPNET_RELEASE_STORE_PASSWORD`
   - `MAPNET_RELEASE_KEY_ALIAS`
   - `MAPNET_RELEASE_KEY_PASSWORD`
   - `MAPNET_GOOGLE_MAPS_API_KEY` *(optional)* — the Android-restricted Google Maps key described above. Without it, releases still publish but Google Maps is disabled and the app directs the user to Settings for setup.

4. Commit the generated Gradle Wrapper and the workflow in `.github/workflows/release.yml`, then push a tag such as `v0.2.0`.

The workflow builds a signed APK, assigns an increasing CI version code, creates the checksum manifest, and publishes both to the tagged GitHub Release. The app is configured at build time to retrieve `https://github.com/OWNER/REPOSITORY/releases/latest/download/mapnet-update.json` for its own repository.

For a locally built test APK, copy `update.properties.example` to an ignored `update.properties`, replace `OWNER/REPOSITORY`, and build/install that APK once. Keep the repository public for this lightweight updater: private release downloads need authenticated delivery and an access token must never be embedded in an APK.
