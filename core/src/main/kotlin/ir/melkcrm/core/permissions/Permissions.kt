package ir.melkcrm.core.permissions

import ir.melkcrm.core.model.AgencySettings
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.UserRole

enum class Permission {
    MANAGE_USERS, MANAGE_SETTINGS, CONFIGURE_MATCHING, MANAGE_COMMISSION_RULES,
    VIEW_ALL_RECORDS, VIEW_TEAM_RECORDS, REASSIGN_RECORDS, VIEW_CONFIDENTIAL_ALL,
    VIEW_REPORTS, VIEW_TEAM_REPORTS, EXPORT_DATA, MERGE_CUSTOMERS, DELETE_RECORDS, APPROVE_OPERATIONS,
    MANAGE_OWN_RECORDS, RUN_MATCHING, RECORD_OFFERS, RECORD_ACTIVITIES,
}

object RolePermissions {
    private val agent = setOf(
        Permission.MANAGE_OWN_RECORDS, Permission.RUN_MATCHING, Permission.RECORD_OFFERS, Permission.RECORD_ACTIVITIES,
    )
    private val manager = agent + setOf(
        Permission.VIEW_TEAM_RECORDS, Permission.REASSIGN_RECORDS, Permission.VIEW_TEAM_REPORTS,
        Permission.APPROVE_OPERATIONS, Permission.MERGE_CUSTOMERS,
    )
    private val admin = Permission.entries.toSet()

    fun of(role: UserRole): Set<Permission> = when (role) {
        UserRole.OWNER_ADMIN -> admin
        UserRole.MANAGER -> manager
        UserRole.AGENT -> agent
    }
}

/** The ownership facts of a record that authorization decisions need. */
data class RecordScope(
    val agencyId: String,
    val assignedAgentId: String?,
    /** Team of the assigned agent, if any. */
    val teamId: String? = null,
    /** Properties are agency inventory; customers are personal to their agent by default. */
    val isInventory: Boolean = false,
)

enum class Access { NONE, MASKED, READ, WRITE }

/**
 * Single source of authorization rules, enforced by the backend on every request and mirrored
 * by the app only to hide UI. The UI is never the enforcement point.
 */
class PermissionPolicy(private val settings: AgencySettings = AgencySettings()) {

    fun has(user: AgencyUser, p: Permission): Boolean = user.active && p in RolePermissions.of(user.role)

    private fun sameTeam(user: AgencyUser, scope: RecordScope): Boolean =
        user.teamId != null && user.teamId == scope.teamId

    fun access(user: AgencyUser, scope: RecordScope): Access {
        if (!user.active || user.agencyId != scope.agencyId) return Access.NONE
        if (has(user, Permission.VIEW_ALL_RECORDS)) return Access.WRITE
        if (scope.assignedAgentId == user.id) return Access.WRITE
        if (scope.assignedAgentId == null && has(user, Permission.MANAGE_OWN_RECORDS)) return Access.WRITE // unassigned pool
        if (has(user, Permission.VIEW_TEAM_RECORDS) && sameTeam(user, scope)) return Access.WRITE
        return when {
            scope.isInventory && settings.agentsSeeAgencyInventory -> Access.READ
            !scope.isInventory && settings.agentsSeeAgencyCustomers -> Access.MASKED
            else -> Access.NONE
        }
    }

    fun canRead(user: AgencyUser, scope: RecordScope) = access(user, scope) >= Access.MASKED
    fun canWrite(user: AgencyUser, scope: RecordScope) = access(user, scope) == Access.WRITE

    /** Contact details (phone, email, address) are visible only with full read access. */
    fun canSeeContactInfo(user: AgencyUser, scope: RecordScope) = access(user, scope) >= Access.READ

    /** Seller minimum price, confidential notes, negotiation ranges. */
    fun canSeeConfidential(user: AgencyUser, scope: RecordScope): Boolean {
        if (!user.active || user.agencyId != scope.agencyId) return false
        if (has(user, Permission.VIEW_CONFIDENTIAL_ALL)) return true
        if (scope.assignedAgentId == user.id) return true
        return has(user, Permission.VIEW_TEAM_RECORDS) && sameTeam(user, scope)
    }

    fun canReassign(user: AgencyUser, scope: RecordScope): Boolean =
        user.agencyId == scope.agencyId && (has(user, Permission.VIEW_ALL_RECORDS) ||
            (has(user, Permission.REASSIGN_RECORDS) && sameTeam(user, scope)))

    fun canDelete(user: AgencyUser, scope: RecordScope) = user.agencyId == scope.agencyId && has(user, Permission.DELETE_RECORDS)

    fun canExport(user: AgencyUser) = has(user, Permission.EXPORT_DATA) || (settings.agentsCanExport && user.active)

    fun canMerge(user: AgencyUser) = has(user, Permission.MERGE_CUSTOMERS)

    fun canConfigureMatching(user: AgencyUser) = has(user, Permission.CONFIGURE_MATCHING)

    fun canManageUsers(user: AgencyUser) = has(user, Permission.MANAGE_USERS)

    fun canViewReports(user: AgencyUser, agencyWide: Boolean) =
        if (agencyWide) has(user, Permission.VIEW_REPORTS) else user.active // own figures
}
