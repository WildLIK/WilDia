package com.obd2.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.obd2.app.ui.ObdViewModel
import com.obd2.app.ui.screens.AllPidsScreen
import com.obd2.app.ui.screens.AdapterScreen
import com.obd2.app.ui.screens.AppUpdatesScreen
import com.obd2.app.ui.screens.ChartsScreen
import com.obd2.app.ui.screens.ConnectScreen
import com.obd2.app.ui.screens.DiagnosticsScreen
import com.obd2.app.ui.screens.DtcBaseScreen
import com.obd2.app.ui.screens.DtcOnlineSearchScreen
import com.obd2.app.ui.screens.ExtendedDiagScreen
import com.obd2.app.ui.screens.FullCheckScreen
import com.obd2.app.ui.screens.HomeScreen
import com.obd2.app.ui.screens.OmvlSkyScreen
import com.obd2.app.ui.screens.QuickDiagScreen
import com.obd2.app.ui.screens.SensorsScreen
import com.obd2.app.ui.screens.SettingsScreen
import com.obd2.app.ui.screens.TripRecordingsScreen
import com.obd2.app.ui.screens.UpdatesScreen
import com.obd2.app.ui.screens.VehicleScreen
import com.obd2.app.ui.theme.Obd2Theme
import kotlinx.coroutines.launch

/**
 * Route registry.
 *
 * MUST stay an enum: the previous `sealed class` + `data object` variant built
 * `all`/`bottom` inside `Screen.<clinit>` by reading static INSTANCE fields of
 * nine singletons whose own class-init chains pull in material-icons. On ART
 * (and under Robolectric) that read can happen before the singletons finish
 * initializing, putting nulls into the list → NPE on first composition
 * (crash on launch). Enum constants are plain strings; icons are resolved
 * lazily at render time.
 */
enum class Screen(val route: String, val title: String) {
    HOME("home", "Главная"),
    CONNECT("connect", "Подключение"),
    DIAGNOSTICS("diagnostics", "Диагностика"),
    QUICK_DIAG("quick_diag", "Быстрая диагностика"),
    EXTENDED_DIAG("extended_diag", "Расширенная диагностика"),
    SENSORS("sensors", "Датчики"),
    CHARTS("charts", "Графики"),
    RECORDINGS("recordings", "Записи"),
    ALLPIDS("allpids", "Параметры автомобиля"),
    DTCBASE("dtcbase", "База ошибок"),
    DTC_SEARCH("dtc_search", "Поиск причин в интернете"),
    DIAG("diag", "Диагностика подключения"),
    VEHICLE("vehicle", "Автомобиль"),
    ADAPTER("adapter", "Исследование адаптера"),
    FULLCHECK("fullcheck", "Полная проверка"),
    SETTINGS("settings", "Настройки"),
    UPDATES("updates", "Обновления баз"),
    APP_UPDATES("app_updates", "Обновления приложения"),
    OMVL_SKY("omvl_sky", "ГБО: OMVL SAVER SKY");

    val icon: ImageVector?
        get() = when (this) {
            HOME -> Icons.Filled.Home
            CONNECT -> Icons.Filled.Bluetooth
            DIAGNOSTICS, QUICK_DIAG, EXTENDED_DIAG -> Icons.Filled.WarningAmber
            SENSORS, CHARTS -> Icons.Filled.Speed
            ALLPIDS, RECORDINGS -> Icons.Filled.Memory
            DTCBASE, DTC_SEARCH, DIAG -> Icons.AutoMirrored.Filled.ListAlt
            VEHICLE -> Icons.Filled.DirectionsCar
            ADAPTER, FULLCHECK -> Icons.Filled.Tune
            OMVL_SKY -> Icons.Filled.Speed
            SETTINGS, UPDATES, APP_UPDATES -> Icons.Filled.Settings
        }

    companion object {
        val all: List<Screen> get() = entries.toList()
        val bottom: List<Screen> get() = listOf(HOME, CONNECT, SENSORS, DIAGNOSTICS)

        fun byRoute(route: String): Screen? = entries.firstOrNull { it.route == route }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOTE: the root composable is named ObdAppRoot on purpose — it must NOT
        // share a name with the Application class (ObdApp). A same-named top-level
        // fun and class make `ObdApp()` resolve to the CONSTRUCTOR (silent empty
        // composition → black screen, see CORRECTIONS_2026-11-20.md).
        //
        // A saved crash from a previous launch is NOT shown here: the container
        // reads it at startup and the UI surfaces a short notice (Home) with the
        // full report inside the Diagnostics screen instead.
        setContent {
            Obd2Theme {
                ObdAppRoot()
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ObdAppRoot(vm: ObdViewModel = viewModel()) {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Screen.HOME.route
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600
    val startElapsed = remember { android.os.SystemClock.elapsedRealtime() }

    LaunchedEffect(Unit) {
        val dt = android.os.SystemClock.elapsedRealtime() - startElapsed
        android.util.Log.i("OBD2", "app.startup.first_frame_ms=${dt}ms (wide=$isWideScreen)")
    }

    val title = Screen.byRoute(current)?.title ?: "Wildia"
    val drawerScreens = remember { Screen.all.distinctBy { it.route } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        "Wildia",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                    drawerScreens.forEach { s ->
                        NavigationDrawerItem(
                            label = { Text(s.title) },
                            icon = { s.icon?.let { Icon(it, contentDescription = null) } },
                            selected = current == s.route,
                            modifier = if (isWideScreen) Modifier.defaultMinSize(minHeight = 56.dp) else Modifier,
                            onClick = {
                                scope.launch { drawer.close() }
                                nav.navigate(s.route) { launchSingleTop = true }
                            },
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawer.open() } },
                            modifier = if (isWideScreen) Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 56.dp) else Modifier,
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "Меню")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar {
                        Screen.bottom.forEach { s ->
                            NavigationBarItem(
                                selected = current == s.route,
                                onClick = { nav.navigate(s.route) { launchSingleTop = true } },
                                icon = { s.icon?.let { Icon(it, contentDescription = s.title) } },
                                label = { Text(s.title) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val conn by vm.connection.collectAsState()
            val demoActive = (conn as? com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connected)?.demo == true ||
                (conn as? com.obd2.app.core.repo.DiagnosticRepository.ConnectionState.Connecting)?.demo == true
            Row(Modifier.padding(padding)) {
                if (isWideScreen) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Screen.bottom.forEach { s ->
                            NavigationRailItem(
                                selected = current == s.route,
                                onClick = { nav.navigate(s.route) { launchSingleTop = true } },
                                icon = { s.icon?.let { Icon(it, contentDescription = s.title) } },
                                label = { Text(s.title, maxLines = 1) },
                                alwaysShowLabel = true,
                                modifier = Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 56.dp),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                if (demoActive) {
                    androidx.compose.material3.Surface(
                        color = com.obd2.app.ui.theme.Amber.copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "ДЕМО — данные симулированы (не реальный автомобиль)",
                            color = com.obd2.app.ui.theme.Amber,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                NavHost(
                    navController = nav,
                    startDestination = Screen.HOME.route,
                    modifier = Modifier.weight(1f),
                ) {
                composable(Screen.HOME.route) { HomeScreen(vm, nav) }
                composable(Screen.CONNECT.route) { ConnectScreen(vm) }
                composable(Screen.DIAGNOSTICS.route) { DiagnosticsScreen(vm, nav) }
                composable(Screen.DTC_SEARCH.route) { DtcOnlineSearchScreen(vm) }
                composable(Screen.QUICK_DIAG.route) { QuickDiagScreen(vm) }
                composable(Screen.EXTENDED_DIAG.route) { ExtendedDiagScreen(vm) }
                composable(Screen.SENSORS.route) { SensorsScreen(vm, nav) }
                composable(Screen.CHARTS.route) { ChartsScreen(vm) }
                composable(Screen.RECORDINGS.route) { TripRecordingsScreen(vm, nav) }
                composable(Screen.ALLPIDS.route) { AllPidsScreen(vm) }
                composable(Screen.DTCBASE.route) { DtcBaseScreen(vm) }
                composable(Screen.DIAG.route) { com.obd2.app.ui.screens.DiagScreen(vm) }
                composable(Screen.VEHICLE.route) { VehicleScreen(vm) }
                composable(Screen.ADAPTER.route) { AdapterScreen(vm) }
                composable(Screen.FULLCHECK.route) { FullCheckScreen(vm) }
                composable(Screen.SETTINGS.route) { SettingsScreen(vm, nav) }
                composable(Screen.UPDATES.route) { UpdatesScreen(vm) }
                composable(Screen.APP_UPDATES.route) { AppUpdatesScreen(vm) }
                composable(Screen.OMVL_SKY.route) { OmvlSkyScreen(vm) }
                }
                }
            }
        }
    }
}

typealias Nav = NavHostController
