package moe.tenshii.blockextremedroite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Loads account pictures into recycled rows through an [ImageCache]. Rows scrolled away before their turn
 * are skipped, so a fast fling doesn't queue a download for every account it passes.
 */
class PortraitLoader private constructor(context: Context) {
    private val cache = ImageCache(
        directory = File(context.applicationContext.cacheDir, DIRECTORY),
        memoryBytes = MEMORY_BYTES,
        maxAgeMs = MAX_AGE_MS,
        maxDiskBytes = MAX_DISK_BYTES,
    )
    private val executor = Executors.newFixedThreadPool(THREADS)
    private val main = Handler(Looper.getMainLooper())
    private val wanted: MutableMap<ImageView, String> = Collections.synchronizedMap(WeakHashMap())

    init {
        executor.execute { cache.trim() }
    }

    /** Shows [url] in [view] once loaded; until then, or if it fails, the view stays empty. */
    fun load(view: ImageView, url: String?, sizePx: Int) {
        val cached = url?.let(cache::cached)
        view.setImageBitmap(cached)
        if (url == null || cached != null || cache.hasFailed(url)) {
            wanted.remove(view)
            return
        }
        wanted[view] = url
        executor.execute {
            if (wanted[view] != url) return@execute
            val bitmap = cache.get(url, sizePx) ?: return@execute
            main.post {
                if (wanted[view] == url) {
                    wanted.remove(view)
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    companion object {
        private const val DIRECTORY = "portraits"
        private const val THREADS = 3
        private const val MEMORY_BYTES = 8 * 1024 * 1024
        /** The `/avatar` proxy's own cache lifetime; profile pictures change. */
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_DISK_BYTES = 30L * 1024 * 1024

        @Volatile
        private var instance: PortraitLoader? = null

        fun get(context: Context): PortraitLoader =
            instance ?: synchronized(this) { instance ?: PortraitLoader(context).also { instance = it } }
    }
}
