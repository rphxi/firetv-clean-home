package tv.minimal.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLaunchPolicyTest {
    @Test fun originalHomeRejectsAutomaticServiceAndBootLaunches() {
        assertFalse(HomeLaunchPolicy.shouldOpen(originalHomeActive = true, automatic = true))
        assertFalse(HomeLaunchPolicy.shouldResume(automatic = true, restoredActivity = false))
    }

    @Test fun explicitLauncherOpenResumesRedirect() {
        assertTrue(HomeLaunchPolicy.shouldOpen(originalHomeActive = true, automatic = false))
        assertTrue(HomeLaunchPolicy.shouldResume(automatic = false, restoredActivity = false))
    }

    @Test fun restoringAnOldActivityDoesNotResumeTheRedirect() {
        assertFalse(HomeLaunchPolicy.shouldResume(automatic = false, restoredActivity = true))
        assertFalse(HomeLaunchPolicy.shouldResume(automatic = true, restoredActivity = true))
    }

    @Test fun automaticLauncherOpenWorksWhenOriginalHomeIsNotSelected() {
        assertTrue(HomeLaunchPolicy.shouldOpen(originalHomeActive = false, automatic = true))
    }
}
