package tv.minimal.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.minimal.home.WallpaperPreferences.Source

class WallpaperPolicyTest {
    @Test fun automaticModePreservesLocalThenBingThenOfflinePriority() {
        assertEquals(Source.LOCAL, WallpaperPolicy.effectiveSource(Source.AUTO, true, true))
        assertEquals(Source.BING, WallpaperPolicy.effectiveSource(Source.AUTO, false, true))
        assertEquals(Source.OFFLINE, WallpaperPolicy.effectiveSource(Source.AUTO, false, false))
    }

    @Test fun explicitSourceDoesNotGetOverriddenByAnotherLibrary() {
        assertEquals(Source.BING, WallpaperPolicy.effectiveSource(Source.BING, true, true))
        assertEquals(Source.LOCAL, WallpaperPolicy.effectiveSource(Source.LOCAL, true, true))
        assertEquals(Source.OFFLINE, WallpaperPolicy.effectiveSource(Source.OFFLINE, true, true))
    }

    @Test fun unavailableExplicitSourceUsesOfflineFallback() {
        assertEquals(Source.OFFLINE, WallpaperPolicy.effectiveSource(Source.LOCAL, false, true))
        assertEquals(Source.OFFLINE, WallpaperPolicy.effectiveSource(Source.BING, true, false))
    }

    @Test fun localAndOfflineModesNeverRequestBing() {
        for (hasLocal in listOf(true, false)) {
            assertFalse(WallpaperPolicy.shouldRefreshBing(Source.LOCAL, hasLocal))
            assertFalse(WallpaperPolicy.shouldRefreshBing(Source.OFFLINE, hasLocal))
        }
    }

    @Test fun bingRefreshesOnlyWhenItsLibraryIsNeeded() {
        assertFalse(WallpaperPolicy.shouldRefreshBing(Source.AUTO, true))
        assertTrue(WallpaperPolicy.shouldRefreshBing(Source.AUTO, false))
        assertTrue(WallpaperPolicy.shouldRefreshBing(Source.BING, true))
    }

    @Test fun onlySelectedAvailablePhotosRotateInLibraryOrder() {
        assertEquals(listOf("b", "c"),
            WallpaperPolicy.select(listOf("a", "b", "c"), setOf("c", "b", "expired")))
    }

    @Test fun allSelectionIncludesFuturePhotosAndDeduplicates() {
        assertEquals(listOf("a", "b"), WallpaperPolicy.select(listOf("a", "b", "a"), null))
    }

    @Test fun expiredOrEmptySelectionsCannotProduceAnEmptyPlaylist() {
        assertEquals(listOf("new"), WallpaperPolicy.select(listOf("new"), setOf("expired")))
        assertEquals(listOf("new"), WallpaperPolicy.select(listOf("new"), emptySet()))
        assertEquals(emptyList<String>(), WallpaperPolicy.select(emptyList(), setOf("expired")))
    }

    @Test fun panoramaAndPortraitDecodeAreBoundedOnBothAxes() {
        assertEquals(8, WallpaperPolicy.sampleSize(12000, 1000, 1920, 1080))
        assertEquals(16, WallpaperPolicy.sampleSize(1000, 12000, 1920, 1080))
        assertEquals(4, WallpaperPolicy.sampleSize(7680, 4320, 1920, 1080))
        assertEquals(1, WallpaperPolicy.sampleSize(960, 540, 1920, 1080))
    }
}
