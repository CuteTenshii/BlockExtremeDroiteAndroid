package moe.tenshii.blockextremedroite

import android.os.Build
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A response other than 200 OK; [code] lets callers tell an expired session (401) from other failures. */
class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

object Http {
    private const val TIMEOUT_MS = 15_000
    const val PROJECT_URL = "https://github.com/CuteTenshii/BlockExtremeDroiteAndroid"
    private val USER_AGENT = "BlockExtremeDroite/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; +$PROJECT_URL)"

    @WorkerThread
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): String = request(url, body = null, headers)

    @WorkerThread
    @Throws(IOException::class)
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String = request(url, body = json, headers)

    /** Streams [url] into [target]; [onProgress] gets the bytes written so far and the total, or -1 when unknown. */
    @WorkerThread
    @Throws(IOException::class)
    fun download(url: String, target: File, onProgress: (downloaded: Long, total: Long) -> Unit) {
        val connection = open(url, emptyMap())
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpException(code, url)
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(url: String, body: String?, headers: Map<String, String>): String {
        val connection = open(url, headers)
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpException(code, url)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            // The site's API only answers requests that carry its page's marker and origin.
            if (url.startsWith(AccountRepository.SITE + "/")) {
                setRequestProperty("X-BF", "1")
                setRequestProperty("Origin", AccountRepository.SITE)
            }
            headers.forEach(::setRequestProperty)
        }
}
