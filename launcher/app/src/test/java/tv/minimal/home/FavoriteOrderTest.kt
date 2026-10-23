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
}
