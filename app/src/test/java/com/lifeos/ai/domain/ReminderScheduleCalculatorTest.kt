package com.lifeos.ai.domain

import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.usecase.ReminderScheduleCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderScheduleCalculatorTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val ny = ZoneId.of("America/New_York")

    private fun at(s: String, zone: ZoneId = ist) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long, zone: ZoneId = ist) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    @Test fun `future one-time reminder triggers at its time`() {
        val t = at("2026-11-07T07:00")
        assertEquals(t, ReminderScheduleCalculator.nextTrigger(t, RepeatType.NONE, 1, at("2026-11-06T22:00"), ist))
    }

    @Test fun `past one-time reminder does not trigger`() {
        assertNull(ReminderScheduleCalculator.nextTrigger(at("2026-11-05T07:00"), RepeatType.NONE, 1, at("2026-11-06T08:00"), ist))
    }

    @Test fun `daily reminder rolls forward to next day at same time`() {
        val next = ReminderScheduleCalculator.nextTrigger(at("2026-11-01T07:00"), RepeatType.DAILY, 1, at("2026-11-06T08:00"), ist)!!
        assertEquals(LocalDateTime.parse("2026-11-07T07:00"), local(next))
    }

    @Test fun `daily reminder later today stays today`() {
        val next = ReminderScheduleCalculator.nextTrigger(at("2026-11-01T21:00"), RepeatType.DAILY, 1, at("2026-11-06T08:00"), ist)!!
        assertEquals(LocalDateTime.parse("2026-11-06T21:00"), local(next))
    }

    @Test fun `weekly reminder keeps weekday`() {
        val next = ReminderScheduleCalculator.nextTrigger(at("2026-10-05T09:30"), RepeatType.WEEKLY, 1, at("2026-11-06T08:00"), ist)!!
        assertEquals(LocalDateTime.parse("2026-11-09T09:30"), local(next)) // Mondays
    }

    @Test fun `monthly reminder on the 31st does not drift`() {
        // Jan 31 -> Feb 28 -> Mar 31 (computed from base, not by chaining)
        val base = at("2026-01-31T10:00")
        val feb = ReminderScheduleCalculator.nextTrigger(base, RepeatType.MONTHLY, 1, at("2026-02-01T00:00"), ist)!!
        assertEquals(LocalDateTime.parse("2026-02-28T10:00"), local(feb))
        val mar = ReminderScheduleCalculator.nextTrigger(base, RepeatType.MONTHLY, 1, at("2026-03-01T00:00"), ist)!!
        assertEquals(LocalDateTime.parse("2026-03-31T10:00"), local(mar))
    }

    @Test fun `custom interval every 3 days`() {
        val next = ReminderScheduleCalculator.nextTrigger(at("2026-11-01T06:00"), RepeatType.CUSTOM, 3, at("2026-11-06T08:00"), ist)!!
        assertEquals(LocalDateTime.parse("2026-11-07T06:00"), local(next))
    }

    @Test fun `daily reminder keeps wall-clock time across DST change`() {
        // US DST ended on 2026-11-01. A 07:00 daily reminder must still fire at 07:00 local.
        val base = at("2026-10-28T07:00", ny)
        val next = ReminderScheduleCalculator.nextTrigger(base, RepeatType.DAILY, 1, at("2026-11-02T08:00", ny), ny)!!
        assertEquals(LocalDateTime.parse("2026-11-03T07:00"), local(next, ny))
    }

    @Test fun `long-running daily reminder computes efficiently and correctly`() {
        val next = ReminderScheduleCalculator.nextTrigger(at("2020-01-01T07:00"), RepeatType.DAILY, 1, at("2026-11-06T08:00"), ist)!!
        assertEquals(LocalDateTime.parse("2026-11-07T07:00"), local(next))
    }
}
