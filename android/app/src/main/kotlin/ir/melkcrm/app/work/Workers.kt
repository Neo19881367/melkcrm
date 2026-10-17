package ir.melkcrm.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.container
import ir.melkcrm.client.reminders.ReminderPlanner
import ir.melkcrm.core.model.Appointment
import ir.melkcrm.core.model.TaskItem
import ir.melkcrm.core.sync.EntityType
import java.util.concurrent.TimeUnit

/** Background synchronization. Only runs when signed in to a server and a network is available. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.container
        val session = app.crm.session.session.value
        if (!session.signedIn || session.authExpired) return Result.success()
        val report = app.syncNow()
        return when (report.error) {
            null -> Result.success()
            "network" -> if (runAttemptCount < 5) Result.retry() else Result.success()
            else -> Result.success() // shown in Settings → Sync; retrying would not help
        }
    }
}

object SyncScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Debounced: each call within the delay replaces the previous request. */
    fun requestSoon(context: Context, delaySeconds: Long = 10) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-soon", ExistingWorkPolicy.REPLACE, req)
    }
}

/**
 * Task and appointment reminders as local notifications. A one-off job is planned for the next
 * reminder; a 15-minute periodic check catches anything the system delayed (Doze), so a reminder
 * can arrive late on a sleeping phone but is not lost.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.container
        val now = System.currentTimeMillis()
        val last = app.store.meta(KEY_LAST)?.toLongOrNull() ?: (now - 15 * 60_000L)
        val settings = app.currentSettings()
        if (settings.notificationsEnabled) {
            val (tasks, appointments) = Reminders.mine(app)
            for (r in ReminderPlanner.due(tasks, appointments, after = last, upTo = now)) {
                Notifications.showReminder(applicationContext, r, settings.display)
            }
        }
        app.store.setMeta(KEY_LAST, now.toString())
        Reminders.planNext(applicationContext, app)
        return Result.success()
    }

    companion object {
        const val KEY_LAST = "reminders.lastCheck"
    }
}

object Reminders {
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Plans a one-off check at the next reminder time within the coming week. */
    suspend fun planNext(context: Context, app: AppContainer) {
        val now = System.currentTimeMillis()
        val (tasks, appointments) = mine(app)
        val next = ReminderPlanner.due(tasks, appointments, after = now, upTo = now + 7 * 86_400_000L).minOfOrNull { it.at }
        val wm = WorkManager.getInstance(context)
        if (next == null) {
            wm.cancelUniqueWork("reminders-next")
            return
        }
        val req = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay((next - now).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork("reminders-next", ExistingWorkPolicy.REPLACE, req)
    }

    /** Only the signed-in user's own (or unassigned) tasks and appointments remind on this phone. */
    suspend fun mine(app: AppContainer): Pair<List<TaskItem>, List<Appointment>> {
        val me = app.crm.session.user.id
        val tasks = app.crm.writer.loadAll<TaskItem>(EntityType.TASK).filter { it.assignedAgentId == null || it.assignedAgentId == me }
        val appointments = app.crm.writer.loadAll<Appointment>(EntityType.APPOINTMENT).filter { it.agentId == null || it.agentId == me }
        return tasks to appointments
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("reminders-next")
        Notifications.cancelAll(context)
    }
}
