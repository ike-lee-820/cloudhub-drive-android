package com.cloudhub.drive

import com.cloudhub.drive.core.DriveApp
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp", application = DriveApp::class)
class StartupSmokeTest {

    @Test
    fun applicationCreatesWithoutCrashing() {
        val app = RuntimeEnvironment.getApplication() as DriveApp
        assertNotNull(app)
        assertNotNull(app.store)
    }

    @Test
    fun mainActivityStartsWithoutCrashing() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            assertNotNull(controller.get())
        }
    }
}
