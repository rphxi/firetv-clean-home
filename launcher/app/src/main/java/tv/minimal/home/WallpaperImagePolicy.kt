package tv.minimal.home

/** Still-image formats and EXIF transforms, independent of Android's device-specific decoders. */
internal object WallpaperImagePolicy {
    val extensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    data class Orientation(val degrees: Int = 0, val flipHorizontal: Boolean = false) {
        val swapsAxes: Boolean get() = degrees == 90 || degrees == 270
    }

    fun extensionForMime(mime: String?): String? = when (mime?.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> null
    }

    /** EXIF 1–8: mirror is applied after clockwise rotation, matching Matrix.postScale. */
    fun orientation(exif: Int): Orientation = when (exif) {
        2 -> Orientation(0, true)
        3 -> Orientation(180)
        4 -> Orientation(180, true)
        5 -> Orientation(90, true)
        6 -> Orientation(90)
        7 -> Orientation(270, true)
        8 -> Orientation(270)
        else -> Orientation() // Missing or malformed metadata should not hide an otherwise valid photo.
    }

    fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int, exif: Int): Int =
        if (orientation(exif).swapsAxes)
            WallpaperPolicy.sampleSize(width, height, maxHeight, maxWidth)
        else WallpaperPolicy.sampleSize(width, height, maxWidth, maxHeight)
}
