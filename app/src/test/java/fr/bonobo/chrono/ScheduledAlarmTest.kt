// SPDX-License-Identifier: GPL-3.0-or-later
package fr.bonobo.chrono

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduledAlarmTest {
    private lateinit var originalZone: TimeZone

    @Before fun setZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @After fun restoreZone() { TimeZone.setDefault(originalZone) }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris")).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    @Test fun oneTimeAlarmMovesToTomorrowAfterItsHour() {
        val alarm = ScheduledAlarm(1, 7, 30)
        assertEquals(at(2026, 10, 26, 7, 30), alarm.nextAfter(at(2026, 10, 25, 8, 0)))
    }

    @Test fun repeatingAlarmSkipsUnselectedDays() {
        val alarm = ScheduledAlarm(1, 7, 30, setOf(Calendar.MONDAY, Calendar.FRIDAY))
        assertEquals(at(2026, 10, 30, 7, 30), alarm.nextAfter(at(2026, 10, 26, 8, 0)))
    }

    @Test fun exactCurrentMinuteIsNotRescheduledImmediately() {
        val alarm = ScheduledAlarm(1, 7, 30, setOf(Calendar.MONDAY))
        assertEquals(at(2026, 11, 2, 7, 30), alarm.nextAfter(at(2026, 10, 26, 7, 30)))
    }
}
