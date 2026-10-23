package tv.minimal.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

data class WallpaperChoice(val id: String, val label: String)

/**
 * Rotating wallpaper shared by every home screen in the process (the launcher activity and the
 * instant overlay), so both always show the same photo. The user can choose Bing photos,
 * local images, or generated offline landscapes. Automatic mode retains the original priority:
 * local images, then cached Bing photos, then offline landscapes.
 * Images are decoded off the UI thread so focus animations never stutter.
 */
object WallpaperEngine {
    class Frame(val bitmap: Bitmap, val caption: String?, val fitCenter: Boolean = false)

    /** The photo currently on screen; kept while nothing is attached so the next view shows it instantly. */
    var current: Frame? = null
        private set

    private class Source(
        val id: String,
        val label: String,
        val stamp: String,
        val caption: String?,
        val fitCenter: Boolean = false,
        val load: () -> Bitmap?,
    )

    private const val INTERVAL_MS = 10_000L
    private val EXTENSIONS = WallpaperImagePolicy.extensions

    private lateinit var app: Context
    private val listeners = LinkedHashSet<(Frame, Long) -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var sources: List<Source> = emptyList()
    private var signature = ""
    private var index = 0
    @Volatile private var generation = 0L
    private var networkInFlight = false
    private val rotate = object : Runnable {
        override fun run() {
            next(fade = 2000)
            refreshBing()
            if (listeners.isNotEmpty()) main.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Stable choices for the requested source; does not download or decode images. */
    fun choices(context: Context, source: WallpaperPreferences.Source): List<WallpaperChoice> {
        // Offline images are a rendering fallback, not misleading entries in an empty photo library.
        if (source == WallpaperPreferences.Source.LOCAL && ownPhotos(context).isEmpty()) return emptyList()
        if (source == WallpaperPreferences.Source.BING && cachedBing(context).isEmpty()) return emptyList()
        return available(context.applicationContext, source).map { WallpaperChoice(it.id, it.label) }
    }

    /** Apply saved source/image selections immediately to both home views. Call from the UI thread. */
    fun reload(context: Context) {
        app = context.applicationContext
        reloadSources(force = true)
        refreshBing()
        resetRotation()
    }

    /** Advance the current selection immediately, without waiting for the rotation timer. */
    fun nextImage(context: Context) {
        app = context.applicationContext
        if (!reloadSources()) next(fade = 700)
        refreshBing()
        resetRotation()
    }

    private fun resetRotation() {
        main.removeCallbacks(rotate)
        if (listeners.isNotEmpty()) main.postDelayed(rotate, INTERVAL_MS)
    }

    /** Starts delivering frames to [listener] (immediately with the current one, without a fade). */
    fun attach(context: Context, listener: (Frame, Long) -> Unit) {
        app = context.applicationContext
        listeners += listener
        current?.let { listener(it, 0L) }
        if (listeners.size == 1) {
            reloadSources()
            refreshBing()
            resetRotation()
        }
    }

    fun detach(listener: (Frame, Long) -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) main.removeCallbacks(rotate)
    }

    private fun ownPhotos(context: Context): List<File> = context.getExternalFilesDir("wallpapers")
        ?.listFiles { file -> file.isFile && file.extension.lowercase() in EXTENSIONS }
        .orEmpty().sortedBy { it.name }

    private fun cachedBing(context: Context): List<BingPhotos.Photo> =
        runCatching { BingPhotos.cached(context) }.getOrDefault(emptyList())

    private fun offlineSources(): List<Source> = PALETTES.mapIndexed { index, palette ->
        Source("offline:${palette.seed}", PALETTE_NAMES[index], palette.seed.toString(), null) { paint(palette) }
    }

    private fun available(context: Context, requested: WallpaperPreferences.Source): List<Source> {
        val dm = context.resources.displayMetrics
        val decode = { file: File -> decodeScaled(file, dm.widthPixels, dm.heightPixels) }
        val own = if (requested == WallpaperPreferences.Source.AUTO || requested == WallpaperPreferences.Source.LOCAL)
            ownPhotos(context) else emptyList()
        val bing = if (requested == WallpaperPreferences.Source.BING ||
            (requested == WallpaperPreferences.Source.AUTO && own.isEmpty())) cachedBing(context) else emptyList()
        return when (WallpaperPolicy.effectiveSource(requested, own.isNotEmpty(), bing.isNotEmpty())) {
            WallpaperPreferences.Source.LOCAL -> own.map { file ->
                Source("local:${file.name}", file.nameWithoutExtension,
                    "${file.length()}:${file.lastModified()}", null, fitCenter = true) { decode(file) }
            }
            WallpaperPreferences.Source.BING -> bing.map { photo ->
                Source("bing:${photo.file.name}", photo.caption?.takeIf { it.isNotBlank() }
                    ?: photo.file.nameWithoutExtension,
                    "${photo.file.length()}:${photo.file.lastModified()}", photo.caption) { decode(photo.file) }
            }
            else -> offlineSources()
        }
    }

    /** Rebuild when files or preferences change; one generation prevents old decodes winning a race. */
    private fun reloadSources(force: Boolean = false): Boolean {
        val requested = WallpaperPreferences.getSource(app)
        val selected = WallpaperPreferences.getSelectedIds(app, requested)
        val available = available(app, requested)
        val selectedIds = WallpaperPolicy.select(available.map { it.id }, selected).toSet()
        val sig = requested.name + "|" + (selected?.sorted()?.joinToString() ?: "all") + "|" +
            available.joinToString { it.id + ":" + it.stamp }
        if (!force && sig == signature && sources.isNotEmpty()) return false
        generation++
        signature = sig
        val filtered = available.filter { it.id in selectedIds }
        // Keep today's Bing photo first, randomize the remainder and local/offline collections.
        sources = if (filtered.firstOrNull()?.id?.startsWith("bing:") == true)
            filtered.take(1) + filtered.drop(1).shuffled() else filtered.shuffled()
        index = 0
        next(fade = if (current == null) 700 else 1200)
        return true
    }

    private fun refreshBing() {
        if (networkInFlight) return
        val requested = WallpaperPreferences.getSource(app)
        if (!WallpaperPolicy.shouldRefreshBing(requested,
                requested == WallpaperPreferences.Source.AUTO && ownPhotos(app).isNotEmpty())) return
        networkInFlight = true
        val context = app
        network.execute {
            val activeSource = WallpaperPreferences.getSource(context)
            val allowed = WallpaperPolicy.shouldRefreshBing(activeSource,
                activeSource == WallpaperPreferences.Source.AUTO && ownPhotos(context).isNotEmpty())
            val changed = allowed && runCatching { BingPhotos.refreshIfStale(context) }.getOrDefault(false)
            main.post {
                networkInFlight = false
                if (changed) reloadSources()
            }
        }
    }

    private fun next(fade: Long) {
        if (sources.isEmpty()) return
        val source = sources[index % sources.size]
        index = (index + 1) % sources.size
        val request = ++generation
        worker.execute {
            if (request != generation) return@execute
            val bmp = runCatching { source.load() }.getOrNull()
            main.post {
                if (request != generation) {
                    bmp?.recycle() // never handed to an ImageView
                    return@post
                }
                if (bmp == null) {
                    // A removed/corrupt local file must not leave a changed source stuck on the old photo.
                    sources = sources.filterNot { it.id == source.id }
                    if (sources.isEmpty() && !source.id.startsWith("offline:")) sources = offlineSources()
                    index = 0
                    if (sources.isNotEmpty()) next(fade)
                    return@post
                }
                val frame = Frame(bmp, source.caption, source.fitCenter)
                current = frame
                listeners.toList().forEach { it(frame, fade) }
            }
        }
    }

    private val PALETTE_NAMES = listOf("Alpensee", "Wüste am Abend", "Morgennebel", "Nächtliche Berge", "Waldtal")

    private class Palette(
        val skyTop: Int, val skyBottom: Int, val glow: Int, val sunX: Float,
        val hills: IntArray, val seed: Int,
    )

    private val PALETTES = listOf(
        // alpine lake
        Palette(0xFF16324F.toInt(), 0xFF7FB2C9.toInt(), 0xFFE9F3F5.toInt(), 0.7f,
            intArrayOf(0xFF4F7489.toInt(), 0xFF2F5161.toInt(), 0xFF1B3340.toInt(), 0xFF0E1C24.toInt()), 7),
        // desert dusk
        Palette(0xFF2B1C3A.toInt(), 0xFFD9886A.toInt(), 0xFFFFD39A.toInt(), 0.3f,
            intArrayOf(0xFF8A4F4A.toInt(), 0xFF5E3540.toInt(), 0xFF3A2233.toInt(), 0xFF1C1220.toInt()), 11),
        // misty morning
        Palette(0xFF51677A.toInt(), 0xFFC9D3D9.toInt(), 0xFFFFFFFF.toInt(), 0.55f,
            intArrayOf(0xFF8A9AA6.toInt(), 0xFF64747F.toInt(), 0xFF434F58.toInt(), 0xFF262E34.toInt()), 3),
        // night ridge
        Palette(0xFF060B1C.toInt(), 0xFF1E3A5C.toInt(), 0xFF7FB7FF.toInt(), 0.6f,
            intArrayOf(0xFF1B2B45.toInt(), 0xFF132036.toInt(), 0xFF0C1526.toInt(), 0xFF060B14.toInt()), 19),
        // forest valley
        Palette(0xFF1F3A3A.toInt(), 0xFF9CC5A1.toInt(), 0xFFF3F7D9.toInt(), 0.4f,
            intArrayOf(0xFF4E7A5A.toInt(), 0xFF335A43.toInt(), 0xFF1F3D2E.toInt(), 0xFF10221A.toInt()), 23),
    )

    /** A layered mountain landscape in the given palette. */
    private fun paint(p: Palette): Bitmap {
        val w = 960
        val h = 540
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.8f, p.skyTop, p.skyBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = RadialGradient(w * p.sunX, h * 0.55f, h * 0.85f,
            intArrayOf(p.glow and 0x00FFFFFF or 0x99000000.toInt(), p.glow and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val rnd = Random(p.seed)
        p.hills.forEachIndexed { i, color ->
            val base = h * (0.55f + i * 0.13f)
            val amp = h * (0.13f - i * 0.025f)
            val f1 = 0.8f + rnd.nextFloat() * 1.2f
            val f2 = 2.5f + rnd.nextFloat() * 3f
            val ph1 = rnd.nextFloat() * 6.28f
            val ph2 = rnd.nextFloat() * 6.28f
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                var x = 0f
                while (x <= w) {
                    val t = x / w * 2 * PI.toFloat()
                    lineTo(x, base - amp * (0.65f * sin(t * f1 + ph1) + 0.35f * sin(t * f2 + ph2)))
                    x += 6f
                }
                lineTo(w.toFloat(), h.toFloat())
                close()
            }
            paint.shader = LinearGradient(0f, base - amp, 0f, h.toFloat(),
                color, blend(color, 0xFF000000.toInt(), 0.35f), Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
        }
        return bmp
    }

    private fun decodeScaled(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxWidth = w.coerceIn(1, 3840)
        val maxHeight = h.coerceIn(1, 2160)
        val exifOrientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val orientation = WallpaperImagePolicy.orientation(exifOrientation)
        val sample = WallpaperImagePolicy.sampleSize(
            bounds.outWidth, bounds.outHeight, maxWidth, maxHeight, exifOrientation)
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null // HEIF may not have a native decoder on older devices.
        val upright = if (orientation.degrees == 0 && !orientation.flipHorizontal) decoded else {
            val matrix = Matrix().apply {
                if (orientation.degrees != 0) setRotate(orientation.degrees.toFloat())
                if (orientation.flipHorizontal) postScale(-1f, 1f)
            }
            // These bitmaps are still private to the worker, never used by an ImageView.
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        if (upright.width <= maxWidth && upright.height <= maxHeight) return upright
        // Keep the cap even if a decoder rounds or ignores its requested sample size.
        val scale = minOf(maxWidth.toFloat() / upright.width, maxHeight.toFloat() / upright.height)
        return Bitmap.createScaledBitmap(upright, (upright.width * scale).toInt().coerceAtLeast(1),
            (upright.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== upright) upright.recycle() }
    }
}

/** Full-screen view of [WallpaperEngine]'s photo, crossfading between frames while attached. */
class WallpaperLayer(context: Context) : FrameLayout(context) {
    /** Called with each new frame (and the fade duration; 0 when shown instantly on attach). */
    var onFrame: ((WallpaperEngine.Frame, Long) -> Unit)? = null

    private val layers = List(2) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            addView(this, LayoutParams(-1, -1))
        }
    }
    private var front = -1
    private var showing: Bitmap? = null
    private var showingFitCenter = false
    private var transition = 0L
    private var subscribed = false
    private val listener: (WallpaperEngine.Frame, Long) -> Unit = { frame, fade ->
        show(frame.bitmap, fade, frame.fitCenter)
        onFrame?.invoke(frame, fade)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSubscription()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateSubscription()
    }

    private fun updateSubscription() {
        val visible = isAttachedToWindow && windowVisibility == VISIBLE
        if (visible == subscribed) return
        subscribed = visible
        if (visible) WallpaperEngine.attach(context, listener) else WallpaperEngine.detach(listener)
    }

    override fun onDetachedFromWindow() {
        if (subscribed) WallpaperEngine.detach(listener)
        subscribed = false
        super.onDetachedFromWindow()
    }

    private fun show(bmp: Bitmap, fade: Long, fitCenter: Boolean) {
        if (bmp === showing && fitCenter == showingFitCenter) return
        showing = bmp
        showingFitCenter = fitCenter
        val activeTransition = ++transition
        layers.forEach { it.animate().cancel() }
        val out = layers.getOrNull(front)
        out?.alpha = 1f
        front = if (front == 0) 1 else 0
        val into = layers[front]
        into.animate().cancel()
        // Preserve every part of private photos; opaque bars also crossfade cleanly from scenic images.
        into.scaleType = if (fitCenter) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        into.setBackgroundColor(if (fitCenter) 0xFF07090D.toInt() else 0x00000000)
        into.setImageBitmap(bmp)
        into.bringToFront() // fade the new image in on top, so there's no dark dip mid-crossfade
        if (fade <= 0) {
            into.alpha = 1f
            out?.apply { animate().cancel(); alpha = 0f; setImageDrawable(null) }
            return
        }
        into.alpha = 0f
        into.animate().alpha(1f).setDuration(fade).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (activeTransition == transition) out?.apply { alpha = 0f; setImageDrawable(null) }
            }
            .start()
    }
}
