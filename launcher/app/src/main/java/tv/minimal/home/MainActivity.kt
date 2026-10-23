package tv.minimal.home

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast

/** The launcher activity; the UI itself lives in [HomeView]. */
class MainActivity : Activity(), HomeView.Host {
    private lateinit var home: HomeView
    private var isResumedNow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!acceptLaunch(intent, savedInstanceState != null)) {
            finish()
            return
        }
        Apps.watch(this)
        home = HomeView(this, this)
        setContentView(home)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!acceptLaunch(intent, restoredActivity = false)) {
            finish()
            return
        }
        // Home pressed while already home → back to the dock, like tvOS.
        // (Coming back from an app arrives here too, but then we keep the launched tile focused.)
        if (isResumedNow) home.goToTop()
    }

    override fun onResume() {
        super.onResume()
        // A service launch already queued by Android may arrive after choosing Fire TV.
        if (OriginalHomeMode.isActive(this)) {
            finish()
            return
        }
        isResumedNow = true
        visible = true
        // If the instant overlay was covering for us, take over exactly where it was, then drop it.
        HomeRedirectService.instance?.handOffTo(home)
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        visible = false
        if (::home.isInitialized) lastFocusedPkg = home.focusedPkg
    }

    private fun acceptLaunch(launch: Intent, restoredActivity: Boolean): Boolean {
        val automatic = launch.getBooleanExtra(OriginalHomeMode.AUTOMATIC_LAUNCH, false)
        if (!HomeLaunchPolicy.shouldOpen(OriginalHomeMode.isActive(this), automatic)) return false
        if (HomeLaunchPolicy.shouldResume(automatic, restoredActivity) && OriginalHomeMode.isActive(this)) {
            return OriginalHomeMode.setActive(this, false)
        }
        return true
    }

    override fun launch(app: AppEntry, from: View) {
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            startActivity(app.intent(), opts)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** True while the home screen is in front; the redirect service uses it to confirm a redirect landed. */
        @Volatile
        var visible = false
            private set

        /** The app that had focus when we last left home, so the overlay can pick up from there. */
        @Volatile
        var lastFocusedPkg: String? = null
            private set
    }
}
