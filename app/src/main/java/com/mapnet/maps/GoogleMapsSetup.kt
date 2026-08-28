package com.mapnet.maps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.security.MessageDigest

private const val MAPS_API_KEY_METADATA_NAME = "com.google.android.geo.API_KEY"
private const val UNCONFIGURED_MAPS_API_KEY = "MAPS_API_KEY_NOT_CONFIGURED"

/**
 * The device-visible parts of the Maps SDK setup. A valid key can still be rejected by Google
 * Cloud when its project, billing, API enablement, or Android restriction is incorrect.
 */
data class GoogleMapsSetup(
    val hasApiKey: Boolean,
    val manifestHasApiKey: Boolean,
    val playServicesAvailable: Boolean,
    val applicationId: String,
    val signingCertificateSha1s: List<String>
) {
    val canStartMap: Boolean get() = hasApiKey && manifestHasApiKey && playServicesAvailable
}

fun Context.googleMapsSetup(buildConfigApiKey: String): GoogleMapsSetup {
    val manifestApiKey = runCatching {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString(MAPS_API_KEY_METADATA_NAME)
    }.getOrNull()
    return GoogleMapsSetup(
        hasApiKey = buildConfigApiKey.isConfiguredGoogleMapsKey(),
        manifestHasApiKey = manifestApiKey.isConfiguredGoogleMapsKey(),
        playServicesAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS,
        applicationId = packageName,
        signingCertificateSha1s = signingCertificateSha1s()
    )
}

fun String?.isConfiguredGoogleMapsKey(): Boolean =
    !isNullOrBlank() && this != UNCONFIGURED_MAPS_API_KEY && this != "YOUR_GOOGLE_MAPS_ANDROID_API_KEY"

private fun Context.signingCertificateSha1s(): List<String> = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(
            packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        )
    }
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        packageInfo.signatures.orEmpty()
    }
    signatures.map { signature ->
        MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
    }.distinct()
}.getOrDefault(emptyList())
