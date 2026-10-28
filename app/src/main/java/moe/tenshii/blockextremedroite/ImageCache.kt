package moe.tenshii.blockextremedroite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-level cache for downloaded pictures: decoded bitmaps in memory, files on disk for [maxAgeMs].
 * Concurrent requests for one URL share a single download, and a URL that failed isn't retried until restart.
 */
class ImageCache(
    private val directory: File,
    memoryBytes: Int,
    private val maxAgeMs: Long,
    private val maxDiskBytes: Long,
) {
    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val locks = ConcurrentHashMap<String, Any>()

    fun cached(url: String): Bitmap? = memory.get(url)

    fun hasFailed(url: String): Boolean = url in failed

    /** Returns the picture at [url] downsampled to about [sizePx], from memory, disk or the network in that order. */
    @WorkerThread
    fun get(url: String, sizePx: Int): Bitmap? {
        memory.get(url)?.let { return it }
        if (url in failed) return null
        synchronized(locks.getOrPut(url) { Any() }) {
            memory.get(url)?.let { return it }
            if (url in failed) return null
            val bitmap = runCatching { decode(fileFor(url), sizePx) }.getOrNull()
            if (bitmap == null) failed += url else memory.put(url, bitmap)
            return bitmap
        }
    }

    /** Deletes the oldest files until the disk cache fits in [maxDiskBytes]. */
    @WorkerThread
    fun trim() {
        val files = directory.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxDiskBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun fileFor(url: String): File {
        val file = File(directory, sha1(url))
        if (file.isFile && System.currentTimeMillis() - file.lastModified() <= maxAgeMs) return file
        directory.mkdirs()
        val part = File(directory, "${file.name}.part")
        val downloaded = runCatching { Http.download(url, part) { _, _ -> } }
        if (downloaded.isSuccess && part.renameTo(file)) {
            file.setLastModified(System.currentTimeMillis())
        } else {
            part.delete()
        }
        // A stale picture is still better than none when the refresh fails.
        if (!file.isFile) throw downloaded.exceptionOrNull() ?: IOException("Could not save $url")
        return file
    }

    private fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
