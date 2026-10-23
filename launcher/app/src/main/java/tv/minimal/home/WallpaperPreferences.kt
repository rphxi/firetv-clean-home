package tv.minimal.home

import android.content.Context

/** User choices are shared by the activity and accessibility overlay and survive restarts. */
object WallpaperPreferences {
    enum class Source { AUTO, BING, LOCAL, OFFLINE }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)

    fun getSource(context: Context): Source = runCatching {
        Source.valueOf(preferences(context).getString("source", Source.AUTO.name)!!)
    }.getOrDefault(Source.AUTO)

    fun setSource(context: Context, source: Source) {
        preferences(context).edit().putString("source", source.name).apply()
    }

    /** Null means all current and future images from this source, including newly downloaded photos. */
    fun getSelectedIds(context: Context, source: Source): Set<String>? =
        preferences(context).getStringSet("selected_${source.name}", null)?.toSet()

    /** Null resets the source to all images; an empty/stale selection also falls back to all images. */
    fun setSelectedIds(context: Context, source: Source, ids: Set<String>?) {
        val editor = preferences(context).edit()
        if (ids == null) editor.remove("selected_${source.name}")
        else editor.putStringSet("selected_${source.name}", ids.toSet())
        editor.apply()
    }
}

/** Pure policy keeps empty libraries and expired cached-photo selections usable. */
internal object WallpaperPolicy {
    fun effectiveSource(requested: WallpaperPreferences.Source, hasLocal: Boolean, hasBing: Boolean): WallpaperPreferences.Source =
        when (requested) {
            WallpaperPreferences.Source.AUTO -> when {
                hasLocal -> WallpaperPreferences.Source.LOCAL
                hasBing -> WallpaperPreferences.Source.BING
                else -> WallpaperPreferences.Source.OFFLINE
            }
            WallpaperPreferences.Source.LOCAL -> if (hasLocal) requested else WallpaperPreferences.Source.OFFLINE
            WallpaperPreferences.Source.BING -> if (hasBing) requested else WallpaperPreferences.Source.OFFLINE
            WallpaperPreferences.Source.OFFLINE -> requested
        }

    fun shouldRefreshBing(requested: WallpaperPreferences.Source, hasLocal: Boolean): Boolean =
        requested == WallpaperPreferences.Source.BING ||
            (requested == WallpaperPreferences.Source.AUTO && !hasLocal)

    fun select(available: List<String>, selected: Set<String>?): List<String> {
        val unique = available.distinct()
        if (selected == null) return unique
        return unique.filter { it in selected }.ifEmpty { unique }
    }

    /** Bound both axes, including very wide panoramas that never fill the display's height. */
    fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        require(width > 0 && height > 0 && maxWidth > 0 && maxHeight > 0)
        var sample = 1
        while (sample < (1 shl 30) &&
            (width.toLong() > maxWidth.toLong() * sample || height.toLong() > maxHeight.toLong() * sample)) {
            sample *= 2
        }
        return sample
    }
}
