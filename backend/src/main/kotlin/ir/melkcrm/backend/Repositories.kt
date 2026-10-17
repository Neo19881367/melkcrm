package ir.melkcrm.backend

import ir.melkcrm.core.matching.MatchingConfig
import ir.melkcrm.core.model.AgencySettings
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.AuditAction
import ir.melkcrm.core.model.AuditEvent
import ir.melkcrm.core.model.UserRole
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.RecordCodec
import ir.melkcrm.core.sync.SyncRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

private val json = RecordCodec.json

object AgencyRepo {
    data class AgencyRow(val id: String, val name: String, val settings: AgencySettings, val matching: MatchingConfig)

    fun create(c: Connection, id: String, name: String, now: Long) {
        c.update(
            "INSERT INTO agencies(id, name, settings_json, matching_json, created_at) VALUES (?,?,?,?,?)",
            id, name, json.encodeToString(AgencySettings.serializer(), AgencySettings()),
            json.encodeToString(MatchingConfig.serializer(), MatchingConfig()), now,
        )
    }

    fun get(c: Connection, id: String): AgencyRow? = c.queryOne("SELECT * FROM agencies WHERE id = ?", id) {
        AgencyRow(
            it.getString("id"), it.getString("name"),
            json.decodeFromString(AgencySettings.serializer(), it.getString("settings_json")),
            json.decodeFromString(MatchingConfig.serializer(), it.getString("matching_json")),
        )
    }

    fun count(c: Connection): Int = c.queryOne("SELECT COUNT(*) AS n FROM agencies") { it.getInt("n") } ?: 0

    fun updateSettings(c: Connection, id: String, settings: AgencySettings, matching: MatchingConfig) {
        c.update(
            "UPDATE agencies SET settings_json = ?, matching_json = ? WHERE id = ?",
            json.encodeToString(AgencySettings.serializer(), settings), json.encodeToString(MatchingConfig.serializer(), matching), id,
        )
    }
}

object UserRepo {
    private fun map(rs: ResultSet) = UserRow(
        user = AgencyUser(
            id = rs.getString("id"), agencyId = rs.getString("agency_id"), displayName = rs.getString("display_name"),
            phone = rs.getString("phone"), email = rs.getString("email"), role = roleOf(rs.getString("role")),
            teamId = rs.getString("team_id"), active = rs.getBoolean("active"), createdAt = rs.getLong("created_at"),
        ),
        login = rs.getString("login"),
        passwordHash = rs.getString("password_hash"),
        failedLogins = rs.getInt("failed_logins"),
        lockedUntil = rs.longOrNull("locked_until"),
        tokenEpoch = rs.getInt("token_epoch"),
    )

    fun byLogin(c: Connection, login: String): UserRow? = c.queryOne("SELECT * FROM users WHERE login = ?", login.trim().lowercase(), map = ::map)
    fun byId(c: Connection, id: String): UserRow? = c.queryOne("SELECT * FROM users WHERE id = ?", id, map = ::map)
    fun list(c: Connection, agencyId: String): List<UserRow> = c.query("SELECT * FROM users WHERE agency_id = ? ORDER BY created_at", agencyId, map = ::map)

    fun insert(c: Connection, u: AgencyUser, login: String, passwordHash: String) {
        c.update(
            "INSERT INTO users(id, agency_id, login, display_name, phone, email, role, team_id, password_hash, active, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            u.id, u.agencyId, login.trim().lowercase(), u.displayName, u.phone, u.email, u.role.name, u.teamId, passwordHash, u.active, u.createdAt,
        )
    }

    fun update(c: Connection, id: String, displayName: String, role: UserRole, teamId: String?, active: Boolean, now: Long) {
        c.update(
            "UPDATE users SET display_name = ?, role = ?, team_id = ?, active = ?, deactivated_at = CASE WHEN ? THEN NULL ELSE COALESCE(deactivated_at, ?) END, token_epoch = token_epoch + 1 WHERE id = ?",
            displayName, role.name, teamId, active, active, now, id,
        )
    }

    fun setPassword(c: Connection, id: String, hash: String) =
        c.update("UPDATE users SET password_hash = ?, token_epoch = token_epoch + 1, failed_logins = 0, locked_until = NULL WHERE id = ?", hash, id)

    fun recordFailure(c: Connection, id: String, lockAt: Int, lockUntil: Long) {
        c.update("UPDATE users SET failed_logins = failed_logins + 1 WHERE id = ?", id)
        c.update("UPDATE users SET locked_until = ?, failed_logins = 0 WHERE id = ? AND failed_logins >= ?", lockUntil, id, lockAt)
    }

    fun recordSuccess(c: Connection, id: String) = c.update("UPDATE users SET failed_logins = 0, locked_until = NULL WHERE id = ?", id)
}

object RefreshTokenRepo {
    data class Row(val id: String, val userId: String, val familyId: String, val expiresAt: Long, val revoked: Boolean)

    fun insert(c: Connection, userId: String, tokenHash: String, familyId: String, expiresAt: Long, now: Long) {
        c.update("INSERT INTO refresh_tokens(id, user_id, token_hash, family_id, expires_at, revoked, created_at) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), userId, tokenHash, familyId, expiresAt, false, now)
    }

    fun byHash(c: Connection, hash: String): Row? = c.queryOne("SELECT * FROM refresh_tokens WHERE token_hash = ?", hash) {
        Row(it.getString("id"), it.getString("user_id"), it.getString("family_id"), it.getLong("expires_at"), it.getBoolean("revoked"))
    }

    fun revoke(c: Connection, id: String) = c.update("UPDATE refresh_tokens SET revoked = TRUE WHERE id = ?", id)
    fun revokeFamily(c: Connection, familyId: String) = c.update("UPDATE refresh_tokens SET revoked = TRUE WHERE family_id = ?", familyId)
    fun revokeAllForUser(c: Connection, userId: String) = c.update("UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = ?", userId)
}

data class StoredRecord(val record: SyncRecord, val teamId: String?, val serverSeq: Long)

object RecordRepo {
    private fun map(rs: ResultSet) = StoredRecord(
        record = SyncRecord(
            type = EntityType.valueOf(rs.getString("entity_type")),
            id = rs.getString("id"),
            agencyId = rs.getString("agency_id"),
            assignedAgentId = rs.getString("assigned_agent_id"),
            version = rs.getLong("version"),
            updatedAt = rs.getLong("updated_at"),
            deleted = rs.getBoolean("deleted"),
            data = json.decodeFromString(JsonObject.serializer(), rs.getString("data_json")),
            confidential = rs.getString("confidential_json")?.let { json.decodeFromString(JsonObject.serializer(), it) },
        ),
        teamId = rs.getString("team_id"),
        serverSeq = rs.getLong("server_seq"),
    )

    fun getForUpdate(c: Connection, agencyId: String, type: EntityType, id: String): StoredRecord? =
        c.queryOne("SELECT * FROM records WHERE agency_id = ? AND entity_type = ? AND id = ? FOR UPDATE", agencyId, type.name, id, map = ::map)

    fun get(c: Connection, agencyId: String, type: EntityType, id: String): StoredRecord? =
        c.queryOne("SELECT * FROM records WHERE agency_id = ? AND entity_type = ? AND id = ?", agencyId, type.name, id, map = ::map)

    private fun nextSeq(c: Connection): Long = c.queryOne("SELECT nextval('record_seq') AS s") { it.getLong("s") }!!

    fun insert(c: Connection, r: SyncRecord, teamId: String?, userId: String): Long {
        val seq = nextSeq(c)
        c.update(
            "INSERT INTO records(agency_id, entity_type, id, version, assigned_agent_id, team_id, updated_at, deleted, data_json, confidential_json, server_seq, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            r.agencyId, r.type.name, r.id, 1L, r.assignedAgentId, teamId, r.updatedAt, r.deleted,
            json.encodeToString(JsonObject.serializer(), r.data), r.confidential?.let { json.encodeToString(JsonObject.serializer(), it) }, seq, userId,
        )
        return 1L
    }

    /** Optimistic update: only succeeds when the stored version still equals [expectedVersion]. */
    fun update(c: Connection, r: SyncRecord, teamId: String?, expectedVersion: Long, userId: String): Long? {
        val seq = nextSeq(c)
        val n = c.update(
            "UPDATE records SET version = ?, assigned_agent_id = ?, team_id = ?, updated_at = ?, deleted = ?, data_json = ?, confidential_json = ?, server_seq = ?, updated_by = ? WHERE agency_id = ? AND entity_type = ? AND id = ? AND version = ?",
            expectedVersion + 1, r.assignedAgentId, teamId, r.updatedAt, r.deleted,
            json.encodeToString(JsonObject.serializer(), r.data), r.confidential?.let { json.encodeToString(JsonObject.serializer(), it) },
            seq, userId, r.agencyId, r.type.name, r.id, expectedVersion,
        )
        return if (n == 1) expectedVersion + 1 else null
    }

    fun changesSince(c: Connection, agencyId: String, cursor: Long, limit: Int): List<StoredRecord> =
        c.query("SELECT * FROM records WHERE agency_id = ? AND server_seq > ? ORDER BY server_seq LIMIT ?", agencyId, cursor, limit, map = ::map)

    fun allOfType(c: Connection, agencyId: String, type: EntityType): List<StoredRecord> =
        c.query("SELECT * FROM records WHERE agency_id = ? AND entity_type = ? AND deleted = FALSE", agencyId, type.name, map = ::map)

    /** Keeps team_id denormalized when an agent changes team. */
    fun updateTeamForAgent(c: Connection, agencyId: String, agentId: String, teamId: String?) =
        c.update("UPDATE records SET team_id = ? WHERE agency_id = ? AND assigned_agent_id = ?", teamId, agencyId, agentId)
}

object AppliedChangeRepo {
    fun get(c: Connection, changeId: String): String? = c.queryOne("SELECT result_json FROM applied_changes WHERE change_id = ?", changeId) { it.getString("result_json") }
    fun put(c: Connection, changeId: String, agencyId: String, userId: String, resultJson: String, now: Long) =
        c.update("INSERT INTO applied_changes(change_id, agency_id, user_id, result_json, created_at) VALUES (?,?,?,?,?)", changeId, agencyId, userId, resultJson, now)
}

object AuditRepo {
    private val changesSer = MapSerializer(String.serializer(), ListSerializer(String.serializer().nullable))

    fun insert(c: Connection, e: AuditEvent) {
        c.update(
            "INSERT INTO audit_events(id, agency_id, actor_user_id, entity_type, entity_id, action, changes_json, note, at) VALUES (?,?,?,?,?,?,?,?,?)",
            e.id, e.agencyId, e.actorUserId, e.entityType, e.entityId, e.action.name,
            if (e.changes.isEmpty()) null else json.encodeToString(changesSer, e.changes), e.note?.take(500), e.at,
        )
    }

    fun log(c: Connection, agencyId: String, actor: String?, type: String, id: String, action: AuditAction, now: Long, note: String? = null, changes: Map<String, List<String?>> = emptyMap()) =
        insert(c, AuditEvent(UUID.randomUUID().toString(), agencyId, actor, type, id, action, changes, note, now))

    fun list(c: Connection, agencyId: String, entityId: String?, limit: Int): List<AuditEvent> {
        val sql = if (entityId != null) "SELECT * FROM audit_events WHERE agency_id = ? AND entity_id = ? ORDER BY at DESC LIMIT ?"
        else "SELECT * FROM audit_events WHERE agency_id = ? ORDER BY at DESC LIMIT ?"
        val args: Array<Any?> = if (entityId != null) arrayOf(agencyId, entityId, limit) else arrayOf(agencyId, limit)
        return c.query(sql, *args) {
            AuditEvent(
                it.getString("id"), it.getString("agency_id"), it.getString("actor_user_id"), it.getString("entity_type"),
                it.getString("entity_id"), AuditAction.valueOf(it.getString("action")),
                it.getString("changes_json")?.let { j -> json.decodeFromString(changesSer, j) } ?: emptyMap(),
                it.getString("note"), it.getLong("at"),
            )
        }
    }
}
