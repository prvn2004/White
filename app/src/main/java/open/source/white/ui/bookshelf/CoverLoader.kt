package open.source.white.ui.bookshelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

object CoverLoader {

    private val cache = object : LruCache<String, Bitmap>(20 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun load(path: String, targetWidth: Int, targetHeight: Int, onLoaded: (Bitmap?) -> Unit) {
        val key = path + "@" + targetWidth + "x" + targetHeight
        val cached = cache.get(key)
        if (cached != null) {
            onLoaded(cached)
            return
        }
        synchronized(inFlight) {
            val existing = inFlight[key]
            if (existing != null) {
                existing.add(onLoaded)
                return
            }
            inFlight[key] = mutableListOf(onLoaded)
        }
        executor.execute {
            val bitmap = decode(path, targetWidth, targetHeight)
            if (bitmap != null) {
                cache.put(key, bitmap)
            }
            val callbacks = synchronized(inFlight) {
                inFlight.remove(key).orEmpty()
            }
            mainHandler.post {
                for (callback in callbacks) {
                    callback(bitmap)
                }
            }
        }
    }

    private fun decode(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null

            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, bounds)

            var sample = 1
            val width = bounds.outWidth
            val height = bounds.outHeight
            val safeWidth = if (targetWidth <= 0) 320 else targetWidth
            val safeHeight = if (targetHeight <= 0) 480 else targetHeight
            while (width / (sample * 2) >= safeWidth && height / (sample * 2) >= safeHeight) {
                sample *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, options)
        } catch (error: Exception) {
            null
        }
    }
}

