package tv.minimal.home

/** Null means first-run defaults; an empty saved list intentionally clears the dock. */
internal object FavoriteOrder {
    fun resolve(saved: List<String>?, defaults: List<List<String>>, installed: Set<String>): List<String> =
        (saved ?: defaults.mapNotNull { alternatives -> alternatives.firstOrNull { it in installed } })
            .filter { it in installed }.distinct()
}
