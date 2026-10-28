package moe.tenshii.blockextremedroite

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published build newer than the installed one. [sha256] is null for assets GitHub has no digest for. */
@Serializable
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
)

/**
 * Looks for newer builds in the project's GitHub releases and downloads their APK. Installing is left
 * to the system installer, which refuses an APK signed with a different key than the installed app.
 */
class Updater(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val directory = File(appContext.cacheDir, DIRECTORY)

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long,
        val digest: String? = null,
    )

    /** The release found by the last successful check, as long as it is still ahead of the installed version. */
    val available: Release?
        get() = prefs.getString(KEY_RELEASE, null)
            ?.let { runCatching { json.decodeFromString<Release>(it) }.getOrNull() }
            ?.takeIf { isNewerVersion(it.version, BuildConfig.VERSION_NAME) }

    /** Returns the latest release if it is newer than the installed version, and remembers the answer. */
    @WorkerThread
    @Throws(IOException::class, IllegalArgumentException::class)
    fun check(): Release? {
        val latest = json.decodeFromString<GitHubRelease>(Http.get(LATEST_URL))
        val release = latest.toRelease()?.takeIf { isNewerVersion(it.version, BuildConfig.VERSION_NAME) }
        prefs.edit {
            if (release == null) remove(KEY_RELEASE) else putString(KEY_RELEASE, json.encodeToString(release))
        }
        if (release == null) directory.deleteRecursively()
        return release
    }

    /** Downloads the release's APK, or reuses one already downloaded, and checks it really is this app. */
    @WorkerThread
    @Throws(IOException::class)
    fun download(release: Release, onProgress: (percent: Int) -> Unit): File {
        val apk = File(directory, "${release.version}.apk")
        if (apk.isFile && runCatching { verify(apk, release) }.isSuccess) return apk

        directory.deleteRecursively()
        if (!directory.mkdirs()) throw IOException("Could not create ${directory.path}")
        val part = File(directory, "${apk.name}.part")
        var lastPercent = -1
        Http.download(release.apkUrl, part) { downloaded, total ->
            val size = if (total > 0) total else release.apkSize
            val percent = if (size > 0) (downloaded * 100 / size).toInt().coerceIn(0, 100) else 0
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }
        try {
            verify(part, release)
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        if (!part.renameTo(apk)) throw IOException("Could not save ${apk.path}")
        return apk
    }

    private fun verify(apk: File, release: Release) {
        if (release.apkSize > 0 && apk.length() != release.apkSize) {
            throw IOException("Expected ${release.apkSize} bytes, got ${apk.length()}")
        }
        if (release.sha256 != null && !sha256(apk).equals(release.sha256, ignoreCase = true)) {
            throw IOException("SHA-256 mismatch for ${apk.name}")
        }
        val packageName = archiveInfo(apk)?.packageName ?: throw IOException("${apk.name} is not a valid APK")
        if (packageName != appContext.packageName) throw IOException("${apk.name} is $packageName, not this app")
    }

    private fun archiveInfo(apk: File): PackageInfo? {
        val packageManager = appContext.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.path, 0)
        }
    }

    private fun GitHubRelease.toRelease(): Release? {
        val asset = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        return Release(
            version = tagName.trim().removePrefix("v").removePrefix("V"),
            notes = body.orEmpty().trim(),
            apkUrl = asset.downloadUrl,
            apkSize = asset.size,
            sha256 = asset.digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX),
        )
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/CuteTenshii/BlockExtremeDroiteAndroid/releases/latest"
        private const val DIRECTORY = "updates"
        private const val PREFS = "updates"
        private const val KEY_RELEASE = "release"
        private const val SHA256_PREFIX = "sha256:"
        private val json = Json { ignoreUnknownKeys = true }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Compares dotted numeric versions, ignoring a leading "v"; a version that doesn't parse is never newer. */
fun isNewerVersion(candidate: String, installed: String): Boolean {
    val new = parseVersion(candidate) ?: return false
    val current = parseVersion(installed) ?: return false
    for (i in 0 until maxOf(new.size, current.size)) {
        val difference = new.getOrElse(i) { 0 }.compareTo(current.getOrElse(i) { 0 })
        if (difference != 0) return difference > 0
    }
    return false
}

private fun parseVersion(version: String): List<Int>? =
    version.trim().removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: return null }
