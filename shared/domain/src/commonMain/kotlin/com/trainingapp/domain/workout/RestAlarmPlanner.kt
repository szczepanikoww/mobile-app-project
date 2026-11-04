package com.trainingapp.domain.workout

/** What to do with the rest-end system notification after a change to the workout (decision D7). */
sealed interface RestAlarmCommand {
    data class Schedule(val workoutLogId: String, val endsAtEpochMs: Long) : RestAlarmCommand
    data class Cancel(val workoutLogId: String) : RestAlarmCommand
    data object None : RestAlarmCommand
}

/**
 * Decides when the notification is scheduled, moved or cancelled. The in-app bar and the notification read the same
 * stored end time, so there is never a second countdown.
 */
object RestAlarmPlanner {
    /** After [before] became [after]: a new or moved rest schedules; a removed rest (skip, early tick, end) cancels. */
    fun onChange(before: WorkoutLog?, after: WorkoutLog?, nowEpochMs: Long): RestAlarmCommand {
        val id = after?.id ?: before?.id ?: return RestAlarmCommand.None
        val old = before?.rest
        val new = after?.rest
        return when {
            new == null && old == null -> RestAlarmCommand.None
            new == null -> RestAlarmCommand.Cancel(id)
            new.isOver(nowEpochMs) -> RestAlarmCommand.Cancel(id)
            old?.endsAtEpochMs == new.endsAtEpochMs -> RestAlarmCommand.None
            else -> RestAlarmCommand.Schedule(id, new.endsAtEpochMs)
        }
    }

    /**
     * When a workout is restored (app reopened, also after a reboot): a rest that has not ended yet is scheduled
     * again once; an ended one is cancelled.
     */
    fun onRestore(log: WorkoutLog, nowEpochMs: Long): RestAlarmCommand {
        val rest = log.rest ?: return RestAlarmCommand.None
        return if (rest.isOver(nowEpochMs)) RestAlarmCommand.Cancel(log.id) else RestAlarmCommand.Schedule(log.id, rest.endsAtEpochMs)
    }

    /** The rest ended while the app is in the foreground: the in-app bar gives the feedback, so no notification. */
    fun onEndedInForeground(log: WorkoutLog): RestAlarmCommand = RestAlarmCommand.Cancel(log.id)
}
