package ir.melkcrm.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import ir.melkcrm.app.MainActivity
import ir.melkcrm.app.R
import ir.melkcrm.app.data.LocaleStore
import ir.melkcrm.client.reminders.Reminder
import ir.melkcrm.core.time.DateFormatter
import ir.melkcrm.core.time.DisplaySettings

/** Local notifications only. Nothing is ever sent to customers from here. */
object Notifications {
    private const val CHANNEL_REMINDERS = "reminders"
    const val EXTRA_ROUTE = "route"

    fun createChannels(context: Context) {
        val ctx = LocaleStore.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, ctx.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showReminder(context: Context, r: Reminder, display: DisplaySettings) {
        if (!canNotify(context)) return
        val ctx = LocaleStore.wrap(context)
        val route = when (r.kind) {
            Reminder.Kind.TASK -> "task/${r.entityId}"
            Reminder.Kind.APPOINTMENT -> "appointment/${r.entityId}"
        }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, r.key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = ctx.getString(if (r.kind == Reminder.Kind.TASK) R.string.reminder_task else R.string.reminder_appointment)
        val text = r.title + " · " + DateFormatter.formatDateTime(r.eventAt, display)
        val n = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(r.key.hashCode(), n)
    }

    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }
}
