package moe.tenshii.blockextremedroite

import android.os.Build
import androidx.annotation.WorkerThread
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    private const val TIMEOUT_MS = 15_000
    const val PROJECT_URL = "https://github.com/CuteTenshii/BlockExtremeDroiteAndroid"
    private val USER_AGENT = "BlockExtremeDroite/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; +$PROJECT_URL)"

    @WorkerThread
    @Throws(IOException::class)
    fun get(url: String): String = request(url, body = null)

    @WorkerThread
    @Throws(IOException::class)
    fun postJson(url: String, json: String): String = request(url, body = json)

    private fun request(url: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
