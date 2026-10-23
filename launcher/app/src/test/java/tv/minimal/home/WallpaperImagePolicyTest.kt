package tv.minimal.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperImagePolicyTest {
    @Test fun acceptsNativeHeifStillImagesAndRejectsOtherProviderTypes() {
        assertEquals("heic", WallpaperImagePolicy.extensionForMime("image/heic"))
        assertEquals("heif", WallpaperImagePolicy.extensionForMime("image/heif"))
        assertEquals("jpg", WallpaperImagePolicy.extensionForMime("image/JPEG"))
        assertTrue("heic" in WallpaperImagePolicy.extensions)
        assertTrue("heif" in WallpaperImagePolicy.extensions)
        assertNull(WallpaperImagePolicy.extensionForMime("video/mp4"))
        assertNull(WallpaperImagePolicy.extensionForMime("application/octet-stream"))
        assertNull(WallpaperImagePolicy.extensionForMime(null))
    }

    @Test fun rotatedPhotoIsSampledForItsFinalPortraitShape() {
        // 3840x2160 with EXIF 6 becomes 2160x3840. It needs 8x sampling for a 960x540 screen.
        assertEquals(8, WallpaperImagePolicy.sampleSize(3840, 2160, 960, 540, 6))
        assertEquals(4, WallpaperImagePolicy.sampleSize(3840, 2160, 960, 540, 1))
        assertEquals(8, WallpaperImagePolicy.sampleSize(3840, 2160, 960, 540, 8))
    }

    @Test fun mirroredPortraitsAndPanoramasStayWithinFinalDisplayBounds() {
        for (exif in 1..8) {
            for ((width, height) in listOf(3840 to 2160, 4032 to 3024, 12000 to 1000)) {
                val orientation = WallpaperImagePolicy.orientation(exif)
                val sample = WallpaperImagePolicy.sampleSize(width, height, 960, 540, exif)
                val uprightWidth = if (orientation.swapsAxes) height else width
                val uprightHeight = if (orientation.swapsAxes) width else height
                assertTrue("Width exceeds display after EXIF $exif", uprightWidth <= 960L * sample)
                assertTrue("Height exceeds display after EXIF $exif", uprightHeight <= 540L * sample)
            }
        }
        assertFalse(WallpaperImagePolicy.orientation(2).swapsAxes)
        assertTrue(WallpaperImagePolicy.orientation(5).flipHorizontal)
        assertTrue(WallpaperImagePolicy.orientation(7).flipHorizontal)
    }

    @Test fun invalidExifLeavesImageUntransformed() {
        assertEquals(WallpaperImagePolicy.Orientation(), WallpaperImagePolicy.orientation(0))
        assertEquals(WallpaperImagePolicy.Orientation(), WallpaperImagePolicy.orientation(99))
    }
}
