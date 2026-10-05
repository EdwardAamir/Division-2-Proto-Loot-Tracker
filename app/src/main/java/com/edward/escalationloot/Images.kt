package com.edward.escalationloot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Minimal remote image loader. The gear art is 256x256 PNG on prototrack.gg
 * (HTTPS only, so the app's usesCleartextTraffic=false is fine).
 *
 * Deliberately hand-rolled rather than pulling in Glide/Coil: the app needs
 * exactly one thing (fetch a small PNG into an ImageView with a memory cache
 * and a local placeholder), and that is a few dozen lines.
 */
object Images {
    private const val TAG = "EscalationLootImg"
    private const val CACHE_KB = 4 * 1024 // 4 MB

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /**
     * Load the first of [urls] that resolves, falling back to [placeholder].
     *
     * Takes a list because asset filenames are not reliably derivable from the
     * loot name: ProtoTrack serves "Gear Mods" at gear/gearmod.png, singular,
     * so a single guess 404s and the card shows no art. Trying a short ordered
     * candidate list costs one extra 404 in the rare case and removes the guess
     * from being load-bearing.
     */
    fun load(view: ImageView, urls: List<String>, placeholderRes: Int? = null) {
        val candidates = urls.filter { it.isNotBlank() }
        if (candidates.isEmpty()) {
            view.setImageDrawable(placeholderRes?.let { view.context.getDrawable(it) })
            return
        }

        candidates.forEach { url ->
            cache.get(url)?.let {
                view.setImageBitmap(it)
                return
            }
        }

        view.setImageDrawable(placeholderRes?.let { view.context.getDrawable(it) })
        // Tag the whole candidate list so a late callback can be discarded if the
        // view has since been rebound to different data.
        view.tag = candidates

        val appCtx = view.context.applicationContext
        Thread {
            for (url in candidates) {
                val bmp = runCatching { download(appCtx, url) }.getOrNull()
                if (bmp == null) {
                    Log.d(TAG, "icon miss: $url")
                    continue
                }
                // Logged so a missing icon is diagnosable from logcat rather than
                // being a silent blank space in the UI.
                Log.d(TAG, "icon ok: ${url.substringAfterLast("/")} " +
                    "${bmp.width}x${bmp.height} (candidate ${candidates.indexOf(url) + 1}/${candidates.size})")
                cache.put(url, bmp)
                view.post {
                    if (view.tag == candidates) view.setImageBitmap(bmp)
                }
                return@Thread
            }
            Log.w(TAG, "no icon resolved for ${candidates.joinToString()}")
        }.start()
    }

    fun load(view: ImageView, url: String?, placeholderRes: Int? = null) =
        load(view, listOfNotNull(url), placeholderRes)

    private fun download(ctx: Context, url: String): Bitmap {
        val req = Request.Builder().url(url).header("User-Agent", "EscalationLootApp/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("Empty body")
            // Downsample: source art is 256x256, which is plenty at any phone density.
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: error("Not a decodable image")
        }
    }
}
