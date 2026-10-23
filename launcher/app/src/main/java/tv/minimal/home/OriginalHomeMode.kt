package tv.minimal.home

import android.content.Context

/** The user's original-home choice survives process death, sleep, and device restart. */
internal object OriginalHomeMode {
    private const val PREFERENCES = "home"
    private const val KEY = "original_home_active"
    const val AUTOMATIC_LAUNCH = "tv.minimal.home.AUTOMATIC_LAUNCH"

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Commit before launching another app, so a killed process cannot lose the user's choice. */
    fun setActive(context: Context, active: Boolean): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY, active).commit()
}

/** Explicit user launches resume the custom home; automatic retries must respect the pause. */
internal object HomeLaunchPolicy {
    fun shouldOpen(originalHomeActive: Boolean, automatic: Boolean): Boolean =
        !originalHomeActive || !automatic

    fun shouldResume(automatic: Boolean, restoredActivity: Boolean): Boolean =
        !automatic && !restoredActivity
}
