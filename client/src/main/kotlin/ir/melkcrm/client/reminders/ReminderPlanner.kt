package ir.melkcrm.client.reminders

import ir.melkcrm.core.model.Appointment
import ir.melkcrm.core.model.AppointmentStatus
import ir.melkcrm.core.model.Customer
import ir.melkcrm.core.model.TaskItem
import ir.melkcrm.core.model.TaskStatus
import ir.melkcrm.core.time.EpochDay
import ir.melkcrm.core.time.EpochMillis

data class Reminder(val key: String, val kind: Kind, val entityId: String, val title: String, val at: EpochMillis, val eventAt: EpochMillis) {
    enum class Kind { TASK, APPOINTMENT }
}

/**
 * Decides which reminders fall due in a time window. The Android worker runs periodically and shows
 * the reminders due since its previous run, so nothing is shown twice and nothing is skipped when
 * the device was off (late reminders are still shown).
 */
object ReminderPlanner {
    fun due(tasks: List<TaskItem>, appointments: List<Appointment>, after: EpochMillis, upTo: EpochMillis): List<Reminder> {
        val out = mutableListOf<Reminder>()
        for (t in tasks) {
            if (t.deleted || t.status != TaskStatus.OPEN) continue
            val at = t.reminderAt ?: continue
            if (at in (after + 1)..upTo) out += Reminder("task:${t.id}:$at", Reminder.Kind.TASK, t.id, t.title, at, at)
        }
        for (a in appointments) {
            if (a.deleted || a.status != AppointmentStatus.SCHEDULED) continue
            val minutes = a.reminderMinutesBefore ?: continue
            val at = a.startsAt - minutes * 60_000L
            if (at in (after + 1)..upTo && a.startsAt > after) out += Reminder("appt:${a.id}:$at", Reminder.Kind.APPOINTMENT, a.id, a.title, at, a.startsAt)
        }
        return out.sortedBy { it.at }
    }

    /** Open tasks past their due date plus customers whose follow-up date has passed. */
    fun overdueCount(tasks: List<TaskItem>, customers: List<Customer>, today: EpochDay): Int =
        tasks.count { !it.deleted && it.isOverdue(today) } +
            customers.count { !it.deleted && it.mergedIntoId == null && !it.isInactive && (it.nextFollowUpDate ?: Long.MAX_VALUE) < today }
}
