package com.mapnet.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mapnet.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String
)

sealed interface UpdateCheckResult {
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult
    data object NoUpdate : UpdateCheckResult
    data object NotConfigured : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

sealed interface UpdateDownloadResult {
    data class Ready(val apk: File) : UpdateDownloadResult
    data class Failed(val message: String) : UpdateDownloadResult
}

enum class InstallRequestResult { STARTED, NEEDS_PERMISSION, FAILED }

/** Handles public release assets only. Authentication tokens are never stored in the APK. */
class AppUpdateRepository(private val context: Context) {
    private val packageManager get() = context.packageManager

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim()
        if (manifestUrl.isBlank()) return@withContext UpdateCheckResult.NotConfigured

        runCatching {
            val body = openHttps(manifestUrl).useConnection { connection ->
                connection.inputStream.bufferedReader().use { it.readText() }
            }
            val json = JSONObject(body)
            val manifest = UpdateManifest(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256").lowercase()
            )
            require(manifest.versionCode > 0) { "The update version code is invalid." }
            require(manifest.sha256.matches(Regex("[0-9a-f]{64}"))) { "The update checksum is invalid." }
            require(URL(manifest.apkUrl).protocol == "https") { "Updates must be served over HTTPS." }

            if (manifest.versionCode > installedVersionCode()) {
                UpdateCheckResult.Available(manifest)
            } else {
                UpdateCheckResult.NoUpdate
            }
        }.getOrElse { error ->
            UpdateCheckResult.Failed(error.userMessage("Unable to check for updates."))
        }
    }

    suspend fun downloadUpdate(
        manifest: UpdateManifest,
        onProgress: (Int?) -> Unit
    ): UpdateDownloadResult = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
            val destination = File(updatesDirectory, "mapnet-${manifest.versionCode}.apk")
            val temporary = File(updatesDirectory, "mapnet-${manifest.versionCode}.download")
            temporary.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            openHttps(manifest.apkUrl).useConnection { connection ->
                val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var receivedBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            receivedBytes += count
                            onProgress(totalBytes?.let { ((receivedBytes * 100) / it).toInt().coerceIn(0, 100) })
                        }
                    }
                }
            }

            val actualChecksum = digest.digest().toHex()
            require(actualChecksum == manifest.sha256) { "Downloaded file did not match the release checksum." }
            require(hasSamePackageAndSigner(temporary)) { "Downloaded APK is not signed by the installed MapNet app." }

            destination.delete()
            require(temporary.renameTo(destination)) { "Could not prepare the downloaded APK." }
            UpdateDownloadResult.Ready(destination)
        }.getOrElse { error ->
            UpdateDownloadResult.Failed(error.userMessage("Unable to download the update."))
        }
    }

    fun requestInstall(apk: File): InstallRequestResult = runCatching {
        require(apk.isFile) { "The downloaded update is no longer available." }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            InstallRequestResult.NEEDS_PERMISSION
        } else {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apk
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            InstallRequestResult.STARTED
        }
    }.getOrDefault(InstallRequestResult.FAILED)

    private fun installedVersionCode(): Long = packageInfoFor(context.packageName, 0).versionCodeCompat()

    private fun hasSamePackageAndSigner(apk: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val installed = packageInfoFor(context.packageName, flags)
        val archive = archivePackageInfoFor(apk, flags) ?: return false
        return archive.packageName == context.packageName &&
            installed.signerDigests().isNotEmpty() &&
            installed.signerDigests() == archive.signerDigests()
    }

    @Suppress("DEPRECATION")
    private fun packageInfoFor(packageName: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }

    @Suppress("DEPRECATION")
    private fun archivePackageInfoFor(apk: File, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }

    private fun openHttps(url: String): HttpURLConnection {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https") { "Updates must be served over HTTPS." }
        return (parsedUrl.openConnection() as? HttpURLConnection ?: error("Unsupported update URL"))
            .apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        connect()
        require(url.protocol == "https") { "Update download was redirected outside HTTPS." }
        require(responseCode in 200..299) { "Update server returned HTTP $responseCode." }
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            this.signatures
        }.orEmpty()
        return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun Throwable.userMessage(fallback: String): String = message?.takeIf { it.isNotBlank() } ?: fallback
