package tv.minimal.home

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteOrderTest {
    private val defaults = listOf(listOf("netflix"), listOf("prime", "prime.alt"))

    @Test fun firstRunUsesInstalledAlternatives() {
        assertEquals(listOf("netflix", "prime.alt"),
            FavoriteOrder.resolve(null, defaults, setOf("netflix", "prime.alt", "extra")))
    }

    @Test fun savedSelectionKeepsOrderAndRemovesMissingAndDuplicateApps() {
        assertEquals(listOf("extra", "netflix"),
            FavoriteOrder.resolve(listOf("extra", "removed", "netflix", "extra"), defaults,
                setOf("netflix", "extra")))
    }

    @Test fun clearingAllFavoritesDoesNotRestoreDefaults() {
        assertEquals(emptyList<String>(),
            FavoriteOrder.resolve(emptyList(), defaults, setOf("netflix", "prime")))
    }

    @Test fun moveLeftAndRightPreservesOtherAppsAndOriginalList() {
        val original = listOf("netflix", "youtube", "extra", "prime")
        assertEquals(listOf("netflix", "extra", "youtube", "prime"),
            FavoriteOrder.move(original, 2, -1))
        assertEquals(listOf("netflix", "extra", "youtube", "prime"),
            FavoriteOrder.move(original, 1, 1))
        assertEquals(listOf("netflix", "youtube", "extra", "prime"), original)
    }

    @Test fun movesDoNotWrapOrUseInvalidPositions() {
        val apps = listOf("netflix", "extra")
        assertEquals(apps, FavoriteOrder.move(apps, 0, -1))
        assertEquals(apps, FavoriteOrder.move(apps, 1, 1))
        assertEquals(apps, FavoriteOrder.move(apps, -1, 1))
        assertEquals(apps, FavoriteOrder.move(apps, 0, 2))
        assertEquals(emptyList<String>(), FavoriteOrder.move(emptyList(), 0, 1))
    }

    @Test fun savedOrderSurvivesCatalogReload() {
        val moved = FavoriteOrder.move(listOf("netflix", "extra", "prime"), 1, -1)
        assertEquals(listOf("extra", "netflix", "prime"),
            FavoriteOrder.resolve(moved, defaults, setOf("netflix", "extra", "prime")))
    }
}
