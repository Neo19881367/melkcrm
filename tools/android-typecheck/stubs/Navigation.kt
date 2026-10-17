package androidx.navigation

import kotlinx.coroutines.flow.Flow

open class NavDestination {
    val id: Int = 0
    val route: String? = null
}

open class NavGraph : NavDestination() {
    companion object {
        @JvmStatic fun NavGraph.findStartDestination(): NavDestination = TODO()
    }
}

class NavBackStackEntry private constructor() {
    val destination: NavDestination get() = TODO()
    val arguments: android.os.Bundle? get() = TODO()
}

class PopUpToBuilder {
    var inclusive: Boolean = false
    var saveState: Boolean = false
}

class NavOptionsBuilder {
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false
    fun popUpTo(id: Int, popUpToBuilder: PopUpToBuilder.() -> Unit = {}) {}
    fun popUpTo(route: String, popUpToBuilder: PopUpToBuilder.() -> Unit = {}) {}
}

open class NavController {
    val currentDestination: NavDestination? get() = TODO()
    val currentBackStackEntry: NavBackStackEntry? get() = TODO()
    val currentBackStackEntryFlow: Flow<NavBackStackEntry> get() = TODO()
    open var graph: NavGraph
        get() = TODO()
        set(value) {}
    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) {}
    fun navigate(route: String) {}
    open fun popBackStack(): Boolean = false
    open fun navigateUp(): Boolean = false
}

open class NavHostController : NavController()

abstract class NavType<T>(val isNullableAllowed: Boolean) {
    companion object {
        @JvmField val StringType: NavType<String?> = TODO()
        @JvmField val IntType: NavType<Int> = TODO()
        @JvmField val BoolType: NavType<Boolean> = TODO()
    }
}

class NamedNavArgument internal constructor()
class NavDeepLink internal constructor()

class NavArgumentBuilder {
    var type: NavType<*> = TODO()
    var nullable: Boolean = false
    var defaultValue: Any? = null
}

fun navArgument(name: String, builder: NavArgumentBuilder.() -> Unit): NamedNavArgument = TODO()

open class NavGraphBuilder
