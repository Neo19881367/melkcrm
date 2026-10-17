package ir.melkcrm.core.permissions

import ir.melkcrm.core.model.AgencySettings
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.UserRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionPolicyTest {
    private fun user(id: String, role: UserRole, team: String? = null, agency: String = "A", active: Boolean = true) =
        AgencyUser(id, agency, id, role = role, teamId = team, active = active, createdAt = 0)

    private val admin = user("admin", UserRole.OWNER_ADMIN)
    private val manager = user("mgr", UserRole.MANAGER, team = "west")
    private val agent1 = user("a1", UserRole.AGENT, team = "west")
    private val agent2 = user("a2", UserRole.AGENT, team = "east")
    private val policy = PermissionPolicy()

    private val customerOfA1 = RecordScope("A", "a1", teamId = "west")
    private val propertyOfA1 = RecordScope("A", "a1", teamId = "west", isInventory = true)

    @Test fun agentsManageOnlyTheirOwnCustomers() {
        assertEquals(Access.WRITE, policy.access(agent1, customerOfA1))
        assertEquals(Access.NONE, policy.access(agent2, customerOfA1))
    }

    @Test fun agentsMaySeeAgencyInventoryReadOnly() {
        assertEquals(Access.READ, policy.access(agent2, propertyOfA1))
        assertFalse(policy.canWrite(agent2, propertyOfA1))
        val closed = PermissionPolicy(AgencySettings(agentsSeeAgencyInventory = false))
        assertEquals(Access.NONE, closed.access(agent2, propertyOfA1))
    }

    @Test fun customersOfOthersAreMaskedWhenSharingEnabled() {
        val p = PermissionPolicy(AgencySettings(agentsSeeAgencyCustomers = true))
        assertEquals(Access.MASKED, p.access(agent2, customerOfA1))
        assertFalse(p.canSeeContactInfo(agent2, customerOfA1))
    }

    @Test fun confidentialOnlyForAssignedAgentTeamManagerAndAdmin() {
        assertTrue(policy.canSeeConfidential(agent1, propertyOfA1))
        assertTrue(policy.canSeeConfidential(manager, propertyOfA1))
        assertTrue(policy.canSeeConfidential(admin, propertyOfA1))
        assertFalse(policy.canSeeConfidential(agent2, propertyOfA1))
    }

    @Test fun managersSuperviseOnlyTheirTeam() {
        assertTrue(policy.canReassign(manager, customerOfA1))
        assertFalse(policy.canReassign(manager, RecordScope("A", "a2", teamId = "east")))
        assertFalse(policy.canReassign(agent1, customerOfA1))
        assertTrue(policy.canReassign(admin, RecordScope("A", "a2", teamId = "east")))
    }

    @Test fun noAccessAcrossAgencies() {
        val otherAdmin = user("x", UserRole.OWNER_ADMIN, agency = "B")
        assertEquals(Access.NONE, policy.access(otherAdmin, customerOfA1))
        assertFalse(policy.canSeeConfidential(otherAdmin, propertyOfA1))
        assertFalse(policy.canDelete(otherAdmin, customerOfA1))
    }

    @Test fun deactivatedUsersHaveNoAccess() {
        val gone = user("a1", UserRole.AGENT, team = "west", active = false)
        assertEquals(Access.NONE, policy.access(gone, customerOfA1))
        assertFalse(policy.has(gone, Permission.RUN_MATCHING))
    }

    @Test fun adminOnlyOperations() {
        assertTrue(policy.canConfigureMatching(admin))
        assertFalse(policy.canConfigureMatching(manager))
        assertTrue(policy.canExport(admin))
        assertFalse(policy.canExport(agent1))
        assertTrue(PermissionPolicy(AgencySettings(agentsCanExport = true)).canExport(agent1))
        assertTrue(policy.canManageUsers(admin))
        assertFalse(policy.canManageUsers(manager))
        assertTrue(policy.canMerge(manager))
        assertFalse(policy.canMerge(agent1))
    }
}
