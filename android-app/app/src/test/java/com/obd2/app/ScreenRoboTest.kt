package com.obd2.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checks Screen static initialization inside the Robolectric environment. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreenRoboTest {
    @Test
    fun screenAllRoutesAreCompleteInRobolectric() {
        val all = Screen.all
        println("ALL_SIZE=${all.size}")
        all.forEach { s -> println("ALL=${s.route}") }
        assertFalse(all.isEmpty())
        // Every screen route must resolve (this is where the NPE used to fire)
        all.forEach { s ->
            org.junit.Assert.assertEquals(s, Screen.byRoute(s.route))
        }
    }
}
