package com.lifeos.ai.domain.usecase

import com.lifeos.ai.domain.model.RepeatType
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure scheduling math for reminders. Repeats are computed in the device's time zone using
 * wall-clock arithmetic, so "every day at 7:00" stays at 7:00 across DST changes and when the
 * user travels between time zones (the base is re-anchored in the new zone).
 */
object ReminderScheduleCalculator {

    /**
     * Returns the first trigger time strictly after [now], or null when the reminder does not
     * repeat and its time has already passed.
     */
    fun nextTrigger(
        scheduledAt: Long,
        repeatType: RepeatType,
        intervalDays: Int,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (scheduledAt > now) return scheduledAt
        if (repeatType == RepeatType.NONE) return null
        val base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(scheduledAt), zone)
        val periodApproxMs: Long = when (repeatType) {
            RepeatType.DAILY -> DAY
            RepeatType.WEEKLY -> 7 * DAY
            RepeatType.MONTHLY -> 28 * DAY
            RepeatType.CUSTOM -> intervalDays.coerceAtLeast(1) * DAY
            RepeatType.NONE -> return null
        }
        // Jump close to `now` first, then step forward. Using base.plusX(k) (not repeated
        // additions) avoids month-end drift (Jan 31 -> Feb 28 -> Mar 31).
        var k = ((now - scheduledAt) / periodApproxMs - 1).coerceAtLeast(1)
        while (true) {
            val candidate = advance(base, repeatType, intervalDays, k).toInstant().toEpochMilli()
            if (candidate > now) {
                // Make sure we didn't skip an earlier valid occurrence because of the approximation.
                var best = candidate
                var j = k - 1
                while (j >= 1) {
                    val earlier = advance(base, repeatType, intervalDays, j).toInstant().toEpochMilli()
                    if (earlier > now) { best = earlier; j-- } else break
                }
                return best
            }
            k++
        }
    }

    private fun advance(base: ZonedDateTime, type: RepeatType, interval: Int, k: Long): ZonedDateTime =
        when (type) {
            RepeatType.DAILY -> base.plusDays(k)
            RepeatType.WEEKLY -> base.plusWeeks(k)
            RepeatType.MONTHLY -> base.plusMonths(k)
            RepeatType.CUSTOM -> base.plusDays(k * interval.coerceAtLeast(1))
            RepeatType.NONE -> base
        }

    private const val DAY = 24L * 60 * 60 * 1000
}
