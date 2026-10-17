package ir.melkcrm.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.activities.ActivitiesScreen
import ir.melkcrm.app.ui.activities.ActivityEditScreen
import ir.melkcrm.app.ui.activities.AppointmentEditScreen
import ir.melkcrm.app.ui.activities.TaskEditScreen
import ir.melkcrm.app.ui.customers.CustomerDetailScreen
import ir.melkcrm.app.ui.customers.CustomerEditScreen
import ir.melkcrm.app.ui.customers.CustomerListScreen
import ir.melkcrm.app.ui.customers.DuplicatesScreen
import ir.melkcrm.app.ui.customers.RequirementEditScreen
import ir.melkcrm.app.ui.customers.SellerEditScreen
import ir.melkcrm.app.ui.dashboard.DashboardScreen
import ir.melkcrm.app.ui.deals.DealScreen
import ir.melkcrm.app.ui.deals.OfferEditScreen
import ir.melkcrm.app.ui.map.MapScreen
import ir.melkcrm.app.ui.matching.BuyersForPropertyScreen
import ir.melkcrm.app.ui.matching.CompareScreen
import ir.melkcrm.app.ui.matching.MatchDetailScreen
import ir.melkcrm.app.ui.matching.MatchingHomeScreen
import ir.melkcrm.app.ui.matching.PropertiesForRequirementScreen
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.app.ui.nav.Routes
import ir.melkcrm.app.ui.properties.PropertyDetailScreen
import ir.melkcrm.app.ui.properties.PropertyEditScreen
import ir.melkcrm.app.ui.properties.PropertyListScreen
import ir.melkcrm.app.ui.reports.ReportsScreen
import ir.melkcrm.app.ui.settings.AccountScreen
import ir.melkcrm.app.ui.settings.CommissionRulesScreen
import ir.melkcrm.app.ui.settings.HistoryScreen
import ir.melkcrm.app.ui.settings.MapSettingsScreen
import ir.melkcrm.app.ui.settings.MatchingSettingsScreen
import ir.melkcrm.app.ui.settings.SettingsScreen
import ir.melkcrm.app.ui.settings.SyncScreen
import ir.melkcrm.app.ui.settings.UsersScreen
import ir.melkcrm.app.ui.theme.MelkTheme
import ir.melkcrm.client.settings.AppSettings
import kotlinx.coroutines.launch

private data class TopDestination(val route: String, val label: Int, val icon: ImageVector)

private val destinations = listOf(
    TopDestination(Routes.DASHBOARD, R.string.nav_dashboard, Icons.Filled.Dashboard),
    TopDestination(Routes.CUSTOMERS, R.string.nav_customers, Icons.Filled.People),
    TopDestination(Routes.PROPERTIES, R.string.nav_properties, Icons.Filled.HomeWork),
    TopDestination(Routes.MATCHING, R.string.nav_matching, Icons.Filled.Insights),
    TopDestination(Routes.ACTIVITIES, R.string.nav_activities, Icons.Filled.Event),
    TopDestination(Routes.REPORTS, R.string.nav_reports, Icons.Filled.BarChart),
    TopDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(app: AppContainer, pendingRoute: String?, onRouteConsumed: () -> Unit, onLanguageChanged: () -> Unit) {
    val initial = remember { AppSettings(language = ir.melkcrm.app.data.LocaleStore.load(app.context)) }
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = initial)
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val fmt = remember(settings, agency.settings.customStatuses) { UiFormat(settings, agency.settings.customStatuses) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val context = LocalContext.current

    // The activity's resources are in the language chosen at start; recreate when it changes.
    LaunchedEffect(settings.language) {
        if (settings.language != initial.language) onLanguageChanged()
    }

    MelkTheme {
        CompositionLocalProvider(LocalApp provides app, LocalFormat provides fmt, LocalSnackbar provides snackbar) {
            LaunchedEffect(Unit) { app.messages.collect { snackbar.showSnackbar(context.text(it)) } }
            val controller = rememberNavController()
            LaunchedEffect(pendingRoute) {
                if (pendingRoute != null) {
                    val route = when {
                        pendingRoute.startsWith("task/") -> Routes.taskEdit(pendingRoute.removePrefix("task/"))
                        pendingRoute.startsWith("appointment/") -> Routes.appointmentEdit(pendingRoute.removePrefix("appointment/"))
                        else -> pendingRoute
                    }
                    runCatching { controller.navigate(route) }
                    onRouteConsumed()
                }
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    AdaptiveShell(controller)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
                }
            }
        }
    }
}

/**
 * Navigation adapts to the width: a modal drawer on phones, a navigation rail on small tablets
 * and a permanent drawer on large tablets.
 */
@Composable
private fun AdaptiveShell(controller: NavHostController) {
    val width = LocalConfiguration.current.screenWidthDp
    val entry by controller.currentBackStackEntryAsState()
    val current = Routes.topLevelOf(entry?.destination?.route)
    val goTop: (String) -> Unit = { r -> Nav(controller, null).top(r) }
    when {
        width >= 840 -> PermanentNavigationDrawer(drawerContent = {
            PermanentDrawerSheet(Modifier.width(240.dp)) { DrawerItems(current) { goTop(it) } }
        }) { NavGraph(Nav(controller, null)) }

        width >= 600 -> Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(12.dp))
                for (d in destinations) {
                    NavigationRailItem(
                        selected = current == d.route,
                        onClick = { goTop(d.route) },
                        icon = { Icon(d.icon, null) },
                        label = { Text(stringResource(d.label), maxLines = 1) },
                    )
                }
            }
            Box(Modifier.weight(1f)) { NavGraph(Nav(controller, null)) }
        }

        else -> {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet {
                        DrawerItems(current) { r -> scope.launch { drawer.close() }; goTop(r) }
                    }
                },
            ) {
                NavGraph(Nav(controller) { scope.launch { drawer.open() } })
            }
        }
    }
}

@Composable
private fun DrawerItems(current: String, onSelect: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        for (d in destinations) {
            NavigationDrawerItem(
                label = { Text(stringResource(d.label)) },
                icon = { Icon(d.icon, null) },
                selected = current == d.route,
                onClick = { onSelect(d.route) },
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)
private fun NavBackStackEntry.id(name: String = "id"): String? = arg(name)?.takeIf { it != Routes.NEW }

private val optionalArg = { name: String ->
    navArgument(name) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
}

@Composable
private fun NavGraph(nav: Nav) {
    NavHost(nav.controller, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(nav) }

        composable(Routes.CUSTOMERS) { CustomerListScreen(nav) }
        composable("customer/{id}") { CustomerDetailScreen(it.arg("id").orEmpty(), nav) }
        composable("customer-edit/{id}") { CustomerEditScreen(it.id(), nav) }
        composable("requirement-edit/{customerId}/{id}") { RequirementEditScreen(it.arg("customerId").orEmpty(), it.id(), nav) }
        composable("seller-edit/{propertyId}/{id}") { SellerEditScreen(it.arg("propertyId").orEmpty(), it.id(), nav) }
        composable(Routes.DUPLICATES) { DuplicatesScreen(nav) }

        composable(Routes.PROPERTIES) { PropertyListScreen(nav) }
        composable("property/{id}") { PropertyDetailScreen(it.arg("id").orEmpty(), nav) }
        composable("property-edit/{id}?owner={owner}", arguments = listOf(optionalArg("owner"))) {
            PropertyEditScreen(it.id(), it.arg("owner"), nav)
        }
        composable(Routes.MAP) { MapScreen(nav) }

        composable(Routes.MATCHING) { MatchingHomeScreen(nav) }
        composable("buyers/{propertyId}") { BuyersForPropertyScreen(it.arg("propertyId").orEmpty(), nav) }
        composable("props-for/{requirementId}") { PropertiesForRequirementScreen(it.arg("requirementId").orEmpty(), nav) }
        composable("match/{requirementId}/{propertyId}") { MatchDetailScreen(it.arg("requirementId").orEmpty(), it.arg("propertyId").orEmpty(), nav) }
        composable("compare/{requirementId}/{propertyIds}") {
            CompareScreen(it.arg("requirementId").orEmpty(), it.arg("propertyIds").orEmpty().split(',').filter { s -> s.isNotBlank() }, nav)
        }

        composable(Routes.ACTIVITIES) { ActivitiesScreen(nav) }
        composable("activity-edit/{id}?customer={customer}&property={property}", arguments = listOf(optionalArg("customer"), optionalArg("property"))) {
            ActivityEditScreen(it.id(), it.arg("customer"), it.arg("property"), nav)
        }
        composable("task-edit/{id}?customer={customer}&property={property}", arguments = listOf(optionalArg("customer"), optionalArg("property"))) {
            TaskEditScreen(it.id(), it.arg("customer"), it.arg("property"), nav)
        }
        composable("appointment-edit/{id}?customer={customer}&property={property}", arguments = listOf(optionalArg("customer"), optionalArg("property"))) {
            AppointmentEditScreen(it.id(), it.arg("customer"), it.arg("property"), nav)
        }
        composable("deal/{id}") { DealScreen(it.arg("id").orEmpty(), nav) }
        composable("offer-edit/{dealId}?counter={counter}&offer={offer}", arguments = listOf(optionalArg("counter"), optionalArg("offer"))) {
            OfferEditScreen(it.arg("dealId").orEmpty(), it.arg("counter"), it.arg("offer"), nav)
        }

        composable(Routes.REPORTS) { ReportsScreen(nav) }

        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable("settings/account") { AccountScreen(nav) }
        composable("settings/sync") { SyncScreen(nav) }
        composable("settings/matching") { MatchingSettingsScreen(nav) }
        composable("settings/commission") { CommissionRulesScreen(nav) }
        composable("settings/map") { MapSettingsScreen(nav) }
        composable("settings/users") { UsersScreen(nav) }
        composable("history/{entityId}") { HistoryScreen(it.arg("entityId").orEmpty(), nav) }
    }
}
