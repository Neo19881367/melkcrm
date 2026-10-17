package ir.melkcrm.core.model

import ir.melkcrm.core.time.EpochMillis
import kotlinx.serialization.Serializable

@Serializable enum class UserRole { OWNER_ADMIN, MANAGER, AGENT }

@Serializable
data class AgencyUser(
    val id: String,
    val agencyId: String,
    val displayName: String,
    val phone: String? = null,
    val email: String? = null,
    val role: UserRole,
    /** Managers supervise users with the same teamId. */
    val teamId: String? = null,
    val active: Boolean = true,
    val createdAt: EpochMillis,
)

@Serializable
data class AgencySettings(
    /** Whether agents can see (non-confidential) properties assigned to other agents. */
    val agentsSeeAgencyInventory: Boolean = true,
    /** Whether agents can see customers assigned to other agents (masked contact info). */
    val agentsSeeAgencyCustomers: Boolean = false,
    val agentsCanExport: Boolean = false,
    val customStatuses: List<CustomStatus> = emptyList(),
    /** Agency-defined commission rules; amounts computed from them are estimates until confirmed. */
    val commissionRules: List<ir.melkcrm.core.commission.CommissionRule> = emptyList(),
)
