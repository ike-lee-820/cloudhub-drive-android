package com.cloudhub.drive

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class StartupSmokeTest {

    @Test
    fun mainActivityStartsWithoutCrashing() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            assertNotNull(controller.get())
        }
    }
}
