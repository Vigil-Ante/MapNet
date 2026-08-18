# MapNet

MapNet is a local-first Android Wi-Fi survey MVP. It retains each BSSID as a local historical observation while showing one current survey entry per visible Wi-Fi name, and classifies Android scan capabilities into an understandable security state.

## Included MVP workflow

1. Run a Wi-Fi survey (Android requires location and nearby-Wi-Fi permission).
2. Save the observed APs and raw capabilities to a local Room database.
3. Flag traditional open networks with an accessible `⚠ OPEN` label.
4. Filter the list and the survey map together by security type.
5. Inspect an AP’s normalized security details and observations count.
6. Request Android-approved connections to open and personal WPA networks, or open Wi-Fi Settings for networks that require enterprise/legacy configuration.
7. Inspect the active Wi-Fi connection’s IP details, run Ping, and run a local traceroute from the Tools tab.

`OWE / Enhanced Open` is deliberately shown as passwordless **and encrypted**, rather than as a traditional open network.

## Build

Open this directory in Android Studio, select a device running Android 8.0 (API 26) or newer, and run the `app` configuration. The project uses Jetpack Compose, Room, and Android Wi-Fi scan APIs.

Gradle 8.7 and Temurin JDK 17 are supplied locally in `.tools`. For a command-line build, install/configure an Android SDK (set `ANDROID_HOME`, or put `sdk.dir=<SDK path>` in an untracked `local.properties`) and run:

```powershell
.\mapnet-gradle.bat testDebugUnitTest
```

## Current boundary

The map is a local coordinate plot of observations, with no external map provider or API key. It uses the exact selected security filter, including `Open`, so field data can be reviewed without transmitting survey records to a third party.

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

4. Commit the generated Gradle Wrapper and the workflow in `.github/workflows/release.yml`, then push a tag such as `v0.2.0`.

The workflow builds a signed APK, assigns an increasing CI version code, creates the checksum manifest, and publishes both to the tagged GitHub Release. The app is configured at build time to retrieve `https://github.com/OWNER/REPOSITORY/releases/latest/download/mapnet-update.json` for its own repository.

For a locally built test APK, copy `update.properties.example` to an ignored `update.properties`, replace `OWNER/REPOSITORY`, and build/install that APK once. Keep the repository public for this lightweight updater: private release downloads need authenticated delivery and an access token must never be embedded in an APK.
