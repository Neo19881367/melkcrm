package ir.melkcrm.backend

import ir.melkcrm.core.api.*
import ir.melkcrm.core.money.CurrencyUnit
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.plugins.BadRequestException
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.AuditAction
import ir.melkcrm.core.permissions.Permission
import ir.melkcrm.core.sync.EntityType
import ir.melkcrm.core.sync.RecordCodec
import ir.melkcrm.core.time.AppLanguage
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

fun main() {
    val cfg = AppConfig.fromEnv()
    embeddedServer(Netty, port = cfg.port, host = "0.0.0.0") { module(cfg) }.start(wait = true)
}

class Services(
    val cfg: AppConfig,
    val db: Db,
    val auth: AuthService,
    val sync: SyncService,
    val images: ImageStore,
    val passwords: Passwords,
    val tokens: Tokens,
    val clock: () -> Long,
)

fun buildServices(cfg: AppConfig, db: Db = Db.connect(cfg), clock: () -> Long = System::currentTimeMillis): Services {
    val passwords = Passwords(cfg.bcryptCost)
    val tokens = Tokens(cfg, clock)
    val auth = AuthService(db, cfg, passwords, tokens, clock)
    auth.bootstrap()
    return Services(cfg, db, auth, SyncService(db, clock), ImageStore(cfg, db, clock), passwords, tokens, clock)
}

private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    }
}

fun Application.module(cfg: AppConfig, services: Services = buildServices(cfg)) {
    val tokens = services.tokens
    install(ContentNegotiation) { json(RecordCodec.json) }
    install(CallLogging) {
        level = Level.INFO
        // Never log request bodies: they contain personal data.
        format { call -> "${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')} -> ${call.response.status()?.value}" }
    }
    install(SecurityHeaders)
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorResponse(e.code, e.detail)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", e.message)) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
    }
    install(RateLimit) {
        register(RateLimitName("auth")) { rateLimiter(limit = cfg.loginRateLimitPerMinute, refillPeriod = 1.minutes) }
    }
    install(Authentication) {
        jwt("jwt") {
            realm = "melkcrm"
            verifier(tokens.verifier)
            validate { credential ->
                val sub = credential.payload.subject ?: return@validate null
                val epoch = credential.payload.getClaim("epoch").asInt() ?: return@validate null
                services.auth.principalFor(sub, epoch)
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")) }
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        route("/api/v1") {
            rateLimit(RateLimitName("auth")) {
                post("/auth/login") {
                    val req = call.receive<LoginRequest>()
                    call.respond(services.auth.login(req.login, req.password))
                }
                post("/auth/refresh") { call.respond(services.auth.refresh(call.receive<RefreshRequest>().refreshToken)) }
                post("/auth/logout") {
                    services.auth.logout(call.receive<RefreshRequest>().refreshToken)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            authenticate("jwt") {
                get("/me") { call.respond(call.user()) }
                post("/me/password") {
                    val req = call.receive<ChangePasswordRequest>()
                    services.auth.changePassword(call.user(), req.currentPassword, req.newPassword)
                    call.respond(HttpStatusCode.NoContent)
                }
                syncRoutes(services)
                adminRoutes(services)
                imageRoutes(services)
                get("/export/customers.csv") {
                    val lang = AppLanguage.fromCode(call.request.queryParameters["lang"])
                    val csv = services.sync.exportCustomersCsv(call.user(), lang)
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=customers.csv")
                    call.respondText(csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
                }
                get("/export/properties.csv") {
                    val lang = AppLanguage.fromCode(call.request.queryParameters["lang"])
                    val unit = if (call.request.queryParameters["unit"] == "rial") CurrencyUnit.RIAL else CurrencyUnit.TOMAN
                    val csv = services.sync.exportPropertiesCsv(call.user(), lang, unit)
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=properties.csv")
                    call.respondText(csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
                }
            }
        }
    }
}

fun ApplicationCall.user(): AgencyUser = principal<UserPrincipal>()?.user ?: throw ApiException(401, "unauthorized")

private fun Route.syncRoutes(s: Services) {
    post("/sync/push") {
        val req = call.receive<PushRequest>()
        if (req.changes.size > 500) throw ApiException(413, "too_many_changes")
        call.respond(PushResponse(s.sync.push(call.user(), req.changes)))
    }
    get("/sync/pull") {
        val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0L
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 1000)
        call.respond(s.sync.pull(call.user(), cursor, limit))
    }
    get("/records/{type}/{id}") {
        val type = runCatching { EntityType.valueOf(call.parameters["type"]!!.uppercase()) }.getOrNull() ?: throw ApiException(400, "unknown_type")
        val rec = s.sync.get(call.user(), type, call.parameters["id"]!!) ?: throw ApiException(404, "not_found")
        call.respond(rec)
    }
}

private fun Route.adminRoutes(s: Services) {
    fun policy(user: AgencyUser) = s.db.tx { c -> s.sync.policyFor(c, user.agencyId) }

    get("/agency/config") {
        val u = call.user()
        val row = s.db.tx { c -> AgencyRepo.get(c, u.agencyId) } ?: throw ApiException(404, "not_found")
        call.respond(AgencyConfig(row.settings, row.matching))
    }
    put("/agency/config") {
        val u = call.user()
        val p = policy(u)
        if (!p.has(u, Permission.MANAGE_SETTINGS) || !p.canConfigureMatching(u)) throw ApiException(403, "forbidden")
        val req = call.receive<AgencyConfig>()
        val errors = req.matching.validate()
        if (errors.isNotEmpty()) throw ApiException(400, "invalid_matching_config", errors.joinToString("; "))
        s.db.tx { c ->
            AgencyRepo.updateSettings(c, u.agencyId, req.settings, req.matching)
            AuditRepo.log(c, u.agencyId, u.id, "AGENCY", u.agencyId, AuditAction.UPDATE, s.clock(), "config")
        }
        call.respond(req)
    }
    get("/users") {
        val u = call.user()
        val rows = s.db.tx { c -> UserRepo.list(c, u.agencyId) }
        call.respond(rows.map { it.user })
    }
    post("/users") {
        val u = call.user()
        if (!policy(u).canManageUsers(u)) throw ApiException(403, "forbidden")
        val req = call.receive<CreateUserRequest>()
        s.passwords.validateStrength(req.password)?.let { throw ApiException(400, it) }
        if (req.login.isBlank() || req.displayName.isBlank()) throw ApiException(400, "missing_fields")
        val created = s.db.tx { c ->
            if (UserRepo.byLogin(c, req.login) != null) throw ApiException(409, "login_taken")
            val nu = AgencyUser(UUID.randomUUID().toString(), u.agencyId, req.displayName, req.phone, req.email, req.role, req.teamId, true, s.clock())
            UserRepo.insert(c, nu, req.login, s.passwords.hash(req.password))
            AuditRepo.log(c, u.agencyId, u.id, "USER", nu.id, AuditAction.CREATE, s.clock(), req.role.name)
            nu
        }
        call.respond(HttpStatusCode.Created, created)
    }
    patch("/users/{id}") {
        val u = call.user()
        if (!policy(u).canManageUsers(u)) throw ApiException(403, "forbidden")
        val req = call.receive<UpdateUserRequest>()
        val id = call.parameters["id"]!!
        val updated = s.db.tx { c ->
            val target = UserRepo.byId(c, id)?.takeIf { it.user.agencyId == u.agencyId } ?: throw ApiException(404, "not_found")
            if (target.user.id == u.id && (!req.active || req.role != target.user.role)) throw ApiException(400, "cannot_demote_self")
            UserRepo.update(c, id, req.displayName, req.role, req.teamId, req.active, s.clock())
            if (target.user.teamId != req.teamId) RecordRepo.updateTeamForAgent(c, u.agencyId, id, req.teamId)
            if (!req.active) RefreshTokenRepo.revokeAllForUser(c, id)
            AuditRepo.log(c, u.agencyId, u.id, "USER", id, AuditAction.UPDATE, s.clock(),
                changes = mapOf("role" to listOf(target.user.role.name, req.role.name), "active" to listOf(target.user.active.toString(), req.active.toString())))
            UserRepo.byId(c, id)!!.user
        }
        call.respond(updated)
    }
    get("/audit") {
        val u = call.user()
        val p = policy(u)
        if (!p.has(u, Permission.VIEW_ALL_RECORDS) && !p.has(u, Permission.VIEW_TEAM_RECORDS)) throw ApiException(403, "forbidden")
        val entityId = call.request.queryParameters["entityId"]
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)
        call.respond(s.db.tx { c -> AuditRepo.list(c, u.agencyId, entityId, limit) })
    }
}

private fun Route.imageRoutes(s: Services) {
    post("/properties/{propertyId}/images") {
        val u = call.user()
        val length = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: throw ApiException(411, "length_required")
        if (length > s.cfg.maxImageBytes) throw ApiException(413, "image_too_large")
        val bytes = call.receive<ByteArray>()
        val res = s.images.upload(u, call.parameters["propertyId"]!!, bytes, call.request.header(HttpHeaders.ContentType))
        call.respond(HttpStatusCode.Created, res)
    }
    get("/images/{id}") {
        val (type, bytes) = s.images.download(call.user(), call.parameters["id"]!!)
        call.respondBytes(bytes, ContentType.parse(type))
    }
}

