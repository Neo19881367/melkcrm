package ir.melkcrm.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import ir.melkcrm.client.geo.GeocodingException
import ir.melkcrm.client.geo.NeshanGeocoder
import ir.melkcrm.client.reminders.Reminder
import ir.melkcrm.client.reminders.ReminderPlanner
import ir.melkcrm.client.remote.InMemoryTokenStorage
import ir.melkcrm.client.remote.KtorRemoteApi
import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.client.remote.StoredTokens
import ir.melkcrm.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkAndRemindersTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val me = """{"id":"agent1","agencyId":"A","displayName":"Agent","role":"AGENT","createdAt":0}"""

    @Test fun expiredAccessTokenIsRefreshedOnceAndRotated() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath} ${req.headers[HttpHeaders.Authorization]}"
            when (req.url.encodedPath) {
                "/api/v1/me" -> if (req.headers[HttpHeaders.Authorization] == "Bearer new") respond(me, HttpStatusCode.OK, json)
                    else respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, json)
                "/api/v1/auth/refresh" -> respond("""{"accessToken":"new","refreshToken":"r2","expiresInSeconds":900,"user":$me}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val tokens = InMemoryTokenStorage(StoredTokens("old", "r1", Fx.NOW + 600_000))
        val api = KtorRemoteApi("https://crm.example.ir", HttpClient(engine), tokens) { Fx.NOW }
        assertEquals("agent1", api.me().id)
        assertEquals(listOf("GET /api/v1/me Bearer old", "POST /api/v1/auth/refresh null", "GET /api/v1/me Bearer new"), seen)
        assertEquals(StoredTokens("new", "r2", Fx.NOW + 900_000), tokens.load())
    }

    @Test fun refusedRefreshEndsTheSession() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath == "/api/v1/auth/refresh") respond("""{"error":"invalid_refresh_token"}""", HttpStatusCode.Unauthorized, json)
            else respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, json)
        }
        val tokens = InMemoryTokenStorage(StoredTokens("old", "r1", Fx.NOW - 1))
        val api = KtorRemoteApi("https://crm.example.ir", HttpClient(engine), tokens) { Fx.NOW }
        val e = assertFailsWith<RemoteException> { api.pull(0) }
        assertEquals(401, e.status)
        assertNull(tokens.load())
    }

    @Test fun serverErrorsCarryTheirCode() = runTest {
        val engine = MockEngine { respond("""{"error":"export_forbidden"}""", HttpStatusCode.Forbidden, json) }
        val api = KtorRemoteApi("https://x.ir", HttpClient(engine), InMemoryTokenStorage(StoredTokens("a", "r", Fx.NOW + 600_000))) { Fx.NOW }
        val e = assertFailsWith<RemoteException> { api.exportCustomersCsv(ir.melkcrm.core.time.AppLanguage.PERSIAN) }
        assertTrue(e.isForbidden)
        assertEquals("export_forbidden", e.code)
    }

    @Test fun neshanSearchSendsTheServiceKeyAndParsesResults() = runTest {
        var key: String? = null
        var query: String? = null
        val engine = MockEngine { req ->
            key = req.headers["Api-Key"]
            query = req.url.parameters["term"]
            respond(
                """{"count":1,"items":[{"title":"میدان کاج","address":"تهران، سعادت آباد","neighbourhood":"سعادت آباد","region":"استان تهران، تهران","location":{"x":51.3772,"y":35.7781}}]}""",
                HttpStatusCode.OK, json,
            )
        }
        var configured: String? = null
        val geo = NeshanGeocoder(HttpClient(engine), { configured })
        assertFalse(geo.available, "no key means manual pin placement only")
        assertFailsWith<GeocodingException> { geo.search("کاج", GeoPoint(35.7, 51.4)) }
        configured = "service.abc"
        val r = geo.search("میدان کاج", GeoPoint(35.7, 51.4)).single()
        assertEquals("service.abc", key)
        assertEquals("میدان کاج", query)
        assertEquals(GeoPoint(35.7781, 51.3772), r.point)
        assertEquals("سعادت آباد", r.neighborhood)
        assertEquals("تهران", r.city)
    }

    @Test fun remindersFireOnceInTheirWindow() {
        val hour = 3_600_000L
        val task = TaskItem("t1", "A", "تماس", 0, reminderAt = Fx.NOW + hour, createdAt = 0, updatedAt = 0)
        val done = task.copy(id = "t2", status = TaskStatus.DONE)
        val appt = Appointment("a1", "A", AppointmentType.VIEWING, "بازدید", Fx.NOW + 4 * hour, reminderMinutesBefore = 60, updatedAt = 0)
        val cancelled = appt.copy(id = "a2", status = AppointmentStatus.CANCELLED)
        val first = ReminderPlanner.due(listOf(task, done), listOf(appt, cancelled), Fx.NOW, Fx.NOW + 2 * hour)
        assertEquals(listOf("t1"), first.map { it.entityId })
        val second = ReminderPlanner.due(listOf(task, done), listOf(appt, cancelled), Fx.NOW + 2 * hour, Fx.NOW + 4 * hour)
        assertEquals(listOf(Reminder.Kind.APPOINTMENT), second.map { it.kind })
        assertEquals(Fx.NOW + 4 * hour, second.single().eventAt)
    }

    @Test fun overdueCountsTasksAndCustomerFollowUps() {
        val today = 20_000L
        val tasks = listOf(
            TaskItem("t1", "A", "x", today - 1, createdAt = 0, updatedAt = 0),
            TaskItem("t2", "A", "x", today, createdAt = 0, updatedAt = 0),
            TaskItem("t3", "A", "x", today - 5, status = TaskStatus.DONE, createdAt = 0, updatedAt = 0),
        )
        val customers = listOf(
            Customer("c1", "A", "x", registeredAt = 0, updatedAt = 0, nextFollowUpDate = today - 2),
            Customer("c2", "A", "x", registeredAt = 0, updatedAt = 0, nextFollowUpDate = today - 2, status = CustomerStatus.LOST.name),
        )
        assertEquals(2, ReminderPlanner.overdueCount(tasks, customers, today))
    }
}
