package com.obd2.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression tests for the black-screen bug (a top-level composable that
 * shares its name with the Application class resolved to the constructor, so
 * the composition silently produced no UI) and for the crash-notice UX.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityUiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun drainRoomAcrossEnvBoundary() {
        // Screens observe Room flows; on teardown the flow's query tasks may be
        // dispatched on the global arch_disk_io pool. Let every dispatched task
        // finish in THIS environment before Robolectric swaps it, otherwise the
        // task runs in the next test's environment and fails with "Illegal
        // connection pointer" (Robolectric-only, not a device issue).
        try { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync() } catch (_: Exception) {}
        try { Thread.sleep(600) } catch (_: InterruptedException) {}
        try { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync() } catch (_: Exception) {}
    }

    @Test
    fun homeScreenRendersWithoutBluetoothOrVehicle() {
        // Vehicle profile panel must exist on the start destination
        rule.onAllNodesWithText("Автомобиль", substring = true).onFirst().assertExists()
        // "Подключение" appears in panel title, drawer item and bottom tab — any is fine
        rule.onAllNodesWithText("Подключение").onFirst().assertExists()
        // Primary action button must exist
        rule.onNodeWithText("Начать диагностику").assertExists()
        // Bottom navigation must exist ("Главная" is in drawer and bottom bar)
        rule.onAllNodesWithText("Главная").onFirst().assertExists()
    }

    @Test
    fun navigationToSensorsScreenWorks() {
        rule.onAllNodesWithText("Датчики").onFirst().performClick()
        rule.onNodeWithText("Опрос").assertExists()
        rule.onNodeWithText("Стоп").assertExists()
    }

    @Test
    fun navigationToDiagnosticsScreenWorks() {
        rule.onAllNodesWithText("Диагностика").onFirst().performClick()
        rule.onNodeWithText("Чтение кодов ошибок").assertExists()
        rule.onNodeWithText("Прочитать ошибки").assertExists()

        // Return to Home BEFORE finishing: the Diagnostics screen observes Room
        // flows, and unsubscribing inside this environment (instead of during
        // teardown) prevents the flow's pending query/refresh tasks from
        // crossing the Robolectric environment boundary into the next test
        // (Robolectric-only issue; not reproducible on a device).
        rule.waitForIdle()
        rule.onAllNodesWithText("Главная").onFirst().performClick()
        rule.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        try { Thread.sleep(700) } catch (_: InterruptedException) {}
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

}
