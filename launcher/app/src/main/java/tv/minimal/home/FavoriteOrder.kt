package tv.minimal.home

/** Null means first-run defaults; an empty saved list intentionally clears the dock. */
internal object FavoriteOrder {
    fun resolve(saved: List<String>?, defaults: List<List<String>>, installed: Set<String>): List<String> =
        (saved ?: defaults.mapNotNull { alternatives -> alternatives.firstOrNull { it in installed } })
            .filter { it in installed }.distinct()

    /** Move by one position without wrapping the ends of the dock. */
    fun move(packages: List<String>, index: Int, offset: Int): List<String> {
        val target = index + offset
        if (offset !in listOf(-1, 1) || index !in packages.indices || target !in packages.indices)
            return packages
        return packages.toMutableList().apply { add(target, removeAt(index)) }
    }
}
