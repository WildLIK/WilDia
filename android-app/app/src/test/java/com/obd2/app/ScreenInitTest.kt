package com.obd2.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks Screen static initialization in a plain JVM (no Robolectric). */
class ScreenJvmTest {
    @Test
    fun screenAllRoutesAreUniqueAndComplete() {
        val all = Screen.all
        assertTrue("19 routes expected, got ${all.size}", all.size == 19)
        assertEquals(all.size, all.map { it.route }.toSet().size)
        // every route resolves back to a screen (title lookup used by MainActivity)
        all.forEach { s ->
            assertEquals(s, Screen.byRoute(s.route))
            println("SCREEN ${s.route} title=${s.title} iconNull=${s.icon == null}")
        }
        assertEquals(4, Screen.bottom.size)
    }
}
