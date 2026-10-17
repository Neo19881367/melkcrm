package ir.melkcrm.app.ui.nav

import android.net.Uri
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/** Route builders. Every id is URI-encoded; Navigation decodes path and query arguments. */
object Routes {
    const val NEW = "new"
    const val DASHBOARD = "dashboard"
    const val CUSTOMERS = "customers"
    const val PROPERTIES = "properties"
    const val MATCHING = "matching"
    const val ACTIVITIES = "activities"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
    const val DUPLICATES = "duplicates"
    const val MAP = "map"

    private fun e(s: String) = Uri.encode(s)
    private fun q(name: String, v: String?) = if (v == null) "" else "$name=${e(v)}"
    private fun query(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }

    fun customer(id: String) = "customer/${e(id)}"
    fun customerEdit(id: String?) = "customer-edit/${e(id ?: NEW)}"
    fun requirementEdit(customerId: String, id: String?) = "requirement-edit/${e(customerId)}/${e(id ?: NEW)}"
    fun sellerEdit(propertyId: String, id: String?) = "seller-edit/${e(propertyId)}/${e(id ?: NEW)}"
    fun property(id: String) = "property/${e(id)}"
    fun propertyEdit(id: String?, ownerId: String? = null) = "property-edit/${e(id ?: NEW)}" + query(q("owner", ownerId))
    fun buyersFor(propertyId: String) = "buyers/${e(propertyId)}"
    fun propertiesFor(requirementId: String) = "props-for/${e(requirementId)}"
    fun match(requirementId: String, propertyId: String) = "match/${e(requirementId)}/${e(propertyId)}"
    fun compare(requirementId: String, propertyIds: List<String>) = "compare/${e(requirementId)}/${e(propertyIds.joinToString(","))}"
    fun activityEdit(id: String?, customerId: String? = null, propertyId: String? = null) =
        "activity-edit/${e(id ?: NEW)}" + query(q("customer", customerId), q("property", propertyId))
    fun taskEdit(id: String?, customerId: String? = null, propertyId: String? = null) =
        "task-edit/${e(id ?: NEW)}" + query(q("customer", customerId), q("property", propertyId))
    fun appointmentEdit(id: String?, customerId: String? = null, propertyId: String? = null) =
        "appointment-edit/${e(id ?: NEW)}" + query(q("customer", customerId), q("property", propertyId))
    fun deal(id: String) = "deal/${e(id)}"
    fun offerEdit(dealId: String, counterOf: String? = null, offerId: String? = null) =
        "offer-edit/${e(dealId)}" + query(q("counter", counterOf), q("offer", offerId))
    fun history(entityId: String) = "history/${e(entityId)}"
    fun settings(section: String) = "settings/$section"

    /** Which top-level destination a route belongs to, for highlighting the navigation item. */
    fun topLevelOf(route: String?): String = when {
        route == null -> DASHBOARD
        route.startsWith("customer") || route.startsWith("requirement") || route.startsWith("seller") || route.startsWith(DUPLICATES) -> CUSTOMERS
        route.startsWith("propert") || route.startsWith(MAP) -> PROPERTIES
        route.startsWith(MATCHING) || route.startsWith("buyers") || route.startsWith("props-for") || route.startsWith("match") || route.startsWith("compare") -> MATCHING
        route.startsWith(ACTIVITIES) || route.startsWith("activity") || route.startsWith("task") || route.startsWith("appointment") || route.startsWith("deal") || route.startsWith("offer") -> ACTIVITIES
        route.startsWith(REPORTS) -> REPORTS
        route.startsWith(SETTINGS) || route.startsWith("history") -> SETTINGS
        else -> DASHBOARD
    }
}

/** Navigation actions available to every screen. */
class Nav(val controller: NavHostController, val openDrawer: (() -> Unit)?) {
    fun go(route: String) = controller.navigate(route) { launchSingleTop = true }

    /** Replaces the current screen (used after creating a record: the editor gives way to its detail screen). */
    fun replace(route: String) {
        val current = controller.currentDestination?.id
        controller.navigate(route) {
            launchSingleTop = true
            if (current != null) popUpTo(current) { inclusive = true }
        }
    }

    fun top(route: String) = controller.navigate(route) {
        popUpTo(controller.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun back() {
        if (!controller.popBackStack()) top(Routes.DASHBOARD)
    }
}
