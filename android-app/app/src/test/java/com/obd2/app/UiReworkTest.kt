package com.obd2.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.obd2.app.core.repo.VehicleProfile
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.screens.AllPidsScreen
import com.obd2.app.ui.screens.DtcBaseScreen
import com.obd2.app.ui.screens.SettingsScreen
import com.obd2.app.ui.screens.VehicleScreen
import com.obd2.app.ui.theme.Obd2Theme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI regression tests for the driver-oriented rework:
 * «Параметры автомобиля» (semantic categories, search, filters, honest
 * statuses), universal «Автомобиль» (profiles, VIN status, no invented data),
 * developer-only OBD console, compact DTC filters. Everything renders without
 * a connected adapter; demo data is never presented as real.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UiReworkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var vm: ObdViewModel

    @Before
    fun setUp() {
        vm = ObdViewModel(ApplicationProvider.getApplicationContext())
    }


    /** Waits for a condition, pumping the main looper (DataStore flows on Robolectric). */
    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(50)
        }
        assertTrue(condition())
    }

    private fun compose(content: @Composable () -> Unit) {
        composeRule.setContent { Obd2Theme { content() } }
        composeRule.waitForIdle()
    }

    // ---------------- 1. Параметры автомобиля ----------------

    @Test
    fun paramsScreenShowsSemanticCategoriesSearchAndHidesDecoderTypes() {
        compose { AllPidsScreen(vm) }
        waitFor { vm.pidRegistry.value.isNotEmpty() }
        composeRule.waitForIdle()

        listOf("Двигатель", "Температуры", "Топливо", "Электрика", "Экология", "Прочее")
            .forEach { cat -> composeRule.onNodeWithText(cat).assertExists() }
        composeRule.onNodeWithText("Поиск параметра").assertExists()
        composeRule.onNodeWithText("Доступные").assertExists()
        composeRule.onNodeWithText("Избранное").assertExists()

        // first row of «Двигатель» is visible with a plain-language explanation
        composeRule.onNodeWithText("Насколько сильно загружен", substring = true).assertExists()

        // decoder types must never be shown as descriptions
        assertEquals(0, composeRule.onAllNodesWithText("bitmap", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("encoded", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun paramsScreenFavoritesFilterUsesSavedFavorites() {
        runBlocking { vm.settings.setPidFavorite("01 04", true) }
        waitFor { vm.settingsFlow.value.pidFavorites.contains("01 04") }

        compose { AllPidsScreen(vm) }
        waitFor { vm.pidRegistry.value.isNotEmpty() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Избранное").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Расчётная нагрузка", substring = true).assertExists()
    }

    @Test
    fun paramsScreenAvailableFilterHintsWithoutAdapter() {
        compose { AllPidsScreen(vm) }
        composeRule.onNodeWithText("Доступные").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Сначала проверьте поддержку", substring = true).assertExists()
    }

    // ---------------- 2. Автомобиль ----------------

    @Test
    fun vehicleScreenShowsBuiltinSkodaProfileOnlyForIt() {
        runBlocking {
            vm.settings.setDevMode(true)
            vm.settings.addProfile(TestVehicleFixtures.skodaOctaviaA5Profile())
            vm.settings.setActiveProfile(TestVehicleFixtures.skodaOctaviaA5Profile().id)
        }
        waitFor { vm.settingsFlow.value.devMode && vm.activeProfileId.value == TestVehicleFixtures.skodaOctaviaA5Profile().id }
        compose { VehicleScreen(vm) }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodesWithText("Octavia A5", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // built-in profile carries the owner's VCDS facts only in dev mode (scroll down to them)
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("06A 906 033 KD").assertExists()
        runBlocking {
            vm.settings.setDevMode(false)
            vm.settings.clearDemoProfiles()
        }
    }

    @Test
    fun vehicleScreenNeverAppliesSkodaProfileToAnotherCar() {
        runBlocking {
            vm.settings.addProfile(
                VehicleProfile(
                    id = "user-test", name = "Лада Веста",
                    maker = "Lada", model = "Vesta", year = "2019",
                    identityStatus = VehicleProfile.ID_MANUAL,
                )
            )
            vm.settings.setActiveProfile("user-test")
        }
        compose { VehicleScreen(vm) }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodesWithText("Лада Веста", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        // VCDS data must NOT leak into a user profile
        assertEquals(0, composeRule.onAllNodesWithText("06A 906 033 KD").fetchSemanticsNodes().size)
        // honest unknowns instead of invented data
        composeRule.onNodeWithText("не определено", substring = true).assertExists()
        composeRule.onNodeWithText("Идентификация: выбрано вручную", substring = true).assertExists()
    }

    @Test
    fun vehicleScreenVinStatusIsHonestWithoutAdapter() {
        runBlocking {
            vm.settings.addProfile(
                VehicleProfile(
                    id = "user-vin-check", name = "Мой автомобиль",
                    identityStatus = VehicleProfile.ID_MANUAL,
                )
            )
            vm.settings.setActiveProfile("user-vin-check")
        }
        waitFor { vm.activeProfileId.value == "user-vin-check" }
        compose { VehicleScreen(vm) }
        repeat(3) {
            composeRule.onRoot().performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        // value node for VIN is exactly «не определён» (fields use «не определено»)
        assertTrue(composeRule.onAllNodesWithText("не определён").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("комплектация и блоки НЕ выдумываются", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ---------------- 3. Developer console ----------------

    @Test
    fun devConsoleHiddenByDefaultAndAvailableInDevMode() {
        runBlocking { vm.settings.setDevMode(false) }
        waitFor { !vm.settingsFlow.value.devMode }
        compose { SettingsScreen(vm) }
        composeRule.onNodeWithText("Для разработчиков").assertExists()
        composeRule.onNodeWithText("Режим разработчика").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Консоль OBD2").fetchSemanticsNodes().size)

        runBlocking { vm.settings.setDevMode(true) }
        waitFor { vm.settingsFlow.value.devMode }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Консоль OBD2").assertExists()
        composeRule.onNodeWithText("Например: 01 0C").assertExists()
        runBlocking { vm.settings.setDevMode(false) }
    }

    // ---------------- 4. База ошибок ----------------

    @Test
    fun dtcBaseShowsCompactRuFiltersAndKeepsJ2012InReference() {
        compose { DtcBaseScreen(vm) }
        composeRule.onNodeWithText("Все системы").assertExists()
        composeRule.onNodeWithText("Двигатель/АКПП").assertExists()
        composeRule.onNodeWithText("Шасси").assertExists()
        // the long English J2012 list is NOT on the main view
        assertEquals(0, composeRule.onAllNodesWithText("Fuel and Air Metering", substring = true).fetchSemanticsNodes().size)
        // full classification lives behind the reference toggle
        composeRule.onNodeWithText("Справка: классификация SAE J2012").performClick()
        waitFor { composeRule.onAllNodesWithText("Fuel and Air Metering", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
